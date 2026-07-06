package pl.feelcodes.elevator.common.core.domain

type OrderTag = String

trait HasOrderTag:
  def tag: OrderTag

/** A tagged request to visit a floor, ordered by target floor. */
final case class ElevatorOrder(tag: OrderTag, floor: Floor) extends HasFloorNum, HasOrderTag, Ordered[ElevatorOrder]:
  override def compare(that: ElevatorOrder): Int = this.floor.num.compare(that.floor.num)

  override def num: FloorNum = floor.num
