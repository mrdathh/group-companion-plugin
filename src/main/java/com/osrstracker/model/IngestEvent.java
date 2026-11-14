package com.osrstracker.model;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import lombok.Value;

/** protocol.ts EventBase: one history or state event. */
@Value
public class IngestEvent
{
	private static final DateTimeFormatter ISO_MILLIS =
		DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

	String id;
	String type;
	/** ISO-8601 UTC with milliseconds. */
	String at;
	/** One of the *Data classes in this package. */
	Object data;

	public static IngestEvent create(EventType type, Object data, Instant at)
	{
		return new IngestEvent(UUID.randomUUID().toString(), type.wireName(), formatTime(at), data);
	}

	public static String formatTime(Instant at)
	{
		return ISO_MILLIS.format(at);
	}
}
