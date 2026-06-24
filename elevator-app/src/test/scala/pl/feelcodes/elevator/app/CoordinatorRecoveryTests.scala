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
 * Event-sourcing tests for the Coordinator. Dedup is no longer its job (that moved to durable
 * ingestion-time dedup), so this covers what it DOES own: accepting orders, grouping them by
 * floor, and confirming every order at a floor when the Controller reports it reached — surviving
 * a crash via journal replay.
 */
object CoordinatorRecoveryTests {
  val config = ConfigFactory
    .parseString(
      """
        |pekko.serialization.jackson.jackson-modules += "pl.feelcodes.elevator.app.DomainJacksonModule"
        |pekko.actor {
        |  allow-java-serialization = off
        |  serialization-bindings {
        |    "pl.feelcodes.elevator.app.actors.Coordinator$Command"               = jackson-cbor
        |    "pl.feelcodes.elevator.common.protocol.CoordinatorProtocol$Event"    = jackson-cbor
        |    "pl.feelcodes.elevator.common.protocol.CoordinatorProtocol$State"    = jackson-cbor
        |    "pl.feelcodes.elevator.app.actors.Coordinator$Ack"                   = jackson-cbor
        |    "pl.feelcodes.elevator.common.protocol.ControllerProtocol$Command"   = jackson-cbor
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

  "The Coordinator" should {

    "accept an order, forward it to the Controller, and group it by floor" in {
      val esTestKit = newTestKit()

      val r = esTestKit.runCommand[Coordinator.Ack](rt =>
        Coordinator.Process(ElevatorOrderDto("t1", "lift-a", 3), rt))
      r.event shouldBe Coordinator.Accepted("t1", "lift-a", 3)
      r.reply shouldBe Coordinator.Ack.Ok
      controllerProbe.expectMessageType[Controller.AddRequest]
      esTestKit.getState().byFloor shouldBe Map(3 -> Set("t1"))
    }

    "merge orders sharing a floor and confirm them all when it is reached, surviving a crash" in {
      val esTestKit = newTestKit()

      // a, b, c all want floor 7; d wants floor 2
      List(("a", 7), ("b", 7), ("c", 7), ("d", 2)).foreach { case (tag, floor) =>
        esTestKit.runCommand[Coordinator.Ack](rt =>
          Coordinator.Process(ElevatorOrderDto(tag, "lift-a", floor), rt))
        controllerProbe.expectMessageType[Controller.AddRequest]
      }

      val res = esTestKit.runCommand(Coordinator.Reached(7))
      res.events should contain allOf (
        Coordinator.Completed("a"), Coordinator.Completed("b"), Coordinator.Completed("c"))

      val before = esTestKit.getState()
      before.byFloor shouldBe Map(2 -> Set("d")) // floor 7 cleared; floor 2 still outstanding

      esTestKit.restart() // rebuilt purely from the journal
      esTestKit.getState() shouldBe before
    }

    "accept a redelivered order idempotently — no duplicate event, still acked and forwarded" in {
      val esTestKit = newTestKit()
      val dto = ElevatorOrderDto("dup", "lift-a", 5)

      val first = esTestKit.runCommand[Coordinator.Ack](rt => Coordinator.Process(dto, rt))
      first.event shouldBe Coordinator.Accepted("dup", "lift-a", 5)
      controllerProbe.expectMessageType[Controller.AddRequest]

      // Same tag again: this is Kafka redelivery after a crash between accept and dedup-claim.
      // No new event is recorded, but the order is re-forwarded to the Controller (idempotent
      // there) and acked so the offset can advance.
      val second = esTestKit.runCommand[Coordinator.Ack](rt => Coordinator.Process(dto, rt))
      second.hasNoEvents shouldBe true
      second.reply shouldBe Coordinator.Ack.Ok
      controllerProbe.expectMessageType[Controller.AddRequest]
      esTestKit.getState().byFloor shouldBe Map(5 -> Set("dup"))
    }

    "do nothing when reaching a floor with no orders waiting" in {
      val esTestKit = newTestKit()
      esTestKit.runCommand(Coordinator.Reached(9)).hasNoEvents shouldBe true
    }
  }
}
