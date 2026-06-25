package pl.feelcodes.elevator.common.events

object CoordinatorEvents:
  sealed trait Event
  final case class OrderAccepted(tag: String, elevatorName: String, floor: Int) extends Event
  final case class OrderDone(tag: String) extends Event
