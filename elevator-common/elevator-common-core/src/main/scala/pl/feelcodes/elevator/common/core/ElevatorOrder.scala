package pl.feelcodes.elevator.common.core

// Reviewed — core domain: an order = tag + target floor, ordered by floor.
type OrderTag = String

trait HasOrderTag:
  def tag: OrderTag

final case class ElevatorOrder(tag: OrderTag, floor: Floor) extends HasFloorNum, HasOrderTag, Ordered[ElevatorOrder]:
  override def compare(that: ElevatorOrder): Int = this.floor.num.compare(that.floor.num)

  override def num: FloorNum = floor.num
