package pl.feelcodes.elevator.api.orderstatus;

/**
 * Lifecycle of one order, read from the {@code order_status} read-model.
 * {@code status} is ACCEPTED or DONE; {@code processed} is the convenience boolean (status == DONE).
 */
public record OrderStatusDto(String tag, String elevatorName, Integer floor, String status, boolean processed) {
}
