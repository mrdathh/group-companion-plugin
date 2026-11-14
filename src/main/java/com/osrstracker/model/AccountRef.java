package com.osrstracker.model;

import lombok.Value;

/** protocol.ts AccountRef. */
@Value
public class AccountRef
{
	/** client.getAccountHash() as a decimal string. */
	String hash;
	String rsn;
	/** One of the protocol AccountType names. */
	String type;
	int world;
}
