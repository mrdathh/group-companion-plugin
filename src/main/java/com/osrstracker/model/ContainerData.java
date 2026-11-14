package com.osrstracker.model;

import java.util.List;
import lombok.Value;

/** protocol.ts ContainerData. */
@Value
public class ContainerData
{
	/** Lowercase snake_case container name, e.g. "inventory". */
	String container;
	List<ContainerItem> items;
}
