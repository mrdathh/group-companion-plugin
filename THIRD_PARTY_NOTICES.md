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

## Group Ironmen Tracker (https://github.com/christoabrown/group-ironmen-tracker)

Copyright (c) 2022, Christopher Brown.

- `src/main/java/com/osrstracker/LocationReader.java`: resolving the player's position through the boat's
  world entity (GroupIronmenTrackerPlugin).
- `src/main/java/com/osrstracker/CollectionLogCollector.java`: opening the collection log's search when the
  player's own log opens, the house adventure log check and the 500-tick limit (CollectionLogWidgetSubscriber).

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
