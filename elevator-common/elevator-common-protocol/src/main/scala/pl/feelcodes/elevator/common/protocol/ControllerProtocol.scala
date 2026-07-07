package pl.feelcodes.elevator.common.protocol

import pl.feelcodes.elevator.common.core.domain.*

object ControllerProtocol:
  sealed trait Command
  final case class AddUniqueOrderSet(orders: Set[ElevatorOrder]) extends Command
  final case class ChooseNextOrder(orders: Set[ElevatorOrder]) extends Command
  final case class PublishState(state: ElevatorState) extends Command
  case object RedeliverStuckMove extends Command
