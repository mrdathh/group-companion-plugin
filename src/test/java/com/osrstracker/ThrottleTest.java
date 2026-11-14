package com.osrstracker;

import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ThrottleTest
{
	private final FakeScheduler scheduler = new FakeScheduler();
	private final AtomicInteger runs = new AtomicInteger();

	private Throttle throttle(long delay, long minInterval, long maxWait)
	{
		return new Throttle(scheduler, scheduler.time, delay, minInterval, maxWait, runs::incrementAndGet);
	}

	@Test
	public void runsAQuietPeriodAfterTheLastTrigger()
	{
		Throttle t = throttle(2_000, 0, 60_000);
		t.trigger();
		scheduler.advance(1_500);
		t.trigger();
		scheduler.advance(1_999);
		assertEquals(0, runs.get());
		scheduler.advance(1);
		assertEquals(1, runs.get());
		scheduler.advance(60_000);
		assertEquals(1, runs.get());
	}

	@Test
	public void skillsTimingThreeSecondsAfterLastChangeAtMostEveryTenSeconds()
	{
		Throttle t = throttle(SkillsCollector.DELAY_MS, SkillsCollector.MIN_INTERVAL_MS, SkillsCollector.MIN_INTERVAL_MS);
		t.trigger();
		scheduler.advance(3_000);
		assertEquals(1, runs.get());

		// Next change right away: may not run before 10 s after the previous run.
		t.trigger();
		scheduler.advance(9_999);
		assertEquals(1, runs.get());
		scheduler.advance(1);
		assertEquals(2, runs.get());
	}

	@Test
	public void constantTriggersCannotStarveIt()
	{
		Throttle t = throttle(SkillsCollector.DELAY_MS, SkillsCollector.MIN_INTERVAL_MS, SkillsCollector.MIN_INTERVAL_MS);
		for (int tick = 0; tick < 100; tick++) // 60 s of XP every game tick
		{
			t.trigger();
			scheduler.advance(600);
		}
		// First run 10 s after the first trigger, then every ~10 s (never more often).
		assertEquals(5, runs.get());
	}

	@Test
	public void flushNowRunsOnlyWhenPending()
	{
		Throttle t = throttle(5_000, 0, 30_000);
		t.flushNow();
		assertEquals(0, runs.get());
		t.trigger();
		t.flushNow();
		assertEquals(1, runs.get());
		scheduler.advance(10_000);
		assertEquals(1, runs.get());
	}

	@Test
	public void triggerInDelaysTheFirstRun()
	{
		Throttle t = throttle(QuestDiaryCollector.DELAY_MS, QuestDiaryCollector.MIN_INTERVAL_MS, QuestDiaryCollector.MIN_INTERVAL_MS);
		t.triggerIn(QuestDiaryCollector.LOGIN_SETTLE_MS);
		t.trigger();
		scheduler.advance(4_999);
		assertEquals(0, runs.get());
		scheduler.advance(1);
		assertEquals(1, runs.get());
	}

	@Test
	public void cancelDropsThePendingRun()
	{
		Throttle t = throttle(1_000, 0, 1_000);
		t.trigger();
		t.cancel();
		scheduler.advance(10_000);
		assertEquals(0, runs.get());
	}
}
