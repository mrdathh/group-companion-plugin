package com.osrstracker;

import com.google.common.collect.ImmutableSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.eventbus.Subscribe;

/**
 * Knows when the player is inside a player-owned house, and where its way in was on the real map.
 *
 * <p>A house is an instance whose tiles map back to the game's room templates, so
 * {@link WorldPoint#fromLocalInstance} is meaningless there. Inside means: the scene is an instance and either
 * the house's exit portal object was loaded in this scene, or the tile lies in the room template area (the
 * area check comes from the Reimagined World Map plugin, Maiz19/reimagined-world-map, BSD-2-Clause,
 * HouseTracker#isTemplate).
 *
 * <p>The outside location is only known when the player walked in through a house portal: the portal objects
 * the game loaded are remembered, and if the player's last tile outside was next to one, that portal's tile is
 * used. After a teleport into the house it is unknown (there is no verified table of portal tiles per house
 * location here), and callers fall back to the template tile.
 */
@Singleton
public class HouseTracker
{
	/** Outside house portals (POH_*_PORTAL objects). */
	static final Set<Integer> PORTAL_OBJECTS = ImmutableSet.of(
		ObjectID.POH_RIMMINGTON_PORTAL, ObjectID.POH_TAVERLY_PORTAL, ObjectID.POH_POLLNIVNEACH_PORTAL,
		ObjectID.POH_RELLEKKA_PORTAL, ObjectID.POH_BRIMHAVEN_PORTAL, ObjectID.POH_YANILLE_PORTAL,
		ObjectID.POH_KOUREND_PORTAL);
	/** How close the last outside tile must be to a portal to count as entering through it. */
	static final int PORTAL_RADIUS = 4;
	private static final int MAX_PORTALS = 16;

	private final Client client;

	private final Deque<WorldPoint> portals = new ArrayDeque<>();
	private boolean exitPortalInScene;
	private WorldPoint lastOutside;
	private boolean inside;
	private WorldPoint entrance;

	@Inject
	HouseTracker(Client client)
	{
		this.client = client;
	}

	static boolean isTemplate(int x, int y)
	{
		return x >= 1856 && x <= 2047 && y >= 7040 && y <= 7119;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING)
		{
			exitPortalInScene = false;
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			reset();
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		int id = event.getGameObject().getId();
		if (id == ObjectID.POH_EXIT_PORTAL)
		{
			exitPortalInScene = true;
		}
		else if (PORTAL_OBJECTS.contains(id))
		{
			WorldPoint wp = event.getGameObject().getWorldLocation();
			portals.remove(wp);
			portals.addLast(wp);
			while (portals.size() > MAX_PORTALS)
			{
				portals.removeFirst();
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		Player player = client.getLocalPlayer();
		WorldView top = client.getTopLevelWorldView();
		LocalPoint local = player == null ? null : player.getLocalLocation();
		if (local == null || top == null || player.getWorldView() == null || player.getWorldView().getId() != WorldView.TOPLEVEL)
		{
			return; // not loaded, or on a boat
		}
		WorldPoint wp = WorldPoint.fromLocalInstance(client, local);
		update(wp, top.isInstance());
	}

	/** Visible for tests. */
	void update(WorldPoint wp, boolean instance)
	{
		boolean nowInside = isHouse(wp, instance);
		if (nowInside && !inside)
		{
			entrance = portalNear(lastOutside);
		}
		if (!nowInside)
		{
			entrance = null;
			if (!instance && wp != null)
			{
				lastOutside = wp;
			}
		}
		inside = nowInside;
	}

	boolean isHouse(WorldPoint wp, boolean instance)
	{
		return instance && wp != null && (exitPortalInScene || isTemplate(wp.getX(), wp.getY()));
	}

	/** The house portal the player walked in through, or null (not in a house, or entered by teleport). */
	public WorldPoint entrance()
	{
		return inside ? entrance : null;
	}

	private WorldPoint portalNear(WorldPoint outside)
	{
		if (outside == null)
		{
			return null;
		}
		for (WorldPoint p : portals)
		{
			if (p.getPlane() == outside.getPlane()
				&& Math.abs(p.getX() - outside.getX()) <= PORTAL_RADIUS
				&& Math.abs(p.getY() - outside.getY()) <= PORTAL_RADIUS)
			{
				return p;
			}
		}
		return null;
	}

	void reset()
	{
		portals.clear();
		exitPortalInScene = false;
		lastOutside = null;
		inside = false;
		entrance = null;
	}

	/** Visible for tests. */
	void portalSeen(WorldPoint wp)
	{
		portals.addLast(wp);
	}

	/** Visible for tests. */
	void exitPortalLoaded()
	{
		exitPortalInScene = true;
	}
}
