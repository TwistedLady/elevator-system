package pl.feelcodes.elevator.api.orderstatus;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Was an order processed? Looks the tag up in the {@code order_status} read-model.
 *
 *   GET /api/order/{tag}  ->  {"tag":..,"elevatorName":..,"floor":N,"status":"ACCEPTED|DONE","processed":bool}
 *                             404 if the tag is unknown
 */
@Slf4j
@RestController
@RequestMapping("/api/order")
class OrderStatusController {

    private final OrderStatusService orderStatusService;

    OrderStatusController(OrderStatusService orderStatusService) {
        this.orderStatusService = orderStatusService;
    }

    @GetMapping(value = "/{tag}", produces = MediaType.APPLICATION_JSON_VALUE)
    Mono<ResponseEntity<OrderStatusDto>> status(@PathVariable("tag") String tag) {
        log.info("confirm order request: tag={}", tag);
        return orderStatusService.byTag(tag)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
