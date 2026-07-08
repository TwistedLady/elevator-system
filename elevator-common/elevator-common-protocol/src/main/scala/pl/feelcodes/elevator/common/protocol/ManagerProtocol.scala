package pl.feelcodes.elevator.common.protocol

import pl.feelcodes.elevator.common.core.domain.Call

/** Data-only commands for the Manager: combine calls into orders, mark an order done. */
object ManagerProtocol:
  sealed trait Command
  final case class Combine(calls: List[Call]) extends Command
  final case class MarkOrderDone(orderId: String) extends Command
