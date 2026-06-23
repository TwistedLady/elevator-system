package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import pl.feelcodes.elevator.common.core.*
import pl.feelcodes.elevator.common.dto.ElevatorStateDto

import scala.concurrent.duration.*

object Controller {
  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Controller")

  // --- public protocol: what other actors are allowed to send ---
  sealed trait Command

  final case class AddRequest(request: ElevatorOrder) extends Command

  // --- internal protocol: timer ticks + the Operator's reports.
  //     private[app] hides it from outside the module, while staying reachable
  //     by the composition root that wires the Operator's report ports. ---
  private[app] sealed trait Internal extends Command

  private[app] case object Tick extends Internal

  private[app] final case class MoveExecuted(state: ElevatorState,
                                             orderWithCommand: OrderElevatorCommand) extends Internal

  /** The Operator reports the car has come to a stop (we asked it to, having no more requests). */
  private[app] final case class Stopped(state: ElevatorState) extends Internal


  sealed trait Event

  final case class RequestAdded(request: ElevatorOrder) extends Event

  final case class WaitingSet(waiting: Boolean) extends Event

  final case class ElevatorStateUpdated(state: ElevatorState, orderWithCommand: OrderElevatorCommand) extends Event


  final case class State(waiting: Boolean,
                         elevatorName: ElevatorName,
                         elevatorState: ElevatorState,
                         requests: Set[ElevatorOrder])

  /** Synthetic order used when persisting a stop (no order is being served). */
  private def idleStop(floor: Floor): OrderElevatorCommand =
    OrderElevatorCommand(ElevatorOrder("", floor), Command.Stop())

  private def toDto(elevatorName: String, state: ElevatorState, tag: String): ElevatorStateDto =
    ElevatorStateDto(
      tag = tag,
      elevatorName = elevatorName,
      direction = state.direction.toString,
      motion = state.motion.toString,
      floor = state.floor.num
    )

  def apply(elevatorName: String,
            operatorProvider: (elevatorName: String) => EntityRef[Operator.Command],
            coordinatorProvider: (elevatorName: String) => EntityRef[Coordinator.Command],
            publish: ElevatorStateDto => Unit): Behavior[Command] =
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
              // The Controller owns publishing the state. Reaching a floor serves EVERY order
              // waiting there (merge by floor); tell the Coordinator the floor to confirm them all.
              val servedHere = state.requests.exists(_.floor == newState.floor)
              Effect
                .persist(WaitingSet(false), ElevatorStateUpdated(newState, orderWithCommand))
                .thenRun { s =>
                  publish(toDto(s.elevatorName, newState, orderWithCommand.order.tag))
                  if servedHere then
                    coordinatorProvider(s.elevatorName) ! Coordinator.Reached(newState.floor.num)
                }

            case Stopped(newState) =>
              // The car came to rest after we ran out of requests — persist and publish it.
              Effect
                .persist(WaitingSet(false), ElevatorStateUpdated(newState, idleStop(newState.floor)))
                .thenRun(s => publish(toDto(s.elevatorName, newState, "")))

            case Tick =>
              if state.waiting then Effect.none
              else if state.requests.nonEmpty then
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
              else if state.elevatorState.motion == Motion.Moving then
                // No requests left but the car is still moving -> tell the Operator to stop it.
                Effect
                  .persist(WaitingSet(true))
                  .thenRun { s =>
                    operatorProvider(s.elevatorName) ! Operator.Stop(s.elevatorName, s.elevatorState)
                  }
              else Effect.none
        ,
        eventHandler = (state, evt) =>
          evt match
            case RequestAdded(request) =>
              state.copy(requests = state.requests + request)

            case WaitingSet(waiting) =>
              state.copy(waiting = waiting)

            case ElevatorStateUpdated(newState, _) =>
              // Drop every request waiting at the floor the car is now on — they are all served.
              val reqs = state.requests.filterNot(_.floor == newState.floor)
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
