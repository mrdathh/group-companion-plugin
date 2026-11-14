package com.osrstracker.model;

import lombok.Value;

/** protocol.ts DeathData. */
@Value
public class DeathData
{
	/** Omitted when unknown. */
	Killer killer;
	Location location;

	@Value
	public static class Killer
	{
		/** NPC, PLAYER or UNKNOWN. Name and id are never set for players. */
		String type;
		String name;
		Integer id;
	}

	@Value
	public static class Location
	{
		int x;
		int y;
		int plane;
		int regionId;
	}
}
