package com.osrstracker;

import com.osrstracker.model.ContainerData;
import com.osrstracker.model.EventType;
import com.osrstracker.model.Item;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Bag contents from sample game text and fill/empty diffs. */
public class BagCollectorTest
{
	private static final Map<Integer, String> NAMES = new HashMap<>();

	static
	{
		NAMES.put(ItemID.RAW_SHARK, "Raw shark");
		NAMES.put(ItemID.RAW_ANGLERFISH, "Raw anglerfish");
		NAMES.put(ItemID.UNCUT_SAPPHIRE, "Uncut sapphire");
		NAMES.put(ItemID.UNCUT_RUBY, "Uncut ruby");
		NAMES.put(ItemID.UNCUT_DIAMOND, "Uncut diamond");
		NAMES.put(ItemID.UNCUT_RED_TOPAZ, "Uncut red topaz");
		NAMES.put(ItemID.UNIDENTIFIED_RANARR, "Grimy ranarr weed");
		NAMES.put(ItemID.UNIDENTIFIED_GUAM, "Grimy guam leaf");
		NAMES.put(ItemID.MAGIC_LOGS, "Magic logs");
	}

	private final SessionTracker session = mock(SessionTracker.class);
	private final FakeScheduler scheduler = new FakeScheduler();
	private BagCollector collector;

	@Before
	public void setUp()
	{
		TrackerSyncConfig config = mock(TrackerSyncConfig.class);
		when(config.sendStorages()).thenReturn(true);
		when(session.isActive()).thenReturn(true);
		ItemMapper items = mock(ItemMapper.class);
		when(items.nameOf(anyInt())).thenAnswer(inv -> NAMES.getOrDefault((Integer) inv.getArgument(0), "Other"));
		when(items.toItem(anyInt(), anyInt())).thenAnswer(inv -> new Item(inv.getArgument(0), inv.getArgument(1),
			NAMES.getOrDefault((Integer) inv.getArgument(0), "Other"), 10, 1));
		collector = new BagCollector(mock(Client.class), mock(ClientThread.class), session, config, items,
			new ContainerPublisher(session, scheduler, scheduler.time));
	}

	private ContainerData sent()
	{
		scheduler.advance(BagCollector.DELAY_MS);
		ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
		verify(session, atLeastOnce()).emit(eq(EventType.CONTAINER), data.capture(), any(Instant.class));
		List<Object> all = data.getAllValues();
		return (ContainerData) all.get(all.size() - 1);
	}

	private static int qty(ContainerData d, int itemId)
	{
		return d.getItems().stream().filter(i -> i.getId() == itemId).mapToInt(Item::getQty).sum();
	}

	@Test
	public void fishBarrelCheckIsAFullRead()
	{
		collector.onText("The barrel contains: 12 x Raw shark, 3 x Raw anglerfish");
		collector.onGameTick(null);
		ContainerData d = sent();
		assertEquals("fish_barrel", d.getContainer());
		assertEquals(12, qty(d, ItemID.RAW_SHARK));
		assertEquals(3, qty(d, ItemID.RAW_ANGLERFISH));
		assertNull("a full read is not estimated", d.getEstimated());
	}

	@Test
	public void herbSackListingOverSeveralLines()
	{
		collector.onText("You look in your herb sack and see:");
		collector.onText("14 x Grimy ranarr weed");
		collector.onText("2 x Grimy guam leaf");
		collector.onGameTick(null);
		ContainerData d = sent();
		assertEquals("herb_sack", d.getContainer());
		assertEquals(14, qty(d, ItemID.UNIDENTIFIED_RANARR));
		assertEquals(2, qty(d, ItemID.UNIDENTIFIED_GUAM));
	}

	@Test
	public void gemBagAndCoalBagChecks()
	{
		collector.onText("Sapphires: 4 / Emeralds: 0 / Rubies: 1 / Diamonds: 2 / Dragonstones: 0");
		ContainerData gems = sent();
		assertEquals("gem_bag", gems.getContainer());
		assertEquals(4, qty(gems, ItemID.UNCUT_SAPPHIRE));
		assertEquals(1, qty(gems, ItemID.UNCUT_RUBY));
		assertEquals(2, qty(gems, ItemID.UNCUT_DIAMOND));
		assertEquals(3, gems.getItems().size());

		collector.onText("The coal bag contains 27 pieces of coal.");
		ContainerData coal = sent();
		assertEquals("coal_bag", coal.getContainer());
		assertEquals(27, qty(coal, ItemID.COAL));

		collector.onText("The coal bag contains one piece of coal.");
		assertEquals(1, qty(sent(), ItemID.COAL));
	}

	@Test
	public void emptyMessageIsAnEmptyFullRead()
	{
		collector.onText("The barrel contains: 12 x Raw shark");
		collector.onGameTick(null);
		collector.onText("The barrel is empty.");
		ContainerData d = sent();
		assertEquals("fish_barrel", d.getContainer());
		assertTrue(d.getItems().isEmpty());
	}

	@Test
	public void fillAndEmptyAreEstimatedOnTopOfAKnownBase()
	{
		Map<Integer, Integer> before = new LinkedHashMap<>();
		before.put(ItemID.MAGIC_LOGS, 20);
		before.put(ItemID.LOG_BASKET_CLOSED, 1);
		Map<Integer, Integer> after = new LinkedHashMap<>();
		after.put(ItemID.LOG_BASKET_CLOSED, 1);

		// Unknown base: a fill is not sent.
		collector.applyAction(BagCollector.Bag.LOG_BASKET, true, before, after);
		scheduler.advance(5_000);
		verify(session, never()).emit(eq(EventType.CONTAINER), any(), any(Instant.class));

		collector.onText("The basket is empty.");
		collector.applyAction(BagCollector.Bag.LOG_BASKET, true, before, after);
		ContainerData filled = sent();
		assertEquals(20, qty(filled, ItemID.MAGIC_LOGS));
		assertEquals(Boolean.TRUE, filled.getEstimated());

		Map<Integer, Integer> taken = new LinkedHashMap<>(after);
		taken.put(ItemID.MAGIC_LOGS, 5);
		collector.applyAction(BagCollector.Bag.LOG_BASKET, false, after, taken);
		assertEquals(15, qty(sent(), ItemID.MAGIC_LOGS));
	}

	@Test
	public void entryAndGemParsing()
	{
		Map<Integer, Integer> e = BagCollector.parseEntries(" 1,200 x Raw shark, 3 x Something else",
			n -> n.equalsIgnoreCase("raw shark") ? ItemID.RAW_SHARK : null);
		assertEquals(Integer.valueOf(1200), e.get(ItemID.RAW_SHARK));
		assertEquals(1, e.size());
		assertNull(BagCollector.parseGems("You catch a shark.", n -> 1));
		assertEquals("red topaz", BagCollector.singular("Red topazes"));
		assertEquals("ruby", BagCollector.singular("Rubies"));
	}
}
