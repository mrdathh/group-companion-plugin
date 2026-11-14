package com.osrstracker;

import com.osrstracker.model.Item;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;

/**
 * Builds protocol items: canonical id, name, GE price and HA value. Client thread only.
 * Also remembers the ids of recently seen item names so a collection log chat message can carry an id.
 */
@Singleton
public class ItemMapper
{
	private static final int RECENT_NAMES = 512;

	private final ItemManager itemManager;
	private final Map<String, Integer> recentIdsByName = new LinkedHashMap<String, Integer>(64, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest)
		{
			return size() > RECENT_NAMES;
		}
	};

	@Inject
	ItemMapper(ItemManager itemManager)
	{
		this.itemManager = itemManager;
	}

	public Item toItem(int rawId, int qty)
	{
		int id = itemManager.canonicalize(rawId);
		ItemComposition comp = itemManager.getItemComposition(id);
		String name = comp.getName();
		synchronized (recentIdsByName)
		{
			recentIdsByName.put(name.toLowerCase(Locale.ROOT), id);
		}
		return new Item(id, qty, name, itemManager.getItemPrice(id), comp.getHaPrice());
	}

	/** Item name for an id as-is (no canonicalisation), e.g. for collection log ids. */
	public String nameOf(int id)
	{
		return itemManager.getItemComposition(id).getName();
	}

	/** True for bank placeholders and bank fillers, which are not real items. */
	public boolean isPlaceholder(int rawId)
	{
		if (rawId == net.runelite.api.gameval.ItemID.BANK_FILLER)
		{
			return true;
		}
		return itemManager.getItemComposition(rawId).getPlaceholderTemplateId() != -1;
	}

	/** Id of an item seen recently under this name, or null. */
	public Integer recentIdForName(String name)
	{
		synchronized (recentIdsByName)
		{
			return recentIdsByName.get(name.toLowerCase(Locale.ROOT));
		}
	}
}
