package com.osrstracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(TrackerSyncConfig.GROUP)
public interface TrackerSyncConfig extends Config
{
	String GROUP = "trackersync";
	String CLIENT_ID_KEY = "clientId";
	String CONSENT_KEY = "consent";
	/** Hidden: the host the consent was given for. */
	String CONSENT_HOST_KEY = "consentHost";

	@ConfigSection(
		name = "Server (data leaves RuneLite)",
		description = "Everything this plugin collects is sent to the server URL you enter below, which is not"
			+ " controlled or verified by RuneLite developers. That server sees your IP address and your game data."
			+ " Nothing is sent until the URL and the token are set and you tick \"Allow sending to this server\".",
		position = 0
	)
	String serverSection = "server";

	@ConfigSection(
		name = "Data to send",
		description = "Which groups of game data are sent to your server",
		position = 1
	)
	String dataSection = "data";

	@ConfigItem(
		keyName = "serverUrl",
		name = "Server URL",
		description = "Base URL of your relay server, e.g. http://127.0.0.1:8787. Game data is sent here."
			+ " Use https for anything that is not on this PC.",
		section = serverSection,
		position = 0
	)
	default String serverUrl()
	{
		return "http://127.0.0.1:8787";
	}

	@ConfigItem(
		keyName = "token",
		name = "Token",
		description = "The osrs_... token printed by your relay server when your user was created",
		secret = true,
		section = serverSection,
		position = 1
	)
	default String token()
	{
		return "";
	}

	@ConfigItem(
		keyName = CONSENT_KEY,
		name = "Allow sending to this server",
		description = "Tick to agree that your game data and IP address are sent to the Server URL above. Asked once;"
			+ " changing the Server URL to a different host unticks it.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		section = serverSection,
		position = 2
	)
	default boolean consent()
	{
		return false;
	}

	@ConfigItem(
		keyName = "sendLoot",
		name = "Loot",
		description = "Send NPC kills, raids/clue/chest rewards and pickpockets as they happen",
		section = dataSection,
		position = 0
	)
	default boolean sendLoot()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendSkills",
		name = "Skills",
		description = "Send XP/level snapshots and level-ups",
		section = dataSection,
		position = 1
	)
	default boolean sendSkills()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendItems",
		name = "Items",
		description = "Send inventory, equipment, bank (while open), seed vault and looting bag contents",
		section = dataSection,
		position = 2
	)
	default boolean sendItems()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendQuests",
		name = "Quests and diaries",
		description = "Send quest states, quest points and achievement diary completion",
		section = dataSection,
		position = 3
	)
	default boolean sendQuests()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendCollectionLog",
		name = "Collection log",
		description = "Send new collection log items, and the whole log when you open your collection log",
		section = dataSection,
		position = 4
	)
	default boolean sendCollectionLog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendStatus",
		name = "Live status and location",
		description = "Send hitpoints, prayer, run energy, special attack, world, your location and the NPC you are"
			+ " fighting, at most once per second",
		section = dataSection,
		position = 5
	)
	default boolean sendStatus()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendTimers",
		name = "Timers",
		description = "Send farming patch and bird house states with predicted ready times",
		section = dataSection,
		position = 6
	)
	default boolean sendTimers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendProgress",
		name = "Slayer, kill counts and combat achievements",
		description = "Send your slayer task, points and streak, boss kill counts and combat achievement progress",
		section = dataSection,
		position = 7
	)
	default boolean sendProgress()
	{
		return true;
	}
}
