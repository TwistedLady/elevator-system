package pl.feelcodes.elevator.common.logic

import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.common.core.domain.{ElevatorOrder, Floor}

final class CoordinatorLogicTests extends AnyFunSuite:
  import CoordinatorLogic.*

  test("mergeByFloor | collapses orders sharing a floor into one"):
    val merged = mergeByFloor(List(
      ElevatorOrder("t1", Floor(3)),
      ElevatorOrder("t2", Floor(3))))
    assert(merged == Set(ElevatorOrder("t1", Floor(3))))

  test("mergeByFloor | keeps orders on distinct floors"):
    val orders = List(ElevatorOrder("t1", Floor(3)), ElevatorOrder("t2", Floor(5)))
    assert(mergeByFloor(orders) == orders.toSet)

  test("mergeByFloor | empty -> empty"):
    assert(mergeByFloor(Nil) == Set.empty)
