package pl.feelcodes.elevator.common.core

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import pl.feelcodes.elevator.common.core.domain.*
import pl.feelcodes.elevator.common.core.domain.Direction.*
import pl.feelcodes.elevator.common.core.domain.Motion.*
import pl.feelcodes.elevator.common.core.domain.Command.*

import scala.concurrent.duration.*

/** Elevator core: move/stop transitions and ElevatorState guards, as ScalaCheck properties over
  * arbitrary floor/direction/motion. A zero-cost engine avoids Engine.burn's real-time sleep. */
final class ElevatorTests extends AnyFunSuite with ScalaCheckPropertyChecks:

  private final case class ZeroCostEngine() extends Engine(0.millis)

  private def at(floor: Int, dir: Direction = Up, motion: Motion = Stopped): Elevator =
    Elevator("test", ZeroCostEngine(), ElevatorState(dir, motion, Floor(floor)))

  private val floors: Gen[Int]     = Gen.choose(-50, 50)
  private val dirs: Gen[Direction] = Gen.oneOf(Up, Down)
  private val motions: Gen[Motion] = Gen.oneOf(Moving, Stopped)

  test("Go(dir) sets direction to dir and motion Moving, whatever the prior direction/motion"):
    forAll(floors, dirs, motions, dirs) { (f, priorDir, priorMotion, go) =>
      val e = at(f, priorDir, priorMotion).move(Go(go))
      assert(e.direction() == go)
      assert(e.motion() == Moving)
    }

  test("Go(Up) increments the floor, Go(Down) decrements it, from any starting floor"):
    forAll(floors, dirs, motions) { (f, d, m) =>
      assert(at(f, d, m).move(Go(Up)).floor()   == Floor(f + 1))
      assert(at(f, d, m).move(Go(Down)).floor() == Floor(f - 1))
    }

  test("Stop() halts: motion becomes Stopped, floor and direction are preserved"):
    forAll(floors, dirs, motions) { (f, d, m) =>
      val e = at(f, d, m).move(Stop())
      assert(e.motion()    == Stopped)
      assert(e.floor()     == Floor(f))
      assert(e.direction() == d)
    }

  test("net displacement equals the signed sum of the moves"):
    forAll(Gen.choose(-20, 20), Gen.listOf(dirs)) { (start, moves) =>
      val e = moves.foldLeft(at(start))((acc, d) => acc.move(Go(d)))
      val expected = start + moves.map { case Up => 1; case Down => -1 }.sum
      assert(e.floor() == Floor(expected))
    }

  test("Go(Down) at the ground floor descends to -1 (Floor has no lower bound)"):
    assert(at(0).move(Go(Down)).floor() == Floor(-1))

  test("stop() sets motion Stopped and leaves floor and direction untouched"):
    forAll(floors, dirs) { (f, d) =>
      val e = at(f, d, Moving).stop()
      assert(e.motion()    == Stopped)
      assert(e.floor()     == Floor(f))
      assert(e.direction() == d)
    }

  test("ElevatorState rejects null direction, motion, or floor"):
    assertThrows[IllegalArgumentException](ElevatorState(null, Stopped, Floor(0)))
    assertThrows[IllegalArgumentException](ElevatorState(Up, null, Floor(0)))
    assertThrows[IllegalArgumentException](ElevatorState(Up, Stopped, null))
