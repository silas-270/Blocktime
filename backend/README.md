# Blocktime room server

The relay behind shared challenges. It holds, per room, one definition and one snapshot per pilot,
applies the rules of [docs/shared-challenges.md "Protocol"](../docs/shared-challenges.md#protocol),
and knows nothing about flights. The app keeps working without it: a shared challenge whose
server is gone carries on as a normal one.

Rust, [axum](https://github.com/tokio-rs/axum) on tokio, and Postgres through
[sqlx](https://github.com/launchbadge/sqlx). An idle instance needs a few MB of memory.

| File | What it holds |
|---|---|
| `src/room.rs` | A room and the protocol's rules, free of HTTP and storage |
| `src/api.rs` | The five routes, authentication, and how each refusal maps to a status |
| `src/store.rs` | Postgres, or an in-memory map; every room change is a locked read-modify-write |
| `src/limit.rs` | The look-up rate limit against code guessing |
| `migrations/` | The schema, applied on start |
| `tests/protocol.rs` | The contract, rule for rule, over HTTP |

## Running it

```bash
cargo run                                   # in memory, port 8080; rooms die with the process
DATABASE_URL=postgres://… cargo run         # against Postgres; migrations run on start
cargo test                                  # the protocol rules
```

| Variable | Meaning | Default |
|---|---|---|
| `PORT` | Port to listen on | `8080` |
| `DATABASE_URL` | Postgres connection string. Unset keeps the rooms in memory | unset |
| `CLIENT_IP_HEADER` | Header a trusted proxy puts the client's address in, for the rate limit (`x-real-ip` on Railway) | the socket's address |
| `RUST_LOG` | Log filter | `info,tower_http=info` |

To point the app at it, set `ROOM_SERVER_URL` in the app's `local.properties`. A phone reaches a
server on your machine at `http://<your LAN address>:8080`; plain HTTP is allowed in debug builds
only, so a release build needs the deployed HTTPS server. The app's own client can be tested
against a running server:

```bash
ROOM_SERVER_TEST_URL=http://localhost:8080 ./gradlew :app:testDebugUnitTest --tests '*HttpRoomApiLiveTest*'
```

## Deploying

The `Dockerfile` builds a release binary and runs it on a slim Debian image. On Railway: a
service from this repository with root directory `backend`, a Postgres database whose
`DATABASE_URL` is referenced by the service, `CLIENT_IP_HEADER=x-real-ip`, and a public domain.
`/health` answers 204 when the database does, which makes a good health check.
