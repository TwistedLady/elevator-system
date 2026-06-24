package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.{PersistenceId, RecoveryCompleted}
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import pl.feelcodes.elevator.common.core.*
import pl.feelcodes.elevator.common.dto.ElevatorStateDto
import pl.feelcodes.elevator.common.protocol.ControllerProtocol

import scala.concurrent.duration.*

/** Pekko shell around [[ControllerProtocol]]. The decision (`decide`) and the state machine
  * (`evolve`) are pure and live in the protocol module; this EventSourcedBehavior wires them into
  * Pekko and runs the side effects the pure core deliberately leaves out: publishing the new state,
  * confirming a reached floor with the Coordinator, re-dispatching the move/stop, and the timer. */
object Controller:
  export ControllerProtocol.*

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Controller")

  def apply(elevatorName: String,
            operatorProvider: (elevatorName: String) => EntityRef[Operator.Command],
            coordinatorProvider: (elevatorName: String) => EntityRef[Coordinator.Command],
            publish: ElevatorStateDto => Unit): Behavior[Command] =
    Behaviors.withTimers { timers =>
      timers.startTimerAtFixedRate(Tick, 500.millis)

      // Issue the move/stop the current state calls for. Used by Tick and — crucially — on
      // RecoveryCompleted: the Move/Stop we send to the (ephemeral, non-persistent) Operator is
      // lost if the node crashes before the Operator reports back. Because `waiting` IS persisted,
      // a plain recovery would replay `waiting=true` and every Tick would short-circuit, freezing
      // the car forever. Re-dispatching here redelivers that lost command; `waiting` stays true so
      // we never issue a duplicate, and the Operator's report clears it as usual.
      def dispatch(s: State): Unit =
        if s.requests.nonEmpty then
          val next = Policy.next(
            orders = s.requests,
            floor = s.elevatorState.floor,
            direction = s.elevatorState.direction
          )
          operatorProvider(s.elevatorName) ! Operator.Move(s.elevatorName, s.elevatorState, next)
        else if s.elevatorState.motion == Motion.Moving then
          operatorProvider(s.elevatorName) ! Operator.Stop(s.elevatorName, s.elevatorState)

      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.of(TypeKey.name, elevatorName),
        emptyState = State(
          waiting = false,
          elevatorName = elevatorName,
          elevatorState = ElevatorState(Direction.Up, Motion.Stopped, Floor(0)),
          requests = Set.empty
        ),
        // Pure decision in the protocol; the per-command side effects stay here in the shell.
        commandHandler = (state, msg) =>
          val events = ControllerProtocol.decide(state, msg)
          msg match
            case MoveExecuted(newState, orderWithCommand) =>
              // Reaching a floor serves EVERY order waiting there (merge by floor); tell the
              // Coordinator the floor to confirm them all.
              val servedHere = state.requests.exists(_.floor == newState.floor)
              Effect.persist(events).thenRun { s =>
                publish(ElevatorStateDto(
                  tag = orderWithCommand.order.tag,
                  elevatorName = s.elevatorName,
                  direction = newState.direction.toString,
                  motion = newState.motion.toString,
                  floor = newState.floor.num
                ))
                if servedHere then
                  coordinatorProvider(s.elevatorName) ! Coordinator.Reached(newState.floor.num)
              }

            case Stopped(newState) =>
              Effect.persist(events).thenRun { s =>
                publish(ElevatorStateDto(
                  tag = "",
                  elevatorName = s.elevatorName,
                  direction = newState.direction.toString,
                  motion = newState.motion.toString,
                  floor = newState.floor.num
                ))
              }

            case Tick =>
              // `decide` returns WaitingSet(true) exactly when we should act; latch and dispatch.
              if events.isEmpty then Effect.none
              else Effect.persist(events).thenRun(dispatch)

            case AddRequest(_) =>
              if events.isEmpty then Effect.none else Effect.persist(events)
        ,
        eventHandler = ControllerProtocol.evolve
      ).receiveSignal {
        // The Move/Stop in flight to the Operator is lost on a crash, but `waiting` was persisted.
        // Redeliver it so the car doesn't freeze waiting for a report that will never come.
        case (state, RecoveryCompleted) if state.waiting => dispatch(state)
      }.withTagger {
        case _: ElevatorStateUpdated => Set("controller-state")
        case _ => Set.empty
      }
    }
