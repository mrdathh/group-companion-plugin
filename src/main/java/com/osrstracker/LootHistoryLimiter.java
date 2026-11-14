package com.osrstracker;

import com.google.gson.Gson;
import com.osrstracker.model.Item;
import com.osrstracker.model.LootHistoryData;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import lombok.Value;

/**
 * Makes a {@code loot_history} snapshot fit the server's limits (docs/PROTOCOL.md "Loot history"), keeping as much as
 * possible. Pure and deterministic: the same input always gives the same output, so the sender's unchanged-snapshot
 * check keeps working.
 *
 * <ol>
 *   <li>Every name is cut to {@link #MAX_NAME} characters.</li>
 *   <li>A source with more than {@link #MAX_ITEMS_PER_SOURCE} items keeps the most valuable ones (qty × GE price;
 *   then the highest quantity, which is what decides when no prices are known; then the lowest id).</li>
 *   <li>Sources are ranked newest first ({@code lastAt}; sources without one count as oldest; ties by name, then
 *   type) and kept in that order while they fit {@link #MAX_SOURCES}, {@link #MAX_ITEMS} and {@link #MAX_BYTES};
 *   from the first one that does not fit, that source and every older one are left out.</li>
 * </ol>
 * The kept sources stay in their input order.
 */
final class LootHistoryLimiter
{
	/** Server: {@code sources} array {@code .max(2000)} (inclusive). */
	static final int MAX_SOURCES = 2000;
	/** Server: per-source {@code items} array {@code .max(400)} (inclusive). */
	static final int MAX_ITEMS_PER_SOURCE = 400;
	/** Server: items over all sources {@code <= 50000}. */
	static final int MAX_ITEMS = 50_000;
	/** Server: source name and item name at most 200 characters (UTF-16 units, as Java and JavaScript count). */
	static final int MAX_NAME = 200;
	/**
	 * Serialized {@code data} at most this many bytes. The request carrying it holds only this event (see
	 * {@link com.osrstracker.model.EventType#isSolo()}); its envelope adds well under 1 KB, so the body stays far
	 * below the server's 2 MiB (2,097,152 byte) limit.
	 */
	static final int MAX_BYTES = 1_500_000;

	/** {@code {"sources":[]}} */
	private static final int WRAPPER_BYTES = "{\"sources\":[]}".length();

	@Value
	static class Result
	{
		LootHistoryData data;
		int sourcesBefore;
		int itemsBefore;
		int sourcesLeftOut;
		int itemsLeftOut;
		/** Serialized size of {@link #data}. */
		int bytes;

		boolean trimmed()
		{
			return sourcesLeftOut > 0 || itemsLeftOut > 0;
		}
	}

	private LootHistoryLimiter()
	{
	}

	static Result fit(LootHistoryData in, Gson gson)
	{
		return fit(in, gson, MAX_SOURCES, MAX_ITEMS_PER_SOURCE, MAX_ITEMS, MAX_BYTES);
	}

	/** With explicit limits; visible for tests. */
	static Result fit(LootHistoryData in, Gson gson, int maxSources, int maxItemsPerSource, int maxItems, int maxBytes)
	{
		int itemsBefore = 0;
		List<LootHistoryData.Source> capped = new ArrayList<>(in.getSources().size());
		for (LootHistoryData.Source s : in.getSources())
		{
			itemsBefore += s.getItems().size();
			capped.add(capSource(s, maxItemsPerSource));
		}

		List<LootHistoryData.Source> ranked = new ArrayList<>(capped);
		ranked.sort(NEWEST_FIRST);
		Set<LootHistoryData.Source> kept = Collections.newSetFromMap(new IdentityHashMap<>());
		int items = 0;
		long bytes = WRAPPER_BYTES;
		for (LootHistoryData.Source s : ranked)
		{
			int size = gson.toJson(s).getBytes(StandardCharsets.UTF_8).length + (kept.isEmpty() ? 0 : 1);
			if (kept.size() + 1 > maxSources || items + s.getItems().size() > maxItems || bytes + size > maxBytes)
			{
				break;
			}
			kept.add(s);
			items += s.getItems().size();
			bytes += size;
		}

		List<LootHistoryData.Source> rankedKept = new ArrayList<>();
		for (LootHistoryData.Source s : ranked)
		{
			if (kept.contains(s))
			{
				rankedKept.add(s);
			}
		}
		// The per-source estimate matches Gson's compact output; checked anyway, dropping the oldest kept if needed.
		while (true)
		{
			LootHistoryData out = new LootHistoryData(inInputOrder(capped, rankedKept));
			int actual = gson.toJson(out).getBytes(StandardCharsets.UTF_8).length;
			if (actual <= maxBytes || rankedKept.isEmpty())
			{
				int itemsAfter = 0;
				for (LootHistoryData.Source s : out.getSources())
				{
					itemsAfter += s.getItems().size();
				}
				return new Result(out, in.getSources().size(), itemsBefore, in.getSources().size() - out.getSources().size(),
					itemsBefore - itemsAfter, actual);
			}
			rankedKept.remove(rankedKept.size() - 1);
		}
	}

	private static List<LootHistoryData.Source> inInputOrder(List<LootHistoryData.Source> all, List<LootHistoryData.Source> keep)
	{
		Set<LootHistoryData.Source> set = Collections.newSetFromMap(new IdentityHashMap<>());
		set.addAll(keep);
		List<LootHistoryData.Source> out = new ArrayList<>(keep.size());
		for (LootHistoryData.Source s : all)
		{
			if (set.contains(s))
			{
				out.add(s);
			}
		}
		return out;
	}

	/** A copy with names cut to size and at most {@code max} items (the most valuable, in their original order). */
	private static LootHistoryData.Source capSource(LootHistoryData.Source s, int max)
	{
		List<Item> items = s.getItems();
		if (items.size() > max)
		{
			List<Item> best = new ArrayList<>(items);
			best.sort(MOST_VALUABLE_FIRST);
			Set<Item> keep = Collections.newSetFromMap(new IdentityHashMap<>());
			keep.addAll(best.subList(0, max));
			List<Item> out = new ArrayList<>(max);
			for (Item it : items)
			{
				if (keep.contains(it))
				{
					out.add(it);
				}
			}
			items = out;
		}
		List<Item> named = new ArrayList<>(items.size());
		for (Item it : items)
		{
			named.add(it.getName() != null && it.getName().length() > MAX_NAME
				? new Item(it.getId(), it.getQty(), clip(it.getName()), it.getGe(), it.getHa()) : it);
		}
		return new LootHistoryData.Source(s.getType(), clip(s.getName()), s.getKills(), s.getFirstAt(), s.getLastAt(), named);
	}

	private static String clip(String s)
	{
		if (s == null || s.length() <= MAX_NAME)
		{
			return s;
		}
		// Do not split a surrogate pair.
		int end = Character.isHighSurrogate(s.charAt(MAX_NAME - 1)) ? MAX_NAME - 1 : MAX_NAME;
		return s.substring(0, end);
	}

	static final Comparator<Item> MOST_VALUABLE_FIRST = Comparator
		.comparingLong((Item i) -> (long) i.getQty() * i.getGe()).reversed()
		.thenComparing(Comparator.comparingInt(Item::getQty).reversed())
		.thenComparingInt(Item::getId);

	static final Comparator<LootHistoryData.Source> NEWEST_FIRST = Comparator
		.comparing((LootHistoryData.Source s) -> instant(s.getLastAt()), Comparator.nullsLast(Comparator.reverseOrder()))
		.thenComparing(LootHistoryData.Source::getName, Comparator.nullsLast(Comparator.naturalOrder()))
		.thenComparing(LootHistoryData.Source::getType, Comparator.nullsLast(Comparator.naturalOrder()));

	private static Instant instant(String iso)
	{
		if (iso == null)
		{
			return null;
		}
		try
		{
			return Instant.parse(iso);
		}
		catch (DateTimeParseException e)
		{
			return null;
		}
	}
}
