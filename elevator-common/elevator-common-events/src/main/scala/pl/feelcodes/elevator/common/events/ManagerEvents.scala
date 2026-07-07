package pl.feelcodes.elevator.common.events

/** Order-lifecycle events owned by the Manager: an order created from calls, and completed. */
object ManagerEvents:
  sealed trait Event
  final case class OrderCreated(orderId: String, floor: Int, callIds: Set[String]) extends Event
  final case class OrderDone(orderId: String) extends Event
