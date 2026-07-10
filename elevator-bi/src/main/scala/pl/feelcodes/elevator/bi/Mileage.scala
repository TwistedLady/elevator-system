package pl.feelcodes.elevator.bi

/** Pure mileage arithmetic (no Spark): floors travelled = Σ |floor - prevFloor|
  * over an elevator's ordered floor stream; the first floor sets the baseline (0).
  */

final case class MileageState(lastFloor: Int, floorsTravelled: Long)

object Mileage {

  def update(prev: Option[MileageState], floors: Seq[Int]): Option[MileageState] =
    floors.foldLeft(prev) {
      case (None, floor)      => Some(MileageState(floor, 0L))
      case (Some(st), floor)  => Some(MileageState(floor, st.floorsTravelled + math.abs(floor - st.lastFloor)))
    }
}
