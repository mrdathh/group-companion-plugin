package com.osrstracker;

import com.osrstracker.model.ClogPageData;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** The page -> var table and its pure helpers. */
public class ClogPageVarsTest
{
	private static IntUnaryOperator values(int... idValue)
	{
		Map<Integer, Integer> m = new HashMap<>();
		for (int i = 0; i < idValue.length; i += 2)
		{
			m.put(idValue[i], idValue[i + 1]);
		}
		return id -> m.getOrDefault(id, 0);
	}

	@Test
	public void tableIsConsistent()
	{
		Set<Integer> structs = new HashSet<>();
		int vars = 0;
		for (ClogPageVars.Page p : ClogPageVars.PAGES)
		{
			assertTrue("struct listed twice: " + p.struct, structs.add(p.struct));
			assertFalse(p.vars.isEmpty());
			Set<String> labels = new HashSet<>();
			for (ClogPageVars.Var v : p.vars)
			{
				assertTrue(v.id > 0);
				assertTrue("label twice on " + p.struct, labels.add(v.label));
				// Every label survives the page-open parser unchanged, so both paths send the same label.
				assertEquals(Collections.singletonList(new ClogPageData.Count(v.label, 1234)),
					CollectionLogCollector.parsePageCounts(Arrays.asList("Title", "Obtained: 1/2",
						v.label + ": <col=ffffff>1,234</col>")));
				vars++;
			}
		}
		assertEquals(structs.size(), ClogPageVars.BY_STRUCT.size());
		assertEquals(70, ClogPageVars.PAGES.size());
		assertEquals(83, vars);
		assertTrue(ClogPageVars.VARPS.contains(VarPlayerID.TOTAL_ROYAL_TITAN_KILLS));
		assertTrue(ClogPageVars.VARBITS.contains(VarbitID.COLLECTION_ALUFT_HARD_DELIVERIES));
		assertFalse(ClogPageVars.VARPS.contains(VarPlayerID.COLLECTION_CATEGORY_COUNT)); // the open page's var only
	}

	@Test
	public void knownPagesMapToTheirVars()
	{
		ClogPageVars.Page dks = ClogPageVars.BY_STRUCT.get(486);
		assertEquals(3, dks.vars.size());
		assertEquals(VarPlayerID.TOTAL_REX_KILLS, dks.var("Dagannoth Rex kills").id);
		assertNull(dks.var("Dagannoth Rex"));
		assertEquals(VarPlayerID.TOTAL_ROYAL_TITAN_KILLS, ClogPageVars.BY_STRUCT.get(1743).var("Royal Titan kills").id);
		ClogPageVars.Var hard = ClogPageVars.BY_STRUCT.get(521).var("Gnome restaurant hard deliveries");
		assertNotNull(hard);
		assertTrue(hard.varbit);
		// Wintertodt: only the kill count has a var ("Rewards claimed" comes from the opened page).
		assertEquals(1, ClogPageVars.BY_STRUCT.get(504).vars.size());
		assertNull(ClogPageVars.BY_STRUCT.get(5029)); // Doom of Mokhaiotl: no clear var per header line
	}

	@Test
	public void countsKeepHeaderOrderAndSkipZerosNeverAndSkippedLabels()
	{
		ClogPageVars.Page dks = ClogPageVars.BY_STRUCT.get(486);
		IntUnaryOperator varps = values(VarPlayerID.TOTAL_SUPREME_KILLS, 7, VarPlayerID.TOTAL_PRIME_KILLS, 0,
			VarPlayerID.TOTAL_REX_KILLS, 1234);
		assertEquals(Arrays.asList(new ClogPageData.Count("Dagannoth Supreme kills", 7),
				new ClogPageData.Count("Dagannoth Rex kills", 1234)),
			ClogPageVars.counts(dks, varps, values(), Collections.emptySet()));
		assertEquals(Collections.singletonList(new ClogPageData.Count("Dagannoth Rex kills", 1234)),
			ClogPageVars.counts(dks, varps, values(), Collections.singleton("Dagannoth Supreme kills")));

		// Clue vars use -1 for "never".
		ClogPageVars.Page beginner = ClogPageVars.BY_STRUCT.get(593);
		assertTrue(ClogPageVars.counts(beginner, values(VarPlayerID.COMPLETED_CLUES5, -1), values(),
			Collections.emptySet()).isEmpty());

		// Varbits are read as varbits.
		ClogPageVars.Page gnome = ClogPageVars.BY_STRUCT.get(521);
		assertEquals(Arrays.asList(new ClogPageData.Count("Gnome restaurant hard deliveries", 3),
				new ClogPageData.Count("Gnome restaurant easy deliveries", 40)),
			ClogPageVars.counts(gnome, values(VarbitID.COLLECTION_ALUFT_HARD_DELIVERIES, 999),
				values(VarbitID.COLLECTION_ALUFT_HARD_DELIVERIES, 3, VarbitID.COLLECTION_ALUFT_EASY_DELIVERIES, 40),
				Collections.emptySet()));
	}

	@Test
	public void changedComparesPerLabel()
	{
		List<ClogPageData.Count> counts = Arrays.asList(new ClogPageData.Count("A kills", 5));
		assertTrue(ClogPageVars.changed(counts, null));
		Map<String, Integer> sent = new HashMap<>();
		assertTrue(ClogPageVars.changed(counts, sent));
		sent.put("A kills", 5);
		sent.put("Rewards claimed", 9); // a label only the opened page has does not count as a change
		assertFalse(ClogPageVars.changed(counts, sent));
		sent.put("A kills", 4);
		assertTrue(ClogPageVars.changed(counts, sent));
		assertFalse(ClogPageVars.changed(Collections.emptyList(), sent));
	}
}
