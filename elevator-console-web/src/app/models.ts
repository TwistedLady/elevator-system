// Mirrors the elevator-api elevator-state contract (elevator-common-dto/Dtos.scala,
// GET /api/elevator/stream). The console is read-only: Chart + Trend tabs only.

export interface ElevatorState {
  tag: string;
  elevatorName: string;
  direction: string; // Up | Down   (core Direction enum)
  motion: string;    // Moving | Stopped   (core Motion enum)
  floor: number;
}

/** Live state paired with its recent floor history (oldest → newest), for the charts. */
export interface Row {
  state: ElevatorState;
  history: number[];
}
