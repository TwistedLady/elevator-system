package pl.feelcodes.elevator.bi.config

/** Job config from env vars (12-factor); defaults match in-cluster service names /
  * mount paths so it runs unconfigured on kind. Cadence: loop every intervalSeconds,
  * or runOnce = one pass then exit when an external scheduler owns cadence.
  *
  * The whole module writes exactly one Parquet file — the fact table at `factPath`.
  * All stats are computed downstream by the api as DuckDB views over it.
  */
final case class BiConfig(
    kafkaBootstrap: String,
    stateTopic: String,
    callTopic: String,
    sourceJdbcUrl: String,
    jdbcUser: String,
    jdbcPassword: String,
    orderStatusTable: String,
    callStatusTable: String,
    factPath: String,
    intervalSeconds: Int,
    runOnce: Boolean
)

object BiConfig {
  private def env(key: String, default: String): String = sys.env.getOrElse(key, default)

  def fromEnv(): BiConfig = BiConfig(
    kafkaBootstrap   = env("ELEVATOR_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"),
    stateTopic       = env("ELEVATOR_KAFKA_STATE_TOPIC", "elevator-state"),
    callTopic        = env("ELEVATOR_KAFKA_CALL_TOPIC", "elevator-calls"),
    sourceJdbcUrl    = env("ELEVATOR_BI_SOURCE_JDBC_URL", "jdbc:postgresql://postgres:5432/elevator"),
    jdbcUser         = env("ELEVATOR_PG_USER", "elevator"),
    jdbcPassword     = env("ELEVATOR_PG_PASSWORD", "elevator"),
    orderStatusTable = env("ELEVATOR_BI_ORDER_STATUS_TABLE", "order_status"),
    callStatusTable  = env("ELEVATOR_BI_CALL_STATUS_TABLE", "call_status"),
    factPath         = env("ELEVATOR_BI_FACT_PARQUET_PATH", "file:///data/elevator-facts.parquet"),
    intervalSeconds  = env("ELEVATOR_BI_INTERVAL", "30").toInt,
    runOnce          = env("ELEVATOR_BI_RUN_ONCE", "false").toBoolean
  )
}
