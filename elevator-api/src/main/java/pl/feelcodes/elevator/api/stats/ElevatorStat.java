package pl.feelcodes.elevator.api.stats;

import java.time.OffsetDateTime;

/**
 * One row of the BI read-model, as read from the Spark-produced Parquet file. {@code updatedAt} is
 * the snapshot time (the Parquet file's last-modified), shared by every row since the whole file is
 * rewritten each cycle.
 */
public record ElevatorStat(
        String elevatorName,
        long floorsTravelled,
        long ordersServed,
        OffsetDateTime updatedAt) {
}
