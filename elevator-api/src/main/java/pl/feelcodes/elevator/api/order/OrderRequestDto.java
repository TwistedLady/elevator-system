package pl.feelcodes.elevator.api.order;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
class OrderRequestDto {
    private String tag;          // optional on input; the API fills a UUID when absent
    private String elevatorName;
    private Integer floor;
}
