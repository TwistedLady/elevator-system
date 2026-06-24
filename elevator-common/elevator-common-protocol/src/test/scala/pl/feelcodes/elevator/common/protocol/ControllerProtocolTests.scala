package pl.feelcodes.elevator.common.protocol

import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.common.core.*
import pl.feelcodes.elevator.common.core.Direction.*
import pl.feelcodes.elevator.common.core.Motion.*
import pl.feelcodes.elevator.common.core.Command.*

/**
 * The Controller's heart with no Pekko in sight: drive `decide` and `evolve` as plain functions.
 * This is the payoff of splitting the protocol out — the business rules are unit-testable without
 * an actor system, a journal, or the TestKit.
 */
final class ControllerProtocolTests extends AnyFunSuite:
  import ControllerProtocol.*

  private def state(waiting: Boolean = false,
                    motion: Motion = Motion.Stopped, // ControllerProtocol.Stopped shadows the import
                    floor: Int = 0,
                    requests: Set[ElevatorOrder] = Set.empty): State =
    State(waiting, "lift-a", ElevatorState(Up, motion, Floor(floor)), requests)

  private val order = ElevatorOrder("o-1", Floor(3))
  private val owc = OrderElevatorCommand(order, Go(Up))

  // --- decide -------------------------------------------------------------

  test("decide AddRequest | new tag -> RequestAdded"):
    assert(decide(state(), AddRequest(order)) == List(RequestAdded(order)))

  test("decide AddRequest | duplicate tag -> no event (idempotent)"):
    assert(decide(state(requests = Set(order)), AddRequest(order)) == Nil)

  test("decide MoveExecuted | clears waiting and records the new state"):
    val ns = ElevatorState(Up, Moving, Floor(3))
    assert(decide(state(waiting = true), MoveExecuted(ns, owc))
      == List(WaitingSet(false), ElevatorStateUpdated(ns, owc)))

  test("decide Stopped | records the stop with a synthetic idle order"):
    val ns = ElevatorState(Up, Motion.Stopped, Floor(3))
    assert(decide(state(waiting = true), Stopped(ns))
      == List(WaitingSet(false), ElevatorStateUpdated(ns, idleStop(ns.floor))))

  test("decide Tick | already waiting -> no event"):
    assert(decide(state(waiting = true, requests = Set(order)), Tick) == Nil)

  test("decide Tick | requests pending -> latch waiting"):
    assert(decide(state(requests = Set(order)), Tick) == List(WaitingSet(true)))

  test("decide Tick | no requests but still moving -> latch waiting (to stop it)"):
    assert(decide(state(motion = Moving), Tick) == List(WaitingSet(true)))

  test("decide Tick | idle with nothing to do -> no event"):
    assert(decide(state(), Tick) == Nil)

  // --- evolve -------------------------------------------------------------

  test("evolve RequestAdded | adds the order to the queue"):
    assert(evolve(state(), RequestAdded(order)).requests == Set(order))

  test("evolve WaitingSet | flips the waiting latch"):
    assert(evolve(state(), WaitingSet(true)).waiting)

  test("evolve ElevatorStateUpdated | moves the car and drops every order on the reached floor"):
    val here = ElevatorOrder("here", Floor(3))
    val later = ElevatorOrder("later", Floor(5))
    val ns = ElevatorState(Up, Moving, Floor(3))
    val next = evolve(state(requests = Set(here, later)), ElevatorStateUpdated(ns, owc))
    assert(next.elevatorState == ns)
    assert(next.requests == Set(later))
