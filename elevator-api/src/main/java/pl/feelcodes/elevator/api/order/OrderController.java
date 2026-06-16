package pl.feelcodes.elevator.api.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/order")
class OrderController {
    private final OrderService orderService;

    OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public OrderRequestDto order(@RequestBody OrderRequestDto dto) throws JsonProcessingException {
        if (Objects.isNull(dto.getTag()))
            dto.setTag(UUID.randomUUID().toString());
        orderService.order(dto.getTag(), dto.getElevatorName(), dto.getFloor());
        log.info(dto.toString());
        return dto;
    }
}
