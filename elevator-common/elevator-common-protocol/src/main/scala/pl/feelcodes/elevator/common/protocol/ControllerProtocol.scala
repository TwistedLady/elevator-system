package pl.feelcodes.elevator.common.protocol

// Reviewed — data-only commands for the Controller: AddUniqueOrderSet, ChooseNextOrder, PublishState.
import pl.feelcodes.elevator.common.core.*

object ControllerProtocol:
  sealed trait Command
  final case class AddUniqueOrderSet(orders: Set[ElevatorOrder]) extends Command
  final case class ChooseNextOrder(orders: Set[ElevatorOrder]) extends Command
  final case class PublishState(state: ElevatorState) extends Command
