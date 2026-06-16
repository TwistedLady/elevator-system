package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import pl.feelcodes.elevator.common.core.*

import scala.concurrent.duration.*

object Controller {
  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Controller")

  sealed trait Command

  final case class ToProcess(request: ElevatorOrder) extends Command

  final case class ConfirmProcessed(elevator: Elevator, orderWithCommand: OrderElevatorCommand) extends Command

  case object Tick extends Command


  sealed trait Event

  final case class RequestAdded(request: ElevatorOrder) extends Event

  final case class WaitingSet(waiting: Boolean) extends Event

  final case class ElevatorStateUpdated(state: ElevatorState, orderWithCommand: OrderElevatorCommand) extends Event


  final case class State(waiting: Boolean,
                         elevatorName: ElevatorName,
                         elevatorState: ElevatorState,
                         requests: Set[ElevatorOrder])

  def apply(elevatorName: String,
            operatorProvider: (elevatorName: String) => EntityRef[Operator.Command]): Behavior[Command] =
    Behaviors.withTimers { timers =>
      timers.startTimerAtFixedRate(Tick, 500.millis)

      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.of(TypeKey.name, elevatorName),
        emptyState = State(
          waiting = false,
          elevatorName = elevatorName,
          elevatorState = ElevatorState(
            direction = Direction.Up,
            motion = Motion.Stopped,
            floor = Floor(0)
          ),
          requests = Set.empty
        ),
        commandHandler = (state, msg) =>
          msg match
            case ToProcess(request) =>
              if state.requests.exists(_.tag == request.tag) then Effect.none
              else Effect.persist(RequestAdded(request))

            case ConfirmProcessed(elevator, orderWithCommand) =>
              val newState = ElevatorState(
                direction = elevator.direction(),
                motion = elevator.motion(),
                floor = elevator.floor()
              )
              Effect.persist(
                WaitingSet(false),
                ElevatorStateUpdated(newState, orderWithCommand)
              )

            case Tick =>
              if state.waiting || state.requests.isEmpty then Effect.none
              else
                val elevator = Elevator.fast(state.elevatorName)(state.elevatorState)

                val next = Policy.next(
                  orders = state.requests,
                  floor = elevator.floor(),
                  direction = elevator.direction()
                )

                val operator = operatorProvider.apply(elevator.name)

                Effect
                  .persist(WaitingSet(true))
                  .thenRun { s =>
                    val elevator = Elevator.fast(s.elevatorName)(s.elevatorState)
                    operator ! Operator.Do(elevator, next)
                  }
        ,
        eventHandler = (state, evt) =>
          evt match
            case RequestAdded(request) =>
              state.copy(requests = state.requests + request)

            case WaitingSet(waiting) =>
              state.copy(waiting = waiting)

            case ElevatorStateUpdated(newState, orderWithCommand) =>
              val reached = orderWithCommand.order.floor == newState.floor
              val reqs = if reached then state.requests - orderWithCommand.order else state.requests
              state.copy(
                elevatorState = newState,
                requests = reqs
              )
      ).withTagger {
        case _: ElevatorStateUpdated => Set("controller-state")
        case _ => Set.empty
      }
    }
}
