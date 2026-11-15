# Group Companion

A RuneLite plugin that sends your own game data to a **group-companion-server** that you host yourself
(a separate project: a small server plus a phone web app). The plugin is only the connector: it has no
UI of its own beyond its settings and a small side panel, and it talks to nothing but the server URL you enter.

Formerly called "Tracker Sync"; settings saved under the old name carry over unchanged.

## Before anything is sent

- Nothing is sent until you enter a **Server URL** and a **Token** (printed by your server when your user
  is created) **and** tick **Allow sending to this server**. Ticking it shows RuneLite's standard notice
  that your IP address goes to a server not controlled or verified by RuneLite developers; you are asked
  once. The answer is remembered (also across client restarts) for that server's host: retyping or
  re-pasting the token or reopening the settings does not ask again. Changing the Server URL to a
  **different host** unticks the box, nothing is sent there until you tick it again. If URL and token are
  set but the box is not ticked, the plugin says so once in the game chat.
- Nothing is sent on seasonal (Leagues), Deadman, fresh-start, tournament, beta, no-save,
  quest-speedrunning or PvP-arena worlds.
- Nothing about other players is sent: PvP loot and player killers are sent without names, and the
  "interacting" target is only ever an NPC.
- The plugin never opens a network port and never reads or sends credentials.

## What it sends, and when

Exact JSON shapes and timings: [docs/PROTOCOL.md](docs/PROTOCOL.md).

| Setting (all on by default) | Data | When |
| --- | --- | --- |
| Loot | NPC kills, raid/barrows/clue/chest rewards, pickpockets: source, kill count when the game prints one, items with GE and high-alch values; plus the loot history RuneLite's Loot Tracker has already recorded (totals per source) | Within about a second; the history after login and when the Loot Tracker saves |
| Skills | XP and real level per skill, totals, combat level; level-ups | Snapshot 3 s after XP stops changing (at most every 10 s); level-ups at once |
| Items | Inventory, equipment; bank, seed vault, looting bag while open | 2 s (carried) / 5 s (storage) after a change, and when the bank closes |
| Quests and diaries | Quest states and quest points; diary completion per region and tier with every task and whether it is done | 5 s after login; quests every 60 s, diaries when a diary changes |
| Collection log | New items (from the game's chat message); the whole log | New items at once; the whole log when you open your own collection log, at most every 500 ticks |
| Live status and location | HP, prayer, run energy, special attack, world, map location (also in instances, on boats, in your house), the NPC you are fighting, and activity signals for the app's idle alerts (since when you gave no mouse/keyboard input and the estimated idle logout time, in combat or since when not, since when your character is doing nothing). The plugin only observes; it never moves the mouse or does anything to keep you logged in | Every game tick, at most once per second when it changes, refreshed every 30 s |
| Timers | Farming patches and bird houses with predicted ready times; Tears of Guthix weekly visit; Kingdom of Miscellania approval, coffer and workers | After login, then every 30 s |
| Slayer, kill counts and combat achievements | Slayer task, points, streaks, master, block list and purchased unlocks; boss kill counts; combat achievement points, tiers and every task with its state | After login, then when they change |
| Storages and loadouts | Rune pouch, Dizana's quiver, plank sack, potion storage (doses, while the bank is open), group storage (group ironman, when the game saves it), seed box, tackle box, forestry and huntsman's kits, boat cargo holds, trawling net; fish barrel, log basket, gem bag, coal bag and herb sack from their "Check" output and fill/empty actions (marked estimated when adjusted); deposit-box deposits; your saved Inventory Setups loadouts | When they change or the game shows them; deposits within 2 s; loadouts after login and when you edit a setup |

Always sent while logged in: login (once per real game login) and logout (on logging out or closing the
client; not when the plugin is merely switched off), deaths (with location and the killing NPC),
and a heartbeat every 60 s when nothing else was sent. While the server is unreachable up to 500 events
are kept and retried; live status is dropped instead.

Optional helpers: kill counts come from RuneLite's **Chat Commands** plugin (keep it on); loot history from
RuneLite's **Loot Tracker** plugin's saved data (it saves every few minutes, on logout and on exit); loadouts are the
setups saved by the **Inventory Setups** hub plugin (read from its saved settings); farming timers
are best with RuneLite's **Time Tracking** plugin on as well (it remembers patches visited before this
plugin was installed and learns the farming tick offset); loot from raids, chests and clues needs
RuneLite's **Loot Tracker** plugin (without it only NPC kills are sent). New collection log items need the
in-game collection log chat notification setting.

## Settings

Configuration (wrench icon) > search "Group Companion":

- **Server**: Server URL (default `http://127.0.0.1:8787`; use `https://` for anything not on this PC), Token,
  Allow sending to this server (off until you tick it), Show side panel.
- **Data to send**: Loot, Skills, Items, Quests and diaries, Collection log, Live status and location,
  Timers, Slayer kill counts and combat achievements, Storages and loadouts.

**Side panel:** the Group Companion icon in the RuneLite sidebar opens a small panel with two buttons, **Copy token**
(to paste into the app's login) and **Copy app login link** (`https://<your server>/#token=…`, opens the app signed
in on another device), plus a line showing whether the URL, the token and "Allow sending" are set. The token is never
shown. It only uses the plugin's own settings; hide it with "Show side panel".

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
- Group Ironmen Tracker also: rune pouch, quiver, potion storage, deposit-box detection, group storage commit.
- Mystix (rjmccaskell/mystix-plugin): which bags and containers to cover, bag "Check" text formats, plank sack varbits,
  slayer detail vars, Kingdom of Miscellania vars, Tears of Guthix visit detection.
- Group Ironmen Tracker site data (christoabrown/group-ironmen): diary task names and bits; cross-checked against WikiSync's
  diary spec (weirdgloop/wikisync-api, MIT).
- Combat Achievement Lookup (jimmyununu/combat-achievement-lookup): where the combat achievement task list lives in the game cache.
- The collection log search technique (script 4100 arguments) was first used by WikiSync (weirdgloop/WikiSync).
