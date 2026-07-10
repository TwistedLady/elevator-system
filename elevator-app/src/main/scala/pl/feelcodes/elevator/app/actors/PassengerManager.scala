package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import pl.feelcodes.elevator.common.protocol.PassengerProtocol
import pl.feelcodes.elevator.common.events.PassengerEvents
import pl.feelcodes.elevator.common.events.PassengerEvents.{CallForwarded, CallHeld, Freed}
import pl.feelcodes.elevator.common.logic.PassengerLogic

/** Enforces one lift per passenger: forwards a free passenger's call to the Manager, freezes calls
  * that arrive while busy, and releases the next frozen call when the current travel is done. */
object PassengerManager:
  export PassengerProtocol.*

  type State = PassengerLogic.State

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("PassengerManager")

  def apply(passengerId: String,
            managerProvider: String => EntityRef[Manager.Command]): Behavior[Command] =
    EventSourcedBehavior[Command, PassengerEvents.Event, State](
      persistenceId = PersistenceId.of(TypeKey.name, passengerId),
      emptyState = PassengerLogic.State.empty,
      commandHandler = (state, msg) =>
        msg match
          case Route(elevatorName, call) =>
            if state.busy then Effect.persist(CallHeld(elevatorName, call))
            else
              Effect.persist(CallForwarded(elevatorName, call)).thenRun { _ =>
                managerProvider(elevatorName) ! Manager.Combine(List(call))
              }

          case Free(pid) =>
            state.held.headOption match
              case Some(head) =>
                Effect.persist(Freed(pid)).thenRun { _ =>
                  managerProvider(head.elevatorName) ! Manager.Combine(List(head.call))
                }
              case None =>
                Effect.persist(Freed(pid))
      ,
      eventHandler = PassengerLogic.evolve
    ).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
