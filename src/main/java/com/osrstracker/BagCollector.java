package com.osrstracker;

import com.google.common.collect.ImmutableSet;
import com.osrstracker.model.ContainerData;
import com.osrstracker.model.ContainerItem;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Bags whose contents the game never sends as a container or varbit: fish barrel, log basket, gem bag, coal bag,
 * herb sack. Contents are learnt from what the game shows (technique after the Mystix plugin's StorageItemMonitor /
 * StorageItemLedger, rjmccaskell/mystix-plugin, BSD-2-Clause; own code):
 * <ul>
 * <li>"Check" output (chat lines or the message box): a full read, sent with {@code estimated} omitted;</li>
 * <li>"is empty" / "you empty" messages: a full read (empty);</li>
 * <li>"Fill" / "Empty" clicks on the bag: the next inventory change within two ticks is added to / taken from
 * the last known contents, sent with {@code estimated: true}.</li>
 * </ul>
 * Gathering straight into an open bag (no inventory change) is not counted: while an open bag has been in the
 * inventory since the last full read, nothing is sent until the next Check. Before the first full read of a
 * session nothing is sent for that bag.
 */
@Singleton
public class BagCollector implements SessionTracker.Listener
{
	static final long DELAY_MS = 1_000;
	static final int ACTION_TICKS = 2;

	static final Pattern ENTRY = Pattern.compile("(\\d[\\d,]*)\\s*x\\s*([A-Za-z'() -]+?)\\s*(?=,|$|/)");
	static final Pattern GEM_ENTRY = Pattern.compile("([A-Za-z ]+?):\\s*(\\d[\\d,]*)");
	static final Pattern COAL = Pattern.compile("^The coal bag contains (\\d+|one) pieces? of coal\\.?$");
	static final Set<String> ALL_EMPTIED = ImmutableSet.of(
		"You empty all of your containers into the bank.", "Your containers are already empty.");

	enum Bag
	{
		FISH_BARREL("fish_barrel", "The barrel contains:",
			ImmutableSet.of("The barrel is empty.", "Your barrel is empty.", "You empty the barrel."),
			new int[]{ItemID.FISH_BARREL_CLOSED, ItemID.FISH_SACK_BARREL_CLOSED},
			new int[]{ItemID.FISH_BARREL_OPEN, ItemID.FISH_SACK_BARREL_OPEN},
			new int[]{ItemID.RAW_SHRIMP, ItemID.RAW_SARDINE, ItemID.RAW_HERRING, ItemID.RAW_ANCHOVIES, ItemID.RAW_MACKEREL,
				ItemID.RAW_TROUT, ItemID.RAW_COD, ItemID.RAW_PIKE, ItemID.MORT_SLIMEY_EEL, ItemID.RAW_SALMON, ItemID.RAW_TUNA,
				ItemID.RAW_CAVE_EEL, ItemID.RAW_LOBSTER, ItemID.RAW_BASS, ItemID.BRUT_SPAWNING_TROUT, ItemID.RAW_SWORDFISH,
				ItemID.RAW_LAVA_EEL, ItemID.BRUT_SPAWNING_SALMON, ItemID.RAW_MONKFISH, ItemID.TBWT_RAW_KARAMBWAN,
				ItemID.BRUT_STURGEON, ItemID.RAW_SHARK, ItemID.INFERNAL_EEL, ItemID.RAW_ANGLERFISH, ItemID.RAW_DARK_CRAB,
				ItemID.RAW_SEATURTLE, ItemID.RAW_MANTARAY, ItemID.HUNTING_RAW_FISH_SPECIAL, ItemID.SNAKEBOSS_EEL}),
		LOG_BASKET("log_basket", "The basket contains:",
			ImmutableSet.of("The basket is empty.", "Your basket is empty.", "You empty your basket.", "You empty your basket into the bank."),
			new int[]{ItemID.LOG_BASKET_CLOSED, ItemID.FORESTRY_BASKET_CLOSED},
			new int[]{ItemID.LOG_BASKET_OPEN, ItemID.FORESTRY_BASKET_OPEN},
			new int[]{ItemID.LOGS, ItemID.ACHEY_TREE_LOGS, ItemID.OAK_LOGS, ItemID.WILLOW_LOGS, ItemID.TEAK_LOGS, ItemID.MAPLE_LOGS,
				ItemID.MAHOGANY_LOGS, ItemID.ARCTIC_PINE_LOG, ItemID.YEW_LOGS, ItemID.MAGIC_LOGS, ItemID.REDWOOD_LOGS,
				ItemID.JUNIPER_LOGS, ItemID.CAMPHOR_LOGS, ItemID.IRONWOOD_LOGS, ItemID.ROSEWOOD_LOGS}),
		GEM_BAG("gem_bag", null,
			ImmutableSet.of("The gem bag is empty.", "The gem pouch is empty.", "The gem satchel is empty.",
				"The gem tote is empty.", "The gem sack is empty."),
			new int[]{ItemID.GEM_BAG, ItemID.GEM_POUCH, ItemID.GEM_SATCHEL, ItemID.GEM_TOTE, ItemID.GEM_SACK},
			new int[]{ItemID.GEM_BAG_OPEN, ItemID.GEM_POUCH_OPEN, ItemID.GEM_SATCHEL_OPEN, ItemID.GEM_TOTE_OPEN, ItemID.GEM_SACK_OPEN},
			new int[]{ItemID.UNCUT_SAPPHIRE, ItemID.UNCUT_EMERALD, ItemID.UNCUT_RUBY, ItemID.UNCUT_DIAMOND,
				ItemID.UNCUT_DRAGONSTONE, ItemID.UNCUT_OPAL, ItemID.UNCUT_JADE, ItemID.UNCUT_RED_TOPAZ}),
		COAL_BAG("coal_bag", null,
			ImmutableSet.of("The coal bag is empty.", "The coal bag is now empty."),
			new int[]{ItemID.COAL_BAG},
			new int[]{ItemID.COAL_BAG_OPEN},
			new int[]{ItemID.COAL}),
		HERB_SACK("herb_sack", "You look in your herb sack and see:",
			ImmutableSet.of("The herb sack is empty."),
			new int[]{ItemID.SLAYER_HERB_SACK, ItemID.SLAYER_HERB_SACK_SILK},
			new int[]{ItemID.SLAYER_HERB_SACK_OPEN, ItemID.SLAYER_HERB_SACK_SILK_OPEN},
			new int[]{ItemID.UNIDENTIFIED_GUAM, ItemID.UNIDENTIFIED_MARENTILL, ItemID.UNIDENTIFIED_TARROMIN,
				ItemID.UNIDENTIFIED_HARRALANDER, ItemID.UNIDENTIFIED_RANARR, ItemID.UNIDENTIFIED_TOADFLAX, ItemID.UNIDENTIFIED_IRIT,
				ItemID.UNIDENTIFIED_AVANTOE, ItemID.UNIDENTIFIED_KWUARM, ItemID.UNIDENTIFIED_SNAPDRAGON, ItemID.UNIDENTIFIED_HUASCA,
				ItemID.UNIDENTIFIED_CADANTINE, ItemID.UNIDENTIFIED_LANTADYME, ItemID.UNIDENTIFIED_DWARF_WEED,
				ItemID.UNIDENTIFIED_TORSTOL});

		final String wireName;
		/** Check listing prefix; null when the listing has its own format (gems, coal). */
		final String checkPrefix;
		final Set<String> emptyMessages;
		final Set<Integer> closedIds;
		final Set<Integer> openIds;
		final Set<Integer> accepted;

		Bag(String wireName, String checkPrefix, Set<String> emptyMessages, int[] closed, int[] open, int[] accepted)
		{
			this.wireName = wireName;
			this.checkPrefix = checkPrefix;
			this.emptyMessages = emptyMessages;
			this.closedIds = set(closed);
			this.openIds = set(open);
			this.accepted = set(accepted);
		}

		boolean isBag(int itemId)
		{
			return closedIds.contains(itemId) || openIds.contains(itemId);
		}

		private static Set<Integer> set(int[] ids)
		{
			ImmutableSet.Builder<Integer> b = ImmutableSet.builder();
			for (int id : ids)
			{
				b.add(id);
			}
			return b.build();
		}
	}

	/** Per-bag knowledge for the current session. */
	static final class State
	{
		final Map<Integer, Integer> contents = new LinkedHashMap<>();
		/** A full read happened this session. */
		boolean known;
		/** An open bag was in the inventory since the last full read: gathering may not be counted. */
		boolean stale;
	}

	private final Client client;
	private final ClientThread clientThread;
	private final SessionTracker session;
	private final TrackerSyncConfig config;
	private final ItemMapper items;
	private final ContainerPublisher publisher;

	private final Map<Bag, State> states = new EnumMap<>(Bag.class);
	private final Map<Bag, Map<String, Integer>> nameIndex = new EnumMap<>(Bag.class);
	private Map<Integer, Integer> inventory = new HashMap<>();
	/** Herb sack style listing collected over several lines in one tick. */
	private Bag listing;
	private Map<Integer, Integer> listingItems;
	private Bag actionBag;
	private boolean actionFill;
	private int actionUntilTick = -1;

	@Inject
	BagCollector(Client client, ClientThread clientThread, SessionTracker session, TrackerSyncConfig config,
		ItemMapper items, ContainerPublisher publisher)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.session = session;
		this.config = config;
		this.items = items;
		this.publisher = publisher;
	}

	State state(Bag bag)
	{
		return states.computeIfAbsent(bag, b -> new State());
	}

	// ------------------------------------------------------------------ chat and message box

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() == ChatMessageType.GAMEMESSAGE || event.getType() == ChatMessageType.SPAM)
		{
			onText(Text.removeTags(event.getMessage()).trim());
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.OBJECTBOX)
		{
			// The "Check" result of some bags appears in the item message box instead of the chat.
			clientThread.invokeLater(() ->
			{
				Widget text = client.getWidget(InterfaceID.Objectbox.TEXT);
				if (text != null && text.getText() != null)
				{
					for (String line : text.getText().split("<br>"))
					{
						onText(Text.removeTags(line).trim());
					}
					finishListing();
				}
			});
		}
	}

	/** One line of game text (client thread). Visible for tests. */
	void onText(String text)
	{
		if (text.isEmpty())
		{
			return;
		}
		if (ALL_EMPTIED.contains(text))
		{
			// The bank's "empty containers": every bag carried is now empty.
			for (Bag bag : Bag.values())
			{
				if (carried(bag))
				{
					fullRead(bag, new LinkedHashMap<>());
				}
			}
			return;
		}
		for (Bag bag : Bag.values())
		{
			if (bag.emptyMessages.contains(text))
			{
				fullRead(bag, new LinkedHashMap<>());
				return;
			}
		}
		Matcher coal = COAL.matcher(text);
		if (coal.matches())
		{
			Map<Integer, Integer> c = new LinkedHashMap<>();
			c.put(ItemID.COAL, "one".equals(coal.group(1)) ? 1 : Integer.parseInt(coal.group(1)));
			fullRead(Bag.COAL_BAG, c);
			return;
		}
		Map<Integer, Integer> gems = parseGems(text, name -> idForName(Bag.GEM_BAG, name));
		if (gems != null)
		{
			fullRead(Bag.GEM_BAG, gems);
			return;
		}
		for (Bag bag : Bag.values())
		{
			if (bag.checkPrefix != null && text.startsWith(bag.checkPrefix))
			{
				finishListing();
				listing = bag;
				listingItems = new LinkedHashMap<>();
				Map<Integer, Integer> entries = parseEntries(text.substring(bag.checkPrefix.length()), name -> idForName(bag, name));
				listingItems.putAll(entries);
				return;
			}
		}
		if (listing != null)
		{
			Map<Integer, Integer> entries = parseEntries(text, name -> idForName(listing, name));
			if (entries.isEmpty())
			{
				finishListing();
			}
			else
			{
				entries.forEach((id, qty) -> listingItems.merge(id, qty, Integer::sum));
			}
		}
	}

	private void finishListing()
	{
		if (listing != null)
		{
			Bag bag = listing;
			Map<Integer, Integer> c = listingItems;
			listing = null;
			listingItems = null;
			fullRead(bag, c);
		}
	}

	/** "5 x Raw shark, 3 x Raw anglerfish" → id → qty (names not in the bag are ignored). */
	static Map<Integer, Integer> parseEntries(String text, Function<String, Integer> idForName)
	{
		Map<Integer, Integer> out = new LinkedHashMap<>();
		Matcher m = ENTRY.matcher(text);
		while (m.find())
		{
			Integer id = idForName.apply(m.group(2));
			if (id != null)
			{
				out.merge(id, Integer.parseInt(m.group(1).replace(",", "")), Integer::sum);
			}
		}
		return out;
	}

	/** "Sapphires: 4 / Emeralds: 0 / Rubies: 1 / ..." → id → qty; null when the line is not a gem listing. */
	static Map<Integer, Integer> parseGems(String text, Function<String, Integer> idForName)
	{
		if (!text.contains(":") || !text.toLowerCase(Locale.ROOT).startsWith("sapphires"))
		{
			return null;
		}
		Map<Integer, Integer> out = new LinkedHashMap<>();
		Matcher m = GEM_ENTRY.matcher(text);
		boolean any = false;
		while (m.find())
		{
			Integer id = idForName.apply(singular(m.group(1)));
			if (id != null)
			{
				any = true;
				int qty = Integer.parseInt(m.group(2).replace(",", ""));
				if (qty > 0)
				{
					out.put(id, qty);
				}
			}
		}
		return any ? out : null;
	}

	/** "Sapphires" → "sapphire", "Rubies" → "ruby", "Red topazes" → "red topaz". */
	static String singular(String plural)
	{
		String p = plural.trim().toLowerCase(Locale.ROOT);
		if (p.endsWith("ies"))
		{
			return p.substring(0, p.length() - 3) + "y";
		}
		if (p.endsWith("zes"))
		{
			return p.substring(0, p.length() - 2);
		}
		return p.endsWith("s") ? p.substring(0, p.length() - 1) : p;
	}

	/** Item id of a chat name for this bag ("Raw shark", "shark", "Grimy ranarr weed", "sapphire"). */
	private Integer idForName(Bag bag, String chatName)
	{
		Map<String, Integer> index = nameIndex.computeIfAbsent(bag, b ->
		{
			Map<String, Integer> m = new HashMap<>();
			for (int id : b.accepted)
			{
				String n = items.nameOf(id).toLowerCase(Locale.ROOT);
				m.put(n, id);
				for (String prefix : new String[]{"raw ", "grimy ", "uncut "})
				{
					if (n.startsWith(prefix))
					{
						m.put(n.substring(prefix.length()), id);
					}
				}
			}
			return m;
		});
		return index.get(chatName.trim().toLowerCase(Locale.ROOT));
	}

	// ------------------------------------------------------------------ fill / empty

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String option = event.getMenuOption();
		if (option == null)
		{
			return;
		}
		boolean fill = option.equalsIgnoreCase("Fill");
		boolean empty = option.equalsIgnoreCase("Empty");
		if (!fill && !empty)
		{
			return;
		}
		for (Bag bag : Bag.values())
		{
			if (bag.isBag(event.getItemId()))
			{
				actionBag = bag;
				actionFill = fill;
				actionUntilTick = client.getTickCount() + ACTION_TICKS;
			}
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.INV)
		{
			return;
		}
		Map<Integer, Integer> now = DepositCollector.counts(event.getItemContainer());
		Map<Integer, Integer> before = inventory;
		inventory = now;
		for (Bag bag : Bag.values())
		{
			for (int open : bag.openIds)
			{
				if (now.containsKey(open))
				{
					state(bag).stale = true;
				}
			}
		}
		if (actionBag != null && client.getTickCount() <= actionUntilTick)
		{
			applyAction(actionBag, actionFill, before, now);
			actionBag = null;
		}
	}

	/** Visible for tests. */
	void applyAction(Bag bag, boolean fill, Map<Integer, Integer> before, Map<Integer, Integer> after)
	{
		State s = state(bag);
		Map<Integer, Integer> moved = fill ? DepositCollector.diff(before, after) : DepositCollector.diff(after, before);
		boolean changed = false;
		for (Map.Entry<Integer, Integer> e : moved.entrySet())
		{
			if (!bag.accepted.contains(e.getKey()))
			{
				continue;
			}
			int qty = s.contents.getOrDefault(e.getKey(), 0) + (fill ? e.getValue() : -e.getValue());
			if (qty > 0)
			{
				s.contents.put(e.getKey(), qty);
			}
			else
			{
				s.contents.remove(e.getKey());
			}
			changed = true;
		}
		if (changed && s.known && !s.stale)
		{
			send(bag, true);
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		finishListing();
		if (actionBag != null && client.getTickCount() > actionUntilTick)
		{
			actionBag = null;
		}
	}

	// ------------------------------------------------------------------ state and emission

	/** Visible for tests. */
	void fullRead(Bag bag, Map<Integer, Integer> contents)
	{
		State s = state(bag);
		s.contents.clear();
		s.contents.putAll(contents);
		s.known = true;
		s.stale = false;
		send(bag, false);
	}

	private boolean carried(Bag bag)
	{
		for (int id : inventory.keySet())
		{
			if (bag.isBag(id))
			{
				return true;
			}
		}
		return false;
	}

	private void send(Bag bag, boolean estimated)
	{
		if (!config.sendStorages() || !session.isActive())
		{
			return;
		}
		List<ContainerItem> out = new ArrayList<>();
		int slot = 0;
		for (Map.Entry<Integer, Integer> e : state(bag).contents.entrySet())
		{
			out.add(new ContainerItem(items.toItem(e.getKey(), e.getValue()), slot++, null));
		}
		publisher.offer(new ContainerData(bag.wireName, out, estimated ? Boolean.TRUE : null), DELAY_MS);
	}

	@Override
	public void onSessionStart()
	{
		inventory = DepositCollector.counts(client.getItemContainer(InventoryID.INV));
	}

	@Override
	public void onSessionEnd()
	{
		states.clear();
		listing = null;
		listingItems = null;
		actionBag = null;
	}
}
