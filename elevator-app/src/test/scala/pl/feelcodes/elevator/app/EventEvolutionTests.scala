package pl.feelcodes.elevator.app

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.serialization.SerializationExtension
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.app.actors.{Controller, Coordinator}
import pl.feelcodes.elevator.common.core.*

import java.util.Base64

/**
 * Golden-master schema-evolution guard for persisted events.
 *
 * Disaster recovery only works if events written by an OLDER build still deserialize into the
 * CURRENT classes. The byte strings below were captured from a known-good version (jackson-cbor,
 * serializer id 33). Each test deserializes those frozen bytes and asserts the result equals the
 * event we expect today.
 *
 * If you change an event class incompatibly (rename/remove a field, change a type), one of these
 * will fail — that is the warning that every existing journal would fail to recover. Adding a
 * field with a default is safe and will keep passing.
 *
 * To re-freeze after an INTENTIONAL, compatible change: see git history for the `_GoldenCapture`
 * helper that prints fresh `GOLDEN|label|id|manifest|base64` lines.
 */
final class EventEvolutionTests extends AnyFunSuite, BeforeAndAfterAll:

  private val config = ConfigFactory.parseString(
    """
      |pekko.serialization.jackson.jackson-modules += "pl.feelcodes.elevator.app.DomainJacksonModule"
      |pekko.actor {
      |  allow-java-serialization = off
      |  serialization-bindings {
      |    "pl.feelcodes.elevator.common.protocol.ControllerProtocol$Event"  = jackson-cbor
      |    "pl.feelcodes.elevator.common.protocol.CoordinatorProtocol$Event" = jackson-cbor
      |  }
      |}
      |""".stripMargin
  ).withFallback(ConfigFactory.defaultReference()).resolve()

  private val testKit = ActorTestKit("evolution", config)
  private val serialization = SerializationExtension(testKit.system.classicSystem)

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private val JacksonCborId = 33

  private def fromGolden[T](manifest: String, base64: String): T =
    val bytes = Base64.getDecoder.decode(base64)
    serialization.deserialize(bytes, JacksonCborId, manifest).get.asInstanceOf[T]

  test("Controller.RequestAdded (v1) still recovers"):
    val recovered = fromGolden[Controller.Event](
      "pl.feelcodes.elevator.common.protocol.ControllerProtocol$RequestAdded",
      "v2dyZXF1ZXN0v2N0YWdjby0xZWZsb29yv2NudW0D////"
    )
    assert(recovered == Controller.RequestAdded(ElevatorOrder("o-1", Floor(3))))

  test("Controller.WaitingSet (v1) still recovers"):
    val recovered = fromGolden[Controller.Event](
      "pl.feelcodes.elevator.common.protocol.ControllerProtocol$WaitingSet",
      "v2d3YWl0aW5n9f8="
    )
    assert(recovered == Controller.WaitingSet(true))

  test("Controller.ElevatorStateUpdated (v1) still recovers"):
    val recovered = fromGolden[Controller.Event](
      "pl.feelcodes.elevator.common.protocol.ControllerProtocol$ElevatorStateUpdated",
      "v2VzdGF0Zb9pZGlyZWN0aW9uYlVwZm1vdGlvbmZNb3ZpbmdlZmxvb3K/Y251bQP//3BvcmRlcldpdGhDb21tYW5kv2VvcmRlcr9jdGFnY28tMWVmbG9vcr9jbnVtA///Z2NvbW1hbmRlR286VXD//w=="
    )
    assert(
      recovered == Controller.ElevatorStateUpdated(
        ElevatorState(Direction.Up, Motion.Moving, Floor(3)),
        OrderElevatorCommand(ElevatorOrder("o-1", Floor(3)), Command.Go(Direction.Up))
      )
    )

  test("Coordinator.Accepted (v1) still recovers"):
    val recovered = fromGolden[Coordinator.Event](
      "pl.feelcodes.elevator.common.protocol.CoordinatorProtocol$Accepted",
      "v2N0YWdldGFnLTFsZWxldmF0b3JOYW1lZmxpZnQtYWVmbG9vcgP/"
    )
    assert(recovered == Coordinator.Accepted("tag-1", "lift-a", 3))
