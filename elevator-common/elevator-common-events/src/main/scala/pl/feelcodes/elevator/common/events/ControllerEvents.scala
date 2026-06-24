package pl.feelcodes.elevator.common.events

import pl.feelcodes.elevator.common.core.{ElevatorOrder, ElevatorState}

object ControllerEvents:
  sealed trait Event
  final case class OrderAdded(order: ElevatorOrder) extends Event
  final case class WaitingSet(waiting: Boolean) extends Event
  final case class ElevatorStateUpdated(state: ElevatorState) extends Event
