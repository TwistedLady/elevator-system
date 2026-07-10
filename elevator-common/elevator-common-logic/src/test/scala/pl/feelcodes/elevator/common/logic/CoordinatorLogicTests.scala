package pl.feelcodes.elevator.common.logic

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import pl.feelcodes.elevator.common.events.CoordinatorEvents.*

/** Coordinator call ledger (id -> floor): insert keeps prior calls, done removes only its own,
  * assigned is a no-op, unknown ids are harmless. Verified with several calls in flight, not one. */
final class CoordinatorLogicTests extends AnyFunSuite with ScalaCheckPropertyChecks:
  import CoordinatorLogic.*

  test("CallReceived inserts and keeps every previously received call"):
    val s = List(CallReceived("c1", 3), CallReceived("c2", 5)).foldLeft(State.empty)(evolve)
    assert(s.calls == Map("c1" -> 3, "c2" -> 5))

  test("CallReceived with an existing id updates that call's floor"):
    val s = List(CallReceived("c1", 3), CallReceived("c1", 7)).foldLeft(State.empty)(evolve)
    assert(s.calls == Map("c1" -> 7))

  test("CallAssigned is a no-op with several calls in flight"):
    val s = List(CallReceived("c1", 3), CallReceived("c2", 5)).foldLeft(State.empty)(evolve)
    assert(evolve(s, CallAssigned("c1", "o1")).calls == s.calls)

  test("CallDone removes only the named call, leaving the rest"):
    val s = List(CallReceived("c1", 3), CallReceived("c2", 5)).foldLeft(State.empty)(evolve)
    assert(evolve(s, CallDone("c1")).calls == Map("c2" -> 5))

  test("CallDone of an unknown id leaves the ledger unchanged"):
    val s = evolve(State.empty, CallReceived("c1", 3))
    assert(evolve(s, CallDone("does-not-exist")).calls == s.calls)

  test("received-then-done over any distinct calls returns to empty"):
    val received = Gen.mapOf(for
      id    <- Gen.identifier
      floor <- Gen.choose(0, 30)
    yield (id, floor))
    forAll(received) { calls =>
      val built = calls.foldLeft(State.empty) { case (s, (id, f)) => evolve(s, CallReceived(id, f)) }
      assert(built.calls == calls)
      val drained = calls.keys.foldLeft(built)((s, id) => evolve(s, CallDone(id)))
      assert(drained.calls.isEmpty)
    }
