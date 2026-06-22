package pl.feelcodes.elevator.app

import com.typesafe.config.ConfigFactory
import org.apache.pekko.cluster.sharding.typed.testkit.scaladsl.TestEntityRef
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pl.feelcodes.elevator.app.actors.{Controller, Operator}
import pl.feelcodes.elevator.common.core.*

/**
 * Disaster-recovery test for the Controller's event-sourced state.
 *
 * `EventSourcedBehaviorTestKit.restart()` is the in-test equivalent of a crash: it throws away
 * the actor's in-memory state and rebuilds it purely by replaying the journal. If recovery is
 * sound, the state after the "crash" is identical to the state before it.
 */
object ControllerRecoveryTests {
  // The persistence-testkit journal + the same jackson-cbor bindings used in production, so the
  // testkit's serialize/deserialize round-trip of every event and the state actually exercises
  // the real wire format (not Java serialization).
  val config = ConfigFactory
    .parseString(
      """
        |pekko.serialization.jackson.jackson-modules += "pl.feelcodes.elevator.app.DomainJacksonModule"
        |pekko.actor {
        |  allow-java-serialization = off
        |  serialization-bindings {
        |    "pl.feelcodes.elevator.app.actors.Controller$Command" = jackson-cbor
        |    "pl.feelcodes.elevator.app.actors.Controller$Event"   = jackson-cbor
        |    "pl.feelcodes.elevator.app.actors.Controller$State"   = jackson-cbor
        |    "pl.feelcodes.elevator.app.actors.Operator$Command"   = jackson-cbor
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

  private val operatorProbe = createTestProbe[Operator.Command]()
  private val operatorProvider =
    (name: String) => TestEntityRef(Operator.TypeKey, name, operatorProbe.ref)

  private def newTestKit() =
    EventSourcedBehaviorTestKit[Controller.Command, Controller.Event, Controller.State](
      system,
      Controller("lift-a", operatorProvider)
    )

  "The Controller journal" should {

    "rebuild the full state after a crash when the order was served" in {
      val esTestKit = newTestKit()
      val order = ElevatorOrder("o-1", Floor(3))

      esTestKit.runCommand(Controller.AddRequest(order)).event shouldBe Controller.RequestAdded(order)

      // The Operator reports a move that REACHES floor 3 -> the served order is cleared.
      val reached = ElevatorState(Direction.Up, Motion.Moving, Floor(3))
      val owc = OrderElevatorCommand(order, Command.Go(Direction.Up))
      esTestKit.runCommand(Controller.MoveExecuted(reached, owc))

      val before = esTestKit.getState()
      before.elevatorState shouldBe reached
      before.requests shouldBe empty // served order removed

      esTestKit.restart() // <-- the "disaster": drop memory, recover from the journal

      esTestKit.getState() shouldBe before // recovered identically
    }

    "keep an outstanding (unserved) request after a crash" in {
      val esTestKit = newTestKit()
      val order = ElevatorOrder("o-2", Floor(7))

      esTestKit.runCommand(Controller.AddRequest(order))

      // A move that does NOT reach floor 7 -> the request must remain pending.
      val midway = ElevatorState(Direction.Up, Motion.Moving, Floor(4))
      val owc = OrderElevatorCommand(order, Command.Go(Direction.Up))
      esTestKit.runCommand(Controller.MoveExecuted(midway, owc))

      esTestKit.getState().requests should contain(order)

      esTestKit.restart()

      // The pending request survived the crash, rebuilt from the journal.
      esTestKit.getState().requests should contain(order)
      esTestKit.getState().elevatorState.floor shouldBe Floor(4)
    }
  }
}
