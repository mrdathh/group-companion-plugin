package com.osrstracker;

import com.osrstracker.model.ContainerData;
import com.osrstracker.model.ContainerItem;
import com.osrstracker.model.EventType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
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
 * Containers: each ItemContainerChanged is captured immediately on the client thread (so storages are
 * read while they are open) and emitted after a quiet period: 2 s for inventory/equipment, 5 s for
 * bank, seed vault and looting bag, plus immediately when the bank or seed vault closes.
 */
@Singleton
public class ContainerCollector implements SessionTracker.Listener
{
	static final long CARRIED_DELAY_MS = 2_000;
	static final long STORAGE_DELAY_MS = 5_000;
	/** Upper bound so a container that changes every tick (e.g. while fletching) is still sent. */
	static final long MAX_WAIT_MS = 15_000;

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

	/** Watched containers. */
	enum Watched
	{
		INVENTORY(InventoryID.INV, "inventory", CARRIED_DELAY_MS),
		EQUIPMENT(InventoryID.WORN, "equipment", CARRIED_DELAY_MS),
		BANK(InventoryID.BANK, "bank", STORAGE_DELAY_MS),
		SEED_VAULT(InventoryID.SEED_VAULT, "seed_vault", STORAGE_DELAY_MS),
		LOOTING_BAG(InventoryID.LOOTING_BAG, "looting_bag", STORAGE_DELAY_MS);

		final int inventoryId;
		final String wireName;
		final long delayMs;

		Watched(int inventoryId, String wireName, long delayMs)
		{
			this.inventoryId = inventoryId;
			this.wireName = wireName;
			this.delayMs = delayMs;
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
	private final Map<Watched, Throttle> throttles = new HashMap<>();
	private final Map<Watched, Captured> pending = new ConcurrentHashMap<>();
	private final TimeSource time;

	@Inject
	ContainerCollector(Client client, SessionTracker session, TrackerSyncConfig config, ItemMapper items,
		ScheduledExecutorService executor, TimeSource time)
	{
		this.client = client;
		this.session = session;
		this.config = config;
		this.items = items;
		this.time = time;
		for (Watched w : Watched.values())
		{
			throttles.put(w, new Throttle(executor, time, w.delayMs, 0, MAX_WAIT_MS, () -> emit(w)));
		}
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
			throttles.get(Watched.BANK).flushNow();
		}
		else if (event.getGroupId() == InterfaceID.SEED_VAULT)
		{
			throttles.get(Watched.SEED_VAULT).flushNow();
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
		// Send what was already captured (final flush), then forget it.
		throttles.values().forEach(Throttle::flushNow);
		pending.clear();
	}

	public void shutDown()
	{
		throttles.values().forEach(Throttle::cancel);
		pending.clear();
	}

	/** Client thread. */
	private void capture(Watched w, ItemContainer container)
	{
		if (container == null || !config.sendItems() || !session.isActive())
		{
			return;
		}
		pending.put(w, new Captured(build(w, container.getItems()), time.now()));
		throttles.get(w).trigger();
	}

	/** Client thread. */
	ContainerData build(Watched w, net.runelite.api.Item[] slots)
	{
		List<ContainerItem> out = new ArrayList<>();
		EquipmentInventorySlot[] equipSlots = EquipmentInventorySlot.values();
		for (int slot = 0; slot < slots.length; slot++)
		{
			net.runelite.api.Item it = slots[slot];
			if (it == null || it.getId() < 0 || it.getQuantity() <= 0 || items.isPlaceholder(it.getId()))
			{
				continue;
			}
			String equipSlot = null;
			if (w == Watched.EQUIPMENT)
			{
				for (EquipmentInventorySlot s : equipSlots)
				{
					if (s.getSlotIdx() == slot)
					{
						equipSlot = EQUIP_SLOTS.get(s);
					}
				}
			}
			out.add(new ContainerItem(items.toItem(it.getId(), it.getQuantity()), slot, equipSlot));
		}
		return new ContainerData(w.wireName, out);
	}

	private void emit(Watched w)
	{
		Captured c = pending.remove(w);
		if (c != null && config.sendItems())
		{
			session.emit(EventType.CONTAINER, c.data, c.at);
		}
	}

	private static final class Captured
	{
		final ContainerData data;
		final Instant at;

		Captured(ContainerData data, Instant at)
		{
			this.data = data;
			this.at = at;
		}
	}
}
