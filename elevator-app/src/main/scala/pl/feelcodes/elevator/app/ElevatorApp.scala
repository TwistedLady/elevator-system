package pl.feelcodes.elevator.app

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import pl.feelcodes.elevator.app.actors.*

/** Single-node Pekko app. No database: state lives in the in-memory journal
  * (see application.conf). Orders come from Kafka, moves are published back to Kafka. */
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
          Operator(
            publish = statePublisher.publish,
            report = (name, state, owc) => controllerProvider(name) ! Controller.MoveExecuted(state, owc)
          )
        })
        sharding.init(Entity(Controller.TypeKey) { e =>
          Controller(e.entityId, operatorProvider)
        })
        sharding.init(Entity(Coordinator.TypeKey) { e =>
          Coordinator(e.entityId, controllerProvider)
        })

        Kafka.runKafkaToCoordinator(ctx.system, coordinatorProvider)
        Behaviors.empty
      },
      "elevator-cluster"
    )
}
