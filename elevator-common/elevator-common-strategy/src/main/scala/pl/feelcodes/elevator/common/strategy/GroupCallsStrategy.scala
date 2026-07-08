package pl.feelcodes.elevator.common.strategy

import pl.feelcodes.elevator.common.core.domain.*

import java.security.MessageDigest

/** Groups calls that share a floor into one order; the order id is the hash of its sorted call ids. */
trait GroupCallsStrategy:
  def group(calls: List[Call]): Set[Order]

object GroupCallsStrategy:
  val default: GroupCallsStrategy = calls =>
    calls.groupBy(_.floor).map { (floor, sameFloor) =>
      val ids = sameFloor.map(_.id).toSet
      Order(orderId(ids), floor, ids)
    }.toSet

  private def orderId(callIds: Set[CallId]): OrderId =
    val joined = callIds.toList.sorted.mkString("|")
    val bytes = MessageDigest.getInstance("SHA-1").digest(joined.getBytes("UTF-8"))
    bytes.take(8).map("%02x".format(_)).mkString
