package pl.feelcodes.elevator.app

import com.typesafe.config.ConfigFactory
import org.apache.pekko.cluster.sharding.typed.testkit.scaladsl.TestEntityRef
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pl.feelcodes.elevator.app.actors.{Coordinator, Manager}
import pl.feelcodes.elevator.common.core.domain.{Call, Floor}
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
        |    "pl.feelcodes.elevator.common.logic.CoordinatorLogic$State"         = jackson-cbor
        |    "pl.feelcodes.elevator.common.protocol.ManagerProtocol$Command"     = jackson-cbor
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

  private val managerProbe = createTestProbe[Manager.Command]()
  private val managerProvider =
    (name: String) => TestEntityRef(Manager.TypeKey, name, managerProbe.ref)

  private def newTestKit() =
    EventSourcedBehaviorTestKit[Coordinator.Command, CoordinatorEvents.Event, Coordinator.State](
      system,
      Coordinator("lift-a", managerProvider, _ => ())
    )

  "The Coordinator" should {

    "persist one event per received call and forward the calls to the Manager" in {
      val esTestKit = newTestKit()

      val r = esTestKit.runCommand(
        Coordinator.Handle(List(
          Call("c1", Floor(3)),
          Call("c2", Floor(3)),
          Call("c3", Floor(5)))))

      r.events should contain allOf (
        CoordinatorEvents.CallReceived("c1", 3),
        CoordinatorEvents.CallReceived("c2", 3),
        CoordinatorEvents.CallReceived("c3", 5))

      val forwarded = managerProbe.expectMessageType[Manager.Combine]
      forwarded.calls shouldBe List(Call("c1", Floor(3)), Call("c2", Floor(3)), Call("c3", Floor(5)))
    }

    "record a call's order assignment" in {
      val esTestKit = newTestKit()
      esTestKit.runCommand(Coordinator.AssignOrder("c1", "o-1")).event shouldBe
        CoordinatorEvents.CallAssigned("c1", "o-1")
    }

    "record a call as done" in {
      val esTestKit = newTestKit()
      esTestKit.runCommand(Coordinator.MarkDone("c1")).event shouldBe CoordinatorEvents.CallDone("c1")
    }
  }
}
