package com.osrstracker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.testing.fieldbinder.Bind;
import com.google.inject.testing.fieldbinder.BoundFieldModule;
import java.util.EnumSet;
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
import static org.junit.Assert.assertNotNull;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

	@Bind
	private final ScheduledExecutorService executor = new FakeScheduler();

	@Bind
	private final OkHttpClient okHttpClient = new OkHttpClient();

	@Bind
	private final Gson gson = RuneLiteAPI.GSON;

	@Mock
	private TrackerSyncConfig config;

	private final MockWebServer server = new MockWebServer();
	private TrackerSyncPlugin plugin;

	@Before
	public void setUp() throws Exception
	{
		server.start();
		when(configManager.getConfig(TrackerSyncConfig.class)).thenReturn(config);
		when(config.serverUrl()).thenReturn(server.url("/").toString());
		when(config.token()).thenReturn("osrs_test");
		when(config.sendLoot()).thenReturn(true);
		when(config.sendSkills()).thenReturn(true);
		when(config.sendItems()).thenReturn(true);
		when(config.sendQuests()).thenReturn(true);
		when(config.sendCollectionLog()).thenReturn(true);
		when(config.sendStatus()).thenReturn(true);
		when(config.sendTimers()).thenReturn(true);
		when(config.sendProgress()).thenReturn(true);

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
	public void startsAndStopsTwice() throws Exception
	{
		for (int i = 1; i <= 2; i++)
		{
			plugin.startUp();
			verify(eventBus, times(12 * i)).register(any(Object.class));
			plugin.shutDown();
			verify(eventBus, times(12 * i)).unregister(any(Object.class));
		}
		assertEquals(0, server.getRequestCount());
	}

	@Test
	public void loginIsSentAndLogoutFlushedOnShutdown() throws Exception
	{
		server.enqueue(new MockResponse().setBody("{\"accepted\":[],\"duplicates\":[]}"));
		server.enqueue(new MockResponse().setBody("{\"accepted\":[],\"duplicates\":[]}"));
		Player local = mock(Player.class);
		when(local.getName()).thenReturn("Tester");
		when(client.getLocalPlayer()).thenReturn(local);
		when(client.getAccountHash()).thenReturn(1234567890123456789L);
		when(client.getWorld()).thenReturn(330);
		when(client.getWorldType()).thenReturn(EnumSet.of(WorldType.MEMBERS));
		when(client.getVarbitValue(anyInt())).thenReturn(0);

		plugin.startUp();
		GameStateChanged loggedIn = new GameStateChanged();
		loggedIn.setGameState(GameState.LOGGED_IN);
		plugin.onGameStateChanged(loggedIn);
		plugin.onGameTick(new GameTick());
		((FakeScheduler) executor).advance(EventSender.FLUSH_DELAY_MS);

		RecordedRequest login = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull("login request", login);
		JsonObject body = new JsonParser().parse(login.getBody().readUtf8()).getAsJsonObject();
		assertEquals("1234567890123456789", body.getAsJsonObject("account").get("hash").getAsString());
		assertEquals("Tester", body.getAsJsonObject("account").get("rsn").getAsString());
		assertEquals("NORMAL", body.getAsJsonObject("account").get("type").getAsString());
		JsonArray events = body.getAsJsonArray("events");
		assertEquals("login", events.get(0).getAsJsonObject().get("type").getAsString());
		assertEquals(330, events.get(0).getAsJsonObject().getAsJsonObject("data").get("world").getAsInt());

		plugin.shutDown();
		RecordedRequest logout = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull("logout request", logout);
		JsonObject logoutBody = new JsonParser().parse(logout.getBody().readUtf8()).getAsJsonObject();
		assertEquals("logout", logoutBody.getAsJsonArray("events").get(0).getAsJsonObject().get("type").getAsString());
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
