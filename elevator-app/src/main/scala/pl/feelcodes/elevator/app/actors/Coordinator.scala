package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EffectBuilder, EventSourcedBehavior}
import pl.feelcodes.elevator.common.core.{ElevatorOrder, Floor}
import pl.feelcodes.elevator.common.dto.ElevatorOrderDto
import pl.feelcodes.elevator.common.protocol.CoordinatorProtocol

/**
 * Pekko shell around [[CoordinatorProtocol]]. One per elevator (cluster-sharded, event-sourced).
 * Accepts orders and confirms them; the decide/evolve logic is the pure core in the protocol.
 *
 * Its `Command` and `Ack` stay here (not in the protocol module) because `Process` carries a Pekko
 * `ActorRef[Ack]` (the ask reply-to) and a DTO. The shell maps each command to a pure
 * `CoordinatorProtocol.Decision` before deciding, then runs the side effects (forward to the
 * Controller, ack the sender).
 *
 * Dedup is NOT done here — it happens durably at ingestion, so the Coordinator only ever sees
 * first-time orders. It groups outstanding orders by target floor and, when the Controller reports
 * the car reached a floor, confirms EVERY order waiting there at once (co-floor orders are merged).
 */
object Coordinator:
  export CoordinatorProtocol.*

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Coordinator")

  sealed trait Command
  final case class Process(dto: ElevatorOrderDto, replyTo: ActorRef[Ack]) extends Command

  /** Fire-and-forget: the Controller reports the car reached this floor. Confirm every order
    * still waiting for that floor. */
  private[app] final case class Reached(floor: Int) extends Command

  sealed trait Ack
  object Ack:
    case object Ok extends Ack

  def apply(elevatorName: String,
            controllerProvider: String => EntityRef[Controller.Command]): Behavior[Command] =
    EventSourcedBehavior[Command, Event, State](
      // PersistenceId.of (entityType|entityId) — not ofUniqueId — so the journal records an
      // entity_type and OrderStatusProjection can stream these events by slice.
      persistenceId = PersistenceId.of(TypeKey.name, elevatorName),
      emptyState = State.empty,
      commandHandler = commandHandler(elevatorName, controllerProvider),
      eventHandler = CoordinatorProtocol.evolve
    )

  private def commandHandler(elevatorName: String,
                             controllerProvider: String => EntityRef[Controller.Command])
                            (state: State, cmd: Command): Effect[Event, State] =
    cmd match
      case Process(dto, replyTo) =>
        // Idempotent accept (decide returns no event for an already-outstanding tag). Even then we
        // still (re)forward to the Controller and ack, so a handoff lost in a crash window is healed
        // and the Kafka offset advances.
        val events = CoordinatorProtocol.decide(state, Accept(dto.tag, dto.elevatorName, dto.floor))
        val effect: EffectBuilder[Event, State] =
          if events.isEmpty then Effect.none else Effect.persist(events)
        effect.thenRun { _ =>
          controllerProvider(elevatorName) ! Controller.AddRequest(ElevatorOrder(dto.tag, Floor(dto.floor)))
          replyTo ! Ack.Ok
        }

      case Reached(floor) =>
        val events = CoordinatorProtocol.decide(state, Reach(floor))
        if events.isEmpty then Effect.none else Effect.persist(events)
