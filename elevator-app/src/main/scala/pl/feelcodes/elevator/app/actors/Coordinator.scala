package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import pl.feelcodes.elevator.common.core.{ElevatorOrder, Floor}
import pl.feelcodes.elevator.common.dto.ElevatorOrderDto

/**
 * One per elevator (cluster-sharded, event-sourced). Accepts orders and confirms them.
 *
 * Dedup is NOT done here anymore — it happens durably at ingestion (the Kafka stream claims each
 * tag in `processed_orders`), so the Coordinator only ever sees first-time orders. It groups
 * outstanding orders by target floor (`byFloor`) and, when the Controller reports the car reached
 * a floor, confirms EVERY order waiting there at once (co-floor orders are merged).
 */
object Coordinator {
  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Coordinator")

  sealed trait Command
  final case class Process(dto: ElevatorOrderDto, replyTo: ActorRef[Ack]) extends Command

  /** Fire-and-forget: the Controller reports the car reached this floor. Confirm every order
    * still waiting for that floor. */
  private[app] final case class Reached(floor: Int) extends Command

  sealed trait Ack
  object Ack {
    case object Ok extends Ack
  }

  sealed trait Event
  final case class Accepted(tag: String, elevatorName: String, floor: Int) extends Event
  /** Durable confirmation that the order finished. */
  final case class Completed(tag: String) extends Event

  /** Outstanding (accepted-not-completed) tags grouped by target floor. */
  final case class State(byFloor: Map[Int, Set[String]] = Map.empty)

  object State {
    val empty: State = State()
  }

  def apply(elevatorName: String,
            controllerProvider: String => EntityRef[Controller.Command]): Behavior[Command] = {
    EventSourcedBehavior[Command, Event, State](
      // PersistenceId.of (entityType|entityId) — not ofUniqueId — so the journal records an
      // entity_type and OrderStatusProjection can stream these events by slice.
      persistenceId = PersistenceId.of(TypeKey.name, elevatorName),
      emptyState = State.empty,
      commandHandler = commandHandler(elevatorName, controllerProvider),
      eventHandler = eventHandler
    )
  }

  private def commandHandler(elevatorName: String,
                             controllerProvider: String => EntityRef[Controller.Command])
                            (state: State, cmd: Command): Effect[Event, State] = {
    cmd match
      case Process(dto, replyTo) =>
        Effect
          .persist(Accepted(dto.tag, dto.elevatorName, dto.floor))
          .thenRun { _ =>
            controllerProvider(elevatorName) ! Controller.AddRequest(ElevatorOrder(dto.tag, Floor(dto.floor)))
            replyTo ! Ack.Ok
          }

      case Reached(floor) =>
        // Confirm every order still waiting for this floor. A revisited floor has nothing left
        // outstanding (the tags were removed on Completed), so it's a no-op.
        val tags = state.byFloor.getOrElse(floor, Set.empty).toList
        if tags.isEmpty then Effect.none
        else Effect.persist(tags.map(Completed.apply))
  }

  private val eventHandler: (State, Event) => State = { (state, event) =>
    event match
      case Accepted(tag, _, floor) =>
        state.copy(byFloor = state.byFloor.updated(floor, state.byFloor.getOrElse(floor, Set.empty) + tag))

      case Completed(tag) =>
        state.copy(byFloor = removeTag(state.byFloor, tag))
  }

  /** Drop `tag` from whichever floor bucket holds it, removing now-empty buckets. */
  private def removeTag(byFloor: Map[Int, Set[String]], tag: String): Map[Int, Set[String]] =
    byFloor.view.mapValues(_ - tag).filter(_._2.nonEmpty).toMap
}
