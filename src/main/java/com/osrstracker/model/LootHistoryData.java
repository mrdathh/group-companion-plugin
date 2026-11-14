package com.osrstracker.model;

import java.util.List;
import lombok.Value;

/** protocol.ts LootHistoryData: loot RuneLite's Loot Tracker has recorded, per source. */
@Value
public class LootHistoryData
{
	List<Source> sources;

	@Value
	public static class Source
	{
		/** NPC, EVENT, PICKPOCKET, PLAYER or UNKNOWN. */
		String type;
		String name;
		/** Kills, openings or pickpockets RuneLite counted. */
		int kills;
		/** ISO times; null (omitted) when not stored. */
		String firstAt;
		String lastAt;
		/** Totals per item with current prices. */
		List<Item> items;
	}
}
