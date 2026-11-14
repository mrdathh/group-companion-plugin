package com.osrstracker.model;

import lombok.Value;

/** protocol.ts Location: real-world tile, also inside instances and on boats. */
@Value
public class Location
{
	int x;
	int y;
	int plane;
	int regionId;
	boolean instanced;
	boolean onBoat;
	/** True inside a player-owned house (x/y are then the portal's tile when known); omitted otherwise. */
	Boolean house;
}
