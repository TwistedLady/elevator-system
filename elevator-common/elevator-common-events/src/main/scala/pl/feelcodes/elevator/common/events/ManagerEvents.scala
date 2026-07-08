package pl.feelcodes.elevator.common.events

/** Order-lifecycle events owned by the Manager: an order created, extended with more calls, done. */
object ManagerEvents:
  sealed trait Event
  final case class OrderCreated(orderId: String, floor: Int, callIds: Set[String]) extends Event
  final case class OrderExtended(orderId: String, callIds: Set[String]) extends Event
  final case class OrderDone(orderId: String) extends Event
