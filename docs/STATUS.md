# Status (2026-11-15)

## Owner decisions (2026-11-14)

- This plugin is the only data source for the tracker. Dink and reading other plugins' saved data were
  rejected as sources. (Three RuneLite **core** plugins' persisted data are read: Chat Commands'
  kill counts, Time Tracking's farming observations, and Loot Tracker's stored loot totals (`loot_history`,
  added 2026-11-14 at the owner's request, as Mystix imported them). The owner accepted reading core plugins'
  data; the rejection is about Plugin Hub plugins such as Bank Memory or Loot Logger.)
- Target: "Mystix and the Group Ironmen Tracker combined" (account tracking plus a live group view and map).
- Rune pouch and other storages, and saved gear loadouts, were first not chosen; later on 2026-11-14 the owner
  asked for Mystix's feature set in full, starting with every storage, deposit-box deposits and saved gear
  loadouts. Loadouts are read from the Inventory Setups hub plugin's saved setups (config group
  `inventorysetups`): the owner chose this explicitly, knowing it reads that plugin's data (an exception to
  the rule above). Bags are not read from other hub plugins' data (e.g. "Charges Improved").
- Two projects: this plugin (folder `tracker-sync-plugin`, repository `mrdathh/group-companion-plugin`) and
  `group-companion-server` (server, phone app, contract; repository `mrdathh/group-companion-server`).
- Local use first; the owner hosts a server later. Eventual idea: players self-host the server and this
  plugin is the connector.
- Plugin Hub submission comes later and needs a public BSD-2 repository (`mrdathh/group-companion-plugin` is public
  since 2026-11-15).
- Gear tools (best owned gear, loadout comparison, upgrade suggestions, item catalogue) are deferred by the owner
  (2026-11-14). If they come back, the agreed source is a plugin-sent catalogue from RuneLite's bundled item stats,
  and no DPS calculator. Findings from the stopped attempt: `ItemManager.getItemStats(id)` comes from RuneLite's
  static `stats.ids.min.json`, loaded asynchronously at client start (returns null until loaded, for noted items and
  for items without data); it has slot, two-handed, the attack/defence bonuses, strength, ranged strength, magic
  damage, prayer and attack speed, but no weapon category, attack styles or requirements. Equip requirements are not
  reliably readable: item params 434/436 (and 435/437) look like skill/level pairs but on some items hold the level
  to make the item, so they were not to be used. Enumerating items: `client.getItemCount()` ids, in slices.
- Goals and roadmaps live in the phone app only. The plugin is a pure sender: it never reads data from the
  server (beyond the HTTP status of its own requests), has no in-game goal overlay or side panel, and never
  contacts anything but the player's configured server. Outside data (the owner allowed OSRS Wiki drop rates)
  is fetched by the server.

## Rename to Group Companion (owner decision 2026-11-15)

- The project was renamed from "Tracker Sync" to **Group Companion**: every name a person sees (plugin display name,
  descriptor, config descriptions, chat messages, side panel title and tooltip, log lines, docs) and the GitHub
  repositories (`mrdathh/group-companion-plugin`, now public, and `mrdathh/group-companion-server`).
- Deliberately unchanged so nothing breaks: the folder name `tracker-sync-plugin`, the Java package `com.osrstracker`,
  class names (`TrackerSync*`), the config group `trackersync` and every config key (saved URL, token and consent carry
  over), the hidden `clientId`, the `X-Plugin-Version` header and the wire format. The plugin sends no User-Agent of
  its own (OkHttp/RuneLite default), so there was no name to change there. Gradle's `rootProject.name` stays
  `tracker-sync` (build-only name of the jar; RuneLite and the hub do not show it).
- Plugin Hub name check (2026-11-15, `runelite/plugin-hub` `plugins/` and the 1.13.6 hub manifest): no plugin is
  called "Group Companion" or `group-companion`. Close names: "Group Iron Companion" (`group-iron-companion`, a Group
  Ironman tracker with a dashboard), "GIM Companion" (`gim-companion`, shares GIM data with its site), "Group Ironmen
  Tracker In-Game Companion", and "OSRS Companion" (syncs to a third-party site). Reviewers may flag the closeness to
  "Group Iron Companion". Suggested internal hub name when submitting: `group-companion`.

## Honest state

- The Group Companion side panel (owner request 2026-11-15) only copies the plugin's own settings (the token and an app
  login link `<server origin>/#token=<token>`) and shows whether URL, token and consent are set. It reads nothing from
  the server, shows no goals, never displays or logs the token, and can be hidden ("Show side panel"). It does not
  change the pure-sender rule.
- `./gradlew --no-daemon clean build` passes on JDK 11: 241 unit tests, 0 failures, no javac warnings.
- **Slayer hub fields (2026-11-19, branch `slayer-hub`, merged into main; deploy the server first, its schema migration 18), built and unit-tested, not yet run in the game.** `slayer`
  carries `blockedByMaster` (every master's block list, 7th diary slot included) and `stored` (the Task Storage slot,
  captured while the Rewards > Tasks tab is drawn; `null` when seen empty), and `blocked` uses Turael's list for
  Spria. Optional fields: an older server ignores them. Checklist item 43.
- **Kingdom stored resources and choices (owner request 2026-11-15), built and unit-tested, not yet run in the game.**
  `kingdom` also carries `approvalPoints` (0-127), `resourcePoints` (varbit `MISC_RESTOTAL_LOWBITS`: bits 0-17 of the
  same player var as the approval, 18 bits = the game's 262,143-point store, so very likely the points gathered
  since the last collection), and the hardwood (`MISC_RAREWOOD_TYPE` 0 mahogany / 1 teak / 2 both), herb/flax
  (`MISC_HERBS_OR_FLAX`) and raw/cooked (`MISC_COOKED`) choices, decoded from the game's `misc_both_manage` interface
  scripts. The server turns them into a predicted stock, a planner and alerts (server `docs/PROTOCOL.md` "Kingdom").
  Optional fields: an older server strips them. Checklist item 32.
- **Collection log page kill counts without opening pages (owner request 2026-11-15), built and unit-tested, not yet
  run in the game.** The game's page header (cache script `collection_category_count`, 2735, rev 242) shows generic
  vars the server fills only for the page being opened, so the header itself cannot be read for unopened pages. But
  the same totals live in per-boss player vars (`TOTAL_*_KILLS`, `TOTAL_COMPLETED_*`, `COMPLETED_CLUES*`) that other
  client scripts read at any time (combat achievements' boss kill counts, the gravestone fee, the clue stats), so the
  client holds them for every page. `ClogPageVars` maps 70 pages (83 header lines) to those vars with the header's
  exact labels; `CollectionLogCollector.emitVarPages` sends them as ordinary `clog_page` events ~3 s after login and
  2-5 s after one of them changes (counts above 0 only, only pages that changed since last sent this session). No wire
  change. If an opened page ever shows a different number than its var, that label is taken from the page only for
  the rest of the client run (info line "... its player var holds ..."). This replaces the kill-count varp diagnostic
  line (old checklist item 41).
- **Server side (group-companion-server main, 2026-11-15):** drop luck, approximate loot and the merged collection log
  are on the server's main; no further plugin change is needed for them. Drop luck uses the `loot` events'
  `source.id`, `source.combatLevel` and `killCount`; approximate loot (loot played without this plugin) comes from
  the difference between two `loot_history` snapshots; the app's collection log merges `collection_log`, `clog_item`
  (name-only resolved by the server) and `collection_log_summary`. The owner's running server needs a rebuild and
  restart to have them (migrations 8 and 9, then the first OSRS Wiki import).
- **Contract check (2026-11-15):** the models and `ProtocolJsonTest` match the server's zod schemas. One real
  mismatch, fixed here: `loadouts` were sent uncut, but the server rejects (400, the whole request with it) more
  than 500 loadouts, names over 200 characters, notes over 2,000, or more than 50 equipment / 100 inventory / 10
  rune pouch items. `LoadoutCollector.fitToServer` now cuts setups to fit (test `cutToTheServersLimits`).
- **Collection log rework (2026-11-15), built and unit-tested, not yet run in the game.** The owner's logs showed the
  whole-log read probably never captured anything: the Group Ironmen Tracker (enabled in the same client, registered
  first) also opens the log's search when the log opens, so the item draws (script 4100) came before this plugin was
  listening, and this plugin's own click then likely toggled the search off. Now collection starts when the log is set
  up (script 7797, pre-fire, before any plugin reacts), every draw is taken whoever opened the search, this plugin
  opens the search only if nothing was drawn by the next tick and the search varbit (`FLOATER_IS_SEARCHING`) is off,
  and never toggles it off. The first read of a session is not rate-limited. An info line "collection log read: N
  items, X/Y obtained" on success; debug lines name every skip reason. New: `collection_log_summary` at login (counts
  readable without opening the log), new items also from the game's popup (deduplicated with the chat line), and the
  Gauntlet / Corrupted Gauntlet / Fortis Colosseum kill counts attach to their loot.
- **Restart the developer client to pick up the latest work.** After the rename the plugin shows up as "Group
  Companion" (settings carry over: same config group). Built and unit-tested but never run in the game:
  the collection log page kill counts (`clog_page`, 2026-11-15, checklist item 40) and the same counts read from
  player vars without opening pages (item 41),
  the activity signals in `status.activity` for the app's idle alerts (input idle / logout estimate, combat end,
  action idle; 2026-11-15, checklist items 37-39),
  the Group Companion side panel (copy token / copy app login link, 2026-11-15), the loot history import (`loot_history`, now trimmed to the server's limits and sent in a request of its own) and
  the new loot debug logging. Storages, loadouts, kingdom, diaries
  etc. reached the server in the owner's run (2026-11-14); no live `loot` has been seen yet because the owner had
  not killed anything since installing.
- First in-game run on 2026-11-14 (see below): the basics work against the real game; most of the checklist is
  still unverified. The session-event bug and the repeated IP-address warning dialog are fixed but not yet
  re-checked in game.
- First commit on `main`, pushed to the private GitHub repository `mrdathh/tracker-sync-plugin` (2026-11-14); renamed to
  `mrdathh/group-companion-plugin` and made public on 2026-11-15.

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
9. [ ] **A kill with loot and kill count (first live kill).** In the client log (developer mode logs debug lines)
   look for "Group Companion loot: LootReceived …", "held …", "emit …" and once "Group Companion: first loot event of
   this session queued"; a "server rejected … (loot…) with HTTP 400" warning means the server refused it. Kill a boss with a kill-count message (or Barrows): `loot` within ~1 s,
   `source` name/id/combat level, `killCount` matching the chat; one event per kill (also with Loot Tracker
   turned off: NPC kills only). Raids/clues/chests need the Loot Tracker on. Look at `LootCollector`.
10. [ ] **XP and a level-up.** (initial snapshot confirmed) `skills` 3 s after XP stops (≤ every 10 s while training); `level_up` once with the
    real level; no level-ups after login or hop. Look at `SkillsCollector`.
11. [ ] **Death.** `death` with real location (also in an instance / on a boat) and the NPC killer; PvP killer has
    no name. Look at `DeathCollector`.
12. [ ] **Collection log (reworked 2026-11-15).** Keep the Group Ironmen Tracker **on** (the case that failed).
    a) **Summary at login:** within ~3–10 s of logging in, a `collection_log_summary` whose `obtained`/`total` match
    the log header, `tabs` matching each tab's counts (Bosses, Raids, Clues, Minigames, Other), `recent` = the log's
    recent items with plausible dates (`at` = the day, 00:00 UTC; compare with "today"/"N days ago" in the log), and
    `newItemNotification` matching Settings → "Collection log - New addition notification" (switch it between the
    options once and check each: `off`, `chat`, `popup`, `both`; **the popup/both values 2/3 are inferred**). If no
    summary arrives, look for debug lines "Collection log summary: counts not loaded yet".
    b) **Whole log:** open your own log once: the client log shows the info line "Group Companion: collection log read:
    N items, X/Y obtained" and a `collection_log` arrives with `items`/`names`/`obtained`/`total` matching; the
    search box must not end up toggled off by the two plugins. With the Group Ironmen Tracker off, the same works
    (this plugin opens the search itself). Opening again within 500 ticks sends nothing (debug "last read N ticks
    ago"); a house adventure log never sends (debug "house adventure log"). If nothing arrives, the debug lines
    "Collection log read skipped: …" say why.
    c) **New item:** with the setting on "chat", a new log item sends one `clog_item`; on "popup" also one; on "both"
    still exactly one. `id` only when the item was in recent loot/containers, otherwise name only. The summary
    updates a few seconds later (new count, new recent item). Look at `CollectionLogCollector`.
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

21. [ ] **Rune pouch and quiver.** With a rune pouch / Dizana's quiver: `container` rune_pouch (runes and amounts
    per slot) and quiver (ammo and amount) after login and ~2 s after casting / firing. Look at `StorageCollector`.
22. [ ] **Plank sack.** Fill it: `container` plank_sack with the plank counts. Look at `StorageCollector`.
23. [ ] **Potion storage.** Open the bank with potions stored: `container` potion_storage, 1-dose ids, `qty` =
    doses (compare with the potion store), `unit: "dose"`; withdraw doses: updated. Look at `StorageCollector`.
24. [ ] **Group storage (group ironman).** Open the shared storage, move items, close: one `container`
    group_storage when "Saving..." shows; opening without changes sends nothing. Look at `StorageCollector`.
25. [ ] **Real bag containers.** Open/check a seed box, tackle box, forestry kit, huntsman's kit, a boat cargo
    hold: `container` with that name (`estimated` absent). Look at `ContainerCollector`.
26. [ ] **Chat-tracked bags.** "Check" a fish barrel, log basket, gem bag, coal bag, herb sack: a `container` with
    the listed items and no `estimated`; then "Fill" from the inventory: updated with `estimated: true`; with an
    open bag gathering: nothing sent until the next Check. Confirm the check text formats match
    (`BagCollector` patterns came from another plugin, unverified). Look at `BagCollector`.
27. [ ] **Deposit box.** Deposit an item, the inventory, worn items and a Deposit-X amount at a deposit box: one
    `bank_deposit` each with the removed items within 2 s; normal bank deposits send none. Look at `DepositCollector`.
28. [ ] **Loadouts.** With Inventory Setups setups saved: `loadouts` ~3 s after login with names, equipment
    (`equipSlot`), inventory slots, rune pouch and notes; edit a setup: updated within ~5 s; no setups: nothing.
    A setup with very long notes still arrives (notes cut to 2,000 characters).
    Look at `LoadoutCollector`.

29. [ ] **Diary tasks.** Open the diary journal for a region: `diaries` tasks in the same order and names, the
    done ones matching the green entries, `done`/`total` right; complete one task: update within ~5 s.
    Look at `DiaryTasks`, `QuestDiaryCollector`.
30. [ ] **Combat achievement tasks.** ~5–10 s after login: `combat_achievements` with `tiers` (done/total per tier as
    in the CA interface) and `tasks` (names, tiers, monsters); no hitch while loading. Look at
    `CombatAchievementCollector` (cache ids from another plugin; if `tasks` is missing, the cache layout differs).
31. [ ] **Slayer detail.** `slayer.master` names the master, `wildernessStreak`, `blocked` matches the block list in the
    rewards interface for the current master, `unlocks` matches purchased unlocks/extensions. Look at
    `SlayerCollector` (unlock `bit` column read as a bit index: verify).
32. [ ] **Kingdom.** With Throne of Miscellania done: `kingdom` approval (compare with the in-game %) and
    `approvalPoints`, coffer, workers per resource, royalTrouble. Check whether approval, coffer and `resourcePoints`
    change while away without visiting (log in elsewhere on a later day: same values = the game updates them only on a
    visit). **Stored resources:** before collecting, compare the app's Kingdom screen ("In the kingdom now") with what
    Advisor Ghrim offers; `resourcePoints` should drop to 0 right after collecting, and grow by about 1,143 a day at
    100 % approval and a full coffer after Royal Trouble (762 before). **Choices:** switch hardwood (mahogany / teak /
    both), herbs/flax and the cooked-fish toggle in the manage interface and confirm: `hardwood`, `herbs`, `fish` follow
    (note whether they change already while the interface is open, before confirming). Look at `KingdomCollector`.
33. [ ] **Tears of Guthix.** Talk to Juna and play: `timers` entry tears_of_guthix GROWING with readyAt 00:00 UTC seven
    days after the visit day; before any visit seen: UNKNOWN. Look at `timers/TimersCollector`.
34. [ ] **Group name (group ironman).** Requests carry `account.group` with the group's name within a few ticks of
    login (the first request may lack it); no second `login`; players without a group have no `group`. Look at
    `SessionTracker.readGroup`.
35. [ ] **Loot history import.** ~10 s after login: one `loot_history` with the sources and totals the Loot
    Tracker panel shows (kills, items); PvP sources merged as "Player"; after a kill, a new snapshot within a few
    minutes of RuneLite's config sync (or at logout). Log line "loot history with N sources, B bytes".
    A history over the server's limits sends the newest sources with one info line "left out N of M sources and
    I of J items". Look at `LootHistoryCollector`, `LootHistoryLimiter`.
36. [ ] **Side panel.** The Group Companion icon in the RuneLite sidebar opens a panel: "Copy token" puts the token on
    the clipboard (label shows "Copied" for 2 s); "Copy app login link" gives `https://<server>/#token=…` that signs
    the app in; both disabled without a token; the status line matches the settings; the token is not shown anywhere;
    "Show side panel" off removes the icon. Look at `TrackerSyncPanel`.
37. [ ] **Idle logout estimate.** Stand somewhere safe and do not touch mouse or keyboard: about 10 s later one
    `status` has `activity.inputIdleSince` (≈ when you let go) and `logoutAt` ≈ 5 min after it (later if a plugin
    such as Logout Timer raised the client's idle timeout). Note the real logout time and compare with `logoutAt`.
    Moving the mouse over the game clears both in the next status. Repeat while an NPC keeps hitting you (e.g. a
    low-level monster): `logoutAt` moves to ≈ 20 min after your last click or key press. Also check that nothing in
    `activity` changes while you stay idle (the 30 s refreshes repeat the same values). Look at `ActivityTracker`.
38. [ ] **Combat end.** Attack an NPC: `activity.inCombat` true, `combatEndedAt` null. Kill it and stand still:
    about 5 s after the last hitsplat `inCombat` false and `combatEndedAt` set. Being hit without a target (or a
    splash on you) also counts as combat.
39. [ ] **Action idle while skilling.** Chop a tree / fish / smith for a minute: `activity.actionIdleSince` stays
    null through the short pauses between actions. Stop: about 3 s later it is set (to the moment you stopped);
    walking a tile clears it. Note any skill where it flickers while still working (a gap of 3 s or more without an
    animation, movement or target).
40. [ ] **Collection log page kill counts.** Open your collection log, then open a few boss pages (e.g. Royal
    Titans, Dagannoth Kings with its three counts, a raid). One tick after each page shows, one `clog_page` with the
    tab, the page name and the header's counts; opening the same page again sends nothing until a count changes.
    Check the kill counts in the app's luck view (the highest of the log page, the Loot Tracker and Chat Commands).
    The page shown when the log first opens is skipped if the whole-log read runs then; click it again. Also check:
    clicking a page while the log's search is on (after the whole-log read) still sends it, i.e. the game turns the
    search off when a page is chosen; if not, close and reopen the log. Nothing from a house adventure log. Debug lines
    "Collection log page not read: ..." name the reason. Look at `CollectionLogCollector.readPage`.
41. [ ] **Page kill counts without opening pages.** Log in and do **not** open the collection log. About 3-5 s
    later the client log has one info line "Group Companion: collection log kill counts read without opening pages: N
    pages with counts, M sent" and the server has one `clog_page` per page you have a count on (bosses, raids, clue
    tiers, Gnome Restaurant), e.g. Royal Titans "Royal Titan kills", Dagannoth Kings with its three counts, Chambers of
    Xeric with normal and CM. Compare a few with the log pages / `!kc`; the app's luck view should show them without
    any page opened. Kill a boss: one `clog_page` for that page 2-5 s later. Then open some of those pages: nothing is
    sent when they match; an info line "... shows ... but its player var holds ..." means a var is wrong for that line
    (report it with the page name). Check especially: Brutus, the three "(Rare)" clue pages (same count as their
    tier?), Gnome Restaurant, Corrupted/normal Gauntlet, Theatre of Blood and Tombs of Amascut entry/expert. If N is 0
    for an account with boss kills, the vars do not reach the client at login. Look at `ClogPageVars`,
    `CollectionLogCollector.emitVarPages`.
42. [ ] **Joining with a join code.** Needs a server with migration 16 and a join code (`npm run user -- join-code
    new`). In the plugin: Server URL, the **Join code**, Token **empty**, consent ticked. Within ~2 s the Token field
    fills with an `osrs_…` value (secret field; "Copy token" in the side panel copies it). Log in: the first request
    carries `X-Join-Code` and the server's `npm run user -- list` shows a new user named after the character with
    `joined` after the date. "Copy app login link" opens the app signed in as that user, with this character under
    your own characters. Restart the client: the token stays the same (no second user). A wrong join code: one chat
    line "rejected the token or the join code" and nothing more is sent; fixing the code resumes without a second
    `login`. With the server at its user cap (`JOIN_MAX_USERS`): the chat line "not taking new members". Look at
    `TrackerSyncPlugin.applyServerConfig`, `JoinToken`, `EventSender.onResult`.
43. [ ] **Slayer hub: every master's block list, Task Storage, Spria.** (2026-11-19, branch `slayer-hub`)
    a) Block a task (or just view the block list) at two different masters, e.g. Duradel and Konar: the next `slayer`
    has `blockedByMaster` with both lists matching each master's Rewards > Tasks block list, every other master
    present (`[]` when empty), and no `Spria` key. If you have a 7th (diary-reward) block slot, the task in it is
    listed too, also in `blocked` for the current master.
    b) Open a slayer master's Rewards > Tasks tab with a task in Task Storage: within ~2 s a `slayer` with `stored`
    `{name, amount, seenAt}` matching the tab (a boss task by its boss name). Store or take the task out and open the
    tab again: `stored` follows, and is `null` when the slot is empty. Before opening the tab in a session `stored` is
    absent. Note whether a stored boss task shows an amount (if the tab shows no amount the plugin keeps the old
    value). Other interfaces (bank, shops) must not change `stored`. Look at `SlayerCollector.captureStored`.
    c) With Spria as the current master (task from Spria in Draynor): `blocked` shows Turael's block list.

Also check: the plugin's Data toggles stop each group; turning one back on resends that group's snapshots.

## Known limits

- Combat achievements: the task list comes from cache ids found in another Plugin Hub plugin (not in gameval); if the
  layout changes, `tiers`/`tasks` are left out. The list is read once per client run.
- House location after a teleport is the room template tile (no verified portal table per house location).
- Farming: the farm tick offset is used only if RuneLite's Time Tracking plugin learned it; patches never seen are
  not listed; compost bins "filling" are reported EMPTY.
- Bags tracked from chat (fish barrel, log basket, gem bag, coal bag, herb sack): the "Check" text formats are
  taken from the Mystix plugin and unverified; gathering straight into an open bag is not counted (after an
  open bag was carried nothing is sent until the next Check); nothing is sent for a bag before its first full
  read of a session; the bank's "empty containers" message empties every bag carried.
- Not covered: essence pouches (varbits exist, not requested), bolt pouch, master scroll book, flamtaer bag and
  other items whose contents the game shows only in their own way; Inventory Setups' bolt pouch, quiver and
  "additional filtered items" are not sent.
- Rune pouch, quiver and plank sack are sent once after every login, also when empty.
- Group storage only when the player saves a change ("Saving..."); viewing it, or another member's changes,
  send nothing until the player saves.
- Potion storage needs the bank open; the potion store's var trigger is learnt the first time it is drawn.
- Diary tasks come from bundled data (Group Ironmen Tracker site, BSD-2); a task added by a game update is missing
  until the data is refreshed. Completion = "all tasks done" or "reward claimed" varbit.
- Slayer: `blocked` is the current master's list (Spria (9) uses Turael's, as the wiki says she shares it);
  `blockedByMaster` has every master with block varbits, each with its 7th diary-reward slot (Mortimer: two slots).
  Master names 1–9 are from the OSRS Wiki's varbit table (facts only), 10 = Mortimer from RuneLite core.
- Slayer Task Storage (`stored`): only seen while a slayer master's Rewards > Tasks tab is drawn (scripts 328/421,
  IF4 amount / IF5 task id / IF6 boss sublist, accepted only when IF1/IF2 equal the live task varps); kept in memory
  for the session, forgotten at logout or plugin stop, so it is absent until the tab is opened once per session. The
  script ids, the IF varp layout and the boss slot's amount -1 come from the Mystix plugin, unverified in game.
- Kingdom: workers are named Wood, Herbs/flax, Fishing, Mining, Hardwood, Farming; the hardwood, herb/flax and
  raw/cooked choices and the stored resource points (`MISC_RESTOTAL_LOWBITS`, 18 bits) are sent since 2026-11-15, read
  from varbits whose meaning comes from the game's interface scripts and names, not yet checked in game. Whether the
  kingdom vars change while away is not known (see checklist 32).
- Tears of Guthix: only visits seen by this plugin count (visit time kept in the plugin's RS-profile config); the
  quest-point / XP requirement for the next visit is not modelled.
- Loot history depends on RuneLite's Loot Tracker plugin: with it disabled nothing new is stored (the old records
  are still sent); its "Remember loot" setting only controls loading into its panel and pruning records older than
  365 days, not saving. The server rejects a snapshot over 2,000 sources, 400 items in one source or 50,000 items
  (400) and bodies over 2 MiB (413), so `LootHistoryLimiter` trims instead of skipping: per source the 400 most
  valuable items (qty × GE price, then quantity), then the newest sources (by `lastAt`; none = oldest) while within
  those counts and 1,500,000 bytes of `data`; one info line says what was left out. `loot_history` is always sent in
  a request of its own (`EventType.isSolo`), so the body is the snapshot plus a small envelope. Left-out sources lose
  their history on the server (all-time views fall back to tracked loot for them). A 600-source × 25-item account
  measured 1.33 MB in tests (nothing trimmed); about 680 such sources would start trimming. Trimming has only been
  checked against generated records, not a real account's Loot Tracker data.
- Kill counts depend on RuneLite's Chat Commands plugin; raid/chest/clue loot on the Loot Tracker plugin.
- Collection log page counts from player vars (`ClogPageVars`) cover 70 pages. Still only from an opened page (no
  var the client is known to hold for that header line): Shared Treasure Trail Rewards (total clues), Revenants,
  Glough's Experiments (gorillas), Tormented Demons, Barbarian Assault (gambles), Hallowed Sepulchre, Last Man
  Standing, Soul Wars, Guardians of the Rift, Giants' Foundry, Hunter Guild (rumours), Mastering Mixology, Doom of
  Mokhaiotl, and the second lines of Wintertodt ("Rewards claimed") and Tempoross ("Reward permits claimed"). Some of
  these have candidate vars in the cache (e.g. `TOTAL_GOTR_KILLS`, `TOTAL_MIXOLOGY_ORDERS`, `SOUL_WARS_*`,
  `TOTAL_DOM_LEVELS`) but no client script reads them, so whether the client gets them and what they count is
  unknown. The page list and labels follow cache rev 242; a page added later comes only from opening it until the
  table is extended (a removed one is skipped).
- Loadouts are cut to the server's limits: the first 500 setups in Inventory Setups' order, names (also the id) at
  200 and notes at 2,000 characters, the first 50 equipment / 100 inventory / 10 rune pouch items in slot order.
- Collection log `clog_item` depends on the game's notification setting (chat line or popup; "off" sends nothing);
  its `id` only when the name was seen recently (the server resolves name-only items).
- `collection_log_summary`: the per-tab and recent-item varps are assumed to arrive with the other player vars at login
  (no client script writes them; Dink polls the total the same way) but this is unverified in game; while obtained and
  total read 0/0 the plugin retries for about 2 minutes and then sends nothing until one of them changes. Recent-item
  dates are day precision: the game stores a day number (the log shows "N days ago" = current day number minus it),
  converted to a date with the game's current day number against today's UTC date; near midnight UTC the date can be
  a day off if the game's day does not roll at 00:00 UTC. The notification setting values 2 (popup) / 3 (both) are
  inferred from the option order; 0 = off and 1 = chat are as RuneLite's Screenshot plugin and Dink read them.
- Whole-log read: the search varbit used to decide "already on" (`FLOATER_IS_SEARCHING`) was found in the game's
  scripts (set by the log's search-open script), not tested in game. A read with fewer than half the obtained count
  drawn is treated as partial and not sent.
- Loot kill counts are matched by name: chest-style sources checked against their count lines: Barrows, Wintertodt,
  Tempoross, Lunar Chest, Fractured Archive, raids (all modes), Gauntlet (via the Hunllef aliases) and Fortis
  Colosseum ("Sol Heredit", alias from memory, unverified). Not checked (may not attach a count): Grotesque Guardians
  (loot may be filed as "Dusk"), Royal Titans (loot under a titan's name?), Hallowed Sepulchre ("Hallowed Sack"),
  Doom of Mokhaiotl.
- Timers list every patch the player has stood next to, also EMPTY ones (e.g. Lumbridge hops and tree after
  walking past). The app may want to hide EMPTY entries; the plugin could also omit them (owner's call).
- A plugin stop/start resumes without a new `login`; if the player logged out and back in with the plugin
  switched off, that new login is not announced either (no game events reach a stopped plugin).
- Existing installs must tick "Allow sending to this server" once (the consent box is new and off by default).
- Plugin Hub submission: the reviewers' standard third-party warning text is on the consent checkbox; check
  whether the hub manifest (`runelite-plugin.properties`) also needs a `warning=` line at submission time.
- Status-code handling beyond the contract (408/429 retried, other 4xx dropped, queue kept on 401) is described in `docs/PROTOCOL.md`.

## Next steps

1. Joining with a join code (branch `self-join`, 2026-11-19): deploy the server first (migration 16), then check
   item 42. An older server ignores `X-Join-Code`, so a player with only a join code gets 401 there.
2. In-game checks of the latest work (restart the developer client first): item 12 (collection log rework: summary
   at login, whole-log read with the Group Ironmen Tracker on, new items by chat and popup), item 9 (a kill with a
   kill count: the input for the server's drop luck), item 41 (page kill counts without opening pages, then item 40),
   item 32 (kingdom: stored resources and choices against Advisor Ghrim), item 35 (loot history import; after a session played without
   this plugin, the server's Loot Today / 7 days shows it as approximate loot), item 28 (loadouts), then the rest of
   the unticked items.
3. The owner rebuilds and restarts the server so it has drop luck, approximate loot and the merged collection log;
   the first real OSRS Wiki import runs about a minute after the restart.
4. Later: the Plugin Hub submission (public BSD-2 repository exists; suggested hub name `group-companion`).

## Left undone

House location after teleport; learning the farming tick offset; essence pouches and the other items listed
above; in-game check of the kingdom's stored resources and choices (checklist 32); in-game check of everything in the checklist that is
not ticked, including the session-event and consent fixes.
