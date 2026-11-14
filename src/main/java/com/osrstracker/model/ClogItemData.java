package com.osrstracker.model;

import lombok.Value;

/** protocol.ts ClogItemData. */
@Value
public class ClogItemData
{
	/** Always sent; the server resolves the item from it when {@code id} is missing. */
	String name;
	/** Omitted when the id cannot be resolved from the name (only items seen recently in loot or containers). */
	Integer id;
}
