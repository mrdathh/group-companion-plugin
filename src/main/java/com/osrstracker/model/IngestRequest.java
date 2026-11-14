package com.osrstracker.model;

import java.util.List;
import lombok.Value;

/** protocol.ts IngestRequest. An empty event list is a heartbeat. */
@Value
public class IngestRequest
{
	public static final int PROTOCOL_VERSION = 1;

	int v;
	ClientInfo client;
	AccountRef account;
	List<IngestEvent> events;
}
