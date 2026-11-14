package com.osrstracker;

import com.google.common.collect.ImmutableSet;
import com.osrstracker.model.ClogItemData;
import com.osrstracker.model.CollectionLogData;
import com.osrstracker.model.CollectionLogSummaryData;
import com.osrstracker.model.EventType;
import com.osrstracker.model.IngestEvent;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
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
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Collection log.
 *
 * <ul>
 * <li>{@code clog_item}: a new log item, from the game's chat line "New item added to your collection log: X" or
 * from its "Collection log" popup (game setting "Collection log - New addition notification"). When both arrive
 * for the same item, one event is sent. {@code id} only when the name was seen recently in loot or containers;
 * otherwise the server resolves the name.</li>
 * <li>{@code collection_log_summary}: total, per-tab and "recent items" counts the game sends with the other player
 * vars at login (readable without opening the log), plus the notification setting. Sent a few seconds after login
 * (retried while the counts are still 0/0, i.e. not loaded yet), then when one of them changes.</li>
 * <li>{@code collection_log}: the whole log. The game draws every log item through script 4100 (arguments: item
 * id, quantity) when the log's search is opened. Collection starts when the player's own log is set up (script
 * 7797, before any other plugin's handler runs) and accepts every 4100 call until the draws stop for a tick,
 * whoever opened the search (the Group Ironmen Tracker hub plugin also opens it). Only if nothing was drawn by the
 * next tick and the search is not already on does this plugin open it (technique as in that plugin,
 * christoabrown/group-ironmen-tracker, BSD-2-Clause, CollectionLogWidgetSubscriber), so it never toggles the
 * search off. Never for a house adventure log (someone else's log). The first read of a session is not limited;
 * after a successful read, at most one per 500 game ticks (reset on logout).</li>
 * </ul>
 */
@Slf4j
@Singleton
public class CollectionLogCollector implements SessionTracker.Listener
{
	static final int SCRIPT_COLLECTION_SETUP = 7797;
	static final int SCRIPT_COLLECTION_DRAW_ITEM = 4100;
	static final int SCRIPT_COLLECTION_SEARCH = 2240;
	static final int SCRIPT_NOTIFICATION_START = 3346;
	static final int SCRIPT_NOTIFICATION_DELAY = 3347;
	/** After a successful read, at most one more per this many game ticks. */
	static final int MIN_TICKS_BETWEEN_READS = 500;
	/** Give up if no item was drawn within this many ticks of the log opening. */
	static final int READ_TIMEOUT_TICKS = 5;
	/**
	 * Chat line and popup for the same item within this many ticks are one item. Popups queue behind other popups
	 * (e.g. a combat achievement), so this is generous; a log item is only ever new once.
	 */
	static final int NEW_ITEM_DEDUPE_TICKS = 50;

	static final long SUMMARY_DELAY_MS = 1_000;
	static final long SUMMARY_MIN_INTERVAL_MS = 5_000;
	static final long SUMMARY_LOGIN_SETTLE_MS = 3_000;
	static final long SUMMARY_RETRY_MS = 5_000;
	/** Retries while the counts read 0/0 (not loaded): 24 x 5 s = about 2 minutes. */
	static final int SUMMARY_MAX_RETRIES = 24;

	static final Pattern NEW_ITEM = Pattern.compile("^New item added to your collection log: (.+?)\\s*$");
	static final Pattern POPUP_NEW_ITEM = Pattern.compile("^New item:\\s*(.+?)\\s*$");
	static final String POPUP_TITLE = "Collection log";

	/** Tab names with their obtained / max varps, in the order of the game's vars. */
	static final String[] TAB_NAMES = {"Bosses", "Raids", "Clues", "Minigames", "Other"};
	private static final int[][] TAB_VARPS = {
		{VarPlayerID.COLLECTION_COUNT_BOSSES, VarPlayerID.COLLECTION_COUNT_BOSSES_MAX},
		{VarPlayerID.COLLECTION_COUNT_RAIDS, VarPlayerID.COLLECTION_COUNT_RAIDS_MAX},
		{VarPlayerID.COLLECTION_COUNT_CLUES, VarPlayerID.COLLECTION_COUNT_CLUES_MAX},
		{VarPlayerID.COLLECTION_COUNT_MINIGAMES, VarPlayerID.COLLECTION_COUNT_MINIGAMES_MAX},
		{VarPlayerID.COLLECTION_COUNT_OTHER, VarPlayerID.COLLECTION_COUNT_OTHER_MAX},
	};
	/** Recent items: item id (-1 = empty) and the game day it was added (same unit as CURRENT_RUNEDAY). */
	private static final int[][] RECENT_VARPS = {
		{VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM0, VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM0_DATE},
		{VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM1, VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM1_DATE},
		{VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM2, VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM2_DATE},
		{VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM3, VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM3_DATE},
		{VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM4, VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM4_DATE},
		{VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM5, VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM5_DATE},
		{VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM6, VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM6_DATE},
		{VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM7, VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM7_DATE},
		{VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM8, VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM8_DATE},
		{VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM9, VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM9_DATE},
		{VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM10, VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM10_DATE},
		{VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM11, VarPlayerID.COLLECTION_OVERVIEW_LAST_ITEM11_DATE},
	};
	static final Set<Integer> SUMMARY_VARPS;

	static
	{
		ImmutableSet.Builder<Integer> b = ImmutableSet.builder();
		b.add(VarPlayerID.COLLECTION_COUNT, VarPlayerID.COLLECTION_COUNT_MAX);
		for (int[] t : TAB_VARPS)
		{
			b.add(t[0], t[1]);
		}
		for (int[] r : RECENT_VARPS)
		{
			b.add(r[0], r[1]);
		}
		SUMMARY_VARPS = b.build();
	}

	private final Client client;
	private final SessionTracker session;
	private final TrackerSyncConfig config;
	private final ItemMapper items;
	private final TimeSource time;
	private final Throttle summary;
	private int summaryRetries;

	// full read (client thread only)
	private boolean reading;
	private int openTick = -1;
	private boolean searchChecked;
	private int drawCount;
	private int lastDrawTick = -1;
	/** Tick of the last successful read this session; -1 = none yet (the first read is not limited). */
	private int lastReadTick = -1;
	private final Map<Integer, Integer> readItems = new TreeMap<>();

	// new items
	private boolean popupStarted;
	private final List<String> pendingNewItems = new ArrayList<>();
	/** Lower-case name -> tick it was last reported, for chat/popup dedupe. */
	private final Map<String, Integer> recentNewItems = new HashMap<>();

	@Inject
	CollectionLogCollector(Client client, ClientThread clientThread, SessionTracker session, TrackerSyncConfig config,
		ItemMapper items, ScheduledExecutorService executor, TimeSource time)
	{
		this.client = client;
		this.session = session;
		this.config = config;
		this.items = items;
		this.time = time;
		this.summary = new Throttle(executor, time, SUMMARY_DELAY_MS, SUMMARY_MIN_INTERVAL_MS, SUMMARY_MIN_INTERVAL_MS,
			() -> clientThread.invoke(this::emitSummary));
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
			addNewItem(m.group(1), "chat");
		}
	}

	/** Item name from the game's notification popup, or null when it is not a new collection log item. */
	static String parsePopup(String title, String body)
	{
		if (title == null || body == null || !POPUP_TITLE.equalsIgnoreCase(Text.removeTags(title).trim()))
		{
			return null;
		}
		String text = Text.removeTags(body.replaceAll("(?i)<br\\s*/?>", " ")).replace(' ', ' ').trim();
		Matcher m = POPUP_NEW_ITEM.matcher(text);
		return m.find() && !m.group(1).isEmpty() ? m.group(1) : null;
	}

	/** Client thread. Queues a new item unless the same one was reported moments ago (chat and popup). */
	void addNewItem(String name, String via)
	{
		int now = client.getTickCount();
		String key = name.toLowerCase(Locale.ROOT);
		synchronized (pendingNewItems)
		{
			for (Iterator<Map.Entry<String, Integer>> it = recentNewItems.entrySet().iterator(); it.hasNext(); )
			{
				int at = it.next().getValue();
				if (now < at || now - at > NEW_ITEM_DEDUPE_TICKS)
				{
					it.remove();
				}
			}
			if (recentNewItems.containsKey(key))
			{
				log.debug("Collection log new item {} from the {} already reported", name, via);
				return;
			}
			recentNewItems.put(key, now);
			// Resolved on the tick so the loot / inventory change from the same tick has registered the id.
			pendingNewItems.add(name);
		}
		log.debug("Collection log new item {} (from the {})", name, via);
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

	// ------------------------------------------------------------------ collection_log_summary

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (!session.isActive() || !config.sendCollectionLog())
		{
			return;
		}
		if (event.getVarbitId() == VarbitID.OPTION_COLLECTION_NEW_ITEM
			|| (event.getVarbitId() == -1 && SUMMARY_VARPS.contains(event.getVarpId())))
		{
			summary.trigger();
		}
	}

	/** Client thread. */
	void emitSummary()
	{
		if (!config.sendCollectionLog() || !session.isActive())
		{
			return;
		}
		int[] tabs = new int[TAB_VARPS.length * 2];
		for (int i = 0; i < TAB_VARPS.length; i++)
		{
			tabs[2 * i] = client.getVarpValue(TAB_VARPS[i][0]);
			tabs[2 * i + 1] = client.getVarpValue(TAB_VARPS[i][1]);
		}
		int[] recentIds = new int[RECENT_VARPS.length];
		int[] recentDays = new int[RECENT_VARPS.length];
		for (int i = 0; i < RECENT_VARPS.length; i++)
		{
			recentIds[i] = client.getVarpValue(RECENT_VARPS[i][0]);
			recentDays[i] = client.getVarpValue(RECENT_VARPS[i][1]);
		}
		CollectionLogSummaryData data = buildSummary(client.getVarpValue(VarPlayerID.COLLECTION_COUNT),
			client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX), tabs, recentIds, recentDays,
			client.getVarbitValue(VarbitID.CURRENT_RUNEDAY), time.now().atZone(ZoneOffset.UTC).toLocalDate(),
			client.getVarbitValue(VarbitID.OPTION_COLLECTION_NEW_ITEM));
		if (data == null)
		{
			if (summaryRetries < SUMMARY_MAX_RETRIES)
			{
				summaryRetries++;
				log.debug("Collection log summary: counts not loaded yet (0/0), retry {} of {}", summaryRetries, SUMMARY_MAX_RETRIES);
				summary.triggerIn(SUMMARY_RETRY_MS);
			}
			else
			{
				log.debug("Collection log summary: counts still 0/0, not sent");
			}
			return;
		}
		summaryRetries = 0;
		session.emit(EventType.COLLECTION_LOG_SUMMARY, data);
	}

	/**
	 * Builds the summary, or null when the counts are not loaded (obtained and total both 0). Visible for tests.
	 *
	 * @param tabs         obtained, total per tab in {@link #TAB_NAMES} order
	 * @param recentIds    recent item ids (-1 or 0 = empty slot)
	 * @param recentDays   game day each recent item was added
	 * @param currentDay   the game's current day number (VarbitID.CURRENT_RUNEDAY); 0 = unknown
	 * @param todayUtc     today's date in UTC, matched to {@code currentDay}
	 */
	static CollectionLogSummaryData buildSummary(int obtained, int total, int[] tabs, int[] recentIds, int[] recentDays,
		int currentDay, LocalDate todayUtc, int notificationSetting)
	{
		if (obtained <= 0 && total <= 0)
		{
			return null;
		}
		List<CollectionLogSummaryData.Tab> tabList = new ArrayList<>();
		for (int i = 0; i < TAB_NAMES.length && 2 * i + 1 < tabs.length; i++)
		{
			if (tabs[2 * i + 1] > 0)
			{
				tabList.add(new CollectionLogSummaryData.Tab(TAB_NAMES[i], Math.max(0, tabs[2 * i]), tabs[2 * i + 1]));
			}
		}
		List<CollectionLogSummaryData.Recent> recent = new ArrayList<>();
		for (int i = 0; i < recentIds.length; i++)
		{
			if (recentIds[i] <= 0)
			{
				continue;
			}
			String at = null;
			if (currentDay > 0 && i < recentDays.length && recentDays[i] > 0)
			{
				// The log shows "N days ago" as CURRENT_RUNEDAY minus this value; a negative difference is "recently".
				long daysAgo = Math.max(0, currentDay - recentDays[i]);
				at = IngestEvent.formatTime(todayUtc.minusDays(daysAgo).atStartOfDay(ZoneOffset.UTC).toInstant());
			}
			recent.add(new CollectionLogSummaryData.Recent(recentIds[i], at));
		}
		return new CollectionLogSummaryData(Math.max(0, obtained), Math.max(0, total), tabList, recent,
			notificationSetting(notificationSetting));
	}

	/**
	 * The game setting OPTION_COLLECTION_NEW_ITEM: 0 off, 1 chat, 2 popup, 3 both; null for anything else. 0 and 1
	 * are as other plugins read them (0 = no notification, chat line only when 1); 2 and 3 follow the order of the
	 * in-game options and are not verified.
	 */
	static String notificationSetting(int value)
	{
		switch (value)
		{
			case 0:
				return "off";
			case 1:
				return "chat";
			case 2:
				return "popup";
			case 3:
				return "both";
			default:
				return null;
		}
	}

	// ------------------------------------------------------------------ full log

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		switch (event.getScriptId())
		{
			case SCRIPT_COLLECTION_SETUP:
				onLogOpened();
				break;
			case SCRIPT_COLLECTION_DRAW_ITEM:
				onItemDrawn(event.getScriptEvent() == null ? null : event.getScriptEvent().getArguments());
				break;
			case SCRIPT_NOTIFICATION_START:
				popupStarted = true;
				break;
			case SCRIPT_NOTIFICATION_DELAY:
				onPopupShown();
				break;
			default:
				break;
		}
	}

	private void onPopupShown()
	{
		if (!popupStarted)
		{
			return;
		}
		popupStarted = false;
		if (!config.sendCollectionLog() || !session.isActive())
		{
			return;
		}
		String name = parsePopup(client.getVarcStrValue(VarClientID.NOTIFICATION_TITLE),
			client.getVarcStrValue(VarClientID.NOTIFICATION_MAIN));
		if (name != null)
		{
			addNewItem(name, "popup");
		}
	}

	/**
	 * Client thread: the collection log interface is being set up (before the script runs, so before any other
	 * plugin reacts to it). Start collecting drawn items.
	 */
	void onLogOpened()
	{
		if (!config.sendCollectionLog())
		{
			log.debug("Collection log read skipped: the Collection log setting is off");
			return;
		}
		if (!session.isActive())
		{
			log.debug("Collection log read skipped: no session (not sending)");
			return;
		}
		if (isAdventureLog())
		{
			log.debug("Collection log read skipped: a house adventure log (another player's log) is open");
			return;
		}
		int now = client.getTickCount();
		if (reading)
		{
			log.debug("Collection log set up again while reading; still collecting");
			return;
		}
		// (A tick counter that went backwards, e.g. after a reconnect, does not block reads.)
		if (lastReadTick >= 0 && now >= lastReadTick && now - lastReadTick < MIN_TICKS_BETWEEN_READS)
		{
			log.debug("Collection log read skipped: last read {} ticks ago (at most one per {} ticks after the first)",
				now - lastReadTick, MIN_TICKS_BETWEEN_READS);
			return;
		}
		reading = true;
		openTick = now;
		searchChecked = false;
		drawCount = 0;
		lastDrawTick = -1;
		readItems.clear();
	}

	/** Client thread: script 4100 is about to draw one log item. */
	void onItemDrawn(Object[] args)
	{
		if (!reading)
		{
			return;
		}
		if (isAdventureLog())
		{
			stopReading("a house adventure log replaced the player's own log");
			return;
		}
		if (args == null || args.length < 3 || !(args[1] instanceof Integer) || !(args[2] instanceof Integer))
		{
			return;
		}
		int itemId = (Integer) args[1];
		int qty = (Integer) args[2];
		drawCount++;
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

	/** The log's search is on (the floating log's search varbit). */
	boolean isSearchOn()
	{
		return client.getVarbitValue(VarbitID.FLOATER_IS_SEARCHING) == 1;
	}

	private boolean isLogOpen()
	{
		Widget w = client.getWidget(InterfaceID.Collection.CONTENT);
		return w != null && !w.isHidden();
	}

	/** Client thread, the first tick after the log opened: open the search only if nobody else did. */
	private void checkSearch()
	{
		searchChecked = true;
		if (drawCount > 0)
		{
			return;
		}
		if (isSearchOn())
		{
			log.debug("Collection log search is already on; waiting for its items (not toggling it)");
			return;
		}
		if (!isLogOpen())
		{
			stopReading("the log closed before its items were drawn");
			return;
		}
		log.debug("Collection log: opening the log's search to read every item");
		client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null);
		client.runScript(SCRIPT_COLLECTION_SEARCH);
	}

	private void stopReading(String reason)
	{
		log.debug("Collection log read skipped: {}", reason);
		reading = false;
		drawCount = 0;
		readItems.clear();
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		emitNewItems();

		if (!reading)
		{
			return;
		}
		int now = client.getTickCount();
		if (drawCount > 0 && now > lastDrawTick)
		{
			finishRead();
		}
		else if (!searchChecked && now > openTick)
		{
			checkSearch();
		}
		else if (drawCount == 0 && (now - openTick > READ_TIMEOUT_TICKS || now < openTick))
		{
			stopReading("no item was drawn within " + READ_TIMEOUT_TICKS + " ticks of opening the log");
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

	private void finishRead()
	{
		reading = false;
		if (isAdventureLog())
		{
			stopReading("a house adventure log is open");
			return;
		}
		int obtained = client.getVarpValue(VarPlayerID.COLLECTION_COUNT);
		int total = client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX);
		int captured = readItems.size();
		if (captured == 0)
		{
			stopReading(drawCount + " items drawn, none obtained");
			return;
		}
		if (obtained > 0 && captured * 2 < obtained)
		{
			stopReading("only " + captured + " of " + obtained + " obtained items were drawn (not the full search)");
			return;
		}
		Map<String, Integer> out = new LinkedHashMap<>();
		Map<String, String> names = new LinkedHashMap<>();
		readItems.forEach((id, qty) ->
		{
			out.put(Integer.toString(id), qty);
			names.put(Integer.toString(id), items.nameOf(id));
		});
		if (obtained <= 0)
		{
			obtained = out.size();
		}
		if (total <= 0)
		{
			total = countLogSlotsFromCache();
		}
		readItems.clear();
		lastReadTick = client.getTickCount();
		session.emit(EventType.COLLECTION_LOG, new CollectionLogData(out, names, obtained, total));
		log.info("Tracker Sync: collection log read: {} items, {}/{} obtained", out.size(), obtained, total);
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
		drawCount = 0;
		readItems.clear();
	}

	public void shutDown()
	{
		synchronized (pendingNewItems)
		{
			pendingNewItems.clear();
			recentNewItems.clear();
		}
		popupStarted = false;
		summary.cancel();
		resetRateLimit();
	}

	@Override
	public void onSessionStart()
	{
		summaryRetries = 0;
		summary.triggerIn(SUMMARY_LOGIN_SETTLE_MS);
	}

	@Override
	public void onSessionEnd()
	{
		emitNewItems();
		summary.cancel();
		reading = false;
	}
}
