package com.osrstracker;

import com.osrstracker.model.BankDepositData;
import com.osrstracker.model.EventType;
import com.osrstracker.model.Item;
import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Deposit-box deposits become one bank_deposit; normal bank use and unrelated changes never do. */
public class DepositCollectorTest
{
	private final Client client = mock(Client.class);
	private final SessionTracker session = mock(SessionTracker.class);
	private DepositCollector collector;
	private int tick = 100;

	@Before
	public void setUp()
	{
		TrackerSyncConfig config = mock(TrackerSyncConfig.class);
		when(config.sendStorages()).thenReturn(true);
		when(session.isActive()).thenReturn(true);
		when(client.getTickCount()).thenAnswer(inv -> tick);
		ItemMapper items = mock(ItemMapper.class);
		when(items.toItem(anyInt(), anyInt())).thenAnswer(inv ->
		{
			int id = inv.getArgument(0);
			return new Item(id == ItemID.MAGIC_LOGS + 1 ? ItemID.MAGIC_LOGS : id, inv.getArgument(1), "Item", 10, 1);
		});
		collector = new DepositCollector(client, session, config, items);
		inventory(new net.runelite.api.Item(ItemID.MAGIC_LOGS, 1), new net.runelite.api.Item(ItemID.MAGIC_LOGS, 1),
			new net.runelite.api.Item(ItemID.SHARK, 1));
		equipment(new net.runelite.api.Item(ItemID.ABYSSAL_WHIP, 1));
	}

	private static ItemContainer container(net.runelite.api.Item... items)
	{
		ItemContainer c = mock(ItemContainer.class);
		when(c.getItems()).thenReturn(items);
		return c;
	}

	private void inventory(net.runelite.api.Item... items)
	{
		collector.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, container(items)));
	}

	private void equipment(net.runelite.api.Item... items)
	{
		collector.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, container(items)));
	}

	private void click(int widgetId)
	{
		MenuEntry entry = mock(MenuEntry.class);
		when(entry.getType()).thenReturn(MenuAction.CC_OP);
		when(entry.getParam1()).thenReturn(widgetId);
		collector.onMenuOptionClicked(new MenuOptionClicked(entry));
	}

	private void ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			tick++;
			collector.onGameTick(new GameTick());
		}
	}

	private BankDepositData deposited()
	{
		ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
		verify(session).emit(eq(EventType.BANK_DEPOSIT), data.capture());
		return (BankDepositData) data.getValue();
	}

	@Test
	public void depositInventoryThroughTheDepositBox()
	{
		click(InterfaceID.BankDepositbox.DEPOSIT_INV);
		tick++;
		inventory(); // all gone
		ticks(DepositCollector.WINDOW_TICKS + 1);

		BankDepositData d = deposited();
		assertEquals(2, d.getItems().size());
		assertEquals(ItemID.MAGIC_LOGS, d.getItems().get(0).getId());
		assertEquals(2, d.getItems().get(0).getQty());
		assertEquals(ItemID.SHARK, d.getItems().get(1).getId());
	}

	@Test
	public void depositWornItemsAndASingleItem()
	{
		click(InterfaceID.BankDepositbox.DEPOSIT_WORN);
		equipment();
		click(InterfaceID.BankDepositbox.INVENTORY);
		inventory(new net.runelite.api.Item(ItemID.MAGIC_LOGS, 1), new net.runelite.api.Item(ItemID.MAGIC_LOGS, 1));
		ticks(DepositCollector.WINDOW_TICKS + 1);

		BankDepositData d = deposited();
		assertEquals(2, d.getItems().size());
		assertEquals(ItemID.ABYSSAL_WHIP, d.getItems().get(0).getId());
		assertEquals(ItemID.SHARK, d.getItems().get(1).getId());
	}

	@Test
	public void depositXTypedWhileTheDepositBoxIsOpen()
	{
		when(client.getWidget(InterfaceID.BankDepositbox.INVENTORY)).thenReturn(mock(net.runelite.api.widgets.Widget.class));
		collector.onScriptPostFired(new ScriptPostFired(DepositCollector.SCRIPT_CHATBOX_ENTERED));
		inventory(new net.runelite.api.Item(ItemID.SHARK, 1));
		ticks(DepositCollector.WINDOW_TICKS + 1);
		assertEquals(2, deposited().getItems().get(0).getQty());
	}

	@Test
	public void normalBankUseNeverEmits()
	{
		click(InterfaceID.Bankside.ITEMS);          // depositing from the inventory side of the bank
		click(InterfaceID.Bankmain.DEPOSITINV);     // the bank's "deposit inventory" button
		inventory();
		equipment();
		ticks(10);
		verify(session, never()).emit(eq(EventType.BANK_DEPOSIT), any());
	}

	@Test
	public void changesOutsideTheWindowAreNotDeposits()
	{
		click(InterfaceID.BankDepositbox.DEPOSIT_INV);
		ticks(DepositCollector.WINDOW_TICKS + 1);
		inventory(new net.runelite.api.Item(ItemID.SHARK, 1)); // ate/dropped later
		ticks(5);
		verify(session, never()).emit(eq(EventType.BANK_DEPOSIT), any());
	}

	@Test
	public void chatboxEntryWithoutTheDepositBoxIsIgnored()
	{
		collector.onScriptPostFired(new ScriptPostFired(DepositCollector.SCRIPT_CHATBOX_ENTERED));
		inventory();
		ticks(5);
		verify(session, never()).emit(eq(EventType.BANK_DEPOSIT), any());
	}
}
