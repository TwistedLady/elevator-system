package pl.feelcodes.elevator.bi.sink

import org.apache.spark.sql.Dataset
import pl.feelcodes.elevator.bi.MileageRow
import pl.feelcodes.elevator.bi.config.BiConfig

import java.sql.{Connection, DriverManager, Timestamp}

/** Idempotent JDBC upsert of running mileage into Postgres.
  *
  * The fleet is tiny (~10 elevators), so each micro-batch is collected to the driver and upserted
  * over a single connection — no need for a partitioned `foreachPartition` writer here. `ON CONFLICT`
  * makes re-processing a batch harmless (mileage rows carry absolute totals, not deltas).
  */
object PostgresMileageSink {

  def upsert(batch: Dataset[MileageRow], cfg: BiConfig): Unit = {
    val rows = batch.collect()
    if (rows.isEmpty) return

    var conn: Connection = null
    try {
      conn = DriverManager.getConnection(cfg.statsJdbcUrl, cfg.jdbcUser, cfg.jdbcPassword)
      conn.setAutoCommit(false)
      ensureTable(conn, cfg.mileageTable)

      val sql =
        s"""INSERT INTO ${cfg.mileageTable} (elevator_name, floors_travelled, updated_at)
           |VALUES (?, ?, ?)
           |ON CONFLICT (elevator_name)
           |DO UPDATE SET floors_travelled = EXCLUDED.floors_travelled,
           |              updated_at        = EXCLUDED.updated_at""".stripMargin

      val ps = conn.prepareStatement(sql)
      val now = new Timestamp(System.currentTimeMillis())
      try {
        rows.foreach { r =>
          ps.setString(1, r.elevatorName)
          ps.setLong(2, r.floorsTravelled)
          ps.setTimestamp(3, now)
          ps.addBatch()
        }
        ps.executeBatch()
        conn.commit()
      } finally ps.close()
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
         |  elevator_name    VARCHAR(255) PRIMARY KEY,
         |  floors_travelled BIGINT       NOT NULL,
         |  updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
         |)""".stripMargin
    val st = conn.createStatement()
    try st.execute(ddl)
    finally st.close()
  }
}
