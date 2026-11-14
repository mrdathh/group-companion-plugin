package com.osrstracker;

import com.osrstracker.model.DeathData;
import com.osrstracker.model.EventType;
import com.osrstracker.model.Location;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.InteractingChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Death of the local player (ActorDeath) with its real-world location (instances and boats, see LocationReader). The killer is the last actor that
 * started interacting with the player within the last few ticks, falling back to the player's own target.
 * Other players are reported as type PLAYER without a name.
 */
@Singleton
public class DeathCollector implements SessionTracker.Listener
{
	/** How long an attacker stays the likely killer. */
	static final int ATTACKER_TICKS = 16;

	private final Client client;
	private final SessionTracker session;
	private final LocationReader locationReader;

	private DeathData.Killer lastAttacker;
	private int lastAttackerTick = -1;

	@Inject
	DeathCollector(Client client, SessionTracker session, LocationReader locationReader)
	{
		this.client = client;
		this.session = session;
		this.locationReader = locationReader;
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		Player local = client.getLocalPlayer();
		if (local != null && event.getTarget() == local && event.getSource() != null)
		{
			DeathData.Killer k = killerOf(event.getSource());
			if (k != null)
			{
				lastAttacker = k;
				lastAttackerTick = client.getTickCount();
			}
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Player local = client.getLocalPlayer();
		if (local == null || event.getActor() != local || !session.isActive())
		{
			return;
		}
		Location where = locationReader.read();
		if (where == null)
		{
			return;
		}
		DeathData.Location location = new DeathData.Location(where.getX(), where.getY(), where.getPlane(), where.getRegionId());

		DeathData.Killer killer = null;
		if (lastAttacker != null && client.getTickCount() - lastAttackerTick <= ATTACKER_TICKS)
		{
			killer = lastAttacker;
		}
		else if (local.getInteracting() != null)
		{
			killer = killerOf(local.getInteracting());
		}
		session.emit(EventType.DEATH, new DeathData(killer, location));
		lastAttacker = null;
	}

	static DeathData.Killer killerOf(Actor actor)
	{
		if (actor instanceof NPC)
		{
			NPC npc = (NPC) actor;
			String name = npc.getName() == null ? null : Text.removeTags(npc.getName());
			return new DeathData.Killer("NPC", name, npc.getId());
		}
		if (actor instanceof Player)
		{
			// Never send data about other players.
			return new DeathData.Killer("PLAYER", null, null);
		}
		return null;
	}

	@Override
	public void onSessionStart()
	{
	}

	@Override
	public void onSessionEnd()
	{
		lastAttacker = null;
	}
}
