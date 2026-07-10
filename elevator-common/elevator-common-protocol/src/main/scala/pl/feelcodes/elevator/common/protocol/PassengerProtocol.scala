package pl.feelcodes.elevator.common.protocol

import pl.feelcodes.elevator.common.core.domain.{Call, ElevatorName, PassengerId}

/** Data-only commands for the PassengerManager: route a call (forward or freeze), free the passenger. */
object PassengerProtocol:
  sealed trait Command
  final case class Route(elevatorName: ElevatorName, call: Call) extends Command
  final case class Free(passengerId: PassengerId) extends Command
