package com.osrstracker;

import com.osrstracker.model.EventType;
import com.osrstracker.model.Location;
import com.osrstracker.model.StatusData;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Status cadence: at most once per second, unchanged refresh every 30 s, immediately after a world hop. */
public class StatusCollectorTest
{
	private final SessionTracker session = mock(SessionTracker.class);
	private StatusCollector collector;

	@Before
	public void setUp()
	{
		TrackerSyncConfig config = mock(TrackerSyncConfig.class);
		when(config.sendStatus()).thenReturn(true);
		when(session.isActive()).thenReturn(true);
		collector = new StatusCollector(mock(Client.class), session, config, mock(LocationReader.class), new TimeSource(),
			mock(ActivityTracker.class));
	}

	private static StatusData status(int hp, int world)
	{
		return new StatusData(new StatusData.CurMax(hp, 99), new StatusData.CurMax(1, 1), 100, 100, world,
			new Location(3222, 3218, 0, 12850, false, false, null), null);
	}

	@Test
	public void atMostOncePerSecond()
	{
		collector.offer(status(50, 301), 0);
		collector.offer(status(49, 301), 600);   // next game tick: too soon
		collector.offer(status(48, 301), 1200);  // allowed
		verify(session).emit(EventType.STATUS, status(50, 301));
		verify(session, never()).emit(EventType.STATUS, status(49, 301));
		verify(session).emit(EventType.STATUS, status(48, 301));
	}

	@Test
	public void unchangedStatusIsRefreshedEvery30Seconds()
	{
		collector.offer(status(50, 301), 0);
		verify(session, times(1)).emit(eq(EventType.STATUS), any());
		for (long t = 1200; t < StatusCollector.REFRESH_MS; t += 1200)
		{
			collector.offer(status(50, 301), t);
		}
		verify(session, never()).emitRefresh(any(), any());
		collector.offer(status(50, 301), StatusCollector.REFRESH_MS);
		verify(session, times(1)).emitRefresh(EventType.STATUS, status(50, 301));

		// A change restarts the 30 s.
		reset(session);
		when(session.isActive()).thenReturn(true);
		collector.offer(status(40, 301), StatusCollector.REFRESH_MS + 10_000);
		collector.offer(status(40, 301), 2 * StatusCollector.REFRESH_MS + 9_000);
		verify(session, never()).emitRefresh(any(), any());
		collector.offer(status(40, 301), 2 * StatusCollector.REFRESH_MS + 10_000);
		verify(session).emitRefresh(EventType.STATUS, status(40, 301));
	}

	@Test
	public void anActivityTransitionIsSentWithTheNextAllowedStatus()
	{
		StatusData busy = new StatusData(new StatusData.CurMax(50, 99), new StatusData.CurMax(1, 1), 100, 100, 301,
			new Location(3222, 3218, 0, 12850, false, false, null), null,
			new com.osrstracker.model.ActivityData(null, null, true, null, null));
		StatusData ended = new StatusData(new StatusData.CurMax(50, 99), new StatusData.CurMax(1, 1), 100, 100, 301,
			new Location(3222, 3218, 0, 12850, false, false, null), null,
			new com.osrstracker.model.ActivityData(null, null, false, "2026-11-15T14:00:00.600Z", null));
		collector.offer(busy, 0);
		collector.offer(ended, 600);   // combat ended on the next game tick: within the one-second limit
		verify(session, never()).emit(EventType.STATUS, ended);
		collector.offer(ended, 1200);
		verify(session).emit(EventType.STATUS, ended);
	}

	@Test
	public void worldHopIsSentAtOnce()
	{
		collector.offer(status(50, 301), 0);
		collector.offer(status(50, 420), 300);  // same tile, new world, within the second
		verify(session).emit(EventType.STATUS, status(50, 420));
	}

	@Test
	public void interactingIsNpcOnly()
	{
		NPC npc = mock(NPC.class);
		when(npc.getName()).thenReturn("<col=ffff00>Goblin</col>");
		when(npc.getId()).thenReturn(3029);
		when(npc.getHealthRatio()).thenReturn(10);
		when(npc.getHealthScale()).thenReturn(30);
		StatusData.Interacting i = StatusCollector.interacting(npc);
		assertEquals("Goblin", i.getName());
		assertEquals(Integer.valueOf(3029), i.getId());
		assertEquals(10, i.getHpRatio());
		assertEquals(30, i.getHpScale());

		assertNull(StatusCollector.interacting(mock(Player.class)));
		assertNull(StatusCollector.interacting(null));
	}

	@Test
	public void percentagesAreClamped()
	{
		assertEquals(100, StatusCollector.clampPercent(10000 / 100));
		assertEquals(0, StatusCollector.clampPercent(-5));
		assertEquals(73, StatusCollector.clampPercent(7300 / 100));
	}
}
