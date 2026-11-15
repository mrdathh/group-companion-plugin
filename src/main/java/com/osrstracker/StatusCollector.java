package com.osrstracker;

import com.osrstracker.model.ActivityData;
import com.osrstracker.model.EventType;
import com.osrstracker.model.Location;
import com.osrstracker.model.StatusData;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Live status (docs/PROTOCOL.md, "When things are sent"), sampled every game tick:
 * <ul>
 * <li>offered to the sender at most once per second; the sender drops it when unchanged since the last
 * accepted send and never queues it while the server is unreachable (offering it every second, not only on
 * change, gets the newest status to the server soon after it comes back);</li>
 * <li>re-sent unchanged every 30 s, so the app can tell a player standing still from a stale one;</li>
 * <li>sent at once after a world hop, ignoring the one-second limit.</li>
 * </ul>
 * Includes {@code activity} from {@link ActivityTracker}, whose fields change only on transitions, so an idle
 * alert's transition goes out with the next allowed status (within about a second).
 */
@Singleton
public class StatusCollector implements SessionTracker.Listener
{
	static final long MIN_INTERVAL_MS = 1_000;
	static final long REFRESH_MS = 30_000;

	private final Client client;
	private final SessionTracker session;
	private final TrackerSyncConfig config;
	private final LocationReader locationReader;
	private final TimeSource time;
	private final ActivityTracker activity;

	private long lastEmit = Long.MIN_VALUE / 4;
	/** Last time the content changed or an unchanged refresh was forced. */
	private long lastFresh = Long.MIN_VALUE / 4;
	private StatusData lastStatus;

	@Inject
	StatusCollector(Client client, SessionTracker session, TrackerSyncConfig config, LocationReader locationReader, TimeSource time,
		ActivityTracker activity)
	{
		this.activity = activity;
		this.client = client;
		this.session = session;
		this.config = config;
		this.locationReader = locationReader;
		this.time = time;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!session.isActive())
		{
			activity.reset();
			return;
		}
		// Tracked every tick, also while status is off, so switching it on starts from the true state.
		ActivityData signals = activity.tick();
		if (!config.sendStatus())
		{
			return;
		}
		StatusData status = read(signals);
		if (status != null)
		{
			offer(status, time.nowMillis());
		}
	}

	/** Decide whether to send this sample. Visible for tests. */
	void offer(StatusData status, long now)
	{
		boolean hopped = lastStatus != null && lastStatus.getWorld() != status.getWorld();
		if (!hopped && now - lastEmit < MIN_INTERVAL_MS)
		{
			return;
		}
		boolean changed = !status.equals(lastStatus);
		lastEmit = now;
		lastStatus = status;
		if (changed)
		{
			lastFresh = now;
			session.emit(EventType.STATUS, status);
		}
		else if (now - lastFresh >= REFRESH_MS)
		{
			lastFresh = now;
			session.emitRefresh(EventType.STATUS, status);
		}
		else
		{
			// Unchanged: still offered, the sender drops it unless the server missed the last one.
			session.emit(EventType.STATUS, status);
		}
	}

	/** Client thread. */
	StatusData read(ActivityData signals)
	{
		Player local = client.getLocalPlayer();
		Location location = locationReader.read();
		if (local == null || location == null)
		{
			return null;
		}
		return new StatusData(
			new StatusData.CurMax(client.getBoostedSkillLevel(Skill.HITPOINTS), client.getRealSkillLevel(Skill.HITPOINTS)),
			new StatusData.CurMax(client.getBoostedSkillLevel(Skill.PRAYER), client.getRealSkillLevel(Skill.PRAYER)),
			clampPercent(client.getEnergy() / 100),           // getEnergy(): 1/100ths of a percent
			clampPercent(client.getVarpValue(VarPlayerID.SA_ENERGY) / 10), // 0-1000
			client.getWorld(),
			location,
			interacting(local.getInteracting()),
			signals);
	}

	/** NPCs only; never anything about another player. */
	static StatusData.Interacting interacting(Actor target)
	{
		if (!(target instanceof NPC))
		{
			return null;
		}
		NPC npc = (NPC) target;
		String name = npc.getName() == null ? "" : Text.removeTags(npc.getName());
		return new StatusData.Interacting(name, npc.getId(), npc.getHealthRatio(), npc.getHealthScale());
	}

	static int clampPercent(int v)
	{
		return Math.max(0, Math.min(100, v));
	}

	@Override
	public void onSessionStart()
	{
		lastEmit = Long.MIN_VALUE / 4;
		lastFresh = Long.MIN_VALUE / 4;
		lastStatus = null;
	}

	@Override
	public void onSessionEnd()
	{
	}
}
