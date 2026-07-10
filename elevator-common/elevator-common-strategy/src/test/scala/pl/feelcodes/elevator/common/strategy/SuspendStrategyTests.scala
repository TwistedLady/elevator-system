package pl.feelcodes.elevator.common.strategy

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import pl.feelcodes.elevator.common.core.domain.*
import pl.feelcodes.elevator.common.core.domain.Direction.*
import pl.feelcodes.elevator.common.core.domain.Motion.*

/** SuspendStrategy contract: the default permits movement in every state, and a custom strategy's
  * deny decision (mayMove == false) is a reachable, state-dependent branch the SuspendManager honors. */
final class SuspendStrategyTests extends AnyFunSuite with ScalaCheckPropertyChecks:

  private val states: Gen[ElevatorState] = for
    dir    <- Gen.oneOf(Up, Down)
    motion <- Gen.oneOf(Moving, Stopped)
    floor  <- Gen.choose(-10, 30)
  yield ElevatorState(dir, motion, Floor(floor))

  test("default strategy allows movement in every state"):
    forAll(states)(s => assert(SuspendStrategy.default.mayMove(s)))

  test("a strategy may deny: a state-dependent rule returns false exactly when it should"):
    val denyWhileMoving: SuspendStrategy = _.motion == Stopped
    forAll(states) { s =>
      assert(denyWhileMoving.mayMove(s) == (s.motion == Stopped))
    }
