package pl.feelcodes.elevator.common.protocol

import pl.feelcodes.elevator.common.dto.CallDto

/** Data-only commands for the Coordinator: add calls, assign a call to an order, mark a call done. */
object CoordinatorProtocol:
  sealed trait Command
  final case class AddCalls(calls: List[CallDto]) extends Command
  final case class AssignOrder(callId: String, orderId: String) extends Command
  final case class MarkCallDone(callId: String) extends Command
