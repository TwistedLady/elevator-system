package pl.feelcodes.elevator.api.stats;

import java.util.List;

/**
 * The DuckDB view layer over the single BI fact table. The elevator-bi job writes one
 * detailed Parquet file (grain-tagged rows: ELEVATOR / ORDER / CALL); every stat the api
 * exposes is a view computed here at query time — no stat is pre-aggregated upstream
 * except mileage (its source, the state topic, isn't in the file).
 *
 * <p>{@link #install} registers {@code facts} over the Parquet glob, then the derived
 * views, on a fresh in-memory DuckDB connection. Callers then {@code SELECT} from a view.
 */
final class BiViews {

    private BiViews() {
    }

    private static final String FAR_FUTURE = "TIMESTAMP '9999-12-31 00:00:00'";

    /** Views in dependency order; {@code {GLOB}} is replaced with the Parquet glob path. */
    private static final List<String> DDL = List.of(
            // Base: the whole fact table.
            "CREATE VIEW facts AS SELECT * FROM read_parquet('{GLOB}')",

            // Per-elevator stats: mileage (ELEVATOR grain) full-joined to served counts
            // (completed ORDER rows). Zero-fills either side that is missing.
            "CREATE VIEW v_elevator_stats AS "
                    + "SELECT COALESCE(m.elevator_name, s.elevator_name) AS elevatorName, "
                    + "       COALESCE(m.floors_travelled, 0)            AS floorsTravelled, "
                    + "       COALESCE(s.orders_served, 0)               AS ordersServed "
                    + "FROM (SELECT elevator_name, any_value(floors_travelled) AS floors_travelled "
                    + "        FROM facts WHERE grain = 'ELEVATOR' GROUP BY elevator_name) m "
                    + "FULL OUTER JOIN "
                    + "     (SELECT elevator_name, count(*) AS orders_served "
                    + "        FROM facts WHERE grain = 'ORDER' AND is_done GROUP BY elevator_name) s "
                    + "  ON m.elevator_name = s.elevator_name",

            // One row per completed call with its end-to-end processing time.
            "CREATE VIEW v_call AS "
                    + "SELECT call_id AS callId, elevator_name AS elevatorName, floor, "
                    + "       order_id AS orderId, passenger_id AS passengerId, "
                    + "       created_at AS createdAt, done_at AS doneAt, processing_seconds AS processingSeconds "
                    + "FROM facts "
                    + "WHERE grain = 'CALL' AND done_at IS NOT NULL AND processing_seconds IS NOT NULL",

            // Processing-time summary per elevator plus a fleet-wide ALL row.
            "CREATE VIEW v_latency_summary AS "
                    + "SELECT elevatorName, count(*) AS calls, avg(processingSeconds) AS avgSeconds, "
                    + "       min(processingSeconds) AS minSeconds, max(processingSeconds) AS maxSeconds, "
                    + "       quantile_cont(processingSeconds, 0.5)  AS p50Seconds, "
                    + "       quantile_cont(processingSeconds, 0.95) AS p95Seconds "
                    + "FROM v_call GROUP BY elevatorName "
                    + "UNION ALL "
                    + "SELECT 'ALL', count(*), avg(processingSeconds), min(processingSeconds), max(processingSeconds), "
                    + "       quantile_cont(processingSeconds, 0.5), quantile_cont(processingSeconds, 0.95) "
                    + "FROM v_call",

            // One served window per (passenger, order) on a lift, from CALL rows.
            "CREATE VIEW v_served_window AS "
                    + "SELECT DISTINCT passenger_id, order_id, elevator_name, "
                    + "       served_from AS window_start, served_to AS window_end "
                    + "FROM facts "
                    + "WHERE grain = 'CALL' AND passenger_id IS NOT NULL AND order_id IS NOT NULL",

            // Passenger double-bookings: same passenger, two lifts, windows overlapping in
            // time. Each unordered pair once (order_a < order_b); open windows run forever.
            "CREATE VIEW v_conflicts AS "
                    + "SELECT a.passenger_id AS passengerId, "
                    + "       a.elevator_name AS elevatorA, a.order_id AS orderA, "
                    + "       b.elevator_name AS elevatorB, b.order_id AS orderB, "
                    + "       greatest(a.window_start, b.window_start) AS overlapStart, "
                    + "       least(COALESCE(a.window_end, " + FAR_FUTURE + "), "
                    + "             COALESCE(b.window_end, " + FAR_FUTURE + ")) AS overlapEnd "
                    + "FROM v_served_window a JOIN v_served_window b "
                    + "  ON a.passenger_id = b.passenger_id "
                    + " AND a.order_id < b.order_id "
                    + " AND a.elevator_name <> b.elevator_name "
                    + " AND a.window_start < COALESCE(b.window_end, " + FAR_FUTURE + ") "
                    + " AND b.window_start < COALESCE(a.window_end, " + FAR_FUTURE + ")");

    /** Register {@code facts} + all derived views on {@code st}, bound to {@code glob}. */
    static void install(java.sql.Statement st, String glob) throws java.sql.SQLException {
        for (String ddl : DDL) {
            st.execute(ddl.replace("{GLOB}", glob));
        }
    }
}
