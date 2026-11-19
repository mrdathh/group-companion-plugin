package com.osrstracker;

import com.osrstracker.model.EventType;
import com.osrstracker.model.IngestEvent;
import com.osrstracker.model.SlayerData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Slayer task, points and streak, read the way RuneLite core's Slayer plugin reads them
 * (net.runelite.client.plugins.slayer.SlayerPlugin#updateTask and #addCounter, runelite-client 1.13.6,
 * BSD-2-Clause, Copyright (c) 2017, Tyler; (c) 2018, Shaun Dreclin; see THIRD_PARTY_NOTICES.md): the task comes
 * from varps and the game's slayer DB tables, so this works without the core Slayer plugin. Detail (after the
 * Mystix plugin's SlayerMonitor, rjmccaskell/mystix-plugin, BSD-2-Clause): master name, wilderness streak, the
 * current master's block list (per-master block varbits → SlayerTask names) and purchased unlocks (the game's
 * SlayerUnlock DB table, its {@code bit} column read as a bit index into the three unlock varps). Sent after login
 * and when any of those vars change, at most once per 2 s.
 *
 * <p>Also every master's block list ({@code blockedByMaster}) and the Task Storage slot ({@code stored}). The stored
 * task has no persistent var: the game copies it into the shared interface-transfer varps IF4-IF6 only while the
 * slayer rewards interface's Tasks tab is drawn (scripts 328 / 421). The rule for reading it (IF1/IF2 must equal the
 * live task varps, IF4 amount, IF5 task id, IF6 boss sublist id) follows the Mystix plugin's SlayerMonitor
 * (captureStoredTask / hasStoredTask; rjmccaskell/mystix-plugin, BSD-2-Clause, Copyright (c) 2026, Mystix; see
 * THIRD_PARTY_NOTICES.md); own code. The capture is kept in memory for the session only; this plugin never opens or
 * clicks any interface.
 */
@Singleton
public class SlayerCollector implements SessionTracker.Listener
{
	static final long DELAY_MS = 200;
	static final long MIN_INTERVAL_MS = 2_000;
	static final long LOGIN_SETTLE_MS = 2_000;

	/** From [proc,helper_slayer_current_assignment], via the core Slayer plugin. */
	private static final int BOSS_TASK_ID = 98;
	private static final int KRYSTILIA_SLAYER_MASTER = 7;
	private static final int MORTIMER_SLAYER_MASTER = 10;
	private static final int TURAEL_SLAYER_MASTER = 1;
	private static final int SPRIA_SLAYER_MASTER = 9;

	/** Clientscripts drawing the slayer rewards interface's Tasks tab: setup (once) and redraw (on var updates). */
	static final int TASKS_TAB_SETUP_SCRIPT = 328;
	static final int TASKS_TAB_REDRAW_SCRIPT = 421;

	/** Slayer master varbit value → name (OSRS Wiki's table for varbit 4067; 10 = Mortimer from RuneLite core). */
	static final String[] MASTER_NAMES = {null, "Turael", "Mazchna", "Vannaka", "Chaeldar", "Duradel", "Nieve",
		"Krystilia", "Konar", "Spria", "Mortimer"};
	/**
	 * Per-master block list varbits (each holds a slayer task id, 0 = empty): slots 1-6 (Turael 17812-17817,
	 * Mazchna 17818-17823, Vannaka 17824-17829, Chaeldar 17830-17835, Konar 17836-17841, Nieve 17842-17847,
	 * Duradel 17848-17853, Krystilia 17854-17859) and the 7th, diary-reward slot (Turael 17860, Mazchna 17861,
	 * Vannaka 17862, Chaeldar 17863, Konar 17864, Nieve 17865, Duradel 17866, Krystilia 17867); Mortimer has two
	 * (15783, 15784). Ids as in runelite-api 1.13.7 gameval. Spria (9) has none of her own: she shares Turael's.
	 */
	static final Map<Integer, int[]> BLOCK_VARBITS = new HashMap<>();
	/** Unlock bitfields: bit {@code n} of the unlock table is bit {@code n % 32} of varp {@code n / 32}. */
	static final int[] UNLOCK_VARPS = {VarPlayerID.SLAYER_REWARDS_UNLOCKS, VarPlayerID.SLAYER_REWARDS_UNLOCKS1,
		VarPlayerID.SLAYER_REWARDS_UNLOCKS2};
	private static final Set<Integer> DETAIL_VARBITS = new HashSet<>();
	private static final Set<Integer> DETAIL_VARPS = new HashSet<>();

	static
	{
		BLOCK_VARBITS.put(1, new int[]{VarbitID.SLAYER_BLOCKED_TURAEL_1, VarbitID.SLAYER_BLOCKED_TURAEL_2,
			VarbitID.SLAYER_BLOCKED_TURAEL_3, VarbitID.SLAYER_BLOCKED_TURAEL_4, VarbitID.SLAYER_BLOCKED_TURAEL_5,
			VarbitID.SLAYER_BLOCKED_TURAEL_6, VarbitID.SLAYER_BLOCKED_TURAEL_DIARY});
		BLOCK_VARBITS.put(2, new int[]{VarbitID.SLAYER_BLOCKED_MAZCHNA_1, VarbitID.SLAYER_BLOCKED_MAZCHNA_2,
			VarbitID.SLAYER_BLOCKED_MAZCHNA_3, VarbitID.SLAYER_BLOCKED_MAZCHNA_4, VarbitID.SLAYER_BLOCKED_MAZCHNA_5,
			VarbitID.SLAYER_BLOCKED_MAZCHNA_6, VarbitID.SLAYER_BLOCKED_MAZCHNA_DIARY});
		BLOCK_VARBITS.put(3, new int[]{VarbitID.SLAYER_BLOCKED_VANNAKA_1, VarbitID.SLAYER_BLOCKED_VANNAKA_2,
			VarbitID.SLAYER_BLOCKED_VANNAKA_3, VarbitID.SLAYER_BLOCKED_VANNAKA_4, VarbitID.SLAYER_BLOCKED_VANNAKA_5,
			VarbitID.SLAYER_BLOCKED_VANNAKA_6, VarbitID.SLAYER_BLOCKED_VANNAKA_DIARY});
		BLOCK_VARBITS.put(4, new int[]{VarbitID.SLAYER_BLOCKED_CHAELDAR_1, VarbitID.SLAYER_BLOCKED_CHAELDAR_2,
			VarbitID.SLAYER_BLOCKED_CHAELDAR_3, VarbitID.SLAYER_BLOCKED_CHAELDAR_4, VarbitID.SLAYER_BLOCKED_CHAELDAR_5,
			VarbitID.SLAYER_BLOCKED_CHAELDAR_6, VarbitID.SLAYER_BLOCKED_CHAELDAR_DIARY});
		BLOCK_VARBITS.put(5, new int[]{VarbitID.SLAYER_BLOCKED_DURADEL_1, VarbitID.SLAYER_BLOCKED_DURADEL_2,
			VarbitID.SLAYER_BLOCKED_DURADEL_3, VarbitID.SLAYER_BLOCKED_DURADEL_4, VarbitID.SLAYER_BLOCKED_DURADEL_5,
			VarbitID.SLAYER_BLOCKED_DURADEL_6, VarbitID.SLAYER_BLOCKED_DURADEL_DIARY});
		BLOCK_VARBITS.put(6, new int[]{VarbitID.SLAYER_BLOCKED_NIEVE_1, VarbitID.SLAYER_BLOCKED_NIEVE_2,
			VarbitID.SLAYER_BLOCKED_NIEVE_3, VarbitID.SLAYER_BLOCKED_NIEVE_4, VarbitID.SLAYER_BLOCKED_NIEVE_5,
			VarbitID.SLAYER_BLOCKED_NIEVE_6, VarbitID.SLAYER_BLOCKED_NIEVE_DIARY});
		BLOCK_VARBITS.put(7, new int[]{VarbitID.SLAYER_BLOCKED_KRYSTILIA_1, VarbitID.SLAYER_BLOCKED_KRYSTILIA_2,
			VarbitID.SLAYER_BLOCKED_KRYSTILIA_3, VarbitID.SLAYER_BLOCKED_KRYSTILIA_4, VarbitID.SLAYER_BLOCKED_KRYSTILIA_5,
			VarbitID.SLAYER_BLOCKED_KRYSTILIA_6, VarbitID.SLAYER_BLOCKED_KRYSTILIA_DIARY});
		BLOCK_VARBITS.put(8, new int[]{VarbitID.SLAYER_BLOCKED_KONAR_1, VarbitID.SLAYER_BLOCKED_KONAR_2,
			VarbitID.SLAYER_BLOCKED_KONAR_3, VarbitID.SLAYER_BLOCKED_KONAR_4, VarbitID.SLAYER_BLOCKED_KONAR_5,
			VarbitID.SLAYER_BLOCKED_KONAR_6, VarbitID.SLAYER_BLOCKED_KONAR_DIARY});
		BLOCK_VARBITS.put(10, new int[]{VarbitID.SLAYER_BLOCKED_MORTIMER_1, VarbitID.SLAYER_BLOCKED_MORTIMER_2});
		BLOCK_VARBITS.values().forEach(a ->
		{
			for (int v : a)
			{
				DETAIL_VARBITS.add(v);
			}
		});
		for (int v : UNLOCK_VARPS)
		{
			DETAIL_VARPS.add(v);
		}
	}

	private final Client client;
	/** Unlock table rows {bit, name}, read once from the game's SlayerUnlock DB table. */
	private List<Object[]> unlockRows;
	/** Slayer task id → name (the game's SlayerTask table), filled as names are looked up. Client thread. */
	private final Map<Integer, String> taskNames = new HashMap<>();
	/** Last Task Storage capture of this session; null = not seen. Cleared at session end (may run off-thread). */
	private volatile StoredCapture stored;
	private final SessionTracker session;
	private final TrackerSyncConfig config;
	private final TimeSource time;
	private final Throttle throttle;

	@Inject
	SlayerCollector(Client client, ClientThread clientThread, SessionTracker session, TrackerSyncConfig config,
		ScheduledExecutorService executor, TimeSource time)
	{
		this.client = client;
		this.session = session;
		this.config = config;
		this.time = time;
		this.throttle = new Throttle(executor, time, DELAY_MS, MIN_INTERVAL_MS, MIN_INTERVAL_MS,
			() -> clientThread.invoke(this::emit));
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (session.isActive() && config.sendProgress() && isSlayerVar(event.getVarpId(), event.getVarbitId()))
		{
			throttle.trigger();
		}
	}

	static boolean isSlayerVar(int varpId, int varbitId)
	{
		return varpId == VarPlayerID.SLAYER_COUNT
			|| varpId == VarPlayerID.SLAYER_AREA
			|| varpId == VarPlayerID.SLAYER_TARGET
			|| varpId == VarPlayerID.SLAYER_COUNT_ORIGINAL
			|| varpId == VarPlayerID.SLAYER_MORTIMER_TASKS_COMPLETED
			|| varbitId == VarbitID.SLAYER_TARGET_BOSSID
			|| varbitId == VarbitID.SLAYER_MODIFIER_ID
			|| varbitId == VarbitID.SLAYER_MODIFIER_VALUE
			|| varbitId == VarbitID.SLAYER_MODIFIER_NEGATIVE
			|| varbitId == VarbitID.SLAYER_POINTS
			|| varbitId == VarbitID.SLAYER_TASKS_COMPLETED
			|| varbitId == VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED
			|| varbitId == VarbitID.SLAYER_MASTER
			|| DETAIL_VARBITS.contains(varbitId)
			|| DETAIL_VARPS.contains(varpId);
	}

	@Override
	public void onSessionStart()
	{
		throttle.triggerIn(LOGIN_SETTLE_MS);
	}

	@Override
	public void onSessionEnd()
	{
		throttle.cancel();
		stored = null;
	}

	public void shutDown()
	{
		throttle.cancel();
		stored = null;
	}

	/** Client thread. Captures the Task Storage slot while the rewards interface's Tasks tab is drawn. */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		int id = event.getScriptId();
		if (id != TASKS_TAB_SETUP_SCRIPT && id != TASKS_TAB_REDRAW_SCRIPT)
		{
			return;
		}
		if (captureStored() && session.isActive() && config.sendProgress())
		{
			throttle.trigger();
		}
	}

	/** Reads IF1-IF6; true when the remembered slot changed. Client thread. */
	boolean captureStored()
	{
		if (!servesSlayerScreen(client.getVarpValue(VarPlayerID.IF1), client.getVarpValue(VarPlayerID.IF2),
			client.getVarpValue(VarPlayerID.SLAYER_COUNT), client.getVarpValue(VarPlayerID.SLAYER_TARGET)))
		{
			return false;
		}
		int amount = client.getVarpValue(VarPlayerID.IF4);
		int taskId = client.getVarpValue(VarPlayerID.IF5);
		int bossId = client.getVarpValue(VarPlayerID.IF6);
		StoredSlotState state = storedSlotState(taskId, amount);
		if (state == StoredSlotState.UNREADABLE)
		{
			return false;
		}
		if (state == StoredSlotState.EMPTY)
		{
			taskId = 0;
			amount = 0;
			bossId = 0;
		}
		StoredCapture previous = stored;
		if (previous != null && previous.taskId == taskId && previous.amount == amount && previous.bossId == bossId)
		{
			return false; // unchanged: keep the first-seen time
		}
		SlayerData.StoredSlot slot;
		if (state == StoredSlotState.EMPTY)
		{
			slot = SlayerData.StoredSlot.empty();
		}
		else
		{
			String name = resolveTaskName(taskId, bossId);
			if (name == null)
			{
				return false;
			}
			slot = SlayerData.StoredSlot.of(new SlayerData.StoredTask(name, amount, IngestEvent.formatTime(time.now())));
		}
		stored = new StoredCapture(taskId, amount, bossId, slot);
		return true;
	}

	/**
	 * The Tasks tab copies the current task's count and target into IF1/IF2; only when both match the live task
	 * varps do the shared IF varps serve the slayer rewards interface (they are scratch for many interfaces).
	 */
	static boolean servesSlayerScreen(int if1, int if2, int slayerCount, int slayerTarget)
	{
		return if1 == slayerCount && if2 == slayerTarget;
	}

	enum StoredSlotState
	{
		EMPTY, TASK,
		/** A boss slot with amount -1 (the interface draws it name-only): amount unknown, keep the last capture. */
		UNREADABLE
	}

	/** Task id 0 or amount 0 or less = empty slot. */
	static StoredSlotState storedSlotState(int taskId, int amount)
	{
		if (taskId == BOSS_TASK_ID && amount == -1)
		{
			return StoredSlotState.UNREADABLE;
		}
		return taskId <= 0 || amount <= 0 ? StoredSlotState.EMPTY : StoredSlotState.TASK;
	}

	/** Client thread. */
	void emit()
	{
		if (!config.sendProgress() || !session.isActive())
		{
			return;
		}
		int masterId = client.getVarbitValue(VarbitID.SLAYER_MASTER);
		StoredCapture capture = stored;
		session.emit(EventType.SLAYER, new SlayerData(readTask(), client.getVarbitValue(VarbitID.SLAYER_POINTS), readStreak(),
			masterName(masterId), client.getVarbitValue(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED),
			readBlocked(masterId), readUnlocks(), readBlockedByMaster(), capture == null ? null : capture.slot));
	}

	static String masterName(int id)
	{
		return id > 0 && id < MASTER_NAMES.length ? MASTER_NAMES[id] : null;
	}

	/** The master whose block varbits hold {@code masterId}'s list: Spria shares Turael's (OSRS Wiki). */
	static int blockListMaster(int masterId)
	{
		return masterId == SPRIA_SLAYER_MASTER ? TURAEL_SLAYER_MASTER : masterId;
	}

	/** A master's block list as task names (Spria → Turael's); null when that master has no known list. */
	List<String> readBlocked(int masterId)
	{
		int[] varbits = BLOCK_VARBITS.get(blockListMaster(masterId));
		if (varbits == null)
		{
			return null;
		}
		List<String> out = new ArrayList<>();
		for (int v : varbits)
		{
			int taskId = client.getVarbitValue(v);
			String name = taskId > 0 ? taskName(taskId) : null;
			if (name != null)
			{
				out.add(name);
			}
		}
		return out;
	}

	/** Every master with block varbits (not Spria, who shares Turael's), by name, in master-id order. */
	Map<String, List<String>> readBlockedByMaster()
	{
		Map<String, List<String>> out = new LinkedHashMap<>();
		for (int id = 1; id < MASTER_NAMES.length; id++)
		{
			if (BLOCK_VARBITS.containsKey(id))
			{
				out.put(MASTER_NAMES[id], readBlocked(id));
			}
		}
		return out;
	}

	private String taskName(int taskId)
	{
		String cached = taskNames.get(taskId);
		if (cached != null)
		{
			return cached;
		}
		List<Integer> rows = client.getDBRowsByValue(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, taskId);
		if (rows == null || rows.isEmpty())
		{
			return null;
		}
		String name = rowTaskName(rows.get(0));
		if (name != null)
		{
			taskNames.put(taskId, name);
		}
		return name;
	}

	private String rowTaskName(int taskRow)
	{
		Object[] v = client.getDBTableField(taskRow, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0);
		return v != null && v.length > 0 && v[0] instanceof String ? (String) v[0] : null;
	}

	/** A task id (a boss task through its SlayerTaskSublist id) as a name; null when it cannot be resolved. */
	private String resolveTaskName(int taskId, int bossId)
	{
		if (taskId != BOSS_TASK_ID)
		{
			return taskName(taskId);
		}
		List<Integer> bossRows = client.getDBRowsByValue(DBTableID.SlayerTaskSublist.ID,
			DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, 0, bossId);
		if (bossRows == null || bossRows.isEmpty())
		{
			return null;
		}
		Object[] row = client.getDBTableField(bossRows.get(0), DBTableID.SlayerTaskSublist.COL_TASK, 0);
		return row != null && row.length > 0 && row[0] instanceof Integer ? rowTaskName((Integer) row[0]) : null;
	}

	/** One Task Storage reading: the raw ids (to spot changes) and what is sent. */
	private static final class StoredCapture
	{
		final int taskId;
		final int amount;
		final int bossId;
		final SlayerData.StoredSlot slot;

		StoredCapture(int taskId, int amount, int bossId, SlayerData.StoredSlot slot)
		{
			this.taskId = taskId;
			this.amount = amount;
			this.bossId = bossId;
			this.slot = slot;
		}
	}

	/** Names of the purchased unlocks and extensions (the game's SlayerUnlock table + unlock bitfields). */
	List<String> readUnlocks()
	{
		if (unlockRows == null)
		{
			List<Object[]> rows = new ArrayList<>();
			for (int row : client.getDBTableRows(DBTableID.SlayerUnlock.ID))
			{
				Object[] bit = client.getDBTableField(row, DBTableID.SlayerUnlock.COL_BIT, 0);
				Object[] name = client.getDBTableField(row, DBTableID.SlayerUnlock.COL_NAME, 0);
				if (bit != null && bit.length > 0 && bit[0] instanceof Integer && name != null && name.length > 0 && name[0] instanceof String)
				{
					rows.add(new Object[]{bit[0], name[0]});
				}
			}
			unlockRows = rows;
		}
		int[] bits = new int[UNLOCK_VARPS.length];
		for (int i = 0; i < bits.length; i++)
		{
			bits[i] = client.getVarpValue(UNLOCK_VARPS[i]);
		}
		return unlocked(unlockRows, bits);
	}

	/** Visible for tests. */
	static List<String> unlocked(List<Object[]> rows, int[] bits)
	{
		List<String> out = new ArrayList<>();
		for (Object[] r : rows)
		{
			int bit = (Integer) r[0];
			if (bit >= 0 && bit / 32 < bits.length && ((bits[bit / 32] >>> (bit % 32)) & 1) == 1)
			{
				out.add((String) r[1]);
			}
		}
		return out;
	}

	private int readStreak()
	{
		switch (client.getVarbitValue(VarbitID.SLAYER_MASTER))
		{
			case KRYSTILIA_SLAYER_MASTER:
				return client.getVarbitValue(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED);
			case MORTIMER_SLAYER_MASTER:
				return client.getVarpValue(VarPlayerID.SLAYER_MORTIMER_TASKS_COMPLETED);
			default:
				return client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED);
		}
	}

	/** Null when there is no task (or it cannot be resolved). Ported from SlayerPlugin#updateTask. */
	private SlayerData.Task readTask()
	{
		int amount = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
		if (amount <= 0)
		{
			return null;
		}
		int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
		int taskRow;
		if (taskId == BOSS_TASK_ID)
		{
			List<Integer> bossRows = client.getDBRowsByValue(DBTableID.SlayerTaskSublist.ID,
				DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, 0, client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID));
			if (bossRows.isEmpty())
			{
				return null;
			}
			taskRow = (Integer) client.getDBTableField(bossRows.get(0), DBTableID.SlayerTaskSublist.COL_TASK, 0)[0];
		}
		else
		{
			List<Integer> taskRows = client.getDBRowsByValue(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, taskId);
			if (taskRows.isEmpty())
			{
				return null;
			}
			taskRow = taskRows.get(0);
		}
		String name = (String) client.getDBTableField(taskRow, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)[0];

		String location = null;
		int areaId = client.getVarpValue(VarPlayerID.SLAYER_AREA);
		if (areaId > 0)
		{
			List<Integer> areaRows = client.getDBRowsByValue(DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, 0, areaId);
			if (!areaRows.isEmpty())
			{
				location = (String) client.getDBTableField(areaRows.get(0), DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, 0)[0];
			}
		}

		int initial = client.getVarpValue(VarPlayerID.SLAYER_COUNT_ORIGINAL);
		if (client.getVarbitValue(VarbitID.SLAYER_MODIFIER_ID) == 2)
		{
			boolean negative = client.getVarbitValue(VarbitID.SLAYER_MODIFIER_NEGATIVE) == 1;
			int modifier = client.getVarbitValue(VarbitID.SLAYER_MODIFIER_VALUE);
			initial += negative ? -modifier : modifier;
		}
		return new SlayerData.Task(name, amount, initial, location);
	}
}
