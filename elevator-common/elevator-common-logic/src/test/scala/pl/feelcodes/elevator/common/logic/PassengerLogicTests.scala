package pl.feelcodes.elevator.common.logic

import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.common.core.domain.{Call, Floor}
import pl.feelcodes.elevator.common.events.PassengerEvents.*

final class PassengerLogicTests extends AnyFunSuite:
  import PassengerLogic.*

  private val callA = Call("c1", Floor(3), Some("alice"))
  private val callB = Call("c2", Floor(5), Some("alice"))

  test("evolve | a forwarded call marks the passenger busy"):
    val s = evolve(State.empty, CallForwarded("lift-a", callA))
    assert(s.busy)
    assert(s.held.isEmpty)

  test("evolve | a call arriving while busy is frozen in order"):
    val s = List(CallForwarded("lift-a", callA), CallHeld("lift-b", callB))
      .foldLeft(State.empty)(evolve)
    assert(s.busy)
    assert(s.held == List(HeldCall("lift-b", callB)))

  test("evolve | free with frozen calls releases the head and stays busy"):
    val busyWithHeld = List(CallForwarded("lift-a", callA), CallHeld("lift-b", callB))
      .foldLeft(State.empty)(evolve)
    val released = evolve(busyWithHeld, Freed("alice"))
    assert(released.busy)
    assert(released.held.isEmpty)

  test("evolve | free with no frozen calls clears busy"):
    val busy = evolve(State.empty, CallForwarded("lift-a", callA))
    assert(!evolve(busy, Freed("alice")).busy)
