package pl.feelcodes.elevator.app

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.serialization.{SerializationExtension, Serializers}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.app.actors.{Controller, Coordinator, Operator}
import pl.feelcodes.elevator.common.core.*

/**
 * Proves every actor message survives a real serialize -> deserialize round trip with the
 * same jackson-cbor serializer cluster sharding uses across nodes. On a single node Pekko
 * skips serialization, so these bugs (e.g. shipping the behavioral `Elevator`/`Engine`) only
 * show up here or in production — never in the demo. This is the regression guard for that.
 */
final class ProtocolSerializationTests extends AnyFunSuite, BeforeAndAfterAll:

  private val config = ConfigFactory.parseString(
    """
      |pekko.serialization.jackson.jackson-modules += "pl.feelcodes.elevator.app.DomainJacksonModule"
      |pekko.actor {
      |  allow-java-serialization = off
      |  warn-about-java-serializer-usage = on
      |  serialization-bindings {
      |    "pl.feelcodes.elevator.common.protocol.ControllerProtocol$Command" = jackson-cbor
      |    "pl.feelcodes.elevator.common.protocol.OperatorProtocol$Command"   = jackson-cbor
      |    "pl.feelcodes.elevator.app.actors.Coordinator$Command"             = jackson-cbor
      |  }
      |}
      |""".stripMargin
  ).withFallback(ConfigFactory.defaultReference()).resolve()

  private val testKit = ActorTestKit("proto", config)
  private val serialization = SerializationExtension(testKit.system.classicSystem)

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private def roundTrip[T <: AnyRef](msg: T): T =
    val serializer = serialization.findSerializerFor(msg)
    val bytes = serializer.toBinary(msg)
    val manifest = Serializers.manifestFor(serializer, msg)
    serialization.deserialize(bytes, serializer.identifier, manifest).get.asInstanceOf[T]

  private val owc =
    OrderElevatorCommand(ElevatorOrder("tag-1", Floor(5)), Command.Go(Direction.Up))
  private val state =
    ElevatorState(Direction.Up, Motion.Moving, Floor(3))

  test("Controller.AddRequest round-trips"):
    val msg = Controller.AddRequest(ElevatorOrder("tag-1", Floor(5)))
    assert(roundTrip(msg) == msg)

  test("Controller.MoveExecuted round-trips (data only — no Elevator/Engine)"):
    val msg = Controller.MoveExecuted(state, owc)
    assert(roundTrip(msg) == msg)

  test("Operator.Move round-trips (data only — no Elevator/Engine)"):
    val msg = Operator.Move("lift-a", state, owc)
    assert(roundTrip(msg) == msg)

  test("Operator.Stop round-trips"):
    val msg = Operator.Stop("lift-a", state)
    assert(roundTrip(msg) == msg)

  test("Controller.Stopped round-trips (Operator -> Controller)"):
    val msg = Controller.Stopped(state)
    assert(roundTrip(msg) == msg)

  test("Coordinator.Reached round-trips (Controller -> Coordinator across nodes)"):
    val msg = Coordinator.Reached(5)
    assert(roundTrip(msg) == msg)
