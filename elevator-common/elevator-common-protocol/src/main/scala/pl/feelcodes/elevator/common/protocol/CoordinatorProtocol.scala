package pl.feelcodes.elevator.common.protocol

import pl.feelcodes.elevator.common.core.domain.{Call, CallId, OrderId}

/** Data-only commands for the Coordinator: add calls, assign a call to an order, mark a call done. */
object CoordinatorProtocol:
  sealed trait Command
  final case class Handle(calls: List[Call]) extends Command
  final case class AssignOrder(callId: CallId, orderId: OrderId) extends Command
  final case class MarkDone(callId: CallId) extends Command
