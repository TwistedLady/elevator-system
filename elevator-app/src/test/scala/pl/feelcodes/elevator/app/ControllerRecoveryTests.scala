package pl.feelcodes.elevator.app

/** Controller event-sourcing recovery: state rebuilds correctly across crashes and restarts. */

import com.typesafe.config.ConfigFactory
import org.apache.pekko.cluster.sharding.typed.testkit.scaladsl.TestEntityRef
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pl.feelcodes.elevator.app.actors.{Controller, Doorman, Manager, Operator, SuspendManager}
import pl.feelcodes.elevator.common.core.domain.*

object ControllerRecoveryTests {
  val config = ConfigFactory
    .parseString(
      """
        |pekko.serialization.jackson.jackson-modules += "pl.feelcodes.elevator.common.serializable.ElevatorDomainSerialization"
        |pekko.actor {
        |  allow-java-serialization = off
        |  serialization-bindings {
        |    "pl.feelcodes.elevator.common.protocol.ControllerProtocol$Command"  = jackson-cbor
        |    "pl.feelcodes.elevator.common.events.ControllerEvents$Event"         = jackson-cbor
        |    "pl.feelcodes.elevator.common.logic.ControllerLogic$State"           = jackson-cbor
        |    "pl.feelcodes.elevator.common.protocol.OperatorProtocol$Command"     = jackson-cbor
        |    "pl.feelcodes.elevator.common.protocol.ManagerProtocol$Command"      = jackson-cbor
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
    val managerProbe = createTestProbe[Manager.Command]()
    val doormanProbe = createTestProbe[Doorman.Command]()
    val operatorProvider =
      (name: String) => TestEntityRef(Operator.TypeKey, name, operatorProbe.ref)
    val managerProvider =
      (name: String) => TestEntityRef(Manager.TypeKey, name, managerProbe.ref)
    val doormanProvider =
      (name: String) => TestEntityRef(Doorman.TypeKey, name, doormanProbe.ref)
    val suspendManager = spawn(SuspendManager())
    val kit = EventSourcedBehaviorTestKit[Controller.Command, Controller.Event, Controller.State](
      system,
      Controller("lift-a", operatorProvider, managerProvider, suspendManager, doormanProvider, _ => ())
    )
    (kit, operatorProbe, managerProbe, doormanProbe)

  private def orderAt(id: String, floor: Int) = Order(id, Floor(floor), Set(s"c-$id"))

  "The Controller journal" should {

    "rebuild state after a crash and mark the served order done" in {
      val (esTestKit, _, managerProbe, _) = newTestKit()
      val order = orderAt("o-1", 3)

      esTestKit.runCommand(Controller.Process(Set(order)))
        .events should contain(Controller.OrderAccepted(order))

      val reached = ElevatorState(Direction.Up, Motion.Stopped, Floor(3))
      esTestKit.runCommand(Controller.MarkExecuted(reached))

      managerProbe.expectMessage(Manager.MarkDone("o-1"))

      val before = esTestKit.getState()
      before.elevatorState shouldBe reached
      before.orders shouldBe empty

      esTestKit.restart()
      esTestKit.getState() shouldBe before
    }

    "keep an outstanding (unserved) request after a crash" in {
      val (esTestKit, _, managerProbe, _) = newTestKit()
      val order = orderAt("o-2", 7)

      esTestKit.runCommand(Controller.Process(Set(order)))

      val midway = ElevatorState(Direction.Up, Motion.Moving, Floor(4))
      esTestKit.runCommand(Controller.MarkExecuted(midway))

      managerProbe.expectNoMessage()
      esTestKit.getState().orders should contain(order)

      esTestKit.restart()
      esTestKit.getState().orders should contain(order)
      esTestKit.getState().elevatorState.floor shouldBe Floor(4)
    }

    "redeliver the in-flight move after a crash that happened while waiting for the Operator" in {
      val (esTestKit, operatorProbe, _, _) = newTestKit()
      val order = orderAt("o-3", 9)

      esTestKit.runCommand(Controller.Process(Set(order)))
      esTestKit.runCommand(Controller.ChooseNext(Set(order)))
      esTestKit.getState().waiting shouldBe true

      esTestKit.runCommand(Controller.MoveDecision(true))
      operatorProbe.expectMessageType[Operator.Move]

      esTestKit.restart()

      esTestKit.runCommand(Controller.MoveDecision(true))
      val redelivered = operatorProbe.expectMessageType[Operator.Move]
      redelivered.command shouldBe Command.Go(Direction.Up)
    }

    "open the door on reaching a served floor and block moves until it closes" in {
      val (esTestKit, operatorProbe, managerProbe, doormanProbe) = newTestKit()
      val order = orderAt("o-5", 3)

      esTestKit.runCommand(Controller.Process(Set(order)))
      esTestKit.runCommand(Controller.ChooseNext(Set(order)))
      operatorProbe.expectMessageType[Operator.Move]

      esTestKit.runCommand(Controller.MarkExecuted(ElevatorState(Direction.Up, Motion.Moving, Floor(3))))
      managerProbe.expectMessage(Manager.MarkDone("o-5"))
      doormanProbe.expectMessage(Doorman.Serve("lift-a", Floor(3)))
      esTestKit.getState().waiting shouldBe true

      esTestKit.runCommand(Controller.DoorClosed(Floor(3)))
      operatorProbe.expectMessageType[Operator.Move].command shouldBe Command.Stop()
    }

    "release the waiting flag on MoveRetry so a failed ask does not strand the car" in {
      val (esTestKit, _, _, _) = newTestKit()
      val order = orderAt("o-4", 6)

      esTestKit.runCommand(Controller.Process(Set(order)))
      esTestKit.runCommand(Controller.ChooseNext(Set(order)))
      esTestKit.getState().waiting shouldBe true

      esTestKit.runCommand(Controller.MoveRetry).events should contain(Controller.WaitingSet(false))
    }
  }
}
