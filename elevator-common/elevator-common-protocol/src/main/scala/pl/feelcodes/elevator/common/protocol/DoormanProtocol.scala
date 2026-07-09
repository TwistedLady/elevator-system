package pl.feelcodes.elevator.common.protocol

import pl.feelcodes.elevator.common.core.domain.Floor

object DoormanProtocol:
  sealed trait Command
  final case class Serve(elevatorName: String, floor: Floor) extends Command
