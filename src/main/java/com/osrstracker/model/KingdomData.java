package com.osrstracker.model;

import java.util.Map;
import lombok.Value;

/** protocol.ts KingdomData: Kingdom of Miscellania as last seen. Null fields are left out of the JSON. */
@Value
public class KingdomData
{
	/** 0-100 at the time of the snapshot. */
	int approval;
	int coffer;
	/** Category -> workers assigned (Wood, Herbs/flax, Fishing, Mining, Hardwood, Farming). */
	Map<String, Integer> workers;
	boolean royalTrouble;
	/** The game's approval points, 0-127. */
	Integer approvalPoints;
	/** Resource points stored since the last collection, 0-262,143. */
	Integer resourcePoints;
	/** "mahogany", "teak" or "both". */
	String hardwood;
	/** "herbs" or "flax". */
	String herbs;
	/** "raw" or "cooked". */
	String fish;
}
