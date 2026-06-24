package pl.feelcodes.elevator.app

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import pl.feelcodes.elevator.app.actors.*
import pl.feelcodes.elevator.common.core.Elevator
import pl.feelcodes.elevator.app.kafka.{OrderConsumer, OrderDedup, StatePublisher}
import pl.feelcodes.elevator.app.readside.{ElevatorStateProjection, OrderStatusProjection}

object ElevatorApp extends App {

  val system: ActorSystem[Nothing] =
    ActorSystem(
      Behaviors.setup[Nothing] { ctx =>
        val statePublisher = StatePublisher(ctx.system)

        val sharding = ClusterSharding(ctx.system)
        val operatorProvider = sharding.entityRefFor(Operator.TypeKey, _)
        val controllerProvider = sharding.entityRefFor(Controller.TypeKey, _)
        val coordinatorProvider = sharding.entityRefFor(Coordinator.TypeKey, _)

        sharding.init(Entity(Operator.TypeKey) { _ =>
          val publishMove: Operator.PublishMove =
            (name, state) => controllerProvider(name) ! Controller.PublishState(state)
          val buildElevator: Operator.BuildElevator =
            ctx.system.settings.config.getString("elevator.operator-class") match
              case "FastOperator" => (name, state) => Elevator.fast(name)(state)
              case "SlowOperator" => (name, state) => Elevator.slow(name)(state)
              case other =>
                throw new IllegalArgumentException(
                  s"Unknown elevator.operator-class '$other'. Known: FastOperator, SlowOperator")
          Operator(publishMove, buildElevator)
        })
        sharding.init(Entity(Controller.TypeKey) { e =>
          Controller(e.entityId, operatorProvider, statePublisher.publish)
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
      "elevator-cluster"
    )
}
