package pl.feelcodes.elevator.app

import com.typesafe.config.ConfigFactory
import org.apache.pekko.cluster.sharding.typed.testkit.scaladsl.TestEntityRef
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pl.feelcodes.elevator.app.actors.{Controller, Coordinator}
import pl.feelcodes.elevator.common.dto.ElevatorOrderDto

/**
 * Disaster-recovery test for the Coordinator's idempotency.
 *
 * The Coordinator deduplicates orders by tag. If that memory did NOT survive a crash, after a
 * restart the same order would be accepted twice -> the elevator would be driven twice. This
 * proves the dedup state is rebuilt from the journal and still rejects duplicates afterwards.
 */
object CoordinatorRecoveryTests {
  val config = ConfigFactory
    .parseString(
      """
        |pekko.serialization.jackson.jackson-modules += "pl.feelcodes.elevator.app.DomainJacksonModule"
        |pekko.actor {
        |  allow-java-serialization = off
        |  serialization-bindings {
        |    "pl.feelcodes.elevator.app.actors.Coordinator$Command" = jackson-cbor
        |    "pl.feelcodes.elevator.app.actors.Coordinator$Event"   = jackson-cbor
        |    "pl.feelcodes.elevator.app.actors.Coordinator$State"   = jackson-cbor
        |    "pl.feelcodes.elevator.app.actors.Coordinator$Ack"     = jackson-cbor
        |    "pl.feelcodes.elevator.app.actors.Controller$Command"  = jackson-cbor
        |  }
        |}
        |""".stripMargin
    )
    .withFallback(EventSourcedBehaviorTestKit.config)
    .withFallback(ConfigFactory.defaultReference())
    .resolve()
}

final class CoordinatorRecoveryTests
    extends ScalaTestWithActorTestKit(CoordinatorRecoveryTests.config)
    with AnyWordSpecLike
    with Matchers {

  private val controllerProbe = createTestProbe[Controller.Command]()
  private val controllerProvider =
    (name: String) => TestEntityRef(Controller.TypeKey, name, controllerProbe.ref)

  private def newTestKit() =
    EventSourcedBehaviorTestKit[Coordinator.Command, Coordinator.Event, Coordinator.State](
      system,
      Coordinator("lift-a", controllerProvider)
    )

  "The Coordinator journal" should {

    "remember accepted orders across a crash, and still reject duplicates after recovery" in {
      val esTestKit = newTestKit()

      val dto1 = ElevatorOrderDto("tag-1", "lift-a", 3)
      val dto2 = ElevatorOrderDto("tag-2", "lift-a", 5)

      val r1 = esTestKit.runCommand[Coordinator.Ack](replyTo => Coordinator.Process(dto1, replyTo))
      r1.event shouldBe Coordinator.Accepted("tag-1", "lift-a", 3)
      r1.reply shouldBe Coordinator.Ack.Ok
      controllerProbe.expectMessageType[Controller.AddRequest]

      esTestKit.runCommand[Coordinator.Ack](replyTo => Coordinator.Process(dto2, replyTo))
      controllerProbe.expectMessageType[Controller.AddRequest]

      val before = esTestKit.getState()
      before.seenTags should contain allOf ("tag-1", "tag-2")

      esTestKit.restart() // <-- crash: dedup memory must be rebuilt from the journal

      esTestKit.getState() shouldBe before

      // A duplicate of an already-seen order: no new event, no second forward to the Controller.
      val dup = esTestKit.runCommand[Coordinator.Ack](replyTo => Coordinator.Process(dto1, replyTo))
      dup.hasNoEvents shouldBe true
      dup.reply shouldBe Coordinator.Ack.Ok
      controllerProbe.expectNoMessage()
    }
  }
}
