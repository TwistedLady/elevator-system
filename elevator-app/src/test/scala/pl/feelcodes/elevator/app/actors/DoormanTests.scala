package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.common.core.domain.{DoorState, Floor}
import pl.feelcodes.elevator.common.core.engine.DoorEngine

import scala.concurrent.duration.*

final class DoormanTests extends AnyFunSuite:

  private def serveYields(floor: Floor): List[(String, Floor, DoorState)] =
    var events = List.empty[(String, Floor, DoorState)]
    val kit = BehaviorTestKit(Doorman((n, f, s) => events = events :+ (n, f, s), DoorEngine(1.milli)))
    kit.run(Doorman.Serve("lift-a", floor))
    events

  test("Serve | opens then closes the door at the floor"):
    assert(serveYields(Floor(3)) ==
      List(("lift-a", Floor(3), DoorState.Open), ("lift-a", Floor(3), DoorState.Closed)))
