package pl.feelcodes.elevator.common.logic

import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.common.core.domain.*
import pl.feelcodes.elevator.common.events.ManagerEvents.*

final class ManagerLogicTests extends AnyFunSuite:
  import ManagerLogic.*

  test("combine | groups calls by floor"):
    val orders = combine("lift-a", List(Call("c1", Floor(3)), Call("c2", Floor(3)), Call("c3", Floor(5))))
    assert(orders.map(_.floor) == Set(Floor(3), Floor(5)))

  test("plan | new floor -> OrderCreated, known floor with fresh calls -> OrderExtended"):
    val first = combine("lift-a", List(Call("c1", Floor(3))))
    assert(plan(State.empty, first) == List(OrderCreated(first.head.id, 3, Set("c1"))))
    val after = plan(State.empty, first).foldLeft(State.empty)(evolve)
    val second = combine("lift-a", List(Call("c1", Floor(3)), Call("c2", Floor(3))))
    assert(plan(after, second) == List(OrderExtended(first.head.id, Set("c2"))))

  test("plan | no fresh calls -> no event"):
    val orders = combine("lift-a", List(Call("c1", Floor(3))))
    val after = plan(State.empty, orders).foldLeft(State.empty)(evolve)
    assert(plan(after, orders) == Nil)

  test("evolve | extend adds calls, done removes the order"):
    val o = combine("lift-a", List(Call("c1", Floor(3)))).head
    val created = evolve(State.empty, OrderCreated(o.id, o.floor.num, o.callIds))
    val extended = evolve(created, OrderExtended(o.id, Set("c2")))
    assert(extended.orders(o.id).callIds == Set("c1", "c2"))
    assert(evolve(extended, OrderDone(o.id)).orders.isEmpty)
