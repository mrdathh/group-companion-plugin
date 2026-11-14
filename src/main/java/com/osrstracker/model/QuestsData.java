package com.osrstracker.model;

import java.util.Map;
import lombok.Value;

/** protocol.ts QuestsData. */
@Value
public class QuestsData
{
	/** Quest name -> NOT_STARTED / IN_PROGRESS / FINISHED. */
	Map<String, String> quests;
	int questPoints;
}
