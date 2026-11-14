# Tracker Sync

A RuneLite plugin that sends your own game data to a **tracker-sync-server** that you host yourself
(a separate project: a small server plus a phone web app). The plugin is only the connector: it has no
UI of its own beyond its settings, and it talks to nothing but the server URL you enter.

## Before anything is sent

- Nothing is sent until you enter both a **Server URL** and a **Token** (the token is printed by your
  server when your user is created). The token field asks for confirmation that data will be sent to that
  URL, which RuneLite developers do not control or verify; that server also sees your IP address.
- Nothing is sent on seasonal (Leagues), Deadman, fresh-start, tournament, beta, no-save,
  quest-speedrunning or PvP-arena worlds.
- Nothing about other players is sent: PvP loot and player killers are sent without names, and the
  "interacting" target is only ever an NPC.
- The plugin never opens a network port and never reads or sends credentials.

## What it sends, and when

Exact JSON shapes and timings: [docs/PROTOCOL.md](docs/PROTOCOL.md).

| Setting (all on by default) | Data | When |
| --- | --- | --- |
| Loot | NPC kills, raid/barrows/clue/chest rewards, pickpockets: source, kill count when the game prints one, items with GE and high-alch values | Within about a second |
| Skills | XP and real level per skill, totals, combat level; level-ups | Snapshot 3 s after XP stops changing (at most every 10 s); level-ups at once |
| Items | Inventory, equipment; bank, seed vault, looting bag while open | 2 s (carried) / 5 s (storage) after a change, and when the bank closes |
| Quests and diaries | Quest states and quest points; diary completion per region and tier | 5 s after login; quests every 60 s, diaries when a diary changes |
| Collection log | New items (from the game's chat message); the whole log | New items at once; the whole log when you open your own collection log, at most every 500 ticks |
| Live status and location | HP, prayer, run energy, special attack, world, map location (also in instances, on boats, in your house), the NPC you are fighting | Every game tick, at most once per second when it changes, refreshed every 30 s |
| Timers | Farming patches and bird houses with predicted ready times | After login, then every 30 s |
| Slayer, kill counts and combat achievements | Slayer task, points, streak; boss kill counts; combat achievement points and completed task count | After login, then when they change |

Always sent while logged in: login and logout (with the world), deaths (with location and the killing NPC),
and a heartbeat every 60 s when nothing else was sent. While the server is unreachable up to 500 events
are kept and retried; live status is dropped instead.

Optional helpers: kill counts come from RuneLite's **Chat Commands** plugin (keep it on); farming timers
are best with RuneLite's **Time Tracking** plugin on as well (it remembers patches visited before this
plugin was installed and learns the farming tick offset); loot from raids, chests and clues needs
RuneLite's **Loot Tracker** plugin (without it only NPC kills are sent). New collection log items need the
in-game collection log chat notification setting.

## Settings

Configuration (wrench icon) > search "Tracker Sync":

- **Server**: Server URL (default `http://127.0.0.1:8787`; use `https://` for anything not on this PC), Token.
- **Data to send**: Loot, Skills, Items, Quests and diaries, Collection log, Live status and location,
  Timers, Slayer kill counts and combat achievements.

## Developing

Requires JDK 11. From this folder on Windows (PowerShell or cmd):

```
.\gradlew.bat build      # compile and run the unit tests
.\gradlew.bat run        # start RuneLite in developer mode with the plugin loaded
```

(`./gradlew build` / `./gradlew run` in Git Bash.)

### Logging in with a Jagex account in the developer client

From the RuneLite wiki page "Using Jagex Accounts" (checked November 2026):

1. Start the client with `.\gradlew.bat run` (it passes `--developer-mode`).
2. Click the Jagex account button in the RuneLite sidebar and choose to add a Jagex account.
3. Finish the Jagex login in the window that opens; your characters appear in the sidebar panel.
4. Select a character and click **Play Now** on the login screen.

Next time: start the client, select the character, click **Play Now**. If an older setup left a
`.runelite\credentials.properties` file, delete it; keeping it is a security risk.

## Credits and licences

BSD 2-Clause, see [LICENSE](LICENSE). Parts are ported from, or follow, other BSD-2-Clause projects;
details and notices in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md):

- RuneLite: Time Tracking farming and bird house data and prediction; Slayer task reading.
- Group Ironmen Tracker (christoabrown/group-ironmen-tracker): boat location technique; automatic
  collection log read when the log opens.
- Reimagined World Map (Maiz19/reimagined-world-map): player-owned house template area.
- The collection log search technique (script 4100 arguments) was first used by WikiSync (weirdgloop/WikiSync).
