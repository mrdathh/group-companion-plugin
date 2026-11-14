Protocol v1, plugin 0.1.0, snapshot of 2026-11-14. The source of truth is the tracker-sync-server project's `docs/PROTOCOL.md` and `shared/protocol.ts`; this file is the plugin's half as built, and must be kept in step with them.

# Tracker Sync plugin → server protocol

The plugin POSTs JSON to a server the player hosts and configures (default `http://127.0.0.1:8787`).
It never opens a port and never receives requests.

## Request

`POST <server URL>/api/ingest` (a path in the server URL is kept: `https://host/relay` → `https://host/relay/api/ingest`).

Headers: `Authorization: Bearer <token>`, `X-Plugin-Version: 0.1.0`, `Content-Type: application/json` (no charset parameter).

```jsonc
{
  "v": 1,
  "client": { "id": "<random UUID, once per RuneLite install>", "plugin": "0.1.0" },
  "account": {
    "hash": "<client.getAccountHash() as a decimal string>",
    "rsn": "<player name>",
    "type": "NORMAL | IRONMAN | HARDCORE_IRONMAN | ULTIMATE_IRONMAN | GROUP_IRONMAN | HARDCORE_GROUP_IRONMAN | UNRANKED_GROUP_IRONMAN",
    "world": 302
  },
  "events": [ /* IngestEvent; empty = heartbeat */ ]
}
```

Every event: `{ "id": "<UUID>", "type": "<type>", "at": "<ISO-8601 UTC with milliseconds, e.g. 2026-11-14T16:20:00.123Z>", "data": { ... } }`.

Optional fields are omitted when unknown. Fields typed `X | null` in the contract (`status.interacting`, `slayer.task`, `timers[].readyAt`) are always present and sent as JSON `null`.

## Responses

| Status | Plugin reaction |
| --- | --- |
| 2xx | Drop the events; remember state snapshots as sent. |
| 400, 413, any other 4xx except below | Drop the events (the whole batch), log once with the event types and the server's error text. |
| 401 | Stop sending (also heartbeats) until the URL or token changes; warn once in chat. Queued history events are kept (cap 500). |
| 5xx, 408, 429, network error, 15 s call timeout | Keep and retry with backoff 5, 10, 20, 40, 60, 60 … s. |

One request in flight at a time; one account per request; at most 250 events per request (the rest follows at once).

## Queue

- History events (`login`, `logout`, `loot`, `level_up`, `death`, `clog_item`): kept in order, at most 500; the oldest is dropped.
- State events: only the newest per state key (the event type, or `container:<name>`).
- A snapshot whose `data` is identical to the last one the server accepted for that account and key is not sent. Exception: `status` is re-sent unchanged every 30 s.
- `status` is never queued while the server is unreachable (during backoff or after a 401 it is dropped, and it is not retried).
- Changing the URL or token forgets which snapshots were sent, so a new server gets them all.
- Heartbeat: a request with `events: []` when 60 s passed without any request while logged in.

## When things are sent

Nothing is sent until a server URL and a token are set and the player has ticked "Allow sending to this server" (consent, remembered per URL host; a different host withdraws it), and nothing on worlds of type SEASONAL, DEADMAN, FRESH_START_WORLD, TOURNAMENT_WORLD, BETA_WORLD, NOSAVE_MODE, QUEST_SPEEDRUNNING or PVP_ARENA. Each data group has a config toggle (deaths, login and logout have none).

| Type | When (as built) |
| --- | --- |
| `login` | Once per real game login: the first game tick after logging in once the name and account hash are known, then every snapshot. Region loads (LOADING → LOGGED_IN), hops between allowed worlds and reconnects send nothing. After a hop back from an excluded world: once. Stopping and starting the plugin mid-session resumes without a login (snapshots are offered again). When the destination really changes mid-session (URL, token or consent, applied 2 s after the last edit): `login` and every snapshot again for the new destination, never a `logout`. |
| `logout` | Login screen, client shutdown (waits for the final request), or a hop to an excluded world; only if a `login` was sent for this session. Not on plugin stop. Queued loot and captured containers go out before it. |
| `loot` | Held until the end of the game tick (so a kill-count message of the same tick can be attached), then sent; the request leaves about 50 ms later. |
| `level_up`, `death` | At once. |
| `clog_item` | On the next game tick after the chat message. |
| `skills` | 3 s after the last XP change, at most once per 10 s, at most ~10 s late while XP keeps changing. |
| `container` inventory, equipment | 2 s after the last change (at most 15 s late while it keeps changing); also captured at login. |
| `container` bank, seed_vault, looting_bag | Captured when the game sends the container (while open); sent 5 s after the last change, and at once when the bank or seed vault closes. |
| `quests` | 5 s after login, then every 60 s. |
| `diaries` | 5 s after login, then 1 s after a diary varbit changes, at most once per 5 s. |
| `collection_log` | When the player's own collection log opens (never a house adventure log), at most once per 500 game ticks (reset on logout); sent on the tick after the items were read. |
| `status` | Sampled every game tick; offered at most once per second (sent only if changed), re-sent unchanged every 30 s, at once after a world hop. |
| `timers` | 3 s after login, then every 30 s. |
| `slayer` | 2 s after login, then 200 ms after a slayer var changes, at most once per 2 s. |
| `kill_counts` | 3 s after login, then 1 s after the Chat Commands `killcount` config changes, at most once per 5 s. |
| `combat_achievements` | 5 s after login, then 1 s after a CA var changes, at most once per 5 s. |

World hops between allowed worlds send no login/logout; later requests carry the new `account.world`.

Switching a data-group setting on sends that group's current snapshots and nothing else.

## Event data

Items (`Item`): `{ id, qty, name, ge, ha }`. `id` is canonical (un-noted; placeholders and bank fillers skipped), `ge` is the GE price per unit (0 if untradeable), `ha` the high-alchemy value per unit.

### History events

- **`login`, `logout`**: `{ "world": 302 }`.
- **`loot`**: `{ "source": { "type": "NPC|EVENT|PICKPOCKET|PLAYER|UNKNOWN", "name", "id"?, "combatLevel"? }, "killCount"?, "items": [Item], "totalGe" }`. `items` merges stacks of one id; `totalGe` = Σ qty × ge. Loot with no items is not sent. PvP loot: `name` is `"Player"`, no id or level.
- **`level_up`**: `{ "skill": "Woodcutting", "level": 70 }` (real level; never for the login burst).
- **`death`**: `{ "killer"?: { "type": "NPC|PLAYER", "name"?, "id"? }, "location": { x, y, plane, regionId } }`. A player killer has no name or id; `killer` is omitted when unknown.
- **`clog_item`**: `{ "name": "Pet snakeling", "id"? }` (`id` only if the name was seen in recent loot or containers).

### State events

- **`skills`**: `{ "skills": { "<Skill.getName()>": { xp, level } }, totalLevel, totalXp, combatLevel }`.
- **`container`**: `{ "container": "inventory|equipment|bank|seed_vault|looting_bag", "items": [Item + { slot, equipSlot? }] }`. `equipSlot` (equipment only): head, cape, amulet, weapon, body, shield, legs, gloves, boots, ring, ammo.
- **`quests`**: `{ "quests": { "<quest name>": "NOT_STARTED|IN_PROGRESS|FINISHED" }, questPoints }`.
- **`diaries`**: `{ "diaries": { "<region>": { "Easy|Medium|Hard|Elite": { "complete": bool } } } }`. `done`/`total` are never sent.
- **`collection_log`**: `{ "items": { "<itemId>": qty }, "names": { "<itemId>": name }, obtained, total }`.
- **`status`**: `{ "hp": {cur, max}, "prayer": {cur, max}, runEnergy (0-100), specialAttack (0-100), world, "location": Location, "interacting": { name, id?, hpRatio, hpScale } | null }`. `interacting` is an NPC only; `hpRatio`/`hpScale` are -1 without a health bar.
- **Location**: `{ x, y, plane, regionId, instanced, onBoat, house? }` — real-world tile, also in instances and on boats. `house: true` (omitted otherwise) inside a player-owned house; x/y are then the house portal's tile if the player walked in through it, otherwise the room template tile.
- **`timers`**: `{ "timers": [ { id, kind: "farming|birdhouse", category, place, produce?, itemId?, state: "EMPTY|GROWING|READY|DISEASED|DEAD|UNKNOWN", readyAt: iso|null } ] }`. Ids: `farming:<tab>:<region>[-<patch>]` (e.g. `farming:herb:ardougne`), `birdhouse:<space>` (e.g. `birdhouse:meadow-north`). Patches never observed are not listed.
- **`slayer`**: `{ "task": { name, remaining, initial, location? } | null, points, streak }`.
- **`kill_counts`**: `{ "counts": { "<display name>": count } }`.
- **`combat_achievements`**: `{ points, completed }`. `tiers` and `tasks` are never sent.

## Privacy

- Only the configured URL is contacted, with RuneLite's OkHttp client, asynchronously.
- No credentials are read or sent. The token is only sent to the configured URL.
- Nothing about other players: PvP loot and player killers carry no name; `interacting` is never a player.
