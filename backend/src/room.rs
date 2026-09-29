//! A room and the rules of docs/shared-challenges.md "Protocol", free of HTTP and storage so
//! they read as the table they implement.
//!
//! The server knows nothing about flights. A definition and a snapshot are kept as the JSON
//! objects the app sent, with only the handful of fields the rules need read out of them, so a
//! field a newer app version adds survives the round trip to an older one untouched.

use rand::Rng;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

/// At most six pilots in a room; a pilot who left still counts.
pub const MAX_PARTICIPANTS: usize = 6;

/// Room codes, pilot codes and secrets are drawn from the app's alphabet: upper case without
/// the look-alikes I, O, 0 and 1.
pub const CODE_ALPHABET: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
pub const ROOM_CODE_LENGTH: usize = 6;

pub const KIND_COMPLETED: &str = "completed";
pub const KIND_FAILED: &str = "failed";

pub fn generate_code(length: usize) -> String {
    let mut rng = rand::rng();
    (0..length).map(|_| CODE_ALPHABET[rng.random_range(0..CODE_ALPHABET.len())] as char).collect()
}

/// Six characters of upper case letters and digits. Looser than [CODE_ALPHABET] on purpose: the
/// debug bot and old tests use codes like `BOT001`, and a code is an identifier, not a secret.
pub fn is_valid_code(code: &str) -> bool {
    code.len() == ROOM_CODE_LENGTH && code.bytes().all(|b| b.is_ascii_uppercase() || b.is_ascii_digit())
}

/// One pilot's snapshot, as the JSON object the app sent. The server writes `colorIndex`,
/// `left` and `updatedAt` and reads `userCode`, `routeProgress` and `legIndex`; everything else
/// is carried as it came.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct Snapshot(pub Map<String, Value>);

impl Snapshot {
    pub fn user_code(&self) -> Option<&str> {
        self.0.get("userCode").and_then(Value::as_str)
    }

    pub fn color_index(&self) -> i64 {
        self.0.get("colorIndex").and_then(Value::as_i64).unwrap_or(0)
    }

    pub fn left(&self) -> bool {
        self.0.get("left").and_then(Value::as_bool).unwrap_or(false)
    }

    pub fn route_progress(&self) -> f64 {
        self.0.get("routeProgress").and_then(Value::as_f64).unwrap_or(0.0)
    }

    fn leg_index(&self) -> i64 {
        self.0.get("legIndex").and_then(Value::as_i64).unwrap_or(0)
    }

    /// A race is locked to newcomers once anyone has left the origin.
    fn has_route_progress(&self) -> bool {
        self.route_progress() > 0.0 || self.leg_index() > 0
    }

    fn stamp(&mut self, color_index: i64, left: bool, now: i64) {
        self.0.insert("colorIndex".into(), color_index.into());
        self.0.insert("left".into(), left.into());
        self.0.insert("updatedAt".into(), now.into());
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Outcome {
    pub kind: String,
    pub by_user_code: String,
    pub at: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub placements: Option<Vec<String>>,
}

/// What a pilot claims with a put. An unknown kind is no claim at all.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Claim {
    #[serde(default)]
    pub kind: Option<String>,
    #[serde(default)]
    pub broken_by: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Room {
    pub code: String,
    pub definition: Map<String, Value>,
    pub created_at: i64,
    pub participants: Vec<Snapshot>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub outcome: Option<Outcome>,
    pub version: i64,
}

/// Why a put was refused. Only a join can be.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum JoinRefusal {
    RoomClosed,
    RaceLocked,
    RoomFull,
}

impl Room {
    /// A fresh room with the creator as the only participant, in colour 0.
    pub fn create(code: String, definition: Map<String, Value>, mut creator: Snapshot, now: i64) -> Room {
        creator.stamp(0, false, now);
        Room { code, definition, created_at: now, participants: vec![creator], outcome: None, version: 1 }
    }

    fn is_route(&self) -> bool {
        self.definition.get("type").and_then(Value::as_str) == Some("ROUTE")
    }

    fn position(&self, user_code: &str) -> Option<usize> {
        self.participants.iter().position(|p| p.user_code() == Some(user_code))
    }

    /// The lowest colour nobody in the room holds, a pilot who left included.
    fn next_free_color(&self) -> i64 {
        (0..).find(|c| self.participants.iter().all(|p| p.color_index() != *c)).unwrap()
    }

    /// The upsert of `PUT /rooms/{code}/participants/{userCode}`. The caller has already checked
    /// that the snapshot is the pilot's own. A stranger's first put is the join and the only one
    /// that can be refused; a claim is kept only while the room has no outcome.
    pub fn put(&mut self, mut snapshot: Snapshot, claim: Option<&Claim>, now: i64) -> Result<(), JoinRefusal> {
        let user_code = snapshot.user_code().unwrap_or_default().to_owned();
        let existing = self.position(&user_code);

        let color = match existing {
            Some(index) => self.participants[index].color_index(),
            None => {
                if self.outcome.is_some() {
                    return Err(JoinRefusal::RoomClosed);
                }
                if self.is_route() && self.participants.iter().any(Snapshot::has_route_progress) {
                    return Err(JoinRefusal::RaceLocked);
                }
                if self.participants.len() >= MAX_PARTICIPANTS {
                    return Err(JoinRefusal::RoomFull);
                }
                self.next_free_color()
            }
        };

        snapshot.stamp(color, false, now);
        match existing {
            Some(index) => self.participants[index] = snapshot,
            None => self.participants.push(snapshot),
        }

        if self.outcome.is_none() {
            self.outcome = claim.and_then(|claim| self.resolve_claim(&user_code, claim, now));
        }
        self.version += 1;
        Ok(())
    }

    /// Marks the pilot's snapshot left and keeps it, so pooled contributions stay. False when
    /// the pilot was never in the room, which changes nothing and is not an error.
    pub fn leave(&mut self, user_code: &str, now: i64) -> bool {
        let Some(index) = self.position(user_code) else { return false };
        let snapshot = &mut self.participants[index];
        let color = snapshot.color_index();
        snapshot.stamp(color, true, now);
        self.version += 1;
        true
    }

    /// Route placements: the claimer first, then the rest by progress, leavers unranked. Ties
    /// keep join order (the sort is stable); the app ranks level pilots equal anyway.
    fn resolve_claim(&self, claimer: &str, claim: &Claim, now: i64) -> Option<Outcome> {
        match claim.kind.as_deref()? {
            KIND_COMPLETED => {
                let placements = if self.is_route() {
                    let mut rest: Vec<&Snapshot> =
                        self.participants.iter().filter(|p| !p.left() && p.user_code() != Some(claimer)).collect();
                    rest.sort_by(|a, b| b.route_progress().total_cmp(&a.route_progress()));
                    std::iter::once(claimer.to_owned())
                        .chain(rest.iter().filter_map(|p| p.user_code().map(str::to_owned)))
                        .collect()
                } else {
                    vec![claimer.to_owned()]
                };
                Some(Outcome {
                    kind: KIND_COMPLETED.into(),
                    by_user_code: claimer.into(),
                    at: now,
                    placements: Some(placements),
                })
            }
            KIND_FAILED => Some(Outcome {
                kind: KIND_FAILED.into(),
                by_user_code: claim.broken_by.clone().unwrap_or_default(),
                at: now,
                placements: None,
            }),
            _ => None,
        }
    }

    /// When the room was last written, for retention: the latest participant stamp, or its
    /// creation.
    pub fn last_write(&self) -> i64 {
        self.participants
            .iter()
            .filter_map(|p| p.0.get("updatedAt").and_then(Value::as_i64))
            .max()
            .unwrap_or(self.created_at)
            .max(self.created_at)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn snapshot(value: Value) -> Snapshot {
        Snapshot(value.as_object().unwrap().clone())
    }

    fn race() -> Room {
        let definition = json!({"type": "ROUTE"}).as_object().unwrap().clone();
        Room::create("ROOM01".into(), definition, snapshot(json!({"userCode": "SELF01"})), 1)
    }

    #[test]
    fn unknown_fields_survive_a_put() {
        let mut room = race();
        room.put(snapshot(json!({"userCode": "ANNA02", "futureField": [1, 2]})), None, 2).unwrap();
        assert_eq!(room.participants[1].0["futureField"], json!([1, 2]));
    }

    #[test]
    fn an_unknown_claim_kind_is_no_claim() {
        let mut room = race();
        let claim = Claim { kind: Some("teleported".into()), broken_by: None };
        room.put(snapshot(json!({"userCode": "SELF01"})), Some(&claim), 2).unwrap();
        assert!(room.outcome.is_none());
    }

    #[test]
    fn colours_fill_the_lowest_gap() {
        let mut room = race();
        room.put(snapshot(json!({"userCode": "ANNA02"})), None, 2).unwrap();
        room.participants[0].0.insert("colorIndex".into(), 3.into());
        room.put(snapshot(json!({"userCode": "BOB003"})), None, 3).unwrap();
        assert_eq!(room.participants[2].color_index(), 0);
    }
}
