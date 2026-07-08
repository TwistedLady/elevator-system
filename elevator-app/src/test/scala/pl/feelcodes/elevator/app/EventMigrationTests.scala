package pl.feelcodes.elevator.app

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.serialization.{SerializationExtension, Serializers}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.common.core.domain.*
import pl.feelcodes.elevator.common.events.ControllerEvents.OrderAdded
import pl.feelcodes.elevator.common.logic.ControllerLogic

final class EventMigrationTests extends AnyFunSuite, BeforeAndAfterAll:

  private val config = ConfigFactory.parseString(
    """
      |pekko.serialization.jackson.jackson-modules += "pl.feelcodes.elevator.common.serializable.ElevatorDomainSerialization"
      |pekko.actor {
      |  allow-java-serialization = off
      |  serialization-bindings {
      |    "pl.feelcodes.elevator.common.events.ControllerEvents$Event" = jackson-cbor
      |  }
      |}
      |""".stripMargin
  ).withFallback(ConfigFactory.defaultReference()).resolve()

  private val testKit = ActorTestKit("migration", config)
  private val serialization = SerializationExtension(testKit.system.classicSystem)

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private val order = Order("o-1", Floor(5), Set("c1", "c2"))

  test("legacy OrderAdded event still deserializes (pre-rename journal compat)"):
    val ev = OrderAdded(order)
    val serializer = serialization.findSerializerFor(ev)
    val bytes = serializer.toBinary(ev)
    val manifest = Serializers.manifestFor(serializer, ev)
    assert(serialization.deserialize(bytes, serializer.identifier, manifest).get == ev)

  test("evolve treats OrderAdded the same as OrderAccepted"):
    val evolved = ControllerLogic.evolve(ControllerLogic.State.initial("e1"), OrderAdded(order))
    assert(evolved.orders == Set(order))
