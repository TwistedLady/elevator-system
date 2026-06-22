package pl.feelcodes.elevator.api.order;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.r2dbc.core.DatabaseClient;
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
@RestController
@RequestMapping("/api/order")
class OrderStatusController {

    private final DatabaseClient db;

    OrderStatusController(DatabaseClient db) {
        this.db = db;
    }

    @GetMapping(value = "/{tag}", produces = MediaType.APPLICATION_JSON_VALUE)
    Mono<ResponseEntity<OrderStatusDto>> status(@PathVariable("tag") String tag) {
        return db.sql("SELECT tag, elevator_name, floor, status FROM order_status WHERE tag = :tag")
                .bind("tag", tag)
                .map(row -> {
                    String st = row.get("status", String.class);
                    return new OrderStatusDto(
                            row.get("tag", String.class),
                            row.get("elevator_name", String.class),
                            row.get("floor", Integer.class),
                            st,
                            "DONE".equals(st));
                })
                .one()
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
