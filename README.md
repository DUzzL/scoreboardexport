# Stats Exporter

A server-side Fabric mod for Minecraft 26.2 that exposes player scoreboard
statistics over a lightweight HTTP endpoint, so the xaprosmp.xyz website can
render a Statistics tab.

The mod reads the scoreboard **live from the running server** via
`MinecraftServer#getScoreboard()` — it does **not** parse `scoreboard.dat` as
NBT, because it runs server-side and has direct in-memory access.

## Tracked objectives

| Objective name      | Meaning                          |
|---------------------|----------------------------------|
| `bac_advancements`  | Number of advancements earned    |
| `hc_playTimeShow`   | Play time (raw unit: minutes, see Assumptions) |

## Configuration

A JSON config file is created automatically at
`<server>/config/statsexporter.json` on first launch. Edit it and restart the
server to apply changes.

| Key                    | Type    | Default                  | Notes                                              |
|------------------------|---------|--------------------------|----------------------------------------------------|
| `port`                 | int     | `8790`                   | Port the HTTP server listens on.                   |
| `cacheIntervalMinutes` | int     | `10`                     | How often the stats snapshot is recomputed. Clamped to **5–15**. |
| `allowedOrigin`        | string  | `https://xaprosmp.xyz`   | Origin allowed via CORS (`Access-Control-Allow-Origin`). |

Example:

```json
{
  "port": 8790,
  "cacheIntervalMinutes": 10,
  "allowedOrigin": "https://xaprosmp.xyz"
}
```

## HTTP API

### `GET /api/stats`

Returns the cached stats snapshot as JSON. The snapshot is recomputed every
`cacheIntervalMinutes`; requests always serve the cached copy to stay cheap.

**200 response:**

```json
{
  "lastUpdated": "2026-07-04T14:00:00Z",
  "players": [
    {
      "name": "Steve",
      "bac_advancements": 42,
      "hc_playTimeShow": 128394
    }
  ]
}
```

- `lastUpdated` — ISO-8601 UTC timestamp of the last cache refresh.
- `players` — array of player objects. **Empty array** when the scoreboard
  has no data yet (the endpoint never errors for missing data).
- `bac_advancements` — integer score for the `bac_advancements` objective.
- `hc_playTimeShow` — integer score for the `hc_playTimeShow` objective
  (raw unit assumed to be minutes — see Assumptions).

CORS headers are sent on every response:

```
Access-Control-Allow-Origin: <allowedOrigin>
Access-Control-Allow-Methods: GET, OPTIONS
Access-Control-Allow-Headers: Content-Type
Access-Control-Max-Age: 600
```

`OPTIONS` requests are answered with `204 No Content` for CORS preflight.

## Deployment notes

1. **Open the port.** The chosen `port` must be opened as an **additional
   allocation** in the Pterodactyl/Folium hosting panel — it is separate from
   the Minecraft game port.
2. **Front it with HTTPS.** The website is served over HTTPS, so a plain HTTP
   endpoint will trigger mixed-content blocking in browsers. Put a reverse
   proxy (Caddy or Nginx with Let's Encrypt) or a Cloudflare Tunnel in front
   of the mod's local port, and expose it as an HTTPS subdomain such as
   `api.xaprosmp.xyz` (kept distinct from the `/stats` website page to avoid
   naming confusion), forwarding to the mod's local port.
3. **Set `allowedOrigin`** to the exact origin the website is served from
   (e.g. `https://xaprosmp.xyz`), so the browser allows the cross-origin
   fetch.

## Assumptions

- **Play time unit.** The raw `hc_playTimeShow` value is assumed to be in
  **minutes**. The website converts it to a human-readable hours/minutes
  string. If at runtime the objective turns out to store a different unit
  (e.g. ticks or seconds), only the website's formatting divisor needs to
  change — the JSON contract stays the same.
- **Objective names.** The objective names `bac_advancements` and
  `hc_playTimeShow` are assumed to match the vanilla scoreboard criteria
  registered on the server. If they differ at runtime, update the constants
  `OBJ_ADVANCEMENTS` / `OBJ_PLAY_TIME` in `ScoreboardReader.java`.
- **Player identity.** The scoreholder's scoreboard name is used as the player
  name. For offline-mode servers this is the username; for online-mode servers
  it is also the username (the scoreboard keys on player names, not UUIDs).

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
