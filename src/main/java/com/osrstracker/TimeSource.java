package com.osrstracker;

import java.time.Instant;
import java.util.function.LongSupplier;
import javax.inject.Singleton;

/** Wall clock, replaceable in tests so timers and timestamps can be driven by a fake clock. */
@Singleton
public class TimeSource
{
	private volatile LongSupplier millis = System::currentTimeMillis;

	public long nowMillis()
	{
		return millis.getAsLong();
	}

	public Instant now()
	{
		return Instant.ofEpochMilli(nowMillis());
	}

	void setMillisSupplier(LongSupplier supplier)
	{
		this.millis = supplier;
	}
}
