package com.osrstracker.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Value;

/** protocol.ts ContainerData. */
@Value
@AllArgsConstructor
public class ContainerData
{
	/** Lowercase snake_case container name, e.g. "inventory". */
	String container;
	List<ContainerItem> items;
	/** True when the contents were inferred rather than read from the game; null (omitted) otherwise. */
	Boolean estimated;

	/** Contents read from the game. */
	public ContainerData(String container, List<ContainerItem> items)
	{
		this(container, items, null);
	}
}
