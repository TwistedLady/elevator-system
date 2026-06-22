package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import pl.feelcodes.elevator.common.core.{ElevatorOrder, Floor}
import pl.feelcodes.elevator.common.dto.ElevatorOrderDto

object Coordinator {
  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Coordinator")

  sealed trait Command
  final case class Process(dto: ElevatorOrderDto, replyTo: ActorRef[Ack]) extends Command

  /** Fire-and-forget: the Controller reports that the order with this tag has been served (its car
    * reached the floor). The Coordinator records the confirmation — it owns `OrderCompleted`. */
  private[app] final case class Confirm(tag: String) extends Command

  sealed trait Ack
  object Ack {
    case object Ok extends Ack
  }

  sealed trait Event
  final case class Accepted(tag: String, elevatorName: String, floor: Int) extends Event

  /** Durable confirmation that the order finished. One per tag, so the 5 duplicate submissions of
    * the same order (the Coordinator dedups them) are all covered by this single event. */
  final case class Completed(tag: String) extends Event

  final case class State(seenTags: Set[String],
                         tagOrder: Vector[String],
                         lastAccepted: Option[ElevatorOrderDto],
                         completed: Set[String] = Set.empty,
                         completedOrder: Vector[String] = Vector.empty)

  object State {
    val empty: State = State(
      seenTags = Set.empty,
      tagOrder = Vector.empty,
      lastAccepted = None,
      completed = Set.empty,
      completedOrder = Vector.empty
    )
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
        if state.seenTags.contains(dto.tag) then
          replyTo ! Ack.Ok
          Effect.none
        else
          Effect
            .persist(Accepted(dto.tag, dto.elevatorName, dto.floor))
            .thenRun { _ =>
              val controller = controllerProvider(elevatorName)
              controller ! Controller.AddRequest(ElevatorOrder(dto.tag, Floor(dto.floor)))
              replyTo ! Ack.Ok
            }

      case Confirm(tag) =>
        // The Controller has decided the order is served; record it. Idempotent — the first
        // Confirm persists Completed, repeats (or a Confirm for an already-done tag) are no-ops.
        if state.completed.contains(tag) then Effect.none
        else Effect.persist(Completed(tag))
  }

  private val eventHandler: (State, Event) => State = { (state, event) =>
    event match
      case Accepted(tag, elevatorName, floor) =>
        val nextTagOrder = (state.tagOrder :+ tag)
        val (finalSet, finalOrder) = shrink(state.seenTags + tag, nextTagOrder, maxSize = 10_000)

        state.copy(
          seenTags = finalSet,
          tagOrder = finalOrder,
          lastAccepted = Some(ElevatorOrderDto(tag, elevatorName, floor))
        )

      case Completed(tag) =>
        val (finalSet, finalOrder) =
          shrink(state.completed + tag, state.completedOrder :+ tag, maxSize = 10_000)
        state.copy(completed = finalSet, completedOrder = finalOrder)
  }

  private def shrink(tags: Set[String], order: Vector[String], maxSize: Int): (Set[String], Vector[String]) = {
    if order.size <= maxSize then (tags, order)
    else {
      val toDrop = order.size - maxSize
      val dropped = order.take(toDrop)
      val nextOrder = order.drop(toDrop)
      val nextTags = tags -- dropped
      (nextTags, nextOrder)
    }
  }
}
