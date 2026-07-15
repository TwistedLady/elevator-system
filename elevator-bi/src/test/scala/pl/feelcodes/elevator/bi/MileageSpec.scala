package pl.feelcodes.elevator.bi

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/** Pure Mileage fold: examples plus the defining property — floors travelled is the sum of the
  * absolute consecutive deltas, the last floor is the running baseline, batches continue prior state. */
class MileageSpec extends AnyFunSuite with ScalaCheckPropertyChecks {

  private def sumOfAbsDeltas(floors: Seq[Int]): Long =
    floors.sliding(2).collect { case Seq(a, b) => math.abs(b.toLong - a.toLong) }.sum

  test("first floor sets the baseline and contributes zero mileage") {
    assert(Mileage.update(None, Seq(5)).contains(MileageState(5, 0L)))
  }

  test("mileage is the sum of absolute floor deltas, up and down") {
    assert(Mileage.update(None, Seq(0, 3, 1, 4)).contains(MileageState(4, 8L)))
  }

  test("staying on the same floor adds nothing") {
    assert(Mileage.update(None, Seq(2, 2, 2)).contains(MileageState(2, 0L)))
  }

  test("folding a new batch continues from prior state") {
    val prev = MileageState(lastFloor = 4, floorsTravelled = 8L)
    assert(Mileage.update(Some(prev), Seq(0, 2)).contains(MileageState(2, 14L)))
  }

  test("empty batch with no prior state stays empty") {
    assert(Mileage.update(None, Seq.empty).isEmpty)
  }

  test("empty batch preserves prior state") {
    val prev = MileageState(7, 42L)
    assert(Mileage.update(Some(prev), Seq.empty).contains(prev))
  }

  private val floorSeq: Gen[List[Int]] = Gen.listOf(Gen.choose(0, 30))

  test("property: from no prior state, mileage = sum of |delta| and lastFloor = the final floor") {
    forAll(floorSeq) { floors =>
      val result = Mileage.update(None, floors)
      if (floors.isEmpty) assert(result.isEmpty)
      else {
        assert(result.exists(_.lastFloor == floors.last))
        assert(result.exists(_.floorsTravelled == sumOfAbsDeltas(floors)))
      }
    }
  }

  test("property: a batch continues as if its floors were appended after the prior floor") {
    forAll(Gen.choose(0, 30), Gen.choose(0L, 100000L), floorSeq) { (last, base, batch) =>
      val result = Mileage.update(Some(MileageState(last, base)), batch)
      assert(result.exists(_.floorsTravelled == base + sumOfAbsDeltas(last :: batch)))
      assert(result.exists(_.lastFloor == batch.lastOption.getOrElse(last)))
    }
  }

  test("property: mileage is never negative, and zero exactly when the car never changes floor") {
    forAll(Gen.nonEmptyListOf(Gen.choose(0, 30))) { floors =>
      val travelled = Mileage.update(None, floors).map(_.floorsTravelled).getOrElse(0L)
      assert(travelled >= 0L)
      assert((travelled == 0L) == floors.forall(_ == floors.head))
    }
  }
}
