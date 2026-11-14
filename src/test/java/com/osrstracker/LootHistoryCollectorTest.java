package com.osrstracker;

import com.osrstracker.model.EventType;
import com.osrstracker.model.Item;
import com.osrstracker.model.LootHistoryData;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.http.api.RuneLiteAPI;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
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

/** The core Loot Tracker's stored records → loot_history. */
public class LootHistoryCollectorTest
{
	/** As RuneLite's Gson writes ConfigLoot (Instant = epoch millis). */
	private static final String VORKATH = "{\"type\":\"NPC\",\"name\":\"Vorkath\",\"kills\":120,\"first\":1735787045000,"
		+ "\"last\":1794650400000,\"drops\":[536,240,1748,9000,537,10]}";
	private static final String PKER_1 = "{\"type\":\"PLAYER\",\"name\":\"Some Pker\",\"kills\":2,\"first\":1700000000000,"
		+ "\"last\":1700000100000,\"drops\":[995,5000]}";
	private static final String PKER_2 = "{\"type\":\"PLAYER\",\"name\":\"Other Pker\",\"kills\":1,\"first\":1690000000000,"
		+ "\"last\":1710000000000,\"drops\":[995,1000,385,3]}";
	/** Older data with {seconds, nanos} and no "first". */
	private static final String OLD_FORMAT = "{\"type\":\"EVENT\",\"name\":\"Barrows\",\"kills\":3,"
		+ "\"last\":{\"seconds\":1600000000,\"nanos\":500000000},\"drops\":[4708,1]}";

	private final ConfigManager configManager = mock(ConfigManager.class);
	private final SessionTracker session = mock(SessionTracker.class);
	private final ItemMapper items = mock(ItemMapper.class);
	private LootHistoryCollector collector;

	@Before
	public void setUp()
	{
		TrackerSyncConfig config = mock(TrackerSyncConfig.class);
		when(config.sendLoot()).thenReturn(true);
		when(session.isActive()).thenReturn(true);
		// Noted dragon bones (537) are canonicalised to 536 by ItemMapper.
		when(items.toItem(anyInt(), anyInt())).thenAnswer(inv ->
		{
			int id = inv.getArgument(0);
			return new Item(id == 537 ? 536 : id, inv.getArgument(1), "Item " + id, 100, 10);
		});
		collector = new LootHistoryCollector(configManager, mock(ClientThread.class), session, config, items, RuneLiteAPI.GSON,
			mock(ScheduledExecutorService.class), new TimeSource());
	}

	@Test
	public void parsesTheStoredFormat()
	{
		LootHistoryCollector.Stored v = LootHistoryCollector.parse(VORKATH);
		assertEquals("NPC", v.getType());
		assertEquals("Vorkath", v.getName());
		assertEquals(120, v.getKills());
		assertEquals(Long.valueOf(1735787045000L), v.getFirst());
		assertEquals(Long.valueOf(240), v.getDrops().get(536));
		assertEquals(3, v.getDrops().size());

		LootHistoryCollector.Stored old = LootHistoryCollector.parse(OLD_FORMAT);
		assertNull(old.getFirst());
		assertEquals(Long.valueOf(1600000000500L), old.getLast());

		assertNull(LootHistoryCollector.parse("{not json"));
		assertNull(LootHistoryCollector.parse("{\"type\":\"NPC\",\"kills\":1}")); // no name
		assertEquals("UNKNOWN", LootHistoryCollector.parse("{\"type\":\"WHATEVER\",\"name\":\"x\"}").getType());
	}

	@Test
	public void buildsCanonicalTotalsAndAnonymisesPvp()
	{
		List<LootHistoryCollector.Stored> stored = Arrays.asList(LootHistoryCollector.parse(VORKATH),
			LootHistoryCollector.parse(PKER_1), LootHistoryCollector.parse(PKER_2), LootHistoryCollector.parse(OLD_FORMAT));
		LootHistoryData d = collector.build(stored);
		assertEquals(3, d.getSources().size());
		LootHistoryData.Source barrows = d.getSources().get(0); // EVENT < NPC < PLAYER
		assertEquals("Barrows", barrows.getName());
		assertNull(barrows.getFirstAt());
		LootHistoryData.Source vorkath = d.getSources().get(1);
		assertEquals("2025-01-02T03:04:05.000Z", vorkath.getFirstAt());
		assertEquals(2, vorkath.getItems().size());
		assertEquals(250, vorkath.getItems().get(0).getQty()); // 240 + 10 noted, one canonical id
		LootHistoryData.Source pvp = d.getSources().get(2);
		assertEquals("Player", pvp.getName());
		assertEquals(3, pvp.getKills());
		assertEquals(6000, pvp.getItems().stream().filter(i -> i.getId() == 995).findFirst().get().getQty());
		assertEquals(com.osrstracker.model.IngestEvent.formatTime(java.time.Instant.ofEpochMilli(1690000000000L)), pvp.getFirstAt());
		assertEquals(com.osrstracker.model.IngestEvent.formatTime(java.time.Instant.ofEpochMilli(1710000000000L)), pvp.getLastAt());
		String json = RuneLiteAPI.GSON.toJson(d);
		assertTrue("no other player's name is sent", !json.contains("Pker"));
	}

	@Test
	public void sameStoredDataGivesTheSameSnapshot()
	{
		// The sender drops a state snapshot equal to the last accepted one: identical input must give identical JSON.
		List<LootHistoryCollector.Stored> a = Arrays.asList(LootHistoryCollector.parse(PKER_2), LootHistoryCollector.parse(VORKATH));
		List<LootHistoryCollector.Stored> b = Arrays.asList(LootHistoryCollector.parse(VORKATH), LootHistoryCollector.parse(PKER_2));
		assertEquals(RuneLiteAPI.GSON.toJson(collector.build(a)), RuneLiteAPI.GSON.toJson(collector.build(b)));
	}

	@Test
	public void readsTheCurrentProfilesKeysAndSends()
	{
		when(configManager.getRSProfileKey()).thenReturn("rsprofile.abc");
		when(configManager.getRSProfileConfigurationKeys("loottracker", "rsprofile.abc", "drops_"))
			.thenReturn(Arrays.asList("drops_NPC_Vorkath", "drops_EVENT_Barrows"));
		when(configManager.getConfiguration("loottracker", "rsprofile.abc", "drops_NPC_Vorkath")).thenReturn(VORKATH);
		when(configManager.getConfiguration("loottracker", "rsprofile.abc", "drops_EVENT_Barrows")).thenReturn(OLD_FORMAT);
		collector.emit();
		ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
		verify(session).emit(eq(EventType.LOOT_HISTORY), data.capture());
		assertEquals(2, ((LootHistoryData) data.getValue()).getSources().size());
	}

	@Test
	public void nothingWithoutStoredLoot()
	{
		when(configManager.getRSProfileKey()).thenReturn("rsprofile.abc");
		when(configManager.getRSProfileConfigurationKeys("loottracker", "rsprofile.abc", "drops_")).thenReturn(new ArrayList<>());
		collector.emit();
		verify(session, never()).emit(eq(EventType.LOOT_HISTORY), any());
	}

	@Test
	public void aHistoryOverTheLimitsIsSentTrimmedNotSkipped()
	{
		// 1,500 sources x 25 items is about 3.3 MB: over the byte limit, so the oldest sources are left out.
		when(configManager.getRSProfileKey()).thenReturn("rsprofile.abc");
		List<String> keys = new ArrayList<>();
		for (int s = 0; s < 1500; s++)
		{
			StringBuilder drops = new StringBuilder();
			for (int i = 0; i < 25; i++)
			{
				drops.append(i == 0 ? "" : ",").append(20000 + s * 25 + i).append(',').append(123456);
			}
			String key = "drops_NPC_Monster " + s;
			keys.add(key);
			when(configManager.getConfiguration("loottracker", "rsprofile.abc", key)).thenReturn("{\"type\":\"NPC\","
				+ "\"name\":\"Some monster name " + s + "\",\"kills\":12345,\"first\":1600000000000,\"last\":" + (1700000000000L + s * 1000L)
				+ ",\"drops\":[" + drops + "]}");
		}
		when(configManager.getRSProfileConfigurationKeys("loottracker", "rsprofile.abc", "drops_")).thenReturn(keys);
		when(items.toItem(anyInt(), anyInt())).thenAnswer(inv -> new Item(inv.getArgument(0), inv.getArgument(1),
			"A typical item name (u)", 1234567, 76543));

		collector.emit();
		ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
		verify(session).emit(eq(EventType.LOOT_HISTORY), data.capture());
		LootHistoryData sent = (LootHistoryData) data.getValue();
		int bytes = RuneLiteAPI.GSON.toJson(sent).getBytes(StandardCharsets.UTF_8).length;
		assertTrue(bytes + " bytes", bytes <= LootHistoryLimiter.MAX_BYTES);
		assertTrue(sent.getSources().size() > 500 && sent.getSources().size() < 1500);
		// The newest source (highest "last") is kept, the oldest is not.
		assertTrue(sent.getSources().stream().anyMatch(s -> s.getName().equals("Some monster name 1499")));
		assertTrue(sent.getSources().stream().noneMatch(s -> s.getName().equals("Some monster name 0")));
	}

	@Test
	public void realisticWorstCaseSize()
	{
		// A long-played account: 600 sources with on average 25 distinct items each, realistic name lengths.
		List<LootHistoryCollector.Stored> stored = new ArrayList<>();
		for (int s = 0; s < 600; s++)
		{
			StringBuilder drops = new StringBuilder();
			for (int i = 0; i < 25; i++)
			{
				drops.append(i == 0 ? "" : ",").append(20000 + s * 25 + i).append(',').append(123456);
			}
			stored.add(LootHistoryCollector.parse("{\"type\":\"NPC\",\"name\":\"Some monster name " + s + "\",\"kills\":12345,"
				+ "\"first\":1600000000000,\"last\":1790000000000,\"drops\":[" + drops + "]}"));
		}
		when(items.toItem(anyInt(), anyInt())).thenAnswer(inv -> new Item(inv.getArgument(0), inv.getArgument(1),
			"A typical item name (u)", 1234567, 76543));
		int bytes = RuneLiteAPI.GSON.toJson(collector.build(stored)).getBytes(StandardCharsets.UTF_8).length;
		System.out.println("loot_history worst case (600 sources x 25 items): " + bytes + " bytes");
		assertTrue(bytes + " bytes", bytes < LootHistoryLimiter.MAX_BYTES);
	}
}
