package pl.feelcodes.elevator.bi

import org.scalatest.funsuite.AnyFunSuite

/** Unit tests for the pure Mileage fold. */
class MileageSpec extends AnyFunSuite {

  test("first floor sets the baseline and contributes zero mileage") {
    assert(Mileage.update(None, Seq(5)).contains(MileageState(5, 0L)))
  }

  test("mileage is the sum of absolute floor deltas, up and down") {
    val result = Mileage.update(None, Seq(0, 3, 1, 4))
    assert(result.contains(MileageState(4, 8L)))
  }

  test("staying on the same floor adds nothing") {
    assert(Mileage.update(None, Seq(2, 2, 2)).contains(MileageState(2, 0L)))
  }

  test("folding a new batch continues from prior state") {
    val prev = MileageState(lastFloor = 4, floorsTravelled = 8L)
    val result = Mileage.update(Some(prev), Seq(0, 2))
    assert(result.contains(MileageState(2, 14L)))
  }

  test("empty batch with no prior state stays empty") {
    assert(Mileage.update(None, Seq.empty).isEmpty)
  }

  test("empty batch preserves prior state") {
    val prev = MileageState(7, 42L)
    assert(Mileage.update(Some(prev), Seq.empty).contains(prev))
  }
}
