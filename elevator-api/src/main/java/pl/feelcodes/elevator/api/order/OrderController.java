package pl.feelcodes.elevator.api.order;

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

/**
 * The {@code /api/order} resource — one controller for the whole order lifecycle:
 *
 * <pre>
 *   POST /api/order        place an order (publishes a command to Kafka); echoes the body with the tag filled in
 *   GET  /api/order/{tag}  the order's status from the {@code order_status} read-model:
 *                          {"tag":..,"elevatorId":..,"floor":N,"status":"PROGRESS|DONE",..}; 404 if the tag is unknown
 * </pre>
 *
 * Placement (write → Kafka) and status (read → R2DBC) are the two sides of the same resource,
 * so they share this controller but keep separate services: {@link OrderService} writes,
 * {@link OrderStatusService} reads.
 */
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

    /** Place an order — fills a tag if absent, publishes the command, echoes the body. */
    @PostMapping
    public Mono<OrderRequestDto> place(@RequestBody OrderRequestDto dto) {
        OrderRequestDto order = dto.withTagIfAbsent();
        log.info("[order place ] {} -> floor {} (tag {})",
                order.elevatorName(), order.floor(), order.tag());
        // publishing the command is non-blocking; wrap so the endpoint composes reactively
        return Mono.fromCallable(() -> {
            orderService.order(order.tag(), order.elevatorName(), order.floor());
            return order;
        });
    }

    /** The order's current status, or 404 if the tag is unknown. */
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
