package pl.feelcodes.elevator.common.strategy

import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.common.core.*
import pl.feelcodes.elevator.common.core.Direction.*
import pl.feelcodes.elevator.common.core.Command.*

final class NextFloorStrategyTests extends AnyFunSuite:
  import NextFloorStrategy.default.next as choose

  test("target above while going Up -> Go(Up)"):
    assert(choose(Floor(0), Up, Set(Floor(3))) == Go(Up))

  test("target at current floor -> Stop()"):
    assert(choose(Floor(2), Up, Set(Floor(2))) == Stop())

  test("only target is behind us (below while Up) -> swap to Go(Down)"):
    assert(choose(Floor(5), Up, Set(Floor(2))) == Go(Down))

  test("several targets above while Up -> keep Go(Up)"):
    assert(choose(Floor(0), Up, Set(Floor(5), Floor(2))) == Go(Up))

  test("target below while going Down -> keep Go(Down)"):
    assert(choose(Floor(5), Down, Set(Floor(2), Floor(1))) == Go(Down))

  test("no targets -> Stop()"):
    assert(choose(Floor(0), Up, Set.empty) == Stop())
