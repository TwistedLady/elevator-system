package pl.feelcodes.elevator.common.protocol

import pl.feelcodes.elevator.common.core.domain.*

/** Data-only commands for the Controller: take orders, pick the next stop, publish a new state. */
object ControllerProtocol:
  sealed trait Command
  final case class Process(orders: Set[Order]) extends Command
  final case class ChooseNext(orders: Set[Order]) extends Command
  final case class PublishState(state: ElevatorState) extends Command
