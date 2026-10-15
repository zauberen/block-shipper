<div align="center">

<img src="resources/public/img/icon.svg" alt="Block Shipper logo" width="96" height="96">

# Block Shipper

**Self-hosted Minecraft servers that sleep when nobody's playing and wake up when someone joins.**

One small web app to create, run, watch and back up all your Minecraft servers from the browser.

[![License: AGPL v3](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)
![Java 21+](https://img.shields.io/badge/java-21%2B-orange.svg)
![Clojure](https://img.shields.io/badge/built%20with-Clojure-5881d8.svg)
![Minecraft: Java Edition](https://img.shields.io/badge/minecraft-java%20edition-62b47a.svg)

[Features](#features) · [Install](#installation) · [Usage](#usage) · [Configuration](#configuration) · [How it works](#how-the-proxy-works) · [Security](#security) · [Development](#development)

</div>

---

## Why Block Shipper?

Running a handful of Minecraft servers for friends usually means a pile of terminal sessions,
servers burning RAM while nobody plays, and hand-edited `whitelist.json` files. Block Shipper
(a.k.a. Block Manager) replaces all of that with a dashboard:

- 💤 **Servers sleep when empty and wake on join.** A built-in proxy answers the server list
  ping for sleeping servers and starts them when a player connects.
- 🖥️ **Everything in the browser.** Live console, player lists, `server.properties`, version
  upgrades and backups. No SSH needed.
- 🔒 **Admins and moderators.** Give a friend the keys to one server without handing over the
  whole machine.
- 🌍 **A public status page.** Players get a list of your servers with copyable addresses and
  live status, no login required.

## Features

### 🚀 Server management
- **Create vanilla servers in a few clicks.** Pick a version from Mojang's manifest, set memory
  and a port, accept the EULA, done. Jars are downloaded once, verified against their SHA-1 and cached.
- **Bring your own world.** Upload a `zip`, `jar`, `tar`, `tar.gz` or `7z` archive while creating
  a server; Block Shipper finds the folder with `level.dat` and uses it.
- **Import existing servers.** Settings → Minecraft → Import finds server folders already in
  your hosting directory and registers them, keeping their whitelist and bans.
- **Hosting modes** for every server:
  - *Published*: starts on demand, stops after 15 minutes without players.
  - *Always active*: kept running around the clock (unless an admin stops it).
  - *Disabled*: never started.
- **A cap on running servers.** Set a maximum and Block Shipper stops the server that has been
  idle longest to make room for a new one.

### 🎮 Day-to-day operations
- **Dashboard tab** with a players-online chart, the latest log lines and recent logins.
- **Live console** over a websocket: see output as it happens and run commands.
- **Whitelist and ban list editors** that work whether the server is running or not (commands
  when online, JSON files when offline). In-game edits are imported when the server stops.
- **Players tab** with last login, total playtime and one-click op/de-op, kick and ban.
- **Uptime log** recording who started each run, when, and why it stopped.
- **`server.properties` editor** with friendly controls for the common settings and raw access
  to the rest. Comments and key order are preserved.

### 🔄 Updates and backups
- **Automatic vanilla updates.** Every 6 hours a background job checks for a new release.
  Offline servers update right away; running ones update as soon as the last player leaves.
- **Backups** of the whole server folder (minus the jar) before updates, when a server stops,
  weekly, monthly, or at the press of a button. Saving is paused while a running server is
  copied. Download any backup from the server's Settings tab.

### 🌐 Players and the public page
- **One address per server.** Each server gets its own public port (from `25565`); Minecraft
  itself only listens on `127.0.0.1`.
- **Start methods:**
  - *Kick*: joining a sleeping server starts it. The player is asked to reconnect in about 30 seconds.
  - *Hold*: joining a sleeping server starts it and keeps the player on the connecting screen
    until it is up, then lets them in (like lazymc's hold mode). If it takes longer than about
    25 seconds they are asked to reconnect.
  - *Manual*: a **Start server** button on the public page instead.
- **Global whitelist and ban list.** Ban-list servers inherit the global ban list and whitelist
  servers inherit the global whitelist, so you manage players once. Banned and non-whitelisted
  players can't wake a server.

### 👥 Accounts
- **Admins** manage everything: servers, users and global settings.
- **Moderators** see and moderate only the servers assigned to them (console, lists,
  properties, players).
- Passwords are hashed with Argon2id. Roles and assignments are re-checked on every request,
  so changes take effect immediately.

## Installation

### Requirements

| | |
|---|---|
| **Java 21+** | Block Shipper runs on it, and it launches the Minecraft servers with `java`. Any recent JDK works ([Adoptium Temurin](https://adoptium.net/) is a good choice). |
| **Clojure CLI** | Fetches dependencies and runs the app. See the [official install guide](https://clojure.org/guides/install_clojure). |
| **RAM** | Enough for Block Shipper itself plus whatever you give each running Minecraft server (3 GB max per server by default). |
| **Optional** | Valkey or Redis, if you'd rather keep login sessions out of SQLite. |

<details>
<summary><b>Installing the requirements on Debian/Ubuntu</b></summary>

```sh
sudo apt install openjdk-21-jdk curl rlwrap
curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
chmod +x linux-install.sh
sudo ./linux-install.sh
```
</details>

<details>
<summary><b>Installing the requirements on macOS</b></summary>

```sh
brew install openjdk@21 clojure/tools/clojure
```
</details>

### Get it running

```sh
git clone https://github.com/zauberen/block-shipper.git
cd block-shipper
clj -M:dev -m com.main
```

The first start downloads the dependencies, which takes a minute. Then open
**http://localhost:8081**.

### First-run setup

On the first start there is no configuration and no account, so every page leads to the
**setup page**. There you:

1. Create the first **admin account**. There is no default login.
2. Choose the **start method** (kick, hold or manual) and, optionally, the **public address** players
   should use (e.g. `play.example.com`).
3. Check the folders, ports and memory defaults.

Block Shipper writes the settings to `~/.blockshipper`, creates its folders and logs you in.

> **Tip:** finish setup right after the first start. Until an admin account exists, anyone who
> can reach the web port can complete setup.

### Running it as a service

To keep Block Shipper running in the background and start it at boot, use a systemd unit such
as `/etc/systemd/system/block-shipper.service`:

```ini
[Unit]
Description=Block Shipper
After=network-online.target

[Service]
User=minecraft
WorkingDirectory=/opt/block-shipper
ExecStart=/usr/local/bin/clojure -M:dev -m com.main
Restart=on-failure
# Give running Minecraft servers time to save and stop
TimeoutStopSec=120

[Install]
WantedBy=multi-user.target
```

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now block-shipper
```

Minecraft servers are child processes of Block Shipper. Stopping the service asks every running
server to save and stop.

### Opening it to the internet

- **Players** connect to the public ports (`25565`, `25566`, …). Forward those ports on your
  router or firewall.
- **The web UI** listens on plain HTTP. If you expose it beyond your LAN, put it behind a
  reverse proxy with TLS. The console needs websocket support. For example, with nginx:

```nginx
server {
    listen 443 ssl;
    server_name mc.example.com;
    # ssl_certificate ... ;

    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    # World archives on the create server form. nginx refuses bodies over 1 MB by default
    # (413 Request Entity Too Large); raise the limit for this route only.
    location = /api/servers {
        client_max_body_size 4g;
        proxy_request_buffering off;
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

When `X-Forwarded-Proto` is `https`, the session cookie is marked `Secure`.

#### Public dashboard only

To publish just the public dashboard and keep the management interface off the internet, let
nginx pass only the routes the public page uses and refuse everything else:

```nginx
server {
    listen 443 ssl;
    server_name mc.example.com;
    # ssl_certificate ... ;

    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;

    # The public dashboard (htmx polls it every 15 seconds).
    location = / {
        limit_except GET { deny all; }
        proxy_pass http://127.0.0.1:8081;
    }

    # Stylesheets, scripts and the icon it loads.
    location ~ ^/(css|js|img)/ {
        limit_except GET { deny all; }
        proxy_pass http://127.0.0.1:8081;
    }

    # The Start button (manual start method only; drop this block otherwise).
    location ~ ^/public/servers/[a-z0-9_-]+/start$ {
        limit_except POST { deny all; }
        proxy_pass http://127.0.0.1:8081;
    }

    # Login, /admin, /api and the console websocket are not reachable from here.
    location / {
        return 404;
    }
}
```

The dashboard's **Login** button then shows nginx's 404 page to visitors. Admins and
moderators sign in by going to Block Shipper directly at `http://<server-ip>:8081`. Who can do
that is up to your firewall:

- **Localhost only:** block port `8081` from every other host
  (for example `sudo ufw deny 8081/tcp`), then reach it through an SSH tunnel:
  `ssh -L 8081:127.0.0.1:8081 you@server` and open `http://localhost:8081`.
- **LAN:** allow port `8081` only from your local network, e.g.
  `sudo ufw allow from 192.168.1.0/24 to any port 8081 proto tcp`, and never forward it on
  your router.

Either way, only ports `443` (nginx) and the Minecraft ports should be open to the internet.
Direct access is plain HTTP, so the session cookie isn't marked `Secure` there; keep it to
networks you trust.

## Usage

| Where | What you can do |
|---|---|
| **`/`** (public) | See published servers, their status and address. In manual mode, start them. |
| **Admin → Create Server** | Make a new server, optionally from an uploaded world. |
| **Admin → a server** | Dashboard, Console, Whitelist, Ban list, Properties, Players, Uptime and (admins) Settings. |
| **Settings → User management** | Add admins and moderators, assign servers, reset passwords. |
| **Settings → Minecraft** | Max running servers, start method, public address, global lists, import existing servers. |
| **Settings → Server configuration** | Folders, ports, memory defaults, Java command, session store. |
| **Settings → My account** | Change your password. |

A typical first session:

1. **Create a server.** Give it a name; the id (used for its folder and URLs) is filled in for you.
2. **Wait for the install.** The jar downloads in the background and the server page shows progress.
3. **Join it.** Point Minecraft at `your-host:25565`. In auto mode the first join wakes the
   server; reconnect after about 30 seconds and you're in.
4. **Invite a moderator.** Add a moderator under Settings → User management and tick the servers
   they may manage.

## Configuration

Settings live in **`~/.blockshipper`** as an EDN map. Setup writes the file; afterwards change it
under **Settings → Server configuration**, or edit it by hand while Block Shipper is stopped.
Write symbols without a quote:

```clojure
{:listen-port        8081                  ; web UI port
 :hosting-root       "~/minecraft/hosted"  ; one folder per server
 :backup-root        "~/minecraft/backups"
 :logs-root          "~/minecraft/logs"
 :sqlite-db-path     "~/minecraft/settings.db"
 :mc-mem-min         "512M"                ; defaults for new servers
 :mc-mem-max         "3G"
 :java-cmd           "java"                ; e.g. a full path to a specific JDK
 :proxy-bind-address "0.0.0.0"             ; where the player-facing ports listen
 :nosql-type         yes-sql               ; sessions in SQLite, or valkey / redis
 :debug              false}
```

The web UI port, database, logs folder, proxy bind address and session store apply after a
restart; the settings page shows when a restart is pending. Max running servers, the start
method and the public address are stored in the database and set under **Settings → Minecraft**.

| Default | Value |
|---|---|
| Web UI | `http://localhost:8081` |
| Player ports | `25565` and up (internal ports from `25600`) |
| Database | SQLite at `~/minecraft/settings.db` |
| Servers / backups | `~/minecraft/hosted/<id>` / `~/minecraft/backups/<id>` |
| Start method | auto, unlimited running servers |

## How the proxy works

```
 Player ──► your-host:25565 ──► Block Shipper proxy ──► 127.0.0.1:25600 (Minecraft)
                                       │
                                       └── server asleep? answer the ping with "sleeping",
                                           start it on a join attempt (auto mode)
```

Each Minecraft server listens only on `127.0.0.1` on an internal port. Block Shipper listens on
the server's public port and pipes the connection through once the server is ready. While it's
not running:

- Server list pings get a "sleeping" message from Block Shipper.
- In auto mode, a join attempt starts the server, and the player is told to reconnect in about
  30 seconds. Banned and non-whitelisted players can't wake a server.

**Known limitations**

- Minecraft sees every player as connecting from `127.0.0.1`, so IP bans (`ban-ip`) have no
  effect. Name and UUID bans work.
- Only vanilla servers are supported for now. Fabric and Modrinth modpacks are on the roadmap.

## Security

- Sessions are random IDs in an `HttpOnly`, `SameSite=Strict` cookie (also `Secure` over HTTPS)
  and expire after 30 days.
- State-changing requests and console websockets from other origins are refused, and pages
  can't be framed by other sites.
- Uploaded world archives are checked for path traversal, links and zip bombs before anything
  is extracted.
- Admins are fully trusted: they can run console commands and choose the Java command used to
  launch servers. Only give the admin role to people you'd trust with a shell on the machine.
- The public Start button and the login form are not rate limited yet. Keep the web UI behind a
  reverse proxy if it faces the internet.

## Development

```sh
clj -M:dev -m com.main                         # run the app
clj -M:test                                    # all tests (needs a JDK)
clj -M:test --focus com.blockshipper.web-test  # one namespace
clj -M:chk                                     # outdated dependencies
```

The tests replace Minecraft with `tests/fixtures/FakeServer.java`, a stand-in that prints
vanilla-style log lines and obeys console commands, so they don't need network access or a real
server jar. See [`CLAUDE.md`](CLAUDE.md) for an architecture tour.

Built with [Ring](https://github.com/ring-clojure/ring), [Compojure](https://github.com/weavejester/compojure),
[Hiccup](https://github.com/weavejester/hiccup), [htmx](https://htmx.org/), [Chart.js](https://www.chartjs.org/)
and SQLite.

## License

Block Shipper is free software, released under the
[GNU Affero General Public License v3.0](LICENSE) or later. If you run a modified version for
other people over a network, the AGPL asks you to offer them its source code.

Minecraft is a trademark of Mojang Synergies AB. Block Shipper is not affiliated with Mojang or
Microsoft.
