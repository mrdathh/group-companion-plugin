package com.osrstracker.model;

import lombok.Value;

/** protocol.ts ClogItemData. */
@Value
public class ClogItemData
{
	String name;
	/** Omitted when the id cannot be resolved from the name. */
	Integer id;
}
