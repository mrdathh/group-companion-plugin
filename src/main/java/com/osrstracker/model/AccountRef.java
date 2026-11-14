package com.osrstracker.model;

import lombok.AllArgsConstructor;
import lombok.Value;

/** protocol.ts AccountRef. */
@Value
@AllArgsConstructor
public class AccountRef
{
	/** client.getAccountHash() as a decimal string. */
	String hash;
	String rsn;
	/** One of the protocol AccountType names. */
	String type;
	int world;
	/** Name of the player's group ironman group; null (omitted) when not in one or not known yet. */
	String group;

	public AccountRef(String hash, String rsn, String type, int world)
	{
		this(hash, rsn, type, world, null);
	}

	public AccountRef withGroup(String newGroup)
	{
		return new AccountRef(hash, rsn, type, world, newGroup);
	}
}
