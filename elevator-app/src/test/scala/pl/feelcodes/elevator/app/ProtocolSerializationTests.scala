package pl.feelcodes.elevator.app

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.serialization.{SerializationExtension, Serializers}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.app.actors.{Controller, Coordinator, Manager, Operator}
import pl.feelcodes.elevator.common.core.domain.*
import pl.feelcodes.elevator.common.dto.CallDto
import pl.feelcodes.elevator.common.protocol.CoordinatorProtocol.{AddCalls, AssignOrder, MarkCallDone}
import pl.feelcodes.elevator.common.protocol.ManagerProtocol.{Combine, MarkOrderDone}

final class ProtocolSerializationTests extends AnyFunSuite, BeforeAndAfterAll:

  private val config = ConfigFactory.parseString(
    """
      |pekko.serialization.jackson.jackson-modules += "pl.feelcodes.elevator.common.serializable.ElevatorDomainSerialization"
      |pekko.actor {
      |  allow-java-serialization = off
      |  warn-about-java-serializer-usage = on
      |  serialization-bindings {
      |    "pl.feelcodes.elevator.common.protocol.ControllerProtocol$Command"  = jackson-cbor
      |    "pl.feelcodes.elevator.common.protocol.ManagerProtocol$Command"     = jackson-cbor
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

  private val state = ElevatorState(Direction.Up, Motion.Moving, Floor(3))
  private val order = Order("o-1", Floor(5), Set("c1", "c2"))

  test("Controller.Process round-trips"):
    val msg = Controller.Process(Set(order))
    assert(roundTrip(msg) == msg)

  test("Controller.PublishState round-trips (data only — no Elevator/Engine)"):
    val msg = Controller.PublishState(state)
    assert(roundTrip(msg) == msg)

  test("Controller.ChooseNext round-trips"):
    val msg = Controller.ChooseNext(Set(order))
    assert(roundTrip(msg) == msg)

  test("Operator.Move round-trips (data only — no Elevator/Engine)"):
    val msg = Operator.Move("lift-a", state, Command.Go(Direction.Up))
    assert(roundTrip(msg) == msg)

  test("Operator.Move(Stop) round-trips"):
    val msg = Operator.Move("lift-a", state, Command.Stop())
    assert(roundTrip(msg) == msg)

  test("Coordinator.AddCalls round-trips (original Kafka calls)"):
    val msg = AddCalls(List(CallDto("c1", "lift-a", 3)))
    assert(roundTrip(msg) == msg)

  test("Coordinator.AssignOrder round-trips"):
    assert(roundTrip(AssignOrder("c1", "o-1")) == AssignOrder("c1", "o-1"))

  test("Coordinator.MarkCallDone round-trips"):
    assert(roundTrip(MarkCallDone("c1")) == MarkCallDone("c1"))

  test("Manager.Combine round-trips"):
    val msg = Combine(List(Call("c1", Floor(3))))
    assert(roundTrip(msg) == msg)

  test("Manager.MarkOrderDone round-trips"):
    assert(roundTrip(MarkOrderDone("o-1")) == MarkOrderDone("o-1"))
