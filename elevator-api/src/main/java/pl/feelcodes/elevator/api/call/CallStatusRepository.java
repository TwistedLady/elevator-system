package pl.feelcodes.elevator.api.call;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

interface CallStatusRepository extends ReactiveCrudRepository<CallStatusEntity, String> {

    // Every call of a simulation run shares the id prefix "sim-{runId}-"; used to roll up progress.
    @Query("SELECT * FROM call_status WHERE call_id LIKE :prefix")
    Flux<CallStatusEntity> findByCallIdPrefix(String prefix);
}
