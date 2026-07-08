package pl.feelcodes.elevator.common.strategy

import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.common.core.domain.*

final class GroupCallsStrategyTests extends AnyFunSuite:

  test("group | calls sharing a floor collapse into one order"):
    val orders = GroupCallsStrategy.default.group("lift-a", List(Call("c1", Floor(3)), Call("c2", Floor(3))))
    assert(orders.size == 1)
    assert(orders.head.floor == Floor(3))
    assert(orders.head.callIds == Set("c1", "c2"))

  test("group | distinct floors produce distinct orders"):
    val orders = GroupCallsStrategy.default.group("lift-a", List(Call("c1", Floor(3)), Call("c2", Floor(5))))
    assert(orders.map(_.floor) == Set(Floor(3), Floor(5)))

  test("group | order id depends on (elevator, floor), not on the calls"):
    val a = GroupCallsStrategy.default.group("lift-a", List(Call("c1", Floor(3))))
    val b = GroupCallsStrategy.default.group("lift-a", List(Call("c2", Floor(3))))
    assert(a.head.id == b.head.id)

  test("group | the same floor on a different elevator is a different order"):
    val a = GroupCallsStrategy.default.group("lift-a", List(Call("c1", Floor(3))))
    val b = GroupCallsStrategy.default.group("lift-b", List(Call("c1", Floor(3))))
    assert(a.head.id != b.head.id)

  test("group | empty -> empty"):
    assert(GroupCallsStrategy.default.group("lift-a", Nil) == Set.empty)
