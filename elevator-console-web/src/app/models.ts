// Mirrors the elevator-api elevator-state contract (elevator-common-dto/Dtos.scala,
// GET /api/elevator/stream). The console is read-only: Chart + Trend tabs only.

export interface ElevatorState {
  tag: string;
  elevatorName: string;
  direction: string; // Up | Down   (core Direction enum)
  motion: string;    // Moving | Stopped   (core Motion enum)
  floor: number;
}

// Spark BI outcomes surfaced by the api (elevator-bi → Postgres → GET /api/mileage, /api/served).
// Both are per-elevator; the Stats tab merges them into one row per elevator.

export interface MileageStat {
  elevatorName: string;
  floorsTravelled: number; // total floors travelled (streaming mileage)
  updatedAt: string;
}

export interface OrdersServedStat {
  elevatorName: string;
  ordersServed: number; // times the elevator reached an ordered floor (DONE counts)
  updatedAt: string;
}
