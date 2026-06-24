package pl.feelcodes.elevator.api.order;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/order")
class OrderController {

    private final OrderService orderService;
    private final OrderStatusService orderStatusService;

    OrderController(OrderService orderService, OrderStatusService orderStatusService) {
        this.orderService = orderService;
        this.orderStatusService = orderStatusService;
    }

    @PostMapping
    public Mono<OrderRequestDto> place(@Valid @RequestBody OrderRequestDto dto) {
        OrderRequestDto order = dto.withTagIfAbsent();
        log.info("[order place ] {} -> floor {} (tag {})",
                order.elevatorName(), order.floor(), order.tag());
        return orderService.order(order.tag(), order.elevatorName(), order.floor())
                .thenReturn(order);
    }

    @GetMapping(value = "/{tag}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<OrderStatusDto>> status(@PathVariable("tag") String tag) {
        return orderStatusService.byTag(tag)
                .doOnNext(o -> log.info("[order status] {} -> {} (created {}, done {})",
                        o.tag(), o.status(), o.createDateTime(), o.doneDateTime()))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build())
                .doOnNext(resp -> {
                    if (resp.getStatusCode().is4xxClientError()) {
                        log.info("[order status] {} -> NOT FOUND (404)", tag);
                    }
                });
    }
}
