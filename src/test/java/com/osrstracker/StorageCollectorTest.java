package com.osrstracker;

import com.osrstracker.model.ContainerData;
import com.osrstracker.model.ContainerItem;
import com.osrstracker.model.EventType;
import com.osrstracker.model.Item;
import java.time.Instant;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.ItemContainer;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class StorageCollectorTest
{
	private final Client client = mock(Client.class);
	private final SessionTracker session = mock(SessionTracker.class);
	private final FakeScheduler scheduler = new FakeScheduler();
	private StorageCollector collector;

	@Before
	public void setUp()
	{
		TrackerSyncConfig config = mock(TrackerSyncConfig.class);
		when(config.sendStorages()).thenReturn(true);
		when(session.isActive()).thenReturn(true);
		ItemMapper items = mock(ItemMapper.class);
		when(items.toItem(anyInt(), anyInt())).thenAnswer(inv -> new Item(inv.getArgument(0), inv.getArgument(1), "Item", 10, 1));
		collector = new StorageCollector(client, session, config, items, new ContainerPublisher(session, scheduler, scheduler.time));
	}

	private static ContainerItem item(ContainerData d, int i)
	{
		return d.getItems().get(i);
	}

	@Test
	public void runePouchFromVarbits()
	{
		EnumComposition runes = mock(EnumComposition.class);
		when(runes.getIntValue(2)).thenReturn(ItemID.WATERRUNE);
		when(runes.getIntValue(13)).thenReturn(ItemID.DEATHRUNE);
		when(client.getEnum(EnumID.RUNEPOUCH_RUNE)).thenReturn(runes);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_TYPE_1)).thenReturn(2);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_1)).thenReturn(1000);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_TYPE_3)).thenReturn(13);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_3)).thenReturn(250);

		ContainerData d = collector.readRunePouch();
		assertEquals("rune_pouch", d.getContainer());
		assertEquals(2, d.getItems().size());
		assertEquals(ItemID.WATERRUNE, item(d, 0).getId());
		assertEquals(1000, item(d, 0).getQty());
		assertEquals(0, item(d, 0).getSlot());
		assertEquals(ItemID.DEATHRUNE, item(d, 1).getId());
		assertEquals(2, item(d, 1).getSlot());
		assertNull(d.getEstimated());
	}

	@Test
	public void quiverAndPlankSack()
	{
		when(client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO)).thenReturn(ItemID.AMETHYST_ARROW);
		when(client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT)).thenReturn(800);
		ContainerData q = collector.readQuiver();
		assertEquals("quiver", q.getContainer());
		assertEquals(ItemID.AMETHYST_ARROW, item(q, 0).getId());
		assertEquals(800, item(q, 0).getQty());

		when(client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO)).thenReturn(-1);
		assertTrue("empty quiver is an empty container", collector.readQuiver().getItems().isEmpty());

		when(client.getVarbitValue(VarbitID.PLANK_SACK_OAK)).thenReturn(14);
		when(client.getVarbitValue(VarbitID.PLANK_SACK_MAHOGANY)).thenReturn(3);
		ContainerData p = collector.readPlankSack();
		assertEquals(2, p.getItems().size());
		assertEquals(ItemID.PLANK_OAK, item(p, 0).getId());
		assertEquals(14, item(p, 0).getQty());
		assertEquals(ItemID.PLANK_MAHOGANY, item(p, 1).getId());
	}

	@Test
	public void potionStorageCountsDosesOfTheOneDosePotion()
	{
		EnumComposition list = mock(EnumComposition.class);
		when(list.getIntVals()).thenReturn(new int[]{9001, 9002});
		EnumComposition empty = mock(EnumComposition.class);
		when(empty.getIntVals()).thenReturn(new int[0]);
		EnumComposition prayer = mock(EnumComposition.class);
		when(prayer.getIntValue(1)).thenReturn(ItemID._1DOSEPRAYERRESTORE);
		EnumComposition stamina = mock(EnumComposition.class);
		when(stamina.getIntValue(1)).thenReturn(ItemID._1DOSESTAMINA);
		when(client.getEnum(EnumID.POTIONSTORE_POTIONS)).thenReturn(list);
		when(client.getEnum(EnumID.POTIONSTORE_UNFINISHED_POTIONS)).thenReturn(empty);
		when(client.getEnum(9001)).thenReturn(prayer);
		when(client.getEnum(9002)).thenReturn(stamina);
		int[] stack = new int[1];
		when(client.getIntStack()).thenReturn(stack);
		doAnswer(inv ->
		{
			Object[] args = inv.getArguments();
			stack[0] = ((int) args[1]) == 9001 ? 37 : 0;
			return null;
		}).when(client).runScript(any());

		ContainerData d = collector.readPotionStorage();
		assertEquals("potion_storage", d.getContainer());
		assertEquals(1, d.getItems().size());
		assertEquals(ItemID._1DOSEPRAYERRESTORE, item(d, 0).getId());
		assertEquals(37, item(d, 0).getQty());
		assertEquals("dose", item(d, 0).getUnit());
		verify(client).runScript(ScriptID.POTIONSTORE_DOSES, 9001);
	}

	private ItemContainer container(net.runelite.api.Item... items)
	{
		ItemContainer c = mock(ItemContainer.class);
		when(c.getItems()).thenReturn(items);
		return c;
	}

	private void groupStorageChanged()
	{
		collector.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV_GROUP_TEMP,
			container(new net.runelite.api.Item(ItemID.COAL, 300))));
	}

	@Test
	public void groupStorageOnlyWhenSavedAndOnlyForGroupIronmen()
	{
		Widget saving = mock(Widget.class);
		when(saving.getText()).thenReturn("Saving...");

		when(session.accountType()).thenReturn("IRONMAN");
		groupStorageChanged();
		when(client.getWidget(InterfaceID.LoadingIconModal.TEXT)).thenReturn(saving);
		collector.onGameTick(new GameTick());
		scheduler.advance(1_000);
		verify(session, never()).emit(eq(EventType.CONTAINER), any(), any(Instant.class));

		when(session.accountType()).thenReturn("GROUP_IRONMAN");
		when(client.getWidget(InterfaceID.LoadingIconModal.TEXT)).thenReturn(null);
		groupStorageChanged();
		collector.onGameTick(new GameTick());
		scheduler.advance(1_000);
		verify(session, never()).emit(eq(EventType.CONTAINER), any(), any(Instant.class));

		when(client.getWidget(InterfaceID.LoadingIconModal.TEXT)).thenReturn(saving);
		collector.onGameTick(new GameTick());
		collector.onGameTick(new GameTick()); // "Saving..." shown for several ticks: one send
		scheduler.advance(1_000);
		ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
		verify(session, times(1)).emit(eq(EventType.CONTAINER), data.capture(), any(Instant.class));
		ContainerData d = (ContainerData) data.getValue();
		assertEquals("group_storage", d.getContainer());
		assertEquals(300, item(d, 0).getQty());
	}
}
