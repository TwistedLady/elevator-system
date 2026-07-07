package pl.feelcodes.elevator.bi.sink

import org.apache.spark.sql.{Dataset, SaveMode}
import pl.feelcodes.elevator.bi.StatsRow
import pl.feelcodes.elevator.bi.config.BiConfig

import java.io.File
import java.net.URI
import java.nio.file.{Files, Path, Paths}
import scala.reflect.io.Directory

/** Writes the whole per-elevator model to a single Parquet directory, replacing it each cycle.
  *
  * Parquet files are immutable — there is no row-level upsert — so every refresh rewrites the full
  * snapshot (the fleet is tiny). To keep readers (the api's DuckDB) from ever seeing a half-written
  * file, we write to a staging dir first, then swap it onto the target with a single move. We
  * `coalesce(1)` so the output is one part file, cheap to scan. The target is always a local mount
  * (the shared hostPath volume), so the swap is a plain filesystem move.
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

  /** Resolve a Spark path (`file:///data/x` or a bare `/data/x`) to a local filesystem Path. */
  private def localPath(path: String): Path =
    if (path.contains("://")) Paths.get(URI.create(path)) else Paths.get(path)
}
