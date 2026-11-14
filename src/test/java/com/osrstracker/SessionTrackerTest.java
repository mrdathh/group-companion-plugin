package com.osrstracker;

import com.osrstracker.model.AccountRef;
import com.osrstracker.model.IngestEvent;
import com.osrstracker.model.SessionData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Session events for realistic game-state sequences: exactly one login per real login, no events on region loads. */
public class SessionTrackerTest
{
	private final Client client = mock(Client.class);
	private final EventSender sender = mock(EventSender.class);
	private final Player player = mock(Player.class);
	private final List<String> events = new ArrayList<>();
	private final AtomicInteger sessionStarts = new AtomicInteger();
	private final AtomicInteger sessionEnds = new AtomicInteger();
	private boolean configured = true;
	private SessionTracker session;

	@Before
	public void setUp()
	{
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getName()).thenReturn("Tester");
		when(client.getAccountHash()).thenReturn(123456789L);
		when(client.getWorld()).thenReturn(335);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(client.getVarbitValue(anyInt())).thenReturn(0);
		when(sender.isConfigured()).thenAnswer(inv -> configured);
		doAnswer(inv ->
		{
			if (configured)
			{
				IngestEvent e = inv.getArgument(1);
				events.add(e.getType() + "@" + ((SessionData) e.getData()).getWorld());
			}
			return null;
		}).when(sender).enqueue(any(AccountRef.class), any(IngestEvent.class));

		session = new SessionTracker(client, sender, new TimeSource());
		session.addListener(new SessionTracker.Listener()
		{
			@Override
			public void onSessionStart()
			{
				sessionStarts.incrementAndGet();
			}

			@Override
			public void onSessionEnd()
			{
				sessionEnds.incrementAndGet();
			}
		});
	}

	/** Game state change followed by a game tick, as the client does. */
	private void state(GameState... states)
	{
		for (GameState s : states)
		{
			session.onGameStateChanged(s);
			if (s == GameState.LOGGED_IN)
			{
				session.onGameTick();
			}
		}
	}

	private void hopTo(int world, WorldType... types)
	{
		when(client.getWorld()).thenReturn(world);
		when(client.getWorldType()).thenReturn(types.length == 0 ? EnumSet.noneOf(WorldType.class) : EnumSet.copyOf(Arrays.asList(types)));
		state(GameState.HOPPING, GameState.LOADING, GameState.LOGGED_IN);
	}

	@Test
	public void fullSessionWithRegionLoadsAndAHop()
	{
		state(GameState.LOGIN_SCREEN, GameState.LOGGING_IN, GameState.LOADING, GameState.LOGGED_IN);
		assertEquals(Collections.singletonList("login@335"), events);

		// Walking across region boundaries: LOADING <-> LOGGED_IN many times.
		for (int i = 0; i < 20; i++)
		{
			state(GameState.LOADING, GameState.LOGGED_IN);
		}
		session.onGameTick();
		assertEquals(Collections.singletonList("login@335"), events);

		hopTo(420);
		assertEquals("a hop between normal worlds sends no session events", Collections.singletonList("login@335"), events);
		assertEquals(1, sessionStarts.get());

		state(GameState.LOGIN_SCREEN);
		assertEquals(Arrays.asList("login@335", "logout@420"), events);
		assertEquals(1, sessionEnds.get());
		assertFalse(session.isActive());
	}

	@Test
	public void secondRealLoginIsAnnouncedAgain()
	{
		state(GameState.LOGGING_IN, GameState.LOADING, GameState.LOGGED_IN);
		state(GameState.LOGIN_SCREEN);
		state(GameState.LOGGING_IN, GameState.LOADING, GameState.LOGGED_IN);
		assertEquals(Arrays.asList("login@335", "logout@335", "login@335"), events);
	}

	@Test
	public void connectionLostAndRecoveredIsTheSameSession()
	{
		state(GameState.LOGGING_IN, GameState.LOADING, GameState.LOGGED_IN);
		state(GameState.CONNECTION_LOST, GameState.LOADING, GameState.LOGGED_IN);
		assertEquals(Collections.singletonList("login@335"), events);
	}

	@Test
	public void hopToAnExcludedWorldAndBack()
	{
		state(GameState.LOGGING_IN, GameState.LOADING, GameState.LOGGED_IN);
		hopTo(340, WorldType.SEASONAL);
		assertFalse(session.isActive());
		state(GameState.LOADING, GameState.LOGGED_IN);
		hopTo(301);
		assertTrue(session.isActive());
		assertEquals(Arrays.asList("login@335", "logout@335", "login@301"), events);
	}

	@Test
	public void loginOnAnExcludedWorldSendsNothing()
	{
		when(client.getWorldType()).thenReturn(EnumSet.of(WorldType.DEADMAN));
		state(GameState.LOGGING_IN, GameState.LOADING, GameState.LOGGED_IN, GameState.LOADING, GameState.LOGGED_IN);
		state(GameState.LOGIN_SCREEN);
		assertTrue(events.isEmpty());
	}

	@Test
	public void pluginStopAndStartMidSessionSendsNoSessionEvents()
	{
		state(GameState.LOGGING_IN, GameState.LOADING, GameState.LOGGED_IN);
		session.pause();                // plugin disabled
		assertFalse(session.isActive());
		session.requestLoginCheck();    // plugin enabled again while logged in
		session.onGameTick();
		assertTrue(session.isActive());
		assertEquals(Collections.singletonList("login@335"), events);
		assertEquals("snapshots are captured again on resume", 2, sessionStarts.get());

		state(GameState.LOGIN_SCREEN);
		assertEquals(Arrays.asList("login@335", "logout@335"), events);
	}

	@Test
	public void pluginStartedMidSessionAnnouncesTheLoginOnce()
	{
		session.requestLoginCheck();
		session.onGameTick();
		session.onGameTick();
		assertEquals(Collections.singletonList("login@335"), events);
	}

	@Test
	public void clientShutdownSendsLogout()
	{
		state(GameState.LOGGING_IN, GameState.LOADING, GameState.LOGGED_IN);
		session.shutdown();
		assertEquals(Arrays.asList("login@335", "logout@335"), events);
	}

	@Test
	public void configuredMidSessionSendsLoginButNeverLogout()
	{
		configured = false;
		state(GameState.LOGGING_IN, GameState.LOADING, GameState.LOGGED_IN);
		assertTrue(events.isEmpty());

		configured = true;
		session.onServerConfigChanged();   // URL/token/consent became valid
		assertEquals(Collections.singletonList("login@335"), events);
		assertEquals(2, sessionStarts.get());

		session.onServerConfigChanged();   // e.g. a new token for another server user
		assertEquals("a new destination gets a login, the old one no logout",
			Arrays.asList("login@335", "login@335"), events);
		assertEquals(0, sessionEnds.get());

		state(GameState.LOGIN_SCREEN);
		assertEquals(Arrays.asList("login@335", "login@335", "logout@335"), events);
	}

	@Test
	public void noLogoutWhenTheLoginWasNeverSent()
	{
		configured = false;
		state(GameState.LOGGING_IN, GameState.LOADING, GameState.LOGGED_IN);
		configured = true;
		state(GameState.LOGIN_SCREEN);
		assertTrue(events.isEmpty());
	}

	@Test
	public void groupNameIsAddedWhenKnownWithoutASecondLogin()
	{
		state(GameState.LOGGING_IN, GameState.LOADING, GameState.LOGGED_IN);
		org.mockito.Mockito.verify(sender).setOnlineAccount(new AccountRef("123456789", "Tester", "NORMAL", 335));

		net.runelite.api.clan.ClanSettings settings = mock(net.runelite.api.clan.ClanSettings.class);
		when(settings.getName()).thenReturn("The Irons");
		when(client.getClanSettings(net.runelite.api.clan.ClanID.GROUP_IRONMAN)).thenReturn(settings);
		session.onGameTick();
		org.mockito.Mockito.verify(sender).setOnlineAccount(new AccountRef("123456789", "Tester", "NORMAL", 335, "The Irons"));

		// Briefly absent clan data (loading, hop) keeps the name.
		when(client.getClanSettings(net.runelite.api.clan.ClanID.GROUP_IRONMAN)).thenReturn(null);
		hopTo(420);
		session.onGameTick();
		org.mockito.Mockito.verify(sender).setOnlineAccount(new AccountRef("123456789", "Tester", "NORMAL", 420, "The Irons"));
		assertEquals(Collections.singletonList("login@335"), events);
	}

	@Test
	public void resnapshotOnlyTouchesOneCollector()
	{
		AtomicInteger one = new AtomicInteger();
		SessionTracker.Listener single = new SessionTracker.Listener()
		{
			@Override
			public void onSessionStart()
			{
				one.incrementAndGet();
			}

			@Override
			public void onSessionEnd()
			{
			}
		};
		session.resnapshot(single);
		assertEquals("nothing while logged out", 0, one.get());
		state(GameState.LOGGING_IN, GameState.LOADING, GameState.LOGGED_IN);
		int before = sessionStarts.get();
		session.resnapshot(single);
		assertEquals(1, one.get());
		assertEquals(before, sessionStarts.get());
		assertEquals(Collections.singletonList("login@335"), events);
	}
}
