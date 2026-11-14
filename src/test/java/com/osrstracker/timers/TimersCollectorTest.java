package com.osrstracker.timers;

import com.osrstracker.SessionTracker;
import com.osrstracker.TimeSource;
import com.osrstracker.TrackerSyncConfig;
import com.osrstracker.model.IngestEvent;
import com.osrstracker.model.TimersData;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.timetracking.Tab;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Prediction from stored observations: core Time Tracking data, this plugin's own, newest wins. */
public class TimersCollectorTest
{
	private final Map<String, String> stored = new HashMap<>();
	private final FarmingWorld world = new FarmingWorld();
	private TimersCollector collector;

	@Before
	public void setUp()
	{
		ConfigManager configManager = mock(ConfigManager.class);
		when(configManager.getRSProfileKey()).thenReturn("rsprofile.test");
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer(inv -> stored.get(inv.getArgument(0) + "|" + inv.getArgument(1)));
		collector = new TimersCollector(mock(Client.class), mock(ClientThread.class), configManager, mock(SessionTracker.class),
			mock(TrackerSyncConfig.class), mock(ScheduledExecutorService.class), new TimeSource(), world);
	}

	private FarmingPatch ardougneHerb()
	{
		for (FarmingPatch p : world.getTabs().get(Tab.HERB))
		{
			if (p.getRegion().getName().equals("Ardougne"))
			{
				return p;
			}
		}
		throw new AssertionError("no Ardougne herb patch");
	}

	private static int valueFor(PatchImplementation impl, Produce produce, CropState crop, int stage)
	{
		for (int v = 0; v < 256; v++)
		{
			PatchState s = impl.forVarbitValue(v);
			if (s != null && s.getProduce() == produce && s.getCropState() == crop && (stage < 0 || s.getStage() == stage))
			{
				return v;
			}
		}
		throw new AssertionError("no varbit value for " + produce + " " + crop + " " + stage);
	}

	private TimersData.Timer find(List<TimersData.Timer> timers, String id)
	{
		for (TimersData.Timer t : timers)
		{
			if (t.getId().equals(id))
			{
				return t;
			}
		}
		return null;
	}

	@Test
	public void unknownPatchesAreNotSent()
	{
		assertTrue(collector.readTimers(1_800_000_000L).isEmpty());
	}

	@Test
	public void growingHerbFromCoreTimeTrackingData()
	{
		FarmingPatch patch = ardougneHerb();
		int value = valueFor(PatchImplementation.HERB, Produce.RANARR, CropState.GROWING, 0);
		long planted = 1_800_000_000L - (1_800_000_000L % 1200); // on a 20-minute herb tick
		stored.put(TimeTrackingConfig.CONFIG_GROUP + "|" + patch.configKey(), value + ":" + planted);

		List<TimersData.Timer> timers = collector.readTimers(planted + 60);
		TimersData.Timer t = find(timers, "farming:herb:ardougne");
		assertNotNull(timers.toString(), t);
		assertEquals("farming", t.getKind());
		assertEquals("Herb", t.getCategory());
		assertEquals("Ardougne", t.getPlace());
		assertEquals("Ranarr", t.getProduce());
		assertEquals("GROWING", t.getState());
		int stages = PatchImplementation.HERB.forVarbitValue(value).getStages();
		long expected = planted + (stages - 1) * Produce.RANARR.getTickrate() * 60L;
		assertEquals(IngestEvent.formatTime(Instant.ofEpochSecond(expected)), t.getReadyAt());

		// Long after: predicted ready.
		assertEquals("READY", find(collector.readTimers(expected + 1), "farming:herb:ardougne").getState());
	}

	@Test
	public void ownNewerObservationWins()
	{
		FarmingPatch patch = ardougneHerb();
		int growing = valueFor(PatchImplementation.HERB, Produce.RANARR, CropState.GROWING, 0);
		int diseased = valueFor(PatchImplementation.HERB, Produce.RANARR, CropState.DISEASED, -1);
		stored.put(TimeTrackingConfig.CONFIG_GROUP + "|" + patch.configKey(), growing + ":1700000000");
		stored.put(TimersCollector.OWN_GROUP + "|" + TimersCollector.OWN_PREFIX + patch.configKey(), diseased + ":1700000600");
		TimersData.Timer t = find(collector.readTimers(1_700_001_000L), "farming:herb:ardougne");
		assertEquals("DISEASED", t.getState());
		assertNull(t.getReadyAt());
	}

	@Test
	public void birdHouses()
	{
		// Varp value 3 = first bird house type, seeded (BirdHouseState: value % 3 == 0).
		stored.put(TimeTrackingConfig.CONFIG_GROUP + "|" + TimeTrackingConfig.BIRD_HOUSE + "." + BirdHouseSpace.MEADOW_NORTH.getVarp(),
			"3:1700000000");
		List<TimersData.Timer> timers = collector.readTimers(1_700_000_100L);
		TimersData.Timer t = find(timers, "birdhouse:meadow-north");
		assertNotNull(timers.toString(), t);
		assertEquals("Bird house", t.getCategory());
		assertEquals("GROWING", t.getState());
		assertEquals(IngestEvent.formatTime(Instant.ofEpochSecond(1_700_000_000L + TimersCollector.BIRD_HOUSE_DURATION_S)), t.getReadyAt());
		assertEquals("READY", find(collector.readTimers(1_700_000_000L + 3_001), "birdhouse:meadow-north").getState());
	}

	@Test
	public void timerIdsAreUniqueAndStable()
	{
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (java.util.Set<FarmingPatch> patches : collector.allPatches())
		{
			for (FarmingPatch p : patches)
			{
				stored.put(TimeTrackingConfig.CONFIG_GROUP + "|" + p.configKey(), "3:1700000000");
			}
		}
		for (TimersData.Timer t : collector.readTimers(1_700_000_100L))
		{
			assertTrue("duplicate id " + t.getId(), seen.add(t.getId()));
			assertTrue(t.getId(), t.getId().matches("(farming|birdhouse):[a-z0-9:-]+"));
		}
		assertTrue(seen.size() > 50);
	}

	@Test
	public void categoryAndSlug()
	{
		assertEquals("Fruit Tree", TimersCollector.category(Tab.FRUIT_TREE));
		assertEquals("Bird Houses", TimersCollector.category(Tab.BIRD_HOUSE));
		assertEquals("kourend-kebos", TimersCollector.slug("Kourend & Kebos"));
	}
}
