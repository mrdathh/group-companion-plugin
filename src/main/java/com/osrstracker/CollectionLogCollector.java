package com.osrstracker;

import com.osrstracker.model.ClogItemData;
import com.osrstracker.model.CollectionLogData;
import com.osrstracker.model.EventType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.StructComposition;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Collection log.
 *
 * <ul>
 * <li>{@code clog_item}: the game's "New item added to your collection log" chat message (requires the
 * in-game collection log chat notification setting).</li>
 * <li>{@code collection_log}: the reviewed behaviour of the Group Ironmen Tracker hub plugin
 * (christoabrown/group-ironmen-tracker, BSD-2-Clause, CollectionLogWidgetSubscriber): when the player's own
 * collection log opens (script 7797), the log's search is opened, which makes the game run script 4100 once
 * per obtained item with the item id and quantity as arguments. Never for a house adventure log (someone
 * else's log), at most once per 500 game ticks, reset on logout. The values are sent on the next game tick.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class CollectionLogCollector implements SessionTracker.Listener
{
	static final int SCRIPT_COLLECTION_SETUP = 7797;
	static final int SCRIPT_COLLECTION_DRAW_ITEM = 4100;
	static final int SCRIPT_COLLECTION_SEARCH = 2240;
	/** At most one automatic read per this many game ticks. */
	static final int MIN_TICKS_BETWEEN_READS = 500;
	/** Give up if the search did not draw any item within this many ticks. */
	static final int READ_TIMEOUT_TICKS = 5;

	static final Pattern NEW_ITEM = Pattern.compile("^New item added to your collection log: (.+?)\\s*$");

	private final Client client;
	private final SessionTracker session;
	private final TrackerSyncConfig config;
	private final ItemMapper items;

	private boolean reading;
	private int lastReadTick = -1;
	private int lastDrawTick = -1;
	private final Map<Integer, Integer> readItems = new TreeMap<>();
	private final List<String> pendingNewItems = new ArrayList<>();

	@Inject
	CollectionLogCollector(Client client, SessionTracker session, TrackerSyncConfig config, ItemMapper items)
	{
		this.client = client;
		this.session = session;
		this.config = config;
		this.items = items;
	}

	// ------------------------------------------------------------------ clog_item

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE || !config.sendCollectionLog() || !session.isActive())
		{
			return;
		}
		Matcher m = NEW_ITEM.matcher(Text.removeTags(event.getMessage()));
		if (m.find())
		{
			// Resolved on the tick so the loot / inventory change from the same tick has registered the id.
			synchronized (pendingNewItems)
			{
				pendingNewItems.add(m.group(1));
			}
		}
	}

	private void emitNewItems()
	{
		synchronized (pendingNewItems)
		{
			for (String name : pendingNewItems)
			{
				session.emit(EventType.CLOG_ITEM, new ClogItemData(name, items.recentIdForName(name)));
			}
			pendingNewItems.clear();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		emitNewItems();

		if (reading)
		{
			int now = client.getTickCount();
			if (lastDrawTick >= 0 && now > lastDrawTick)
			{
				finishRead();
			}
			else if (lastDrawTick < 0 && now - lastReadTick > READ_TIMEOUT_TICKS)
			{
				reading = false;
				log.debug("Collection log search drew no items");
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			resetRateLimit();
		}
	}

	// ------------------------------------------------------------------ full log

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == SCRIPT_COLLECTION_SETUP)
		{
			onLogOpened();
		}
	}

	/** Client thread: the collection log interface was set up. Start a read if allowed. */
	void onLogOpened()
	{
		if (!config.sendCollectionLog() || !session.isActive() || isAdventureLog())
		{
			return;
		}
		int now = client.getTickCount();
		// (A tick counter that went backwards, e.g. after a reconnect, does not block reads.)
		if (lastReadTick >= 0 && now >= lastReadTick && now - lastReadTick < MIN_TICKS_BETWEEN_READS)
		{
			return;
		}
		reading = true;
		lastReadTick = now;
		lastDrawTick = -1;
		readItems.clear();
		client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null);
		client.runScript(SCRIPT_COLLECTION_SEARCH);
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (!reading || event.getScriptId() != SCRIPT_COLLECTION_DRAW_ITEM || event.getScriptEvent() == null)
		{
			return;
		}
		if (isAdventureLog())
		{
			reading = false; // a house adventure log replaced the player's own log mid-read
			readItems.clear();
			return;
		}
		Object[] args = event.getScriptEvent().getArguments();
		if (args == null || args.length < 3 || !(args[1] instanceof Integer) || !(args[2] instanceof Integer))
		{
			return;
		}
		int itemId = (Integer) args[1];
		int qty = (Integer) args[2];
		lastDrawTick = client.getTickCount();
		if (qty > 0)
		{
			readItems.put(itemId, qty);
		}
	}

	/** A house adventure log (another player's collection log) is open. */
	boolean isAdventureLog()
	{
		return client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1;
	}

	private void finishRead()
	{
		reading = false;
		if (readItems.isEmpty() || isAdventureLog())
		{
			readItems.clear();
			return;
		}
		Map<String, Integer> out = new LinkedHashMap<>();
		Map<String, String> names = new LinkedHashMap<>();
		readItems.forEach((id, qty) ->
		{
			out.put(Integer.toString(id), qty);
			names.put(Integer.toString(id), items.nameOf(id));
		});
		int obtained = client.getVarpValue(VarPlayerID.COLLECTION_COUNT);
		int total = client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX);
		if (obtained <= 0)
		{
			obtained = out.size();
		}
		if (total <= 0)
		{
			total = countLogSlotsFromCache();
		}
		session.emit(EventType.COLLECTION_LOG, new CollectionLogData(out, names, obtained, total));
		readItems.clear();
	}

	/** Number of distinct collection log slots, from the cache enums/structs (as WikiSync does). */
	private int countLogSlotsFromCache()
	{
		try
		{
			Set<Integer> ids = new HashSet<>();
			for (int tabStruct : client.getEnum(2102).getIntVals())
			{
				StructComposition tab = client.getStructComposition(tabStruct);
				for (int subTabStruct : client.getEnum(tab.getIntValue(683)).getIntVals())
				{
					StructComposition subTab = client.getStructComposition(subTabStruct);
					for (int itemId : client.getEnum(subTab.getIntValue(690)).getIntVals())
					{
						ids.add(itemId);
					}
				}
			}
			EnumComposition replacements = client.getEnum(3721);
			for (int bad : replacements.getKeys())
			{
				ids.remove(bad);
			}
			for (int good : replacements.getIntVals())
			{
				ids.add(good);
			}
			return ids.size();
		}
		catch (RuntimeException e)
		{
			log.debug("Could not count collection log slots", e);
			return 0;
		}
	}

	private void resetRateLimit()
	{
		reading = false;
		lastReadTick = -1;
		lastDrawTick = -1;
		readItems.clear();
	}

	public void shutDown()
	{
		synchronized (pendingNewItems)
		{
			pendingNewItems.clear();
		}
		resetRateLimit();
	}

	@Override
	public void onSessionStart()
	{
	}

	@Override
	public void onSessionEnd()
	{
		emitNewItems();
		reading = false;
	}
}
