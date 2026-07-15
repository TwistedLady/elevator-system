package pl.feelcodes.elevator.api.board;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// The passenger stepping in is the authenticated caller (JWT subject), so the body carries only
// where: which elevator and floor. Any passengerId a client might send is never read here.
record BoardRequestDto(@NotBlank String elevatorName, @NotNull Integer floor) {
}
