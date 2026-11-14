package com.osrstracker;

import com.osrstracker.model.EventType;
import com.osrstracker.model.KillCountsData;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.hiscore.HiscoreSkill;

/**
 * Boss and activity kill counts as persisted by RuneLite core's Chat Commands plugin (RS-profile config
 * group {@code killcount}, key = lower-cased boss name, value = count; the plugin parses the game's kill
 * count chat messages). Read after login and whenever that group changes, at most once per 5 s. Names are
 * restored to display form from the hiscore names where possible.
 *
 * <p>If the Chat Commands plugin is off, the stored counts stop updating; the last stored values are still sent.
 */
@Singleton
public class KillCountCollector implements SessionTracker.Listener
{
	static final String GROUP = "killcount";
	static final long DELAY_MS = 1_000;
	static final long MIN_INTERVAL_MS = 5_000;
	static final long LOGIN_SETTLE_MS = 3_000;

	private static final Map<String, String> DISPLAY_NAMES = new HashMap<>();

	static
	{
		for (HiscoreSkill s : HiscoreSkill.values())
		{
			String n = s.getName();
			DISPLAY_NAMES.put(n.toLowerCase(Locale.ROOT), n);
			DISPLAY_NAMES.put(n.replace(":", "").toLowerCase(Locale.ROOT), n);
			DISPLAY_NAMES.put(n.replace(":", ".").toLowerCase(Locale.ROOT), n);
		}
	}

	private final ConfigManager configManager;
	private final SessionTracker session;
	private final TrackerSyncConfig config;
	private final Throttle throttle;

	@Inject
	KillCountCollector(ConfigManager configManager, SessionTracker session, TrackerSyncConfig config,
		ScheduledExecutorService executor, TimeSource time)
	{
		this.configManager = configManager;
		this.session = session;
		this.config = config;
		// Reads only the RuneLite config, so it can run on the executor thread.
		this.throttle = new Throttle(executor, time, DELAY_MS, MIN_INTERVAL_MS, MIN_INTERVAL_MS, this::emit);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (GROUP.equals(event.getGroup()) && session.isActive())
		{
			throttle.trigger();
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		if (session.isActive())
		{
			throttle.trigger();
		}
	}

	@Override
	public void onSessionStart()
	{
		throttle.triggerIn(LOGIN_SETTLE_MS);
	}

	@Override
	public void onSessionEnd()
	{
		throttle.cancel();
	}

	public void shutDown()
	{
		throttle.cancel();
	}

	void emit()
	{
		if (!config.sendProgress() || !session.isActive())
		{
			return;
		}
		KillCountsData data = read();
		if (data != null)
		{
			session.emit(EventType.KILL_COUNTS, data);
		}
	}

	/** Null when no RuneScape profile is active yet. */
	KillCountsData read()
	{
		String profile = configManager.getRSProfileKey();
		if (profile == null)
		{
			return null;
		}
		Map<String, Integer> counts = new TreeMap<>();
		for (String key : configManager.getRSProfileConfigurationKeys(GROUP, profile, ""))
		{
			String value = configManager.getConfiguration(GROUP, profile, key);
			if (value == null)
			{
				continue;
			}
			try
			{
				int count = Integer.parseInt(value.trim());
				if (count >= 0)
				{
					counts.put(displayName(key), count);
				}
			}
			catch (NumberFormatException e)
			{
				// not a count
			}
		}
		return new KillCountsData(counts);
	}

	static String displayName(String key)
	{
		String known = DISPLAY_NAMES.get(key.toLowerCase(Locale.ROOT));
		if (known != null)
		{
			return known;
		}
		StringBuilder out = new StringBuilder();
		String[] words = key.trim().split("\\s+");
		for (int i = 0; i < words.length; i++)
		{
			String w = words[i];
			if (i > 0)
			{
				out.append(' ');
			}
			boolean small = i > 0 && (w.equals("of") || w.equals("the") || w.equals("and") || w.equals("a"));
			out.append(small || w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
		}
		return out.toString();
	}
}
