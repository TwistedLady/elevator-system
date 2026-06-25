package pl.feelcodes.elevator.app.inbound

import com.typesafe.config.Config
import io.r2dbc.pool.{ConnectionPool, ConnectionPoolConfiguration}
import io.r2dbc.spi.ConnectionFactoryOptions.*
import io.r2dbc.spi.{ConnectionFactories, ConnectionFactory, ConnectionFactoryOptions}
import reactor.core.publisher.Mono

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters.*

final class OrderDedup(connectionFactory: ConnectionFactory) {

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
