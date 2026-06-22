package pl.feelcodes.elevator.api.orderstatus;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

/**
 * Reactive Spring Data repository over the {@code order_status} read-model. Lookups use the
 * inherited {@code findById(tag)} — no hand-written SQL. (JPA/Hibernate are blocking and don't
 * fit this WebFlux + R2DBC app, so the reactive Spring Data repository is the equivalent here.)
 */
interface OrderStatusRepository extends ReactiveCrudRepository<OrderStatusEntity, String> {
}
