package com.osrstracker.model;

import java.util.List;
import java.util.Map;
import lombok.Value;

/** protocol.ts DiariesData. */
@Value
public class DiariesData
{
	/** region -> tier (Easy/Medium/Hard/Elite) -> progress. */
	Map<String, Map<String, TierProgress>> diaries;

	/** protocol.ts DiaryTierProgress. */
	@Value
	public static class TierProgress
	{
		boolean complete;
		/** Task counts; null (omitted) when the tasks are not known. */
		Integer done;
		Integer total;
		/** Every task of the tier in the in-game order; null (omitted) when not known. */
		List<Task> tasks;

		public TierProgress(boolean complete, Integer done, Integer total)
		{
			this(complete, done, total, null);
		}

		public TierProgress(boolean complete, Integer done, Integer total, List<Task> tasks)
		{
			this.complete = complete;
			this.done = done;
			this.total = total;
			this.tasks = tasks;
		}
	}

	/** protocol.ts DiaryTask. */
	@Value
	public static class Task
	{
		String name;
		boolean done;
	}
}
