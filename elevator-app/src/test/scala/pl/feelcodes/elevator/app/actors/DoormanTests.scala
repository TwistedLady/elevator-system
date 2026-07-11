package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.testkit.typed.scaladsl.{ManualTime, ScalaTestWithActorTestKit, TestProbe}
import org.scalatest.wordspec.AnyWordSpecLike
import pl.feelcodes.elevator.common.core.domain.{DoorState, Floor}

import scala.concurrent.duration.*

final class DoormanTests extends ScalaTestWithActorTestKit(ManualTime.config) with AnyWordSpecLike:

  private val manualTime = ManualTime()
  private val boardTimeout = 5.seconds

  private def spawnDoorman(): (ActorRef[Doorman.Command], TestProbe[(String, Int, DoorState)]) =
    val probe = createTestProbe[(String, Int, DoorState)]()
    val doorman = spawn(Doorman((n, f, s) => probe.ref ! (n, f.num, s), boardTimeout))
    (doorman, probe)

  "The Doorman" should {

    "open on Serve, then close immediately when the passenger boards" in {
      val (doorman, probe) = spawnDoorman()

      doorman ! Doorman.Serve("lift-a", Floor(3))
      probe.expectMessage(("lift-a", 3, DoorState.Open))

      doorman ! Doorman.Boarded("lift-a", Floor(3), "alice")
      probe.expectMessage(("lift-a", 3, DoorState.Closed))
    }

    "close on timeout when nobody boards (no-show)" in {
      val (doorman, probe) = spawnDoorman()

      doorman ! Doorman.Serve("lift-a", Floor(3))
      probe.expectMessage(("lift-a", 3, DoorState.Open))

      manualTime.expectNoMessageFor(boardTimeout - 1.second, probe)
      manualTime.timePasses(1.second)
      probe.expectMessage(("lift-a", 3, DoorState.Closed))
    }

    "not close early for a boarding at a different floor" in {
      val (doorman, probe) = spawnDoorman()

      doorman ! Doorman.Serve("lift-a", Floor(3))
      probe.expectMessage(("lift-a", 3, DoorState.Open))

      doorman ! Doorman.Boarded("lift-a", Floor(7), "bob")
      probe.expectNoMessage(200.millis)

      doorman ! Doorman.Boarded("lift-a", Floor(3), "alice")
      probe.expectMessage(("lift-a", 3, DoorState.Closed))
    }

    "ignore a boarding that arrives with no door open" in {
      val (doorman, probe) = spawnDoorman()

      doorman ! Doorman.Boarded("lift-a", Floor(3), "ghost")
      probe.expectNoMessage(200.millis)
    }
  }
