package com.osrstracker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.testing.fieldbinder.Bind;
import com.google.inject.testing.fieldbinder.BoundFieldModule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.PluginManager;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Builds the plugin the way RuneLite's PluginManager does (a child injector that binds the plugin
 * instance and installs it as a module, so its @Provides config method is used) on top of mocked client
 * services, then runs startUp/shutDown. Catches Guice wiring errors that otherwise only show at runtime.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class TrackerSyncPluginInjectionTest
{
	@Mock
	@Bind
	private Client client;

	@Mock
	@Bind
	private ClientThread clientThread;

	@Mock
	@Bind
	private EventBus eventBus;

	@Mock
	@Bind
	private ConfigManager configManager;

	@Mock
	@Bind
	private ItemManager itemManager;

	@Mock
	@Bind
	private ChatMessageManager chatMessageManager;

	@Mock
	@Bind
	private PluginManager pluginManager;

	@Mock
	@Bind
	private net.runelite.client.ui.ClientToolbar clientToolbar;

	private final FakeScheduler fakeScheduler = new FakeScheduler();

	@Bind
	private final ScheduledExecutorService executor = fakeScheduler;

	/** Same fake clock as the executor, so throttles and timers agree on time. */
	@Bind
	private final TimeSource timeSource = fakeScheduler.time;

	@Bind
	private final OkHttpClient okHttpClient = new OkHttpClient();

	@Bind
	private final Gson gson = RuneLiteAPI.GSON;

	@Mock
	private TrackerSyncConfig config;

	private final MockWebServer server = new MockWebServer();
	private volatile String token = "osrs_test";
	private volatile boolean consent = true;
	private TrackerSyncPlugin plugin;

	@Before
	public void setUp() throws Exception
	{
		server.setDispatcher(new okhttp3.mockwebserver.Dispatcher()
		{
			@Override
			public MockResponse dispatch(RecordedRequest request)
			{
				return new MockResponse().setBody("{\"accepted\":[],\"duplicates\":[]}");
			}
		});
		server.start();
		when(client.getIntStack()).thenReturn(new int[]{0}); // Quest.getState runs a script and reads the stack
		when(configManager.getConfig(TrackerSyncConfig.class)).thenReturn(config);
		when(config.serverUrl()).thenReturn(server.url("/").toString());
		when(config.token()).thenAnswer(inv -> token);
		when(config.consent()).thenAnswer(inv -> consent);
		when(config.showPanel()).thenReturn(true);
		doAnswer(inv ->
		{
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(clientThread).invoke(any(Runnable.class));
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(config.sendLoot()).thenReturn(true);
		when(config.sendSkills()).thenReturn(true);
		when(config.sendItems()).thenReturn(true);
		when(config.sendQuests()).thenReturn(true);
		when(config.sendCollectionLog()).thenReturn(true);
		when(config.sendStatus()).thenReturn(true);
		when(config.sendTimers()).thenReturn(true);
		when(config.sendProgress()).thenReturn(true);
		when(config.sendStorages()).thenReturn(true);

		Injector parent = Guice.createInjector(BoundFieldModule.of(this));
		plugin = new TrackerSyncPlugin();
		parent.createChildInjector(binder ->
		{
			binder.bind(TrackerSyncPlugin.class).toInstance(plugin);
			binder.install(plugin);
		});
	}

	@After
	public void tearDown() throws Exception
	{
		server.shutdown();
	}

	@Test
	public void theTokenNeverAppearsInTheLog() throws Exception
	{
		ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		ch.qos.logback.classic.Level before = root.getLevel();
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
		appender.start();
		root.addAppender(appender);
		root.setLevel(ch.qos.logback.classic.Level.ALL);
		try
		{
			inGame();
			plugin.startUp();
			logIn();
			token = "osrs_rotated-token";
			configChanged("token", token);
			((FakeScheduler) executor).advance(TrackerSyncPlugin.CONFIG_SETTLE_MS * 2);
			sessionEventsWithin(500);
			plugin.shutDown();
		}
		finally
		{
			root.detachAppender(appender);
			root.setLevel(before);
		}
		for (ch.qos.logback.classic.spi.ILoggingEvent e : appender.list)
		{
			String line = e.getFormattedMessage() + (e.getThrowableProxy() == null ? "" : e.getThrowableProxy().getMessage());
			assertFalse(line, line.contains("osrs_test") || line.contains("osrs_rotated-token"));
		}
		verify(clientToolbar).addNavigation(any(net.runelite.client.ui.NavigationButton.class));
		verify(clientToolbar).removeNavigation(any(net.runelite.client.ui.NavigationButton.class));
	}

	@Test
	public void startsAndStopsTwice() throws Exception
	{
		for (int i = 1; i <= 2; i++)
		{
			plugin.startUp();
			verify(eventBus, times(19 * i)).register(any(Object.class));
			verify(eventBus, times(i)).register(any(ActivityTracker.class));
			plugin.shutDown();
			verify(eventBus, times(19 * i)).unregister(any(Object.class));
			verify(eventBus, times(i)).unregister(any(ActivityTracker.class));
		}
		assertEquals(0, server.getRequestCount());
	}

	private void inGame()
	{
		Player local = mock(Player.class);
		when(local.getName()).thenReturn("Tester");
		when(client.getLocalPlayer()).thenReturn(local);
		when(client.getAccountHash()).thenReturn(1234567890123456789L);
		when(client.getWorld()).thenReturn(330);
		when(client.getWorldType()).thenReturn(EnumSet.of(WorldType.MEMBERS));
		when(client.getVarbitValue(anyInt())).thenReturn(0);
	}

	private void logIn()
	{
		GameStateChanged loggedIn = new GameStateChanged();
		loggedIn.setGameState(GameState.LOGGED_IN);
		plugin.onGameStateChanged(loggedIn);
		plugin.onGameTick(new GameTick());
		((FakeScheduler) executor).advance(EventSender.FLUSH_DELAY_MS);
	}

	private List<String> eventTypes(RecordedRequest r)
	{
		List<String> types = new ArrayList<>();
		JsonArray events = new JsonParser().parse(r.getBody().readUtf8()).getAsJsonObject().getAsJsonArray("events");
		for (int i = 0; i < events.size(); i++)
		{
			types.add(events.get(i).getAsJsonObject().get("type").getAsString());
		}
		return types;
	}

	/** Every session event (login/logout) in the requests received within the wait. */
	private List<String> sessionEventsWithin(long millis) throws InterruptedException
	{
		// Responses arrive on OkHttp threads; a follow-up flush they schedule runs on the fake executor, so
		// keep nudging it (100 ms of fake time per poll) until no request came for the whole wait.
		List<String> out = new ArrayList<>();
		long idle = 0;
		while (idle < millis)
		{
			RecordedRequest r = server.takeRequest(100, TimeUnit.MILLISECONDS);
			if (r == null)
			{
				idle += 100;
				fakeScheduler.advance(100);
				continue;
			}
			idle = 0;
			for (String t : eventTypes(r))
			{
				if (t.equals("login") || t.equals("logout"))
				{
					out.add(t + " " + r.getHeader("Authorization"));
				}
			}
		}
		return out;
	}

	private void configChanged(String key, String newValue)
	{
		ConfigChanged e = new ConfigChanged();
		e.setGroup(TrackerSyncConfig.GROUP);
		e.setKey(key);
		e.setNewValue(newValue);
		plugin.onConfigChanged(e);
	}

	@Test
	public void loginIsSentAndLogoutFlushedOnClientShutdown() throws Exception
	{
		inGame();
		plugin.startUp();
		logIn();

		RecordedRequest login = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull("login request", login);
		JsonObject body = new JsonParser().parse(login.getBody().readUtf8()).getAsJsonObject();
		assertEquals("1234567890123456789", body.getAsJsonObject("account").get("hash").getAsString());
		assertEquals("Tester", body.getAsJsonObject("account").get("rsn").getAsString());
		assertEquals("NORMAL", body.getAsJsonObject("account").get("type").getAsString());
		JsonArray events = body.getAsJsonArray("events");
		assertEquals("login", events.get(0).getAsJsonObject().get("type").getAsString());
		assertEquals(330, events.get(0).getAsJsonObject().getAsJsonObject("data").get("world").getAsInt());

		plugin.onClientShutdown(new ClientShutdown());
		RecordedRequest logout = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull("logout request", logout);
		// Captured snapshots (the empty rune pouch, quiver and plank sack sent once after login) go out first.
		List<String> types = eventTypes(logout);
		assertEquals("logout", types.get(types.size() - 1));
		assertEquals(1, Collections.frequency(types, "logout"));
		plugin.shutDown();
	}

	@Test
	public void pluginRestartMidSessionSendsNoSessionEvents() throws Exception
	{
		inGame();
		plugin.startUp();
		logIn();
		assertEquals(Collections.singletonList("login Bearer osrs_test"), sessionEventsWithin(1_000));

		plugin.shutDown();
		plugin.startUp();
		plugin.onGameTick(new GameTick());
		((FakeScheduler) executor).advance(10_000);
		assertEquals("no logout on plugin stop, no second login on start", Collections.emptyList(), sessionEventsWithin(1_000));
		plugin.shutDown();
	}

	@Test
	public void tokenEditsSettleIntoOneLoginForTheNewTokenAndNoLogout() throws Exception
	{
		inGame();
		plugin.startUp();
		logIn();
		assertEquals(Collections.singletonList("login Bearer osrs_test"), sessionEventsWithin(1_000));

		// Typing / pasting: several commits within a second, ending at the new token.
		for (String partial : new String[]{"osrs_n", "osrs_ne", "osrs_new"})
		{
			token = partial;
			configChanged("token", partial);
			((FakeScheduler) executor).advance(400);
		}
		((FakeScheduler) executor).advance(TrackerSyncPlugin.CONFIG_SETTLE_MS);
		assertEquals(Collections.singletonList("login Bearer osrs_new"), sessionEventsWithin(1_000));

		// Re-pasting the same token (or adding spaces) is no change: nothing.
		token = " osrs_new ";
		configChanged("token", token);
		((FakeScheduler) executor).advance(2 * TrackerSyncPlugin.CONFIG_SETTLE_MS);
		assertEquals(Collections.emptyList(), sessionEventsWithin(500));

		// Switching a data group on sends no session events.
		configChanged("sendSkills", "true");
		((FakeScheduler) executor).advance(5_000);
		assertEquals(Collections.emptyList(), sessionEventsWithin(500));
		assertEquals(3, plugin.collectorsFor("sendProgress").size());
		plugin.shutDown();
	}

	@Test
	public void nothingIsSentUntilConsentIsGiven() throws Exception
	{
		consent = false;
		inGame();
		plugin.startUp();
		logIn();
		((FakeScheduler) executor).advance(120_000);
		assertEquals(0, server.getRequestCount());

		consent = true;
		configChanged(TrackerSyncConfig.CONSENT_KEY, "true");
		((FakeScheduler) executor).advance(TrackerSyncPlugin.CONFIG_SETTLE_MS);
		((FakeScheduler) executor).advance(EventSender.FLUSH_DELAY_MS);
		assertEquals(Collections.singletonList("login Bearer osrs_test"), sessionEventsWithin(1_000));
		plugin.shutDown();
	}

	@Test
	public void nothingIsSentOnExcludedWorlds() throws Exception
	{
		Player local = mock(Player.class);
		when(local.getName()).thenReturn("Tester");
		when(client.getLocalPlayer()).thenReturn(local);
		when(client.getAccountHash()).thenReturn(42L);
		when(client.getWorldType()).thenReturn(EnumSet.of(WorldType.SEASONAL));

		plugin.startUp();
		GameStateChanged loggedIn = new GameStateChanged();
		loggedIn.setGameState(GameState.LOGGED_IN);
		plugin.onGameStateChanged(loggedIn);
		plugin.onGameTick(new GameTick());
		((FakeScheduler) executor).advance(120_000);
		plugin.shutDown();
		assertEquals(0, server.getRequestCount());
	}
}
