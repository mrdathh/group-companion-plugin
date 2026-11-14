package com.osrstracker.model;

/** protocol.ts IngestEvent["type"], split into history and state events. */
public enum EventType
{
	LOGIN("login", false),
	LOGOUT("logout", false),
	LOOT("loot", false),
	LEVEL_UP("level_up", false),
	DEATH("death", false),
	CLOG_ITEM("clog_item", false),
	BANK_DEPOSIT("bank_deposit", false),
	SKILLS("skills", true),
	CONTAINER("container", true),
	QUESTS("quests", true),
	DIARIES("diaries", true),
	COLLECTION_LOG("collection_log", true),
	/** Volatile: never queued while the server is unreachable. */
	STATUS("status", true),
	TIMERS("timers", true),
	SLAYER("slayer", true),
	KILL_COUNTS("kill_counts", true),
	COMBAT_ACHIEVEMENTS("combat_achievements", true),
	LOADOUTS("loadouts", true),
	KINGDOM("kingdom", true);

	private final String wireName;
	private final boolean state;

	EventType(String wireName, boolean state)
	{
		this.wireName = wireName;
		this.state = state;
	}

	public String wireName()
	{
		return wireName;
	}

	public boolean isState()
	{
		return state;
	}

	/** Volatile events are dropped instead of queued while the server is unreachable. */
	public boolean isVolatile()
	{
		return this == STATUS;
	}

	public static EventType fromWireName(String name)
	{
		for (EventType t : values())
		{
			if (t.wireName.equals(name))
			{
				return t;
			}
		}
		return null;
	}

	/**
	 * State key of a state event: the event type, or "container:name" for containers.
	 * Returns null for history events.
	 */
	public static String stateKey(IngestEvent event)
	{
		EventType t = fromWireName(event.getType());
		if (t == null || !t.state)
		{
			return null;
		}
		if (t == CONTAINER)
		{
			return "container:" + ((ContainerData) event.getData()).getContainer();
		}
		return t.wireName;
	}
}
