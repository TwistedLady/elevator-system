package pl.feelcodes.elevator.common.strategy

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import pl.feelcodes.elevator.common.core.domain.*

final class GroupCallsStrategyTests extends AnyFunSuite with ScalaCheckPropertyChecks:

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

  test("group | splits identified passengers from anonymous calls, deduping people"):
    val order = GroupCallsStrategy.default.group("lift-a", List(
      Call("c1", Floor(3), Some("alice")),
      Call("c2", Floor(3), Some("alice")),
      Call("c3", Floor(3), Some("bob")),
      Call("c4", Floor(3))
    )).head
    assert(order.callIds == Set("c1", "c2", "c3", "c4"))
    assert(order.passengers == Set("alice", "bob"))
    assert(order.passengerCount == 2)
    assert(order.anonymousCallIds == Set("c4"))
    assert(order.anonymousCount == 1)

  test("group | empty -> empty"):
    assert(GroupCallsStrategy.default.group("lift-a", Nil) == Set.empty)

  private val calls: Gen[Call] = for
    id    <- Gen.identifier
    floor <- Gen.choose(0, 15)
    who   <- Gen.option(Gen.oneOf("alice", "bob", "carol"))
  yield Call(id, Floor(floor), who)

  test("group | property: order-independent, one order per floor, every call placed"):
    forAll(Gen.listOf(calls)) { cs =>
      val orders = GroupCallsStrategy.default.group("lift-a", cs)
      assert(orders == GroupCallsStrategy.default.group("lift-a", cs.reverse))
      assert(orders.map(_.floor) == cs.map(_.floor).toSet)
      assert(orders.flatMap(_.callIds) == cs.map(_.id).toSet)
    }
