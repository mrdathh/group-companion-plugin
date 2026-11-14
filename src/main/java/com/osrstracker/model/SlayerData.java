package com.osrstracker.model;

import com.google.gson.annotations.JsonAdapter;
import lombok.Value;

/** protocol.ts SlayerData. */
@Value
public class SlayerData
{
	/** Sent as null when there is no task. */
	@JsonAdapter(value = WriteNull.class, nullSafe = false)
	Task task;
	int points;
	int streak;

	@Value
	public static class Task
	{
		String name;
		int remaining;
		int initial;
		/** Omitted when the task has no assigned area. */
		String location;
	}
}
