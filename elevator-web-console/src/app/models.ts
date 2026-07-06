// Mirrors the elevator-api elevator-state contract (elevator-common-dto/Dtos.scala,
// GET /api/elevator/stream). The console is read-only: Chart + Trend tabs only.

export interface ElevatorState {
  tag: string;
  elevatorName: string;
  direction: string; // UP | DOWN | NONE
  motion: string;    // MOVING | IDLE
  floor: number;
}
