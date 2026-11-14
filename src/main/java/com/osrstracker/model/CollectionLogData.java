package com.osrstracker.model;

import java.util.Map;
import lombok.Value;

/** protocol.ts CollectionLogData. */
@Value
public class CollectionLogData
{
	/** itemId (as string) -> quantity obtained. */
	Map<String, Integer> items;
	/** itemId (as string) -> item name, for the ids in items. */
	Map<String, String> names;
	int obtained;
	int total;
}
