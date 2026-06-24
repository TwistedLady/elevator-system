package pl.feelcodes.elevator.common.protocol

import pl.feelcodes.elevator.common.core.{Command as _, *}
import pl.feelcodes.elevator.common.protocol.ControllerProtocol.*

object ControllerDecider:
  sealed trait Event
  final case class OrderAdded(order: ElevatorOrder) extends Event
  final case class WaitingSet(waiting: Boolean) extends Event
  final case class ElevatorStateUpdated(state: ElevatorState) extends Event

  final case class State(waiting: Boolean,
                         elevatorName: ElevatorName,
                         elevatorState: ElevatorState,
                         orders: Set[ElevatorOrder])

  def decide(state: State, command: Command): List[Event] =
    command match
      case AddOrder(order) =>
        if state.orders.exists(_.tag == order.tag) then Nil
        else List(OrderAdded(order))

      case PublishState(newState) =>
        List(WaitingSet(false), ElevatorStateUpdated(newState))

      case ChooseNextOrder(orders) =>
        if state.waiting then Nil
        else if orders.nonEmpty || state.elevatorState.motion == Motion.Moving then
          List(WaitingSet(true))
        else Nil

  def evolve(state: State, event: Event): State =
    event match
      case OrderAdded(order) =>
        state.copy(orders = state.orders + order)

      case WaitingSet(waiting) =>
        state.copy(waiting = waiting)

      case ElevatorStateUpdated(newState) =>
        state.copy(
          elevatorState = newState,
          orders = state.orders.filterNot(_.floor == newState.floor)
        )
