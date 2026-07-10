package pl.feelcodes.elevator.app

/** PassengerManager event-sourcing: forward a free passenger's call, freeze calls while busy,
  * release the next frozen call (FIFO) when the passenger is freed. */

import com.typesafe.config.ConfigFactory
import org.apache.pekko.cluster.sharding.typed.testkit.scaladsl.TestEntityRef
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pl.feelcodes.elevator.app.actors.{Manager, PassengerManager}
import pl.feelcodes.elevator.common.core.domain.{Call, Floor}
import pl.feelcodes.elevator.common.events.PassengerEvents
import pl.feelcodes.elevator.common.events.PassengerEvents.{CallForwarded, CallHeld, Freed}
import pl.feelcodes.elevator.common.logic.PassengerLogic.HeldCall

object PassengerManagerRecoveryTests {
  val config = ConfigFactory
    .parseString(
      """
        |pekko.serialization.jackson.jackson-modules += "pl.feelcodes.elevator.common.serializable.ElevatorDomainSerialization"
        |pekko.actor {
        |  allow-java-serialization = off
        |  serialization-bindings {
        |    "pl.feelcodes.elevator.common.protocol.PassengerProtocol$Command" = jackson-cbor
        |    "pl.feelcodes.elevator.common.events.PassengerEvents$Event"       = jackson-cbor
        |    "pl.feelcodes.elevator.common.logic.PassengerLogic$State"         = jackson-cbor
        |    "pl.feelcodes.elevator.common.protocol.ManagerProtocol$Command"   = jackson-cbor
        |  }
        |}
        |""".stripMargin
    )
    .withFallback(EventSourcedBehaviorTestKit.config)
    .withFallback(ConfigFactory.defaultReference())
    .resolve()
}

final class PassengerManagerRecoveryTests
    extends ScalaTestWithActorTestKit(PassengerManagerRecoveryTests.config)
    with AnyWordSpecLike
    with Matchers {

  private def newTestKit() =
    val managerProbe = createTestProbe[Manager.Command]()
    val managerProvider =
      (name: String) => TestEntityRef(Manager.TypeKey, name, managerProbe.ref)
    val kit = EventSourcedBehaviorTestKit[PassengerManager.Command, PassengerEvents.Event, PassengerManager.State](
      system,
      PassengerManager("alice", managerProvider)
    )
    (kit, managerProbe)

  private val callA = Call("c1", Floor(3), Some("alice"))
  private val callB = Call("c2", Floor(5), Some("alice"))

  "The PassengerManager" should {

    "forward a free passenger's call to that lift's Manager and become busy" in {
      val (kit, managerProbe) = newTestKit()

      val r = kit.runCommand(PassengerManager.Route("lift-a", callA))

      r.event shouldBe CallForwarded("lift-a", callA)
      kit.getState().busy shouldBe true
      managerProbe.expectMessage(Manager.Combine(List(callA)))
    }

    "freeze a call that arrives while busy instead of forwarding it" in {
      val (kit, managerProbe) = newTestKit()
      kit.runCommand(PassengerManager.Route("lift-a", callA))
      managerProbe.expectMessageType[Manager.Combine]

      val r = kit.runCommand(PassengerManager.Route("lift-b", callB))

      r.event shouldBe CallHeld("lift-b", callB)
      kit.getState().held shouldBe List(HeldCall("lift-b", callB))
      managerProbe.expectNoMessage()
    }

    "release the next frozen call to its lift when freed, and stay busy" in {
      val (kit, managerProbe) = newTestKit()
      kit.runCommand(PassengerManager.Route("lift-a", callA))
      managerProbe.expectMessageType[Manager.Combine]
      kit.runCommand(PassengerManager.Route("lift-b", callB))
      managerProbe.expectNoMessage()

      val r = kit.runCommand(PassengerManager.Free("alice"))

      r.event shouldBe Freed("alice")
      kit.getState().busy shouldBe true
      kit.getState().held shouldBe empty
      managerProbe.expectMessage(Manager.Combine(List(callB)))
    }

    "become free when freed with no frozen calls" in {
      val (kit, managerProbe) = newTestKit()
      kit.runCommand(PassengerManager.Route("lift-a", callA))
      managerProbe.expectMessageType[Manager.Combine]

      kit.runCommand(PassengerManager.Free("alice"))

      kit.getState().busy shouldBe false
      managerProbe.expectNoMessage()
    }
  }
}
