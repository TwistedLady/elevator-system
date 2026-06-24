package pl.feelcodes.elevator.common.protocol

import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.common.core.*
import pl.feelcodes.elevator.common.core.Direction.*
import pl.feelcodes.elevator.common.core.Motion.*
import pl.feelcodes.elevator.common.protocol.ControllerProtocol.{AddOrder, ChooseNextOrder, PublishState}
import pl.feelcodes.elevator.common.protocol.ControllerDecider.*

final class ControllerProtocolTests extends AnyFunSuite:

  private def state(waiting: Boolean = false,
                    motion: Motion = Stopped,
                    floor: Int = 0,
                    orders: Set[ElevatorOrder] = Set.empty): State =
    State(waiting, "lift-a", ElevatorState(Up, motion, Floor(floor)), orders)

  private val order = ElevatorOrder("o-1", Floor(3))

  test("decide AddOrder | new tag -> OrderAdded"):
    assert(decide(state(), AddOrder(order)) == List(OrderAdded(order)))

  test("decide AddOrder | duplicate tag -> no event"):
    assert(decide(state(orders = Set(order)), AddOrder(order)) == Nil)

  test("decide PublishState | clears waiting and records the new state"):
    val ns = ElevatorState(Up, Moving, Floor(3))
    assert(decide(state(waiting = true), PublishState(ns)) == List(WaitingSet(false), ElevatorStateUpdated(ns)))

  test("decide ChooseNextOrder | already waiting -> no event"):
    assert(decide(state(waiting = true), ChooseNextOrder(Set(order))) == Nil)

  test("decide ChooseNextOrder | orders pending -> latch waiting"):
    assert(decide(state(), ChooseNextOrder(Set(order))) == List(WaitingSet(true)))

  test("decide ChooseNextOrder | no orders but still moving -> latch waiting"):
    assert(decide(state(motion = Moving), ChooseNextOrder(Set.empty)) == List(WaitingSet(true)))

  test("decide ChooseNextOrder | idle with nothing to do -> no event"):
    assert(decide(state(), ChooseNextOrder(Set.empty)) == Nil)

  test("evolve OrderAdded | adds the order"):
    assert(evolve(state(), OrderAdded(order)).orders == Set(order))

  test("evolve WaitingSet | flips the latch"):
    assert(evolve(state(), WaitingSet(true)).waiting)

  test("evolve ElevatorStateUpdated | moves the car and drops orders on the reached floor"):
    val here = ElevatorOrder("here", Floor(3))
    val later = ElevatorOrder("later", Floor(5))
    val ns = ElevatorState(Up, Moving, Floor(3))
    val next = evolve(state(orders = Set(here, later)), ElevatorStateUpdated(ns))
    assert(next.elevatorState == ns)
    assert(next.orders == Set(later))
