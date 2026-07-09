# Auth

**There is no authentication yet.** Every endpoint is open; nothing verifies who is calling. A real
login is planned — to be built from the ground up.

Until then, passenger identity is carried, unverified, in the request body:

- `POST /api/call` accepts an **optional `passengerId`** field.
- **Present** → it becomes the call's passenger (see the per-order counts in
  [protocol.md](protocol.md#passenger-identification)).
- **Absent or blank** → the call is **anonymous** (still accepted).

Because it is unverified, the `passengerId` is a *claim*, not proof of identity — a placeholder for
the authenticated user a future login will supply.

## Where it lives

| Piece | File |
|---|---|
| Reads `passengerId` from the body | `elevator-api/.../call/CallController.java` |
| Carries it on the wire DTO | `elevator-common/.../dto` `CallDto.passengerId` |

The Rust console's simulator tags ~half its calls with recurring `rider-N` identities and leaves the
rest anonymous, so both tallies are exercised.

```bash
# anonymous
curl -sk -X POST https://localhost:8080/api/call \
  -H 'content-type: application/json' -d '{"elevatorName":"e1","floor":3}'

# with a passenger claim
curl -sk -X POST https://localhost:8080/api/call \
  -H 'content-type: application/json' -d '{"elevatorName":"e1","floor":3,"passengerId":"rider-0"}'
```
