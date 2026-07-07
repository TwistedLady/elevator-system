package pl.feelcodes.elevator.bi.config

/** All job configuration, resolved from environment variables (12-factor). Defaults match the
  * in-cluster service names / mount paths so the job runs with no config on the kind cluster.
  */
final case class BiConfig(
    kafkaBootstrap: String,
    stateTopic: String,
    // Source read: the operational `elevator` DB (order_status = the "orders served" signal).
    sourceJdbcUrl: String,
    jdbcUser: String,
    jdbcPassword: String,
    orderStatusTable: String,
    // Analytics sink: the single Parquet directory the api reads via DuckDB (shared volume).
    parquetPath: String,
    intervalSeconds: Int
)

object BiConfig {
  private def env(key: String, default: String): String = sys.env.getOrElse(key, default)

  def fromEnv(): BiConfig = BiConfig(
    kafkaBootstrap   = env("ELEVATOR_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"),
    stateTopic       = env("ELEVATOR_KAFKA_STATE_TOPIC", "elevator-state"),
    sourceJdbcUrl    = env("ELEVATOR_BI_SOURCE_JDBC_URL", "jdbc:postgresql://postgres:5432/elevator"),
    jdbcUser         = env("ELEVATOR_PG_USER", "elevator"),
    jdbcPassword     = env("ELEVATOR_PG_PASSWORD", "elevator"),
    orderStatusTable = env("ELEVATOR_BI_ORDER_STATUS_TABLE", "order_status"),
    parquetPath      = env("ELEVATOR_BI_PARQUET_PATH", "file:///data/elevators.parquet"),
    intervalSeconds  = env("ELEVATOR_BI_INTERVAL", "30").toInt
  )
}
