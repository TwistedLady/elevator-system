package pl.feelcodes.elevator.api.order;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;

@Table("order_status")
class OrderStatusEntity {

    @Id
    private String tag;

    @Column("elevator_name")
    private String elevatorName;

    private Integer floor;
    private String status;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("done_at")
    private OffsetDateTime doneAt;

    public String getTag() {
        return tag;
    }

    public String getElevatorName() {
        return elevatorName;
    }

    public Integer getFloor() {
        return floor;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getDoneAt() {
        return doneAt;
    }
}
