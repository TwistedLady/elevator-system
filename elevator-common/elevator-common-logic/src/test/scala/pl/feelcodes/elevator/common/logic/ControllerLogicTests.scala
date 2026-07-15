package pl.feelcodes.elevator.common.logic

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import pl.feelcodes.elevator.common.core.domain.*
import pl.feelcodes.elevator.common.core.domain.Command.*
import pl.feelcodes.elevator.common.core.domain.Direction.*
import pl.feelcodes.elevator.common.core.domain.Motion.*
import pl.feelcodes.elevator.common.events.ControllerEvents.*

/** Controller scheduling logic (pure): dedup on accept, the door-cycle arrival branch, evolve's
  * legacy-alias and arrived-floor pruning, the shouldAct gate, and nextCommand's move/stop/idle. */
final class ControllerLogicTests extends AnyFunSuite with ScalaCheckPropertyChecks:
  import ControllerLogic.*

  private def order(id: String, floor: Int): Order = Order(id, Floor(floor), Set(s"call-$id"))
  private def state(floor: Int, dir: Direction, motion: Motion, orders: Set[Order], waiting: Boolean = false): State =
    State(waiting, "lift-a", ElevatorState(dir, motion, Floor(floor)), orders)

  test("addUniqueOrders accepts only orders whose id is not already present"):
    val s = state(0, Up, Stopped, Set(order("a", 3)))
    assert(addUniqueOrders(s, Set(order("a", 3), order("b", 5))) == List(OrderAccepted(order("b", 5))))

  test("arrival without a door cycle also clears the waiting flag; with one it does not"):
    val ns = ElevatorState(Up, Moving, Floor(4))
    assert(arrival(ns, doorCycle = false) == List(WaitingSet(false), ElevatorStateUpdated(ns)))
    assert(arrival(ns, doorCycle = true)  == List(ElevatorStateUpdated(ns)))

  test("evolve: OrderAccepted and its legacy alias OrderAdded both add the order"):
    val accepted = evolve(State.initial("lift-a"), OrderAccepted(order("a", 3)))
    val added    = evolve(State.initial("lift-a"), OrderAdded(order("a", 3)))
    assert(accepted.orders == Set(order("a", 3)))
    assert(added.orders == accepted.orders)

  test("evolve: arriving at a floor updates state and drops every order on that floor"):
    val s  = state(0, Up, Moving, Set(order("a", 3), order("b", 3), order("c", 5)))
    val ns = ElevatorState(Up, Moving, Floor(3))
    val after = evolve(s, ElevatorStateUpdated(ns))
    assert(after.elevatorState == ns)
    assert(after.orders == Set(order("c", 5)))

  test("evolve: WaitingSet toggles the waiting flag"):
    assert(evolve(State.initial("lift-a"), WaitingSet(true)).waiting)
    assert(!evolve(state(0, Up, Stopped, Set.empty, waiting = true), WaitingSet(false)).waiting)

  test("shouldAct iff not waiting and (orders pending or already moving)"):
    forAll(Gen.oneOf(true, false), Gen.oneOf(true, false), Gen.oneOf(Moving, Stopped)) { (waiting, hasOrders, motion) =>
      val orders = if hasOrders then Set(order("a", 3)) else Set.empty[Order]
      val s = state(0, Up, motion, orders, waiting)
      assert(shouldAct(s, orders) == (!waiting && (hasOrders || motion == Moving)))
    }

  test("nextCommand: heads toward pending orders, stops when moving with none, idles when stopped with none"):
    val heading = state(0, Up, Stopped, Set(order("a", 3)))
    assert(nextCommand(heading, heading.orders) == Some(Go(Up)))
    assert(nextCommand(state(2, Up, Moving, Set.empty), Set.empty) == Some(Stop()))
    assert(nextCommand(state(2, Up, Stopped, Set.empty), Set.empty) == None)
