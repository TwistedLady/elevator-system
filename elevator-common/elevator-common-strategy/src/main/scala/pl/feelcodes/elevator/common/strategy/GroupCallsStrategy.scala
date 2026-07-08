package pl.feelcodes.elevator.common.strategy

import pl.feelcodes.elevator.common.core.domain.*

import java.security.MessageDigest

/** Groups calls that share a floor into one order; the order id is per (elevator, floor). */
trait GroupCallsStrategy:
  def group(elevatorName: ElevatorName, calls: List[Call]): Set[Order]

object GroupCallsStrategy:
  val default: GroupCallsStrategy = (elevatorName, calls) =>
    calls.groupBy(_.floor).map { (floor, sameFloor) =>
      Order(orderId(elevatorName, floor), floor, sameFloor.map(_.id).toSet)
    }.toSet

  private def orderId(elevatorName: ElevatorName, floor: Floor): OrderId =
    val bytes = MessageDigest.getInstance("SHA-1").digest(s"$elevatorName|${floor.num}".getBytes("UTF-8"))
    bytes.take(8).map("%02x".format(_)).mkString
