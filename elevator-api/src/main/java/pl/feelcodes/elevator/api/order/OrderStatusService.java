package pl.feelcodes.elevator.api.order;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Reads the tag-keyed {@code order_status} read-model via a reactive Spring Data repository. */
@Service
class OrderStatusService {

    private final OrderStatusRepository repository;

    OrderStatusService(OrderStatusRepository repository) {
        this.repository = repository;
    }

    /** The order's status by tag, or empty if the tag is unknown. */
    Mono<OrderStatusDto> byTag(String tag) {
        return repository.findById(tag).map(e -> new OrderStatusDto(
                e.getTag(),
                e.getElevatorName(),
                e.getFloor(),
                e.getCreatedAt(),
                e.getDoneAt(),
                e.getStatus()));
    }
}
