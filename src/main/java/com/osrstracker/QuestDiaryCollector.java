package com.osrstracker;

import com.osrstracker.model.DiariesData;
import com.osrstracker.model.EventType;
import com.osrstracker.model.QuestsData;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Quests (Quest enum states + quest points) and achievement diaries (completion per region/tier).
 * Quests are read 5 s after login, then every 60 s (the sender drops unchanged snapshots). Diaries are read
 * 5 s after login, then when a diary varbit changes, at most once per 5 s.
 */
@Singleton
public class QuestDiaryCollector implements SessionTracker.Listener
{
	static final long LOGIN_SETTLE_MS = 5_000;
	static final long QUEST_INTERVAL_MS = 60_000;
	static final long DELAY_MS = 1_000;
	static final long MIN_INTERVAL_MS = 5_000;

	/** region -> tier -> {all-tasks-complete varbit, reward-claimed varbit}. */
	static final Map<String, Map<String, int[]>> DIARIES = new LinkedHashMap<>();
	private static final Set<Integer> DIARY_VARBITS = new HashSet<>();

	static
	{
		diary("Ardougne",
			VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_EASY_REWARD,
			VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, VarbitID.ARDOUGNE_MEDIUM_REWARD,
			VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_HARD_REWARD,
			VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE, VarbitID.ARDOUGNE_ELITE_REWARD);
		diary("Desert",
			VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_EASY_REWARD,
			VarbitID.DESERT_DIARY_MEDIUM_COMPLETE, VarbitID.DESERT_MEDIUM_REWARD,
			VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_HARD_REWARD,
			VarbitID.DESERT_DIARY_ELITE_COMPLETE, VarbitID.DESERT_ELITE_REWARD);
		diary("Falador",
			VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_EASY_REWARD,
			VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE, VarbitID.FALADOR_MEDIUM_REWARD,
			VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_HARD_REWARD,
			VarbitID.FALADOR_DIARY_ELITE_COMPLETE, VarbitID.FALADOR_ELITE_REWARD);
		diary("Fremennik",
			VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_EASY_REWARD,
			VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE, VarbitID.FREMENNIK_MEDIUM_REWARD,
			VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_HARD_REWARD,
			VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE, VarbitID.FREMENNIK_ELITE_REWARD);
		diary("Kandarin",
			VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_EASY_REWARD,
			VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE, VarbitID.KANDARIN_MEDIUM_REWARD,
			VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_HARD_REWARD,
			VarbitID.KANDARIN_DIARY_ELITE_COMPLETE, VarbitID.KANDARIN_ELITE_REWARD);
		diary("Karamja",
			VarbitID.ATJUN_EASY_DONE, VarbitID.ATJUN_EASY_REWARD,
			VarbitID.ATJUN_MED_DONE, VarbitID.ATJUN_MED_REWARD,
			VarbitID.ATJUN_HARD_DONE, VarbitID.ATJUN_HARD_REWARD,
			VarbitID.KARAMJA_DIARY_ELITE_COMPLETE, VarbitID.KARAMJA_ELITE_REWARD);
		diary("Kourend & Kebos",
			VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_EASY_REWARD,
			VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE, VarbitID.KOUREND_MEDIUM_REWARD,
			VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_HARD_REWARD,
			VarbitID.KOUREND_DIARY_ELITE_COMPLETE, VarbitID.KOUREND_ELITE_REWARD);
		diary("Lumbridge & Draynor",
			VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_EASY_REWARD,
			VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE, VarbitID.LUMBRIDGE_MEDIUM_REWARD,
			VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_HARD_REWARD,
			VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE, VarbitID.LUMBRIDGE_ELITE_REWARD);
		diary("Morytania",
			VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_EASY_REWARD,
			VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE, VarbitID.MORYTANIA_MEDIUM_REWARD,
			VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_HARD_REWARD,
			VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE, VarbitID.MORYTANIA_ELITE_REWARD);
		diary("Varrock",
			VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_EASY_REWARD,
			VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE, VarbitID.VARROCK_MEDIUM_REWARD,
			VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_HARD_REWARD,
			VarbitID.VARROCK_DIARY_ELITE_COMPLETE, VarbitID.VARROCK_ELITE_REWARD);
		diary("Western Provinces",
			VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_EASY_REWARD,
			VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE, VarbitID.WESTERN_MEDIUM_REWARD,
			VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_HARD_REWARD,
			VarbitID.WESTERN_DIARY_ELITE_COMPLETE, VarbitID.WESTERN_ELITE_REWARD);
		diary("Wilderness",
			VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_EASY_REWARD,
			VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE, VarbitID.WILDERNESS_MEDIUM_REWARD,
			VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_HARD_REWARD,
			VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE, VarbitID.WILDERNESS_ELITE_REWARD);
	}

	private static void diary(String region, int... completeAndReward)
	{
		String[] tiers = {"Easy", "Medium", "Hard", "Elite"};
		Map<String, int[]> byTier = new LinkedHashMap<>();
		for (int i = 0; i < tiers.length; i++)
		{
			int complete = completeAndReward[i * 2];
			int reward = completeAndReward[i * 2 + 1];
			byTier.put(tiers[i], new int[]{complete, reward});
			DIARY_VARBITS.add(complete);
			DIARY_VARBITS.add(reward);
		}
		DIARIES.put(region, byTier);
	}

	private final Client client;
	private final SessionTracker session;
	private final TrackerSyncConfig config;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final Throttle diaries;
	private ScheduledFuture<?> questTimer;

	@Inject
	QuestDiaryCollector(Client client, ClientThread clientThread, SessionTracker session, TrackerSyncConfig config,
		ScheduledExecutorService executor, TimeSource time)
	{
		this.client = client;
		this.session = session;
		this.config = config;
		this.clientThread = clientThread;
		this.executor = executor;
		this.diaries = new Throttle(executor, time, DELAY_MS, MIN_INTERVAL_MS, MIN_INTERVAL_MS,
			() -> clientThread.invoke(this::emitDiaries));
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (!session.isActive() || !config.sendQuests())
		{
			return;
		}
		if (DIARY_VARBITS.contains(event.getVarbitId()))
		{
			diaries.trigger();
		}
	}

	@Override
	public synchronized void onSessionStart()
	{
		scheduleQuests(LOGIN_SETTLE_MS);
		diaries.triggerIn(LOGIN_SETTLE_MS);
	}

	@Override
	public synchronized void onSessionEnd()
	{
		if (questTimer != null)
		{
			questTimer.cancel(false);
			questTimer = null;
		}
		diaries.cancel();
	}

	private synchronized void scheduleQuests(long delayMs)
	{
		if (questTimer != null)
		{
			questTimer.cancel(false);
		}
		questTimer = executor.schedule(this::onQuestTimer, delayMs, TimeUnit.MILLISECONDS);
	}

	private void onQuestTimer()
	{
		synchronized (this)
		{
			if (questTimer == null)
			{
				return; // cancelled
			}
			questTimer = executor.schedule(this::onQuestTimer, QUEST_INTERVAL_MS, TimeUnit.MILLISECONDS);
		}
		clientThread.invoke(this::emitQuests);
	}

	public void shutDown()
	{
		onSessionEnd();
	}

	/** Client thread (Quest.getState runs a client script). */
	void emitQuests()
	{
		if (!config.sendQuests() || !session.isActive())
		{
			return;
		}
		Map<String, String> states = new LinkedHashMap<>();
		for (Quest quest : Quest.values())
		{
			states.put(quest.getName(), quest.getState(client).name());
		}
		session.emit(EventType.QUESTS, new QuestsData(states, client.getVarpValue(VarPlayerID.QP)));
	}

	/** Client thread. */
	void emitDiaries()
	{
		if (!config.sendQuests() || !session.isActive())
		{
			return;
		}
		session.emit(EventType.DIARIES, readDiaries());
	}

	DiariesData readDiaries()
	{
		Map<String, Map<String, DiariesData.TierProgress>> out = new LinkedHashMap<>();
		DIARIES.forEach((region, tiers) ->
		{
			Map<String, DiariesData.TierProgress> byTier = new LinkedHashMap<>();
			tiers.forEach((tier, vars) ->
			{
				boolean complete = client.getVarbitValue(vars[0]) == 1 || client.getVarbitValue(vars[1]) == 1;
				byTier.put(tier, new DiariesData.TierProgress(complete, null, null));
			});
			out.put(region, byTier);
		});
		return new DiariesData(out);
	}
}
