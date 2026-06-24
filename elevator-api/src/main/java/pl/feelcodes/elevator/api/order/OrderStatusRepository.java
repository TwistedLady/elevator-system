package pl.feelcodes.elevator.api.order;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

interface OrderStatusRepository extends ReactiveCrudRepository<OrderStatusEntity, String> {
}
