package com.osrstracker;

import com.osrstracker.model.ActivityData;
import com.osrstracker.model.IngestEvent;
import java.time.Instant;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.client.eventbus.Subscribe;

/**
 * Activity signals for the app's idle alerts ({@code status.activity}). Observes only: it never simulates input
 * or does anything to keep the player logged in. Every field changes only on a transition, so a player who keeps
 * doing the same thing produces an unchanged {@code status}. The rules follow RuneLite core's Idle Notifier
 * (runelite-client 1.13.6, IdleNotifierPlugin; own code, no lines copied):
 * <ul>
 * <li><b>Input idle and logout</b> ({@code checkIdleLogout}): the game client's own idle counters,
 * {@code min(getKeyboardIdleTicks(), getMouseIdleTicks())}, in client ticks of {@link Constants#CLIENT_TICK_LENGTH}
 * (20 ms), against {@code getIdleTimeout()} (also client ticks; 15000 = 5 min unless a plugin changed it). Mouse
 * movement, clicks, drags and key presses over the game reset them, which is what the client's idle logout uses.
 * While the player is being hit, the game keeps them logged in until about 20 minutes after the last click or key
 * press (the Idle Notifier warns at 19), so {@code logoutAt} is pushed out to that.</li>
 * <li><b>Combat</b> (the Idle Notifier's combat checks): an NPC target with an "Attack" option, or a hitsplat on
 * the player, or one the player dealt (also on another player in PvP: only this boolean comes of it), within the
 * last {@value #COMBAT_TICKS} game ticks; a failed spell's splash on the player counts as a hit.</li>
 * <li><b>Action idle</b> (animation, interaction and movement idle): no animation, no tile change, no interaction
 * target and no combat for {@value #ACTION_IDLE_MS} ms in a row. Requiring a continuous stretch covers the short
 * frames without an animation between skilling actions, which the Idle Notifier also waits out.</li>
 * </ul>
 */
@Singleton
public class ActivityTracker
{
	static final long INPUT_IDLE_MS = 10_000;
	static final long ACTION_IDLE_MS = 3_000;
	/** The Idle Notifier's HIGHEST_MONSTER_ATTACK_SPEED: a hit keeps combat going this many game ticks. */
	static final int COMBAT_TICKS = 8;
	/** Logout while being hit: about 20 minutes after the last click or key press. */
	static final long COMBAT_LOGOUT_MS = 20 * 60_000L;
	/** The game's idle timeout when the client reports none: 5 minutes. */
	static final int DEFAULT_IDLE_TIMEOUT_TICKS = 15_000;

	/** What one game tick shows of the player and their input. */
	@Value
	static class Sample
	{
		int keyboardIdleTicks;
		int mouseIdleTicks;
		/** Client ticks. */
		int idleTimeout;
		/** Wall-clock millis of the last mouse press, 0 if none. */
		long mouseLastPressedMillis;
		int animation;
		WorldPoint position;
		boolean interacting;
		boolean attackTarget;
	}

	private final Client client;
	private final TimeSource time;

	private Long inputIdleSince;
	private Long logoutAt;
	private int logoutTimeout;
	private boolean logoutBeingHit;

	private int hitOnPlayerTicks;
	private int hitByPlayerTicks;
	private boolean inCombat;
	private Long combatEndedAt;

	private WorldPoint lastPosition;
	private Long lastBusyAt;
	private Long actionIdleSince;

	private ActivityData current;

	@Inject
	ActivityTracker(Client client, TimeSource time)
	{
		this.client = client;
		this.time = time;
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (!event.getHitsplat().isMine())
		{
			return;
		}
		if (event.getActor() == client.getLocalPlayer())
		{
			hitOnPlayer();
		}
		else
		{
			hitByPlayer();
		}
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		Actor actor = event.getActor();
		if (actor != null && actor == client.getLocalPlayer() && actor.hasSpotAnim(SpotanimID.FAILEDSPELL_IMPACT))
		{
			hitOnPlayer();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			reset();
		}
	}

	void hitOnPlayer()
	{
		hitOnPlayerTicks = COMBAT_TICKS;
	}

	void hitByPlayer()
	{
		hitByPlayerTicks = COMBAT_TICKS;
	}

	/** Client thread, once per game tick. Returns the current signals, or null before the player exists. */
	ActivityData tick()
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return current;
		}
		Actor target = local.getInteracting();
		return update(time.nowMillis(), new Sample(client.getKeyboardIdleTicks(), client.getMouseIdleTicks(),
			client.getIdleTimeout(), client.getMouseLastPressedMillis(), local.getAnimation(), local.getWorldLocation(),
			target != null, isAttackable(target)));
	}

	/** One game tick. Visible for tests. */
	ActivityData update(long now, Sample s)
	{
		// Combat
		hitOnPlayerTicks = Math.max(hitOnPlayerTicks - 1, 0);
		hitByPlayerTicks = Math.max(hitByPlayerTicks - 1, 0);
		boolean combat = s.isAttackTarget() || hitOnPlayerTicks > 0 || hitByPlayerTicks > 0;
		if (combat && !inCombat)
		{
			combatEndedAt = null;
		}
		else if (!combat && inCombat)
		{
			combatEndedAt = now;
		}
		inCombat = combat;

		// Input idle and logout
		long idleMs = (long) Math.min(s.getKeyboardIdleTicks(), s.getMouseIdleTicks()) * Constants.CLIENT_TICK_LENGTH;
		boolean beingHit = hitOnPlayerTicks > 0;
		int timeout = s.getIdleTimeout() > 0 ? s.getIdleTimeout() : DEFAULT_IDLE_TIMEOUT_TICKS;
		if (idleMs < INPUT_IDLE_MS)
		{
			inputIdleSince = null;
			logoutAt = null;
		}
		else
		{
			boolean started = inputIdleSince == null;
			if (started)
			{
				inputIdleSince = now - idleMs;
			}
			if (started || timeout != logoutTimeout || beingHit != logoutBeingHit)
			{
				long lastPressOrKey = Math.max(s.getMouseLastPressedMillis(),
					now - (long) s.getKeyboardIdleTicks() * Constants.CLIENT_TICK_LENGTH);
				logoutAt = logoutAt(inputIdleSince, timeout, beingHit, lastPressOrKey);
				logoutTimeout = timeout;
				logoutBeingHit = beingHit;
			}
		}

		// Action idle
		boolean moved = lastPosition != null && s.getPosition() != null && !lastPosition.equals(s.getPosition());
		lastPosition = s.getPosition();
		boolean busy = lastBusyAt == null || s.getAnimation() != -1 || moved || s.isInteracting() || combat;
		if (busy)
		{
			lastBusyAt = now;
			actionIdleSince = null;
		}
		else if (actionIdleSince == null && now - lastBusyAt >= ACTION_IDLE_MS)
		{
			actionIdleSince = lastBusyAt;
		}

		current = new ActivityData(format(inputIdleSince), format(logoutAt), inCombat, format(combatEndedAt),
			format(actionIdleSince));
		return current;
	}

	/**
	 * When the game logs out an idle player: the client's idle timeout after input stopped, or, while being hit,
	 * no earlier than {@link #COMBAT_LOGOUT_MS} after the last click or key press.
	 */
	static long logoutAt(long inputIdleSince, int idleTimeoutTicks, boolean beingHit, long lastPressOrKey)
	{
		long base = inputIdleSince + (long) idleTimeoutTicks * Constants.CLIENT_TICK_LENGTH;
		return beingHit ? Math.max(base, lastPressOrKey + COMBAT_LOGOUT_MS) : base;
	}

	/** The Idle Notifier's combat target test: an NPC offering "Attack". */
	static boolean isAttackable(Actor target)
	{
		if (!(target instanceof NPC))
		{
			return false;
		}
		NPCComposition composition = ((NPC) target).getComposition();
		String[] actions = composition == null ? null : composition.getActions();
		if (actions == null)
		{
			return false;
		}
		for (String action : actions)
		{
			if ("Attack".equals(action))
			{
				return true;
			}
		}
		return false;
	}

	private static String format(Long millis)
	{
		return millis == null ? null : IngestEvent.formatTime(Instant.ofEpochMilli(millis));
	}

	/** Forget everything (login screen, plugin stop, no active session). */
	void reset()
	{
		inputIdleSince = null;
		logoutAt = null;
		logoutTimeout = 0;
		logoutBeingHit = false;
		hitOnPlayerTicks = 0;
		hitByPlayerTicks = 0;
		inCombat = false;
		combatEndedAt = null;
		lastPosition = null;
		lastBusyAt = null;
		actionIdleSince = null;
		current = null;
	}
}
