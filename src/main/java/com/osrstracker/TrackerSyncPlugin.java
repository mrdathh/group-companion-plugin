package com.osrstracker;

import com.google.inject.Provides;
import com.osrstracker.timers.TimersCollector;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Tracker Sync",
	description = "Sends your loot, levels, items, quests, diaries, collection log, live status, farming timers,"
		+ " slayer, kill counts and combat achievements to your own tracker server",
	tags = {"tracker", "sync", "loot", "phone", "notifications", "collection", "log"}
)
public class TrackerSyncPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private ConfigManager configManager;

	@Inject
	private TrackerSyncConfig config;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private EventSender sender;

	@Inject
	private SessionTracker session;

	@Inject
	private LootCollector lootCollector;

	@Inject
	private SkillsCollector skillsCollector;

	@Inject
	private ContainerCollector containerCollector;

	@Inject
	private QuestDiaryCollector questDiaryCollector;

	@Inject
	private CollectionLogCollector collectionLogCollector;

	@Inject
	private DeathCollector deathCollector;

	@Inject
	private StatusCollector statusCollector;

	@Inject
	private TimersCollector timersCollector;

	@Inject
	private SlayerCollector slayerCollector;

	@Inject
	private KillCountCollector killCountCollector;

	@Inject
	private CombatAchievementCollector combatAchievementCollector;

	@Inject
	private HouseTracker houseTracker;

	private List<SessionTracker.Listener> collectors;

	@Override
	protected void startUp()
	{
		collectors = Arrays.asList(lootCollector, skillsCollector, containerCollector, questDiaryCollector,
			collectionLogCollector, deathCollector, statusCollector, timersCollector, slayerCollector,
			killCountCollector, combatAchievementCollector);
		eventBus.register(houseTracker);
		for (SessionTracker.Listener c : collectors)
		{
			eventBus.register(c);
			session.addListener(c);
		}
		applyServerConfig();
		sender.start(this::onUnauthorized);

		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				session.requestLoginCheck();
			}
		});
	}

	@Override
	protected void shutDown()
	{
		session.shutdown();
		if (collectors != null)
		{
			for (SessionTracker.Listener c : collectors)
			{
				eventBus.unregister(c);
			}
		}
		eventBus.unregister(houseTracker);
		houseTracker.reset();
		session.clearListeners();
		skillsCollector.shutDown();
		containerCollector.shutDown();
		questDiaryCollector.shutDown();
		collectionLogCollector.shutDown();
		timersCollector.shutDown();
		slayerCollector.shutDown();
		killCountCollector.shutDown();
		combatAchievementCollector.shutDown();
		sender.stop();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		session.onGameStateChanged(event.getGameState());
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		session.onGameTick();
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarbitId() == VarbitID.IRONMAN)
		{
			session.refreshAccountType();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!TrackerSyncConfig.GROUP.equals(event.getGroup()) || TrackerSyncConfig.CLIENT_ID_KEY.equals(event.getKey())
			|| (event.getProfile() != null && event.getProfile().startsWith(ConfigManager.RSPROFILE_GROUP))
			|| event.getKey().startsWith(TimersCollector.OWN_PREFIX))
		{
			return; // not a setting: the client id, or the timers' own per-account observations
		}
		if ("serverUrl".equals(event.getKey()) || "token".equals(event.getKey()))
		{
			applyServerConfig();
			clientThread.invoke(session::onServerConfigChanged);
		}
		else
		{
			// A data group may have been switched on: send current snapshots (unchanged ones are suppressed).
			clientThread.invoke(session::resnapshot);
		}
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		session.shutdown();
		event.waitFor(sender.stop());
	}

	private void applyServerConfig()
	{
		sender.configure(config.serverUrl(), config.token(), clientId());
	}

	/** Random id generated once per RuneLite install (stored in the RuneLite config). */
	private String clientId()
	{
		String id = configManager.getConfiguration(TrackerSyncConfig.GROUP, TrackerSyncConfig.CLIENT_ID_KEY);
		if (id == null || id.isEmpty())
		{
			id = UUID.randomUUID().toString();
			configManager.setConfiguration(TrackerSyncConfig.GROUP, TrackerSyncConfig.CLIENT_ID_KEY, id);
		}
		return id;
	}

	private void onUnauthorized()
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage("Tracker Sync: your server rejected the token. Nothing is sent until you fix the token in the plugin settings.")
			.build());
	}

	@Provides
	TrackerSyncConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TrackerSyncConfig.class);
	}
}
