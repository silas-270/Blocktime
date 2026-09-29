//! docs/shared-challenges.md "Protocol" over HTTP, rule for rule the suite the app's
//! `FakeRoomApiTest` holds the fake to, against the in-memory store and a clock the test moves.

use std::sync::Arc;
use std::sync::atomic::{AtomicI64, Ordering};

use axum::Router;
use axum::body::Body;
use axum::http::{Method, Request, StatusCode};
use blocktime_backend::store::Store;
use blocktime_backend::{AppState, router};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use tower::ServiceExt;

struct Server {
    app: Router,
    now: Arc<AtomicI64>,
}

const SELF: &str = "SELF01";

fn secret(pilot: &str) -> String {
    format!("{pilot}-SECRET-ABCDEFGHJKLMNPQRSTUV")
}

impl Server {
    fn new() -> Server {
        let now = Arc::new(AtomicI64::new(1_000));
        let clock = now.clone();
        let mut state = AppState::new(Store::memory());
        state.clock = Arc::new(move || clock.load(Ordering::SeqCst));
        state.client_ip_header = Some("x-real-ip".into());
        Server { app: router(Arc::new(state)), now }
    }

    fn at(&self, millis: i64) {
        self.now.store(millis, Ordering::SeqCst);
    }

    async fn call(
        &self,
        method: Method,
        uri: &str,
        pilot: Option<(&str, &str)>,
        body: Option<Value>,
    ) -> (StatusCode, Value) {
        let mut request = Request::builder().method(method).uri(uri).header("x-real-ip", "10.0.0.1");
        if let Some((code, secret)) = pilot {
            request = request.header("x-pilot", code).header("authorization", format!("Bearer {secret}"));
        }
        let request = match body {
            Some(body) => request.header("content-type", "application/json").body(Body::from(body.to_string())),
            None => request.body(Body::empty()),
        }
        .unwrap();
        let response = self.app.clone().oneshot(request).await.unwrap();
        let status = response.status();
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        (status, serde_json::from_slice(&bytes).unwrap_or(Value::Null))
    }

    async fn create(&self, definition: Value) -> Value {
        let (status, room) = self
            .call(
                Method::POST,
                "/rooms",
                Some((SELF, &secret(SELF))),
                Some(json!({"definition": definition, "snapshot": {"userCode": SELF, "username": "Swift Pilot"}})),
            )
            .await;
        assert_eq!(status, StatusCode::CREATED, "{room}");
        room
    }

    async fn put_as(&self, pilot: &str, code: &str, snapshot: Value, claim: Option<Value>) -> (StatusCode, Value) {
        let mut body = json!({"snapshot": snapshot});
        if let Some(claim) = claim {
            body["claim"] = claim;
        }
        self.call(
            Method::PUT,
            &format!("/rooms/{code}/participants/{pilot}"),
            Some((pilot, &secret(pilot))),
            Some(body),
        )
        .await
    }

    async fn put(&self, pilot: &str, code: &str, snapshot: Value) -> Value {
        let (status, room) = self.put_as(pilot, code, snapshot, None).await;
        assert_eq!(status, StatusCode::OK, "{room}");
        room
    }

    async fn leave(&self, pilot: &str, code: &str) -> StatusCode {
        self.call(Method::DELETE, &format!("/rooms/{code}/participants/{pilot}"), Some((pilot, &secret(pilot))), None)
            .await
            .0
    }

    async fn room(&self, code: &str) -> Value {
        let (status, room) = self.call(Method::GET, &format!("/rooms/{code}"), None, None).await;
        assert_eq!(status, StatusCode::OK);
        room
    }
}

fn distance() -> Value {
    json!({"type": "DISTANCE", "source": "CUSTOM", "name": "Around the world", "targetDistanceKm": 40000.0})
}

fn race() -> Value {
    json!({"type": "ROUTE", "source": "CURATED", "name": "London to Sydney", "originIata": "LHR", "destIata": "SYD"})
}

fn streak() -> Value {
    json!({"type": "STREAK", "source": "CUSTOM", "name": "Streak", "targetDays": 3})
}

fn code_of(room: &Value) -> String {
    room["code"].as_str().unwrap().to_owned()
}

fn participant<'a>(room: &'a Value, pilot: &str) -> &'a Value {
    room["participants"].as_array().unwrap().iter().find(|p| p["userCode"] == pilot).unwrap()
}

fn codes(room: &Value) -> Vec<&str> {
    room["participants"].as_array().unwrap().iter().map(|p| p["userCode"].as_str().unwrap()).collect()
}

#[tokio::test]
async fn health_answers_204() {
    assert_eq!(Server::new().call(Method::GET, "/health", None, None).await.0, StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn creating_a_room_gives_a_six_character_code_from_the_alphabet_and_colour_0() {
    let server = Server::new();
    let room = server.create(distance()).await;

    let code = code_of(&room);
    assert_eq!(code.len(), 6);
    assert!(code.chars().all(|c| "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".contains(c)));
    assert_eq!(room["definition"], distance());
    assert_eq!(room["createdAt"], 1_000);
    assert_eq!(room["version"], 1);
    assert_eq!(
        room["participants"],
        json!([{"userCode": SELF, "username": "Swift Pilot", "colorIndex": 0, "left": false, "updatedAt": 1_000}])
    );
    assert!(room.get("outcome").is_none());
    assert_eq!(server.room(&code).await, room);
}

#[tokio::test]
async fn getting_or_putting_an_unknown_room_is_404() {
    let server = Server::new();
    assert_eq!(server.call(Method::GET, "/rooms/NOPE42", None, None).await.0, StatusCode::NOT_FOUND);
    assert_eq!(server.put_as(SELF, "NOPE42", json!({"userCode": SELF}), None).await.0, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn a_strangers_first_put_is_the_join_and_takes_the_next_colour() {
    let server = Server::new();
    let code = code_of(&server.create(distance()).await);

    let room = server
        .put("ANNA02", &code, json!({"userCode": "ANNA02", "username": "Anna", "colorIndex": 5, "distanceKm": 100.0}))
        .await;

    assert_eq!(codes(&room), ["SELF01", "ANNA02"]);
    let anna = participant(&room, "ANNA02");
    assert_eq!(anna["colorIndex"], 1);
    assert_eq!(anna["distanceKm"], 100.0);
    assert_eq!(anna["updatedAt"], 1_000);
}

#[tokio::test]
async fn a_put_by_an_existing_participant_replaces_their_snapshot() {
    let server = Server::new();
    let code = code_of(&server.create(distance()).await);
    server.put("ANNA02", &code, json!({"userCode": "ANNA02"})).await;

    server.at(2_000);
    let room = server.put(SELF, &code, json!({"userCode": SELF, "distanceKm": 500.0})).await;

    assert_eq!(codes(&room).len(), 2);
    let mine = participant(&room, SELF);
    assert_eq!(mine["distanceKm"], 500.0);
    assert_eq!(mine["colorIndex"], 0);
    assert_eq!(mine["updatedAt"], 2_000);
    assert!(mine.get("username").is_none(), "a put replaces the snapshot, it does not merge into it");
}

#[tokio::test]
async fn a_left_snapshot_with_the_same_code_is_replaced_and_no_longer_left() {
    let server = Server::new();
    let code = code_of(&server.create(distance()).await);
    server.put("ANNA02", &code, json!({"userCode": "ANNA02"})).await;
    assert_eq!(server.leave(SELF, &code).await, StatusCode::NO_CONTENT);
    assert_eq!(participant(&server.room(&code).await, SELF)["left"], true);

    let room = server.put(SELF, &code, json!({"userCode": SELF, "distanceKm": 10.0})).await;

    let mine = participant(&room, SELF);
    assert_eq!(mine["left"], false);
    assert_eq!(mine["colorIndex"], 0);
    assert_eq!(codes(&room).len(), 2);
}

#[tokio::test]
async fn joining_a_closed_room_is_room_closed() {
    let server = Server::new();
    let code = code_of(&server.create(distance()).await);
    server
        .put_as(
            SELF,
            &code,
            json!({"userCode": SELF, "distanceKm": 40000.0}),
            Some(json!({"kind": "completed", "at": 1_000})),
        )
        .await;

    let (status, body) = server.put_as("ANNA02", &code, json!({"userCode": "ANNA02"}), None).await;

    assert_eq!((status, body), (StatusCode::CONFLICT, json!({"error": "RoomClosed"})));
    assert_eq!(codes(&server.room(&code).await), [SELF]);
}

#[tokio::test]
async fn an_existing_participant_may_still_put_into_a_closed_room() {
    let server = Server::new();
    let code = code_of(&server.create(distance()).await);
    server.put("ANNA02", &code, json!({"userCode": "ANNA02"})).await;
    server
        .put_as(SELF, &code, json!({"userCode": SELF, "distanceKm": 40000.0}), Some(json!({"kind": "completed"})))
        .await;

    let room = server.put("ANNA02", &code, json!({"userCode": "ANNA02", "distanceKm": 7.0})).await;

    assert_eq!(participant(&room, "ANNA02")["distanceKm"], 7.0);
    assert_eq!(room["outcome"]["kind"], "completed");
}

#[tokio::test]
async fn joining_a_route_room_with_progress_is_race_locked() {
    let server = Server::new();
    let by_progress = code_of(&server.create(race()).await);
    server.put(SELF, &by_progress, json!({"userCode": SELF, "routeProgress": 0.1})).await;
    let (status, body) = server.put_as("ANNA02", &by_progress, json!({"userCode": "ANNA02"}), None).await;
    assert_eq!((status, body), (StatusCode::CONFLICT, json!({"error": "RaceLocked"})));

    let by_leg = code_of(&server.create(race()).await);
    server.put(SELF, &by_leg, json!({"userCode": SELF, "legIndex": 1})).await;
    assert_eq!(server.put_as("ANNA02", &by_leg, json!({"userCode": "ANNA02"}), None).await.0, StatusCode::CONFLICT);
}

#[tokio::test]
async fn joining_a_route_room_at_its_origin_is_allowed() {
    let server = Server::new();
    let code = code_of(&server.create(race()).await);
    assert_eq!(codes(&server.put("ANNA02", &code, json!({"userCode": "ANNA02"})).await).len(), 2);
}

#[tokio::test]
async fn an_existing_participant_may_still_put_into_a_race_with_progress() {
    let server = Server::new();
    let code = code_of(&server.create(race()).await);
    server.put("ANNA02", &code, json!({"userCode": "ANNA02"})).await;
    server.put("ANNA02", &code, json!({"userCode": "ANNA02", "routeProgress": 0.5, "legIndex": 1})).await;

    let room = server.put(SELF, &code, json!({"userCode": SELF, "routeProgress": 0.2})).await;
    assert_eq!(participant(&room, SELF)["routeProgress"], 0.2);
}

#[tokio::test]
async fn joining_a_pool_with_progress_is_allowed() {
    let server = Server::new();
    let code = code_of(&server.create(distance()).await);
    server.put(SELF, &code, json!({"userCode": SELF, "distanceKm": 12000.0})).await;
    assert_eq!(codes(&server.put("ANNA02", &code, json!({"userCode": "ANNA02"})).await).len(), 2);
}

#[tokio::test]
async fn the_seventh_join_is_room_full_and_a_leaver_still_counts() {
    let server = Server::new();
    let code = code_of(&server.create(distance()).await);
    for i in 0..5 {
        let bot = format!("BOT00{i}");
        server.put(&bot, &code, json!({"userCode": bot})).await;
    }
    server.leave("BOT000", &code).await;

    let (status, body) = server.put_as("LATE07", &code, json!({"userCode": "LATE07"}), None).await;
    assert_eq!((status, body), (StatusCode::CONFLICT, json!({"error": "RoomFull"})));
    // A member of a full room can still write.
    assert_eq!(codes(&server.put(SELF, &code, json!({"userCode": SELF, "distanceKm": 1.0})).await).len(), 6);
}

#[tokio::test]
async fn the_first_claim_sets_the_outcome_at_server_time() {
    let server = Server::new();
    let code = code_of(&server.create(distance()).await);
    server.at(5_000);

    let (_, room) = server
        .put_as(
            SELF,
            &code,
            json!({"userCode": SELF, "distanceKm": 40000.0}),
            Some(json!({"kind": "completed", "at": 4_900})),
        )
        .await;

    assert_eq!(room["outcome"], json!({"kind": "completed", "byUserCode": SELF, "at": 5_000, "placements": [SELF]}));
}

#[tokio::test]
async fn a_second_claim_is_ignored_and_the_first_outcome_stands() {
    let server = Server::new();
    let code = code_of(&server.create(distance()).await);
    server.put("ANNA02", &code, json!({"userCode": "ANNA02"})).await;
    server.at(5_000);
    let (_, first) = server
        .put_as(
            "ANNA02",
            &code,
            json!({"userCode": "ANNA02", "distanceKm": 40000.0}),
            Some(json!({"kind": "completed"})),
        )
        .await;

    server.at(6_000);
    let (_, room) = server
        .put_as(SELF, &code, json!({"userCode": SELF, "distanceKm": 40000.0}), Some(json!({"kind": "completed"})))
        .await;

    assert_eq!(room["outcome"], first["outcome"]);
    assert_eq!(room["outcome"]["byUserCode"], "ANNA02");
    // The refused claimer's snapshot was still stored.
    assert_eq!(participant(&room, SELF)["distanceKm"], 40000.0);
}

#[tokio::test]
async fn a_failed_claim_after_a_completion_is_ignored_too() {
    let server = Server::new();
    let code = code_of(&server.create(streak()).await);
    server.put_as(SELF, &code, json!({"userCode": SELF, "streakDays": 3}), Some(json!({"kind": "completed"}))).await;

    let (_, room) = server
        .put_as(
            SELF,
            &code,
            json!({"userCode": SELF, "streakDays": 0}),
            Some(json!({"kind": "failed", "brokenBy": SELF})),
        )
        .await;

    assert_eq!(room["outcome"]["kind"], "completed");
}

#[tokio::test]
async fn a_route_completion_ranks_the_claimer_first_then_the_rest_by_progress() {
    let server = Server::new();
    let code = code_of(&server.create(race()).await);
    for pilot in ["ANNA02", "BOB003", "GONE04"] {
        server.put(pilot, &code, json!({"userCode": pilot})).await;
    }
    server.put("ANNA02", &code, json!({"userCode": "ANNA02", "routeProgress": 0.3})).await;
    server.put("BOB003", &code, json!({"userCode": "BOB003", "routeProgress": 0.8})).await;
    server.put("GONE04", &code, json!({"userCode": "GONE04", "routeProgress": 0.9})).await;
    server.leave("GONE04", &code).await;
    server.at(9_000);

    let (_, room) = server
        .put_as(
            SELF,
            &code,
            json!({"userCode": SELF, "routeProgress": 1.0, "positionIata": "SYD"}),
            Some(json!({"kind": "completed"})),
        )
        .await;

    assert_eq!(
        room["outcome"],
        json!({"kind": "completed", "byUserCode": SELF, "at": 9_000, "placements": [SELF, "BOB003", "ANNA02"]})
    );
}

#[tokio::test]
async fn a_pooled_completion_places_only_the_claimer() {
    let server = Server::new();
    let code = code_of(&server.create(distance()).await);
    server.put("ANNA02", &code, json!({"userCode": "ANNA02"})).await;

    let (_, room) = server.put_as(SELF, &code, json!({"userCode": SELF}), Some(json!({"kind": "completed"}))).await;
    assert_eq!(room["outcome"]["placements"], json!([SELF]));
}

#[tokio::test]
async fn a_failed_claim_names_the_breaker() {
    let server = Server::new();
    let code = code_of(&server.create(streak()).await);
    server.put("ANNA02", &code, json!({"userCode": "ANNA02"})).await;
    server.at(7_000);

    let (_, room) = server
        .put_as(
            SELF,
            &code,
            json!({"userCode": SELF, "streakDays": 2}),
            Some(json!({"kind": "failed", "brokenBy": "ANNA02"})),
        )
        .await;

    assert_eq!(room["outcome"], json!({"kind": "failed", "byUserCode": "ANNA02", "at": 7_000}));
}

#[tokio::test]
async fn every_write_stamps_updated_at_and_bumps_version() {
    let server = Server::new();
    let code = code_of(&server.create(distance()).await);

    server.at(2_000);
    let after_put = server.put(SELF, &code, json!({"userCode": SELF, "distanceKm": 1.0})).await;
    assert_eq!(after_put["version"], 2);
    assert_eq!(participant(&after_put, SELF)["updatedAt"], 2_000);

    server.at(3_000);
    server.leave(SELF, &code).await;
    let after_leave = server.room(&code).await;
    assert_eq!(after_leave["version"], 3);
    assert_eq!(participant(&after_leave, SELF)["updatedAt"], 3_000);

    // The client's own stamps are never trusted.
    let stamped =
        server.put(SELF, &code, json!({"userCode": SELF, "updatedAt": 99, "colorIndex": 4, "left": true})).await;
    let mine = participant(&stamped, SELF);
    assert_eq!(
        (mine["updatedAt"].clone(), mine["colorIndex"].clone(), mine["left"].clone()),
        (json!(3_000), json!(0), json!(false))
    );
}

#[tokio::test]
async fn leaving_marks_left_keeps_the_snapshot_and_is_idempotent() {
    let server = Server::new();
    let code = code_of(&server.create(distance()).await);
    server.put(SELF, &code, json!({"userCode": SELF, "distanceKm": 250.0})).await;

    assert_eq!(server.leave(SELF, &code).await, StatusCode::NO_CONTENT);
    let mine = participant(&server.room(&code).await, SELF).clone();
    assert_eq!((mine["left"].clone(), mine["distanceKm"].clone()), (json!(true), json!(250.0)));
    assert_eq!(server.leave(SELF, &code).await, StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn leaving_an_unknown_room_is_404_and_one_never_joined_is_204_and_changes_nothing() {
    let server = Server::new();
    assert_eq!(server.leave(SELF, "NOPE42").await, StatusCode::NOT_FOUND);

    let code = code_of(&server.create(distance()).await);
    assert_eq!(server.leave("ANNA02", &code).await, StatusCode::NO_CONTENT);
    let room = server.room(&code).await;
    assert_eq!((codes(&room), room["version"].clone()), (vec![SELF], json!(1)));
}

#[tokio::test]
async fn a_snapshot_for_another_pilot_is_401() {
    let server = Server::new();
    let code = code_of(&server.create(distance()).await);
    let me = Some((SELF, secret(SELF)));
    let me = me.as_ref().map(|(c, s)| (*c, s.as_str()));

    let into_path = server
        .call(
            Method::PUT,
            &format!("/rooms/{code}/participants/ANNA02"),
            me,
            Some(json!({"snapshot": {"userCode": "ANNA02"}})),
        )
        .await;
    let in_body = server
        .call(
            Method::PUT,
            &format!("/rooms/{code}/participants/{SELF}"),
            me,
            Some(json!({"snapshot": {"userCode": "ANNA02"}})),
        )
        .await;
    let create = server
        .call(Method::POST, "/rooms", me, Some(json!({"definition": distance(), "snapshot": {"userCode": "ANNA02"}})))
        .await;
    let leave = server.call(Method::DELETE, &format!("/rooms/{code}/participants/ANNA02"), me, None).await;

    for (status, _) in [into_path, in_body, create, leave] {
        assert_eq!(status, StatusCode::UNAUTHORIZED);
    }
    assert_eq!(codes(&server.room(&code).await), [SELF]);
}

#[tokio::test]
async fn the_first_write_binds_the_secret_and_another_secret_is_401() {
    let server = Server::new();
    let code = code_of(&server.create(distance()).await);

    let impostor = server
        .call(
            Method::PUT,
            &format!("/rooms/{code}/participants/{SELF}"),
            Some((SELF, "SOMEBODY-ELSES-SECRET")),
            Some(json!({"snapshot": {"userCode": SELF}})),
        )
        .await;
    assert_eq!(impostor.0, StatusCode::UNAUTHORIZED);

    let missing = server
        .call(
            Method::PUT,
            &format!("/rooms/{code}/participants/{SELF}"),
            None,
            Some(json!({"snapshot": {"userCode": SELF}})),
        )
        .await;
    assert_eq!(missing.0, StatusCode::UNAUTHORIZED);

    assert_eq!(server.room(&code).await["version"], 1);
}

#[tokio::test]
async fn unknown_fields_travel_through_untouched() {
    let server = Server::new();
    let mut definition = distance();
    definition["addedInV3"] = json!({"nested": true});
    let code = code_of(&server.create(definition.clone()).await);

    let room =
        server.put("ANNA02", &code, json!({"userCode": "ANNA02", "visitedMembers": ["FR", "DE"], "newThing": 7})).await;

    assert_eq!(room["definition"], definition);
    assert_eq!(participant(&room, "ANNA02")["newThing"], 7);
    assert_eq!(participant(&room, "ANNA02")["visitedMembers"], json!(["FR", "DE"]));
}

#[tokio::test]
async fn too_many_misses_lock_the_client_out_of_hits_too() {
    let server = Server::new();
    let code = code_of(&server.create(distance()).await);
    for _ in 0..blocktime_backend::limit::MAX_MISSES {
        assert_eq!(server.call(Method::GET, "/rooms/NOPE42", None, None).await.0, StatusCode::NOT_FOUND);
    }

    assert_eq!(server.call(Method::GET, &format!("/rooms/{code}"), None, None).await.0, StatusCode::TOO_MANY_REQUESTS);

    server.at(1_000 + blocktime_backend::limit::WINDOW_MS);
    assert_eq!(server.call(Method::GET, &format!("/rooms/{code}"), None, None).await.0, StatusCode::OK);
}

#[tokio::test]
async fn an_oversized_body_is_refused() {
    let server = Server::new();
    let huge = "x".repeat(20 * 1024);
    let (status, _) = server
        .call(
            Method::POST,
            "/rooms",
            Some((SELF, &secret(SELF))),
            Some(json!({"definition": distance(), "snapshot": {"userCode": SELF, "username": huge}})),
        )
        .await;
    assert_eq!(status, StatusCode::PAYLOAD_TOO_LARGE);
}

#[tokio::test]
async fn a_room_is_swept_30_days_after_its_outcome_or_180_after_its_last_write() {
    use blocktime_backend::store::{RETAIN_AFTER_OUTCOME_MS, RETAIN_AFTER_WRITE_MS};

    let store = Store::memory();
    let definition = distance().as_object().unwrap().clone();
    let creator =
        |code: &str| blocktime_backend::room::Snapshot(json!({"userCode": code}).as_object().unwrap().clone());

    let mut closed = blocktime_backend::room::Room::create("CLOSED".into(), definition.clone(), creator(SELF), 0);
    let claim: blocktime_backend::room::Claim = serde_json::from_value(json!({"kind": "completed"})).unwrap();
    closed.put(creator(SELF), Some(&claim), 10).unwrap();
    let idle = blocktime_backend::room::Room::create("IDLE22".into(), definition, creator(SELF), 0);
    store.insert(&closed).await.unwrap();
    store.insert(&idle).await.unwrap();

    assert_eq!(store.sweep(10 + RETAIN_AFTER_OUTCOME_MS - 1).await.unwrap().rooms, 0);
    assert_eq!(store.sweep(10 + RETAIN_AFTER_OUTCOME_MS + 1).await.unwrap().rooms, 1);
    assert!(store.get("CLOSED").await.unwrap().is_none());
    assert_eq!(store.sweep(RETAIN_AFTER_WRITE_MS + 1).await.unwrap().rooms, 1);
    assert!(store.get("IDLE22").await.unwrap().is_none());
}

#[tokio::test]
async fn a_pilot_is_swept_180_days_after_their_last_write_and_can_then_bind_again() {
    use blocktime_backend::store::RETAIN_AFTER_WRITE_MS;

    let store = Store::memory();
    store.bind_pilot(SELF, b"first", 0).await.unwrap();
    // A later write keeps the pilot, and the first secret still stands.
    assert_eq!(store.bind_pilot(SELF, b"other", 100).await.unwrap(), b"first");

    assert_eq!(store.sweep(100 + RETAIN_AFTER_WRITE_MS - 1).await.unwrap().pilots, 0);
    assert_eq!(store.sweep(100 + RETAIN_AFTER_WRITE_MS + 1).await.unwrap().pilots, 1);
    assert_eq!(store.bind_pilot(SELF, b"second", RETAIN_AFTER_WRITE_MS + 200).await.unwrap(), b"second");
}
