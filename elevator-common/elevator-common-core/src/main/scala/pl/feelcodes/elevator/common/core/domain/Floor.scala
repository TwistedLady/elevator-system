package pl.feelcodes.elevator.common.core

// Reviewed — core domain: floor number type, ordered, with ++/-- navigation.
type FloorNum = Int

trait HasFloorNum:
  def num: FloorNum

final case class Floor(num: FloorNum) extends HasFloorNum, Ordered[Floor]:
  def ++ : Floor = Floor(num + 1)

  def -- : Floor = Floor(num - 1)

  override def compare(that: Floor): FloorNum = num.compare(that.num)
