package pl.feelcodes.elevator.api.served;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

interface ServedRepository extends ReactiveCrudRepository<ServedEntity, String> {

    Flux<ServedEntity> findAllByOrderByOrdersServedDesc();
}
