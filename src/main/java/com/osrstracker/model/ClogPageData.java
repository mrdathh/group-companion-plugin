package com.osrstracker.model;

import java.util.List;
import lombok.Value;

/** protocol.ts ClogPageData: the counts at the top of one collection log page the player opened. */
@Value
public class ClogPageData
{
	/** "Bosses", "Raids", "Clues", "Minigames" or "Other". */
	String tab;
	/** The page's name, e.g. "Royal Titans". */
	String page;
	List<Count> counts;

	@Value
	public static class Count
	{
		/** The header line's label, e.g. "Royal Titans kills". */
		String label;
		int count;
	}
}
