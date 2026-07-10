package pl.feelcodes.elevator.api.call;

public record SimStatusResponse(int total, int done, int progress, int pending) {
}
