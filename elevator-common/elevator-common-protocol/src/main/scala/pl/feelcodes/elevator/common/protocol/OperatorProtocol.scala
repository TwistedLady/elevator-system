package pl.feelcodes.elevator.common.protocol

import pl.feelcodes.elevator.common.core.{Command as ElevatorCommand, *}

object OperatorProtocol:

  sealed trait Command

  final case class Move(elevatorName: String, state: ElevatorState, command: ElevatorCommand) extends Command

  final case class Stop(elevatorName: String, state: ElevatorState) extends Command

  type ReportMove = (String, ElevatorState) => Unit
  type ReportStop = (String, ElevatorState) => Unit

  type BuildElevator = (String, ElevatorState) => Elevator

  def afterMove(buildElevator: BuildElevator,
                elevatorName: String,
                state: ElevatorState,
                command: ElevatorCommand): ElevatorState =
    stateOf(buildElevator(elevatorName, state).move(command))

  def afterStop(buildElevator: BuildElevator,
                elevatorName: String,
                state: ElevatorState): ElevatorState =
    stateOf(buildElevator(elevatorName, state).stop())

  private def stateOf(e: Elevator): ElevatorState =
    ElevatorState(e.direction(), e.motion(), e.floor())
