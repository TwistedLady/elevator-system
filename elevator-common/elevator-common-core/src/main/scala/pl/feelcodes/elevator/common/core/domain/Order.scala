package pl.feelcodes.elevator.common.core.domain

type OrderId = String

trait HasOrderId:
  def id: OrderId

/** Calls sharing a floor, grouped into one stop; the id is per (elevator, floor).
  * Tracks two passenger tallies: distinct identified passengers and anonymous calls. */
final case class Order(id: OrderId,
                       floor: Floor,
                       callIds: Set[CallId],
                       passengers: Set[PassengerId] = Set.empty,
                       anonymousCallIds: Set[CallId] = Set.empty) extends HasFloorNum, HasOrderId, Ordered[Order]:
  override def compare(that: Order): Int = floor.num.compare(that.floor.num)

  override def num: FloorNum = floor.num

  def passengerCount: Int = passengers.size

  def anonymousCount: Int = anonymousCallIds.size
