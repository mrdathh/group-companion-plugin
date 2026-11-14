package com.osrstracker;

import com.google.gson.Gson;
import com.osrstracker.model.IngestEvent;
import com.osrstracker.model.Item;
import com.osrstracker.model.LootHistoryData;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import net.runelite.http.api.RuneLiteAPI;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** loot_history is trimmed to the server's limits: newest sources, most valuable items, deterministic. */
public class LootHistoryLimiterTest
{
	private static final Gson GSON = RuneLiteAPI.GSON;
	private static final int NO_LIMIT = Integer.MAX_VALUE;

	private static String at(long epochSeconds)
	{
		return IngestEvent.formatTime(Instant.ofEpochSecond(epochSeconds));
	}

	private static LootHistoryData.Source source(String name, String lastAt, Item... items)
	{
		return new LootHistoryData.Source("NPC", name, 10, null, lastAt, new ArrayList<>(Arrays.asList(items)));
	}

	/** A source with {@code n} distinct cheap items. */
	private static LootHistoryData.Source source(String name, String lastAt, int n)
	{
		List<Item> items = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			items.add(new Item(1000 + i, 1, "Item " + i, 1, 1));
		}
		return new LootHistoryData.Source("NPC", name, 10, null, lastAt, items);
	}

	private static LootHistoryData data(LootHistoryData.Source... sources)
	{
		return new LootHistoryData(new ArrayList<>(Arrays.asList(sources)));
	}

	private static List<String> names(LootHistoryLimiter.Result r)
	{
		return r.getData().getSources().stream().map(LootHistoryData.Source::getName).collect(Collectors.toList());
	}

	private static List<Integer> ids(LootHistoryData.Source s)
	{
		return s.getItems().stream().map(Item::getId).collect(Collectors.toList());
	}

	@Test
	public void nothingIsTrimmedUnderTheLimits()
	{
		LootHistoryData in = data(source("Vorkath", at(2000), new Item(536, 240, "Dragon bones", 2000, 50)),
			source("Barrows", null, 30), source("Zulrah", at(1000), 400));
		LootHistoryLimiter.Result r = LootHistoryLimiter.fit(in, GSON);
		assertFalse(r.trimmed());
		assertEquals(0, r.getSourcesLeftOut());
		assertEquals(0, r.getItemsLeftOut());
		assertEquals(GSON.toJson(in), GSON.toJson(r.getData()));
		assertEquals(GSON.toJson(in).getBytes(StandardCharsets.UTF_8).length, r.getBytes());
	}

	@Test
	public void aSourceOverTheItemCapKeepsItsMostValuableItemsInTheirOrder()
	{
		LootHistoryData in = data(source("Vorkath", at(1000),
			new Item(1, 1000, "Cheap many", 1, 0),      // value 1,000
			new Item(2, 1, "Rare", 5_000_000, 0),       // value 5,000,000
			new Item(3, 10, "Mid", 2_000, 0),           // value 20,000
			new Item(4, 2, "Untradeable", 0, 900),      // value 0
			new Item(5, 100, "Also mid", 200, 0)));     // value 20,000 (tie with id 3: higher qty first)
		LootHistoryLimiter.Result r = LootHistoryLimiter.fit(in, GSON, NO_LIMIT, 3, NO_LIMIT, NO_LIMIT);
		assertEquals(Arrays.asList(2, 3, 5), ids(r.getData().getSources().get(0)));
		assertEquals(0, r.getSourcesLeftOut());
		assertEquals(2, r.getItemsLeftOut());
		assertTrue(r.trimmed());
	}

	@Test
	public void withoutPricesTheHighestQuantitiesAreKept()
	{
		LootHistoryData in = data(source("Barrows", at(1000),
			new Item(10, 5, "a", 0, 0), new Item(11, 50, "b", 0, 0), new Item(12, 7, "c", 0, 0),
			new Item(13, 50, "d", 0, 0), new Item(14, 1, "e", 0, 0)));
		LootHistoryLimiter.Result r = LootHistoryLimiter.fit(in, GSON, NO_LIMIT, 3, NO_LIMIT, NO_LIMIT);
		assertEquals(Arrays.asList(11, 12, 13), ids(r.getData().getSources().get(0)));
	}

	@Test
	public void tooManySourcesDropsTheOldestLastAtFirst()
	{
		LootHistoryData in = data(
			source("A no date", null, 1),
			source("B old", at(1000), 1),
			source("C newest", at(5000), 1),
			source("D mid", at(3000), 1),
			source("E also mid", at(3000), 1),
			source("F no date", null, 1));
		LootHistoryLimiter.Result r = LootHistoryLimiter.fit(in, GSON, 3, NO_LIMIT, NO_LIMIT, NO_LIMIT);
		// Newest three: C, then D and E (same lastAt, by name); output keeps the input order.
		assertEquals(Arrays.asList("C newest", "D mid", "E also mid"), names(r));
		assertEquals(3, r.getSourcesLeftOut());
		assertEquals(3, r.getItemsLeftOut());

		// Sources without lastAt are the first to go, by name among themselves.
		r = LootHistoryLimiter.fit(in, GSON, 5, NO_LIMIT, NO_LIMIT, NO_LIMIT);
		assertEquals(Arrays.asList("A no date", "B old", "C newest", "D mid", "E also mid"), names(r));
	}

	@Test
	public void tooManyItemsInAllDropsTheOldestSources()
	{
		LootHistoryData in = data(source("Old", at(1000), 40), source("Newer", at(2000), 40), source("Newest", at(3000), 40));
		LootHistoryLimiter.Result r = LootHistoryLimiter.fit(in, GSON, NO_LIMIT, NO_LIMIT, 100, NO_LIMIT);
		assertEquals(Arrays.asList("Newer", "Newest"), names(r));
		assertEquals(1, r.getSourcesLeftOut());
		assertEquals(40, r.getItemsLeftOut());

		// The item cap per source applies first, so more sources fit.
		r = LootHistoryLimiter.fit(in, GSON, NO_LIMIT, 30, 100, NO_LIMIT);
		assertEquals(Arrays.asList("Old", "Newer", "Newest"), names(r));
		assertEquals(30, r.getItemsLeftOut());
	}

	@Test
	public void overTheByteLimitTheOldestSourcesAreLeftOutInsteadOfSkippingAll()
	{
		LootHistoryData in = data(source("Old", at(1000), 50), source("Newer", at(2000), 50), source("Newest", at(3000), 50));
		int full = GSON.toJson(in).getBytes(StandardCharsets.UTF_8).length;
		int limit = full * 3 / 4;
		LootHistoryLimiter.Result r = LootHistoryLimiter.fit(in, GSON, NO_LIMIT, NO_LIMIT, NO_LIMIT, limit);
		assertEquals(Arrays.asList("Newer", "Newest"), names(r));
		assertTrue(r.getBytes() <= limit);
		assertEquals(GSON.toJson(r.getData()).getBytes(StandardCharsets.UTF_8).length, r.getBytes());

		// Exactly at the limit nothing is dropped (the check is "at most").
		r = LootHistoryLimiter.fit(in, GSON, NO_LIMIT, NO_LIMIT, NO_LIMIT, full);
		assertFalse(r.trimmed());
		assertEquals(full, r.getBytes());
	}

	@Test
	public void sameInputSameOutputWhateverTheOrderOfTies()
	{
		List<LootHistoryData.Source> sources = new ArrayList<>();
		for (int i = 0; i < 40; i++)
		{
			// Many equal lastAt values and equal-value items: only the tie-breaks decide.
			sources.add(source("Source " + i, i % 3 == 0 ? null : at(1000 + i % 4), 12));
		}
		LootHistoryData a = new LootHistoryData(sources);
		List<LootHistoryData.Source> reversed = new ArrayList<>(sources);
		Collections.reverse(reversed);
		LootHistoryData b = new LootHistoryData(reversed);

		LootHistoryLimiter.Result ra = LootHistoryLimiter.fit(a, GSON, 17, 5, 70, NO_LIMIT);
		LootHistoryLimiter.Result rb = LootHistoryLimiter.fit(b, GSON, 17, 5, 70, NO_LIMIT);
		assertEquals(GSON.toJson(ra.getData()), GSON.toJson(LootHistoryLimiter.fit(a, GSON, 17, 5, 70, NO_LIMIT).getData()));
		List<String> na = new ArrayList<>(names(ra));
		List<String> nb = new ArrayList<>(names(rb));
		Collections.sort(na);
		Collections.sort(nb);
		assertEquals(na, nb);
		assertEquals(14, na.size()); // 70 items / 5 per source
		assertEquals(Arrays.asList(1000, 1001, 1002, 1003, 1004), ids(ra.getData().getSources().get(0)));
	}

	@Test
	public void longNamesAreCutToTheServersMaximum()
	{
		StringBuilder longName = new StringBuilder();
		for (int i = 0; i < 300; i++)
		{
			longName.append('x');
		}
		LootHistoryData in = data(new LootHistoryData.Source("EVENT", longName.toString(), 1, null, null,
			new ArrayList<>(Collections.singletonList(new Item(1, 1, longName.toString(), 1, 1)))));
		LootHistoryData.Source out = LootHistoryLimiter.fit(in, GSON).getData().getSources().get(0);
		assertEquals(200, out.getName().length());
		assertEquals(200, out.getItems().get(0).getName().length());
		assertEquals(300, in.getSources().get(0).getName().length()); // input untouched
	}

	@Test
	public void theServersCountLimitsAreNeverExceeded()
	{
		// Count limits only (bytes unlimited here): 2,100 sources, some with 450 items.
		List<LootHistoryData.Source> sources = new ArrayList<>();
		for (int s = 0; s < 2100; s++)
		{
			sources.add(source(String.format("Source %04d", s), at(1_000_000 + s), s % 10 == 0 ? 450 : 20));
		}
		LootHistoryLimiter.Result r = LootHistoryLimiter.fit(new LootHistoryData(sources), GSON,
			LootHistoryLimiter.MAX_SOURCES, LootHistoryLimiter.MAX_ITEMS_PER_SOURCE, LootHistoryLimiter.MAX_ITEMS, NO_LIMIT);
		assertServerAccepts(r.getData());
		assertTrue(r.getData().getSources().size() <= 2000);

		// With the byte limit too, a history far over every limit still goes out, trimmed.
		r = LootHistoryLimiter.fit(new LootHistoryData(sources), GSON);
		assertServerAccepts(r.getData());
		assertTrue(r.getBytes() <= LootHistoryLimiter.MAX_BYTES);
		assertTrue(r.getData().getSources().size() > 100);
		assertEquals("Source 2099", r.getData().getSources().get(r.getData().getSources().size() - 1).getName());
	}

	/** The server's checks (server/src/ingest/schema.ts LOOT_HISTORY_LIMITS), written out. */
	private static void assertServerAccepts(LootHistoryData d)
	{
		assertTrue(d.getSources().size() <= 2000);
		int total = 0;
		for (LootHistoryData.Source s : d.getSources())
		{
			assertTrue(s.getItems().size() <= 400);
			assertTrue(s.getName().length() >= 1 && s.getName().length() <= 200);
			total += s.getItems().size();
		}
		assertTrue(total <= 50_000);
	}
}
