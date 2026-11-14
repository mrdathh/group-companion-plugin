package com.osrstracker.model;

import java.util.Map;
import lombok.Value;

/** protocol.ts DiariesData. */
@Value
public class DiariesData
{
	/** region -> tier (Easy/Medium/Hard/Elite) -> progress. */
	Map<String, Map<String, TierProgress>> diaries;

	@Value
	public static class TierProgress
	{
		boolean complete;
		/** Task counts; left null (omitted) because they are not read. */
		Integer done;
		Integer total;
	}
}
