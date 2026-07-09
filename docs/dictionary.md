# Dictionary

The words this system uses. Call and Order are **not** the same thing.

| Term | Meaning |
|---|---|
| **Call** | A user action: a button press to visit a floor. `id, elevatorName, floor`, optional `passengerId`. |
| **Order** | App-made. One living stop per floor. `id = f(elevator, floor)`; later same-floor calls attach until it is done. Tracks two tallies: `passengers` (distinct riders) and `anonymous` (id-less presses). |
| **passengerId** | Optional rider identity on a Call, supplied by the API caller. Lets the Manager count distinct riders vs. anonymous presses per order. |
| **Coordinator** | Actor, owns **call** status. Receives calls, forwards them to the Manager, tracks each to done. |
| **Manager** | Actor, owns the **call↔order** relation. Groups calls into orders, assigns them, marks orders done. |
| **Controller** | Actor, owns **movement**. Picks the next stop (`NextFloorStrategy`), tells the Operator to move. |
| **Operator** | Actor, stateless. Applies one move on the engine. |
| **GroupCallsStrategy** | Groups calls by floor into orders; the order id is per (elevator, floor). |
| **NextFloorStrategy** | Picks the next floor to serve from the pending orders. |
| **call_status** | Read table: one row per call (`GET /api/call/{id}`). |
| **order_status** | Read table: one row per order. |
| **elevator_state_view** | Read table: current state per elevator. |
| **processed_calls** | Dedup table: a call id seen once, never re-ingested. |
| **Topics** | `elevator-calls` (in); `elevator-state` / `elevator-order-state` / `elevator-call-state` (out, feed BI). |
