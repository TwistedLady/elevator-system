package pl.feelcodes.elevator.common.logic

import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.common.events.CoordinatorEvents.*

final class CoordinatorLogicTests extends AnyFunSuite:
  import CoordinatorLogic.*

  test("evolve | CallReceived records the call's floor"):
    assert(evolve(State.empty, CallReceived("c1", 3)).calls == Map("c1" -> 3))

  test("evolve | CallAssigned leaves the call set unchanged"):
    val s = evolve(State.empty, CallReceived("c1", 3))
    assert(evolve(s, CallAssigned("c1", "o1")).calls == Map("c1" -> 3))

  test("evolve | CallDone drops the call"):
    val s = evolve(State.empty, CallReceived("c1", 3))
    assert(evolve(s, CallDone("c1")).calls == Map.empty)
