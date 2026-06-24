package pl.feelcodes.elevator.common.strategy

import pl.feelcodes.elevator.common.core.{Command as ElevatorCommand, *}

object OperatorStrategy:
  def afterMove(buildElevator: (String, ElevatorState) => Elevator,
                elevatorName: String,
                state: ElevatorState,
                command: ElevatorCommand): ElevatorState =
    val e = buildElevator(elevatorName, state).move(command)
    ElevatorState(e.direction(), e.motion(), e.floor())
