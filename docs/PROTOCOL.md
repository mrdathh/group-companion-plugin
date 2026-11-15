Protocol v1, plugin 0.1.0, snapshot of 2026-11-15 (updated for storages, deposit box, loadouts, diary tasks, CA tasks, slayer detail, kingdom, Tears of Guthix, loot history limits, collection log summary, name-only `clog_item` and loadout limits). The source of truth is the group-companion-server project's `docs/PROTOCOL.md` and `shared/protocol.ts`; this file is the plugin's half as built, and must be kept in step with them.

# Group Companion plugin → server protocol

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
    "world": 302,
    "group": "<group ironman group name>"   // only when in a group ironman group
  },
  "events": [ /* IngestEvent; empty = heartbeat */ ]
}
```

`account.group` is the group ironman group's name from `client.getClanSettings(ClanID.GROUP_IRONMAN)` (or that clan's channel). The game sends it a few ticks after login, so the first requests may lack it; it is added as soon as it is known, without a second `login`. Nothing about other group members is sent.

Every event: `{ "id": "<UUID>", "type": "<type>", "at": "<ISO-8601 UTC with milliseconds, e.g. 2026-11-14T16:20:00.123Z>", "data": { ... } }`.

Optional fields are omitted when unknown. Fields typed `X | null` in the contract (`status.interacting`, the `status.activity` times, `slayer.task`, `timers[].readyAt`) are always present and sent as JSON `null`.

## Responses

| Status | Plugin reaction |
| --- | --- |
| 2xx | Drop the events; remember state snapshots as sent. |
| 400, 413, any other 4xx except below | Drop the events (the whole batch), log once with the event types and the server's error text. |
| 401 | Stop sending (also heartbeats) until the URL or token changes; warn once in chat. Queued history events are kept (cap 500). |
| 5xx, 408, 429, network error, 15 s call timeout | Keep and retry with backoff 5, 10, 20, 40, 60, 60 … s. |

One request in flight at a time; one account per request; at most 250 events per request (the rest follows at once). `loot_history` always travels in a request of its own (no other events): when it is the oldest pending event it is sent alone, otherwise the other events go first as one batch and it follows in the next request; the final flush at shutdown sends the other events (an unsent `loot_history` is sent again next session). The server limits bodies to 2 MiB (2,097,152 bytes; 413).

## Queue

- History events (`login`, `logout`, `loot`, `level_up`, `death`, `clog_item`, `bank_deposit`): kept in order, at most 500; the oldest is dropped.
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
| `loot_history` | 10 s after login, then 2 s after the core Loot Tracker's stored records (`loottracker` RS-profile keys `drops_*`) change, at most once per 60 s; unchanged snapshots are not resent. Trimmed to the server's limits when over them (see below), never skipped. |
| `clog_item` | On the next game tick after the game's chat line or its "Collection log" popup (whichever the game setting shows); when both arrive for the same item (within 50 ticks, popups can queue), one event. |
| `skills` | 3 s after the last XP change, at most once per 10 s, at most ~10 s late while XP keeps changing. |
| `container` inventory, equipment | 2 s after the last change (at most 15 s late while it keeps changing); also captured at login. |
| `container` bank, seed_vault, looting_bag | Captured when the game sends the container (while open); sent 5 s after the last change, and at once when the bank or seed vault closes. |
| `container` seed_box, tackle_box, forestry_kit, huntsmans_kit, boat_cargo_hold_1..5, trawling_net | Real game containers: captured when the game sends them; sent 5 s after the last change. |
| `container` rune_pouch, quiver, plank_sack | From varbits/varps: once after login (also when empty), then 2 s after they change. Always exact. |
| `container` potion_storage | While the bank is open: when the bank is built or a potion-store var changes; sent 5 s later, at once when the bank closes. `qty` = doses of the 1-dose item, `unit: "dose"`. |
| `container` group_storage | Group ironman accounts only: the working copy captured while the shared storage is open, sent when the game shows "Saving...". |
| `container` fish_barrel, log_basket, gem_bag, coal_bag, herb_sack | 1 s after the plugin learns the contents: a "Check" listing or an "is empty" message is a full read (`estimated` omitted); a "Fill"/"Empty" click followed by an inventory change within 2 ticks adjusts the last full read and is sent with `estimated: true`. Nothing before the first full read of a session, and nothing from adjustments while an open bag has been carried since the last full read (gathering into it is not counted). |
| `bank_deposit` | After a deposit-box control click (or a quantity typed while the deposit box is open): inventory, equipment and looting bag decreases within 2 ticks, sent on the next tick (under 2 s). Never from the normal bank interface. |
| `loadouts` | 3 s after login, then 1 s after the Inventory Setups config group changes, at most once per 5 s. Not sent when there are no setups. Cut to the server's limits (see below), never skipped. |
| `quests` | 5 s after login, then every 60 s. |
| `diaries` | 5 s after login, then 1 s after a diary completion varbit or task var changes, at most once per 5 s. |
| `collection_log` | When the player's own collection log opens (never a house adventure log): every item the log's search draws is taken, whoever opened the search (this plugin opens it only if nothing was drawn by the next tick and it is not already on). The first read of a session is not limited; after a successful read at most one per 500 game ticks (reset on logout). Sent on the tick after the draws stop; not sent when far fewer items were drawn than the obtained count (not the full search). |
| `collection_log_summary` | 3 s after login (retried every 5 s for about 2 minutes while obtained and total are both 0, i.e. not loaded yet; never sent as 0/0), then 1 s after one of its vars changes, at most once per 5 s. |
| `status` | Sampled every game tick; offered at most once per second (sent only if changed), re-sent unchanged every 30 s, at once after a world hop. Every `activity` field changes only on a transition (input stopping/resuming, combat starting/ending, the character going idle/busy), so a transition goes out with the next allowed status and a steady state sends nothing new. |
| `timers` | 3 s after login, then every 30 s, and 1 s after a Tears of Guthix visit is seen. |
| `kingdom` | 5 s after login, then 1 s after a kingdom varbit changes, at most once per 5 s. Only after Throne of Miscellania is finished and once the approval var is loaded. |
| `slayer` | 2 s after login, then 200 ms after a slayer var changes, at most once per 2 s. |
| `kill_counts` | 3 s after login, then 1 s after the Chat Commands `killcount` config changes, at most once per 5 s. |
| `combat_achievements` | 5 s after login, then 1 s after a CA var changes, at most once per 5 s; once more when the task list has been read from the game cache (first login of a client run). |

World hops between allowed worlds send no login/logout; later requests carry the new `account.world`.

Switching a data-group setting on sends that group's current snapshots and nothing else.

## Event data

Items (`Item`): `{ id, qty, name, ge, ha }`. `id` is canonical (un-noted; placeholders and bank fillers skipped), `ge` is the GE price per unit (0 if untradeable), `ha` the high-alchemy value per unit.

### History events

- **`login`, `logout`**: `{ "world": 302 }`.
- **`loot`**: `{ "source": { "type": "NPC|EVENT|PICKPOCKET|PLAYER|UNKNOWN", "name", "id"?, "combatLevel"? }, "killCount"?, "items": [Item], "totalGe" }`. `items` merges stacks of one id; `totalGe` = Σ qty × ge. Loot with no items is not sent. PvP loot: `name` is `"Player"`, no id or level. The server's drop luck uses `source.id` (NPC id), `source.combatLevel` and `killCount` to pick the drop table and count kills without a drop.
- **`loot_history`** (state): `{ "sources": [ { type, name, kills, firstAt?, lastAt?, items: [Item] } ] }` — RuneLite core Loot Tracker's stored totals per source for the current profile (`ConfigLoot` JSON: type, name, kills, first/last epoch millis, drops = [id, qty, …]); canonical ids, current prices; all PLAYER sources merged into one source named "Player". Server limits (inclusive): at most 2,000 sources, 400 items per source, 50,000 items in all, names at most 200 characters (over any: 400 for the whole request). The plugin trims to fit, deterministically: names cut to 200; per source the 400 most valuable items (qty × `ge`, then highest qty, then lowest id); then the newest sources by `lastAt` (none = oldest; ties by name, then type) while within 2,000 sources, 50,000 items and 1,500,000 bytes of serialized `data`; kept sources stay sorted by type and name. One info log line says how many sources and items were left out. Left-out sources lose their history on the server (the snapshot replaces the previous one).
- **`level_up`**: `{ "skill": "Woodcutting", "level": 70 }` (real level; never for the login burst).
- **`death`**: `{ "killer"?: { "type": "NPC|PLAYER", "name"?, "id"? }, "location": { x, y, plane, regionId } }`. A player killer has no name or id; `killer` is omitted when unknown.
- **`clog_item`**: `{ "name": "Pet snakeling", "id"? }`. `name` always; `id` only if the name was seen in recent loot or containers. Name-only events are normal: the server resolves the item from the name.
- **`bank_deposit`**: `{ "items": [Item] }` — items put into the bank through a deposit box (the server adds them to the stored bank).

### State events

- **`skills`**: `{ "skills": { "<Skill.getName()>": { xp, level } }, totalLevel, totalXp, combatLevel }`.
- **`container`**: `{ "container": "<name>", "items": [Item + { slot, equipSlot?, unit? }], "estimated"? }`. Names sent: inventory, equipment, bank, seed_vault, looting_bag, rune_pouch, quiver, potion_storage, group_storage, plank_sack, seed_box, tackle_box, forestry_kit, huntsmans_kit, boat_cargo_hold_1..5, trawling_net, fish_barrel, log_basket, gem_bag, coal_bag, herb_sack. `equipSlot` (equipment only): head, cape, amulet, weapon, body, shield, legs, gloves, boots, ring, ammo. `unit: "dose"` only in potion_storage. `estimated: true` only for bags adjusted from fill/empty actions; omitted when read from the game.
- **`loadouts`**: `{ "loadouts": [ { id, name, source: "inventory_setups", equipment: [ContainerItem with equipSlot], inventory: [ContainerItem], runePouch?: [Item], notes? } ] }`. `id` = the setup's name. Read from the Inventory Setups plugin's saved config (formats V3, V2, V1). Server limits (inclusive; over any: 400): at most 500 loadouts, `id`/`name` 1–200 characters, `notes` 2,000, 50 `equipment`, 100 `inventory` and 10 `runePouch` items. The plugin cuts to fit: the first 500 setups in the plugin's order, name (and id) and notes shortened (never splitting a surrogate pair), the first items in slot order.
- **`quests`**: `{ "quests": { "<quest name>": "NOT_STARTED|IN_PROGRESS|FINISHED" }, questPoints }`.
- **`diaries`**: `{ "diaries": { "<region>": { "Easy|Medium|Hard|Elite": { complete, done, total, tasks: [ { name, done } ] } } } }`. `complete` comes from the tier's own "all tasks done" or "reward claimed" varbit; `tasks` are in the in-game order from the bundled task data (names and bits from the Group Ironmen Tracker, BSD-2); `done`/`total` count them. If the data cannot be loaded, only `complete` is sent.
- **`collection_log`**: `{ "items": { "<itemId>": qty }, "names": { "<itemId>": name }, obtained, total }`.
- **`collection_log_summary`**: `{ obtained, total, "tabs": [ { "tab": "Bosses|Raids|Clues|Minigames|Other", obtained, total } ], "recent": [ { "itemId", "at"? } ], "newItemNotification": "off|chat|popup|both" | null }` — the counts the game sends with the player vars at login, readable without opening the log (`COLLECTION_COUNT`/`_MAX`, the per-tab `COLLECTION_COUNT_<TAB>`/`_MAX` varps). `tabs` leaves out tabs whose total is 0 (not loaded); `recent` is the log's 12 "recent items" (`COLLECTION_OVERVIEW_LAST_ITEM0..11`, the game's order, empty slots left out); `at` is 00:00 UTC of the day the item was added (day precision: the game stores a day number, converted with the game's current day number `CURRENT_RUNEDAY` against today's UTC date), omitted when unknown. `newItemNotification` is the game setting "Collection log - New addition notification" (varbit 11959: 0 off, 1 chat, 2 popup, 3 both), always present, `null` for an unknown value. `tabs` and `recent` are always present (possibly empty). Server validation: each tab at most once, at most 100 `recent` entries, counts integers ≥ 0; anything else is 400 for the whole request. The day in `at` may be off by one near midnight.
- **`status`**: `{ "hp": {cur, max}, "prayer": {cur, max}, runEnergy (0-100), specialAttack (0-100), world, "location": Location, "interacting": { name, id?, hpRatio, hpScale } | null }`. `interacting` is an NPC only; `hpRatio`/`hpScale` are -1 without a health bar. Plus `"activity": { inputIdleSince, logoutAt, inCombat, combatEndedAt, actionIdleSince }` (times ISO, each `| null`, always present): `inputIdleSince` once there has been no mouse or keyboard input for 10 s (from the client's own idle counters), with `logoutAt` = that + the client's idle timeout (5 min by default; while being hit, no earlier than 20 min after the last click or key press), both null as soon as input resumes; `inCombat` while an NPC with an "Attack" option is the target or a hitsplat on or by the player came within the last 8 game ticks; `combatEndedAt` when that last turned false (null while in combat or before any combat since login); `actionIdleSince` once the character has had no animation, tile change, interaction target or combat for 3 s (set to the last busy moment).
- **Location**: `{ x, y, plane, regionId, instanced, onBoat, house? }` — real-world tile, also in instances and on boats. `house: true` (omitted otherwise) inside a player-owned house; x/y are then the house portal's tile if the player walked in through it, otherwise the room template tile.
- **`timers`**: `{ "timers": [ { id, kind: "farming|birdhouse|tears_of_guthix", category, place, produce?, itemId?, state: "EMPTY|GROWING|READY|DISEASED|DEAD|UNKNOWN", readyAt: iso|null } ] }`. Ids: `farming:<tab>:<region>[-<patch>]` (e.g. `farming:herb:ardougne`), `birdhouse:<space>` (e.g. `birdhouse:meadow-north`). Patches never observed are not listed. Tears of Guthix (only when the quest is done): `{ id: "tears_of_guthix", kind: "tears_of_guthix", category: "Weekly", place: "Tears of Guthix", state, readyAt }` — GROWING until 00:00 UTC seven days after the day of the last visit seen, then READY; UNKNOWN (readyAt null) before the first visit this plugin saw.
- **`slayer`**: `{ "task": { name, remaining, initial, location? } | null, points, streak, master?, wildernessStreak, blocked?: [name], unlocks: [name] }`. `master` from the master varbit (1 Turael … 10 Mortimer); `blocked` is the current master's block list; `unlocks` are the purchased rewards whose `bit` in the game's SlayerUnlock table is set in the unlock varps.
- **`kingdom`**: `{ approval (0–100, from points out of 127), coffer, workers: { Wood, Herbs/flax, Fishing, Mining, Hardwood, Farming }, royalTrouble }`.
- **`kill_counts`**: `{ "counts": { "<display name>": count } }`.
- **`combat_achievements`**: `{ points, completed, tiers?: { "<tier>": { done, total } }, tasks?: [ { id, name, tier, monster?, done } ] }`. `tiers`/`tasks` once the task list was read from the game cache; until then (or if the cache layout is not as expected) only `points` and `completed`.

## Privacy

- Only the configured URL is contacted, with RuneLite's OkHttp client, asynchronously.
- No credentials are read or sent. The token is only sent to the configured URL.
- Nothing about other players: PvP loot and player killers carry no name; `interacting` is never a player.
