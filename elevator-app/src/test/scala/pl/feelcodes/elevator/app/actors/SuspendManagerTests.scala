package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import pl.feelcodes.elevator.common.core.domain.{Direction, ElevatorState, Floor, Motion, SuspendDwell}

import scala.concurrent.duration.*

final class SuspendManagerTests extends ScalaTestWithActorTestKit with AnyWordSpecLike:

  private def stopped(floor: Int) = ElevatorState(Direction.Up, Motion.Stopped, Floor(floor))

  "The SuspendManager" should {

    "let a lone elevator move immediately" in {
      val manager = spawn(SuspendManager())
      val reply = createTestProbe[SuspendManager.Decision]()

      manager ! SuspendManager.MayMove("e1", stopped(3), reply.ref)

      reply.expectMessage(SuspendManager.Decision(true))
    }

    "pause an elevator when another is on the same floor, then let it go" in {
      val manager = spawn(SuspendManager())
      val reply = createTestProbe[SuspendManager.Decision]()

      manager ! SuspendManager.Arrived("e2", Floor(3))
      manager ! SuspendManager.MayMove("e1", stopped(3), reply.ref)

      reply.expectNoMessage(SuspendDwell.duration - 500.millis)
      reply.expectMessage(SuspendDwell.duration, SuspendManager.Decision(true))
    }

    "not pause elevators on different floors" in {
      val manager = spawn(SuspendManager())
      val reply = createTestProbe[SuspendManager.Decision]()

      manager ! SuspendManager.Arrived("e2", Floor(3))
      manager ! SuspendManager.MayMove("e1", stopped(4), reply.ref)

      reply.expectMessage(SuspendManager.Decision(true))
    }
  }
