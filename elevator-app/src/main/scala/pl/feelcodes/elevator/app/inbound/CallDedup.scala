package pl.feelcodes.elevator.app.inbound

import com.typesafe.config.Config
import io.r2dbc.pool.{ConnectionPool, ConnectionPoolConfiguration}
import io.r2dbc.spi.ConnectionFactoryOptions.*
import io.r2dbc.spi.{ConnectionFactories, ConnectionFactory, ConnectionFactoryOptions}
import reactor.core.publisher.Mono

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters.*

/** Kafka-side dedup: skips calls whose id has already been seen (table `processed_calls`). */
final class CallDedup(connectionFactory: ConnectionFactory) {

  def alreadyProcessed(callId: String): Future[Boolean] =
    withConnection { conn =>
      Mono
        .from(
          conn
            .createStatement("SELECT EXISTS(SELECT 1 FROM processed_calls WHERE call_id = $1)")
            .bind(0, callId)
            .execute())
        .flatMap(result => Mono.from(result.map((row, _) => row.get(0, classOf[java.lang.Boolean]))))
    }.toFuture.asScala.map(_.booleanValue())(ExecutionContext.parasitic)

  def markProcessed(callId: String): Future[Unit] =
    withConnection { conn =>
      Mono
        .from(
          conn
            .createStatement("INSERT INTO processed_calls (call_id) VALUES ($1) ON CONFLICT DO NOTHING")
            .bind(0, callId)
            .execute())
        .flatMap(result => Mono.from(result.getRowsUpdated))
    }.toFuture.asScala.map(_ => ())(ExecutionContext.parasitic)

  private def withConnection[A](use: io.r2dbc.spi.Connection => Mono[A]): Mono[A] =
    Mono.usingWhen(
      Mono.from(connectionFactory.create()),
      use(_),
      conn => Mono.from(conn.close()))
}

object CallDedup {

  def apply(config: Config): CallDedup = {
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
    new CallDedup(pool)
  }
}
