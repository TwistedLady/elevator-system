package pl.feelcodes.elevator.api.stats.mileage;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

interface MileageRepository extends ReactiveCrudRepository<MileageEntity, String> {

    Flux<MileageEntity> findAllByOrderByFloorsTravelledDesc();
}
