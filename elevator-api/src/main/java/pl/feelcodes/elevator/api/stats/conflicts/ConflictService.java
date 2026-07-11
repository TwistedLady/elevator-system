package pl.feelcodes.elevator.api.stats.conflicts;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import pl.feelcodes.elevator.api.stats.Conflict;
import pl.feelcodes.elevator.api.stats.ParquetStatsReader;
import reactor.core.publisher.Flux;

import java.util.Comparator;

@Service
@ConditionalOnProperty(prefix = "elevator.bi", name = "enabled", havingValue = "true", matchIfMissing = true)
class ConflictService {

    private final ParquetStatsReader reader;

    ConflictService(ParquetStatsReader reader) {
        this.reader = reader;
    }

    /** Passenger double-bookings, earliest overlap first; empty is the healthy case. */
    Flux<ConflictDto> all() {
        return reader.conflicts()
                .sort(Comparator.comparing(Conflict::overlapStart,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(ConflictService::toDto);
    }

    private static ConflictDto toDto(Conflict c) {
        return new ConflictDto(c.passengerId(), c.elevatorA(), c.orderA(), c.elevatorB(), c.orderB(),
                c.overlapStart(), c.overlapEnd(), c.updatedAt());
    }
}
