package pl.feelcodes.elevator.common.core.domain

type CallId = String
type PassengerId = String

trait HasCallId:
  def id: CallId

/** A user action: a request for an elevator to visit a floor, optionally identifying the passenger. */
final case class Call(id: CallId, floor: Floor, passengerId: Option[PassengerId] = None) extends HasFloorNum, HasCallId, Ordered[Call]:
  override def compare(that: Call): Int = floor.num.compare(that.floor.num)

  override def num: FloorNum = floor.num
