package pl.feelcodes.elevator.bi.sink

import org.apache.spark.sql.DataFrame
import pl.feelcodes.elevator.bi.config.BiConfig

import java.sql.{Connection, DriverManager, Timestamp}

/** Idempotent JDBC upsert of per-elevator "orders served" counts into Postgres.
  *
  * The result set is tiny (one row per elevator), so it's collected to the driver and upserted over
  * a single connection. `ON CONFLICT` makes each periodic refresh overwrite the absolute count.
  */
object PostgresOrdersServedSink {

  def upsert(served: DataFrame, cfg: BiConfig): Unit = {
    val rows = served.collect()

    var conn: Connection = null
    try {
      conn = DriverManager.getConnection(cfg.statsJdbcUrl, cfg.jdbcUser, cfg.jdbcPassword)
      conn.setAutoCommit(false)
      ensureTable(conn, cfg.servedTable)

      if (rows.nonEmpty) {
        val sql =
          s"""INSERT INTO ${cfg.servedTable} (elevator_name, orders_served, updated_at)
             |VALUES (?, ?, ?)
             |ON CONFLICT (elevator_name)
             |DO UPDATE SET orders_served = EXCLUDED.orders_served,
             |              updated_at    = EXCLUDED.updated_at""".stripMargin

        val ps = conn.prepareStatement(sql)
        val now = new Timestamp(System.currentTimeMillis())
        try {
          rows.foreach { r =>
            ps.setString(1, r.getAs[String]("elevator_name"))
            ps.setLong(2, r.getAs[Long]("orders_served"))
            ps.setTimestamp(3, now)
            ps.addBatch()
          }
          ps.executeBatch()
        } finally ps.close()
      }
      conn.commit()
    } catch {
      case e: Throwable =>
        if (conn != null) conn.rollback()
        throw e
    } finally {
      if (conn != null) conn.close()
    }
  }

  private def ensureTable(conn: Connection, table: String): Unit = {
    val ddl =
      s"""CREATE TABLE IF NOT EXISTS $table (
         |  elevator_name VARCHAR(255) PRIMARY KEY,
         |  orders_served BIGINT       NOT NULL,
         |  updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
         |)""".stripMargin
    val st = conn.createStatement()
    try st.execute(ddl)
    finally st.close()
  }
}
