package pl.feelcodes.elevator.common.events

import pl.feelcodes.elevator.common.core.domain.Call

/** Busy-state events owned by the PassengerManager: a call forwarded to a lift (passenger now busy),
  * a call frozen while busy, the passenger freed (releases the next frozen call, if any). */
object PassengerEvents:
  sealed trait Event
  final case class CallForwarded(elevatorName: String, call: Call) extends Event
  final case class CallHeld(elevatorName: String, call: Call) extends Event
  final case class Freed(passengerId: String) extends Event
