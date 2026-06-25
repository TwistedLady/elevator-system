package pl.feelcodes.elevator.app

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.serialization.{SerializationExtension, Serializers}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.app.actors.{Controller, Operator}
import pl.feelcodes.elevator.common.core.*
import pl.feelcodes.elevator.common.dto.ElevatorOrderDto
import pl.feelcodes.elevator.common.protocol.CoordinatorProtocol.{AddOriginalStream, MarkOrderDone}

final class ProtocolSerializationTests extends AnyFunSuite, BeforeAndAfterAll:

  private val config = ConfigFactory.parseString(
    """
      |pekko.serialization.jackson.jackson-modules += "pl.feelcodes.elevator.app.DomainJacksonModule"
      |pekko.actor {
      |  allow-java-serialization = off
      |  warn-about-java-serializer-usage = on
      |  serialization-bindings {
      |    "pl.feelcodes.elevator.common.protocol.ControllerProtocol$Command"  = jackson-cbor
      |    "pl.feelcodes.elevator.common.protocol.OperatorProtocol$Command"    = jackson-cbor
      |    "pl.feelcodes.elevator.common.protocol.CoordinatorProtocol$Command" = jackson-cbor
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

  private val state =
    ElevatorState(Direction.Up, Motion.Moving, Floor(3))

  test("Controller.AddUniqueOrderSet round-trips"):
    val msg = Controller.AddUniqueOrderSet(Set(ElevatorOrder("tag-1", Floor(5))))
    assert(roundTrip(msg) == msg)

  test("Controller.PublishState round-trips (data only — no Elevator/Engine)"):
    val msg = Controller.PublishState(state)
    assert(roundTrip(msg) == msg)

  test("Controller.ChooseNextOrder round-trips"):
    val msg = Controller.ChooseNextOrder(Set(ElevatorOrder("tag-1", Floor(5))))
    assert(roundTrip(msg) == msg)

  test("Operator.Move round-trips (data only — no Elevator/Engine)"):
    val msg = Operator.Move("lift-a", state, Command.Go(Direction.Up))
    assert(roundTrip(msg) == msg)

  test("Operator.Move(Stop) round-trips"):
    val msg = Operator.Move("lift-a", state, Command.Stop())
    assert(roundTrip(msg) == msg)

  test("Coordinator.AddOriginalStream round-trips (original Kafka orders)"):
    val msg = AddOriginalStream(List(ElevatorOrderDto("tag-1", "lift-a", 3)))
    assert(roundTrip(msg) == msg)

  test("Coordinator.MarkOrderDone round-trips"):
    val msg = MarkOrderDone("tag-1")
    assert(roundTrip(msg) == msg)
