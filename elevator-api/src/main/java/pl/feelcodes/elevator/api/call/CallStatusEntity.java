package pl.feelcodes.elevator.api.call;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;

@Table("call_status")
class CallStatusEntity {

    @Id
    @Column("call_id")
    private String callId;

    @Column("elevator_name")
    private String elevatorName;

    private Integer floor;

    @Column("order_id")
    private String orderId;

    private String status;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("done_at")
    private OffsetDateTime doneAt;

    public String getCallId() {
        return callId;
    }

    public String getElevatorName() {
        return elevatorName;
    }

    public Integer getFloor() {
        return floor;
    }

    public String getOrderId() {
        return orderId;
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
