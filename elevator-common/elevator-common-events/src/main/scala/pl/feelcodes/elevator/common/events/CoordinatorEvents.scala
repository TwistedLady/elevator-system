package pl.feelcodes.elevator.common.events

/** Call-lifecycle events owned by the Coordinator: received, assigned to an order, done. */
object CoordinatorEvents:
  sealed trait Event
  final case class CallReceived(callId: String, floor: Int) extends Event
  final case class CallAssigned(callId: String, orderId: String) extends Event
  final case class CallDone(callId: String) extends Event
