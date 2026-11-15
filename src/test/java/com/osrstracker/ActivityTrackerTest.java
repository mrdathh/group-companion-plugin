package com.osrstracker;

import com.osrstracker.ActivityTracker.Sample;
import com.osrstracker.model.ActivityData;
import com.osrstracker.model.IngestEvent;
import java.time.Instant;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.mock;

/** Activity signals: each field changes only on a transition, from fake idle-tick, animation and hit sequences. */
public class ActivityTrackerTest
{
	private static final long T0 = 1_763_215_200_000L; // 2025-11-15T14:00:00Z
	private static final long TICK = 600;
	private static final int TIMEOUT = 15_000; // client ticks: 5 min
	private static final WorldPoint HERE = new WorldPoint(3222, 3218, 0);

	private ActivityTracker tracker;
	private long now;
	private int idleTicks;

	@Before
	public void setUp()
	{
		tracker = new ActivityTracker(mock(Client.class), new TimeSource());
		now = T0;
		idleTicks = 0;
	}

	private static String iso(long millis)
	{
		return IngestEvent.formatTime(Instant.ofEpochMilli(millis));
	}

	/** One game tick: input idle counters advance by 30 client ticks (600 ms) unless reset by the caller. */
	private ActivityData tick(int animation, WorldPoint position, boolean interacting, boolean attackTarget)
	{
		return tick(idleTicks, idleTicks, TIMEOUT, 0, animation, position, interacting, attackTarget);
	}

	private ActivityData tick(int keyboard, int mouse, int timeout, long lastPressed, int animation, WorldPoint position,
		boolean interacting, boolean attackTarget)
	{
		ActivityData d = tracker.update(now, new Sample(keyboard, mouse, timeout, lastPressed, animation, position,
			interacting, attackTarget));
		now += TICK;
		idleTicks += 30;
		return d;
	}

	private ActivityData idleTick()
	{
		return tick(-1, HERE, false, false);
	}

	@Test
	public void inputIdleStartsAfterTenSecondsAndNeverChangesUntilInputResumes()
	{
		ActivityData d = null;
		// 10 s = 500 client ticks; the counter grows by 30 per game tick, so it reaches 510 on tick 17.
		for (int i = 0; i < 17; i++)
		{
			d = idleTick();
			assertNull("tick " + i, d.getInputIdleSince());
			assertNull(d.getLogoutAt());
		}
		long at = now;
		d = idleTick();
		long since = at - 510 * 20;
		assertEquals(iso(since), d.getInputIdleSince());
		assertEquals(iso(since + 300_000), d.getLogoutAt());

		// Still idle: identical signals, however long it lasts (the client tick count jitters, the value does not).
		for (int i = 0; i < 100; i++)
		{
			idleTicks += i % 3; // client ticks do not line up exactly with game ticks
			assertEquals(d, idleTick());
		}

		// Mouse moved: both clear on the next tick.
		ActivityData resumed = tick(idleTicks, 2, TIMEOUT, 0, -1, HERE, false, false);
		assertNull(resumed.getInputIdleSince());
		assertNull(resumed.getLogoutAt());
	}

	@Test
	public void keyboardInputAloneAlsoCountsAsInput()
	{
		idleTicks = 1_000;
		ActivityData idle = idleTick();
		assertEquals(iso(T0 - 1_000 * 20), idle.getInputIdleSince());
		ActivityData typed = tick(0, idleTicks, TIMEOUT, 0, -1, HERE, false, false);
		assertNull(typed.getInputIdleSince());
	}

	@Test
	public void theClientResettingItsMouseCounterAfterTheIdlePacketIsNotInput()
	{
		idleTicks = TIMEOUT - 600;
		ActivityData before = idleTick();
		// At the timeout the client sends its idle-logout packet and winds the mouse counter back to timeout - 500.
		ActivityData after = tick(TIMEOUT + 10, TIMEOUT - 500, TIMEOUT, 0, -1, HERE, false, false);
		assertEquals(before, after);
	}

	@Test
	public void logoutAtFollowsAChangedIdleTimeoutOnce()
	{
		idleTicks = 600;
		ActivityData d = idleTick();
		long since = T0 - 600 * 20;
		assertEquals(iso(since + 300_000), d.getLogoutAt());
		// A plugin raises the client's idle timeout to 25 min.
		ActivityData raised = tick(idleTicks, idleTicks, 75_000, 0, -1, HERE, false, false);
		assertEquals(iso(since + 1_500_000), raised.getLogoutAt());
		assertEquals(d.getInputIdleSince(), raised.getInputIdleSince());
		assertEquals(raised, tick(idleTicks, idleTicks, 75_000, 0, -1, HERE, false, false));
	}

	@Test
	public void logoutMaths()
	{
		assertEquals(1_000 + 300_000, ActivityTracker.logoutAt(1_000, 15_000, false, 0));
		// Being hit: no earlier than 20 min after the last click or key press.
		assertEquals(500 + 1_200_000, ActivityTracker.logoutAt(1_000, 15_000, true, 500));
		// ... but never earlier than the client's own timeout.
		assertEquals(1_000 + 1_500_000, ActivityTracker.logoutAt(1_000, 75_000, true, 500));
	}

	@Test
	public void beingHitWhileIdlePushesTheLogoutOutAndBackOnce()
	{
		// Last key press 100 s ago, last click 15 s ago, last mouse movement 12 s ago.
		int keyboard = 5_000;
		long lastPressed = T0 - 15_000;
		ActivityData idle = tick(keyboard, 600, TIMEOUT, lastPressed, -1, HERE, false, false);
		long since = T0 - 12_000;
		assertEquals(iso(since + 300_000), idle.getLogoutAt());

		tracker.hitOnPlayer();
		ActivityData hit = tick(keyboard += 30, 630, TIMEOUT, lastPressed, -1, HERE, false, false);
		assertTrue(hit.isInCombat());
		assertEquals(iso(lastPressed + 1_200_000), hit.getLogoutAt());
		assertEquals(idle.getInputIdleSince(), hit.getInputIdleSince());

		// Hits keep coming: no change.
		for (int i = 0; i < 20; i++)
		{
			if (i % 4 == 0)
			{
				tracker.hitOnPlayer();
			}
			assertEquals(hit, tick(keyboard += 30, 630, TIMEOUT, lastPressed, -1, HERE, false, false));
		}
	}

	@Test
	public void combatFromATargetEndsWhenTheTargetGoes()
	{
		ActivityData out = idleTick();
		assertFalse(out.isInCombat());
		assertNull(out.getCombatEndedAt()); // no combat yet this session

		ActivityData fighting = tick(422, HERE, true, true);
		assertTrue(fighting.isInCombat());
		assertNull(fighting.getCombatEndedAt());
		for (int i = 0; i < 10; i++)
		{
			assertEquals(fighting, tick(i % 2 == 0 ? 422 : -1, HERE, true, true));
		}

		long endTick = now;
		ActivityData ended = tick(-1, HERE, false, false);
		assertFalse(ended.isInCombat());
		assertEquals(iso(endTick), ended.getCombatEndedAt());
		assertEquals(ended.getCombatEndedAt(), tick(-1, HERE, false, false).getCombatEndedAt());

		ActivityData again = tick(422, HERE, true, true);
		assertTrue(again.isInCombat());
		assertNull(again.getCombatEndedAt());
	}

	@Test
	public void aHitKeepsCombatGoingForEightGameTicks()
	{
		idleTick();
		tracker.hitByPlayer();
		for (int i = 1; i < ActivityTracker.COMBAT_TICKS; i++)
		{
			assertTrue("tick " + i, idleTick().isInCombat());
		}
		long endTick = now;
		ActivityData ended = idleTick();
		assertFalse(ended.isInCombat());
		assertEquals(iso(endTick), ended.getCombatEndedAt());
	}

	@Test
	public void actionIdleAfterThreeSecondsAndShortGapsBetweenSkillingActionsDoNotCount()
	{
		ActivityData d = tick(879, HERE, false, false); // first tick: busy baseline
		for (int i = 0; i <= 20; i++)
		{
			// woodcutting with a 2-tick gap without animation every 5 ticks
			d = tick(i % 5 >= 3 ? -1 : 879, HERE, false, false);
			assertNull("tick " + i, d.getActionIdleSince());
		}
		long lastBusy = now - TICK;
		// Stops: 3 s = 5 game ticks later the signal appears, set to the last busy moment.
		for (int i = 0; i < 4; i++)
		{
			assertNull(idleTick().getActionIdleSince());
		}
		ActivityData idle = idleTick();
		assertEquals(iso(lastBusy), idle.getActionIdleSince());
		for (int i = 0; i < 50; i++)
		{
			assertEquals(idle.getActionIdleSince(), idleTick().getActionIdleSince());
		}
		// Walks a tile: busy again.
		assertNull(tick(-1, new WorldPoint(3223, 3218, 0), false, false).getActionIdleSince());
	}

	@Test
	public void interactingOrStandingInCombatIsNotActionIdle()
	{
		for (int i = 0; i < 20; i++)
		{
			assertNull(tick(-1, HERE, true, false).getActionIdleSince()); // e.g. fishing spot, no animation yet
		}
		for (int i = 0; i < 6; i++)
		{
			idleTick();
		}
		assertNotEquals(null, idleTick().getActionIdleSince());
		tracker.hitOnPlayer();
		assertNull(idleTick().getActionIdleSince());
	}

	@Test
	public void noActionIdleRightAfterLoginAndResetForgetsCombat()
	{
		for (int i = 0; i < 4; i++)
		{
			assertNull(idleTick().getActionIdleSince());
		}
		tick(422, HERE, true, true);
		assertNotEquals(null, idleTick().getCombatEndedAt());
		tracker.reset();
		ActivityData fresh = idleTick();
		assertNull(fresh.getCombatEndedAt());
		assertNull(fresh.getActionIdleSince());
	}

	@Test
	public void onlyNpcsWithAnAttackOptionAreCombatTargets()
	{
		assertFalse(ActivityTracker.isAttackable(null));
		assertFalse(ActivityTracker.isAttackable(mock(net.runelite.api.Player.class)));
		net.runelite.api.NPC banker = mock(net.runelite.api.NPC.class);
		net.runelite.api.NPCComposition bankerComp = mock(net.runelite.api.NPCComposition.class);
		org.mockito.Mockito.when(bankerComp.getActions()).thenReturn(new String[]{"Talk-to", null, "Bank", null, null});
		org.mockito.Mockito.when(banker.getComposition()).thenReturn(bankerComp);
		assertFalse(ActivityTracker.isAttackable(banker));
		net.runelite.api.NPC goblin = mock(net.runelite.api.NPC.class);
		net.runelite.api.NPCComposition goblinComp = mock(net.runelite.api.NPCComposition.class);
		org.mockito.Mockito.when(goblinComp.getActions()).thenReturn(new String[]{null, "Attack", null, null, null});
		org.mockito.Mockito.when(goblin.getComposition()).thenReturn(goblinComp);
		assertTrue(ActivityTracker.isAttackable(goblin));
	}
}
