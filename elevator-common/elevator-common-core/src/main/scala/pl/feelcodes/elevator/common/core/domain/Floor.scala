package pl.feelcodes.elevator.common.core.domain

type FloorNum = Int

trait HasFloorNum:
  def num: FloorNum

/** A floor, ordered by number; `++` / `--` step one level. */
final case class Floor(num: FloorNum) extends HasFloorNum, Ordered[Floor]:
  def ++ : Floor = Floor(num + 1)

  def -- : Floor = Floor(num - 1)

  override def compare(that: Floor): FloorNum = num.compare(that.num)
