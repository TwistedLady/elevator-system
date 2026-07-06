package pl.feelcodes.elevator.app

import com.typesafe.config.ConfigFactory
import org.apache.pekko.cluster.sharding.typed.testkit.scaladsl.TestEntityRef
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pl.feelcodes.elevator.app.actors.{Controller, Coordinator}
import pl.feelcodes.elevator.common.core.domain.{ElevatorOrder, Floor}
import pl.feelcodes.elevator.common.dto.ElevatorOrderDto
import pl.feelcodes.elevator.common.events.CoordinatorEvents

object CoordinatorRecoveryTests {
  val config = ConfigFactory
    .parseString(
      """
        |pekko.serialization.jackson.jackson-modules += "pl.feelcodes.elevator.common.serializable.ElevatorDomainSerialization"
        |pekko.actor {
        |  allow-java-serialization = off
        |  serialization-bindings {
        |    "pl.feelcodes.elevator.common.protocol.CoordinatorProtocol$Command" = jackson-cbor
        |    "pl.feelcodes.elevator.common.events.CoordinatorEvents$Event"       = jackson-cbor
        |    "pl.feelcodes.elevator.common.logic.CoordinatorLogic$State"        = jackson-cbor
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

      val r = esTestKit.runCommand(
        Coordinator.AddOriginalStream(List(
          ElevatorOrderDto("t1", "lift-a", 3),
          ElevatorOrderDto("t2", "lift-a", 3),
          ElevatorOrderDto("t3", "lift-a", 5))))

      r.events should contain allOf (
        CoordinatorEvents.OrderAccepted("t1", "lift-a", 3),
        CoordinatorEvents.OrderAccepted("t2", "lift-a", 3),
        CoordinatorEvents.OrderAccepted("t3", "lift-a", 5))

      val forwarded = controllerProbe.expectMessageType[Controller.AddUniqueOrderSet]
      forwarded.orders shouldBe Set(ElevatorOrder("t1", Floor(3)), ElevatorOrder("t3", Floor(5)))
    }

    "record an order as done" in {
      val esTestKit = newTestKit()
      esTestKit.runCommand(Coordinator.MarkOrderDone("t1")).event shouldBe CoordinatorEvents.OrderDone("t1")
    }
  }
}
