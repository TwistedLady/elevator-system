package pl.feelcodes.elevator.api.order;

import java.util.UUID;

/**
 * POST /api/order request body. {@code tag} is optional on input — {@link #withTagIfAbsent()}
 * fills a UUID when the client omits it. {@link ValidOrder} checks floor + elevator against the
 * configured fleet/height (see {@link OrderValidator}).
 */
@ValidOrder
record OrderRequestDto(String tag, String elevatorName, Integer floor) {

    /** This order, or a copy with a freshly generated tag when none was supplied. */
    OrderRequestDto withTagIfAbsent() {
        return tag != null ? this : new OrderRequestDto(UUID.randomUUID().toString(), elevatorName, floor);
    }
}
