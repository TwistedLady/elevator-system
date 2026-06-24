package pl.feelcodes.elevator.common.protocol

import org.scalatest.funsuite.AnyFunSuite

/**
 * The Coordinator's heart as plain functions: accept orders idempotently, group them by floor, and
 * confirm every order waiting on a reached floor.
 */
final class CoordinatorProtocolTests extends AnyFunSuite:
  import CoordinatorProtocol.*

  // --- decide -------------------------------------------------------------

  test("decide Accept | first-time order -> Accepted"):
    assert(decide(State.empty, Accept("t1", "lift-a", 3)) == List(Accepted("t1", "lift-a", 3)))

  test("decide Accept | tag already outstanding at that floor -> no event (idempotent)"):
    val s = State(Map(3 -> Set("t1")))
    assert(decide(s, Accept("t1", "lift-a", 3)) == Nil)

  test("decide Reach | confirms every order waiting on the floor"):
    val s = State(Map(3 -> Set("t1", "t2")))
    assert(decide(s, Reach(3)).toSet == Set(Completed("t1"), Completed("t2")))

  test("decide Reach | floor with nothing outstanding -> no event"):
    assert(decide(State.empty, Reach(7)) == Nil)

  // --- evolve -------------------------------------------------------------

  test("evolve Accepted | adds the tag to its floor bucket"):
    assert(evolve(State.empty, Accepted("t1", "lift-a", 3)) == State(Map(3 -> Set("t1"))))

  test("evolve Accepted | merges co-floor orders into the same bucket"):
    val s = evolve(State(Map(3 -> Set("t1"))), Accepted("t2", "lift-a", 3))
    assert(s == State(Map(3 -> Set("t1", "t2"))))

  test("evolve Completed | removes the tag and drops the now-empty bucket"):
    assert(evolve(State(Map(3 -> Set("t1"))), Completed("t1")) == State.empty)
