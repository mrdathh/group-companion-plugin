package com.osrstracker.timers;

import com.osrstracker.model.TimersData;

/** Test access to package-private timer rules from other test packages. */
public final class TimersTestAccess
{
	private TimersTestAccess()
	{
	}

	public static TimersData.Timer tearsOfGuthix(long now, Long lastVisit)
	{
		return TimersCollector.tearsOfGuthix(now, lastVisit);
	}
}
