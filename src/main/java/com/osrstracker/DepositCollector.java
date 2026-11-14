package com.osrstracker;

import com.google.common.collect.ImmutableSet;
import com.osrstracker.model.BankDepositData;
import com.osrstracker.model.EventType;
import com.osrstracker.model.Item;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;

/**
 * Deposits made through a deposit box (the bank itself is not readable there). Technique from the Group
 * Ironmen Tracker plugin (christoabrown/group-ironmen-tracker, BSD-2-Clause: the deposit-box detection in
 * GroupIronmenTrackerPlugin and ItemContainerState#whatGotRemoved): a click on a deposit-box deposit control,
 * or a quantity typed while the deposit box is open, opens a two-tick window; inventory, equipment and looting
 * bag changes inside that window are diffed against their previous contents and the removed items become one
 * {@code bank_deposit} event, emitted on the first tick after the window closes (well within 2 s).
 *
 * <p>Clicks in the normal bank interface never open the window, so normal bank use never emits.
 */
@Singleton
public class DepositCollector implements SessionTracker.Listener
{
	/** Ticks after a deposit action during which container changes count as deposits. */
	static final int WINDOW_TICKS = 2;
	/** [clientscript,chatbox_entered]: a quantity was typed into the chatbox (Deposit-X). */
	static final int SCRIPT_CHATBOX_ENTERED = 681;

	/** Deposit-box controls whose click puts items into the bank. */
	static final Set<Integer> DEPOSIT_WIDGETS = ImmutableSet.of(
		InterfaceID.BankDepositbox.INVENTORY,
		InterfaceID.BankDepositbox.DEPOSIT_INV,
		InterfaceID.BankDepositbox.DEPOSIT_WORN,
		InterfaceID.BankDepositbox.DEPOSIT_LOOTINGBAG,
		InterfaceID.BankDepositbox.SLOT0, InterfaceID.BankDepositbox.SLOT1, InterfaceID.BankDepositbox.SLOT2,
		InterfaceID.BankDepositbox.SLOT3, InterfaceID.BankDepositbox.SLOT4, InterfaceID.BankDepositbox.SLOT5,
		InterfaceID.BankDepositbox.SLOT7, InterfaceID.BankDepositbox.SLOT9, InterfaceID.BankDepositbox.SLOT10,
		InterfaceID.BankDepositbox.SLOT12, InterfaceID.BankDepositbox.SLOT13,
		InterfaceID.BankDepositbox.EXTRA_QUIVER_SLOT);

	private static final int[] TRACKED = {InventoryID.INV, InventoryID.WORN, InventoryID.LOOTING_BAG};

	private final Client client;
	private final SessionTracker session;
	private final TrackerSyncConfig config;
	private final ItemMapper items;

	/** Last seen contents (raw id → qty) per tracked container. */
	private final Map<Integer, Map<Integer, Integer>> last = new HashMap<>();
	private final Map<Integer, Integer> removed = new LinkedHashMap<>();
	private int windowUntilTick = -1;

	@Inject
	DepositCollector(Client client, SessionTracker session, TrackerSyncConfig config, ItemMapper items)
	{
		this.client = client;
		this.session = session;
		this.config = config;
		this.items = items;
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (event.getMenuAction() == MenuAction.CC_OP || event.getMenuAction() == MenuAction.CC_OP_LOW_PRIORITY)
		{
			if (DEPOSIT_WIDGETS.contains(event.getParam1()))
			{
				openWindow();
			}
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == SCRIPT_CHATBOX_ENTERED && client.getWidget(InterfaceID.BankDepositbox.INVENTORY) != null)
		{
			openWindow();
		}
	}

	void openWindow()
	{
		windowUntilTick = client.getTickCount() + WINDOW_TICKS;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int id = event.getContainerId();
		if (id != InventoryID.INV && id != InventoryID.WORN && id != InventoryID.LOOTING_BAG)
		{
			return;
		}
		Map<Integer, Integer> now = counts(event.getItemContainer());
		Map<Integer, Integer> before = last.put(id, now);
		if (before != null && inWindow())
		{
			diff(before, now).forEach((itemId, qty) -> removed.merge(itemId, qty, Integer::sum));
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!removed.isEmpty() && !inWindow())
		{
			emit();
		}
	}

	private boolean inWindow()
	{
		return windowUntilTick >= 0 && client.getTickCount() <= windowUntilTick;
	}

	private void emit()
	{
		if (config.sendStorages() && session.isActive())
		{
			List<Item> out = new ArrayList<>();
			removed.forEach((id, qty) -> out.add(items.toItem(id, qty)));
			session.emit(EventType.BANK_DEPOSIT, new BankDepositData(out));
		}
		removed.clear();
		windowUntilTick = -1;
	}

	/** Items (raw id) whose quantity went down from {@code before} to {@code after}. */
	static Map<Integer, Integer> diff(Map<Integer, Integer> before, Map<Integer, Integer> after)
	{
		Map<Integer, Integer> out = new LinkedHashMap<>();
		before.forEach((id, qty) ->
		{
			int left = after.getOrDefault(id, 0);
			if (qty > left)
			{
				out.put(id, qty - left);
			}
		});
		return out;
	}

	static Map<Integer, Integer> counts(ItemContainer c)
	{
		Map<Integer, Integer> out = new LinkedHashMap<>();
		if (c != null)
		{
			for (net.runelite.api.Item it : c.getItems())
			{
				if (it != null && it.getId() >= 0 && it.getQuantity() > 0)
				{
					out.merge(it.getId(), it.getQuantity(), Integer::sum);
				}
			}
		}
		return out;
	}

	@Override
	public void onSessionStart()
	{
		for (int id : TRACKED)
		{
			last.put(id, counts(client.getItemContainer(id)));
		}
	}

	@Override
	public void onSessionEnd()
	{
		if (!removed.isEmpty())
		{
			emit();
		}
		last.clear();
		windowUntilTick = -1;
	}
}
