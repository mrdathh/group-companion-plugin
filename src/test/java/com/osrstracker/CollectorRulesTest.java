package com.osrstracker;

import java.util.EnumSet;
import java.util.regex.Matcher;
import net.runelite.api.WorldType;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Small pure rules: world exclusions, account types, chat parsing. */
public class CollectorRulesTest
{
	@Test
	public void excludedWorlds()
	{
		assertTrue(SessionTracker.isAllowedWorld(EnumSet.noneOf(WorldType.class)));
		assertTrue(SessionTracker.isAllowedWorld(EnumSet.of(WorldType.MEMBERS, WorldType.SKILL_TOTAL)));
		assertTrue(SessionTracker.isAllowedWorld(EnumSet.of(WorldType.PVP)));
		for (WorldType t : new WorldType[]{WorldType.SEASONAL, WorldType.DEADMAN, WorldType.FRESH_START_WORLD,
			WorldType.TOURNAMENT_WORLD, WorldType.BETA_WORLD, WorldType.NOSAVE_MODE, WorldType.QUEST_SPEEDRUNNING,
			WorldType.PVP_ARENA})
		{
			assertFalse(t.name(), SessionTracker.isAllowedWorld(EnumSet.of(WorldType.MEMBERS, t)));
		}
	}

	@Test
	public void accountTypeFromVarbit()
	{
		assertEquals("NORMAL", SessionTracker.accountType(0));
		assertEquals("IRONMAN", SessionTracker.accountType(1));
		assertEquals("ULTIMATE_IRONMAN", SessionTracker.accountType(2));
		assertEquals("HARDCORE_IRONMAN", SessionTracker.accountType(3));
		assertEquals("GROUP_IRONMAN", SessionTracker.accountType(4));
		assertEquals("HARDCORE_GROUP_IRONMAN", SessionTracker.accountType(5));
		assertEquals("UNRANKED_GROUP_IRONMAN", SessionTracker.accountType(6));
		assertEquals("NORMAL", SessionTracker.accountType(99));
	}

	@Test
	public void killCountMessages()
	{
		assertKc("Your Zulrah kill count is: 1,234.", "Zulrah", "1,234");
		assertKc("Your completed Chambers of Xeric count is: 12.", "Chambers of Xeric", "12");
		assertKc("Your Barrows chest count is: 50.", "Barrows", "50");
		assertKc("Your subdued Wintertodt count is: 400.", "Wintertodt", "400");
		assertKc("Your Gauntlet completion count is: 3.", "Gauntlet", "3");
	}

	private static void assertKc(String message, String name, String count)
	{
		Matcher m = LootCollector.KILL_COUNT.matcher(message);
		assertTrue(message, m.find());
		assertEquals(name, m.group(1));
		assertEquals(count, m.group(2));
	}

	@Test
	public void newCollectionLogItemMessage()
	{
		Matcher m = CollectionLogCollector.NEW_ITEM.matcher("New item added to your collection log: Pet snakeling");
		assertTrue(m.find());
		assertEquals("Pet snakeling", m.group(1));
	}

	@Test
	public void lootSourceNamesAreNormalisedForKillCountMatching()
	{
		assertEquals("gauntlet", LootCollector.normalize("The Gauntlet"));
		assertEquals("vorkath", LootCollector.normalize("<col=ff0000>Vorkath</col>"));
	}
}
