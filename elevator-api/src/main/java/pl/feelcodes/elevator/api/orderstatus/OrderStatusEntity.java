package pl.feelcodes.elevator.api.orderstatus;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Maps a row of the {@code order_status} read-model. The tag is the primary key. */
@Table("order_status")
class OrderStatusEntity {

    @Id
    private String tag;

    @Column("elevator_name")
    private String elevatorName;

    private Integer floor;
    private String status;

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
}
