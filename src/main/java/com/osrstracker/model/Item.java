package com.osrstracker.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** protocol.ts Item. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Item
{
	/** Canonical (un-noted, non-placeholder) item id. */
	private int id;
	private int qty;
	private String name;
	/** GE price per unit, 0 if untradeable or unknown. */
	private long ge;
	/** High alchemy value per unit. */
	private int ha;
}
