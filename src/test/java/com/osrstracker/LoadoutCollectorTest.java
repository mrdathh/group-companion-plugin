package com.osrstracker;

import com.osrstracker.model.EventType;
import com.osrstracker.model.Item;
import com.osrstracker.model.LoadoutsData;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
	public void nothingSentWithoutSetupsOrWithBrokenData()
	{
		when(configManager.getConfiguration("inventorysetups", "setupsV2")).thenReturn("{not json");
		assertTrue(collector.readSetups().isEmpty());
		collector.emit();
		verify(session, never()).emit(eq(EventType.LOADOUTS), any());
		verify(session, never()).emit(eq(EventType.LOADOUTS), any(), any(Instant.class));
	}
}
