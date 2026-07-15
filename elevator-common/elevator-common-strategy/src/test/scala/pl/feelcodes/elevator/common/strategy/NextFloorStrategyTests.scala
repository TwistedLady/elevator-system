package pl.feelcodes.elevator.common.strategy

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import pl.feelcodes.elevator.common.core.domain.*
import pl.feelcodes.elevator.common.core.domain.Direction.*
import pl.feelcodes.elevator.common.core.domain.Command.*

/** NextFloor SCAN: keep moving while a target lies ahead, reverse only when none does, stop on
  * arrival. Includes the straddle case (targets on both sides) that separates SCAN from nearest. */
final class NextFloorStrategyTests extends AnyFunSuite with ScalaCheckPropertyChecks:
  import NextFloorStrategy.default.next as choose

  test("target above while going Up -> Go(Up)"):
    assert(choose(Floor(0), Up, Set(Floor(3))) == Go(Up))

  test("target at current floor -> Stop() even with others still pending"):
    assert(choose(Floor(2), Up, Set(Floor(2), Floor(5))) == Stop())

  test("only target is behind us (below while Up) -> swap to Go(Down)"):
    assert(choose(Floor(5), Up, Set(Floor(2))) == Go(Down))

  test("no targets -> Stop()"):
    assert(choose(Floor(0), Up, Set.empty) == Stop())

  test("straddle: with targets both ahead and behind, keep going (SCAN, not nearest-first)"):
    assert(choose(Floor(3), Up,   Set(Floor(2), Floor(5))) == Go(Up))
    assert(choose(Floor(3), Down, Set(Floor(2), Floor(5))) == Go(Down))

  private val floors: Gen[Floor]         = Gen.choose(-20, 20).map(Floor(_))
  private val dirs: Gen[Direction]       = Gen.oneOf(Up, Down)
  private val targetSets: Gen[Set[Floor]] = Gen.listOf(floors).map(_.toSet)

  test("spec: Stop at a target, else advance if any target is ahead, else reverse, else Stop"):
    forAll(floors, dirs, targetSets) { (current, dir, targets) =>
      val ahead = dir match
        case Up   => targets.exists(_.num > current.num)
        case Down => targets.exists(_.num < current.num)
      val expected =
        if targets.contains(current) then Stop()
        else if ahead then Go(dir)
        else if targets.nonEmpty then Go(dir.swap)
        else Stop()
      assert(choose(current, dir, targets) == expected)
    }

  test("never idles while targets remain and we are not standing on one"):
    forAll(floors, dirs, targetSets) { (current, dir, targets) =>
      if targets.nonEmpty && !targets.contains(current) then
        assert(choose(current, dir, targets) != Stop())
    }
