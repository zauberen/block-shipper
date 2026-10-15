# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

Block Shipper (also called Block Manager) is a Clojure web application for managing Minecraft servers. Admins and moderators get a dashboard to create servers, start/stop them, watch the console, manage whitelists/bans/properties, see players and uptime, and configure backups and auto-updates. A public dashboard (no login) lists published servers. Servers run as child processes of Block Shipper, behind a built-in reverse proxy that can start sleeping servers when a player joins.

Every source file (Clojure, Java, our JS/CSS; not `resources/public/js/vendor/`) starts with the AGPLv3 license header; add it to new files.

## Commands

```bash
# Run the application
clj -M:dev -m com.main

# Run all tests (kaocha, configured in tests.edn)
clj -M:test

# Run a single test namespace
clj -M:test --focus com.blockshipper.web-test

# Check for outdated dependencies
clj -M:chk
```

The `:dev` alias adds `tests/` to the classpath and configures slf4j logging. Tests need a JDK (not just a JRE): `tests/fixtures/FakeServer.java` is compiled at test time into a stand-in `server.jar` that prints vanilla-style log lines, echoes traffic on its port and obeys console commands.

## Architecture

**Entry point:** `src/com/main.clj` calls `com.blockshipper/start-server`, which creates directories, migrates the DB, closes uptime/player rows left open by a previous run, installs hooks, opens proxy listeners, starts the scheduler and Jetty, and registers a JVM shutdown hook that stops all servers gracefully.

**Onboarding** (`pages/onboarding.clj`): when there is no `~/.blockshipper` or the database has no users, `start-server` only starts Jetty and sets `setup-required`; every request then goes to `setup-routes` (`/setup`, `POST /api/setup`, everything else redirects to `/setup`; static files still load). Setup validates the form, pins the chosen restart-only settings so the chosen database is used at once, creates the folders, writes the settings file, creates the admin, stores start method/public host in `global_settings`, runs the deferred startup and logs the admin in. A missing settings file next to an existing default database (installs from before onboarding) is treated as configured: the defaults are written out.

**Routing** (`src/com/blockshipper.clj`): Compojure routes with Ring middleware (keyword-params, params, static resources from `resources/public/`, cookies) plus `middleware.clj`: non-GET requests and websocket upgrades whose `Origin` doesn't match `Host`/`X-Forwarded-Host` get a 403, bodies over 1 MB (by Content-Length) get a 413 (upload pieces may be up to the piece size), and security headers are added. Nothing parses multipart: uploads go through `uploads.clj` (below). Access checks live in `com.blockshipper.auth`: `requires-user`, `requires-admin`, `requires-server` (admin or assigned moderator) and `requires-server-admin`. Handlers receive `(req user)` or `(req user server)`.

Routes: `/` public dashboard · `/login` `/logout` · `/admin` admin dashboard · `/admin/servers/new` · `/admin/servers/:slug/:tab` · `/admin/settings/:tab` · `/api/...` htmx endpoints · `/api/uploads` chunked uploads · `/ws/servers/:slug/console` websocket.

**Uploads** (`uploads.clj`): the world archive on the create form is sent by `main.js` (`bsUploadWorld`) in pieces of `:upload-chunk-mb` (default 1 MB, so reverse proxies with nginx's default 1 MB limit pass them): `POST /api/uploads` (filename, size) returns `{id, chunkSize}` (fixed per upload), `PUT /api/uploads/:id?offset=N` appends a raw piece (409 with the received count when the offset is off, so retries resume), and the form posts only `world_upload=<id>`, which `api-create` claims with `uploads/take!`. Uploads belong to the admin who started them and are swept after an idle hour; one survives a form with validation errors so it isn't sent again.

**Session management** (`api/session.clj`): manual sessions, not Ring's. On login a UUID sid is stored in the DB (or Valkey/Redis) and sent as the `bs-session-id` cookie (HttpOnly, SameSite=Strict, `Secure` over https); sessions expire after `db/session-ttl-seconds` (30 days). The session only identifies the user; role and server assignments are re-read from the DB on each request (`auth/current-user`). Unauthenticated htmx requests get an `HX-Redirect` to `/login`.

**Domain namespaces** (thin SQL + pure helpers):
- `servers` — `mc_servers` records; `name` is the immutable slug (directory name), `display_name` is shown in the UI. Port allocation, validation, change hooks (`notify-changed!`, used by the proxy).
- `users`, `auth`, `global-settings`, `players` (player sessions → last login / playtime; logins refused by the whitelist, recorded from the server log and the proxy and listed on the whitelist tab), `uptime`, `stats` (players-online samples), `player-lists` (whitelist/ban entries; `server_id` NULL = global list).

**Minecraft namespaces** (`src/com/blockshipper/minecraft/`):
- `instances` — process manager. `start!`/`stop!`/`send-command!`, a ring buffer of console lines, listeners (log/state events) and exit hooks. Enforces the max-running limit by stopping an idle server. Writes a pid file so orphans from a killed Block Shipper are cleaned up.
- `log-parser` — regexes for `Done (`, joins, leaves, UUID lines.
- `provider` — multimethods dispatching on `server_type` (install, launch command, versions, auto-update). Only `:vanilla` exists; this is the seam for Fabric/Modrinth.
- `setup` — eula, managed `server.properties` keys (`server-port`, `server-ip=127.0.0.1`, `white-list`, `enforce-whitelist`), background installs.
- `properties` — comment/order preserving `server.properties` parser/writer.
- `player-sync` — renders `whitelist.json`/`banned-players.json` for offline servers, sends `whitelist`/`ban`/`pardon` commands to running ones, imports in-game edits on stop. Whitelist-mode servers inherit the global whitelist; ban-list-mode servers inherit the global ban list.
- `protocol` + `mc-proxy` — one listener per non-disabled server on `exposed_port`. Ready servers get a byte pipe to `127.0.0.1:mc_port`; otherwise the proxy answers status pings ("sleeping") and, in the kick/hold start methods, starts the server on a login attempt. Kick disconnects the player with a reconnect message; hold keeps the login open until the server is ready (up to `hold-timeout-ms`) and then forwards it, replaying the handshake and login packets.
- `mc-update` — Mojang v2 manifest, sha1-verified jar cache in `~/minecraft/jars/vanilla`. `updater` — separate single-thread executor (every 6h) that updates offline servers and marks running ones `pending_update`.
- `discovery` — finds directories under `:hosting-root` with no `mc_servers` record (Settings → Minecraft → Import) and registers them, importing their whitelist/bans before the first sync. Folders whose name is not a valid slug get a suggested (editable) id and are renamed on import; the original folder name becomes the display name.
- `world-import` — optional world archive on the create form (chunked upload, see Uploads): detects zip/jar, tar, tar.gz or 7z by magic bytes (Commons Compress + xz), extracts to a temp dir with path-traversal/link/size guards, takes the shallowest directory with `level.dat` and moves it to `<server>/world` (`level-name=world`).
- `player-import` — Players tab → Import from world: reads `<world>/playerdata` and `stats` (playtime), names from `usercache.json`/player lists/the Mojang API, and adds `imported` player sessions (a closed session of the world's playtime ending when the player was last seen). Known players only get playtime Block Shipper didn't record.
- `backups` — zips the server dir (minus `server.jar`) to `:backup-root/<slug>/`, pausing saves on running servers; weekly/monthly schedule logic.

**Scheduler** (`scheduler.clj`): one tick per minute — record player samples, stop published servers idle for 15 minutes, keep always-active servers running (unless an admin stopped them), stop-update-restart servers with a pending update once empty, run due scheduled backups. Its exit hook does on-stop backups and applies pending updates.

**Storage** (`database/`): `db.clj` dispatches session storage to SQLite or Valkey/Redis (`:nosql-type`); `sqlite.clj` (`db-spec` is a function); `migration.clj` runs idempotent migrations whenever `server-version` changes. It creates no users; the first admin comes from onboarding.

**Settings** (`settings.clj`): `~/.blockshipper` (`config-path`; EDN map, read with `clojure.edn` — write symbols unquoted, e.g. `{:nosql-type valkey}`) merged over defaults. The file is re-read on each `get-settings`, except `restart-keys` (port, DB path, logs root, proxy bind address, session store), which are pinned at startup by `pin-restart-settings!`; `pending-restart` lists saved values not yet in effect. `save-settings!` merges into the file. `pages/server_config.clj` renders `config-keys` as a form, shared by onboarding and Settings → Server configuration (`POST /api/global/config`). Keys: `:hosting-root`, `:backup-root`, `:logs-root`, `:sqlite-db-path`, `:nosql-type`, `:listen-port` (8081), `:mc-mem-min`/`:mc-mem-max`, `:java-cmd`, `:proxy-bind-address` (0.0.0.0), `:mc-manifest-url` (override for testing), `:debug` (default false; enables the admin-only `/api/echo`), `:upload-chunk-mb` (1, max 256; size of world upload pieces, applies without a restart). Settings editable from the UI (max running servers, start method, public host) live in the `global_settings` table.

**UI rendering**: server-side Hiccup + HTMX. `pages/layout.clj` is the app shell; pages return a `(layout/main ...)` element and `layout/app-page` wraps it (htmx navigation requests get only `#main`). In-app links use `ui/nav-attrs` so only `#main` is swapped and the URL is pushed, keeping the sidebar. Server tabs register themselves with `page/register-tab!`. Polling containers that use `hx-select` must set `hx-disinherit "*"` so child buttons don't inherit it. htmx, its ws extension and Chart.js are vendored in `resources/public/js/vendor/`; `resources/public/js/main.js` initialises charts (`canvas[data-chart]`), copy buttons and console scrolling. Response helpers in `response.clj`: `h-respond` for Hiccup, `respond` for HTML strings, `respond-generic` for other content types.

**Passwords**: Argon2id via `buddy-hashers`, Base64-encoded before storage.

## DB Schema

`db_version`, `users` (role `admin`|`moderator`), `user_servers` (moderator assignments), `mc_servers` (slug, display name, type, version, ports, host mode, list mode, auto-update, backup schedule), `player_sessions` (`imported` rows come from world files and are not logins), `rejected_logins`, `uptime_log`, `player_lists`, `global_settings`, `server_samples`, `sessions`, and the legacy `server_stats`. New tables store times as epoch milliseconds.

## Default Configuration

- Web UI: `8081`; first server's public port `25565`, internal ports from `25600`
- DB: SQLite at `~/minecraft/settings.db`
- Servers: `~/minecraft/hosted/<slug>`; backups: `~/minecraft/backups/<slug>`
- Session store: SQLite (`:nosql-type yes-sql`)
- Start method: kick (proxy; the others are hold and manual); max running servers: unlimited
- No default login: the first admin is created on the `/setup` page. In tests, `tu/with-temp-env` creates `admin`/`admin` and points `config-path` into the temp dir.
