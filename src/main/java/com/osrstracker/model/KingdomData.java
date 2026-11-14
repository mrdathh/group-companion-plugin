package com.osrstracker.model;

import java.util.Map;
import lombok.Value;

/** protocol.ts KingdomData: Kingdom of Miscellania as last seen. */
@Value
public class KingdomData
{
	/** 0-100 at the time of the snapshot. */
	int approval;
	int coffer;
	/** Resource name -> workers assigned. */
	Map<String, Integer> workers;
	boolean royalTrouble;
}
