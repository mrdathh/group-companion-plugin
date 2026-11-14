package com.osrstracker.model;

import com.google.gson.annotations.JsonAdapter;
import java.util.List;
import lombok.Value;

/**
 * protocol.ts CollectionLogSummaryData: the collection log counts the game sends at login (readable without
 * opening the log).
 */
@Value
public class CollectionLogSummaryData
{
	int obtained;
	int total;
	/** Per tab; tabs whose total is not known (0) are left out. */
	List<Tab> tabs;
	/** The log's "recent items", newest first as the game lists them; empty slots left out. */
	List<Recent> recent;
	/** The game setting "Collection log - New addition notification"; sent as null when unknown. */
	@JsonAdapter(value = WriteNull.class, nullSafe = false)
	String newItemNotification;

	@Value
	public static class Tab
	{
		/** Bosses, Raids, Clues, Minigames or Other. */
		String tab;
		int obtained;
		int total;
	}

	@Value
	public static class Recent
	{
		int itemId;
		/** ISO-8601 UTC, 00:00 of the game day the item was added (day precision); omitted when unknown. */
		String at;
	}
}
