# Status (2026-11-14)

## Owner decisions (2026-11-14)

- This plugin is the only data source for the tracker. Dink and reading other plugins' saved data were
  rejected as sources. (Two RuneLite **core** plugins' persisted data are read: Chat Commands'
  kill counts and Time Tracking's farming observations. The owner confirmed keeping both on 2026-11-14; the
  rejection is about Plugin Hub plugins such as Bank Memory.)
- Target: "Mystix and the Group Ironmen Tracker combined" (account tracking plus a live group view and map).
- Rune pouch and other storages, and saved gear loadouts, are not chosen for now.
- Two projects: this plugin (`tracker-sync-plugin`) and `tracker-sync-server` (server, phone app, contract).
- Local use first; the owner hosts a server later. Eventual idea: players self-host the server and this
  plugin is the connector.
- Plugin Hub submission comes later and needs a public BSD-2 repository (none exists yet).

## Honest state

- `./gradlew --no-daemon cleanTest build` passes on JDK 11: 116 unit tests, 0 failures, no javac warnings.
- First in-game run on 2026-11-14 (see below): the basics work against the real game; most of the checklist is
  still unverified. The session-event bug and the repeated IP-address warning dialog are fixed but not yet
  re-checked in game.
- First commit on `main`, pushed to the private GitHub repository `mrdathh/tracker-sync-plugin` (2026-11-14). It must be made public before a Plugin Hub submission.

## First in-game run (2026-11-14)

Fresh low-level account in Lumbridge, world 335, server on the same PC.

Confirmed in game:
- `login` with account hash (decimal string), rsn, type NORMAL, world 335.
- `status`: HP 12/12, prayer 1/1, run energy 100, special attack 100, location 3217,3262 plane 0 region 12850
  (map marker correctly north of Lumbridge castle); sent continuously.
- `skills`: 24 skills, total level 63, combat level 8.
- `container` inventory (16 items, correct slots, names and prices) and equipment (weapon and shield with
  correct `equipSlot`).
- `quests` (216 entries, 3 finished, 3 quest points), `diaries` (12 regions).
- `timers`: two Lumbridge patches (hops, tree) EMPTY with `readyAt: null` (own observation while standing there).
- `slayer` null/0/0, `kill_counts` empty, `combat_achievements` 0/0 (as expected for a fresh account).

Found and fixed (not yet re-checked in game):
- Session events: the feed showed login → logout → login → login within 30 s. Causes in the old code:
  `TrackerSyncPlugin.shutDown()` sent a `logout` and `startUp()` a new `login` (a plugin stop/start mid-session
  looks like logout + login one tick apart); and every committed edit of Server URL or Token called
  `SessionTracker.onServerConfigChanged()`, which sent a `login` even when the effective destination did not
  change. Now: one `login` per real game login (region loads, hops, reconnects and plugin restarts send none);
  plugin stop sends no `logout`; URL/token/consent edits are applied 2 s after the last one and only an
  effective change re-announces (login + snapshots, never logout); switching a data group on resends only
  that group. Covered by `SessionTrackerTest` and `TrackerSyncPluginInjectionTest`.
- "It keeps asking me about the IP address": RuneLite's `@ConfigItem(warning=...)` dialog on a text field runs on
  every focus loss of the field, changed or not (`ConfigPanel.createTextField` → `changeConfiguration`). The
  warning is now on a separate opt-in checkbox "Allow sending to this server" (asked once, on that click),
  remembered per URL host in the hidden key `consentHost`; a different host unticks it. See `Consent`.

Still unverified in game: everything else in the checklist below, in particular items 3–18, the fixes above,
and the consent checkbox flow.

## In-game verification checklist

Ticked items were confirmed in the first in-game run.

Run `.\gradlew.bat run`, set Server URL and Token, keep the server's log or the app open. Ordered so the first
ten minutes cover the most. "Look at" names the class to check when it is wrong.

1. [x] **Login.** (confirmed except the event order fix and ironman types) Within a second of the first tick in game: a request with a `login` event, `account.hash` a decimal
   string, correct `rsn`, `type` (ironman types!) and `world`; then skills, inventory and equipment snapshots
   within ~3 s; quests and diaries at ~5 s; slayer/kill counts/combat achievements/timers within 2–5 s.
   Look at `SessionTracker`, `SkillsCollector`, `ContainerCollector`.
2. [x] **Live status, standing still.** (values confirmed; the 30 s refresh not checked) One `status` per change at most per second; an unchanged one every 30 s; HP
   and prayer `cur/max`, run energy and special attack 0–100 (check against the orbs). Look at `StatusCollector`.
3. [ ] **Location and plane.** (outdoor tile confirmed; plane change and instance not checked) Walk; climb stairs: `plane` changes, x/y match the world map. Enter an instance
   (e.g. a boss or minigame): `instanced: true`, x/y are the real-world tile. Look at `LocationReader`.
4. [ ] **World hop.** `status` with the new world arrives at once; no logout/login events; later requests carry the
   new `account.world`. Look at `StatusCollector.offer`, `SessionTracker.onGameTick`.
5. [ ] **Boat (sailing).** `onBoat: true`, x/y follow the boat on the real map. Look at `LocationReader`.
6. [ ] **House.** Walk in through your house portal: `house: true` and x/y at that portal. Teleport in by tab: `house:
   true` with template coordinates (known limit). Check `instanced` is true. Look at `HouseTracker`.
7. [x] **Inventory and equipment.** (initial snapshot confirmed; a gear change not checked) Change gear: `container` equipment 2 s later with correct `equipSlot` names; noted
   items have the un-noted id; `ge`/`ha` look right. Look at `ContainerCollector`, `ItemMapper`.
8. [ ] **Bank.** Open the bank: `container` bank about 5 s later, no placeholders or fillers; close it: sent at once.
   Also seed vault and looting bag. Look at `ContainerCollector`.
9. [ ] **A kill with loot and kill count.** Kill a boss with a kill-count message (or Barrows): `loot` within ~1 s,
   `source` name/id/combat level, `killCount` matching the chat; one event per kill (also with Loot Tracker
   turned off: NPC kills only). Raids/clues/chests need the Loot Tracker on. Look at `LootCollector`.
10. [ ] **XP and a level-up.** (initial snapshot confirmed) `skills` 3 s after XP stops (≤ every 10 s while training); `level_up` once with the
    real level; no level-ups after login or hop. Look at `SkillsCollector`.
11. [ ] **Death.** `death` with real location (also in an instance / on a boat) and the NPC killer; PvP killer has
    no name. Look at `DeathCollector`.
12. [ ] **Collection log.** Open your own log: a `collection_log` with `items`, `names`, `obtained`, `total` matching
    the log header; opening again within 500 ticks sends nothing; a house adventure log never sends. Get a new
    log item: `clog_item` (needs the in-game chat notification). Look at `CollectionLogCollector`.
13. [x] **Quests and diaries.** (snapshots confirmed; diary tiers not checked against the journal) Quest states match the quest list; `questPoints` correct; a Karamja tier and a
    claimed/unclaimed tier show the right `complete`. Look at `QuestDiaryCollector`.
14. [ ] **Farming patch and bird house.** (EMPTY patches observed; planting not checked) Plant a herb (stand by the patch a tick): within 30 s a `timers` entry
    `farming:herb:<place>` GROWING with a plausible `readyAt`; with Time Tracking off it still appears (own
    observation). Bird houses on Fossil Island: `birdhouse:*` GROWING, ready 50 min after seeding.
    Look at `timers/TimersCollector`.
15. [ ] **Slayer.** (no-task snapshot confirmed) Get/kill on a task: `slayer` with name, remaining, initial, location (Konar), points and streak;
    a boss task resolves its name. Look at `SlayerCollector`.
16. [ ] **Kill counts / combat achievements.** (empty/zero snapshots confirmed) After a boss kill, `kill_counts` with display names (needs Chat
    Commands on); `combat_achievements` points and completed match the CA interface. Look at
    `KillCountCollector`, `CombatAchievementCollector`.
17. [ ] **Offline and errors.** Stop the server: no status queued, events retried at 5, 10, 20, 40, 60 s; restart:
    queued events arrive, newest status within a second. Wrong token: one chat warning, nothing more sent.
    Logout: `logout` arrives; close the client: final request still arrives. Look at `EventSender`.
18. [ ] **Excluded world.**
19. [ ] **Session events (after the fix).** Log in: exactly one `login`. Walk across several regions, hop
    between normal worlds, switch the plugin off and on: no further `login`/`logout`. Paste a new token: one
    `login` about 2 s later, no `logout`. Log out: one `logout`. Look at `SessionTracker`, `TrackerSyncPlugin`.
20. [ ] **Consent.** With URL and token set and the box unticked: nothing sent, one chat hint. Tick the box:
    RuneLite's notice once, then sending starts; retyping the token or restarting the client does not ask
    again; change the URL to another host: the box unticks and sending stops. Look at `Consent`,
    `TrackerSyncPlugin.applyServerConfig`. On a seasonal/deadman/etc. world nothing is sent. Look at `SessionTracker`.

Also check: the plugin's Data toggles stop each group; turning one back on resends that group's snapshots.

## Known limits

- Combat achievements: only `points` and `completed`; no tiers or task list (needs cache struct ids not in gameval).
- House location after a teleport is the room template tile (no verified portal table per house location).
- Farming: the farm tick offset is used only if RuneLite's Time Tracking plugin learned it; patches never seen are
  not listed; compost bins "filling" are reported EMPTY.
- Rune pouch and other storages (potion storage, group storage, quiver, etc.) are not sent.
- Diary task counts (`done`/`total`) are not sent; completion = "all tasks done" or "reward claimed" varbit.
- Kill counts depend on RuneLite's Chat Commands plugin; raid/chest/clue loot on the Loot Tracker plugin.
- Collection log `clog_item` depends on the game's chat notification; its `id` only when the name was seen recently.
- Timers list every patch the player has stood next to, also EMPTY ones (e.g. Lumbridge hops and tree after
  walking past). The app may want to hide EMPTY entries; the plugin could also omit them (owner's call).
- A plugin stop/start resumes without a new `login`; if the player logged out and back in with the plugin
  switched off, that new login is not announced either (no game events reach a stopped plugin).
- Existing installs must tick "Allow sending to this server" once (the consent box is new and off by default).
- Plugin Hub submission: the reviewers' standard third-party warning text is on the consent checkbox; check
  whether the hub manifest (`runelite-plugin.properties`) also needs a `warning=` line at submission time.
- Status-code handling beyond the contract (408/429 retried, other 4xx dropped, queue kept on 401) is described in `docs/PROTOCOL.md`.

## Left undone

Combat achievement tiers/tasks; house location after teleport; learning the farming tick offset; rune pouch and
other storages; diary task counts; in-game re-check of the session-event and consent fixes, and the rest of
the checklist.
