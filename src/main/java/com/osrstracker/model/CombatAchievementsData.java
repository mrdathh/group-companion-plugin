package com.osrstracker.model;

import java.util.List;
import java.util.Map;
import lombok.Value;

/** protocol.ts CombatAchievementsData. {@code tiers} and {@code tasks} are omitted until the task list is read. */
@Value
public class CombatAchievementsData
{
	int points;
	/** Number of tasks completed. */
	int completed;
	/** Tier name (Easy..Grandmaster) -> done/total. */
	Map<String, TierCount> tiers;
	List<Task> tasks;

	public CombatAchievementsData(int points, int completed)
	{
		this(points, completed, null, null);
	}

	public CombatAchievementsData(int points, int completed, Map<String, TierCount> tiers, List<Task> tasks)
	{
		this.points = points;
		this.completed = completed;
		this.tiers = tiers;
		this.tasks = tasks;
	}

	@Value
	public static class TierCount
	{
		int done;
		int total;
	}

	@Value
	public static class Task
	{
		int id;
		String name;
		String tier;
		/** Omitted for tasks not tied to a monster. */
		String monster;
		boolean done;
	}
}
