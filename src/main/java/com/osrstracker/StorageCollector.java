package com.osrstracker;

import com.osrstracker.model.ContainerData;
import com.osrstracker.model.ContainerItem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.ItemContainer;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;

/**
 * Storages the game describes exactly, without a container event of their own (all under "Storages and
 * loadouts"). Techniques follow the Group Ironmen Tracker plugin (christoabrown/group-ironmen-tracker,
 * BSD-2-Clause: RunePouchState, QuiverState, PotionStorageState, the group storage "saving..." commit):
 * <ul>
 * <li>{@code rune_pouch}: rune type and quantity varbits; type → item id through the game's rune pouch enum.</li>
 * <li>{@code quiver}: Dizana's quiver ammo id and amount varps.</li>
 * <li>{@code plank_sack}: one count varbit per plank type.</li>
 * <li>{@code potion_storage}: while the bank is open, the potion store's enums and the game's dose script;
 * items are the 1-dose potion with {@code qty} = doses and {@code unit: "dose"}.</li>
 * <li>{@code group_storage}: group ironman only; the shared storage's working copy is captured as it changes and
 * sent only when the game shows "Saving..." (the edit was committed).</li>
 * </ul>
 */
@Singleton
public class StorageCollector implements SessionTracker.Listener
{
	static final String RUNE_POUCH = "rune_pouch";
	static final String QUIVER = "quiver";
	static final String PLANK_SACK = "plank_sack";
	static final String POTION_STORAGE = "potion_storage";
	static final String GROUP_STORAGE = "group_storage";
	static final long VAR_DELAY_MS = 2_000;
	static final long POTION_DELAY_MS = 5_000;

	static final int[] RUNE_TYPE_VARBITS = {
		VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6,
	};
	static final int[] RUNE_QTY_VARBITS = {
		VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3,
		VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6,
	};
	/** Plank sack count varbit → plank item. */
	static final Map<Integer, Integer> PLANK_VARBITS = new LinkedHashMap<>();
	private static final Set<Integer> RUNE_VARBITS = new HashSet<>();

	static
	{
		PLANK_VARBITS.put(VarbitID.PLANK_SACK_PLAIN, ItemID.WOODPLANK);
		PLANK_VARBITS.put(VarbitID.PLANK_SACK_OAK, ItemID.PLANK_OAK);
		PLANK_VARBITS.put(VarbitID.PLANK_SACK_TEAK, ItemID.PLANK_TEAK);
		PLANK_VARBITS.put(VarbitID.PLANK_SACK_MAHOGANY, ItemID.PLANK_MAHOGANY);
		PLANK_VARBITS.put(VarbitID.PLANK_SACK_CAMPHOR, ItemID.PLANK_CAMPHOR);
		PLANK_VARBITS.put(VarbitID.PLANK_SACK_IRONWOOD, ItemID.PLANK_IRONWOOD);
		PLANK_VARBITS.put(VarbitID.PLANK_SACK_ROSEWOOD, ItemID.PLANK_ROSEWOOD);
		for (int v : RUNE_TYPE_VARBITS)
		{
			RUNE_VARBITS.add(v);
		}
		for (int v : RUNE_QTY_VARBITS)
		{
			RUNE_VARBITS.add(v);
		}
	}

	private static final String SAVING = "saving...";

	private final Client client;
	private final SessionTracker session;
	private final TrackerSyncConfig config;
	private final ItemMapper items;
	private final ContainerPublisher publisher;

	private boolean potionsDirty;
	private Set<Integer> potionStoreVarps;
	private ContainerData groupStorageCapture;

	@Inject
	StorageCollector(Client client, SessionTracker session, TrackerSyncConfig config, ItemMapper items, ContainerPublisher publisher)
	{
		this.client = client;
		this.session = session;
		this.config = config;
		this.items = items;
		this.publisher = publisher;
	}

	private boolean wanted()
	{
		return config.sendStorages() && session.isActive();
	}

	@Override
	public void onSessionStart()
	{
		if (wanted())
		{
			// Once after login, also when empty, so the server never keeps contents emptied while the plugin was off.
			publisher.offer(readRunePouch(), VAR_DELAY_MS);
			publisher.offer(readQuiver(), VAR_DELAY_MS);
			publisher.offer(readPlankSack(), VAR_DELAY_MS);
		}
	}

	@Override
	public void onSessionEnd()
	{
		potionsDirty = false;
		groupStorageCapture = null;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (!wanted())
		{
			return;
		}
		int varbit = event.getVarbitId();
		int varp = event.getVarpId();
		if (RUNE_VARBITS.contains(varbit))
		{
			publisher.offer(readRunePouch(), VAR_DELAY_MS);
		}
		else if (varp == VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO || varp == VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT)
		{
			publisher.offer(readQuiver(), VAR_DELAY_MS);
		}
		else if (PLANK_VARBITS.containsKey(varbit))
		{
			publisher.offer(readPlankSack(), VAR_DELAY_MS);
		}
		if (potionStoreVarps != null && potionStoreVarps.contains(varp))
		{
			potionsDirty = true;
		}
	}

	// ------------------------------------------------------------------ varbit-backed storages (client thread)

	ContainerData readRunePouch()
	{
		EnumComposition runes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
		List<ContainerItem> out = new ArrayList<>();
		for (int i = 0; i < RUNE_TYPE_VARBITS.length; i++)
		{
			int type = client.getVarbitValue(RUNE_TYPE_VARBITS[i]);
			int qty = client.getVarbitValue(RUNE_QTY_VARBITS[i]);
			if (type <= 0 || qty <= 0 || runes == null)
			{
				continue;
			}
			int itemId = runes.getIntValue(type);
			if (itemId > 0)
			{
				out.add(new ContainerItem(items.toItem(itemId, qty), i, null));
			}
		}
		return new ContainerData(RUNE_POUCH, out);
	}

	ContainerData readQuiver()
	{
		int id = client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO);
		int qty = client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT);
		List<ContainerItem> out = new ArrayList<>();
		if (id > 0 && qty > 0)
		{
			out.add(new ContainerItem(items.toItem(id, qty), 0, null));
		}
		return new ContainerData(QUIVER, out);
	}

	ContainerData readPlankSack()
	{
		List<ContainerItem> out = new ArrayList<>();
		int slot = 0;
		for (Map.Entry<Integer, Integer> e : PLANK_VARBITS.entrySet())
		{
			int qty = client.getVarbitValue(e.getKey());
			if (qty > 0)
			{
				out.add(new ContainerItem(items.toItem(e.getValue(), qty), slot, null));
			}
			slot++;
		}
		return new ContainerData(PLANK_SACK, out);
	}

	// ------------------------------------------------------------------ potion storage (bank open)

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() == ScriptID.BANKMAIN_FINISHBUILDING)
		{
			potionsDirty = true;
		}
	}

	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		if (!potionsDirty)
		{
			return;
		}
		potionsDirty = false;
		Widget store = client.getWidget(InterfaceID.Bankmain.POTIONSTORE_ITEMS);
		if (store == null || !wanted())
		{
			return;
		}
		if (potionStoreVarps == null && store.getVarTransmitTrigger() != null)
		{
			// The vars that redraw the potion store: a change of any of them means its contents changed.
			Set<Integer> vars = new HashSet<>();
			for (int v : store.getVarTransmitTrigger())
			{
				vars.add(v);
			}
			potionStoreVarps = vars;
		}
		publisher.offer(readPotionStorage(), POTION_DELAY_MS);
	}

	/** Client thread, outside of scripts (runs the dose script once per potion). */
	ContainerData readPotionStorage()
	{
		List<ContainerItem> out = new ArrayList<>();
		int slot = 0;
		for (int listEnum : new int[]{EnumID.POTIONSTORE_POTIONS, EnumID.POTIONSTORE_UNFINISHED_POTIONS})
		{
			EnumComposition list = client.getEnum(listEnum);
			if (list == null)
			{
				continue;
			}
			for (int potionEnumId : list.getIntVals())
			{
				EnumComposition potion = client.getEnum(potionEnumId);
				if (potion == null)
				{
					continue;
				}
				client.runScript(ScriptID.POTIONSTORE_DOSES, potionEnumId);
				int doses = client.getIntStack()[0];
				int oneDose = potion.getIntValue(1);
				if (doses > 0 && oneDose > 0)
				{
					out.add(new ContainerItem(items.toItem(oneDose, doses), slot, null, "dose"));
				}
				slot++;
			}
		}
		return new ContainerData(POTION_STORAGE, out);
	}

	// ------------------------------------------------------------------ group storage (group ironman)

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.INV_GROUP_TEMP || !wanted() || !isGroupIronman())
		{
			return;
		}
		ItemContainer c = event.getItemContainer();
		List<ContainerItem> out = new ArrayList<>();
		net.runelite.api.Item[] slots = c == null ? new net.runelite.api.Item[0] : c.getItems();
		for (int slot = 0; slot < slots.length; slot++)
		{
			net.runelite.api.Item it = slots[slot];
			if (it != null && it.getId() >= 0 && it.getQuantity() > 0 && !items.isPlaceholder(it.getId()))
			{
				out.add(new ContainerItem(items.toItem(it.getId(), it.getQuantity()), slot, null));
			}
		}
		groupStorageCapture = new ContainerData(GROUP_STORAGE, out);
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (groupStorageCapture == null)
		{
			return;
		}
		Widget loading = client.getWidget(InterfaceID.LoadingIconModal.TEXT);
		if (loading != null && !loading.isHidden() && loading.getText() != null && SAVING.equalsIgnoreCase(loading.getText().trim()))
		{
			// The game is saving the shared storage: the working copy is now what the group has.
			publisher.offer(groupStorageCapture, 0);
			groupStorageCapture = null;
		}
	}

	boolean isGroupIronman()
	{
		String type = session.accountType();
		return type != null && type.contains("GROUP_IRONMAN");
	}
}
