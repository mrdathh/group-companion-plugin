package com.osrstracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Single-threaded fake executor driven by a fake clock: tasks run only inside {@link #advance}. */
class FakeScheduler extends AbstractExecutorService implements ScheduledExecutorService
{
	private long now = 1_700_000_000_000L;
	private long seq;
	private final List<Task> tasks = new ArrayList<>();

	final TimeSource time = new TimeSource();

	FakeScheduler()
	{
		time.setMillisSupplier(() -> now);
	}

	long now()
	{
		return now;
	}

	/** Move the clock forward, running every task that falls due on the way, in order. */
	void advance(long ms)
	{
		long target = now + ms;
		while (true)
		{
			Task next = null;
			synchronized (this)
			{
				for (Task t : tasks)
				{
					if (!t.cancelled && t.due <= target && (next == null || t.due < next.due || (t.due == next.due && t.seq < next.seq)))
					{
						next = t;
					}
				}
				if (next == null)
				{
					break;
				}
				tasks.remove(next);
				now = Math.max(now, next.due);
			}
			next.done = true;
			next.runnable.run();
		}
		now = target;
	}

	synchronized int pendingTasks()
	{
		int n = 0;
		for (Task t : tasks)
		{
			if (!t.cancelled)
			{
				n++;
			}
		}
		return n;
	}

	@Override
	public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit)
	{
		Task t = new Task(command, now + unit.toMillis(delay), seq++);
		tasks.add(t);
		return t;
	}

	@Override
	public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public void execute(Runnable command)
	{
		schedule(command, 0, TimeUnit.MILLISECONDS);
	}

	@Override
	public void shutdown()
	{
	}

	@Override
	public List<Runnable> shutdownNow()
	{
		return Collections.emptyList();
	}

	@Override
	public boolean isShutdown()
	{
		return false;
	}

	@Override
	public boolean isTerminated()
	{
		return false;
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit)
	{
		return true;
	}

	private final class Task implements ScheduledFuture<Object>
	{
		final Runnable runnable;
		final long due;
		final long seq;
		volatile boolean cancelled;
		volatile boolean done;

		Task(Runnable runnable, long due, long seq)
		{
			this.runnable = runnable;
			this.due = due;
			this.seq = seq;
		}

		@Override
		public long getDelay(TimeUnit unit)
		{
			return unit.convert(due - now, TimeUnit.MILLISECONDS);
		}

		@Override
		public int compareTo(Delayed o)
		{
			return Long.compare(getDelay(TimeUnit.MILLISECONDS), o.getDelay(TimeUnit.MILLISECONDS));
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning)
		{
			if (done)
			{
				return false;
			}
			cancelled = true;
			return true;
		}

		@Override
		public boolean isCancelled()
		{
			return cancelled;
		}

		@Override
		public boolean isDone()
		{
			return done || cancelled;
		}

		@Override
		public Object get()
		{
			return null;
		}

		@Override
		public Object get(long timeout, TimeUnit unit)
		{
			return null;
		}
	}
}
