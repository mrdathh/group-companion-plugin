package com.osrstracker.model;

import java.util.List;
import lombok.Value;

/** protocol.ts LoadoutsData / Loadout: saved gear setups. */
@Value
public class LoadoutsData
{
	List<Loadout> loadouts;

	@Value
	public static class Loadout
	{
		/** Stable within its source: the setup's name. */
		String id;
		String name;
		/** Always "inventory_setups". */
		String source;
		List<ContainerItem> equipment;
		List<ContainerItem> inventory;
		/** Omitted when the setup has no rune pouch. */
		List<Item> runePouch;
		/** Omitted when empty. */
		String notes;
	}
}
