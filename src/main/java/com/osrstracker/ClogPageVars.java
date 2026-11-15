package com.osrstracker;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.osrstracker.model.ClogPageData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * Collection log page kill counts that the client already holds in player vars, so they can be read without the
 * player opening the page.
 *
 * <p>Where the page header's counts come from (game cache scripts, rev 242): the page-draw proc
 * {@code collection_draw_log} (2732) asks {@code collection_category_count} (2735) for up to four header lines. That
 * proc switches on the page struct and formats the generic vars {@code %collection_category_count},
 * {@code ..._count2} and {@code ..._count3} (varps 2048, 2941, 2942), which the server fills only for the page being
 * opened; they are useless for the other pages. The same totals are kept per boss in dedicated player vars
 * ({@code total_*_kills}, {@code total_completed_*}, {@code completed_clues*}) that other client scripts read at any
 * time (combat achievements' boss kill counts {@code ca_specific_killcount} 4778 and {@code ca_total_killcount_boss}
 * 4776, the gravestone fee {@code script1797}, the clue stats {@code script10298}), so the server keeps them in sync
 * with the client. The Gnome Restaurant page reads its own varbits directly.
 *
 * <p>Each page below lists its header labels exactly as {@code collection_category_count} writes them (so the
 * events match what the page-open read in {@link CollectionLogCollector#readPage()} sends) and the var holding that
 * count. Header lines with no known var (e.g. "Rewards claimed" on Wintertodt, Last Man Standing, Guardians of the
 * Rift) are not listed; they still come from the page-open read.
 */
final class ClogPageVars
{
	/** One header count and the var it is read from: a varp, or a varbit when {@code varbit} is set. */
	static final class Var
	{
		final String label;
		final int id;
		final boolean varbit;

		Var(String label, int id, boolean varbit)
		{
			this.label = label;
			this.id = id;
			this.varbit = varbit;
		}
	}

	/** A collection log page (its cache struct, as in the tab's page enum) and its countable header lines. */
	static final class Page
	{
		final int struct;
		final List<Var> vars;

		Page(int struct, List<Var> vars)
		{
			this.struct = struct;
			this.vars = vars;
		}

		/** The var behind a header label, or null. */
		Var var(String label)
		{
			for (Var v : vars)
			{
				if (v.label.equals(label))
				{
					return v;
				}
			}
			return null;
		}
	}

	static final List<Page> PAGES = ImmutableList.of(
		// Bosses
		page(476, varp("Abyssal Sire kills", VarPlayerID.TOTAL_ABYSSALSIRE_KILLS)),
		page(995, varp("Araxxor kills", VarPlayerID.TOTAL_ARAXXOR_KILLS)),
		page(1016, varp("Amoxliatl kills", VarPlayerID.TOTAL_AMOXLIATL_KILLS)),
		page(539, varp("Alchemical Hydra kills", VarPlayerID.TOTAL_HYDRABOSS_KILLS)),
		page(477, varp("Barrows Chests opened", VarPlayerID.TOTAL_BARROWS_CHESTS)),
		page(6413, varp("Brutus kills", VarPlayerID.TOTAL_COWBOSS_KILLS)),
		page(478, varp("Bryophyta kills", VarPlayerID.TOTAL_BRYOPHYTA_KILLS)),
		page(479, varp("Artio kills", VarPlayerID.TOTAL_ARTIO_KILLS),
			varp("Callisto kills", VarPlayerID.TOTAL_CALLISTO_KILLS)),
		page(480, varp("Cerberus kills", VarPlayerID.TOTAL_CERBERUS_KILLS)),
		page(481, varp("Chaos Elemental kills", VarPlayerID.TOTAL_CHAOSELE_KILLS)),
		page(482, varp("Chaos Fanatic kills", VarPlayerID.TOTAL_CHAOSFANATIC_KILLS)),
		page(483, varp("Commander Zilyana kills", VarPlayerID.TOTAL_SARADOMIN_KILLS)),
		page(484, varp("Corporeal Beast kills", VarPlayerID.TOTAL_CORP_KILLS)),
		page(485, varp("Crazy Archaeologist kills", VarPlayerID.TOTAL_CRAZYARCHAEOLOGIST_KILLS)),
		page(486, varp("Dagannoth Supreme kills", VarPlayerID.TOTAL_SUPREME_KILLS),
			varp("Dagannoth Prime kills", VarPlayerID.TOTAL_PRIME_KILLS),
			varp("Dagannoth Rex kills", VarPlayerID.TOTAL_REX_KILLS)),
		page(4867, varp("Deranged Archaeologist kills", VarPlayerID.TOTAL_DERANGEDARCHAEOLOGIST_KILLS)),
		page(4652, varp("Duke Sucellus kills", VarPlayerID.TOTAL_DUKE_SUCELLUS_KILLS)),
		page(487, varp("General Graardor kills", VarPlayerID.TOTAL_BANDOS_KILLS)),
		page(488, varp("Giant Mole kills", VarPlayerID.TOTAL_MOLE_KILLS)),
		page(489, varp("Grotesque Guardian kills", VarPlayerID.TOTAL_GARGBOSS_KILLS)),
		page(541, varp("Hespori kills", VarPlayerID.TOTAL_HESPORI_KILLS)),
		page(490, varp("Kalphite Queen kills", VarPlayerID.TOTAL_KALPHITE_KILLS)),
		page(491, varp("King Black Dragon kills", VarPlayerID.TOTAL_KBD_KILLS)),
		page(492, varp("Kraken kills", VarPlayerID.TOTAL_KRAKEN_BOSS_KILLS)),
		page(493, varp("Kree'arra kills", VarPlayerID.TOTAL_ARMADYL_KILLS)),
		page(494, varp("K'ril Tsutsaroth kills", VarPlayerID.TOTAL_ZAMORAK_KILLS)),
		page(1189, varp("Maggot King kills", VarPlayerID.TOTAL_MAGGOT_KING_KILLS)),
		page(1203, varp("Mad Angel kills", VarPlayerID.TOTAL_MAD_ANGEL_KILLS)),
		page(3769, varp("Nex kills", VarPlayerID.TOTAL_NEX_KILLS)),
		page(1263, varp("Nightmare kills", VarPlayerID.TOTAL_NIGHTMARE_KILLS),
			varp("Phosani's Nightmare kills", VarPlayerID.TOTAL_NIGHTMARE_CHALLENGE_KILLS)),
		page(495, varp("Obor kills", VarPlayerID.TOTAL_HILLGIANT_BOSS_KILLS)),
		page(4455, varp("Phantom Muspah kills", VarPlayerID.TOTAL_MUSPAH_KILLS)),
		page(1743, varp("Royal Titan kills", VarPlayerID.TOTAL_ROYAL_TITAN_KILLS)),
		page(601, varp("Sarachnis kills", VarPlayerID.TOTAL_SARACHNIS_KILLS)),
		page(496, varp("Scorpia kills", VarPlayerID.TOTAL_SCORPIA_KILLS)),
		page(777, varp("Scurrius kills", VarPlayerID.TOTAL_RAT_BOSS_KILLS)),
		page(6337, varp("Shellbane Gryphon kills", VarPlayerID.TOTAL_GRYPHON_BOSS_KILLS)),
		page(497, varp("Skotizo kills", VarPlayerID.TOTAL_CATA_BOSS_KILLS)),
		page(2867, varp("Tempoross kills", VarPlayerID.TOTAL_TEMPOROSS_KILLS)),
		page(605, varp("Corrupted Gauntlet completion count", VarPlayerID.TOTAL_COMPLETED_GAUNTLET_HM),
			varp("Gauntlet completion count", VarPlayerID.TOTAL_COMPLETED_GAUNTLET)),
		page(1015, varp("Hueycoatl kills", VarPlayerID.TOTAL_HUEY_KILLS)),
		page(4655, varp("Leviathan kills", VarPlayerID.TOTAL_LEVIATHAN_KILLS)),
		page(4654, varp("Whisperer kills", VarPlayerID.TOTAL_WHISPERER_KILLS)),
		page(498, varp("Thermonuclear Smoke Devil kills", VarPlayerID.TOTAL_THERMY_KILLS)),
		page(500, varp("TzTok-Jad kills", VarPlayerID.TOTAL_JAD_KILLS)),
		page(499, varp("TzKal-Zuk kills", VarPlayerID.TOTAL_ZUK_KILLS)),
		page(909, varp("Sol Heredit kills", VarPlayerID.TOTAL_SOL_KILLS)),
		page(4653, varp("Vardorvis kills", VarPlayerID.TOTAL_VARDORVIS_KILLS)),
		page(501, varp("Spindel kills", VarPlayerID.TOTAL_SPINDEL_KILLS),
			varp("Venenatis kills", VarPlayerID.TOTAL_VENENATIS_KILLS)),
		page(502, varp("Calvar'ion kills", VarPlayerID.TOTAL_CALVARION_KILLS),
			varp("Vet'ion kills", VarPlayerID.TOTAL_VETION_KILLS)),
		page(503, varp("Vorkath kills", VarPlayerID.TOTAL_VORKATH_KILLS)),
		page(504, varp("Wintertodt kills", VarPlayerID.TOTAL_WINTERTODT_KILLS)),
		page(4736, varp("Yama kills", VarPlayerID.TOTAL_YAMA_KILLS)),
		page(604, varp("Zalcano kills", VarPlayerID.TOTAL_ZALCANO_KILLS)),
		page(505, varp("Zulrah kills", VarPlayerID.TOTAL_SNAKEBOSS_KILLS)),
		page(910, varp("Lunar Chests opened", VarPlayerID.TOTAL_PMOON_CHESTS)),
		// Raids
		page(507, varp("Chambers of Xeric (CM) completions", VarPlayerID.TOTAL_COMPLETED_XERICCHAMBERS_CHALLENGE),
			varp("Chambers of Xeric completions", VarPlayerID.TOTAL_COMPLETED_XERICCHAMBERS)),
		page(506, varp("Theatre of Blood (Hard) completions", VarPlayerID.TOTAL_COMPLETED_THEATREOFBLOOD_HARD),
			varp("Theatre of Blood (Entry) completions", VarPlayerID.TOTAL_COMPLETED_THEATREOFBLOOD_STORY),
			varp("Theatre of Blood completions", VarPlayerID.TOTAL_COMPLETED_THEATREOFBLOOD)),
		page(4378, varp("Tombs of Amascut (Expert) completions", VarPlayerID.TOTAL_COMPLETED_TOMBSOFAMASCUT_EXPERT),
			varp("Tombs of Amascut (Entry) completions", VarPlayerID.TOTAL_COMPLETED_TOMBSOFAMASCUT_ENTRY),
			varp("Tombs of Amascut completions", VarPlayerID.TOTAL_COMPLETED_TOMBSOFAMASCUT)),
		page(1220, varp("Fractured Archive completions", VarPlayerID.TOTAL_COMPLETED_THEFRACTUREDARCHIVE)),
		// Clues (per tier as the clue stats script reads them; the rare pages show the same tier's count)
		page(593, varp("Beginner clues completed", VarPlayerID.COMPLETED_CLUES5)),
		page(508, varp("Easy clues completed", VarPlayerID.COMPLETED_CLUES)),
		page(509, varp("Medium clues completed", VarPlayerID.COMPLETED_CLUES1)),
		page(510, varp("Hard clues completed", VarPlayerID.COMPLETED_CLUES2)),
		page(511, varp("Elite clues completed", VarPlayerID.COMPLETED_CLUES3)),
		page(512, varp("Master clues completed", VarPlayerID.COMPLETED_CLUES4)),
		page(2869, varp("Hard clues completed", VarPlayerID.COMPLETED_CLUES2)),
		page(2870, varp("Elite clues completed", VarPlayerID.COMPLETED_CLUES3)),
		page(2871, varp("Master clues completed", VarPlayerID.COMPLETED_CLUES4)),
		// Minigames
		page(521, varbit("Gnome restaurant hard deliveries", VarbitID.COLLECTION_ALUFT_HARD_DELIVERIES),
			varbit("Gnome restaurant easy deliveries", VarbitID.COLLECTION_ALUFT_EASY_DELIVERIES))
	);

	static final Map<Integer, Page> BY_STRUCT;
	static final Set<Integer> VARPS;
	static final Set<Integer> VARBITS;

	static
	{
		ImmutableMap.Builder<Integer, Page> byStruct = ImmutableMap.builder();
		ImmutableSet.Builder<Integer> varps = ImmutableSet.builder();
		ImmutableSet.Builder<Integer> varbits = ImmutableSet.builder();
		for (Page p : PAGES)
		{
			byStruct.put(p.struct, p);
			for (Var v : p.vars)
			{
				(v.varbit ? varbits : varps).add(v.id);
			}
		}
		BY_STRUCT = byStruct.build();
		VARPS = varps.build();
		VARBITS = varbits.build();
	}

	private ClogPageVars()
	{
	}

	private static Page page(int struct, Var... vars)
	{
		return new Page(struct, ImmutableList.copyOf(vars));
	}

	private static Var varp(String label, int id)
	{
		return new Var(label, id, false);
	}

	private static Var varbit(String label, int id)
	{
		return new Var(label, id, true);
	}

	/** Reads a var's value. */
	static int read(Var v, IntUnaryOperator varps, IntUnaryOperator varbits)
	{
		return v.varbit ? varbits.applyAsInt(v.id) : varps.applyAsInt(v.id);
	}

	/**
	 * A page's counts from the vars, in header order: only counts above 0 (0 is the same as "not loaded yet" and
	 * tells the server nothing; -1 is the clue vars' "never") and only labels not in {@code skip}.
	 */
	static List<ClogPageData.Count> counts(Page page, IntUnaryOperator varps, IntUnaryOperator varbits, Set<String> skip)
	{
		List<ClogPageData.Count> out = new ArrayList<>();
		for (Var v : page.vars)
		{
			int value = read(v, varps, varbits);
			if (value > 0 && !skip.contains(v.label))
			{
				out.add(new ClogPageData.Count(v.label, value));
			}
		}
		return out.isEmpty() ? Collections.emptyList() : out;
	}

	/** True when any of {@code counts} differs from what was last sent for its label. */
	static boolean changed(List<ClogPageData.Count> counts, Map<String, Integer> sent)
	{
		for (ClogPageData.Count c : counts)
		{
			Integer before = sent == null ? null : sent.get(c.getLabel());
			if (before == null || before != c.getCount())
			{
				return true;
			}
		}
		return false;
	}
}
