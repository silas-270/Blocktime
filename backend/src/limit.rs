//! Look-ups by code are rate-limited against guessing. Only misses count, so a pilot syncing
//! their own rooms never runs into it; once a client has missed too often, every room request
//! from it is refused for the rest of the window, hits included, or a hit would still tell a
//! guesser that a code exists.

use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Mutex;

/// Misses allowed per client and window. Generous, because carrier-grade NAT puts many phones
/// behind one address, and a pilot typing a code wrong a few times must never notice.
pub const MAX_MISSES: u32 = 60;
pub const WINDOW_MS: i64 = 10 * 60 * 1000;

#[derive(Default)]
pub struct MissLimiter {
    windows: Mutex<HashMap<IpAddr, (i64, u32)>>,
}

impl MissLimiter {
    /// False when [client] has used up its misses in the current window.
    pub fn allows(&self, client: IpAddr, now: i64) -> bool {
        match self.windows.lock().unwrap().get(&client) {
            Some((start, misses)) if now - start < WINDOW_MS => *misses < MAX_MISSES,
            _ => true,
        }
    }

    pub fn record_miss(&self, client: IpAddr, now: i64) {
        let mut windows = self.windows.lock().unwrap();
        let entry = windows.entry(client).or_insert((now, 0));
        if now - entry.0 >= WINDOW_MS {
            *entry = (now, 0);
        }
        entry.1 += 1;
    }

    /// Drops the windows that have run out, so the map does not grow with every address seen.
    pub fn prune(&self, now: i64) {
        self.windows.lock().unwrap().retain(|_, (start, _)| now - *start < WINDOW_MS);
    }
}
