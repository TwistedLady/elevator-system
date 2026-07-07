package pl.feelcodes.elevator.api.call;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

interface CallStatusRepository extends ReactiveCrudRepository<CallStatusEntity, String> {
}
