package pl.feelcodes.elevator.bi.sink

import org.apache.spark.sql.{Dataset, SaveMode}
import pl.feelcodes.elevator.bi.StatsRow
import pl.feelcodes.elevator.bi.config.BiConfig

import java.io.File
import java.net.URI
import java.nio.file.{Files, Path, Paths}
import scala.reflect.io.Directory

/** Rewrites the whole per-elevator model to one Parquet dir each cycle (Parquet is
  * immutable, no upsert; the fleet is tiny). Writes to staging then moves it in so
  * readers (api's DuckDB) never see a partial file; the delete-then-move leaves a
  * brief window where the target is absent — the reader treats that as empty.
  */
object ParquetSink {

  def write(stats: Dataset[StatsRow], cfg: BiConfig): Unit = {
    val stagingUri = cfg.parquetPath + ".staging"
    stats.coalesce(1).write.mode(SaveMode.Overwrite).parquet(stagingUri)

    val target  = localPath(cfg.parquetPath)
    val staging = localPath(stagingUri)
    if (Files.exists(target)) new Directory(new File(target.toString)).deleteRecursively()
    Files.move(staging, target)
  }

  private def localPath(path: String): Path =
    if (path.contains("://")) Paths.get(URI.create(path)) else Paths.get(path)
}
