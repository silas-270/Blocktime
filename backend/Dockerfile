# Two stages: build a static-ish release binary, then run it on a slim image. The dependency
# layer is cached separately so a code change does not rebuild every crate.
FROM rust:1-slim-bookworm AS build
WORKDIR /app
COPY Cargo.toml Cargo.lock ./
RUN mkdir src && echo 'fn main() {}' > src/main.rs && touch src/lib.rs \
    && cargo build --release && rm -rf src
COPY src src
COPY migrations migrations
RUN touch src/main.rs src/lib.rs && cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/* && useradd --system --uid 10001 app
COPY --from=build /app/target/release/blocktime-backend /usr/local/bin/blocktime-backend
USER app
ENV PORT=8080
EXPOSE 8080
CMD ["blocktime-backend"]
