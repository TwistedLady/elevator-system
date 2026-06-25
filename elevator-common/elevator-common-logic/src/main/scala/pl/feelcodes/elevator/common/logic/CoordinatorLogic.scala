package pl.feelcodes.elevator.common.logic

import pl.feelcodes.elevator.common.core.ElevatorOrder

object CoordinatorLogic:
  final case class State()
  object State:
    val empty: State = State()

  def mergeByFloor(orders: List[ElevatorOrder]): Set[ElevatorOrder] =
    orders.distinctBy(_.floor).toSet
