package com.osrstracker;

import com.google.common.collect.ImmutableMap;
import com.osrstracker.model.EventType;
import com.osrstracker.model.Item;
import com.osrstracker.model.LootData;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.NPCComposition;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.plugins.loottracker.LootTrackerPlugin;
import net.runelite.client.util.Text;

/**
 * Loot. The primary source is the core Loot Tracker's {@link LootReceived}, which already covers NPC
 * kills (from the server's loot packet), raids/barrows/clue/chest rewards, pickpockets and PvP. When the
 * Loot Tracker plugin is not active, {@link ServerNpcLoot} is used directly so NPC kills still arrive
 * (other sources are then not tracked). Exactly one of the two paths handles a given NPC kill, so a kill
 * is never sent twice.
 *
 * <p>Loot is held until the end of the current game tick so a kill-count chat message from the same tick
 * can be attached, then emitted (well within the 1 s budget).
 */
@Slf4j
@Singleton
public class LootCollector implements SessionTracker.Listener
{
	/** "Your Zulrah kill count is: 12.", "Your completed Chambers of Xeric count is: 3.", "Your Barrows chest count is: 5." */
	static final Pattern KILL_COUNT = Pattern.compile(
		"^Your (?:completed |subdued )?(.+?) (?:kill |chest |completion |success )?count is: ([\\d,]+)");
	/** How long a kill-count message can wait for its loot (raid completion message comes before the chest). */
	static final long KC_MAX_AGE_MS = 5 * 60_000;

	private final SessionTracker session;
	private final TrackerSyncConfig config;
	private final ItemMapper items;
	private final PluginManager pluginManager;
	private final TimeSource time;

	private final List<Held> held = new ArrayList<>();
	private final Map<String, KillCount> killCounts = new HashMap<>();
	private Plugin lootTracker;
	/** For the one info line per session when the first loot event goes out. */
	private boolean firstLootLogged;

	@Inject
	LootCollector(SessionTracker session, TrackerSyncConfig config, ItemMapper items, PluginManager pluginManager, TimeSource time)
	{
		this.session = session;
		this.config = config;
		this.items = items;
		this.pluginManager = pluginManager;
		this.time = time;
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		log.debug("Tracker Sync loot: LootReceived {} {} with {} stack(s)", event.getType(), event.getName(),
			event.getItems() == null ? 0 : event.getItems().size());
		if (!wanted())
		{
			log.debug("Tracker Sync loot: ignored (loot setting {}, session active {})", config.sendLoot(), session.isActive());
			return;
		}
		String type = event.getType() == null ? "UNKNOWN" : event.getType().name();
		String name = event.getName();
		Integer id = null;
		Integer combatLevel = event.getCombatLevel() > 0 ? event.getCombatLevel() : null;
		Object md = event.getMetadata();
		if (md instanceof Integer)
		{
			id = (Integer) md;
		}
		else if (md instanceof Map && ((Map<?, ?>) md).get("id") instanceof Integer)
		{
			id = (Integer) ((Map<?, ?>) md).get("id");
		}
		if ("PLAYER".equals(type))
		{
			// Never send data about other players.
			name = "Player";
			id = null;
			combatLevel = null;
		}
		hold(new LootData.Source(type, name, id, combatLevel), event.getItems());
	}

	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		if (!wanted())
		{
			log.debug("Tracker Sync loot: ServerNpcLoot ignored (loot setting {}, session active {})", config.sendLoot(), session.isActive());
			return;
		}
		if (isLootTrackerActive())
		{
			log.debug("Tracker Sync loot: ServerNpcLoot for {} left to the Loot Tracker's LootReceived", event.getComposition().getName());
			return; // the Loot Tracker reports this kill through LootReceived
		}
		log.debug("Tracker Sync loot: ServerNpcLoot for {} used directly (Loot Tracker plugin not active)", event.getComposition().getName());
		NPCComposition npc = event.getComposition();
		String name = npc.getName() == null ? "Unknown" : Text.removeTags(npc.getName());
		hold(new LootData.Source("NPC", name, npc.getId(), npc.getCombatLevel() > 0 ? npc.getCombatLevel() : null), event.getItems());
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		Matcher m = KILL_COUNT.matcher(Text.removeTags(event.getMessage()));
		if (m.find())
		{
			try
			{
				int kc = Integer.parseInt(m.group(2).replace(",", ""));
				synchronized (this)
				{
					killCounts.put(normalize(m.group(1)), new KillCount(kc, time.nowMillis()));
				}
			}
			catch (NumberFormatException e)
			{
				log.debug("Unparseable kill count: {}", event.getMessage());
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		flushHeld();
	}

	synchronized void flushHeld()
	{
		if (held.isEmpty())
		{
			return;
		}
		for (Held h : held)
		{
			Integer kc = takeKillCount(h.source.getName());
			log.debug("Tracker Sync loot: emit {} {} ({} items, {} gp, kill count {})", h.source.getType(), h.source.getName(),
				h.items.size(), h.totalGe, kc);
			if (!session.isActive())
			{
				log.debug("Tracker Sync loot: dropped, no session");
				continue;
			}
			session.emit(EventType.LOOT, new LootData(h.source, kc, h.items, h.totalGe), h.at);
			if (!firstLootLogged)
			{
				firstLootLogged = true;
				log.info("Tracker Sync: first loot event of this session queued ({} {})", h.source.getType(), h.source.getName());
			}
		}
		held.clear();
	}

	@Override
	public void onSessionStart()
	{
		firstLootLogged = false;
	}

	@Override
	public void onSessionEnd()
	{
		flushHeld();
		synchronized (this)
		{
			killCounts.clear();
		}
	}

	private boolean wanted()
	{
		return config.sendLoot() && session.isActive();
	}

	/** Client thread: builds the items now (ItemManager needs the client thread). */
	private void hold(LootData.Source source, Collection<ItemStack> stacks)
	{
		Map<Integer, Item> merged = new LinkedHashMap<>();
		for (ItemStack s : stacks)
		{
			if (s.getQuantity() <= 0)
			{
				continue;
			}
			Item item = items.toItem(s.getId(), s.getQuantity());
			Item existing = merged.get(item.getId());
			if (existing != null)
			{
				existing.setQty(existing.getQty() + item.getQty());
			}
			else
			{
				merged.put(item.getId(), item);
			}
		}
		if (merged.isEmpty())
		{
			log.debug("Tracker Sync loot: {} {} had no items; not a loot event", source.getType(), source.getName());
			return; // a kill without a drop is not a loot event
		}
		List<Item> list = new ArrayList<>(merged.values());
		long total = 0;
		for (Item i : list)
		{
			total += i.getGe() * i.getQty();
		}
		synchronized (this)
		{
			held.add(new Held(source, list, total, time.now()));
		}
		log.debug("Tracker Sync loot: held {} {} until the end of the tick (kill-count message)", source.getType(), source.getName());
	}

	private boolean isLootTrackerActive()
	{
		if (lootTracker == null)
		{
			for (Plugin p : pluginManager.getPlugins())
			{
				if (p instanceof LootTrackerPlugin)
				{
					lootTracker = p;
					break;
				}
			}
		}
		return lootTracker != null && pluginManager.isPluginActive(lootTracker);
	}

	/**
	 * Loot source names whose kill-count chat line uses another name (normalized). RuneLite files the Gauntlet
	 * reward as the Hunllef NPC, but the game's line is "Your (Corrupted) Gauntlet completion count is: N"; the
	 * Fortis Colosseum reward's line names its final boss.
	 */
	static final Map<String, String> KILL_COUNT_ALIASES = ImmutableMap.of(
		"corrupted hunllef", "corrupted gauntlet",
		"crystalline hunllef", "gauntlet",
		"fortis colosseum", "sol heredit");

	/** Kill count for this loot source, consumed so it is attached to one loot event only. */
	synchronized Integer takeKillCount(String sourceName)
	{
		String name = normalize(sourceName);
		name = KILL_COUNT_ALIASES.getOrDefault(name, name);
		long now = time.nowMillis();
		killCounts.values().removeIf(kc -> now - kc.atMillis > KC_MAX_AGE_MS);
		// An exact name first, so "gauntlet" never takes the "corrupted gauntlet" count or the other way round.
		KillCount exact = killCounts.remove(name);
		if (exact != null)
		{
			return exact.count;
		}
		for (Iterator<Map.Entry<String, KillCount>> it = killCounts.entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<String, KillCount> e = it.next();
			String kcName = e.getKey();
			if (kcName.length() >= 3 && (name.contains(kcName) || kcName.contains(name)))
			{
				it.remove();
				return e.getValue().count;
			}
		}
		return null;
	}

	static String normalize(String name)
	{
		String n = Text.removeTags(name).toLowerCase(Locale.ROOT).trim();
		return n.startsWith("the ") ? n.substring(4) : n;
	}

	private static final class Held
	{
		final LootData.Source source;
		final List<Item> items;
		final long totalGe;
		final Instant at;

		Held(LootData.Source source, List<Item> items, long totalGe, Instant at)
		{
			this.source = source;
			this.items = items;
			this.totalGe = totalGe;
			this.at = at;
		}
	}

	private static final class KillCount
	{
		final int count;
		final long atMillis;

		KillCount(int count, long atMillis)
		{
			this.count = count;
			this.atMillis = atMillis;
		}
	}
}
