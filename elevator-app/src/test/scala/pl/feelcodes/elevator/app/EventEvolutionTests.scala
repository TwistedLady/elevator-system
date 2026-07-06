package pl.feelcodes.elevator.app

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.serialization.SerializationExtension
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.app.actors.Controller
import pl.feelcodes.elevator.common.events.CoordinatorEvents
import pl.feelcodes.elevator.common.core.domain.*

import java.util.Base64

final class EventEvolutionTests extends AnyFunSuite, BeforeAndAfterAll:

  private val config = ConfigFactory.parseString(
    """
      |pekko.serialization.jackson.jackson-modules += "pl.feelcodes.elevator.common.serializable.ElevatorDomainSerialization"
      |pekko.actor {
      |  allow-java-serialization = off
      |  serialization-bindings {
      |    "pl.feelcodes.elevator.common.events.ControllerEvents$Event"  = jackson-cbor
      |    "pl.feelcodes.elevator.common.events.CoordinatorEvents$Event" = jackson-cbor
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

  test("Controller.OrderAdded (v1) still recovers"):
    val recovered = fromGolden[Controller.Event](
      "pl.feelcodes.elevator.common.events.ControllerEvents$OrderAdded",
      "v2VvcmRlcr9jdGFnY28tMWVmbG9vcr9jbnVtA////w=="
    )
    assert(recovered == Controller.OrderAdded(ElevatorOrder("o-1", Floor(3))))

  test("Controller.WaitingSet (v1) still recovers"):
    val recovered = fromGolden[Controller.Event](
      "pl.feelcodes.elevator.common.events.ControllerEvents$WaitingSet",
      "v2d3YWl0aW5n9f8="
    )
    assert(recovered == Controller.WaitingSet(true))

  test("Controller.ElevatorStateUpdated (v1) still recovers"):
    val recovered = fromGolden[Controller.Event](
      "pl.feelcodes.elevator.common.events.ControllerEvents$ElevatorStateUpdated",
      "v2VzdGF0Zb9pZGlyZWN0aW9uYlVwZm1vdGlvbmZNb3ZpbmdlZmxvb3K/Y251bQP///8="
    )
    assert(recovered == Controller.ElevatorStateUpdated(ElevatorState(Direction.Up, Motion.Moving, Floor(3))))

  test("Coordinator.OrderAccepted (v1) still recovers"):
    val recovered = fromGolden[CoordinatorEvents.Event](
      "pl.feelcodes.elevator.common.events.CoordinatorEvents$OrderAccepted",
      "v2N0YWdldGFnLTFsZWxldmF0b3JOYW1lZmxpZnQtYWVmbG9vcgP/"
    )
    assert(recovered == CoordinatorEvents.OrderAccepted("tag-1", "lift-a", 3))
