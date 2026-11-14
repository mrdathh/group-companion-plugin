package com.osrstracker;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs an action a quiet period after the last trigger, with a minimum interval between runs and a
 * maximum wait so a constant stream of triggers (e.g. XP every tick) cannot starve it.
 *
 * <p>Uses one lazily rescheduled future on the injected executor. The action runs on that executor's
 * thread; actions that read game state must hop to the client thread themselves.
 */
final class Throttle
{
	private final ScheduledExecutorService executor;
	private final TimeSource time;
	private final long delayMs;
	private final long minIntervalMs;
	private final long maxWaitMs;
	private final Runnable action;

	private long firstTrigger = -1;
	private long lastTrigger;
	private long lastRun = Long.MIN_VALUE / 4;
	private ScheduledFuture<?> future;

	Throttle(ScheduledExecutorService executor, TimeSource time, long delayMs, long minIntervalMs, long maxWaitMs, Runnable action)
	{
		this.executor = executor;
		this.time = time;
		this.delayMs = delayMs;
		this.minIntervalMs = minIntervalMs;
		this.maxWaitMs = maxWaitMs;
		this.action = action;
	}

	/** Request a run after the normal quiet period. */
	void trigger()
	{
		triggerIn(delayMs);
	}

	/** Request a run no sooner than {@code ms} from now (still subject to the minimum interval). */
	synchronized void triggerIn(long ms)
	{
		long now = time.nowMillis();
		if (firstTrigger < 0)
		{
			firstTrigger = now;
		}
		lastTrigger = Math.max(lastTrigger, now + ms - delayMs);
		if (future == null)
		{
			schedule(Math.max(0, due() - now));
		}
	}

	/** Run now if a run is pending (e.g. the bank was closed). */
	void flushNow()
	{
		synchronized (this)
		{
			if (firstTrigger < 0)
			{
				return;
			}
			cancelFuture();
			markRun();
		}
		action.run();
	}

	/** Drop any pending run. */
	synchronized void cancel()
	{
		cancelFuture();
		firstTrigger = -1;
	}

	synchronized boolean isPending()
	{
		return firstTrigger >= 0;
	}

	private long due()
	{
		long d = Math.min(lastTrigger + delayMs, firstTrigger + maxWaitMs);
		return Math.max(d, lastRun + minIntervalMs);
	}

	private void schedule(long delay)
	{
		future = executor.schedule(this::onTimer, delay, TimeUnit.MILLISECONDS);
	}

	private void onTimer()
	{
		synchronized (this)
		{
			future = null;
			if (firstTrigger < 0)
			{
				return;
			}
			long now = time.nowMillis();
			long due = due();
			if (now < due)
			{
				schedule(due - now);
				return;
			}
			markRun();
		}
		action.run();
	}

	private void markRun()
	{
		firstTrigger = -1;
		lastRun = time.nowMillis();
	}

	private void cancelFuture()
	{
		if (future != null)
		{
			future.cancel(false);
			future = null;
		}
	}
}
