package com.osrstracker;

import com.osrstracker.model.CombatAchievementsData;
import com.osrstracker.model.EventType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.StructComposition;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Combat achievements: points from the {@code CA_POINTS} varbit; completion from the task-completion varps
 * ({@code CA_TASK_COMPLETED_0..20}: task {@code n} is bit {@code n % 32} of varp {@code n / 32}).
 *
 * <p>The task list (id, name, tier, monster) is read from the game cache the way the Plugin Hub plugin
 * "Combat Achievement Lookup" does (jimmyununu/combat-achievement-lookup, BSD-2-Clause, CombatTaskRepository and
 * TaskTier): one enum per tier (3981 Easy … 3986 Grandmaster) lists the task structs; struct params 1306 (id),
 * 1308 (name) and 1312 (index into the monster-name enum 3971). These ids are not in RuneLite's gameval constants.
 * The structs are read once per client run on the client thread, {@link #SLICE} per client tick, then kept in
 * memory. If the cache does not look as expected, {@code tiers} and {@code tasks} are left out.
 *
 * <p>Sent after login and when those vars change, at most once per 5 s.
 */
@Singleton
public class CombatAchievementCollector implements SessionTracker.Listener
{
	static final long DELAY_MS = 1_000;
	static final long MIN_INTERVAL_MS = 5_000;
	static final long LOGIN_SETTLE_MS = 5_000;

	static final int[] TASK_VARPS = {
		VarPlayerID.CA_TASK_COMPLETED_0, VarPlayerID.CA_TASK_COMPLETED_1, VarPlayerID.CA_TASK_COMPLETED_2,
		VarPlayerID.CA_TASK_COMPLETED_3, VarPlayerID.CA_TASK_COMPLETED_4, VarPlayerID.CA_TASK_COMPLETED_5,
		VarPlayerID.CA_TASK_COMPLETED_6, VarPlayerID.CA_TASK_COMPLETED_7, VarPlayerID.CA_TASK_COMPLETED_8,
		VarPlayerID.CA_TASK_COMPLETED_9, VarPlayerID.CA_TASK_COMPLETED_10, VarPlayerID.CA_TASK_COMPLETED_11,
		VarPlayerID.CA_TASK_COMPLETED_12, VarPlayerID.CA_TASK_COMPLETED_13, VarPlayerID.CA_TASK_COMPLETED_14,
		VarPlayerID.CA_TASK_COMPLETED_15, VarPlayerID.CA_TASK_COMPLETED_16, VarPlayerID.CA_TASK_COMPLETED_17,
		VarPlayerID.CA_TASK_COMPLETED_18, VarPlayerID.CA_TASK_COMPLETED_19, VarPlayerID.CA_TASK_COMPLETED_20,
	};
	private static final Set<Integer> TASK_VARP_SET = new HashSet<>();

	static
	{
		for (int v : TASK_VARPS)
		{
			TASK_VARP_SET.add(v);
		}
	}

	static final int[] TIER_ENUMS = {3981, 3982, 3983, 3984, 3985, 3986};
	static final String[] TIER_NAMES = {"Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster"};
	static final int PARAM_TASK_ID = 1306;
	static final int PARAM_TASK_NAME = 1308;
	static final int PARAM_TASK_MONSTER = 1312;
	static final int ENUM_MONSTER_NAMES = 3971;
	/** Task structs read per client tick. */
	static final int SLICE = 40;

	/** One task from the cache. */
	static final class TaskDef
	{
		final int id;
		final String name;
		final int tier;
		final String monster;

		TaskDef(int id, String name, int tier, String monster)
		{
			this.id = id;
			this.name = name;
			this.tier = tier;
			this.monster = monster;
		}
	}

	private final Client client;
	private final ClientThread clientThread;
	/** Null until read; empty when the cache did not look as expected. */
	private volatile List<TaskDef> taskDefs;
	private boolean loading;
	private final SessionTracker session;
	private final TrackerSyncConfig config;
	private final Throttle throttle;

	@Inject
	CombatAchievementCollector(Client client, ClientThread clientThread, SessionTracker session, TrackerSyncConfig config,
		ScheduledExecutorService executor, TimeSource time)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.session = session;
		this.config = config;
		this.throttle = new Throttle(executor, time, DELAY_MS, MIN_INTERVAL_MS, MIN_INTERVAL_MS,
			() -> clientThread.invoke(this::emit));
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (session.isActive() && config.sendProgress()
			&& (event.getVarbitId() == VarbitID.CA_POINTS || TASK_VARP_SET.contains(event.getVarpId())))
		{
			throttle.trigger();
		}
	}

	@Override
	public void onSessionStart()
	{
		throttle.triggerIn(LOGIN_SETTLE_MS);
		startLoading();
	}

	/** Read the task structs in slices on the client thread (once per client run). */
	void startLoading()
	{
		if (taskDefs != null || loading)
		{
			return;
		}
		loading = true;
		List<int[]> queue = new ArrayList<>();      // {tier, structId}
		List<TaskDef> read = new ArrayList<>();
		int[] next = {0};
		boolean[] listed = {false};
		clientThread.invoke(() ->
		{
			if (!listed[0])
			{
				listed[0] = true;
				for (int t = 0; t < TIER_ENUMS.length; t++)
				{
					EnumComposition e = client.getEnum(TIER_ENUMS[t]);
					if (e == null || e.getIntVals().length == 0)
					{
						finishLoading(Collections.emptyList()); // not the expected cache layout
						return true;
					}
					for (int structId : e.getIntVals())
					{
						queue.add(new int[]{t, structId});
					}
				}
			}
			EnumComposition monsters = client.getEnum(ENUM_MONSTER_NAMES);
			int end = Math.min(queue.size(), next[0] + SLICE);
			for (; next[0] < end; next[0]++)
			{
				int[] q = queue.get(next[0]);
				StructComposition s = client.getStructComposition(q[1]);
				if (s == null)
				{
					continue;
				}
				String name = s.getStringValue(PARAM_TASK_NAME);
				if (name == null || name.isEmpty())
				{
					continue;
				}
				String monster = monsters == null ? null : monsters.getStringValue(s.getIntValue(PARAM_TASK_MONSTER));
				if (monster != null && (monster.isEmpty() || "None".equalsIgnoreCase(monster)))
				{
					monster = null;
				}
				read.add(new TaskDef(s.getIntValue(PARAM_TASK_ID), name, q[0], monster));
			}
			if (next[0] < queue.size())
			{
				return false; // more next client tick
			}
			finishLoading(read);
			return true;
		});
	}

	private void finishLoading(List<TaskDef> read)
	{
		loading = false;
		taskDefs = Collections.unmodifiableList(new ArrayList<>(read));
		throttle.trigger(); // send the tiers and tasks
	}

	@Override
	public void onSessionEnd()
	{
		throttle.cancel();
	}

	public void shutDown()
	{
		throttle.cancel();
	}

	/** Client thread. */
	void emit()
	{
		if (!config.sendProgress() || !session.isActive())
		{
			return;
		}
		int[] bits = new int[TASK_VARPS.length];
		int completed = 0;
		for (int i = 0; i < TASK_VARPS.length; i++)
		{
			bits[i] = client.getVarpValue(TASK_VARPS[i]);
			completed += Integer.bitCount(bits[i]);
		}
		session.emit(EventType.COMBAT_ACHIEVEMENTS, build(client.getVarbitValue(VarbitID.CA_POINTS), completed, bits, taskDefs));
	}

	/** Visible for tests. */
	static CombatAchievementsData build(int points, int completed, int[] bits, List<TaskDef> defs)
	{
		if (defs == null || defs.isEmpty())
		{
			return new CombatAchievementsData(points, completed);
		}
		int[] done = new int[TIER_NAMES.length];
		int[] total = new int[TIER_NAMES.length];
		List<CombatAchievementsData.Task> tasks = new ArrayList<>(defs.size());
		for (TaskDef d : defs)
		{
			boolean isDone = isDone(bits, d.id);
			total[d.tier]++;
			if (isDone)
			{
				done[d.tier]++;
			}
			tasks.add(new CombatAchievementsData.Task(d.id, d.name, TIER_NAMES[d.tier], d.monster, isDone));
		}
		Map<String, CombatAchievementsData.TierCount> tiers = new LinkedHashMap<>();
		int doneTotal = 0;
		for (int t = 0; t < TIER_NAMES.length; t++)
		{
			tiers.put(TIER_NAMES[t], new CombatAchievementsData.TierCount(done[t], total[t]));
			doneTotal += done[t];
		}
		// With the task list known, count real tasks only (stray bits in the varps are ignored).
		return new CombatAchievementsData(points, doneTotal, tiers, tasks);
	}

	static boolean isDone(int[] bits, int taskId)
	{
		int varp = taskId / 32;
		return taskId >= 0 && varp < bits.length && ((bits[varp] >>> (taskId % 32)) & 1) == 1;
	}
}
