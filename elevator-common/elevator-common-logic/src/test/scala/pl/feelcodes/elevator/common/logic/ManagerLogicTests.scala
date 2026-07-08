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
    assert(plan(State.empty, first) == List(OrderCreated(first.head.id, 3, Set("c1"), Set.empty, Set("c1"))))
    val after = plan(State.empty, first).foldLeft(State.empty)(evolve)
    val second = combine("lift-a", List(Call("c1", Floor(3)), Call("c2", Floor(3))))
    assert(plan(after, second) == List(OrderExtended(first.head.id, Set("c2"), Set.empty, Set("c2"))))

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

  test("counts | passengers dedup while the order lives; anonymous counted apart"):
    val first = combine("lift-a", List(Call("c1", Floor(3), Some("alice")), Call("c2", Floor(3))))
    val s1 = plan(State.empty, first).foldLeft(State.empty)(evolve)
    val order1 = s1.orders.values.head
    assert(order1.passengerCount == 1)
    assert(order1.anonymousCount == 1)

    // alice presses again (new call, same person) plus a new anonymous press
    val second = combine("lift-a", List(
      Call("c1", Floor(3), Some("alice")),
      Call("c2", Floor(3)),
      Call("c3", Floor(3), Some("alice")),
      Call("c4", Floor(3))
    ))
    val events = plan(s1, second)
    assert(events == List(OrderExtended(order1.id, Set("c3", "c4"), Set.empty, Set("c4"))))
    val order2 = events.foldLeft(s1)(evolve).orders.values.head
    assert(order2.callIds == Set("c1", "c2", "c3", "c4"))
    assert(order2.passengerCount == 1)
    assert(order2.anonymousCount == 2)
