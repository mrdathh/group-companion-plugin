package com.osrstracker;

import com.osrstracker.model.EventType;
import com.osrstracker.model.SlayerData;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
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
 * from varps and the game's slayer DB tables, so this works without the core Slayer plugin. Sent after login
 * and when any of those vars change, at most once per 2 s.
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

	private final Client client;
	private final SessionTracker session;
	private final TrackerSyncConfig config;
	private final Throttle throttle;

	@Inject
	SlayerCollector(Client client, ClientThread clientThread, SessionTracker session, TrackerSyncConfig config,
		ScheduledExecutorService executor, TimeSource time)
	{
		this.client = client;
		this.session = session;
		this.config = config;
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
			|| varbitId == VarbitID.SLAYER_MASTER;
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
		session.emit(EventType.SLAYER, new SlayerData(readTask(), client.getVarbitValue(VarbitID.SLAYER_POINTS), readStreak()));
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
