package pl.feelcodes.elevator.common.protocol

// Reviewed — data-only command for the Operator: Move (run one command on an elevator).
import pl.feelcodes.elevator.common.core.domain.{Command as ElevatorCommand, *}

object OperatorProtocol:
  sealed trait Command
  final case class Move(elevatorName: String, state: ElevatorState, command: ElevatorCommand) extends Command
