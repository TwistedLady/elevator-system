package pl.feelcodes.elevator.common.protocol

import pl.feelcodes.elevator.common.core.domain.Floor

object DoormanProtocol:
  sealed trait Command
  final case class Serve(elevatorName: String, floor: Floor) extends Command
  final case class Boarded(elevatorName: String, floor: Floor, passengerId: String) extends Command
  final case class BoardTimeout(elevatorName: String, floor: Floor) extends Command
