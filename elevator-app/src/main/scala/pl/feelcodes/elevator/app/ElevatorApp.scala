package pl.feelcodes.elevator.app

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import pl.feelcodes.elevator.app.actors.*
import pl.feelcodes.elevator.app.kafka.{OrderConsumer, OrderDedup, StatePublisher}
import pl.feelcodes.elevator.app.readside.{ElevatorStateProjection, OrderStatusProjection}

/** Single-node Pekko app. Events are persisted to Postgres via the reactive R2DBC journal
  * (see application.conf) and projected into the `elevator_state_view` read-model by
  * [[pl.feelcodes.elevator.app.readside.ElevatorStateProjection]]. Orders come from Kafka,
  * moves are published back to Kafka. */
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
          val reportMove: Operator.ReportMove =
            (name, state, owc) => controllerProvider(name) ! Controller.MoveExecuted(state, owc)
          val reportStop: Operator.ReportStop =
            (name, state) => controllerProvider(name) ! Controller.Stopped(state)
          // Pick the operator subclass from config key `elevator.operator-class` at startup.
          // Adding a new operator is one `final class` in Operator.scala plus one case here;
          // the compiler still sees every class (no reflection, so renames/find-usages work).
          Behaviors.setup { ctx =>
            ctx.system.settings.config.getString("elevator.operator-class") match
              case "FastOperator" => new FastOperator(ctx, reportMove, reportStop)
              case "SlowOperator" => new SlowOperator(ctx, reportMove, reportStop)
              case other =>
                throw new IllegalArgumentException(
                  s"Unknown elevator.operator-class '$other'. Known: FastOperator, SlowOperator")
          }
        })
        sharding.init(Entity(Controller.TypeKey) { e =>
          Controller(e.entityId, operatorProvider, coordinatorProvider, statePublisher.publish)
        })
        sharding.init(Entity(Coordinator.TypeKey) { e =>
          Coordinator(e.entityId, controllerProvider)
        })

        val dedup = OrderDedup(ctx.system.settings.config)
        OrderConsumer.run(ctx.system, coordinatorProvider, dedup)

        // Read-side: project Controller events into the elevator_state_view read-model, and
        // Coordinator events into the order_status read-model (queryable by order tag).
        ElevatorStateProjection.init(ctx.system)
        OrderStatusProjection.init(ctx.system)

        Behaviors.empty
      },
      "elevator-cluster"
    )
}
