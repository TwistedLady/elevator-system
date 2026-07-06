package pl.feelcodes.elevator.bi.config

/** All job configuration, resolved from environment variables (12-factor). Defaults match the
  * in-cluster service names so the job runs with no config on the kind cluster.
  */
final case class BiConfig(
    kafkaBootstrap: String,
    stateTopic: String,
    startingOffsets: String,
    checkpointLocation: String,
    triggerInterval: String,
    jdbcUrl: String,
    jdbcUser: String,
    jdbcPassword: String,
    mileageTable: String
)

object BiConfig {
  private def env(key: String, default: String): String = sys.env.getOrElse(key, default)

  def fromEnv(): BiConfig = BiConfig(
    kafkaBootstrap     = env("ELEVATOR_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"),
    stateTopic         = env("ELEVATOR_KAFKA_STATE_TOPIC", "elevator-state"),
    startingOffsets    = env("ELEVATOR_BI_STARTING_OFFSETS", "earliest"),
    checkpointLocation = env("ELEVATOR_BI_CHECKPOINT", "file:///checkpoint"),
    triggerInterval    = env("ELEVATOR_BI_TRIGGER", "10 seconds"),
    jdbcUrl            = env("ELEVATOR_BI_JDBC_URL", "jdbc:postgresql://postgres:5432/elevator"),
    jdbcUser           = env("ELEVATOR_PG_USER", "elevator"),
    jdbcPassword       = env("ELEVATOR_PG_PASSWORD", "elevator"),
    mileageTable       = env("ELEVATOR_BI_TABLE", "elevator_mileage")
  )
}
