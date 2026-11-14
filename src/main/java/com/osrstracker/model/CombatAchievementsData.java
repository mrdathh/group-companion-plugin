package com.osrstracker.model;

import lombok.Value;

/**
 * protocol.ts CombatAchievementsData. Only {@code points} and {@code completed} are filled; the optional
 * {@code tiers} and {@code tasks} are omitted because tier/name data is not read from the cache.
 */
@Value
public class CombatAchievementsData
{
	int points;
	/** Number of tasks completed. */
	int completed;
}
