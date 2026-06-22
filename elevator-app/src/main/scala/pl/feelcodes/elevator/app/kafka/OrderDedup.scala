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
 * proper replacement for the Coordinator's old in-memory seen-tags set. Used at ingestion: the
 * Kafka stream claims a tag before forwarding the order; a re-sent tag conflicts and is dropped.
 */
final class OrderDedup(connectionFactory: ConnectionFactory) {

  /** Claim the tag. `true` = first time we've seen it (row inserted); `false` = already
    * processed (primary-key conflict), so the caller should drop the order. */
  def firstSeen(tag: String): Future[Boolean] = {
    val rowsInserted: Mono[java.lang.Long] = Mono.usingWhen(
      Mono.from(connectionFactory.create()),
      conn =>
        Mono
          .from(
            conn
              .createStatement("INSERT INTO processed_orders (tag) VALUES ($1) ON CONFLICT DO NOTHING")
              .bind(0, tag)
              .execute())
          .flatMap(result => Mono.from(result.getRowsUpdated)),
      conn => Mono.from(conn.close()))

    rowsInserted.toFuture.asScala.map(_.longValue() == 1L)(ExecutionContext.parasitic)
  }
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
