package com.osrstracker.model;

import java.util.Map;
import lombok.Value;

/** protocol.ts KillCountsData: boss or activity display name -> count. */
@Value
public class KillCountsData
{
	Map<String, Integer> counts;
}
