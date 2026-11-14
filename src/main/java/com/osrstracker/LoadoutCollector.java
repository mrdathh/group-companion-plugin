package com.osrstracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.osrstracker.model.ContainerItem;
import com.osrstracker.model.EventType;
import com.osrstracker.model.Item;
import com.osrstracker.model.LoadoutsData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Saved gear setups of the Inventory Setups hub plugin (dillydill123/inventory-setups), read from its saved
 * config (group {@code inventorysetups}). The owner chose this feature knowing it reads that plugin's data.
 *
 * <p>Formats read, newest first:
 * <ul>
 * <li>V3 (current): one key {@code setupsV3_<hash>} per setup, JSON {@code {inv, eq, rp?, name, notes?, ...}}
 * with items {@code {id, q?}} or {@code null}; order in {@code setupsOrderV3_} (a JSON list of hashes).</li>
 * <li>V2: key {@code setupsV2}, a JSON list of the same objects (only when no V3 key exists).</li>
 * <li>V1: key {@code setups}, a JSON list of {@code {inventory, equipment, rune_pouch, name, notes}} with items
 * {@code {id, quantity}} (only when neither V3 nor V2 exists).</li>
 * </ul>
 * Sent after login and when that config group changes, at most once per 5 s; not sent when there are no setups.
 */
@Slf4j
@Singleton
public class LoadoutCollector implements SessionTracker.Listener
{
	static final String GROUP = "inventorysetups";
	static final String V3_PREFIX = "setupsV3_";
	static final String V3_ORDER = "setupsOrderV3_";
	static final String V2_KEY = "setupsV2";
	static final String V1_KEY = "setups";
	static final long DELAY_MS = 1_000;
	static final long MIN_INTERVAL_MS = 5_000;
	static final long LOGIN_SETTLE_MS = 3_000;

	// The server's limits for `loadouts` (server/src/ingest/schema.ts, inclusive); over any of them the whole
	// request is rejected with 400, so setups are cut to fit instead (in the plugin's order, then slot order).
	static final int MAX_LOADOUTS = 500;
	static final int MAX_NAME = 200;
	static final int MAX_NOTES = 2000;
	static final int MAX_EQUIPMENT = 50;
	static final int MAX_INVENTORY = 100;
	static final int MAX_RUNE_POUCH = 10;

	/** A parsed setup with raw item ids (resolved to protocol items on the client thread). */
	@Value
	static class RawSetup
	{
		String name;
		String notes;
		/** slot → {id, qty}; slots without an item are absent. */
		Map<Integer, int[]> equipment;
		Map<Integer, int[]> inventory;
		/** null when the setup has no rune pouch. */
		List<int[]> runePouch;
	}

	private final ConfigManager configManager;
	private final SessionTracker session;
	private final TrackerSyncConfig config;
	private final ItemMapper items;
	private final Throttle throttle;

	@Inject
	LoadoutCollector(ConfigManager configManager, ClientThread clientThread, SessionTracker session, TrackerSyncConfig config,
		ItemMapper items, ScheduledExecutorService executor, TimeSource time)
	{
		this.configManager = configManager;
		this.session = session;
		this.config = config;
		this.items = items;
		this.throttle = new Throttle(executor, time, DELAY_MS, MIN_INTERVAL_MS, MIN_INTERVAL_MS,
			() -> clientThread.invoke(this::emit));
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (GROUP.equals(event.getGroup()) && session.isActive())
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

	/** Client thread (item names and values). */
	void emit()
	{
		if (!config.sendStorages() || !session.isActive())
		{
			return;
		}
		List<RawSetup> setups = readSetups();
		if (setups.isEmpty())
		{
			return;
		}
		List<LoadoutsData.Loadout> out = new ArrayList<>();
		for (RawSetup s : fitToServer(setups))
		{
			List<Item> pouch = null;
			if (s.getRunePouch() != null)
			{
				pouch = new ArrayList<>();
				for (int[] r : s.getRunePouch())
				{
					pouch.add(items.toItem(r[0], r[1]));
				}
			}
			out.add(new LoadoutsData.Loadout(s.getName(), s.getName(), "inventory_setups",
				resolve(s.getEquipment(), true), resolve(s.getInventory(), false), pouch,
				s.getNotes() == null || s.getNotes().isEmpty() ? null : s.getNotes()));
		}
		session.emit(EventType.LOADOUTS, new LoadoutsData(out));
	}

	/**
	 * Setups cut to the server's limits: the first {@link #MAX_LOADOUTS}; name (also the id) and notes cut to
	 * their maximum length; equipment, inventory and rune pouch to their first entries. Visible for tests.
	 */
	static List<RawSetup> fitToServer(List<RawSetup> setups)
	{
		List<RawSetup> out = new ArrayList<>();
		for (RawSetup s : setups.size() > MAX_LOADOUTS ? setups.subList(0, MAX_LOADOUTS) : setups)
		{
			List<int[]> pouch = s.getRunePouch();
			out.add(new RawSetup(cut(s.getName(), MAX_NAME), cut(s.getNotes(), MAX_NOTES),
				first(s.getEquipment(), MAX_EQUIPMENT), first(s.getInventory(), MAX_INVENTORY),
				pouch == null || pouch.size() <= MAX_RUNE_POUCH ? pouch : new ArrayList<>(pouch.subList(0, MAX_RUNE_POUCH))));
		}
		if (setups.size() > MAX_LOADOUTS)
		{
			log.info("Tracker Sync: {} Inventory Setups setups over the server's limit of {} were left out",
				setups.size() - MAX_LOADOUTS, MAX_LOADOUTS);
		}
		return out;
	}

	/** At most {@code max} UTF-16 units (as the server counts), never splitting a surrogate pair. */
	private static String cut(String s, int max)
	{
		if (s == null || s.length() <= max)
		{
			return s;
		}
		int end = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
		return s.substring(0, end);
	}

	private static Map<Integer, int[]> first(Map<Integer, int[]> slots, int max)
	{
		if (slots.size() <= max)
		{
			return slots;
		}
		Map<Integer, int[]> out = new LinkedHashMap<>();
		for (Map.Entry<Integer, int[]> e : slots.entrySet())
		{
			if (out.size() == max)
			{
				break;
			}
			out.put(e.getKey(), e.getValue());
		}
		return out;
	}

	private List<ContainerItem> resolve(Map<Integer, int[]> slots, boolean equipment)
	{
		List<ContainerItem> out = new ArrayList<>();
		slots.forEach((slot, it) -> out.add(new ContainerItem(items.toItem(it[0], it[1]), slot,
			equipment ? ContainerCollector.equipSlotName(slot) : null)));
		return out;
	}

	/** All setups in the plugin's order, from the newest format present. */
	List<RawSetup> readSetups()
	{
		List<RawSetup> out = new ArrayList<>();
		List<String> v3Keys = configManager.getConfigurationKeys(ConfigManager.getWholeKey(GROUP, null, V3_PREFIX));
		if (v3Keys != null && !v3Keys.isEmpty())
		{
			Set<String> hashes = new LinkedHashSet<>();
			String order = configManager.getConfiguration(GROUP, V3_ORDER);
			JsonElement orderJson = parse(order);
			if (orderJson != null && orderJson.isJsonArray())
			{
				for (JsonElement h : orderJson.getAsJsonArray())
				{
					hashes.add(h.getAsString());
				}
			}
			String wholePrefix = ConfigManager.getWholeKey(GROUP, null, V3_PREFIX);
			for (String whole : v3Keys)
			{
				hashes.add(whole.substring(wholePrefix.length()));
			}
			Set<String> names = new LinkedHashSet<>();
			for (String hash : hashes)
			{
				RawSetup s = parseSetup(parse(configManager.getConfiguration(GROUP, V3_PREFIX + hash)), false);
				if (s != null && names.add(s.getName()))
				{
					out.add(s);
				}
			}
			return out;
		}
		JsonElement v2 = parse(configManager.getConfiguration(GROUP, V2_KEY));
		if (v2 != null && v2.isJsonArray())
		{
			return parseList(v2.getAsJsonArray(), false);
		}
		JsonElement v1 = parse(configManager.getConfiguration(GROUP, V1_KEY));
		if (v1 != null && v1.isJsonArray())
		{
			return parseList(v1.getAsJsonArray(), true);
		}
		return out;
	}

	private static List<RawSetup> parseList(JsonArray list, boolean v1)
	{
		List<RawSetup> out = new ArrayList<>();
		for (JsonElement e : list)
		{
			RawSetup s = parseSetup(e, v1);
			if (s != null)
			{
				out.add(s);
			}
		}
		return out;
	}

	/** One setup object (V2/V3 short keys, or V1 long keys); null when it is not a setup. */
	static RawSetup parseSetup(JsonElement e, boolean v1)
	{
		if (e == null || !e.isJsonObject())
		{
			return null;
		}
		JsonObject o = e.getAsJsonObject();
		String name = string(o, "name");
		if (name == null || name.isEmpty())
		{
			return null;
		}
		JsonElement rp = o.get(v1 ? "rune_pouch" : "rp");
		List<int[]> pouch = null;
		if (rp != null && rp.isJsonArray())
		{
			pouch = new ArrayList<>(slots(rp, v1).values());
		}
		return new RawSetup(name, string(o, "notes"), slots(o.get(v1 ? "equipment" : "eq"), v1),
			slots(o.get(v1 ? "inventory" : "inv"), v1), pouch);
	}

	/** List of items with nulls / dummies for empty slots → slot → {id, qty}. */
	private static Map<Integer, int[]> slots(JsonElement list, boolean v1)
	{
		Map<Integer, int[]> out = new LinkedHashMap<>();
		if (list == null || !list.isJsonArray())
		{
			return out;
		}
		JsonArray a = list.getAsJsonArray();
		for (int slot = 0; slot < a.size(); slot++)
		{
			JsonElement it = a.get(slot);
			if (it == null || !it.isJsonObject())
			{
				continue;
			}
			JsonObject io = it.getAsJsonObject();
			int id = io.has("id") ? io.get("id").getAsInt() : -1;
			String qtyKey = v1 ? "quantity" : "q";
			int qty = io.has(qtyKey) && !io.get(qtyKey).isJsonNull() ? io.get(qtyKey).getAsInt() : 1;
			if (id > 0 && qty > 0)
			{
				out.put(slot, new int[]{id, qty});
			}
		}
		return out;
	}

	private static String string(JsonObject o, String key)
	{
		JsonElement v = o.get(key);
		return v == null || v.isJsonNull() ? null : v.getAsString();
	}

	private static JsonElement parse(String json)
	{
		if (json == null || json.isEmpty())
		{
			return null;
		}
		try
		{
			return new JsonParser().parse(json);
		}
		catch (JsonSyntaxException | IllegalStateException e)
		{
			log.debug("Unreadable Inventory Setups data", e);
			return null;
		}
	}
}
