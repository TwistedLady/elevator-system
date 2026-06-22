package pl.feelcodes.elevator.api.order;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

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
    public Mono<OrderRequestDto> order(@RequestBody OrderRequestDto dto) {
        if (dto.getTag() == null) {
            dto.setTag(UUID.randomUUID().toString());
        }
        log.info("order elevator request: elevator={} floor={} tag={}",
                dto.getElevatorName(), dto.getFloor(), dto.getTag());
        // publishing the command is non-blocking; wrap so the endpoint composes reactively
        return Mono.fromCallable(() -> {
            orderService.order(dto.getTag(), dto.getElevatorName(), dto.getFloor());
            return dto;
        });
    }
}
