package pl.feelcodes.elevator.app

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.serialization.{SerializationExtension, Serializers}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.common.core.domain.*
import pl.feelcodes.elevator.common.events.{ControllerEvents, CoordinatorEvents, ManagerEvents}

final class EventEvolutionTests extends AnyFunSuite, BeforeAndAfterAll:

  private val config = ConfigFactory.parseString(
    """
      |pekko.serialization.jackson.jackson-modules += "pl.feelcodes.elevator.common.serializable.ElevatorDomainSerialization"
      |pekko.actor {
      |  allow-java-serialization = off
      |  serialization-bindings {
      |    "pl.feelcodes.elevator.common.events.ControllerEvents$Event"  = jackson-cbor
      |    "pl.feelcodes.elevator.common.events.CoordinatorEvents$Event" = jackson-cbor
      |    "pl.feelcodes.elevator.common.events.ManagerEvents$Event"     = jackson-cbor
      |  }
      |}
      |""".stripMargin
  ).withFallback(ConfigFactory.defaultReference()).resolve()

  private val testKit = ActorTestKit("evolution", config)
  private val serialization = SerializationExtension(testKit.system.classicSystem)

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private def roundTrip[T <: AnyRef](msg: T): T =
    val serializer = serialization.findSerializerFor(msg)
    val bytes = serializer.toBinary(msg)
    val manifest = Serializers.manifestFor(serializer, msg)
    serialization.deserialize(bytes, serializer.identifier, manifest).get.asInstanceOf[T]

  test("ControllerEvents.OrderAccepted round-trips"):
    val e = ControllerEvents.OrderAccepted(Order("o-1", Floor(3), Set("c1")))
    assert(roundTrip[ControllerEvents.Event](e) == e)

  test("ControllerEvents.WaitingSet round-trips"):
    val e = ControllerEvents.WaitingSet(true)
    assert(roundTrip[ControllerEvents.Event](e) == e)

  test("ControllerEvents.ElevatorStateUpdated round-trips"):
    val e = ControllerEvents.ElevatorStateUpdated(ElevatorState(Direction.Up, Motion.Moving, Floor(3)))
    assert(roundTrip[ControllerEvents.Event](e) == e)

  test("CoordinatorEvents (call lifecycle) round-trip"):
    val received = CoordinatorEvents.CallReceived("c1", 3)
    val assigned = CoordinatorEvents.CallAssigned("c1", "o-1")
    val done = CoordinatorEvents.CallDone("c1")
    assert(roundTrip[CoordinatorEvents.Event](received) == received)
    assert(roundTrip[CoordinatorEvents.Event](assigned) == assigned)
    assert(roundTrip[CoordinatorEvents.Event](done) == done)

  test("ManagerEvents (order lifecycle) round-trip"):
    val created = ManagerEvents.OrderCreated("o-1", 3, Set("c1", "c2"))
    val extended = ManagerEvents.OrderExtended("o-1", Set("c3"))
    val done = ManagerEvents.OrderDone("o-1")
    assert(roundTrip[ManagerEvents.Event](created) == created)
    assert(roundTrip[ManagerEvents.Event](extended) == extended)
    assert(roundTrip[ManagerEvents.Event](done) == done)
