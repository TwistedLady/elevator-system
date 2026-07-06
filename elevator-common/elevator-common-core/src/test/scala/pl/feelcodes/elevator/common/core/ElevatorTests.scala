package pl.feelcodes.elevator.common.core

import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.common.core.domain.*
import pl.feelcodes.elevator.common.core.domain.Direction.*
import pl.feelcodes.elevator.common.core.domain.Motion.*
import pl.feelcodes.elevator.common.core.domain.Command.*
import pl.feelcodes.elevator.common.core.engine.Elevator

final class ElevatorTests extends AnyFunSuite:

  private def at(floor: Int, dir: Direction = Up, motion: Motion = Stopped): Elevator =
    Elevator.fast("test")(ElevatorState(dir, motion, Floor(floor)))

  test("move up one | floor 0, Go(Up) -> floor 1, Up, Moving"):
    val e = at(0).move(Go(Up))
    assert(e.floor() == Floor(1))
    assert(e.direction() == Up)
    assert(e.motion() == Moving)

  test("move up many | floor 0, Go(Up) x5 -> floor 5"):
    val e = (1 to 5).foldLeft(at(0))((acc, _) => acc.move(Go(Up)))
    assert(e.floor() == Floor(5))

  test("move down one | floor 5, Go(Down) -> floor 4, Down"):
    val e = at(5).move(Go(Down))
    assert(e.floor() == Floor(4))
    assert(e.direction() == Down)

  test("stop | moving at 3, Stop() -> Stopped, floor + direction unchanged"):
    val e = at(3, dir = Up, motion = Moving).move(Stop())
    assert(e.motion() == Stopped)
    assert(e.floor() == Floor(3))
    assert(e.direction() == Up)


