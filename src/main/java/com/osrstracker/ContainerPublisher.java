package com.osrstracker;

import com.osrstracker.model.ContainerData;
import com.osrstracker.model.EventType;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Debounced emission of {@code container} snapshots, shared by every collector that produces one. A snapshot
 * is captured when the game shows the contents (stamped then) and emitted after the container's quiet
 * period, or at once on {@link #flush}. Only the newest capture per container name is kept.
 */
@Singleton
public class ContainerPublisher
{
	/** Upper bound so a container that changes every tick (e.g. while fletching) is still sent. */
	static final long MAX_WAIT_MS = 15_000;

	private final SessionTracker session;
	private final ScheduledExecutorService executor;
	private final TimeSource time;
	private final Map<String, Throttle> throttles = new ConcurrentHashMap<>();
	private final Map<String, Captured> pending = new ConcurrentHashMap<>();

	@Inject
	ContainerPublisher(SessionTracker session, ScheduledExecutorService executor, TimeSource time)
	{
		this.session = session;
		this.executor = executor;
		this.time = time;
	}

	/** Capture {@code data} now and emit it {@code delayMs} after the last capture of the same container. */
	public void offer(ContainerData data, long delayMs)
	{
		if (!session.isActive())
		{
			return;
		}
		String name = data.getContainer();
		pending.put(name, new Captured(data, time.now()));
		throttles.computeIfAbsent(name, n -> new Throttle(executor, time, delayMs, 0, MAX_WAIT_MS, () -> emit(n))).trigger();
	}

	/** Emit the pending capture of this container now, if any (e.g. its interface closed). */
	public void flush(String name)
	{
		Throttle t = throttles.get(name);
		if (t != null)
		{
			t.flushNow();
		}
	}

	/** Emit everything pending now (session end). */
	public void flushAll()
	{
		throttles.values().forEach(Throttle::flushNow);
		pending.clear();
	}

	/** Drop everything pending (plugin stop). */
	public void cancelAll()
	{
		throttles.values().forEach(Throttle::cancel);
		pending.clear();
	}

	private void emit(String name)
	{
		Captured c = pending.remove(name);
		if (c != null)
		{
			session.emit(EventType.CONTAINER, c.data, c.at);
		}
	}

	private static final class Captured
	{
		final ContainerData data;
		final Instant at;

		Captured(ContainerData data, Instant at)
		{
			this.data = data;
			this.at = at;
		}
	}
}
