package pl.feelcodes.elevator.common.logic

import pl.feelcodes.elevator.common.core.domain.*
import pl.feelcodes.elevator.common.events.ControllerEvents.*
import pl.feelcodes.elevator.common.strategy.NextFloorStrategy

/** Decides elevator movement from the set of pending orders (pure decide/evolve). */
object ControllerLogic:

  final case class State(waiting: Boolean,
                         elevatorName: ElevatorName,
                         elevatorState: ElevatorState,
                         orders: Set[Order])

  object State:
    def initial(elevatorName: ElevatorName): State =
      State(false, elevatorName, ElevatorState(Direction.Up, Motion.Stopped, Floor(0)), Set.empty)

  def addUniqueOrders(state: State, orders: Set[Order]): List[Event] =
    orders.toList.filterNot(o => state.orders.exists(_.id == o.id)).map(OrderAccepted.apply)

  def publishState(newState: ElevatorState): List[Event] =
    List(WaitingSet(false), ElevatorStateUpdated(newState))

  def evolve(state: State, event: Event): State =
    event match
      case OrderAccepted(order)        => state.copy(orders = state.orders + order)
      case WaitingSet(waiting)      => state.copy(waiting = waiting)
      case ElevatorStateUpdated(ns) => state.copy(elevatorState = ns, orders = state.orders.filterNot(_.floor == ns.floor))

  def shouldAct(state: State, orders: Set[Order]): Boolean =
    !state.waiting && (orders.nonEmpty || state.elevatorState.motion == Motion.Moving)

  def nextCommand(state: State, orders: Set[Order]): Option[Command] =
    if orders.nonEmpty then
      Some(NextFloorStrategy.default.next(
        state.elevatorState.floor, state.elevatorState.direction, orders.map(_.floor)))
    else if state.elevatorState.motion == Motion.Moving then Some(Command.Stop())
    else None
