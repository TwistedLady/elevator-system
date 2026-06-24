package pl.feelcodes.elevator.common.protocol

import pl.feelcodes.elevator.common.core.*

/** The Pekko-free heart of the Controller: its message/event protocol, its persisted [[State]],
  * and the two pure functions of an event-sourced aggregate (the "Decider"):
  *
  *   - [[decide]] : (State, Command) => the events that should be persisted
  *   - [[evolve]] : (State, Event)   => the next state
  *
  * Neither touches Pekko. The actor (in elevator-app) wraps these in an `EventSourcedBehavior`,
  * and adds the side effects that don't belong in the pure core — publishing state, telling the
  * Coordinator a floor was reached, re-dispatching the move/stop, and the timer. */
object ControllerProtocol:

  // --- public protocol: what other actors are allowed to send ---
  sealed trait Command

  final case class AddRequest(request: ElevatorOrder) extends Command

  // --- internal protocol: timer ticks + the Operator's reports. These are sent only by the
  //     actor's own shell (timer) and by the Operator; they are Pekko-free data, so they live
  //     here with the rest of the protocol. ---
  sealed trait Internal extends Command

  case object Tick extends Internal

  final case class MoveExecuted(state: ElevatorState,
                                orderWithCommand: OrderElevatorCommand) extends Internal

  /** The Operator reports the car has come to a stop (we asked it to, having no more requests). */
  final case class Stopped(state: ElevatorState) extends Internal


  sealed trait Event

  final case class RequestAdded(request: ElevatorOrder) extends Event

  final case class WaitingSet(waiting: Boolean) extends Event

  final case class ElevatorStateUpdated(state: ElevatorState, orderWithCommand: OrderElevatorCommand) extends Event


  final case class State(waiting: Boolean,
                         elevatorName: ElevatorName,
                         elevatorState: ElevatorState,
                         requests: Set[ElevatorOrder])

  /** Synthetic order used when persisting a stop (no order is being served). */
  def idleStop(floor: Floor): OrderElevatorCommand =
    OrderElevatorCommand(ElevatorOrder("", floor), Command.Stop())

  /** Pure decision: which events (if any) this command produces. Side effects (publish, notify the
    * Coordinator, dispatch the move) are NOT here — the actor runs them after persisting. */
  def decide(state: State, command: Command): List[Event] =
    command match
      case AddRequest(request) =>
        // Idempotent: a tag already queued produces no event.
        if state.requests.exists(_.tag == request.tag) then Nil
        else List(RequestAdded(request))

      case MoveExecuted(newState, orderWithCommand) =>
        List(WaitingSet(false), ElevatorStateUpdated(newState, orderWithCommand))

      case Stopped(newState) =>
        List(WaitingSet(false), ElevatorStateUpdated(newState, idleStop(newState.floor)))

      case Tick =>
        // Latch `waiting` when there is something to do (requests to serve, or a moving car to
        // stop). The actual move/stop the Tick triggers is a side effect the actor performs.
        if state.waiting then Nil
        else if state.requests.nonEmpty || state.elevatorState.motion == Motion.Moving then
          List(WaitingSet(true))
        else Nil

  /** Pure state machine: fold one event into the state. */
  def evolve(state: State, event: Event): State =
    event match
      case RequestAdded(request) =>
        state.copy(requests = state.requests + request)

      case WaitingSet(waiting) =>
        state.copy(waiting = waiting)

      case ElevatorStateUpdated(newState, _) =>
        // Drop every request waiting at the floor the car is now on — they are all served.
        val reqs = state.requests.filterNot(_.floor == newState.floor)
        state.copy(elevatorState = newState, requests = reqs)
