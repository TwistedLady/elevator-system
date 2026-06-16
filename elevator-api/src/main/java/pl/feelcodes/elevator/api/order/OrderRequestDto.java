package pl.feelcodes.elevator.api.order;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
class OrderRequestDto {
    private String tag;
    private String elevatorName;
    private Integer floor;
    private String status;
}
