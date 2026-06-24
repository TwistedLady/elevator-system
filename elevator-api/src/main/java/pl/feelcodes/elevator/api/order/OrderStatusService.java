package pl.feelcodes.elevator.api.order;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
class OrderStatusService {

    private final OrderStatusRepository repository;

    OrderStatusService(OrderStatusRepository repository) {
        this.repository = repository;
    }

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
