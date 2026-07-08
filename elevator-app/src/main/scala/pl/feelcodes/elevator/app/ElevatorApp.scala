package pl.feelcodes.elevator.app

import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.apache.pekko.actor.typed.{ActorSystem, DispatcherSelector}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import org.apache.pekko.cluster.typed.{ClusterSingleton, SingletonActor}
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap
import org.apache.pekko.management.scaladsl.PekkoManagement
import pl.feelcodes.elevator.app.actors.*
import pl.feelcodes.elevator.common.core.engine.{Elevator, DoorEngine}
import pl.feelcodes.elevator.common.core.domain.DoorState
import pl.feelcodes.elevator.common.dto.DoorStateDto
import pl.feelcodes.elevator.app.inbound.{CallConsumer, CallDedup}
import pl.feelcodes.elevator.app.outbound.Publishers
import pl.feelcodes.elevator.app.readside.{CallStatusProjection, ElevatorStateProjection, OrderStatusProjection}

import java.nio.file.Path
import scala.concurrent.duration.*
import scala.jdk.DurationConverters.*

object ElevatorApp extends App {

  // On Kubernetes the pods form one cluster via Pekko Management + Cluster Bootstrap (k8s-api
  // discovery). Bootstrap only runs if the static seed-nodes are empty, so we clear them here.
  // Locally (flag off) the seed-nodes from application.conf give a single-node cluster.
  private val bootstrapEnabled = sys.env.get("ELEVATOR_CLUSTER_BOOTSTRAP").contains("on")
  private val config =
    val base = ConfigFactory.load()
    if bootstrapEnabled then
      base.withValue("pekko.cluster.seed-nodes", ConfigValueFactory.fromIterable(java.util.List.of()))
    else base

  val system: ActorSystem[Nothing] =
    ActorSystem(
      Behaviors.setup[Nothing] { ctx =>
        if bootstrapEnabled then
          PekkoManagement(ctx.system).start()
          ClusterBootstrap(ctx.system).start()

        val publishers = Publishers(ctx.system)

        val sharding = ClusterSharding(ctx.system)
        val operatorProvider = sharding.entityRefFor(Operator.TypeKey, _)
        val controllerProvider = sharding.entityRefFor(Controller.TypeKey, _)
        val managerProvider = sharding.entityRefFor(Manager.TypeKey, _)
        val coordinatorProvider = sharding.entityRefFor(Coordinator.TypeKey, _)
        val doormanProvider = sharding.entityRefFor(Doorman.TypeKey, _)

        val suspendManager = ClusterSingleton(ctx.system)
          .init(SingletonActor(SuspendManager(), "SuspendManager"))

        val engineMode = new EngineMode(ctx.system.settings.config.getString("elevator.engine"))
        val enginePath = Path.of(ctx.system.settings.config.getString("elevator.engine-file"))
        ctx.system.scheduler.scheduleWithFixedDelay(0.seconds, 5.seconds) { () =>
          val _ = engineMode.refreshFrom(enginePath)
        }(ctx.executionContext)

        val doorDwell = ctx.system.settings.config.getDuration("elevator.door-dwell").toScala

        val buildElevator: Operator.BuildElevator = (name, state) =>
          engineMode.current match
            case EngineMode.Fast => Elevator.fast(name)(state)
            case EngineMode.Slow => Elevator.slow(name)(state)
            case other => throw new IllegalArgumentException(s"Unknown engine '$other'")

        sharding.init(Entity(Operator.TypeKey) { _ =>
          val publishMove: Operator.PublishMove =
            (name, state) => controllerProvider(name) ! Controller.MarkExecuted(state)
          Operator(publishMove, buildElevator)
        }.withEntityProps(DispatcherSelector.fromConfig("elevator-blocking-dispatcher")))
        sharding.init(Entity(Doorman.TypeKey) { _ =>
          val publishDoor: Doorman.PublishDoor = (name, floor, doorState) =>
            publishers.door.publish(DoorStateDto(name, floor.num, doorState.toString))
            if doorState == DoorState.Closed then controllerProvider(name) ! Controller.DoorClosed(floor)
          Doorman(publishDoor, DoorEngine(doorDwell))
        }.withEntityProps(DispatcherSelector.fromConfig("elevator-blocking-dispatcher")))
        sharding.init(Entity(Controller.TypeKey) { e =>
          Controller(e.entityId, operatorProvider, managerProvider, suspendManager, doormanProvider, publishers.elevator.publish)
        })
        sharding.init(Entity(Manager.TypeKey) { e =>
          Manager(e.entityId, coordinatorProvider, controllerProvider, publishers.order.publish)
        })
        sharding.init(Entity(Coordinator.TypeKey) { e =>
          Coordinator(e.entityId, managerProvider, publishers.call.publish)
        })

        val dedup = CallDedup(ctx.system.settings.config)
        CallConsumer.run(ctx.system, coordinatorProvider, dedup)

        ElevatorStateProjection.init(ctx.system)
        OrderStatusProjection.init(ctx.system)
        CallStatusProjection.init(ctx.system)

        Behaviors.empty
      },
      "elevator-cluster",
      config
    )
}
