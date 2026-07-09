package pl.feelcodes.elevator.common.protocol

import pl.feelcodes.elevator.common.core.domain.*

/** Data-only commands for the Controller: take orders, pick the next stop, publish a new state. */
object ControllerProtocol:
  sealed trait Command
  final case class Process(orders: Set[Order]) extends Command
  final case class ChooseNext(orders: Set[Order]) extends Command
  final case class MarkExecuted(state: ElevatorState) extends Command
  final case class DoorClosed(floor: Floor) extends Command
  final case class MoveDecision(allowed: Boolean) extends Command
  case object MoveRetry extends Command
