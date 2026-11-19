package com.osrstracker;

import com.osrstracker.model.CombatAchievementsData;
import com.osrstracker.model.DiariesData;
import com.osrstracker.model.EventType;
import com.osrstracker.model.KingdomData;
import com.osrstracker.model.SlayerData;
import com.osrstracker.model.TimersData;
import com.osrstracker.timers.TimersTestAccess;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.http.api.RuneLiteAPI;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Diary tasks, combat achievement tasks, slayer names, kingdom and Tears of Guthix rules. */
public class ProgressRulesTest
{
	// ------------------------------------------------------------------ diaries

	private final DiaryTasks diaries = new DiaryTasks(RuneLiteAPI.GSON);

	private static List<DiariesData.Task> eval(DiaryTasks d, String region, String tier, Map<Integer, Integer> varps, Map<Integer, Integer> varbits)
	{
		return d.evaluate(region, tier, v -> varps.getOrDefault(v, 0), v -> varbits.getOrDefault(v, 0));
	}

	@Test
	public void diaryResourceLoadsEveryRegionAndTier()
	{
		assertFalse(diaries.isEmpty());
		int tiers = 0;
		for (String region : new String[]{"Ardougne", "Desert", "Falador", "Fremennik", "Kandarin", "Karamja", "Kourend & Kebos",
			"Lumbridge & Draynor", "Morytania", "Varrock", "Western Provinces", "Wilderness"})
		{
			for (String tier : new String[]{"Easy", "Medium", "Hard", "Elite"})
			{
				List<DiariesData.Task> t = eval(diaries, region, tier, Collections.emptyMap(), Collections.emptyMap());
				assertTrue(region + " " + tier, t != null && !t.isEmpty());
				tiers++;
			}
		}
		assertEquals(48, tiers);
		assertEquals(10, eval(diaries, "Ardougne", "Easy", Collections.emptyMap(), Collections.emptyMap()).size());
		assertEquals(19, eval(diaries, "Karamja", "Medium", Collections.emptyMap(), Collections.emptyMap()).size());
	}

	@Test
	public void diaryBitsDecodeKnownTasks()
	{
		Map<Integer, Integer> varps = new HashMap<>();
		varps.put(1196, 1 | (1 << 2));  // Ardougne easy: tasks at bits 0 and 2 → first and third task
		List<DiariesData.Task> ardy = eval(diaries, "Ardougne", "Easy", varps, Collections.emptyMap());
		assertTrue(ardy.get(0).getName().startsWith("Have Wizard Comperty teleport you"));
		assertTrue(ardy.get(0).isDone());
		assertFalse(ardy.get(1).isDone());
		assertTrue(ardy.get(2).isDone());

		Map<Integer, Integer> varbits = new HashMap<>();
		varbits.put(3566, 5);   // Karamja easy task 1 is done at value 5
		varbits.put(3567, 1);
		List<DiariesData.Task> karamja = eval(diaries, "Karamja", "Easy", Collections.emptyMap(), varbits);
		assertTrue(karamja.get(0).isDone());
		assertTrue(karamja.get(1).isDone());
		varbits.put(3566, 1);
		assertFalse(eval(diaries, "Karamja", "Easy", Collections.emptyMap(), varbits).get(0).isDone());

		// Desert medium task 11 counts as done through either of its two bits.
		Map<Integer, Integer> alt = new HashMap<>();
		alt.put(1199, 1 << 9);
		assertTrue(eval(diaries, "Desert", "Medium", alt, Collections.emptyMap()).get(10).isDone());

		assertTrue(diaries.varps().contains(1196));
		assertTrue(diaries.varbits().contains(3566));
	}

	// ------------------------------------------------------------------ combat achievements

	@Test
	public void combatAchievementTiersFromTaskBits()
	{
		List<CombatAchievementCollector.TaskDef> defs = Arrays.asList(
			new CombatAchievementCollector.TaskDef(0, "A", 0, "Giant Mole"),
			new CombatAchievementCollector.TaskDef(1, "B", 0, null),
			new CombatAchievementCollector.TaskDef(40, "C", 5, "Zulrah"));
		int[] bits = new int[21];
		bits[0] = 1;            // task 0
		bits[1] = 1 << 8;       // task 40
		CombatAchievementsData d = CombatAchievementCollector.build(7, 99, bits, defs);
		assertEquals(2, d.getCompleted());
		assertEquals(1, d.getTiers().get("Easy").getDone());
		assertEquals(2, d.getTiers().get("Easy").getTotal());
		assertEquals(1, d.getTiers().get("Grandmaster").getDone());
		assertEquals(0, d.getTiers().get("Medium").getTotal());
		assertEquals("Grandmaster", d.getTasks().get(2).getTier());
		assertTrue(d.getTasks().get(2).isDone());
		assertFalse(d.getTasks().get(1).isDone());

		CombatAchievementsData noList = CombatAchievementCollector.build(7, 99, bits, Collections.emptyList());
		assertNull(noList.getTiers());
		assertNull(noList.getTasks());
		assertEquals(99, noList.getCompleted());
	}

	@Test
	public void combatAchievementTaskListIsReadInSlices()
	{
		Client client = mock(Client.class);
		ClientThread clientThread = mock(ClientThread.class);
		SessionTracker session = mock(SessionTracker.class);
		TrackerSyncConfig config = mock(TrackerSyncConfig.class);
		int perTier = CombatAchievementCollector.SLICE; // 6 tiers × SLICE structs = 6 slices
		for (int t = 0; t < 6; t++)
		{
			EnumComposition e = mock(EnumComposition.class);
			int[] ids = new int[perTier];
			for (int i = 0; i < perTier; i++)
			{
				ids[i] = 10_000 + t * 1000 + i;
			}
			when(e.getIntVals()).thenReturn(ids);
			when(client.getEnum(CombatAchievementCollector.TIER_ENUMS[t])).thenReturn(e);
		}
		EnumComposition monsters = mock(EnumComposition.class);
		when(monsters.getStringValue(anyInt())).thenReturn("None");
		when(client.getEnum(CombatAchievementCollector.ENUM_MONSTER_NAMES)).thenReturn(monsters);
		when(client.getStructComposition(anyInt())).thenAnswer(inv ->
		{
			int id = inv.getArgument(0);
			StructComposition s = mock(StructComposition.class);
			when(s.getIntValue(CombatAchievementCollector.PARAM_TASK_ID)).thenReturn(id - 10_000);
			when(s.getStringValue(CombatAchievementCollector.PARAM_TASK_NAME)).thenReturn("Task " + id);
			return s;
		});
		CombatAchievementCollector collector = new CombatAchievementCollector(client, clientThread, session, config,
			mock(ScheduledExecutorService.class), new TimeSource());

		collector.startLoading();
		ArgumentCaptor<BooleanSupplier> step = ArgumentCaptor.forClass(BooleanSupplier.class);
		verify(clientThread).invoke(step.capture());
		int calls = 1;
		while (!step.getValue().getAsBoolean())
		{
			calls++;
		}
		assertEquals("one slice per client tick", 6, calls);
	}

	// ------------------------------------------------------------------ slayer

	@Test
	public void slayerNamesFromTheGameTables()
	{
		assertEquals("Duradel", SlayerCollector.masterName(5));
		assertEquals("Mortimer", SlayerCollector.masterName(10));
		assertNull(SlayerCollector.masterName(0));
		assertNull(SlayerCollector.masterName(42));

		List<Object[]> rows = new ArrayList<>();
		rows.add(new Object[]{0, "Gargoyle smasher"});
		rows.add(new Object[]{33, "Need more darkness"});
		rows.add(new Object[]{70, "Bigger and badder"});
		int[] bits = {1, 0, 0};
		assertEquals(Collections.singletonList("Gargoyle smasher"), SlayerCollector.unlocked(rows, bits));
		bits[1] = 1 << 1;
		assertEquals(Arrays.asList("Gargoyle smasher", "Need more darkness"), SlayerCollector.unlocked(rows, bits));

		Client client = mock(Client.class);
		when(client.getVarbitValue(VarbitID.SLAYER_BLOCKED_DURADEL_1)).thenReturn(46);
		when(client.getVarbitValue(VarbitID.SLAYER_BLOCKED_DURADEL_3)).thenReturn(97);
		when(client.getDBRowsByValue(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, 46)).thenReturn(Collections.singletonList(501));
		when(client.getDBRowsByValue(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, 97)).thenReturn(Collections.singletonList(502));
		when(client.getDBTableField(501, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)).thenReturn(new Object[]{"Black demons"});
		when(client.getDBTableField(502, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)).thenReturn(new Object[]{"Spiritual creatures"});
		SlayerCollector collector = new SlayerCollector(client, mock(ClientThread.class), mock(SessionTracker.class),
			mock(TrackerSyncConfig.class), mock(ScheduledExecutorService.class), new TimeSource());
		assertEquals(Arrays.asList("Black demons", "Spiritual creatures"), collector.readBlocked(5));
		assertEquals("Spria has none of her own: Turael's (empty here)", Collections.emptyList(), collector.readBlocked(9));
		assertNull("no master", collector.readBlocked(0));
	}

	private static Client slayerClient()
	{
		Client client = mock(Client.class);
		when(client.getDBRowsByValue(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, 46)).thenReturn(Collections.singletonList(501));
		when(client.getDBRowsByValue(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, 97)).thenReturn(Collections.singletonList(502));
		when(client.getDBRowsByValue(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, 42)).thenReturn(Collections.singletonList(503));
		when(client.getDBTableField(501, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)).thenReturn(new Object[]{"Black demons"});
		when(client.getDBTableField(502, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)).thenReturn(new Object[]{"Spiritual creatures"});
		when(client.getDBTableField(503, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)).thenReturn(new Object[]{"Abyssal demons"});
		// boss task: sublist id 7 → task row 504
		when(client.getDBRowsByValue(DBTableID.SlayerTaskSublist.ID, DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, 0, 7))
			.thenReturn(Collections.singletonList(600));
		when(client.getDBTableField(600, DBTableID.SlayerTaskSublist.COL_TASK, 0)).thenReturn(new Object[]{504});
		when(client.getDBTableField(504, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)).thenReturn(new Object[]{"Vorkath"});
		when(client.getDBTableRows(DBTableID.SlayerUnlock.ID)).thenReturn(Collections.emptyList());
		return client;
	}

	@Test
	public void slayerBlockListsOfEveryMasterWithTheDiarySlot()
	{
		Client client = slayerClient();
		when(client.getVarbitValue(VarbitID.SLAYER_BLOCKED_DURADEL_1)).thenReturn(46);
		when(client.getVarbitValue(VarbitID.SLAYER_BLOCKED_DURADEL_DIARY)).thenReturn(97);
		when(client.getVarbitValue(VarbitID.SLAYER_BLOCKED_TURAEL_2)).thenReturn(42);
		when(client.getVarbitValue(VarbitID.SLAYER_BLOCKED_TURAEL_DIARY)).thenReturn(46);
		when(client.getVarbitValue(VarbitID.SLAYER_BLOCKED_MORTIMER_2)).thenReturn(97);
		SlayerCollector collector = new SlayerCollector(client, mock(ClientThread.class), mock(SessionTracker.class),
			mock(TrackerSyncConfig.class), mock(ScheduledExecutorService.class), new TimeSource());

		assertEquals("7th (diary) slot included", Arrays.asList("Black demons", "Spiritual creatures"), collector.readBlocked(5));
		assertEquals(1, SlayerCollector.blockListMaster(9));
		assertEquals(5, SlayerCollector.blockListMaster(5));
		assertEquals("Spria → Turael's varbits", Arrays.asList("Abyssal demons", "Black demons"), collector.readBlocked(9));

		Map<String, List<String>> all = collector.readBlockedByMaster();
		assertEquals(Arrays.asList("Turael", "Mazchna", "Vannaka", "Chaeldar", "Duradel", "Nieve", "Krystilia", "Konar",
			"Mortimer"), new ArrayList<>(all.keySet()));
		assertEquals(Arrays.asList("Abyssal demons", "Black demons"), all.get("Turael"));
		assertEquals(Arrays.asList("Black demons", "Spiritual creatures"), all.get("Duradel"));
		assertEquals(Collections.singletonList("Spiritual creatures"), all.get("Mortimer"));
		assertEquals(Collections.emptyList(), all.get("Konar"));

		for (int v : new int[]{VarbitID.SLAYER_BLOCKED_TURAEL_DIARY, VarbitID.SLAYER_BLOCKED_MAZCHNA_DIARY,
			VarbitID.SLAYER_BLOCKED_VANNAKA_DIARY, VarbitID.SLAYER_BLOCKED_CHAELDAR_DIARY, VarbitID.SLAYER_BLOCKED_KONAR_DIARY,
			VarbitID.SLAYER_BLOCKED_NIEVE_DIARY, VarbitID.SLAYER_BLOCKED_DURADEL_DIARY, VarbitID.SLAYER_BLOCKED_KRYSTILIA_DIARY})
		{
			assertTrue("diary varbit " + v + " triggers a send", SlayerCollector.isSlayerVar(-1, v));
		}
		for (int[] varbits : SlayerCollector.BLOCK_VARBITS.values())
		{
			assertTrue(varbits.length == 7 || varbits.length == 2);
		}
	}

	@Test
	public void slayerTaskStorageRules()
	{
		assertTrue(SlayerCollector.servesSlayerScreen(87, 42, 87, 42));
		assertFalse("IF varps serve another interface", SlayerCollector.servesSlayerScreen(5, 42, 87, 42));
		assertFalse(SlayerCollector.servesSlayerScreen(87, 3, 87, 42));

		assertEquals(SlayerCollector.StoredSlotState.TASK, SlayerCollector.storedSlotState(42, 150));
		assertEquals(SlayerCollector.StoredSlotState.EMPTY, SlayerCollector.storedSlotState(0, 150));
		assertEquals(SlayerCollector.StoredSlotState.EMPTY, SlayerCollector.storedSlotState(42, 0));
		assertEquals(SlayerCollector.StoredSlotState.EMPTY, SlayerCollector.storedSlotState(42, -1));
		assertEquals(SlayerCollector.StoredSlotState.TASK, SlayerCollector.storedSlotState(98, 5));
		assertEquals(SlayerCollector.StoredSlotState.UNREADABLE, SlayerCollector.storedSlotState(98, -1));
	}

	@Test
	public void slayerTaskStorageCapture()
	{
		Client client = slayerClient();
		SessionTracker session = mock(SessionTracker.class);
		TrackerSyncConfig config = mock(TrackerSyncConfig.class);
		when(session.isActive()).thenReturn(true);
		when(config.sendProgress()).thenReturn(true);
		TimeSource time = new TimeSource();
		time.setMillisSupplier(() -> Instant.parse("2026-11-19T10:00:00Z").toEpochMilli());
		SlayerCollector collector = new SlayerCollector(client, mock(ClientThread.class), session, config,
			mock(ScheduledExecutorService.class), time);
		ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);

		collector.emit();
		verify(session).emit(eq(EventType.SLAYER), data.capture());
		assertNull("not seen yet: omitted", ((SlayerData) data.getValue()).getStored());

		// current task 87 x task 46; the Tasks tab mirrors it into IF1/IF2 and the stored slot into IF4-IF6
		when(client.getVarpValue(VarPlayerID.SLAYER_COUNT)).thenReturn(87);
		when(client.getVarpValue(VarPlayerID.SLAYER_TARGET)).thenReturn(46);
		when(client.getVarpValue(VarPlayerID.IF1)).thenReturn(3);
		when(client.getVarpValue(VarPlayerID.IF2)).thenReturn(46);
		when(client.getVarpValue(VarPlayerID.IF4)).thenReturn(150);
		when(client.getVarpValue(VarPlayerID.IF5)).thenReturn(42);
		assertFalse("IF1 does not match: other interface", collector.captureStored());

		when(client.getVarpValue(VarPlayerID.IF1)).thenReturn(87);
		assertTrue(collector.captureStored());
		assertFalse("unchanged", collector.captureStored());
		collector.emit();
		verify(session, times(2)).emit(eq(EventType.SLAYER), data.capture());
		SlayerData.StoredSlot slot = ((SlayerData) data.getValue()).getStored();
		assertEquals(new SlayerData.StoredTask("Abyssal demons", 150, "2026-11-19T10:00:00.000Z"), slot.getTask());

		// boss task in storage
		when(client.getVarpValue(VarPlayerID.IF4)).thenReturn(4);
		when(client.getVarpValue(VarPlayerID.IF5)).thenReturn(98);
		when(client.getVarpValue(VarPlayerID.IF6)).thenReturn(7);
		assertTrue(collector.captureStored());
		// boss slot drawn name-only (amount -1): kept as it was
		when(client.getVarpValue(VarPlayerID.IF4)).thenReturn(-1);
		assertFalse(collector.captureStored());
		collector.emit();
		verify(session, times(3)).emit(eq(EventType.SLAYER), data.capture());
		assertEquals(new SlayerData.StoredTask("Vorkath", 4, "2026-11-19T10:00:00.000Z"),
			((SlayerData) data.getValue()).getStored().getTask());

		// emptied
		when(client.getVarpValue(VarPlayerID.IF5)).thenReturn(0);
		assertTrue(collector.captureStored());
		collector.emit();
		verify(session, times(4)).emit(eq(EventType.SLAYER), data.capture());
		assertEquals(SlayerData.StoredSlot.empty(), ((SlayerData) data.getValue()).getStored());

		// the session ends: forgotten
		collector.onSessionEnd();
		collector.emit();
		verify(session, times(5)).emit(eq(EventType.SLAYER), data.capture());
		assertNull(((SlayerData) data.getValue()).getStored());
	}

	// ------------------------------------------------------------------ kingdom

	@Test
	public void kingdomMapping()
	{
		KingdomData k = KingdomCollector.read(127, 750_000, 5, 0, 5, 0, 0, 0, true, 4_572, 1, 1, 1);
		assertEquals(100, k.getApproval());
		assertEquals(750_000, k.getCoffer());
		assertEquals(Integer.valueOf(5), k.getWorkers().get("Wood"));
		assertEquals(Integer.valueOf(5), k.getWorkers().get("Fishing"));
		assertEquals(6, k.getWorkers().size());
		assertTrue(k.isRoyalTrouble());
		assertEquals(Integer.valueOf(127), k.getApprovalPoints());
		assertEquals(Integer.valueOf(4_572), k.getResourcePoints());
		assertEquals("teak", k.getHardwood());
		assertEquals("flax", k.getHerbs());
		assertEquals("cooked", k.getFish());
		KingdomData low = KingdomCollector.read(32, 0, 0, 0, 0, 0, 0, 0, false, 0, 0, 0, 0);
		assertEquals(25, low.getApproval());
		assertEquals(Integer.valueOf(32), low.getApprovalPoints());
		assertEquals(Integer.valueOf(0), low.getResourcePoints());
		assertEquals("mahogany", low.getHardwood());
		assertEquals("herbs", low.getHerbs());
		assertEquals("raw", low.getFish());
		assertEquals("both", KingdomCollector.read(64, 0, 0, 0, 0, 0, 0, 0, true, 0, 2, 0, 0).getHardwood());
		// The store's 18 bits cannot exceed its maximum; clamped anyway (the server rejects more).
		assertEquals(Integer.valueOf(262_143), KingdomCollector.read(64, 0, 0, 0, 0, 0, 0, 0, true, 300_000, 0, 0, 0).getResourcePoints());
		assertNull("vars not loaded", KingdomCollector.read(0, 0, 0, 0, 0, 0, 0, 0, false, 0, 0, 0, 0));
	}

	// ------------------------------------------------------------------ Tears of Guthix

	@Test
	public void tearsOfGuthixStates()
	{
		long visit = 1_763_200_000L;                       // 2025-11-15 09:46:40 UTC
		long dayStart = visit - visit % 86_400L;
		long next = dayStart + 7 * 86_400L;
		TimersData.Timer growing = TimersTestAccess.tearsOfGuthix(visit + 3600, visit);
		assertEquals("tears_of_guthix", growing.getId());
		assertEquals("tears_of_guthix", growing.getKind());
		assertEquals("Weekly", growing.getCategory());
		assertEquals("GROWING", growing.getState());
		assertEquals(com.osrstracker.model.IngestEvent.formatTime(java.time.Instant.ofEpochSecond(next)), growing.getReadyAt());
		assertEquals("READY", TimersTestAccess.tearsOfGuthix(next, visit).getState());
		TimersData.Timer unknown = TimersTestAccess.tearsOfGuthix(visit, null);
		assertEquals("UNKNOWN", unknown.getState());
		assertNull(unknown.getReadyAt());
	}
}
