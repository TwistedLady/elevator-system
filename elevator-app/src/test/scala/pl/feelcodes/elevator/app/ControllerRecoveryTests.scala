package pl.feelcodes.elevator.app

import com.typesafe.config.ConfigFactory
import org.apache.pekko.cluster.sharding.typed.testkit.scaladsl.TestEntityRef
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pl.feelcodes.elevator.app.actors.{Controller, Coordinator, Operator}
import pl.feelcodes.elevator.common.core.*

object ControllerRecoveryTests {
  val config = ConfigFactory
    .parseString(
      """
        |pekko.serialization.jackson.jackson-modules += "pl.feelcodes.elevator.app.DomainJacksonModule"
        |pekko.actor {
        |  allow-java-serialization = off
        |  serialization-bindings {
        |    "pl.feelcodes.elevator.common.protocol.ControllerProtocol$Command"  = jackson-cbor
        |    "pl.feelcodes.elevator.common.events.ControllerEvents$Event"         = jackson-cbor
        |    "pl.feelcodes.elevator.common.logic.ControllerLogic$State"           = jackson-cbor
        |    "pl.feelcodes.elevator.common.protocol.OperatorProtocol$Command"     = jackson-cbor
        |    "pl.feelcodes.elevator.common.protocol.CoordinatorProtocol$Command"  = jackson-cbor
        |  }
        |}
        |""".stripMargin
    )
    .withFallback(EventSourcedBehaviorTestKit.config)
    .withFallback(ConfigFactory.defaultReference())
    .resolve()
}

final class ControllerRecoveryTests
    extends ScalaTestWithActorTestKit(ControllerRecoveryTests.config)
    with AnyWordSpecLike
    with Matchers {

  private def newTestKit() =
    val operatorProbe = createTestProbe[Operator.Command]()
    val coordinatorProbe = createTestProbe[Coordinator.Command]()
    val operatorProvider =
      (name: String) => TestEntityRef(Operator.TypeKey, name, operatorProbe.ref)
    val coordinatorProvider =
      (name: String) => TestEntityRef(Coordinator.TypeKey, name, coordinatorProbe.ref)
    val kit = EventSourcedBehaviorTestKit[Controller.Command, Controller.Event, Controller.State](
      system,
      Controller("lift-a", operatorProvider, coordinatorProvider, _ => ())
    )
    (kit, operatorProbe, coordinatorProbe)

  "The Controller journal" should {

    "rebuild state after a crash and mark the served order done" in {
      val (esTestKit, _, coordinatorProbe) = newTestKit()
      val order = ElevatorOrder("o-1", Floor(3))

      esTestKit.runCommand(Controller.AddUniqueOrderSet(Set(order)))
        .events should contain(Controller.OrderAdded(order))

      val reached = ElevatorState(Direction.Up, Motion.Stopped, Floor(3))
      esTestKit.runCommand(Controller.PublishState(reached))

      coordinatorProbe.expectMessage(Coordinator.MarkOrderDone("o-1"))

      val before = esTestKit.getState()
      before.elevatorState shouldBe reached
      before.orders shouldBe empty

      esTestKit.restart()
      esTestKit.getState() shouldBe before
    }

    "keep an outstanding (unserved) request after a crash" in {
      val (esTestKit, _, coordinatorProbe) = newTestKit()
      val order = ElevatorOrder("o-2", Floor(7))

      esTestKit.runCommand(Controller.AddUniqueOrderSet(Set(order)))

      val midway = ElevatorState(Direction.Up, Motion.Moving, Floor(4))
      esTestKit.runCommand(Controller.PublishState(midway))

      coordinatorProbe.expectNoMessage()
      esTestKit.getState().orders should contain(order)

      esTestKit.restart()
      esTestKit.getState().orders should contain(order)
      esTestKit.getState().elevatorState.floor shouldBe Floor(4)
    }

    "redeliver the in-flight move after a crash that happened while waiting for the Operator" in {
      val (esTestKit, operatorProbe, _) = newTestKit()
      val order = ElevatorOrder("o-3", Floor(9))

      esTestKit.runCommand(Controller.AddUniqueOrderSet(Set(order)))

      esTestKit.runCommand(Controller.ChooseNextOrder(Set(order)))
      operatorProbe.expectMessageType[Operator.Move]
      esTestKit.getState().waiting shouldBe true

      esTestKit.restart()

      val redelivered = operatorProbe.expectMessageType[Operator.Move]
      redelivered.command shouldBe Command.Go(Direction.Up)
    }
  }
}
