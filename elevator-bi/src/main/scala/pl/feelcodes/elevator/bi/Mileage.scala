package pl.feelcodes.elevator.bi

/** Running mileage state for one elevator: its last observed floor and the total floors travelled. */
final case class MileageState(lastFloor: Int, floorsTravelled: Long)

/** Pure mileage arithmetic — no Spark, fully unit-testable.
  *
  * Mileage = number of floors travelled = Σ |floor - previousFloor| over the ordered stream of
  * floors an elevator reported. The first floor ever seen sets the baseline (contributes 0).
  */
object Mileage {

  /** Fold a batch of floors (already ordered oldest→newest) into the prior state. */
  def update(prev: Option[MileageState], floors: Seq[Int]): Option[MileageState] =
    floors.foldLeft(prev) {
      case (None, floor)      => Some(MileageState(floor, 0L))
      case (Some(st), floor)  => Some(MileageState(floor, st.floorsTravelled + math.abs(floor - st.lastFloor)))
    }
}
