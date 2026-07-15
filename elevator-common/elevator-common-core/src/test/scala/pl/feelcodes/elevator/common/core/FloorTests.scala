package pl.feelcodes.elevator.common.core

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import pl.feelcodes.elevator.common.core.domain.*
import pl.feelcodes.elevator.common.core.domain.Direction.*

/** Pure value algebra: Floor ordering and step operators, and Direction.swap being a self-inverse. */
final class FloorTests extends AnyFunSuite with ScalaCheckPropertyChecks:

  private val nums: Gen[Int] = Gen.choose(-1000, 1000)

  test("Floor ordering agrees with its number on every pair"):
    forAll(nums, nums) { (a, b) =>
      assert(Floor(a).compare(Floor(b)).sign == a.compare(b).sign)
      assert((Floor(a) < Floor(b)) == (a < b))
    }

  test("++ steps up one, -- steps down one, and they are inverses"):
    forAll(nums) { n =>
      assert((Floor(n).++).num == n + 1)
      assert((Floor(n).--).num == n - 1)
      assert(Floor(n).++.-- == Floor(n))
    }

  test("Direction.swap flips and is its own inverse"):
    assert(Up.swap == Down)
    assert(Down.swap == Up)
    forAll(Gen.oneOf(Up, Down))(d => assert(d.swap.swap == d))
