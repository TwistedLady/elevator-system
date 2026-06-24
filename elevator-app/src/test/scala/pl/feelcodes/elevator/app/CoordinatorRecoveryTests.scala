package pl.feelcodes.elevator.app

import com.typesafe.config.ConfigFactory
import org.apache.pekko.cluster.sharding.typed.testkit.scaladsl.TestEntityRef
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pl.feelcodes.elevator.app.actors.{Controller, Coordinator}
import pl.feelcodes.elevator.common.core.{ElevatorOrder, Floor}
import pl.feelcodes.elevator.common.dto.ElevatorOrderDto
import pl.feelcodes.elevator.common.events.CoordinatorEvents
import pl.feelcodes.elevator.common.protocol.CoordinatorProtocol.AddOriginalStream

object CoordinatorRecoveryTests {
  val config = ConfigFactory
    .parseString(
      """
        |pekko.serialization.jackson.jackson-modules += "pl.feelcodes.elevator.app.DomainJacksonModule"
        |pekko.actor {
        |  allow-java-serialization = off
        |  serialization-bindings {
        |    "pl.feelcodes.elevator.app.actors.Coordinator$Command"              = jackson-cbor
        |    "pl.feelcodes.elevator.app.actors.Coordinator$Ack"                  = jackson-cbor
        |    "pl.feelcodes.elevator.app.actors.Coordinator$State"                = jackson-cbor
        |    "pl.feelcodes.elevator.common.protocol.CoordinatorProtocol$Command" = jackson-cbor
        |    "pl.feelcodes.elevator.common.events.CoordinatorEvents$Event"       = jackson-cbor
        |    "pl.feelcodes.elevator.common.protocol.ControllerProtocol$Command"  = jackson-cbor
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
    EventSourcedBehaviorTestKit[Coordinator.Command, CoordinatorEvents.Event, Coordinator.State](
      system,
      Coordinator("lift-a", controllerProvider)
    )

  "The Coordinator" should {

    "persist one event per original order and forward the floor-merged set to the Controller" in {
      val esTestKit = newTestKit()

      val r = esTestKit.runCommand[Coordinator.Ack](rt =>
        Coordinator.Process(AddOriginalStream(List(
          ElevatorOrderDto("t1", "lift-a", 3),
          ElevatorOrderDto("t2", "lift-a", 3),
          ElevatorOrderDto("t3", "lift-a", 5))), rt))

      r.events should contain allOf (
        CoordinatorEvents.OrderAccepted("t1", "lift-a", 3),
        CoordinatorEvents.OrderAccepted("t2", "lift-a", 3),
        CoordinatorEvents.OrderAccepted("t3", "lift-a", 5))
      r.reply shouldBe Coordinator.Ack.Ok

      val forwarded = controllerProbe.expectMessageType[Controller.AddUniqueOrderSet]
      forwarded.orders shouldBe Set(ElevatorOrder("t1", Floor(3)), ElevatorOrder("t3", Floor(5)))
    }

    "recover its (trivial) state after a crash" in {
      val esTestKit = newTestKit()
      esTestKit.runCommand[Coordinator.Ack](rt =>
        Coordinator.Process(AddOriginalStream(List(ElevatorOrderDto("a", "lift-a", 2))), rt))
      controllerProbe.expectMessageType[Controller.AddUniqueOrderSet]

      esTestKit.restart()
      esTestKit.getState() shouldBe Coordinator.State.empty
    }
  }
}
