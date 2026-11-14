package com.osrstracker.model;

import java.util.List;
import lombok.Value;

/** protocol.ts LootData. */
@Value
public class LootData
{
	Source source;
	/** Omitted when unknown. */
	Integer killCount;
	List<Item> items;
	/** Sum of qty * ge over items. */
	long totalGe;

	@Value
	public static class Source
	{
		/** NPC, EVENT, PICKPOCKET, PLAYER or UNKNOWN. */
		String type;
		String name;
		Integer id;
		Integer combatLevel;
	}
}
