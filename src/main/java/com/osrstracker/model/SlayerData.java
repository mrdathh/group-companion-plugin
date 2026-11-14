package com.osrstracker.model;

import com.google.gson.annotations.JsonAdapter;
import java.util.List;
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
	/** The master who gave the current or last task; omitted when unknown. */
	String master;
	Integer wildernessStreak;
	/** Blocked task names (current master's block list); omitted when unknown. */
	List<String> blocked;
	/** Purchased reward unlocks and extensions, by name; omitted when unknown. */
	List<String> unlocks;

	public SlayerData(Task task, int points, int streak)
	{
		this(task, points, streak, null, null, null, null);
	}

	public SlayerData(Task task, int points, int streak, String master, Integer wildernessStreak, List<String> blocked, List<String> unlocks)
	{
		this.task = task;
		this.points = points;
		this.streak = streak;
		this.master = master;
		this.wildernessStreak = wildernessStreak;
		this.blocked = blocked;
		this.unlocks = unlocks;
	}

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
