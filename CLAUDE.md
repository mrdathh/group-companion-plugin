# Tracker Sync (RuneLite plugin) — notes for coding sessions

Read `docs/STATUS.md` first (state, owner decisions, in-game checklist). Wire format: `docs/PROTOCOL.md`.

## Layout

Package `com.osrstracker`, Java 11, Gradle project in this folder (Plugin Hub template layout).

- `TrackerSyncPlugin`, `TrackerSyncConfig` — lifecycle/event wiring; config group `trackersync` (hidden key `clientId`).
- `EventSender` — queue, batching, retry/backoff, 500 cap, newest snapshot per key, unchanged suppression, volatile `status`, heartbeat.
- `SessionTracker` — login/logout, account ref, world exclusions; every emit goes through it.
- `Throttle`, `TimeSource` — debounce/min-interval/max-wait on the injected executor; replaceable clock for tests.
- `ItemMapper`, `LocationReader`, `HouseTracker` — item values; real-world location (instances, boats, house).
- Collectors (one per data group): `LootCollector`, `SkillsCollector`, `ContainerCollector`, `QuestDiaryCollector`,
  `CollectionLogCollector`, `DeathCollector`, `StatusCollector`, `SlayerCollector`, `KillCountCollector`,
  `CombatAchievementCollector`, `timers/TimersCollector`.
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
- Send nothing until URL and token are set; nothing on excluded worlds.
- Use `net.runelite.api.gameval` constants; keep deprecation warnings at zero.
- Ported code keeps its licence notice and is listed in `THIRD_PARTY_NOTICES.md`.

## Contract rule

The source of truth is the tracker-sync-server project's `docs/PROTOCOL.md` and `shared/protocol.ts`. This
project must not depend on files outside its folder: `docs/PROTOCOL.md` here is a snapshot of the plugin's half.
When the contract changes: update the models, `ProtocolJsonTest` (expected JSON written by hand from the
contract), and this snapshot together. Do not change the contract from here; report disagreements to the owner.

## Environment quirks

- This PC has only JDK 11 (Temurin 11.0.32); Gradle 8.10 via the wrapper; RuneLite resolves `latest.release`
  (1.13.6 at the time of writing).
- The folder is on a network share: git refuses it as "dubious ownership". Pass `-c safe.directory=*` on each git
  command; do not change the global git config. Do not commit or push unless the owner asks.
