package pl.feelcodes.elevator.common.protocol

import pl.feelcodes.elevator.common.core.*

object ControllerProtocol:
  sealed trait Command
  final case class AddOrder(order: ElevatorOrder) extends Command
  final case class ChooseNextOrder(orders: Set[ElevatorOrder]) extends Command
  final case class PublishState(state: ElevatorState) extends Command
