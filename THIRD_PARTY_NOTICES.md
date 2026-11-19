# Third-party notices

This plugin contains code ported from, or closely following, the projects below. All are under the
BSD 2-Clause License, reproduced at the end of this file.

## RuneLite (https://github.com/runelite/runelite), runelite-client 1.13.6

- `src/main/java/com/osrstracker/timers/PatchImplementation.java`, `PatchState.java`, `CropState.java`,
  `Produce.java`, `FarmingPatch.java`, `FarmingRegion.java`, `FarmingWorld.java`, `BirdHouse.java`,
  `BirdHouseSpace.java`, `BirdHouseState.java`: copied from `net.runelite.client.plugins.timetracking`
  (farming and hunter); only the package, imports and one unused method were changed. Each file keeps its
  original copyright notice.
- `src/main/java/com/osrstracker/timers/TimersCollector.java`: observation and prediction logic ported from
  `FarmingTracker` (updateData, predictPatch, getTickTime) and `BirdHouseTracker` (updateData).
  Copyright (c) 2018 Abex; Copyright (c) 2018, Daniel Teo <https://github.com/takuyakanbr>.
- `src/main/java/com/osrstracker/SlayerCollector.java`: task, points and streak reading ported from
  `net.runelite.client.plugins.slayer.SlayerPlugin` (updateTask, addCounter).
  Copyright (c) 2017, Tyler <https://github.com/tylerthardy>; Copyright (c) 2018, Shaun Dreclin.
- `src/main/java/com/osrstracker/ActivityTracker.java`: own code following the idle rules of
  `net.runelite.client.plugins.idlenotifier.IdleNotifierPlugin` (checkIdleLogout's idle counters and combat
  logout, the 8-tick hitsplat combat window, the "Attack" NPC target test, animation/interaction/movement idle).
  Copyright (c) 2016-2017, Abel Briggs; Copyright (c) 2017, Kronos <https://github.com/KronosDesign>.

## Group Ironmen Tracker (https://github.com/christoabrown/group-ironmen-tracker)

Copyright (c) 2022, Christopher Brown.

- `src/main/java/com/osrstracker/LocationReader.java`: resolving the player's position through the boat's
  world entity (GroupIronmenTrackerPlugin).
- `src/main/java/com/osrstracker/CollectionLogCollector.java`: opening the collection log's search when the
  player's own log opens, the house adventure log check and the 500-tick limit (CollectionLogWidgetSubscriber);
  `clog_page`: reading the page header's count lines from child index 2, and the page name from the cache via the
  tab/page varbits 6905/6906, tab structs 471-475, param 683 (page enum) and 689 (page name) (CollectionLogManager,
  last at commit a5c1cf93c0, since removed).
- `src/main/java/com/osrstracker/StorageCollector.java`: rune pouch varbits and enum (RunePouchState), Dizana's
  quiver varps (QuiverState), potion storage enums and dose script with its var-transmit trigger
  (PotionStorageState and GroupIronmenTrackerPlugin), group storage committed on "Saving..." (DataState transactions).
- `src/main/java/com/osrstracker/DepositCollector.java`: deposit-box detection by deposit clicks / chatbox entry and
  a two-tick window, removed items by diff (GroupIronmenTrackerPlugin, ItemContainerState#whatGotRemoved).

## Collection Log Luck (https://github.com/peanubnutter/collection-log-luck)

Copyright (c) 2020, Evan Sloan; Copyright (c) 2023, github.com/peanubnutter.

- `src/main/java/com/osrstracker/CollectionLogCollector.java`: reading a page's kill counts after the game's
  `COLLECTION_DRAW_LIST` script, as "label: number" lines of the header (own code).

## Mystix (https://github.com/rjmccaskell/mystix-plugin)

Copyright (c) 2026, Mystix. BSD-2-Clause. Techniques only, own code:

- `src/main/java/com/osrstracker/BagCollector.java`: the bags covered, their "Check"/"empty" message texts and listing
  formats, and fill/empty inventory diffs (StorageItemSpec, StorageItemLedger, StorageItemMonitor).
- `src/main/java/com/osrstracker/StorageCollector.java`: plank sack count varbits (PlankSackMonitor).
- `src/main/java/com/osrstracker/ContainerCollector.java`: the extra real containers (seed box, tackle box, kits, boat
  cargo holds, trawling net) (VaultMonitor).
- `src/main/java/com/osrstracker/SlayerCollector.java`: which vars hold the master, wilderness streak, block lists and
  unlocks (SlayerMonitor); reading the Task Storage slot from the interface-transfer varps IF4-IF6 after the rewards
  Tasks tab scripts 328/421, guarded by IF1/IF2 matching the live task varps, and the empty-slot rule
  (SlayerMonitor#captureStoredTask, #hasStoredTask).
- `src/main/java/com/osrstracker/KingdomCollector.java`: the Kingdom of Miscellania varbits, approval out of 127, the
  Throne of Miscellania gate (KingdomMonitor, KingdomSyncPayload).
- `src/main/java/com/osrstracker/LootHistoryCollector.java`: importing the core Loot Tracker's stored records
  (LootMonitor bulk sync); the record format itself is RuneLite's (`ConfigLoot`).
- `src/main/java/com/osrstracker/timers/TimersCollector.java`: Tears of Guthix visit message and the 7-day reset from
  00:00 UTC (TimerMonitor, MystixPlugin).

## Group Ironmen Tracker site (https://github.com/christoabrown/group-ironmen)

Copyright (c) 2022, Christopher Brown. BSD-2-Clause.

- `src/main/resources/com/osrstracker/diary_tasks.json`: achievement diary task names (from
  `site/public/data/diary_data.json`) and the varp bit / varbit value marking each task done (from
  `site/src/data/diaries.js` with the var order of the plugin's AchievementDiaryState), regenerated into one file.
  The origin of the task texts themselves is that project's; they read like the in-game diary journal.

## WikiSync API (https://github.com/weirdgloop/wikisync-api)

Declared MIT in its `package.json` (the repository has no LICENSE file). Used only to cross-check: every diary task bit in `diary_tasks.json` matches
`src/runelite/data/achievementDiariesSpecs.json` (same var and bit/value, same order, same task count). No data from
it is shipped.

## Combat Achievement Lookup (https://github.com/jimmyununu/combat-achievement-lookup)

Copyright (c) 2026, Jimmy Ununu. BSD-2-Clause. Accepted on the Plugin Hub.

- `src/main/java/com/osrstracker/CombatAchievementCollector.java`: the cache ids of the combat achievement task list
  (tier enums 3981–3986, struct params 1306 id / 1308 name / 1312 monster index, monster-name enum 3971) and the
  completion varps layout (CombatTaskRepository, TaskTier).

## WikiSync (https://github.com/weirdgloop/WikiSync)

Copyright (c) 2021, andmcadams.

- `src/main/java/com/osrstracker/CollectionLogCollector.java`: reading script 4100 arguments after opening
  the log's search (`menuAction` on the search toggle, `runScript(2240)`), and counting the log's slots from
  the cache enums/structs (`countLogSlotsFromCache`, after WikiSyncPlugin#parseCacheForClog).

## Reimagined World Map (https://github.com/Maiz19/reimagined-world-map)

Copyright (c) 2026, maiz.

- `src/main/java/com/osrstracker/HouseTracker.java`: the player-owned house room template area
  (HouseTracker#isTemplate).

## BSD 2-Clause License

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
