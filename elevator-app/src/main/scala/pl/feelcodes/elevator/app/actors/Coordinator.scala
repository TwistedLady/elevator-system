package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import pl.feelcodes.elevator.common.core.domain.{Call, Floor}
import pl.feelcodes.elevator.common.dto.CallStateDto
import pl.feelcodes.elevator.common.protocol.CoordinatorProtocol
import pl.feelcodes.elevator.common.events.CoordinatorEvents
import pl.feelcodes.elevator.common.events.CoordinatorEvents.{CallAssigned, CallDone, CallReceived}
import pl.feelcodes.elevator.common.logic.CoordinatorLogic

/** Owns call status: receives calls, hands them to the Manager, tracks each call to done. */
object Coordinator:
  export CoordinatorProtocol.*

  type State = CoordinatorLogic.State

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Coordinator")

  def apply(elevatorName: String,
            managerProvider: String => EntityRef[Manager.Command],
            publish: CallStateDto => Unit): Behavior[Command] =
    EventSourcedBehavior[Command, CoordinatorEvents.Event, State](
      persistenceId = PersistenceId.of(TypeKey.name, elevatorName),
      emptyState = CoordinatorLogic.State.empty,
      commandHandler = (state, msg) =>
        msg match
          case AddCalls(calls) =>
            Effect.persist(calls.map(c => CallReceived(c.id, c.floor))).thenRun { _ =>
              calls.foreach(c => publish(CallStateDto(c.id, elevatorName, c.floor, "PROGRESS")))
              managerProvider(elevatorName) ! Manager.Combine(calls.map(c => Call(c.id, Floor(c.floor))))
            }

          case AssignOrder(callId, orderId) =>
            Effect.persist(CallAssigned(callId, orderId))

          case MarkCallDone(callId) =>
            Effect.persist(CallDone(callId)).thenRun { _ =>
              publish(CallStateDto(callId, elevatorName, state.calls.getOrElse(callId, -1), "DONE"))
            }
      ,
      eventHandler = CoordinatorLogic.evolve
    ).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
