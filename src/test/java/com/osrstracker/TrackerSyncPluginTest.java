package com.osrstracker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Launches RuneLite in developer mode with this plugin loaded: {@code ./gradlew run}. */
public class TrackerSyncPluginTest
{
	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(TrackerSyncPlugin.class);
		RuneLite.main(args);
	}
}
