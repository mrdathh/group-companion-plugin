package com.osrstracker.model;

import java.util.Map;
import lombok.Value;

/** protocol.ts SkillsData. */
@Value
public class SkillsData
{
	/** Keyed by Skill.getName(); level is the real, unboosted level. */
	Map<String, SkillValue> skills;
	int totalLevel;
	long totalXp;
	int combatLevel;

	@Value
	public static class SkillValue
	{
		int xp;
		int level;
	}
}
