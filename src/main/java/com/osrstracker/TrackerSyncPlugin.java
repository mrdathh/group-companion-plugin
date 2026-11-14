package com.osrstracker;

import com.google.inject.Provides;
import com.osrstracker.timers.TimersCollector;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
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

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private TimeSource time;

	private List<SessionTracker.Listener> collectors;
	/** Applies URL/token/consent edits once they settle, so typing or re-pasting a token is one change. */
	private Throttle serverConfigChange;
	private boolean consentHintShown;

	static final long CONFIG_SETTLE_MS = 2_000;

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
		serverConfigChange = new Throttle(executor, time, CONFIG_SETTLE_MS, 0, 5 * CONFIG_SETTLE_MS,
			() -> clientThread.invoke(this::onServerConfigSettled));
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
		// Plugin stopped (not a game logout): no logout event, and a restart resumes without a second login.
		session.pause();
		if (serverConfigChange != null)
		{
			serverConfigChange.cancel();
		}
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
		if (!consentHintShown && session.isActive() && !config.consent()
			&& !config.token().trim().isEmpty() && Consent.host(config.serverUrl()) != null)
		{
			consentHintShown = true;
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.GAMEMESSAGE)
				.runeLiteFormattedMessage("Tracker Sync: nothing is sent yet. To start, tick \"Allow sending to this server\""
					+ " in the plugin settings.")
				.build());
		}
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
		String key = event.getKey();
		if (TrackerSyncConfig.CONSENT_HOST_KEY.equals(key))
		{
			return; // written by applyServerConfig itself
		}
		if ("serverUrl".equals(key) || "token".equals(key) || TrackerSyncConfig.CONSENT_KEY.equals(key))
		{
			if (serverConfigChange != null)
			{
				serverConfigChange.trigger();
			}
			return;
		}
		// A data group switched on: send that group's current snapshots, nothing else.
		if ("true".equals(event.getNewValue()))
		{
			List<SessionTracker.Listener> group = collectorsFor(key);
			clientThread.invoke(() -> group.forEach(session::resnapshot));
		}
	}

	/** Collectors whose snapshots belong to a data-group toggle (loot and collection log have none to resend). */
	List<SessionTracker.Listener> collectorsFor(String key)
	{
		switch (key)
		{
			case "sendSkills":
				return Collections.singletonList(skillsCollector);
			case "sendItems":
				return Collections.singletonList(containerCollector);
			case "sendQuests":
				return Collections.singletonList(questDiaryCollector);
			case "sendStatus":
				return Collections.singletonList(statusCollector);
			case "sendTimers":
				return Collections.singletonList(timersCollector);
			case "sendProgress":
				return Arrays.asList(slayerCollector, killCountCollector, combatAchievementCollector);
			default:
				return Collections.emptyList();
		}
	}

	/** Client thread, after URL/token/consent edits settled. */
	private void onServerConfigSettled()
	{
		if (applyServerConfig())
		{
			session.onServerConfigChanged();
		}
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		session.shutdown();
		event.waitFor(sender.stop());
	}

	/**
	 * Apply URL, token and consent to the sender. Consent is bound to the URL's host: a different host
	 * unticks the consent box so the player is asked again (see {@link Consent}).
	 *
	 * @return true if the effective destination changed
	 */
	boolean applyServerConfig()
	{
		String storedHost = configManager.getConfiguration(TrackerSyncConfig.GROUP, TrackerSyncConfig.CONSENT_HOST_KEY);
		Consent.Decision d = Consent.decide(config.serverUrl(), config.consent(), storedHost);
		if (!d.getBoundHost().equals(storedHost == null ? "" : storedHost))
		{
			if (d.getBoundHost().isEmpty())
			{
				configManager.unsetConfiguration(TrackerSyncConfig.GROUP, TrackerSyncConfig.CONSENT_HOST_KEY);
			}
			else
			{
				configManager.setConfiguration(TrackerSyncConfig.GROUP, TrackerSyncConfig.CONSENT_HOST_KEY, d.getBoundHost());
			}
		}
		if (d.isChecked() != config.consent())
		{
			configManager.setConfiguration(TrackerSyncConfig.GROUP, TrackerSyncConfig.CONSENT_KEY, d.isChecked());
		}
		return sender.configure(config.serverUrl(), config.token(), clientId(), d.isGranted());
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
