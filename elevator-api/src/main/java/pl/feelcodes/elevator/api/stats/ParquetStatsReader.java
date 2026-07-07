package pl.feelcodes.elevator.api.stats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the single Spark BI Parquet file (one row per elevator) with an embedded DuckDB engine —
 * no analytics database. DuckDB's JDBC driver is blocking, so every read runs on the bounded-elastic
 * scheduler, keeping it off the WebFlux event loop.
 *
 * <p>The file is produced by the elevator-bi job and mounted read-only on the same node. It may be
 * absent (BI off, or no cycle has run yet) — that yields an empty result, not an error.
 */
@Component
@ConditionalOnProperty(prefix = "elevator.bi", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ParquetStatsReader {

    private static final Logger log = LoggerFactory.getLogger(ParquetStatsReader.class);

    static {
        try {
            Class.forName("org.duckdb.DuckDBDriver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("DuckDB JDBC driver not on the classpath", e);
        }
    }

    private final Path parquetDir;

    ParquetStatsReader(@Value("${elevator.stats.parquet-path:/data/elevators.parquet}") String parquetPath) {
        this.parquetDir = Path.of(parquetPath);
    }

    /** All elevators, unordered — callers sort by whichever metric they expose. */
    public Flux<ElevatorStat> all() {
        return Mono.fromCallable(this::query)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    private List<ElevatorStat> query() throws Exception {
        if (!Files.isDirectory(parquetDir)) {
            log.debug("stats parquet not present yet at {}", parquetDir);
            return List.of();
        }
        OffsetDateTime updatedAt = snapshotTime();
        String glob = parquetDir.resolve("*.parquet").toString();
        List<ElevatorStat> rows = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT elevatorName, floorsTravelled, ordersServed FROM read_parquet('" + glob + "')")) {
            while (rs.next()) {
                rows.add(new ElevatorStat(
                        rs.getString("elevatorName"),
                        rs.getLong("floorsTravelled"),
                        rs.getLong("ordersServed"),
                        updatedAt));
            }
        }
        return rows;
    }

    private OffsetDateTime snapshotTime() throws Exception {
        return Files.getLastModifiedTime(parquetDir).toInstant().atOffset(ZoneOffset.UTC);
    }
}
