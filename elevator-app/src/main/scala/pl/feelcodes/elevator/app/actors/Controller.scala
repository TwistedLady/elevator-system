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

  // --- public protocol: what other actors are allowed to send ---
  sealed trait Command

  final case class AddRequest(request: ElevatorOrder) extends Command

  // --- internal protocol: timer ticks + the Operator's report-back.
  //     private[app] hides it from outside the module, while staying reachable
  //     by the composition root that wires the Operator's `report` port. ---
  private[app] sealed trait Internal extends Command

  private[app] case object Tick extends Internal

  private[app] final case class MoveExecuted(state: ElevatorState,
                                             orderWithCommand: OrderElevatorCommand) extends Internal


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
          elevatorState = ElevatorState(Direction.Up, Motion.Stopped, Floor(0)),
          requests = Set.empty
        ),
        commandHandler = (state, msg) =>
          msg match
            case AddRequest(request) =>
              if state.requests.exists(_.tag == request.tag) then Effect.none
              else Effect.persist(RequestAdded(request))

            case MoveExecuted(newState, orderWithCommand) =>
              Effect.persist(
                WaitingSet(false),
                ElevatorStateUpdated(newState, orderWithCommand)
              )

            case Tick =>
              if state.waiting || state.requests.isEmpty then Effect.none
              else
                val next = Policy.next(
                  orders = state.requests,
                  floor = state.elevatorState.floor,
                  direction = state.elevatorState.direction
                )
                Effect
                  .persist(WaitingSet(true))
                  .thenRun { s =>
                    operatorProvider(s.elevatorName) ! Operator.Move(s.elevatorName, s.elevatorState, next)
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
