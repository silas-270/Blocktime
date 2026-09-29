//! Where rooms and pilot secrets live: Postgres in production, a map in memory for tests and
//! for running the server locally without a database.
//!
//! Every change to a room is a read-modify-write under a lock on that room (`SELECT … FOR
//! UPDATE` in Postgres), so two pilots writing to one room at once cannot lose either write, and
//! the first claim really is the first.

use std::collections::HashMap;
use std::sync::Mutex;

use sqlx::PgPool;
use sqlx::types::Json;

use crate::room::Room;

/// A room is deleted this long after its outcome...
pub const RETAIN_AFTER_OUTCOME_MS: i64 = 30 * DAY_MS;
/// ...or this long after its last write, whichever comes first.
pub const RETAIN_AFTER_WRITE_MS: i64 = 180 * DAY_MS;
const DAY_MS: i64 = 24 * 60 * 60 * 1000;

pub enum Store {
    Memory(Mutex<Memory>),
    Postgres(PgPool),
}

#[derive(Default)]
pub struct Memory {
    rooms: HashMap<String, Room>,
    pilots: HashMap<String, Vec<u8>>,
}

impl Store {
    pub fn memory() -> Store {
        Store::Memory(Mutex::new(Memory::default()))
    }

    pub async fn postgres(url: &str) -> anyhow::Result<Store> {
        let pool = sqlx::postgres::PgPoolOptions::new().max_connections(8).connect(url).await?;
        sqlx::migrate!("./migrations").run(&pool).await?;
        Ok(Store::Postgres(pool))
    }

    /// Stores a new room. False when its code is already taken, so the caller draws another.
    pub async fn insert(&self, room: &Room) -> Result<bool, sqlx::Error> {
        match self {
            Store::Memory(memory) => {
                let mut memory = memory.lock().unwrap();
                if memory.rooms.contains_key(&room.code) {
                    return Ok(false);
                }
                memory.rooms.insert(room.code.clone(), room.clone());
                Ok(true)
            }
            Store::Postgres(pool) => {
                let inserted = sqlx::query(
                    "INSERT INTO rooms (code, data, last_write_ms, outcome_ms) VALUES ($1, $2, $3, NULL) \
                     ON CONFLICT (code) DO NOTHING",
                )
                .bind(&room.code)
                .bind(Json(room))
                .bind(room.last_write())
                .execute(pool)
                .await?
                .rows_affected();
                Ok(inserted == 1)
            }
        }
    }

    pub async fn get(&self, code: &str) -> Result<Option<Room>, sqlx::Error> {
        match self {
            Store::Memory(memory) => Ok(memory.lock().unwrap().rooms.get(code).cloned()),
            Store::Postgres(pool) => {
                let row: Option<(Json<Room>,)> =
                    sqlx::query_as("SELECT data FROM rooms WHERE code = $1").bind(code).fetch_optional(pool).await?;
                Ok(row.map(|(Json(room),)| room))
            }
        }
    }

    /// Applies [change] to the room under its lock and stores the result when it answers
    /// `Ok((true, _))`. `Ok(None)` when there is no such room; an `Err` from [change] leaves the
    /// room as it was.
    pub async fn update<T, E>(
        &self,
        code: &str,
        change: impl FnOnce(&mut Room) -> Result<(bool, T), E>,
    ) -> Result<Option<(Room, T)>, UpdateError<E>> {
        match self {
            Store::Memory(memory) => {
                let mut memory = memory.lock().unwrap();
                let Some(stored) = memory.rooms.get_mut(code) else { return Ok(None) };
                let mut room = stored.clone();
                let (changed, value) = change(&mut room).map_err(UpdateError::Refused)?;
                if changed {
                    *stored = room.clone();
                }
                Ok(Some((room, value)))
            }
            Store::Postgres(pool) => {
                let mut tx = pool.begin().await?;
                let row: Option<(Json<Room>,)> = sqlx::query_as("SELECT data FROM rooms WHERE code = $1 FOR UPDATE")
                    .bind(code)
                    .fetch_optional(&mut *tx)
                    .await?;
                let Some((Json(mut room),)) = row else { return Ok(None) };
                let (changed, value) = change(&mut room).map_err(UpdateError::Refused)?;
                if changed {
                    sqlx::query("UPDATE rooms SET data = $2, last_write_ms = $3, outcome_ms = $4 WHERE code = $1")
                        .bind(code)
                        .bind(Json(&room))
                        .bind(room.last_write())
                        .bind(room.outcome.as_ref().map(|o| o.at))
                        .execute(&mut *tx)
                        .await?;
                    tx.commit().await?;
                }
                Ok(Some((room, value)))
            }
        }
    }

    /// Binds [code] to [secret_hash] if it has no secret yet, and returns the hash that stands.
    pub async fn bind_pilot(&self, code: &str, secret_hash: &[u8]) -> Result<Vec<u8>, sqlx::Error> {
        match self {
            Store::Memory(memory) => {
                Ok(memory.lock().unwrap().pilots.entry(code.to_owned()).or_insert_with(|| secret_hash.to_vec()).clone())
            }
            Store::Postgres(pool) => {
                // The no-op update makes RETURNING answer for an existing row too.
                let (hash,): (Vec<u8>,) = sqlx::query_as(
                    "INSERT INTO pilots (code, secret_hash) VALUES ($1, $2) \
                     ON CONFLICT (code) DO UPDATE SET code = EXCLUDED.code RETURNING secret_hash",
                )
                .bind(code)
                .bind(secret_hash)
                .fetch_one(pool)
                .await?;
                Ok(hash)
            }
        }
    }

    /// Deletes the rooms past retention and answers how many went.
    pub async fn sweep(&self, now: i64) -> Result<u64, sqlx::Error> {
        let outcome_cutoff = now - RETAIN_AFTER_OUTCOME_MS;
        let write_cutoff = now - RETAIN_AFTER_WRITE_MS;
        match self {
            Store::Memory(memory) => {
                let mut memory = memory.lock().unwrap();
                let before = memory.rooms.len();
                memory.rooms.retain(|_, room| {
                    room.outcome.as_ref().is_none_or(|o| o.at >= outcome_cutoff) && room.last_write() >= write_cutoff
                });
                Ok((before - memory.rooms.len()) as u64)
            }
            Store::Postgres(pool) => Ok(sqlx::query("DELETE FROM rooms WHERE outcome_ms < $1 OR last_write_ms < $2")
                .bind(outcome_cutoff)
                .bind(write_cutoff)
                .execute(pool)
                .await?
                .rows_affected()),
        }
    }

    pub async fn healthy(&self) -> bool {
        match self {
            Store::Memory(_) => true,
            Store::Postgres(pool) => sqlx::query("SELECT 1").execute(pool).await.is_ok(),
        }
    }
}

pub enum UpdateError<E> {
    Refused(E),
    Database(sqlx::Error),
}

impl<E> From<sqlx::Error> for UpdateError<E> {
    fn from(error: sqlx::Error) -> Self {
        UpdateError::Database(error)
    }
}
