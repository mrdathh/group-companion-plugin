package com.osrstracker;

import com.osrstracker.model.Location;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/**
 * Real-world location of the local player, also inside instances and while on a boat (sailing world view),
 * and flagged (with the house portal's tile when known, see {@link HouseTracker}) inside a player-owned house.
 * Client thread only.
 *
 * <p>The boat handling follows the Group Ironmen Tracker plugin (christoabrown/group-ironmen-tracker,
 * BSD-2-Clause, GroupIronmenTrackerPlugin#updateThingsThatDoChangeOften): when the player's world view is
 * not the top level one, the player is on a world entity (boat) of the top level view, and the position
 * is resolved through that entity before {@link WorldPoint#fromLocalInstance}. Here the player's own tile
 * on the boat is projected onto the main world ({@link WorldEntity#transformToMainWorld}); the boat's own
 * position is the fallback.
 */
@Singleton
public class LocationReader
{
	private final Client client;
	private final HouseTracker house;

	@Inject
	LocationReader(Client client, HouseTracker house)
	{
		this.client = client;
		this.house = house;
	}

	/** Null when the player or its position is not available. */
	public Location read()
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return null;
		}
		LocalPoint local = player.getLocalLocation();
		WorldView playerView = player.getWorldView();
		WorldView top = client.getTopLevelWorldView();
		boolean onBoat = playerView != null && playerView.getId() != WorldView.TOPLEVEL;

		if (onBoat && top != null)
		{
			WorldEntity boat = top.worldEntities().byIndex(playerView.getId());
			if (boat != null)
			{
				LocalPoint onMain = local == null ? null : boat.transformToMainWorld(local);
				local = onMain != null ? onMain : boat.getLocalLocation();
			}
		}
		if (local == null)
		{
			return null;
		}
		WorldPoint wp = WorldPoint.fromLocalInstance(client, local);
		if (wp == null)
		{
			return null;
		}
		boolean instanced = top != null && top.isInstance();
		if (!onBoat && house.isHouse(wp, instanced))
		{
			// In a house the instance tile is a room template; use the portal the player came in by, if known.
			WorldPoint entrance = house.entrance();
			WorldPoint shown = entrance != null ? entrance : wp;
			return new Location(shown.getX(), shown.getY(), shown.getPlane(), shown.getRegionID(), instanced, false, true);
		}
		return new Location(wp.getX(), wp.getY(), wp.getPlane(), wp.getRegionID(), instanced, onBoat, null);
	}
}
