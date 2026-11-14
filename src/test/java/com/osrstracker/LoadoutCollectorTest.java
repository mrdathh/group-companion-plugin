package com.osrstracker;

import com.osrstracker.model.EventType;
import com.osrstracker.model.Item;
import com.osrstracker.model.LoadoutsData;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Inventory Setups saved data (sample config JSON in its V3, V2 and V1 formats). */
public class LoadoutCollectorTest
{
	private static final String VORKATH_V3 = "{\"inv\":[{\"id\":385,\"q\":4},null,{\"id\":2444}],"
		+ "\"eq\":[{\"id\":10828},null,null,{\"id\":11785}],"
		+ "\"rp\":[{\"id\":560,\"q\":500},{\"id\":565,\"q\":200}],"
		+ "\"name\":\"Vorkath\",\"notes\":\"Bring antifire\",\"hc\":{\"value\":-1},\"fv\":true}";
	private static final String SKILLING_V3 = "{\"inv\":[],\"eq\":[],\"name\":\"Skilling\"}";

	private final ConfigManager configManager = mock(ConfigManager.class);
	private final SessionTracker session = mock(SessionTracker.class);
	private LoadoutCollector collector;

	@Before
	public void setUp()
	{
		TrackerSyncConfig config = mock(TrackerSyncConfig.class);
		when(config.sendStorages()).thenReturn(true);
		when(session.isActive()).thenReturn(true);
		when(configManager.getConfigurationKeys(anyString())).thenReturn(Collections.emptyList());
		ItemMapper items = mock(ItemMapper.class);
		when(items.toItem(anyInt(), anyInt())).thenAnswer(inv -> new Item(inv.getArgument(0), inv.getArgument(1), "Item", 10, 1));
		collector = new LoadoutCollector(configManager, mock(ClientThread.class), session, config, items,
			mock(ScheduledExecutorService.class), new TimeSource());
	}

	@Test
	public void currentFormatInThePluginsOrder()
	{
		when(configManager.getConfigurationKeys("inventorysetups.setupsV3_"))
			.thenReturn(Arrays.asList("inventorysetups.setupsV3_aaa", "inventorysetups.setupsV3_bbb"));
		when(configManager.getConfiguration("inventorysetups", "setupsOrderV3_")).thenReturn("[\"bbb\",\"aaa\"]");
		when(configManager.getConfiguration("inventorysetups", "setupsV3_aaa")).thenReturn(SKILLING_V3);
		when(configManager.getConfiguration("inventorysetups", "setupsV3_bbb")).thenReturn(VORKATH_V3);

		List<LoadoutCollector.RawSetup> setups = collector.readSetups();
		assertEquals(2, setups.size());
		LoadoutCollector.RawSetup v = setups.get(0);
		assertEquals("Vorkath", v.getName());
		assertEquals("Bring antifire", v.getNotes());
		assertArrayEquals(new int[]{385, 4}, v.getInventory().get(0));
		assertArrayEquals(new int[]{2444, 1}, v.getInventory().get(2));
		assertEquals(2, v.getInventory().size());
		assertArrayEquals(new int[]{11785, 1}, v.getEquipment().get(3));
		assertEquals(2, v.getRunePouch().size());
		assertEquals("Skilling", setups.get(1).getName());
		assertNull(setups.get(1).getRunePouch());

		collector.emit();
		ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
		verify(session).emit(eq(EventType.LOADOUTS), data.capture());
		LoadoutsData d = (LoadoutsData) data.getValue();
		LoadoutsData.Loadout vorkath = d.getLoadouts().get(0);
		assertEquals("Vorkath", vorkath.getId());
		assertEquals("inventory_setups", vorkath.getSource());
		assertEquals("weapon", vorkath.getEquipment().get(1).getEquipSlot());
		assertEquals("head", vorkath.getEquipment().get(0).getEquipSlot());
		assertEquals(500, vorkath.getRunePouch().get(0).getQty());
		assertNull(d.getLoadouts().get(1).getNotes());
	}

	@Test
	public void olderFormats()
	{
		when(configManager.getConfiguration("inventorysetups", "setupsV2")).thenReturn("[" + VORKATH_V3 + "]");
		assertEquals("Vorkath", collector.readSetups().get(0).getName());

		when(configManager.getConfiguration("inventorysetups", "setupsV2")).thenReturn(null);
		when(configManager.getConfiguration("inventorysetups", "setups")).thenReturn("[{\"inventory\":[{\"id\":385,\"name\":\"Shark\","
			+ "\"quantity\":3,\"fuzzy\":false},{\"id\":-1,\"quantity\":0}],\"equipment\":[],\"rune_pouch\":null,\"name\":\"Old\"}]");
		List<LoadoutCollector.RawSetup> v1 = collector.readSetups();
		assertEquals("Old", v1.get(0).getName());
		assertArrayEquals(new int[]{385, 3}, v1.get(0).getInventory().get(0));
		assertEquals(1, v1.get(0).getInventory().size());
	}

	@Test
	public void cutToTheServersLimits()
	{
		StringBuilder longName = new StringBuilder();
		for (int i = 0; i < 199; i++)
		{
			longName.append('a');
		}
		longName.appendCodePoint(0x1F600).append('b'); // a surrogate pair across the 200th unit
		StringBuilder notes = new StringBuilder();
		for (int i = 0; i < 2500; i++)
		{
			notes.append('n');
		}
		Map<Integer, int[]> inv = new LinkedHashMap<>();
		for (int slot = 0; slot < 120; slot++)
		{
			inv.put(slot, new int[]{385, 1});
		}
		Map<Integer, int[]> eq = new LinkedHashMap<>();
		for (int slot = 0; slot < 60; slot++)
		{
			eq.put(slot, new int[]{10828, 1});
		}
		List<int[]> pouch = new ArrayList<>();
		for (int i = 0; i < 12; i++)
		{
			pouch.add(new int[]{560, 1});
		}
		List<LoadoutCollector.RawSetup> many = new ArrayList<>();
		many.add(new LoadoutCollector.RawSetup(longName.toString(), notes.toString(), eq, inv, pouch));
		for (int i = 1; i < 510; i++)
		{
			many.add(new LoadoutCollector.RawSetup("Setup " + i, null, Collections.emptyMap(), Collections.emptyMap(), null));
		}

		List<LoadoutCollector.RawSetup> fitted = LoadoutCollector.fitToServer(many);
		assertEquals(500, fitted.size());
		assertEquals("Setup 499", fitted.get(499).getName());
		LoadoutCollector.RawSetup first = fitted.get(0);
		assertEquals(199, first.getName().length());
		assertEquals(2000, first.getNotes().length());
		assertEquals(50, first.getEquipment().size());
		assertEquals(100, first.getInventory().size());
		assertTrue(first.getInventory().containsKey(99));
		assertEquals(10, first.getRunePouch().size());

		// Within the limits nothing changes.
		Map<Integer, int[]> fewEq = new LinkedHashMap<>();
		fewEq.put(3, new int[]{11785, 1});
		LoadoutCollector.RawSetup small = new LoadoutCollector.RawSetup("Vorkath", "Bring antifire", fewEq, inv, null);
		LoadoutCollector.RawSetup same = LoadoutCollector.fitToServer(Collections.singletonList(small)).get(0);
		assertEquals("Vorkath", same.getName());
		assertEquals("Bring antifire", same.getNotes());
		assertEquals(1, same.getEquipment().size());
		assertNull(same.getRunePouch());
	}

	@Test
	public void nothingSentWithoutSetupsOrWithBrokenData()
	{
		when(configManager.getConfiguration("inventorysetups", "setupsV2")).thenReturn("{not json");
		assertTrue(collector.readSetups().isEmpty());
		collector.emit();
		verify(session, never()).emit(eq(EventType.LOADOUTS), any());
		verify(session, never()).emit(eq(EventType.LOADOUTS), any(), any(Instant.class));
	}
}
