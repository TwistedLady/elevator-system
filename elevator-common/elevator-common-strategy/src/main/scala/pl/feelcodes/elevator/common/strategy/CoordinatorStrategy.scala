package pl.feelcodes.elevator.common.strategy

import pl.feelcodes.elevator.common.core.ElevatorOrder

object CoordinatorStrategy:
  def mergeByFloor(orders: List[ElevatorOrder]): Set[ElevatorOrder] =
    orders.distinctBy(_.floor).toSet
