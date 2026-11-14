package com.osrstracker.model;

import com.google.gson.annotations.JsonAdapter;
import java.util.List;
import lombok.Value;

/** protocol.ts TimersData. */
@Value
public class TimersData
{
	List<Timer> timers;

	/** protocol.ts Timer. */
	@Value
	public static class Timer
	{
		/** Stable per patch, e.g. "farming:herb:ardougne". */
		String id;
		/** "farming" or "birdhouse". */
		String kind;
		String category;
		String place;
		String produce;
		Integer itemId;
		/** EMPTY, GROWING, READY, DISEASED, DEAD or UNKNOWN. */
		String state;
		/** ISO time it is or was ready; sent as null when empty or unknown. */
		@JsonAdapter(value = WriteNull.class, nullSafe = false)
		String readyAt;
	}
}
