package com.osrstracker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.osrstracker.model.EventType;
import com.osrstracker.model.IngestEvent;
import com.osrstracker.model.Item;
import com.osrstracker.model.LootHistoryData;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;

/**
 * Loot history ({@code loot_history}, under "Loot"): what RuneLite core's Loot Tracker plugin has stored for the
 * current RuneScape profile (RS-profile config group {@code loottracker}, keys {@code drops_<TYPE>_<name>}, each a
 * JSON {@code ConfigLoot}: {@code type}, {@code name}, {@code kills}, {@code first}, {@code last} (epoch millis), and
 * {@code drops} = [itemId, qty, itemId, qty, …]). The Loot Tracker writes these on RuneLite's config sync, on profile
 * switch, when it stops and on client shutdown. Technique after the Mystix plugin's LootMonitor bulk sync
 * (rjmccaskell/mystix-plugin, BSD-2-Clause); own code.
 *
 * <p>Sent 10 s after login and 2 s after those keys change, at most once per 60 s; the sender drops unchanged
 * snapshots. PvP sources (named after other players) are merged into one source "Player". Items get canonical ids,
 * names and current prices on the client thread. A snapshot over {@link #MAX_BYTES} is not sent (logged), never
 * truncated.
 */
@Slf4j
@Singleton
public class LootHistoryCollector implements SessionTracker.Listener
{
	static final String GROUP = "loottracker";
	static final String PREFIX = "drops_";
	static final long DELAY_MS = 2_000;
	static final long MIN_INTERVAL_MS = 60_000;
	static final long LOGIN_SETTLE_MS = 10_000;
	static final int MAX_BYTES = 1_500_000;

	/** One stored record, before item lookups. */
	@Value
	static class Stored
	{
		String type;
		String name;
		int kills;
		Long first;
		Long last;
		/** raw item id → qty */
		Map<Integer, Long> drops;
	}

	private final ConfigManager configManager;
	private final SessionTracker session;
	private final TrackerSyncConfig config;
	private final ItemMapper items;
	private final Gson gson;
	private final Throttle throttle;

	@Inject
	LootHistoryCollector(ConfigManager configManager, ClientThread clientThread, SessionTracker session, TrackerSyncConfig config,
		ItemMapper items, Gson gson, ScheduledExecutorService executor, TimeSource time)
	{
		this.configManager = configManager;
		this.session = session;
		this.config = config;
		this.items = items;
		this.gson = gson;
		this.throttle = new Throttle(executor, time, DELAY_MS, MIN_INTERVAL_MS, MIN_INTERVAL_MS,
			() -> clientThread.invoke(this::emit));
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (GROUP.equals(event.getGroup()) && event.getKey() != null && event.getKey().startsWith(PREFIX)
			&& session.isActive() && config.sendLoot())
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

	/** Client thread (item names and prices). */
	void emit()
	{
		if (!config.sendLoot() || !session.isActive())
		{
			return;
		}
		String profile = configManager.getRSProfileKey();
		if (profile == null)
		{
			return;
		}
		List<Stored> stored = new ArrayList<>();
		for (String key : configManager.getRSProfileConfigurationKeys(GROUP, profile, PREFIX))
		{
			Stored s = parse(configManager.getConfiguration(GROUP, profile, key));
			if (s != null)
			{
				stored.add(s);
			}
		}
		if (stored.isEmpty())
		{
			log.debug("Tracker Sync: no stored Loot Tracker history for this profile");
			return;
		}
		LootHistoryData data = build(stored);
		int bytes = gson.toJson(data).getBytes(StandardCharsets.UTF_8).length;
		if (bytes > MAX_BYTES)
		{
			log.warn("Tracker Sync: loot history is {} bytes ({} sources), over the {} byte limit; not sent", bytes,
				data.getSources().size(), MAX_BYTES);
			return;
		}
		log.debug("Tracker Sync: loot history with {} sources, {} bytes", data.getSources().size(), bytes);
		session.emit(EventType.LOOT_HISTORY, data);
	}

	/** Client thread. Merges PvP sources into one anonymous "Player" source; sorted by type and name. */
	LootHistoryData build(List<Stored> stored)
	{
		Map<String, Stored> merged = new TreeMap<>();
		for (Stored s : stored)
		{
			Stored use = "PLAYER".equals(s.getType()) ? new Stored("PLAYER", "Player", s.getKills(), s.getFirst(), s.getLast(), s.getDrops()) : s;
			merged.merge(use.getType() + "|" + use.getName(), use, LootHistoryCollector::combine);
		}
		List<LootHistoryData.Source> sources = new ArrayList<>();
		for (Stored s : merged.values())
		{
			Map<Integer, Item> byId = new TreeMap<>();
			for (Map.Entry<Integer, Long> d : s.getDrops().entrySet())
			{
				int qty = (int) Math.min(Integer.MAX_VALUE, d.getValue());
				Item it = items.toItem(d.getKey(), qty);
				Item existing = byId.get(it.getId());
				if (existing == null)
				{
					byId.put(it.getId(), it);
				}
				else
				{
					existing.setQty((int) Math.min(Integer.MAX_VALUE, (long) existing.getQty() + it.getQty()));
				}
			}
			sources.add(new LootHistoryData.Source(s.getType(), s.getName(), s.getKills(), iso(s.getFirst()), iso(s.getLast()),
				new ArrayList<>(byId.values())));
		}
		sources.sort(Comparator.comparing(LootHistoryData.Source::getType).thenComparing(LootHistoryData.Source::getName));
		return new LootHistoryData(sources);
	}

	private static Stored combine(Stored a, Stored b)
	{
		Map<Integer, Long> drops = new LinkedHashMap<>(a.getDrops());
		b.getDrops().forEach((id, qty) -> drops.merge(id, qty, Long::sum));
		return new Stored(a.getType(), a.getName(), a.getKills() + b.getKills(), min(a.getFirst(), b.getFirst()),
			max(a.getLast(), b.getLast()), drops);
	}

	/** One stored {@code ConfigLoot} JSON; null when it is not one. Visible for tests. */
	static Stored parse(String json)
	{
		if (json == null || json.isEmpty())
		{
			return null;
		}
		try
		{
			JsonElement e = new JsonParser().parse(json);
			if (!e.isJsonObject())
			{
				return null;
			}
			JsonObject o = e.getAsJsonObject();
			String type = o.has("type") && !o.get("type").isJsonNull() ? o.get("type").getAsString() : "UNKNOWN";
			String name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : null;
			if (name == null || name.isEmpty())
			{
				return null;
			}
			int kills = o.has("kills") ? o.get("kills").getAsInt() : 0;
			Map<Integer, Long> drops = new LinkedHashMap<>();
			JsonElement d = o.get("drops");
			if (d != null && d.isJsonArray())
			{
				JsonArray a = d.getAsJsonArray();
				for (int i = 0; i + 1 < a.size(); i += 2)
				{
					int id = a.get(i).getAsInt();
					long qty = a.get(i + 1).getAsLong();
					if (id >= 0 && qty > 0)
					{
						drops.merge(id, qty, Long::sum);
					}
				}
			}
			return new Stored(knownType(type), name, Math.max(0, kills), instant(o.get("first")), instant(o.get("last")), drops);
		}
		catch (RuntimeException ex)
		{
			log.debug("Unreadable Loot Tracker record", ex);
			return null;
		}
	}

	private static String knownType(String t)
	{
		switch (t)
		{
			case "NPC":
			case "EVENT":
			case "PICKPOCKET":
			case "PLAYER":
				return t;
			default:
				return "UNKNOWN";
		}
	}

	/** RuneLite's Gson stores an Instant as epoch millis, older data as {seconds, nanos}. */
	private static Long instant(JsonElement e)
	{
		if (e == null || e.isJsonNull())
		{
			return null;
		}
		if (e.isJsonPrimitive())
		{
			return e.getAsLong();
		}
		if (e.isJsonObject() && e.getAsJsonObject().has("seconds"))
		{
			JsonObject o = e.getAsJsonObject();
			long nanos = o.has("nanos") ? o.get("nanos").getAsLong() : 0;
			return o.get("seconds").getAsLong() * 1000 + nanos / 1_000_000;
		}
		return null;
	}

	private static String iso(Long millis)
	{
		return millis == null ? null : IngestEvent.formatTime(Instant.ofEpochMilli(millis));
	}

	private static Long min(Long a, Long b)
	{
		return a == null ? b : b == null ? a : Math.min(a, b);
	}

	private static Long max(Long a, Long b)
	{
		return a == null ? b : b == null ? a : Math.max(a, b);
	}
}
