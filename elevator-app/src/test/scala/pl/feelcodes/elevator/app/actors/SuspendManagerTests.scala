package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import pl.feelcodes.elevator.common.core.domain.{Direction, ElevatorState, Floor, Motion, SuspendDwell}
import pl.feelcodes.elevator.common.strategy.SuspendStrategy

import scala.concurrent.duration.*

/** SuspendManager gate: lone cars go, two on a floor pause then go, and — the safety case — a
  * strategy that forbids movement yields Decision(false), overriding even the same-floor dwell. */
final class SuspendManagerTests extends ScalaTestWithActorTestKit with AnyWordSpecLike:

  private def stopped(floor: Int) = ElevatorState(Direction.Up, Motion.Stopped, Floor(floor))
  private def moving(floor: Int)  = ElevatorState(Direction.Up, Motion.Moving, Floor(floor))

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

    "deny movement when the strategy forbids it, ahead of any same-floor dwell" in {
      val manager = spawn(SuspendManager(_ => false))
      val reply = createTestProbe[SuspendManager.Decision]()

      manager ! SuspendManager.Arrived("e2", Floor(3))
      manager ! SuspendManager.MayMove("e1", stopped(3), reply.ref)

      reply.expectMessage(SuspendManager.Decision(false))
    }

    "consult the strategy per state: deny while moving, allow while stopped" in {
      val allowWhenStopped: SuspendStrategy = _.motion == Motion.Stopped
      val manager = spawn(SuspendManager(allowWhenStopped))
      val reply = createTestProbe[SuspendManager.Decision]()

      manager ! SuspendManager.MayMove("e1", moving(2), reply.ref)
      reply.expectMessage(SuspendManager.Decision(false))

      manager ! SuspendManager.MayMove("e1", stopped(2), reply.ref)
      reply.expectMessage(SuspendManager.Decision(true))
    }
  }
