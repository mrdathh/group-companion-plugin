# Group Companion (RuneLite plugin) — notes for coding sessions

Read `docs/STATUS.md` first (state, owner decisions, in-game checklist). Wire format: `docs/PROTOCOL.md`.

## Layout

Package `com.osrstracker`, Java 11, Gradle project in this folder (Plugin Hub template layout).
Shown name: "Group Companion" (renamed from "Tracker Sync", 2026-11-15). The identifiers keep the old name on
purpose: class names `TrackerSync*`, config group `trackersync` and its keys (saved settings), folder name. Do not
rename them.

- `TrackerSyncPlugin`, `TrackerSyncConfig` — lifecycle/event wiring; config group `trackersync` (hidden key `clientId`).
- `TrackerSyncPanel` — side panel: copy token, copy app login link (`<origin>/#token=`), status line; "Show side panel".
- `JoinToken` — joining with the server's join code: with an empty Token and a "Join code", `applyServerConfig` makes
  the player's token (`SecureRandom`, server format) and saves it once; `EventSender` sends `X-Join-Code` while the
  field is set; a 403 on ingest (server full for joining) stops sending like a 401.
- `EventSender` — queue, batching, retry/backoff, 500 cap, newest snapshot per key, unchanged suppression, volatile `status`,
  solo events (`loot_history` alone in its request), heartbeat.
- `SessionTracker` — login/logout, account ref, world exclusions; every emit goes through it. One `login` per real
  game login (never on region loads, hops, reconnects or plugin restarts); `pause()` on plugin stop sends no logout.
- `Consent` — one-time "Allow sending to this server" consent bound to the URL host (hidden key `consentHost`).
  URL/token/consent edits are applied by `TrackerSyncPlugin` 2 s after the last edit.
- `Throttle`, `TimeSource` — debounce/min-interval/max-wait on the injected executor; replaceable clock for tests.
- `ItemMapper`, `LocationReader`, `HouseTracker` — item values; real-world location (instances, boats, house).
- `ActivityTracker` — `status.activity` for the app's idle alerts (input idle + logout estimate, combat, action idle),
  modelled on core's Idle Notifier. Every field changes only on a transition (keeps `status` traffic low); observe only,
  never simulate input or prevent a logout. Thresholds live in the app/server, not here.
- Collectors (one per data group): `LootCollector`, `SkillsCollector`, `ContainerCollector`, `QuestDiaryCollector`,
  `CollectionLogCollector`, `DeathCollector`, `StatusCollector`, `SlayerCollector`, `KillCountCollector`,
  `CombatAchievementCollector`, `timers/TimersCollector`, `StorageCollector` (rune pouch, quiver, plank sack, potion
  and group storage), `BagCollector` (chat-tracked bags), `DepositCollector` (deposit box → `bank_deposit`),
  `LoadoutCollector` (Inventory Setups saved config), `KingdomCollector` (Miscellania), `LootHistoryCollector`
  (core Loot Tracker's stored `loottracker` records → `loot_history`; `LootHistoryLimiter` trims it to the server's limits).
- `SlayerCollector` also sends every master's block list (`blockedByMaster`) and the Task Storage slot (`stored`),
  captured only while the game draws a slayer master's Rewards > Tasks tab (scripts 328/421, IF varps, guarded by
  IF1/IF2 = the live task varps); the capture lives in memory for the session. It never opens the interface.
- `CollectionLogCollector` also sends `clog_page`: header kill counts of a page the player opens (never opens pages
  itself), and the same counts for every page in `ClogPageVars` (page struct → header label → `TOTAL_*` player var,
  from cache script 2735) at login and when those vars change, without any page being opened.
- `DiaryTasks` + `src/main/resources/com/osrstracker/diary_tasks.json` — per-task diary names and done-bits (generated
  from the Group Ironmen Tracker site data, BSD-2; cross-checked against WikiSync's spec, MIT).
- `ContainerPublisher` — shared debounced emission of every `container` snapshot (capture now, send after a quiet period).
- `timers/` other files — ported RuneLite Time Tracking tables (BSD-2 headers kept; do not edit beyond package/imports).
- `model/` — plain classes mirroring the contract's JSON; `WriteNull` writes explicit `null` for `T | null` fields.
- Tests in `src/test/java/com/osrstracker/`: JSON vs hand-written contract JSON (`ProtocolJsonTest`), sender with
  `MockWebServer` + `FakeScheduler` (`EventSenderTest`), Guice wiring smoke test (`TrackerSyncPluginInjectionTest`),
  collector rules. `TrackerSyncPluginTest` is the `./gradlew run` launcher, not a test.

## Commands

```
./gradlew --no-daemon clean build     # compile + all unit tests (must pass)
./gradlew --no-daemon cleanTest test  # rerun tests
```

`./gradlew run` starts the RuneLite GUI client: **never run it from an agent** (only the owner plays; automating
game input breaks Jagex rules). Ask the owner to run it and follow `docs/STATUS.md`'s checklist.

## Rules this code follows (Plugin Hub)

- Network only through RuneLite's injected `OkHttpClient`, always `enqueue` (never on the client thread). JSON only
  through the injected `Gson`. No other runtime dependencies.
- Game state is read on the client thread (`ClientThread`, event handlers); timers use the injected
  `ScheduledExecutorService`. No `Thread.sleep`, no blocking in startUp/shutDown.
- No reflection, JNI/JNA, subprocesses, `java.io` file access, dynamic code loading, or Java serialization.
- Nothing about other players (no names of PvP victims/killers; `interacting` is NPCs only). No local ports.
  No credentials read or sent.
- Send nothing until URL and token are set and consent is ticked; nothing on excluded worlds. Do not put a
  `warning` on text fields (RuneLite shows it on every focus loss); the warning sits on the consent checkbox.
- Use `net.runelite.api.gameval` constants; keep deprecation warnings at zero.
- Ported code keeps its licence notice and is listed in `THIRD_PARTY_NOTICES.md`. No text copied from the OSRS Wiki
  (CC BY-NC-SA) inside the plugin.
- **Pure sender (owner decision 2026-11-14):** the plugin never reads data from the server (beyond the HTTP status of its
  own requests), has no in-game goal overlay or goal panel, and contacts nothing but the player's configured server.
  Goals/roadmaps live in the phone app; outside data (e.g. OSRS Wiki drop rates) is fetched by the server.
  The one side panel (`TrackerSyncPanel`, owner request 2026-11-15) only copies the token / app login link from the
  plugin's own settings and shows a status line; it must never display or log the token.

## Contract rule

The source of truth is the group-companion-server project's `docs/PROTOCOL.md` and `shared/protocol.ts`. This
project must not depend on files outside its folder: `docs/PROTOCOL.md` here is a snapshot of the plugin's half.
When the contract changes: update the models, `ProtocolJsonTest` (expected JSON written by hand from the
contract), and this snapshot together. Do not change the contract from here; report disagreements to the owner.

## Environment quirks

- This PC has only JDK 11 (Temurin 11.0.32); Gradle 8.10 via the wrapper; RuneLite resolves `latest.release`
  (1.13.6 at the time of writing).
- The folder is on a network share: git refuses it as "dubious ownership". Pass `-c safe.directory=*` on each git
  command; do not change the global git config. Do not commit or push unless the owner asks.
