package com.osrstracker;

import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import static org.mockito.Mockito.mock;

public class HouseTrackerTest
{
	private final HouseTracker house = new HouseTracker(mock(Client.class));
	private static final WorldPoint RIMMINGTON_PORTAL = new WorldPoint(2953, 3224, 0);
	private static final WorldPoint TEMPLATE = new WorldPoint(1900, 7080, 0);

	@Test
	public void templateTileInAnInstanceIsAHouse()
	{
		assertTrue(house.isHouse(TEMPLATE, true));
		assertFalse(house.isHouse(TEMPLATE, false));
		assertFalse(house.isHouse(new WorldPoint(3222, 3218, 0), true));
		house.exitPortalLoaded();
		assertTrue("any instance with the house exit portal loaded", house.isHouse(new WorldPoint(3222, 3218, 0), true));
	}

	@Test
	public void walkingInThroughAPortalGivesThePortalTile()
	{
		house.portalSeen(RIMMINGTON_PORTAL);
		house.update(new WorldPoint(2954, 3222, 0), false);
		house.update(TEMPLATE, true);
		assertEquals(RIMMINGTON_PORTAL, house.entrance());

		house.update(new WorldPoint(2954, 3222, 0), false);
		assertNull("left the house", house.entrance());
	}

	@Test
	public void teleportingInLeavesTheEntranceUnknown()
	{
		house.portalSeen(RIMMINGTON_PORTAL);
		house.update(new WorldPoint(3213, 3424, 0), false); // Varrock
		house.update(TEMPLATE, true);
		assertNull(house.entrance());
	}
}
