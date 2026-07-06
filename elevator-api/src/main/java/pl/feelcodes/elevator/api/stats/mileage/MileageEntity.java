package pl.feelcodes.elevator.api.stats.mileage;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;

@Table("elevator_mileage")
class MileageEntity {

    @Id
    @Column("elevator_name")
    private String elevatorName;

    @Column("floors_travelled")
    private Long floorsTravelled;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    public String getElevatorName() {
        return elevatorName;
    }

    public Long getFloorsTravelled() {
        return floorsTravelled;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
