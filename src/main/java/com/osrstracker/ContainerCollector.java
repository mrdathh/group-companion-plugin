package com.osrstracker;

import com.osrstracker.model.ContainerData;
import com.osrstracker.model.ContainerItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;

/**
 * Containers the game sends as real item containers: each ItemContainerChanged is captured immediately on the
 * client thread (so storages are read while they are open) and emitted after a quiet period through the
 * {@link ContainerPublisher}: 2 s for inventory/equipment, 5 s for storages, plus immediately when the bank or
 * seed vault closes. Inventory and equipment belong to the "Items" setting; the bank, seed vault and looting
 * bag too; the other storages (seed box, tackle box, kits, boat cargo holds) to "Storages and loadouts".
 */
@Singleton
public class ContainerCollector implements SessionTracker.Listener
{
	static final long CARRIED_DELAY_MS = 2_000;
	static final long STORAGE_DELAY_MS = 5_000;

	private static final Map<EquipmentInventorySlot, String> EQUIP_SLOTS = new HashMap<>();

	static
	{
		EQUIP_SLOTS.put(EquipmentInventorySlot.HEAD, "head");
		EQUIP_SLOTS.put(EquipmentInventorySlot.CAPE, "cape");
		EQUIP_SLOTS.put(EquipmentInventorySlot.AMULET, "amulet");
		EQUIP_SLOTS.put(EquipmentInventorySlot.WEAPON, "weapon");
		EQUIP_SLOTS.put(EquipmentInventorySlot.BODY, "body");
		EQUIP_SLOTS.put(EquipmentInventorySlot.SHIELD, "shield");
		EQUIP_SLOTS.put(EquipmentInventorySlot.LEGS, "legs");
		EQUIP_SLOTS.put(EquipmentInventorySlot.GLOVES, "gloves");
		EQUIP_SLOTS.put(EquipmentInventorySlot.BOOTS, "boots");
		EQUIP_SLOTS.put(EquipmentInventorySlot.RING, "ring");
		EQUIP_SLOTS.put(EquipmentInventorySlot.AMMO, "ammo");
	}

	/** Contract slot name for an equipment slot index, or null. */
	static String equipSlotName(int slotIdx)
	{
		for (EquipmentInventorySlot s : EquipmentInventorySlot.values())
		{
			if (s.getSlotIdx() == slotIdx)
			{
				return EQUIP_SLOTS.get(s);
			}
		}
		return null;
	}

	/** Watched containers. */
	enum Watched
	{
		INVENTORY(InventoryID.INV, "inventory", CARRIED_DELAY_MS, false),
		EQUIPMENT(InventoryID.WORN, "equipment", CARRIED_DELAY_MS, false),
		BANK(InventoryID.BANK, "bank", STORAGE_DELAY_MS, false),
		SEED_VAULT(InventoryID.SEED_VAULT, "seed_vault", STORAGE_DELAY_MS, false),
		LOOTING_BAG(InventoryID.LOOTING_BAG, "looting_bag", STORAGE_DELAY_MS, false),
		SEED_BOX(InventoryID.SEED_BOX, "seed_box", STORAGE_DELAY_MS, true),
		TACKLE_BOX(InventoryID.TACKLE_BOX, "tackle_box", STORAGE_DELAY_MS, true),
		FORESTRY_KIT(InventoryID.FORESTRY_KIT, "forestry_kit", STORAGE_DELAY_MS, true),
		HUNTSMANS_KIT(InventoryID.HUNTSMANS_KIT, "huntsmans_kit", STORAGE_DELAY_MS, true),
		CARGO_HOLD_1(InventoryID.SAILING_BOAT_1_CARGOHOLD, "boat_cargo_hold_1", STORAGE_DELAY_MS, true),
		CARGO_HOLD_2(InventoryID.SAILING_BOAT_2_CARGOHOLD, "boat_cargo_hold_2", STORAGE_DELAY_MS, true),
		CARGO_HOLD_3(InventoryID.SAILING_BOAT_3_CARGOHOLD, "boat_cargo_hold_3", STORAGE_DELAY_MS, true),
		CARGO_HOLD_4(InventoryID.SAILING_BOAT_4_CARGOHOLD, "boat_cargo_hold_4", STORAGE_DELAY_MS, true),
		CARGO_HOLD_5(InventoryID.SAILING_BOAT_5_CARGOHOLD, "boat_cargo_hold_5", STORAGE_DELAY_MS, true),
		TRAWLING_NET(InventoryID.SAILING_TRAWLING_NET, "trawling_net", STORAGE_DELAY_MS, true);

		final int inventoryId;
		final String wireName;
		final long delayMs;
		/** Under the "Storages and loadouts" setting rather than "Items". */
		final boolean storage;

		Watched(int inventoryId, String wireName, long delayMs, boolean storage)
		{
			this.inventoryId = inventoryId;
			this.wireName = wireName;
			this.delayMs = delayMs;
			this.storage = storage;
		}

		static Watched byInventoryId(int id)
		{
			for (Watched w : values())
			{
				if (w.inventoryId == id)
				{
					return w;
				}
			}
			return null;
		}
	}

	private final Client client;
	private final SessionTracker session;
	private final TrackerSyncConfig config;
	private final ItemMapper items;
	private final ContainerPublisher publisher;

	@Inject
	ContainerCollector(Client client, SessionTracker session, TrackerSyncConfig config, ItemMapper items,
		ContainerPublisher publisher)
	{
		this.client = client;
		this.session = session;
		this.config = config;
		this.items = items;
		this.publisher = publisher;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		Watched w = Watched.byInventoryId(event.getContainerId());
		if (w != null)
		{
			capture(w, event.getItemContainer());
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			publisher.flush(Watched.BANK.wireName);
			publisher.flush(StorageCollector.POTION_STORAGE);
		}
		else if (event.getGroupId() == InterfaceID.SEED_VAULT)
		{
			publisher.flush(Watched.SEED_VAULT.wireName);
		}
	}

	@Override
	public void onSessionStart()
	{
		// Only what is always readable; storages are sent when the player opens them.
		capture(Watched.INVENTORY, client.getItemContainer(InventoryID.INV));
		capture(Watched.EQUIPMENT, client.getItemContainer(InventoryID.WORN));
	}

	@Override
	public void onSessionEnd()
	{
		// Send what was already captured, from every container collector (final flush).
		publisher.flushAll();
	}

	public void shutDown()
	{
		publisher.cancelAll();
	}

	/** Client thread. */
	private void capture(Watched w, ItemContainer container)
	{
		if (container == null || !session.isActive() || !(w.storage ? config.sendStorages() : config.sendItems()))
		{
			return;
		}
		publisher.offer(build(w, container.getItems()), w.delayMs);
	}

	/** Client thread. */
	ContainerData build(Watched w, net.runelite.api.Item[] slots)
	{
		List<ContainerItem> out = new ArrayList<>();
		for (int slot = 0; slot < slots.length; slot++)
		{
			net.runelite.api.Item it = slots[slot];
			if (it == null || it.getId() < 0 || it.getQuantity() <= 0 || items.isPlaceholder(it.getId()))
			{
				continue;
			}
			String equipSlot = w == Watched.EQUIPMENT ? equipSlotName(slot) : null;
			out.add(new ContainerItem(items.toItem(it.getId(), it.getQuantity()), slot, equipSlot));
		}
		return new ContainerData(w.wireName, out);
	}
}
