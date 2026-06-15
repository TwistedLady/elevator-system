package pl.feelcodes.elevator.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrderElevatorRequestDto {
    private String tag;
    private String elevatorName;
    private Integer floor;
    private String status;
}
