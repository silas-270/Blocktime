use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use blocktime_backend::store::Store;
use blocktime_backend::{AppState, router};
use tower_http::trace::TraceLayer;
use tracing_subscriber::EnvFilter;

/// How often expired rooms and spent rate-limit windows are cleared out.
const SWEEP_EVERY: Duration = Duration::from_secs(60 * 60);

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| "info,tower_http=info".into()))
        .init();

    let store = match std::env::var("DATABASE_URL") {
        Ok(url) if !url.is_empty() => Store::postgres(&url).await?,
        _ => {
            tracing::warn!("DATABASE_URL is not set: rooms live in memory and die with the process");
            Store::memory()
        }
    };
    let mut state = AppState::new(store);
    state.client_ip_header = std::env::var("CLIENT_IP_HEADER").ok().filter(|h| !h.is_empty());
    let state = Arc::new(state);

    let sweeper = state.clone();
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(SWEEP_EVERY);
        loop {
            interval.tick().await;
            let now = (sweeper.clock)();
            match sweeper.store.sweep(now).await {
                Ok(0) => {}
                Ok(deleted) => tracing::info!(deleted, "swept expired rooms"),
                Err(error) => tracing::error!(%error, "sweep failed"),
            }
            sweeper.limiter.prune(now);
        }
    });

    let port: u16 = std::env::var("PORT").ok().and_then(|p| p.parse().ok()).unwrap_or(8080);
    let listener = tokio::net::TcpListener::bind(SocketAddr::from(([0, 0, 0, 0], port))).await?;
    tracing::info!(port, "listening");
    let app = router(state).layer(TraceLayer::new_for_http());
    axum::serve(listener, app.into_make_service_with_connect_info::<SocketAddr>())
        .with_graceful_shutdown(shutdown())
        .await?;
    Ok(())
}

/// Ctrl-C locally, SIGTERM from the platform on a redeploy.
async fn shutdown() {
    let ctrl_c = async { tokio::signal::ctrl_c().await.ok() };
    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()).unwrap().recv().await;
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}
