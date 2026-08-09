# Hosting the multiplayer server on the internet

Phase 4 infrastructure. Getting the headless server reachable from a browser is a
hard prerequisite for the Phase 5 web client — a page served over `https` may only
open a `wss://` socket, so a bare `ws://host:8777` will not do once the client is
a real web page.

**Shape of the deployment:** one JVM hosts one game (the engine keeps a single
process-wide `GameSession`), so several concurrent games means several processes,
one per port. Capacity is bounded by RAM, not CPU — turns resolve in well under a
second and the server is idle between them. At `-Xmx2g` per game, an **Oracle Cloud
Always Free Ampere A1 VM (4 OCPU / 24 GB)** runs about six games with headroom.

There is no container image and none is needed: the build already produces a
single runnable fat JAR (`maven-shade-plugin`), so a deployment is a JAR, a JVM,
and a systemd unit.

```
                        ┌──────── Oracle Cloud ARM VM ────────┐
  browser ──wss:443──▶  │  Caddy  ──ws──▶  java -jar :8777    │  game 1
                        │         ──ws──▶  java -jar :8778    │  game 2
                        │         ──ws──▶  ...        :8782   │  game 6
                        └─────────────────────────────────────┘
```

## 1. Build

```bash
mvn -q package                     # target/rotp-<version>.jar (fat JAR)
```

The JAR is architecture-independent; build it anywhere and copy it to the ARM VM.
Only a JDK 17+ runtime is needed there (`sudo apt install openjdk-17-jre-headless`
on Ubuntu, `sudo dnf install java-17-openjdk-headless` on Oracle Linux).

## 2. Lay out the host

```bash
sudo useradd --system --home /opt/rotp --shell /usr/sbin/nologin rotp
sudo mkdir -p /opt/rotp /etc/rotp /var/lib/rotp
sudo cp target/rotp-*.jar /opt/rotp/rotp.jar
sudo mkdir -p /var/lib/rotp/game-{1,2,3,4,5,6}
sudo chown -R rotp:rotp /opt/rotp /var/lib/rotp

sudo cp deploy/rotp-game@.service /etc/systemd/system/
sudo cp deploy/game-1.env.example /etc/rotp/game-1.env   # edit port/players/size
sudo systemctl daemon-reload
sudo systemctl enable --now rotp-game@1
journalctl -u rotp-game@1 -f
```

Each instance gets its own env file (`/etc/rotp/game-2.env` with `ROTP_PORT=8778`,
and so on) and its own save directory (`savedir=`, passed by the unit). Sharing a
save directory between games would let them overwrite each other's saves, and
sharing the preferences file would have them race over it — hence the dedicated
`savedir=` argument rather than the global preference.

## 3. TLS

Two options; **the reverse proxy is the better one** and is what the systemd unit
assumes (`ROTP_BIND=127.0.0.1`, so the game ports are not reachable from the
internet at all).

### Caddy in front (recommended)

Caddy is a single static binary — not a container — and obtains and renews
Let's Encrypt certificates by itself. Crucially, a renewed certificate is picked
up by reloading *Caddy*, leaving the game JVMs untouched; terminating TLS inside
each JVM would mean restarting them every renewal, ending any game in progress.

`/etc/caddy/Caddyfile`:

```
rotp.example.com {
    handle_path /game/1/* { reverse_proxy 127.0.0.1:8777 }
    handle_path /game/2/* { reverse_proxy 127.0.0.1:8778 }
    handle_path /game/3/* { reverse_proxy 127.0.0.1:8779 }
    handle_path /game/4/* { reverse_proxy 127.0.0.1:8780 }
    handle_path /game/5/* { reverse_proxy 127.0.0.1:8781 }
    handle_path /game/6/* { reverse_proxy 127.0.0.1:8782 }
}
```

Caddy upgrades WebSocket connections through `reverse_proxy` with no extra
configuration. The web client then connects to `wss://rotp.example.com/game/1`.

The Java reference client speaks the same URL, which is how you test a hosted
game before the browser client exists:

```bash
java -jar rotp.jar --client url=wss://rotp.example.com/game/1 name=Alice
```

(`host=`/`port=` still work for a plain `ws://` LAN game.)

### TLS inside the JVM (no proxy)

For a single self-contained process — a player hosting one game for friends:

```bash
java -Xmx2g -jar rotp.jar --server port=8777 bind=0.0.0.0 \
     keystore=/etc/rotp/cert.p12 keystorePassword=...
```

The keystore is PKCS12. From certbot output:

```bash
openssl pkcs12 -export -out cert.p12 \
    -inkey /etc/letsencrypt/live/HOST/privkey.pem \
    -in    /etc/letsencrypt/live/HOST/fullchain.pem
```

If the keystore cannot be read the server **fails to start** rather than falling
back to plain `ws://` — an operator who asked for TLS must never silently get an
unencrypted public port.

## 4. Open the ports

Oracle Cloud filters traffic in two independent places, and both must allow it:

1. **VCN security list / network security group** — add an ingress rule for TCP
   443 (and 80, so Caddy can answer the ACME HTTP challenge) from `0.0.0.0/0`.
2. **The instance's own firewall** — Oracle's images ship with restrictive rules
   preloaded:

```bash
# Ubuntu images
sudo iptables -I INPUT -p tcp --dport 443 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 80  -j ACCEPT
sudo netfilter-persistent save

# Oracle Linux images
sudo firewall-cmd --permanent --add-service=https --add-service=http
sudo firewall-cmd --reload
```

Only 80/443 are exposed. The game ports stay on loopback behind Caddy.

## 5. What the server already does for internet play

- **Session tokens.** `Joined.sessionToken` is issued on join and replayed in
  `Hello.sessionToken` to re-claim the same empire. It survives a display-name
  change, and it works even when the old socket is still open — the browser
  refresh case, where the new connection arrives before the server sees the close.
  The stale connection is evicted. Name matching remains as a fallback for
  clients carrying no token. Store it in `localStorage` in the web client.
- **Connection-lost detection.** The server pings every 30s and drops a silent
  connection, so a slept laptop or a dead mobile link lands the player in the
  `departed` map (where their token can reclaim them) instead of leaving a ghost
  holding the empire.
- **Turn timers.** `ROTP_TIMER` (or the host's lobby pick) auto-resolves a we-go
  turn after the deadline, so one absent player cannot stall an internet game
  indefinitely.

## Operating notes

- **Ending a game.** `systemctl stop rotp-game@N`. Save first (the host's ⌘S /
  `saveGame`), then restart with `load=<name>` added to `ExecStart` to resume.
- **Restarts are survivable.** `Restart=on-failure` brings a crashed game back;
  clients re-claim their empires with their session tokens. What a restart does
  *not* recover is the in-memory galaxy, so an unsaved game restarts from the
  last save (or not at all) — take saves before any planned restart.
- **Sizing.** Raise `-Xmx` for large galaxies; lower the instance count to match.
  The 2 GB in the unit file is sized against what the integration suite needs for
  a full game engine.
