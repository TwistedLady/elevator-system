package pl.feelcodes.elevator.common.protocol

import pl.feelcodes.elevator.common.core.domain.{Command as ElevatorCommand, *}

/** Data-only command for the Operator: run one command on an elevator. */
object OperatorProtocol:
  sealed trait Command
  final case class Move(elevatorName: String, state: ElevatorState, command: ElevatorCommand) extends Command
