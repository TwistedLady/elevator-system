package pl.feelcodes.elevator.common.strategy

import pl.feelcodes.elevator.common.core.ElevatorOrder

object CoordinatorStrategy:
  final case class State()
  object State:
    val empty: State = State()

  def mergeByFloor(orders: List[ElevatorOrder]): Set[ElevatorOrder] =
    orders.distinctBy(_.floor).toSet
