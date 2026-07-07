package pl.feelcodes.elevator.common.core.domain

type CallId = String

trait HasCallId:
  def id: CallId

/** A user action: a request for an elevator to visit a floor. */
final case class Call(id: CallId, floor: Floor) extends HasFloorNum, HasCallId, Ordered[Call]:
  override def compare(that: Call): Int = floor.num.compare(that.floor.num)

  override def num: FloorNum = floor.num
