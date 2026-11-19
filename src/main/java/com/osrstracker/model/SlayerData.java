package com.osrstracker.model;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.Value;

/** protocol.ts SlayerData. */
@Value
public class SlayerData
{
	/** Sent as null when there is no task. */
	@JsonAdapter(value = WriteNull.class, nullSafe = false)
	Task task;
	int points;
	int streak;
	/** The master who gave the current or last task; omitted when unknown. */
	String master;
	Integer wildernessStreak;
	/** Blocked task names (current master's block list; Spria uses Turael's); omitted when unknown. */
	List<String> blocked;
	/** Purchased reward unlocks and extensions, by name; omitted when unknown. */
	List<String> unlocks;
	/** Every master's block list: master name → blocked task names (empty = none); omitted when unknown. */
	Map<String, List<String>> blockedByMaster;
	/**
	 * The Task Storage slot: omitted (Java null) when not seen this session; {@link StoredSlot#empty()} is sent as
	 * JSON {@code null} (seen empty).
	 */
	StoredSlot stored;

	public SlayerData(Task task, int points, int streak)
	{
		this(task, points, streak, null, null, null, null);
	}

	public SlayerData(Task task, int points, int streak, String master, Integer wildernessStreak, List<String> blocked, List<String> unlocks)
	{
		this(task, points, streak, master, wildernessStreak, blocked, unlocks, null, null);
	}

	public SlayerData(Task task, int points, int streak, String master, Integer wildernessStreak, List<String> blocked,
		List<String> unlocks, Map<String, List<String>> blockedByMaster, StoredSlot stored)
	{
		this.task = task;
		this.points = points;
		this.streak = streak;
		this.master = master;
		this.wildernessStreak = wildernessStreak;
		this.blocked = blocked;
		this.unlocks = unlocks;
		this.blockedByMaster = blockedByMaster;
		this.stored = stored;
	}

	@Value
	public static class Task
	{
		String name;
		int remaining;
		int initial;
		/** Omitted when the task has no assigned area. */
		String location;
	}

	/** protocol.ts SlayerStoredTask. */
	@Value
	public static class StoredTask
	{
		String name;
		int amount;
		/** ISO-8601 UTC: when the plugin first saw the slot hold this task (this session). */
		String seenAt;
	}

	/**
	 * A seen Task Storage slot: written as the {@link StoredTask} or, when the slot was seen empty, as JSON
	 * {@code null}. A {@code null} StoredSlot field is left out as usual (not seen).
	 */
	@Value
	@JsonAdapter(StoredSlot.Adapter.class)
	public static class StoredSlot
	{
		private static final StoredSlot EMPTY = new StoredSlot(null);

		/** Null = the slot was seen empty. */
		StoredTask task;

		public static StoredSlot empty()
		{
			return EMPTY;
		}

		public static StoredSlot of(StoredTask task)
		{
			return task == null ? EMPTY : new StoredSlot(task);
		}

		/** Class-level adapter (null-safe by default, so a null field is omitted); see {@link WriteNull}. */
		public static final class Adapter implements TypeAdapterFactory
		{
			@Override
			@SuppressWarnings("unchecked")
			public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type)
			{
				TypeAdapter<StoredTask> delegate = gson.getAdapter(StoredTask.class);
				return (TypeAdapter<T>) new TypeAdapter<StoredSlot>()
				{
					@Override
					public void write(JsonWriter out, StoredSlot slot) throws IOException
					{
						if (slot.task != null)
						{
							delegate.write(out, slot.task);
							return;
						}
						boolean previous = out.getSerializeNulls();
						out.setSerializeNulls(true);
						out.nullValue();
						out.setSerializeNulls(previous);
					}

					@Override
					public StoredSlot read(JsonReader in) throws IOException
					{
						return of(delegate.read(in));
					}
				};
			}
		}
	}
}
