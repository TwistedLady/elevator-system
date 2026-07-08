package pl.feelcodes.elevator.api.call;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
class CallStatusService {

    private final CallStatusRepository repository;

    CallStatusService(CallStatusRepository repository) {
        this.repository = repository;
    }

    Mono<CallStatusDto> byId(String id) {
        return repository.findById(id).map(e -> new CallStatusDto(
                e.getCallId(),
                e.getElevatorName(),
                e.getFloor(),
                e.getOrderId(),
                e.getCreatedAt(),
                e.getDoneAt(),
                e.getStatus()));
    }
}
