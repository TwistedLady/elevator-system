package pl.feelcodes.elevator.api.stats;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Exercises the real DuckDB {@link BiViews} SQL end-to-end: writes a fact-table Parquet
 * fixture with DuckDB, points {@link ParquetStatsReader} at it, and asserts every view
 * (elevator stats, call latency, conflicts) computes correctly.
 */
class ParquetStatsReaderTest {

    @BeforeAll
    static void loadDriver() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
    }

    /** Writes the fixture into {@code dir/facts.parquet} so the reader's *.parquet glob finds it. */
    private static void writeFixture(Path dir) throws Exception {
        String file = dir.resolve("facts.parquet").toString();
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE f ("
                    + "grain VARCHAR, elevator_name VARCHAR, floor INTEGER, floors_travelled BIGINT, "
                    + "order_id VARCHAR, call_id VARCHAR, passenger_id VARCHAR, status VARCHAR, is_done BOOLEAN, "
                    + "created_at TIMESTAMP, done_at TIMESTAMP, processing_seconds DOUBLE, "
                    + "served_from TIMESTAMP, served_to TIMESTAMP)");
            st.execute("INSERT INTO f VALUES "
                    // ELEVATOR (mileage)
                    + "('ELEVATOR','e1',NULL,12,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),"
                    + "('ELEVATOR','e2',NULL,5,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL),"
                    // ORDER (served): e1 has 2 DONE, e2 has 1 DONE
                    + "('ORDER','e1',3,NULL,'o1',NULL,NULL,'DONE',true,"
                    + "  TIMESTAMP '2026-07-10 10:00:00',TIMESTAMP '2026-07-10 10:00:04',4.0,"
                    + "  TIMESTAMP '2026-07-10 10:00:00',TIMESTAMP '2026-07-10 10:00:04'),"
                    + "('ORDER','e1',7,NULL,'o2',NULL,NULL,'DONE',true,"
                    + "  TIMESTAMP '2026-07-10 10:00:00',TIMESTAMP '2026-07-10 10:00:10',10.0,"
                    + "  TIMESTAMP '2026-07-10 10:00:00',TIMESTAMP '2026-07-10 10:00:10'),"
                    + "('ORDER','e2',2,NULL,'o3',NULL,NULL,'DONE',true,"
                    + "  TIMESTAMP '2026-07-10 10:00:02',TIMESTAMP '2026-07-10 10:00:06',4.0,"
                    + "  TIMESTAMP '2026-07-10 10:00:02',TIMESTAMP '2026-07-10 10:00:06'),"
                    // CALL (latency + conflict windows): alice rides both e1(o1) and e2(o3), overlapping
                    + "('CALL','e1',3,NULL,'o1','c1','alice','DONE',true,"
                    + "  TIMESTAMP '2026-07-10 10:00:00',TIMESTAMP '2026-07-10 10:00:02.5',2.5,"
                    + "  TIMESTAMP '2026-07-10 10:00:00',TIMESTAMP '2026-07-10 10:00:04'),"
                    + "('CALL','e1',7,NULL,'o2','c2','bob','DONE',true,"
                    + "  TIMESTAMP '2026-07-10 10:00:00',TIMESTAMP '2026-07-10 10:00:04',4.0,"
                    + "  TIMESTAMP '2026-07-10 10:00:00',TIMESTAMP '2026-07-10 10:00:10'),"
                    + "('CALL','e2',2,NULL,'o3','c3','alice','DONE',true,"
                    + "  TIMESTAMP '2026-07-10 10:00:01',TIMESTAMP '2026-07-10 10:00:05',4.0,"
                    + "  TIMESTAMP '2026-07-10 10:00:02',TIMESTAMP '2026-07-10 10:00:06')");
            st.execute("COPY f TO '" + file + "' (FORMAT PARQUET)");
        }
    }

    @Test
    void elevatorStatsJoinMileageAndServed(@TempDir Path dir) throws Exception {
        writeFixture(dir);
        ParquetStatsReader reader = new ParquetStatsReader(dir.toString());

        Map<String, ElevatorStat> byName = reader.all().collectList().block().stream()
                .collect(Collectors.toMap(ElevatorStat::elevatorName, s -> s));

        assertThat(byName.get("e1").floorsTravelled()).isEqualTo(12L);
        assertThat(byName.get("e1").ordersServed()).isEqualTo(2L);
        assertThat(byName.get("e2").floorsTravelled()).isEqualTo(5L);
        assertThat(byName.get("e2").ordersServed()).isEqualTo(1L);
    }

    @Test
    void perCallLatencyRows(@TempDir Path dir) throws Exception {
        writeFixture(dir);
        ParquetStatsReader reader = new ParquetStatsReader(dir.toString());

        List<CallLatency> calls = reader.calls().collectList().block();

        assertThat(calls).extracting(CallLatency::callId).containsExactlyInAnyOrder("c1", "c2", "c3");
        CallLatency c1 = calls.stream().filter(c -> c.callId().equals("c1")).findFirst().orElseThrow();
        assertThat(c1.processingSeconds()).isCloseTo(2.5, within(1e-6));
        assertThat(c1.passengerId()).isEqualTo("alice");
    }

    @Test
    void latencySummaryPerElevatorAndFleetWide(@TempDir Path dir) throws Exception {
        writeFixture(dir);
        ParquetStatsReader reader = new ParquetStatsReader(dir.toString());

        Map<String, LatencySummary> byName = reader.latencySummary().collectList().block().stream()
                .collect(Collectors.toMap(LatencySummary::elevatorName, s -> s));

        assertThat(byName.get("e1").calls()).isEqualTo(2L);
        assertThat(byName.get("e1").avgSeconds()).isCloseTo(3.25, within(1e-6));
        assertThat(byName.get("e1").minSeconds()).isCloseTo(2.5, within(1e-6));
        assertThat(byName.get("e1").maxSeconds()).isCloseTo(4.0, within(1e-6));

        LatencySummary all = byName.get("ALL");
        assertThat(all.calls()).isEqualTo(3L);
        assertThat(all.minSeconds()).isCloseTo(2.5, within(1e-6));
        assertThat(all.maxSeconds()).isCloseTo(4.0, within(1e-6));
        assertThat(all.avgSeconds()).isCloseTo((2.5 + 4.0 + 4.0) / 3, within(1e-6));
    }

    @Test
    void conflictsDetectsPassengerDoubleBooking(@TempDir Path dir) throws Exception {
        writeFixture(dir);
        ParquetStatsReader reader = new ParquetStatsReader(dir.toString());

        List<Conflict> conflicts = reader.conflicts().collectList().block();

        assertThat(conflicts).hasSize(1);
        Conflict c = conflicts.get(0);
        assertThat(c.passengerId()).isEqualTo("alice");
        assertThat(List.of(c.elevatorA(), c.elevatorB())).containsExactlyInAnyOrder("e1", "e2");
        assertThat(List.of(c.orderA(), c.orderB())).containsExactlyInAnyOrder("o1", "o3");
    }

    @Test
    void absentFactTableYieldsEmpty(@TempDir Path dir) {
        ParquetStatsReader reader = new ParquetStatsReader(dir.resolve("missing").toString());
        assertThat(reader.all().collectList().block()).isEmpty();
        assertThat(reader.calls().collectList().block()).isEmpty();
        assertThat(reader.latencySummary().collectList().block()).isEmpty();
        assertThat(reader.conflicts().collectList().block()).isEmpty();
    }
}
