# Auth

`POST /api/call` accepts **optional HTTP Basic** authentication. The point is passenger identity:
identity comes from the login, never the request body.

- **With valid credentials** → the authenticated username becomes the call's `passengerId`.
- **No credentials** → the call is **anonymous** (still accepted).
- **Bad credentials** → `401` (Spring Security rejects them).

Every endpoint is `permitAll`, so anonymous access to state, health, and calls keeps working; auth
only *labels* a call with a passenger. See the counts in [protocol.md](protocol.md#passenger-identification).

## Where it lives

| Piece | File |
|---|---|
| Security config (optional Basic, permitAll) | `elevator-api/.../config/SecurityConfig.java` |
| User accounts | `elevator-api/src/main/resources/passengers.properties` |
| Passenger extracted from the principal | `elevator-api/.../call/CallController.java` |

Users are a file of `username=password` lines — a lab stand-in for a real directory (LDAP/OIDC).
Passwords are stored plainly with the `{noop}` encoder: **lab-only, not for production.**

The Rust console's simulator authenticates ~half its calls as recurring `rider-N` accounts (shared
password `liftpass`) and leaves the rest anonymous, so both tallies are exercised.

```bash
# anonymous
curl -sk -X POST https://localhost:8080/api/call \
  -H 'content-type: application/json' -d '{"elevatorName":"e1","floor":3}'

# as a passenger
curl -sk -u rider-0:liftpass -X POST https://localhost:8080/api/call \
  -H 'content-type: application/json' -d '{"elevatorName":"e1","floor":3}'
```
