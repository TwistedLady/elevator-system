package pl.feelcodes.elevator.common.logic

import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.common.core.domain.*
import pl.feelcodes.elevator.common.events.ManagerEvents.*

final class ManagerLogicTests extends AnyFunSuite:
  import ManagerLogic.*

  test("combine | groups calls by floor"):
    val orders = combine(List(Call("c1", Floor(3)), Call("c2", Floor(3)), Call("c3", Floor(5))))
    assert(orders.map(_.floor) == Set(Floor(3), Floor(5)))

  test("created | emits one OrderCreated per new order, skips known ones"):
    val orders = combine(List(Call("c1", Floor(3))))
    val events = created(State.empty, orders)
    assert(events.map(_.orderId).toSet == orders.map(_.id))
    val after = events.foldLeft(State.empty)(evolve)
    assert(created(after, orders) == Nil)

  test("evolve | OrderCreated then OrderDone leaves no order"):
    val o = combine(List(Call("c1", Floor(3)))).head
    val s = evolve(State.empty, OrderCreated(o.id, o.floor.num, o.callIds))
    assert(s.orders.contains(o.id))
    assert(evolve(s, OrderDone(o.id)).orders.isEmpty)
