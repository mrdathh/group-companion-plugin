/*
 * Prediction and observation logic in this file is ported from RuneLite's Time Tracking plugin
 * (runelite-client 1.13.6: timetracking/farming/FarmingTracker#updateData, #predictPatch, #getTickTime and
 * timetracking/hunter/BirdHouseTracker#updateData). Copyright (c) 2018 Abex; Copyright (c) 2018, Daniel Teo
 * <https://github.com/takuyakanbr>. BSD-2-Clause; the full notice is in THIRD_PARTY_NOTICES.md.
 */
package com.osrstracker.timers;

import com.google.common.collect.ImmutableSet;
import com.osrstracker.SessionTracker;
import com.osrstracker.TimeSource;
import com.osrstracker.TrackerSyncConfig;
import com.osrstracker.model.EventType;
import com.osrstracker.model.IngestEvent;
import com.osrstracker.model.TimersData;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.Player;
import net.runelite.api.WidgetNode;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.WidgetModalMode;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.timetracking.Tab;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;
import net.runelite.client.util.Text;

/**
 * Farming patches, bird houses and Tears of Guthix with predicted ready times.
 *
 * <p>Observations ({@code varbit value:unix seconds}, the core plugin's format) come from two places, and the
 * newer one wins per patch: the core Time Tracking plugin's persisted RS-profile config (group
 * {@code timetracking}, written while that plugin is on), and this plugin's own observations, written to its
 * own RS-profile config ({@code trackersync}, keys {@code timers.*}) while the player stands in a farming
 * region or on Fossil Island. Patches never observed by either are unknown and not sent. The farm-tick
 * offset learned by the core plugin is used when present; this plugin does not learn it.
 *
 * <p>Sent after login, then checked every 30 s (the sender drops unchanged snapshots).
 */
@Singleton
public class TimersCollector implements SessionTracker.Listener
{
	static final long CHECK_INTERVAL_MS = 30_000;
	static final long LOGIN_SETTLE_MS = 3_000;
	static final String OWN_GROUP = TrackerSyncConfig.GROUP;
	public static final String OWN_PREFIX = "timers.";
	static final long BIRD_HOUSE_DURATION_S = 50 * 60;
	static final String TOG_ID = "tears_of_guthix";
	static final String TOG_KEY = OWN_PREFIX + "tog";
	static final long TOG_RESET_DAYS = 7;
	static final String TOG_CAVE_MESSAGE = "Your stories have entertained me. I will let you into the cave for a short time.";
	private static final Set<Integer> FOSSIL_ISLAND_REGIONS = ImmutableSet.of(14650, 14651, 14652, 14906, 14907, 15162, 15163);

	private final Client client;
	private final ClientThread clientThread;
	private final ConfigManager configManager;
	private final SessionTracker session;
	private final TrackerSyncConfig config;
	private final ScheduledExecutorService executor;
	private final TimeSource time;
	private final FarmingWorld farmingWorld;

	private WorldPoint lastTickLocation;
	private ScheduledFuture<?> timer;

	@Inject
	TimersCollector(Client client, ClientThread clientThread, ConfigManager configManager, SessionTracker session,
		TrackerSyncConfig config, ScheduledExecutorService executor, TimeSource time, FarmingWorld farmingWorld)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.configManager = configManager;
		this.session = session;
		this.config = config;
		this.executor = executor;
		this.time = time;
		this.farmingWorld = farmingWorld;
	}

	// ------------------------------------------------------------------ schedule

	@Override
	public synchronized void onSessionStart()
	{
		schedule(LOGIN_SETTLE_MS);
	}

	@Override
	public synchronized void onSessionEnd()
	{
		if (timer != null)
		{
			timer.cancel(false);
			timer = null;
		}
		lastTickLocation = null;
	}

	public void shutDown()
	{
		onSessionEnd();
	}

	private synchronized void schedule(long delayMs)
	{
		if (timer != null)
		{
			timer.cancel(false);
		}
		timer = executor.schedule(this::onTimer, delayMs, TimeUnit.MILLISECONDS);
	}

	private void onTimer()
	{
		synchronized (this)
		{
			if (timer == null)
			{
				return;
			}
			timer = executor.schedule(this::onTimer, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
		}
		clientThread.invoke(this::emit);
	}

	/** Client thread. */
	void emit()
	{
		if (!config.sendTimers() || !session.isActive())
		{
			return;
		}
		long now = time.nowMillis() / 1000;
		List<TimersData.Timer> timers = new ArrayList<>(readTimers(now));
		if (Quest.TEARS_OF_GUTHIX.getState(client) == QuestState.FINISHED)
		{
			timers.add(tearsOfGuthix(now, lastTearsVisit()));
		}
		session.emit(EventType.TIMERS, new TimersData(timers));
	}

	// ------------------------------------------------------------------ Tears of Guthix (weekly)

	/**
	 * Tears of Guthix: technique after the Mystix plugin's TimerMonitor (rjmccaskell/mystix-plugin, BSD-2-Clause):
	 * Juna's "let you into the cave" message (or the minigame's collecting varbit) marks a visit; the next visit
	 * is possible 7 days after the start (00:00 UTC) of the visit's day. The visit time is kept in this plugin's
	 * RS-profile config, so it survives restarts; before the first visit seen by this plugin the state is UNKNOWN.
	 */
	static TimersData.Timer tearsOfGuthix(long now, Long lastVisit)
	{
		if (lastVisit == null)
		{
			return new TimersData.Timer(TOG_ID, "tears_of_guthix", "Weekly", "Tears of Guthix", null, null, "UNKNOWN", null);
		}
		long next = lastVisit - Math.floorMod(lastVisit, 86_400L) + TOG_RESET_DAYS * 86_400L;
		return new TimersData.Timer(TOG_ID, "tears_of_guthix", "Weekly", "Tears of Guthix", null, null,
			now >= next ? "READY" : "GROWING", IngestEvent.formatTime(Instant.ofEpochSecond(next)));
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if ((event.getType() == ChatMessageType.GAMEMESSAGE || event.getType() == ChatMessageType.DIALOG)
			&& event.getMessage() != null && Text.removeTags(event.getMessage()).contains(TOG_CAVE_MESSAGE))
		{
			recordTearsVisit();
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarbitId() == VarbitID.TOG_MINIGAME_COLLECTING && event.getValue() > 0)
		{
			recordTearsVisit();
		}
	}

	private void recordTearsVisit()
	{
		if (!session.isActive() || !config.sendTimers())
		{
			return;
		}
		long now = time.nowMillis() / 1000;
		Long last = lastTearsVisit();
		// One visit per UTC day is enough (the message and the varbit both fire).
		if (last == null || last / 86_400L != now / 86_400L)
		{
			configManager.setRSProfileConfiguration(OWN_GROUP, TOG_KEY, Long.toString(now));
			schedule(1_000);
		}
	}

	private Long lastTearsVisit()
	{
		String v = configManager.getRSProfileConfiguration(OWN_GROUP, TOG_KEY);
		try
		{
			return v == null ? null : Long.parseLong(v.trim());
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	// ------------------------------------------------------------------ observation (ported updateData)

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!config.sendTimers() || !session.isActive())
		{
			return;
		}
		Player player = client.getLocalPlayer();
		WorldPoint previous = lastTickLocation;
		lastTickLocation = player == null ? null : player.getWorldLocation();
		// Like the core plugin: only once the player has been in the same region for a tick.
		if (previous == null || lastTickLocation == null || previous.getRegionID() != lastTickLocation.getRegionID())
		{
			return;
		}
		// Varbits are not sent while a modal interface is open.
		for (WidgetNode node : client.getComponentTable())
		{
			if (node.getModalMode() != WidgetModalMode.NON_MODAL)
			{
				return;
			}
		}
		long now = time.nowMillis() / 1000;
		observeFarming(previous, now);
		observeBirdHouses(previous, now);
	}

	private void observeFarming(WorldPoint location, long now)
	{
		for (FarmingRegion region : farmingWorld.getRegionsForLocation(location))
		{
			for (FarmingPatch patch : region.getPatches())
			{
				int value = client.getVarbitValue(patch.getVarbit());
				if (patch.getImplementation().forVarbitValue(value) == null)
				{
					continue;
				}
				String key = OWN_PREFIX + patch.configKey();
				Observation stored = Observation.parse(configManager.getRSProfileConfiguration(OWN_GROUP, key));
				// Rewrite when changed, or every 5 minutes to keep the observation fresh (as the core plugin does).
				if (stored != null && stored.value == value && stored.time + 5 * 60 > now && now + 30 > stored.time)
				{
					continue;
				}
				configManager.setRSProfileConfiguration(OWN_GROUP, key, value + ":" + now);
			}
		}
	}

	private void observeBirdHouses(WorldPoint location, long now)
	{
		if (!FOSSIL_ISLAND_REGIONS.contains(location.getRegionID()) || location.getPlane() != 0)
		{
			return;
		}
		int removals = 0;
		int[] values = new int[BirdHouseSpace.values().length];
		Observation[] old = new Observation[values.length];
		for (BirdHouseSpace space : BirdHouseSpace.values())
		{
			int i = space.ordinal();
			values[i] = client.getVarpValue(space.getVarp());
			old[i] = newest(birdHouseCoreKey(space), OWN_PREFIX + birdHouseCoreKey(space));
			int oldValue = old[i] == null ? -1 : old[i].value;
			if (values[i] <= 0 && oldValue > 0)
			{
				removals++;
			}
		}
		// Same guard as the core plugin: varps may not be loaded yet right after entering the region.
		if (removals > 2)
		{
			return;
		}
		for (BirdHouseSpace space : BirdHouseSpace.values())
		{
			int i = space.ordinal();
			if (old[i] == null || old[i].value != values[i])
			{
				configManager.setRSProfileConfiguration(OWN_GROUP, OWN_PREFIX + birdHouseCoreKey(space), values[i] + ":" + now);
			}
		}
	}

	private static String birdHouseCoreKey(BirdHouseSpace space)
	{
		return TimeTrackingConfig.BIRD_HOUSE + "." + space.getVarp();
	}

	// ------------------------------------------------------------------ prediction (ported predictPatch)

	/** Client thread (reads the autoweed varbit). */
	List<TimersData.Timer> readTimers(long now)
	{
		List<TimersData.Timer> out = new ArrayList<>();
		boolean autoweed = client.getVarbitValue(VarbitID.FARMING_BLOCKWEEDS) == 1;
		Set<String> ids = new HashSet<>();
		for (Map.Entry<Tab, Set<FarmingPatch>> tab : farmingWorld.getTabs().entrySet())
		{
			for (FarmingPatch patch : tab.getValue())
			{
				Observation obs = newest(patch.configKey(), OWN_PREFIX + patch.configKey());
				TimersData.Timer t = obs == null ? null : farmingTimer(tab.getKey(), patch, obs, autoweed, now, ids);
				if (t != null)
				{
					out.add(t);
				}
			}
		}
		for (BirdHouseSpace space : BirdHouseSpace.values())
		{
			Observation obs = newest(birdHouseCoreKey(space), OWN_PREFIX + birdHouseCoreKey(space));
			if (obs != null)
			{
				out.add(birdHouseTimer(space, obs, now));
			}
		}
		out.sort(Comparator.comparing(TimersData.Timer::getId));
		return out;
	}

	private TimersData.Timer farmingTimer(Tab tab, FarmingPatch patch, Observation obs, boolean autoweed, long now, Set<String> ids)
	{
		PatchState state = patch.getImplementation().forVarbitValue(obs.value);
		if (state == null)
		{
			return null;
		}
		int stage = state.getStage();
		int stages = state.getStages();
		int tickrate = state.getTickRate();
		if (autoweed && state.getProduce() == Produce.WEEDS)
		{
			stage = 0;
			stages = 1;
			tickrate = 0;
		}
		long doneEstimate = 0;
		if (tickrate > 0)
		{
			long tickTime = tickTime(tickrate, 0, obs.time);
			doneEstimate = tickTime(tickrate, stages - 1 - stage, tickTime);
		}

		Produce produce = state.getProduce();
		CropState crop = state.getCropState();
		String wireState;
		Long readyAt = null;
		if (produce == Produce.WEEDS || crop == CropState.EMPTY || crop == CropState.FILLING)
		{
			wireState = "EMPTY";
		}
		else
		{
			switch (crop)
			{
				case HARVESTABLE:
					wireState = "READY";
					readyAt = doneEstimate > 0 ? doneEstimate : obs.time;
					break;
				case GROWING:
					if (doneEstimate > 0)
					{
						readyAt = doneEstimate;
						wireState = doneEstimate <= now ? "READY" : "GROWING";
					}
					else
					{
						wireState = "GROWING";
					}
					break;
				case DISEASED:
					wireState = "DISEASED";
					break;
				case DEAD:
					wireState = "DEAD";
					break;
				default:
					wireState = "UNKNOWN";
			}
		}
		boolean planted = produce != Produce.WEEDS && !"EMPTY".equals(wireState);

		String place = patch.getRegion().getName() + (patch.getName().isEmpty() ? "" : " (" + patch.getName() + ")");
		String id = "farming:" + slug(tab.name()) + ":" + slug(patch.getRegion().getName())
			+ (patch.getName().isEmpty() ? "" : "-" + slug(patch.getName()));
		if (!ids.add(id))
		{
			id = id + "-" + patch.getVarbit();
			ids.add(id);
		}
		return new TimersData.Timer(id, "farming", category(tab), place,
			planted ? produce.getName() : null,
			planted && produce.getItemID() > 0 ? produce.getItemID() : null,
			wireState, readyAt == null ? null : IngestEvent.formatTime(Instant.ofEpochSecond(readyAt)));
	}

	private TimersData.Timer birdHouseTimer(BirdHouseSpace space, Observation obs, long now)
	{
		BirdHouseState state = BirdHouseState.fromVarpValue(obs.value);
		BirdHouse house = BirdHouse.fromVarpValue(obs.value);
		String wireState;
		String readyAt = null;
		switch (state)
		{
			case SEEDED:
				long done = obs.time + BIRD_HOUSE_DURATION_S;
				wireState = done <= now ? "READY" : "GROWING";
				readyAt = IngestEvent.formatTime(Instant.ofEpochSecond(done));
				break;
			case BUILT:
			case EMPTY:
				wireState = "EMPTY";
				break;
			default:
				wireState = "UNKNOWN";
		}
		return new TimersData.Timer("birdhouse:" + slug(space.name()), "birdhouse", "Bird house", space.getName(),
			house == null ? null : house.getName(), house == null ? null : house.getItemID(), wireState, readyAt);
	}

	/** Ported FarmingTracker#getTickTime, using the farm tick offset the core plugin learned (if any). */
	long tickTime(int tickRate, int ticks, long requestedTime)
	{
		long offset = 0;
		String profile = configManager.getRSProfileKey();
		if (profile != null)
		{
			Integer precision = parseInt(configManager.getConfiguration(TimeTrackingConfig.CONFIG_GROUP, profile, TimeTrackingConfig.FARM_TICK_OFFSET_PRECISION));
			Integer minutes = parseInt(configManager.getConfiguration(TimeTrackingConfig.CONFIG_GROUP, profile, TimeTrackingConfig.FARM_TICK_OFFSET));
			if (precision != null && minutes != null && (precision >= tickRate || precision >= 40))
			{
				offset = (minutes % tickRate) * 60L;
			}
		}
		long unixNow = requestedTime + offset;
		long currentTick = unixNow - (unixNow % (tickRate * 60L));
		return currentTick + (ticks * tickRate * 60L) - offset;
	}

	/** Newest of the core plugin's observation and ours. */
	private Observation newest(String coreKey, String ownKey)
	{
		Observation core = Observation.parse(configManager.getRSProfileConfiguration(TimeTrackingConfig.CONFIG_GROUP, coreKey));
		Observation own = Observation.parse(configManager.getRSProfileConfiguration(OWN_GROUP, ownKey));
		if (core == null)
		{
			return own;
		}
		if (own == null)
		{
			return core;
		}
		return own.time >= core.time ? own : core;
	}

	static String category(Tab tab)
	{
		String n = tab.getName();
		return n.endsWith(" Patches") ? n.substring(0, n.length() - " Patches".length()) : n;
	}

	static String slug(String s)
	{
		return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
	}

	private static Integer parseInt(String s)
	{
		try
		{
			return s == null ? null : Integer.parseInt(s.trim());
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/** "value:unixSeconds". */
	static final class Observation
	{
		final int value;
		final long time;

		Observation(int value, long time)
		{
			this.value = value;
			this.time = time;
		}

		static Observation parse(String stored)
		{
			if (stored == null)
			{
				return null;
			}
			String[] parts = stored.split(":");
			if (parts.length != 2)
			{
				return null;
			}
			try
			{
				long t = Long.parseLong(parts[1]);
				return t > 0 ? new Observation(Integer.parseInt(parts[0]), t) : null;
			}
			catch (NumberFormatException e)
			{
				return null;
			}
		}
	}

	/** For tests: the whole list of known patches. */
	Collection<Set<FarmingPatch>> allPatches()
	{
		return farmingWorld.getTabs().values();
	}
}
