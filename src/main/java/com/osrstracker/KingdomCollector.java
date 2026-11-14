package com.osrstracker;

import com.google.common.collect.ImmutableSet;
import com.osrstracker.model.EventType;
import com.osrstracker.model.KingdomData;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Kingdom of Miscellania (under the "Timers" setting). The kingdom varbits come with the other player vars at
 * login, so they are readable anywhere (technique after the Mystix plugin's KingdomMonitor,
 * rjmccaskell/mystix-plugin, BSD-2-Clause): approval points out of 127 (shown as 0–100), coffer coins,
 * workers per resource. Nothing is sent before Throne of Miscellania is finished, nor for an approval of 0 (vars
 * not loaded yet; the floor after the quest is 25%). Sent 5 s after login, then when a kingdom varbit changes,
 * at most once per 5 s.
 */
@Singleton
public class KingdomCollector implements SessionTracker.Listener
{
	static final long DELAY_MS = 1_000;
	static final long MIN_INTERVAL_MS = 5_000;
	static final long LOGIN_SETTLE_MS = 5_000;
	static final int APPROVAL_MAX = 127;

	static final Set<Integer> KINGDOM_VARBITS = ImmutableSet.of(
		VarbitID.MISC_APPROVAL, VarbitID.MISC_COFFERS, VarbitID.MISC_POINTS_WOOD, VarbitID.MISC_POINTS_HERB,
		VarbitID.MISC_POINTS_FISH, VarbitID.MISC_POINTS_MINE, VarbitID.MISC_POINTS_RAREWOOD, VarbitID.MISC_POINTS_FARM);

	private final Client client;
	private final SessionTracker session;
	private final TrackerSyncConfig config;
	private final Throttle throttle;

	@Inject
	KingdomCollector(Client client, ClientThread clientThread, SessionTracker session, TrackerSyncConfig config,
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
		if (session.isActive() && config.sendTimers() && KINGDOM_VARBITS.contains(event.getVarbitId()))
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

	/** Client thread (Quest.getState runs a script). */
	void emit()
	{
		if (!config.sendTimers() || !session.isActive()
			|| Quest.THRONE_OF_MISCELLANIA.getState(client) != QuestState.FINISHED)
		{
			return;
		}
		KingdomData data = read(client.getVarbitValue(VarbitID.MISC_APPROVAL), client.getVarbitValue(VarbitID.MISC_COFFERS),
			client.getVarbitValue(VarbitID.MISC_POINTS_WOOD), client.getVarbitValue(VarbitID.MISC_POINTS_HERB),
			client.getVarbitValue(VarbitID.MISC_POINTS_FISH), client.getVarbitValue(VarbitID.MISC_POINTS_MINE),
			client.getVarbitValue(VarbitID.MISC_POINTS_RAREWOOD), client.getVarbitValue(VarbitID.MISC_POINTS_FARM),
			Quest.ROYAL_TROUBLE.getState(client) == QuestState.FINISHED);
		if (data != null)
		{
			session.emit(EventType.KINGDOM, data);
		}
	}

	/** Null when the approval var is not loaded yet. Visible for tests. */
	static KingdomData read(int approvalPoints, int coffer, int wood, int herbs, int fish, int mining, int hardwood,
		int farming, boolean royalTrouble)
	{
		if (approvalPoints <= 0)
		{
			return null;
		}
		Map<String, Integer> workers = new LinkedHashMap<>();
		workers.put("Wood", wood);
		workers.put("Herbs/flax", herbs);
		workers.put("Fishing", fish);
		workers.put("Mining", mining);
		workers.put("Hardwood", hardwood);
		workers.put("Farming", farming);
		int approval = Math.round(Math.min(approvalPoints, APPROVAL_MAX) * 100f / APPROVAL_MAX);
		return new KingdomData(approval, coffer, workers, royalTrouble);
	}
}
