package pl.feelcodes.elevator.app.kafka

import com.typesafe.config.Config
import io.r2dbc.pool.{ConnectionPool, ConnectionPoolConfiguration}
import io.r2dbc.spi.ConnectionFactoryOptions.*
import io.r2dbc.spi.{ConnectionFactories, ConnectionFactory, ConnectionFactoryOptions}
import reactor.core.publisher.Mono

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters.*

/**
 * Durable, unbounded order dedup backed by the `processed_orders` table's primary key — the
 * proper replacement for the Coordinator's old in-memory seen-tags set. Used at ingestion.
 *
 * The two steps are deliberately split: the caller [[OrderConsumer]] CHECKS ([[alreadyProcessed]])
 * up front to drop re-sent tags, but only CLAIMS ([[markProcessed]]) AFTER the Coordinator has
 * durably accepted the order. Claiming first would lose orders: if the node crashed between the
 * claim and the accept, the Kafka offset would not be committed, the message would be redelivered,
 * and the now-claimed tag would be dropped — an order accepted by nobody. Claiming last means a
 * crash in that window simply leaves the tag unclaimed, so redelivery reprocesses it.
 */
final class OrderDedup(connectionFactory: ConnectionFactory) {

  /** Has this tag already been fully processed (claimed)? `true` => the caller should drop it. */
  def alreadyProcessed(tag: String): Future[Boolean] =
    withConnection { conn =>
      Mono
        .from(
          conn
            .createStatement("SELECT EXISTS(SELECT 1 FROM processed_orders WHERE tag = $1)")
            .bind(0, tag)
            .execute())
        .flatMap(result => Mono.from(result.map((row, _) => row.get(0, classOf[java.lang.Boolean]))))
    }.toFuture.asScala.map(_.booleanValue())(ExecutionContext.parasitic)

  /** Durably claim the tag so it is never processed again. Idempotent (`ON CONFLICT DO NOTHING`),
    * so a redelivered-then-reaccepted order can re-claim harmlessly. */
  def markProcessed(tag: String): Future[Unit] =
    withConnection { conn =>
      Mono
        .from(
          conn
            .createStatement("INSERT INTO processed_orders (tag) VALUES ($1) ON CONFLICT DO NOTHING")
            .bind(0, tag)
            .execute())
        .flatMap(result => Mono.from(result.getRowsUpdated))
    }.toFuture.asScala.map(_ => ())(ExecutionContext.parasitic)

  private def withConnection[A](use: io.r2dbc.spi.Connection => Mono[A]): Mono[A] =
    Mono.usingWhen(
      Mono.from(connectionFactory.create()),
      use(_),
      conn => Mono.from(conn.close()))
}

object OrderDedup {

  /** Build the dedup over its own small connection pool, using the same Postgres coordinates as
    * the Pekko R2DBC journal (`pekko.persistence.r2dbc.connection-factory`). */
  def apply(config: Config): OrderDedup = {
    val cf = config.getConfig("pekko.persistence.r2dbc.connection-factory")
    val options = ConnectionFactoryOptions
      .builder()
      .option(DRIVER, "postgresql")
      .option(HOST, cf.getString("host"))
      .option(PORT, Integer.valueOf(cf.getInt("port")))
      .option(DATABASE, cf.getString("database"))
      .option(USER, cf.getString("user"))
      .option(PASSWORD, cf.getString("password"))
      .build()
    val pool = new ConnectionPool(
      ConnectionPoolConfiguration.builder(ConnectionFactories.get(options)).maxSize(8).build())
    new OrderDedup(pool)
  }
}
