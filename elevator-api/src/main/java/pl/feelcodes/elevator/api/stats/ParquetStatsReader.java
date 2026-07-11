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
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the single Spark BI fact table with an embedded DuckDB engine — no analytics
 * database. Each read opens a fresh in-memory DuckDB connection, registers the {@link
 * BiViews} over the Parquet glob, and selects from one view. DuckDB's JDBC driver is
 * blocking, so every read runs on the bounded-elastic scheduler, off the WebFlux loop.
 *
 * <p>The file is produced by the elevator-bi job and mounted read-only on the same node.
 * It may be absent (BI off, or no cycle has run yet) — that yields an empty result, not
 * an error.
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

    private final Path factDir;

    ParquetStatsReader(@Value("${elevator.stats.fact-path:/data/elevator-facts.parquet}") String factPath) {
        this.factDir = Path.of(factPath);
    }

    /** Per-elevator mileage + orders-served, unordered — callers sort by their metric. */
    public Flux<ElevatorStat> all() {
        return read("SELECT elevatorName, floorsTravelled, ordersServed FROM v_elevator_stats",
                (rs, updatedAt) -> new ElevatorStat(
                        rs.getString("elevatorName"),
                        rs.getLong("floorsTravelled"),
                        rs.getLong("ordersServed"),
                        updatedAt));
    }

    /** One row per completed call with its processing time in seconds. */
    public Flux<CallLatency> calls() {
        return read("SELECT callId, elevatorName, floor, orderId, passengerId, createdAt, doneAt, processingSeconds "
                        + "FROM v_call",
                (rs, updatedAt) -> new CallLatency(
                        rs.getString("callId"),
                        rs.getString("elevatorName"),
                        rs.getInt("floor"),
                        rs.getString("orderId"),
                        rs.getString("passengerId"),
                        offset(rs.getTimestamp("createdAt")),
                        offset(rs.getTimestamp("doneAt")),
                        rs.getDouble("processingSeconds"),
                        updatedAt));
    }

    /** Processing-time summary per elevator plus a fleet-wide {@code ALL} row. */
    public Flux<LatencySummary> latencySummary() {
        return read("SELECT elevatorName, calls, avgSeconds, minSeconds, maxSeconds, p50Seconds, p95Seconds "
                        + "FROM v_latency_summary",
                (rs, updatedAt) -> new LatencySummary(
                        rs.getString("elevatorName"),
                        rs.getLong("calls"),
                        box(rs.getDouble("avgSeconds"), rs),
                        box(rs.getDouble("minSeconds"), rs),
                        box(rs.getDouble("maxSeconds"), rs),
                        box(rs.getDouble("p50Seconds"), rs),
                        box(rs.getDouble("p95Seconds"), rs),
                        updatedAt));
    }

    /** Passenger double-bookings; empty is the healthy case. */
    public Flux<Conflict> conflicts() {
        return read("SELECT passengerId, elevatorA, orderA, elevatorB, orderB, overlapStart, overlapEnd "
                        + "FROM v_conflicts",
                (rs, updatedAt) -> new Conflict(
                        rs.getString("passengerId"),
                        rs.getString("elevatorA"),
                        rs.getString("orderA"),
                        rs.getString("elevatorB"),
                        rs.getString("orderB"),
                        offset(rs.getTimestamp("overlapStart")),
                        offset(rs.getTimestamp("overlapEnd")),
                        updatedAt));
    }

    private interface RowMapper<T> {
        T map(ResultSet rs, OffsetDateTime updatedAt) throws Exception;
    }

    private <T> Flux<T> read(String sql, RowMapper<T> mapper) {
        return Mono.fromCallable(() -> query(sql, mapper))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    private <T> List<T> query(String sql, RowMapper<T> mapper) throws Exception {
        if (!Files.isDirectory(factDir)) {
            log.debug("fact table not present yet at {}", factDir);
            return List.of();
        }
        OffsetDateTime updatedAt = snapshotTime();
        String glob = factDir.resolve("*.parquet").toString();
        List<T> rows = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = conn.createStatement()) {
            BiViews.install(st, glob);
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    rows.add(mapper.map(rs, updatedAt));
                }
            }
        }
        return rows;
    }

    private static OffsetDateTime offset(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    /** Box a double that is SQL NULL (aggregate over zero rows) back to a null Double. */
    private static Double box(double value, ResultSet rs) throws java.sql.SQLException {
        return rs.wasNull() ? null : value;
    }

    private OffsetDateTime snapshotTime() throws Exception {
        return Files.getLastModifiedTime(factDir).toInstant().atOffset(ZoneOffset.UTC);
    }
}
