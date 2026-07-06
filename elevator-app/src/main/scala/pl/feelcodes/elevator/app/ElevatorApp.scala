package pl.feelcodes.elevator.app

import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.apache.pekko.actor.typed.{ActorSystem, DispatcherSelector}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap
import org.apache.pekko.management.scaladsl.PekkoManagement
import pl.feelcodes.elevator.app.actors.*
import pl.feelcodes.elevator.common.core.engine.Elevator
import pl.feelcodes.elevator.app.inbound.{OrderConsumer, OrderDedup}
import pl.feelcodes.elevator.app.outbound.StatePublisher
import pl.feelcodes.elevator.app.readside.{ElevatorStateProjection, OrderStatusProjection}

import java.nio.file.Path
import scala.concurrent.duration.*

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

        val statePublisher = StatePublisher(ctx.system)

        val sharding = ClusterSharding(ctx.system)
        val operatorProvider = sharding.entityRefFor(Operator.TypeKey, _)
        val controllerProvider = sharding.entityRefFor(Controller.TypeKey, _)
        val coordinatorProvider = sharding.entityRefFor(Coordinator.TypeKey, _)

        val engineMode = new EngineMode(ctx.system.settings.config.getString("elevator.engine"))
        val enginePath = Path.of(ctx.system.settings.config.getString("elevator.engine-file"))
        ctx.system.scheduler.scheduleWithFixedDelay(0.seconds, 5.seconds) { () =>
          engineMode.refreshFrom(enginePath).foreach(v => ctx.log.info("engine mode -> {}", v))
        }(ctx.executionContext)

        val buildElevator: Operator.BuildElevator = (name, state) =>
          engineMode.current match
            case EngineMode.Fast => Elevator.fast(name)(state)
            case EngineMode.Slow => Elevator.slow(name)(state)
            case other => throw new IllegalArgumentException(s"Unknown engine '$other'")

        sharding.init(Entity(Operator.TypeKey) { _ =>
          val publishMove: Operator.PublishMove =
            (name, state) => controllerProvider(name) ! Controller.PublishState(state)
          Operator(publishMove, buildElevator)
        }.withEntityProps(DispatcherSelector.fromConfig("elevator-blocking-dispatcher")))
        sharding.init(Entity(Controller.TypeKey) { e =>
          Controller(e.entityId, operatorProvider, coordinatorProvider, statePublisher.publish)
        })
        sharding.init(Entity(Coordinator.TypeKey) { e =>
          Coordinator(e.entityId, controllerProvider)
        })

        val dedup = OrderDedup(ctx.system.settings.config)
        OrderConsumer.run(ctx.system, coordinatorProvider, dedup)

        ElevatorStateProjection.init(ctx.system)
        OrderStatusProjection.init(ctx.system)

        Behaviors.empty
      },
      "elevator-cluster",
      config
    )
}
