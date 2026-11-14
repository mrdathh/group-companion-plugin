package com.osrstracker.model;

import lombok.Value;

/** protocol.ts IngestRequest.client. */
@Value
public class ClientInfo
{
	/** Random UUID generated once per RuneLite install. */
	String id;
	/** Plugin version. */
	String plugin;
}
