package pl.feelcodes.elevator.app

import com.typesafe.config.ConfigFactory
import org.apache.pekko.cluster.sharding.typed.testkit.scaladsl.TestEntityRef
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pl.feelcodes.elevator.app.actors.{Controller, Coordinator, Manager}
import pl.feelcodes.elevator.common.core.domain.{Call, Floor}
import pl.feelcodes.elevator.common.events.ManagerEvents

object ManagerRecoveryTests {
  val config = ConfigFactory
    .parseString(
      """
        |pekko.serialization.jackson.jackson-modules += "pl.feelcodes.elevator.common.serializable.ElevatorDomainSerialization"
        |pekko.actor {
        |  allow-java-serialization = off
        |  serialization-bindings {
        |    "pl.feelcodes.elevator.common.protocol.ManagerProtocol$Command"     = jackson-cbor
        |    "pl.feelcodes.elevator.common.events.ManagerEvents$Event"           = jackson-cbor
        |    "pl.feelcodes.elevator.common.logic.ManagerLogic$State"             = jackson-cbor
        |    "pl.feelcodes.elevator.common.protocol.ControllerProtocol$Command"  = jackson-cbor
        |    "pl.feelcodes.elevator.common.protocol.CoordinatorProtocol$Command" = jackson-cbor
        |  }
        |}
        |""".stripMargin
    )
    .withFallback(EventSourcedBehaviorTestKit.config)
    .withFallback(ConfigFactory.defaultReference())
    .resolve()
}

final class ManagerRecoveryTests
    extends ScalaTestWithActorTestKit(ManagerRecoveryTests.config)
    with AnyWordSpecLike
    with Matchers {

  private def newTestKit() =
    val coordinatorProbe = createTestProbe[Coordinator.Command]()
    val controllerProbe = createTestProbe[Controller.Command]()
    val coordinatorProvider =
      (name: String) => TestEntityRef(Coordinator.TypeKey, name, coordinatorProbe.ref)
    val controllerProvider =
      (name: String) => TestEntityRef(Controller.TypeKey, name, controllerProbe.ref)
    val kit = EventSourcedBehaviorTestKit[Manager.Command, ManagerEvents.Event, Manager.State](
      system,
      Manager("lift-a", coordinatorProvider, controllerProvider, _ => ())
    )
    (kit, coordinatorProbe, controllerProbe)

  "The Manager" should {

    "group calls into orders, assign each call, and hand the orders to the Controller" in {
      val (esTestKit, coordinatorProbe, controllerProbe) = newTestKit()

      val r = esTestKit.runCommand(Manager.Combine(List(Call("c1", Floor(3)), Call("c2", Floor(3)))))

      r.events should have size 1
      val created = r.events.head.asInstanceOf[ManagerEvents.OrderCreated]
      created.floor shouldBe 3
      created.callIds shouldBe Set("c1", "c2")

      controllerProbe.expectMessageType[Controller.Process].orders.map(_.id) shouldBe Set(created.orderId)
      val assigns = Set(
        coordinatorProbe.expectMessageType[Coordinator.AssignOrder],
        coordinatorProbe.expectMessageType[Coordinator.AssignOrder])
      assigns.map(_.callId) shouldBe Set("c1", "c2")
      assigns.map(_.orderId) shouldBe Set(created.orderId)
    }

    "mark an order done and tell the Coordinator each of its calls is done" in {
      val (esTestKit, coordinatorProbe, _) = newTestKit()
      esTestKit.runCommand(Manager.Combine(List(Call("c1", Floor(3)))))
      coordinatorProbe.expectMessageType[Coordinator.AssignOrder] // from Combine
      val orderId = esTestKit.getState().orders.keys.head

      esTestKit.runCommand(Manager.MarkOrderDone(orderId)).event shouldBe ManagerEvents.OrderDone(orderId)
      coordinatorProbe.expectMessage(Coordinator.MarkCallDone("c1"))
    }

    "attach a later call to the existing floor order and mark both calls done" in {
      val (esTestKit, coordinatorProbe, controllerProbe) = newTestKit()
      esTestKit.runCommand(Manager.Combine(List(Call("c1", Floor(3)))))
      coordinatorProbe.expectMessageType[Coordinator.AssignOrder]
      controllerProbe.expectMessageType[Controller.Process]
      val orderId = esTestKit.getState().orders.keys.head

      val r = esTestKit.runCommand(Manager.Combine(List(Call("c2", Floor(3)))))
      r.event shouldBe ManagerEvents.OrderExtended(orderId, Set("c2"))
      esTestKit.getState().orders(orderId).callIds shouldBe Set("c1", "c2")
      coordinatorProbe.expectMessage(Coordinator.AssignOrder("c2", orderId))
      controllerProbe.expectMessageType[Controller.Process].orders.map(_.id) shouldBe Set(orderId)

      esTestKit.runCommand(Manager.MarkOrderDone(orderId))
      Set(
        coordinatorProbe.expectMessageType[Coordinator.MarkCallDone],
        coordinatorProbe.expectMessageType[Coordinator.MarkCallDone]
      ).map(_.callId) shouldBe Set("c1", "c2")
    }
  }
}
