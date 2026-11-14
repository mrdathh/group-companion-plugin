package com.osrstracker;

import com.osrstracker.model.CombatAchievementsData;
import com.osrstracker.model.EventType;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Combat achievements: points from the {@code CA_POINTS} varbit, and the number of completed tasks as the
 * number of set bits in the game's task-completion varps ({@code CA_TASK_COMPLETED_0..20}, one bit per task
 * id). Tiers and the task list are not sent: they need the task structs from the cache, whose ids are not
 * part of RuneLite's gameval constants. Read after login and when those vars change, at most once per 5 s.
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

	private final Client client;
	private final SessionTracker session;
	private final TrackerSyncConfig config;
	private final Throttle throttle;

	@Inject
	CombatAchievementCollector(Client client, ClientThread clientThread, SessionTracker session, TrackerSyncConfig config,
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
		int completed = 0;
		for (int varp : TASK_VARPS)
		{
			completed += Integer.bitCount(client.getVarpValue(varp));
		}
		session.emit(EventType.COMBAT_ACHIEVEMENTS, new CombatAchievementsData(client.getVarbitValue(VarbitID.CA_POINTS), completed));
	}
}
