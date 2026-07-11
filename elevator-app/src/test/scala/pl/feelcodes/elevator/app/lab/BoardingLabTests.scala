package pl.feelcodes.elevator.app.lab

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers
import pl.feelcodes.elevator.sim.Simulator

import scala.concurrent.duration.*
import scala.util.Random

final class BoardingLabTests extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers:

  private val noop: pl.feelcodes.elevator.sim.CallSender = _ => ()

  "The boarding lab" should {

    "board the riders who step in and time out the no-shows, one stop each" in {
      val sim = Simulator(noop, Seq("lift-a"), maxFloor = 6, Random(3), noShowRate = 0.25)
      val riders = BoardingLab.ridersOf(sim, "lift-a").take(12)

      // a meaningful lab needs both outcomes present in the slice
      riders.exists(_.boards) shouldBe true
      riders.exists(!_.boards) shouldBe true

      val probe = createTestProbe[BoardingLab.Summary]()
      spawn(BoardingLab.LabDriver("lift-a", riders,
        boardTimeout = 300.millis, thinkOf = _ => 20.millis, probe.ref))

      val summary = probe.receiveMessage(20.seconds)

      summary.elevator shouldBe "lift-a"
      summary.outcomes.map(_.passenger) shouldBe riders.map(_.spec.id)
      summary.outcomes.map(_.floor) shouldBe riders.map(_.spec.floor)
      summary.outcomes.zip(riders).foreach { case (o, r) => o.boarded shouldBe r.boards }
      summary.boarded shouldBe riders.count(_.boards)
      summary.noShows shouldBe riders.count(!_.boards)
    }

    "produce an empty summary when the elevator was never called" in {
      val sim = Simulator(noop, Seq("lift-a"), maxFloor = 6, Random(3))
      val probe = createTestProbe[BoardingLab.Summary]()
      spawn(BoardingLab.LabDriver("lift-z", Nil,
        boardTimeout = 100.millis, thinkOf = _ => 10.millis, probe.ref))

      val summary = probe.receiveMessage(5.seconds)
      summary.outcomes shouldBe empty
    }
  }
