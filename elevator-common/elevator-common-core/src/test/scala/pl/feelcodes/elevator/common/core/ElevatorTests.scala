package pl.feelcodes.elevator.common.core

import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.common.core.Direction.*
import pl.feelcodes.elevator.common.core.Motion.*
import pl.feelcodes.elevator.common.core.Command.*

/**
 * Mechanics of a single elevator: does `move` change floor / direction / motion correctly?
 * Pure, no infrastructure. Uses the FAST engine so the burn() loop is negligible.
 */
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


/**
 * The brain: Policy.next decides command + which order to serve next, SCAN-style.
 * Read each test as a truth-table row:  (floor, direction, orders) -> (command, target).
 */
final class PolicyTests extends AnyFunSuite:

  private def order(tag: String, floor: Int): ElevatorOrder =
    ElevatorOrder(tag, Floor(floor))

  test("order above while going Up -> Go(Up), target that order"):
    val result = Policy.next(Floor(0), Up, Set(order("a", 3)))
    assert(result.command == Go(Up))
    assert(result.order == order("a", 3))

  test("order at current floor -> Stop(), target that order"):
    val result = Policy.next(Floor(2), Up, Set(order("a", 2)))
    assert(result.command == Stop())
    assert(result.order == order("a", 2))

  test("only order is behind us (below while Up) -> swap to Go(Down)"):
    val result = Policy.next(Floor(5), Up, Set(order("a", 2)))
    assert(result.command == Go(Down))
    assert(result.order == order("a", 2))

  test("several orders above while Up -> serve the NEAREST first"):
    val result = Policy.next(Floor(0), Up, Set(order("far", 5), order("near", 2)))
    assert(result.command == Go(Up))
    assert(result.order == order("near", 2))

  test("order below while going Down -> keep Go(Down), serve highest below"):
    val result = Policy.next(Floor(5), Down, Set(order("a", 2), order("b", 1)))
    assert(result.command == Go(Down))
    assert(result.order == order("a", 2))

  test("empty orders is illegal -> Policy.next rejects it"):
    assertThrows[IllegalArgumentException](Policy.next(Floor(0), Up, Set.empty))
