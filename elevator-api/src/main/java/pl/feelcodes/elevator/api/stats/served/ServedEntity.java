package pl.feelcodes.elevator.api.stats.served;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;

@Table("elevator_orders_served")
class ServedEntity {

    @Id
    @Column("elevator_name")
    private String elevatorName;

    @Column("orders_served")
    private Long ordersServed;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    public String getElevatorName() {
        return elevatorName;
    }

    public Long getOrdersServed() {
        return ordersServed;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
