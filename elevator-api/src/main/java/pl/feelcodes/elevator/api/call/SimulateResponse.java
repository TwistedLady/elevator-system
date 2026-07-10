package pl.feelcodes.elevator.api.call;

import java.util.List;

public record SimulateResponse(String runId, int count, List<String> ids) {
}
