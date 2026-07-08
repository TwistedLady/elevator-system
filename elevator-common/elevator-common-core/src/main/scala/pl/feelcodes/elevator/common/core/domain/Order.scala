package pl.feelcodes.elevator.common.core.domain

type OrderId = String

trait HasOrderId:
  def id: OrderId

/** Calls sharing a floor, grouped into one stop; the id is derived from its call ids. */
final case class Order(id: OrderId, floor: Floor, callIds: Set[CallId]) extends HasFloorNum, HasOrderId, Ordered[Order]:
  override def compare(that: Order): Int = floor.num.compare(that.floor.num)

  override def num: FloorNum = floor.num
