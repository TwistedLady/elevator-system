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

  private def at(floor: Int, dir: Direction = UP, motion: Motion = STOPPED): Elevator =
    Elevator.fast("test")(ElevatorState(dir, motion, Floor(floor)))

  test("move up one | floor 0, GO(UP) -> floor 1, UP, MOVING"):
    val e = at(0).move(GO(UP))
    assert(e.floor() == Floor(1))
    assert(e.direction() == UP)
    assert(e.motion() == MOVING)

  test("move up many | floor 0, GO(UP) x5 -> floor 5"):
    val e = (1 to 5).foldLeft(at(0))((acc, _) => acc.move(GO(UP)))
    assert(e.floor() == Floor(5))

  test("move down one | floor 5, GO(DOWN) -> floor 4, DOWN"):
    val e = at(5).move(GO(DOWN))
    assert(e.floor() == Floor(4))
    assert(e.direction() == DOWN)

  test("stop | moving at 3, STOP() -> STOPPED, floor + direction unchanged"):
    val e = at(3, dir = UP, motion = MOVING).move(STOP())
    assert(e.motion() == STOPPED)
    assert(e.floor() == Floor(3))
    assert(e.direction() == UP)


/**
 * The brain: Policy.next decides command + which order to serve next, SCAN-style.
 * Read each test as a truth-table row:  (floor, direction, orders) -> (command, target).
 */
final class PolicyTests extends AnyFunSuite:

  private def order(tag: String, floor: Int): OrderElevator =
    OrderElevator(tag, Floor(floor))

  test("order above while going UP -> GO(UP), target that order"):
    val result = Policy.next(Floor(0), UP, Set(order("a", 3)))
    assert(result.command == GO(UP))
    assert(result.order == order("a", 3))

  test("order at current floor -> STOP(), target that order"):
    val result = Policy.next(Floor(2), UP, Set(order("a", 2)))
    assert(result.command == STOP())
    assert(result.order == order("a", 2))

  test("only order is behind us (below while UP) -> swap to GO(DOWN)"):
    val result = Policy.next(Floor(5), UP, Set(order("a", 2)))
    assert(result.command == GO(DOWN))
    assert(result.order == order("a", 2))

  test("several orders above while UP -> serve the NEAREST first"):
    val result = Policy.next(Floor(0), UP, Set(order("far", 5), order("near", 2)))
    assert(result.command == GO(UP))
    assert(result.order == order("near", 2))

  test("order below while going DOWN -> keep GO(DOWN), serve highest below"):
    val result = Policy.next(Floor(5), DOWN, Set(order("a", 2), order("b", 1)))
    assert(result.command == GO(DOWN))
    assert(result.order == order("a", 2))

  test("empty orders is illegal -> Policy.next rejects it"):
    assertThrows[IllegalArgumentException](Policy.next(Floor(0), UP, Set.empty))
