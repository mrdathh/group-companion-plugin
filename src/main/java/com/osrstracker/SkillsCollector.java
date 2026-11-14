package com.osrstracker;

import com.osrstracker.model.EventType;
import com.osrstracker.model.LevelUpData;
import com.osrstracker.model.SkillsData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Skills: StatChanged drives a debounced full snapshot (3 s after the last XP change, at most once per
 * 10 s) and immediate level_up events. The first StatChanged per skill after login or a hop only sets
 * the baseline, so the login burst never produces level-ups.
 */
@Singleton
public class SkillsCollector implements SessionTracker.Listener
{
	static final long DELAY_MS = 3_000;
	static final long MIN_INTERVAL_MS = 10_000;

	/** Every real skill, in game order (Skill.values() minus the deprecated OVERALL pseudo-skill). */
	static final List<Skill> SKILLS = realSkills();

	@SuppressWarnings("deprecation")
	private static List<Skill> realSkills()
	{
		List<Skill> out = new ArrayList<>();
		for (Skill s : Skill.values())
		{
			if (s != Skill.OVERALL)
			{
				out.add(s);
			}
		}
		return Collections.unmodifiableList(out);
	}

	private final Client client;
	private final ClientThread clientThread;
	private final SessionTracker session;
	private final TrackerSyncConfig config;
	private final Throttle snapshot;

	private final Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
	private final Map<Skill, Integer> xp = new EnumMap<>(Skill.class);

	@Inject
	SkillsCollector(Client client, ClientThread clientThread, SessionTracker session, TrackerSyncConfig config,
		ScheduledExecutorService executor, TimeSource time)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.session = session;
		this.config = config;
		this.snapshot = new Throttle(executor, time, DELAY_MS, MIN_INTERVAL_MS, MIN_INTERVAL_MS,
			() -> clientThread.invoke(this::emitSnapshot));
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING || state == GameState.LOGGING_IN)
		{
			levels.clear();
			xp.clear();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		if (!SKILLS.contains(skill))
		{
			return;
		}
		Integer previousLevel = levels.put(skill, event.getLevel());
		Integer previousXp = xp.put(skill, event.getXp());

		if (previousLevel != null && event.getLevel() > previousLevel && config.sendSkills())
		{
			session.emit(EventType.LEVEL_UP, new LevelUpData(skill.getName(), event.getLevel()));
		}
		if (previousXp == null || previousXp != event.getXp())
		{
			snapshot.trigger();
		}
	}

	@Override
	public void onSessionStart()
	{
		// Baseline for level-ups when the plugin starts mid-session (no login burst of StatChanged).
		if (client.getSkillExperience(Skill.HITPOINTS) > 0)
		{
			for (Skill skill : SKILLS)
			{
				levels.putIfAbsent(skill, client.getRealSkillLevel(skill));
				xp.putIfAbsent(skill, client.getSkillExperience(skill));
			}
		}
		snapshot.trigger();
	}

	@Override
	public void onSessionEnd()
	{
		snapshot.cancel();
	}

	public void shutDown()
	{
		snapshot.cancel();
	}

	/** Client thread. */
	void emitSnapshot()
	{
		if (!config.sendSkills() || !session.isActive())
		{
			return;
		}
		SkillsData data = readSkills();
		if (data != null)
		{
			session.emit(EventType.SKILLS, data);
		}
	}

	/** Client thread. Null while stats are not loaded yet. */
	SkillsData readSkills()
	{
		Player local = client.getLocalPlayer();
		// Hitpoints XP is never below 1154 once stats are loaded.
		if (local == null || client.getSkillExperience(Skill.HITPOINTS) <= 0)
		{
			return null;
		}
		Map<String, SkillsData.SkillValue> skills = new LinkedHashMap<>();
		for (Skill skill : SKILLS)
		{
			skills.put(skill.getName(), new SkillsData.SkillValue(client.getSkillExperience(skill), client.getRealSkillLevel(skill)));
		}
		return new SkillsData(skills, client.getTotalLevel(), client.getOverallExperience(), local.getCombatLevel());
	}
}
