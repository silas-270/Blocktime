//! The five routes of docs/shared-challenges.md "Protocol", and how each refusal maps to a status
//! the app's `HttpRoomApi` turns back into a `RoomResult`.
//!
//! | Status | `RoomResult` |
//! |---|---|
//! | 200, 201, 204 | `Ok` |
//! | 404 | `NotFound` |
//! | 409 `{"error": "RoomClosed" \| "RaceLocked" \| "RoomFull"}` | the matching case |
//! | 401 | `Unauthorized` |
//! | 429, 5xx, anything else | `Unreachable` (nothing changes locally) |

use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;

use axum::extract::{ConnectInfo, DefaultBodyLimit, FromRequestParts, Path, State};
use axum::http::StatusCode;
use axum::http::request::Parts;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post, put};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::{Map, Value, json};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

use crate::limit::MissLimiter;
use crate::room::{self, Claim, JoinRefusal, Room, Snapshot};
use crate::store::{Store, UpdateError};

/// Rooms are under 4 KB; a request four times that is not one the app sends.
const BODY_LIMIT: usize = 16 * 1024;

pub type Clock = Arc<dyn Fn() -> i64 + Send + Sync>;

pub struct AppState {
    pub store: Store,
    pub limiter: MissLimiter,
    pub clock: Clock,
    /// The header a trusted reverse proxy puts the client's address in (Railway: `x-real-ip`).
    /// None reads the socket's address, which behind a proxy is the proxy's.
    pub client_ip_header: Option<String>,
}

impl AppState {
    pub fn new(store: Store) -> AppState {
        AppState {
            store,
            limiter: MissLimiter::default(),
            clock: Arc::new(|| {
                std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as i64
            }),
            client_ip_header: None,
        }
    }

    fn now(&self) -> i64 {
        (self.clock)()
    }
}

pub fn router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/health", get(health))
        .route("/rooms", post(create_room))
        .route("/rooms/{code}", get(get_room))
        .route("/rooms/{code}/participants/{user_code}", put(put_snapshot).delete(leave_room))
        .layer(DefaultBodyLimit::max(BODY_LIMIT))
        .with_state(state)
}

#[derive(Debug)]
pub enum ApiError {
    NotFound,
    Refused(JoinRefusal),
    Unauthorized,
    BadRequest(&'static str),
    RateLimited,
    Internal,
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let (status, error) = match self {
            ApiError::NotFound => (StatusCode::NOT_FOUND, "NotFound"),
            ApiError::Refused(JoinRefusal::RoomClosed) => (StatusCode::CONFLICT, "RoomClosed"),
            ApiError::Refused(JoinRefusal::RaceLocked) => (StatusCode::CONFLICT, "RaceLocked"),
            ApiError::Refused(JoinRefusal::RoomFull) => (StatusCode::CONFLICT, "RoomFull"),
            ApiError::Unauthorized => (StatusCode::UNAUTHORIZED, "Unauthorized"),
            ApiError::BadRequest(reason) => (StatusCode::BAD_REQUEST, reason),
            ApiError::RateLimited => (StatusCode::TOO_MANY_REQUESTS, "RateLimited"),
            ApiError::Internal => (StatusCode::INTERNAL_SERVER_ERROR, "Internal"),
        };
        (status, Json(json!({ "error": error }))).into_response()
    }
}

impl From<sqlx::Error> for ApiError {
    fn from(error: sqlx::Error) -> Self {
        tracing::error!(%error, "database");
        ApiError::Internal
    }
}

impl From<UpdateError<ApiError>> for ApiError {
    fn from(error: UpdateError<ApiError>) -> Self {
        match error {
            UpdateError::Refused(refusal) => refusal,
            UpdateError::Database(error) => error.into(),
        }
    }
}

/// The caller as the headers name them: `X-Pilot: <userCode>` and `Authorization: Bearer
/// <secret>`. Checked against the stored secret only by [authenticate], because a read needs
/// neither.
struct Pilot {
    code: String,
    secret_hash: [u8; 32],
}

impl<S: Send + Sync> FromRequestParts<S> for Pilot {
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, _: &S) -> Result<Self, ApiError> {
        let header = |name: &str| parts.headers.get(name).and_then(|v| v.to_str().ok());
        let code = header("x-pilot").filter(|c| room::is_valid_code(c)).ok_or(ApiError::Unauthorized)?;
        let secret = header("authorization")
            .and_then(|v| v.strip_prefix("Bearer "))
            .filter(|s| (16..=128).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_graphic()))
            .ok_or(ApiError::Unauthorized)?;
        Ok(Pilot { code: code.to_owned(), secret_hash: Sha256::digest(secret.as_bytes()).into() })
    }
}

/// Binds the pilot's code to their secret on the first write the server sees, and afterwards
/// refuses any other secret for that code.
async fn authenticate(state: &AppState, pilot: &Pilot) -> Result<(), ApiError> {
    let stored = state.store.bind_pilot(&pilot.code, &pilot.secret_hash).await?;
    if bool::from(stored.as_slice().ct_eq(&pilot.secret_hash)) { Ok(()) } else { Err(ApiError::Unauthorized) }
}

/// The address the miss limiter counts against.
struct ClientIp(IpAddr);

impl FromRequestParts<Arc<AppState>> for ClientIp {
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, state: &Arc<AppState>) -> Result<Self, ApiError> {
        let from_header = state
            .client_ip_header
            .as_deref()
            .and_then(|name| parts.headers.get(name))
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.trim().parse().ok());
        let from_socket = parts.extensions.get::<ConnectInfo<SocketAddr>>().map(|c| c.0.ip());
        Ok(ClientIp(from_header.or(from_socket).unwrap_or(IpAddr::V4(Ipv4Addr::UNSPECIFIED))))
    }
}

/// Refuses a client that has missed too often; a request that then misses counts against it.
fn check_limit(state: &AppState, client: &ClientIp) -> Result<(), ApiError> {
    if state.limiter.allows(client.0, state.now()) { Ok(()) } else { Err(ApiError::RateLimited) }
}

fn miss(state: &AppState, client: &ClientIp) -> ApiError {
    state.limiter.record_miss(client.0, state.now());
    ApiError::NotFound
}

async fn health(State(state): State<Arc<AppState>>) -> StatusCode {
    if state.store.healthy().await { StatusCode::NO_CONTENT } else { StatusCode::SERVICE_UNAVAILABLE }
}

#[derive(Deserialize)]
struct CreateBody {
    definition: Map<String, Value>,
    snapshot: Snapshot,
}

async fn create_room(
    State(state): State<Arc<AppState>>,
    pilot: Pilot,
    Json(body): Json<CreateBody>,
) -> Result<(StatusCode, Json<Room>), ApiError> {
    authenticate(&state, &pilot).await?;
    if body.snapshot.user_code() != Some(pilot.code.as_str()) {
        return Err(ApiError::Unauthorized);
    }
    if !body.definition.get("type").is_some_and(Value::is_string) {
        return Err(ApiError::BadRequest("DefinitionWithoutType"));
    }
    // 32^6 codes: a collision is rare, and ten in a row means something else is wrong.
    for _ in 0..10 {
        let code = room::generate_code(room::ROOM_CODE_LENGTH);
        let room = Room::create(code, body.definition.clone(), body.snapshot.clone(), state.now());
        if state.store.insert(&room).await? {
            return Ok((StatusCode::CREATED, Json(room)));
        }
    }
    Err(ApiError::Internal)
}

async fn get_room(
    State(state): State<Arc<AppState>>,
    client: ClientIp,
    Path(code): Path<String>,
) -> Result<Json<Room>, ApiError> {
    check_limit(&state, &client)?;
    if !room::is_valid_code(&code) {
        return Err(miss(&state, &client));
    }
    state.store.get(&code).await?.map(Json).ok_or_else(|| miss(&state, &client))
}

#[derive(Deserialize)]
struct PutBody {
    snapshot: Snapshot,
    #[serde(default)]
    claim: Option<Claim>,
}

async fn put_snapshot(
    State(state): State<Arc<AppState>>,
    client: ClientIp,
    pilot: Pilot,
    Path((code, user_code)): Path<(String, String)>,
    Json(body): Json<PutBody>,
) -> Result<Json<Room>, ApiError> {
    authenticate(&state, &pilot).await?;
    if user_code != pilot.code || body.snapshot.user_code() != Some(pilot.code.as_str()) {
        return Err(ApiError::Unauthorized);
    }
    check_limit(&state, &client)?;
    let now = state.now();
    let updated = state
        .store
        .update(&code, |room| {
            room.put(body.snapshot, body.claim.as_ref(), now).map(|()| (true, ())).map_err(ApiError::Refused)
        })
        .await?;
    updated.map(|(room, ())| Json(room)).ok_or_else(|| miss(&state, &client))
}

async fn leave_room(
    State(state): State<Arc<AppState>>,
    client: ClientIp,
    pilot: Pilot,
    Path((code, user_code)): Path<(String, String)>,
) -> Result<StatusCode, ApiError> {
    authenticate(&state, &pilot).await?;
    if user_code != pilot.code {
        return Err(ApiError::Unauthorized);
    }
    check_limit(&state, &client)?;
    let now = state.now();
    let left = state.store.update(&code, |room| Ok::<_, ApiError>((room.leave(&pilot.code, now), ()))).await?;
    left.map(|_| StatusCode::NO_CONTENT).ok_or_else(|| miss(&state, &client))
}
