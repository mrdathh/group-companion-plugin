package com.osrstracker;

import com.osrstracker.model.KillCountsData;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.client.config.ConfigManager;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Kill counts from the Chat Commands plugin's persisted RS-profile config, with display names. */
public class KillCountCollectorTest
{
	private final ConfigManager configManager = mock(ConfigManager.class);
	private final KillCountCollector collector = new KillCountCollector(configManager, mock(SessionTracker.class),
		mock(TrackerSyncConfig.class), mock(ScheduledExecutorService.class), new TimeSource());

	@Test
	public void readsAndRenamesStoredCounts()
	{
		String profile = "rsprofile.abc";
		when(configManager.getRSProfileKey()).thenReturn(profile);
		when(configManager.getRSProfileConfigurationKeys("killcount", profile, ""))
			.thenReturn(Arrays.asList("chambers of xeric", "vorkath", "tombs of amascut: expert mode", "duel arena wins", "broken"));
		when(configManager.getConfiguration("killcount", profile, "chambers of xeric")).thenReturn("12");
		when(configManager.getConfiguration("killcount", profile, "vorkath")).thenReturn("301");
		when(configManager.getConfiguration("killcount", profile, "tombs of amascut: expert mode")).thenReturn("4");
		when(configManager.getConfiguration("killcount", profile, "duel arena wins")).thenReturn("7");
		when(configManager.getConfiguration("killcount", profile, "broken")).thenReturn("x");

		KillCountsData data = collector.read();
		assertEquals(Integer.valueOf(12), data.getCounts().get("Chambers of Xeric"));
		assertEquals(Integer.valueOf(301), data.getCounts().get("Vorkath"));
		assertEquals(Integer.valueOf(4), data.getCounts().get("Tombs of Amascut: Expert Mode"));
		assertEquals(Integer.valueOf(7), data.getCounts().get("Duel Arena Wins"));
		assertEquals(4, data.getCounts().size());
	}

	@Test
	public void nothingWithoutAProfile()
	{
		assertNull(collector.read());
	}

	@Test
	public void displayNameFallback()
	{
		assertEquals("Hallowed Sepulchre Floor 5", KillCountCollector.displayName("hallowed sepulchre floor 5"));
		assertEquals("Theatre of the Dead", KillCountCollector.displayName("theatre of the dead"));
	}
}
