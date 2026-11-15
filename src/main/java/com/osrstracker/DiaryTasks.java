package com.osrstracker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrstracker.model.DiariesData;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-task achievement diary data, loaded once from the bundled resource {@code diary_tasks.json}: for every
 * region and tier, the tasks in the in-game order with the var that marks each one done. Task names and bits
 * come from the Group Ironmen Tracker site (christoabrown/group-ironmen, BSD-2-Clause), cross-checked against
 * WikiSync's diary spec (weirdgloop/wikisync-api, MIT); see THIRD_PARTY_NOTICES.md.
 */
@Slf4j
@Singleton
public class DiaryTasks
{
	static final String RESOURCE = "diary_tasks.json";

	/** One task: its name and the conditions of which any one marks it done. */
	static final class Task
	{
		final String name;
		/** Each {kind, var, x}: kind 0 = varp bit x set, kind 1 = varbit equals x. */
		final int[][] conditions;

		Task(String name, int[][] conditions)
		{
			this.name = name;
			this.conditions = conditions;
		}

		boolean done(IntUnaryOperator varp, IntUnaryOperator varbit)
		{
			for (int[] c : conditions)
			{
				if (c[0] == 0 ? ((varp.applyAsInt(c[1]) >>> c[2]) & 1) == 1 : varbit.applyAsInt(c[1]) == c[2])
				{
					return true;
				}
			}
			return false;
		}
	}

	/** region -> tier -> tasks; empty when the resource could not be read. */
	private final Map<String, Map<String, List<Task>>> diaries;
	private final Set<Integer> varps = new HashSet<>();
	private final Set<Integer> varbits = new HashSet<>();

	@Inject
	DiaryTasks(Gson gson)
	{
		this.diaries = load(gson);
	}

	private Map<String, Map<String, List<Task>>> load(Gson gson)
	{
		Map<String, Map<String, List<Task>>> out = new LinkedHashMap<>();
		try (InputStream in = DiaryTasks.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("Group Companion: {} missing; diaries are sent without tasks", RESOURCE);
				return out;
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				JsonObject root = gson.fromJson(reader, JsonObject.class);
				for (Map.Entry<String, JsonElement> region : root.getAsJsonObject("diaries").entrySet())
				{
					Map<String, List<Task>> tiers = new LinkedHashMap<>();
					for (Map.Entry<String, JsonElement> tier : region.getValue().getAsJsonObject().entrySet())
					{
						List<Task> tasks = new ArrayList<>();
						for (JsonElement t : tier.getValue().getAsJsonArray())
						{
							tasks.add(parseTask(t.getAsJsonArray()));
						}
						tiers.put(tier.getKey(), tasks);
					}
					out.put(region.getKey(), tiers);
				}
			}
		}
		catch (Exception e)
		{
			log.warn("Group Companion: could not read {}; diaries are sent without tasks", RESOURCE, e);
			out.clear();
		}
		return out;
	}

	private Task parseTask(JsonArray a)
	{
		String name = a.get(0).getAsString();
		int[][] conditions = new int[a.size() - 1][];
		for (int i = 1; i < a.size(); i++)
		{
			JsonArray c = a.get(i).getAsJsonArray();
			boolean varp = "p".equals(c.get(0).getAsString());
			int var = c.get(1).getAsInt();
			conditions[i - 1] = new int[]{varp ? 0 : 1, var, c.get(2).getAsInt()};
			(varp ? varps : varbits).add(var);
		}
		return new Task(name, conditions);
	}

	public boolean isEmpty()
	{
		return diaries.isEmpty();
	}

	/** Varps holding task bits (a change means a task may have been completed). */
	public Set<Integer> varps()
	{
		return Collections.unmodifiableSet(varps);
	}

	/** Varbits holding task states. */
	public Set<Integer> varbits()
	{
		return Collections.unmodifiableSet(varbits);
	}

	/** The tier's tasks with their state, or null when the tier is not in the data. */
	public List<DiariesData.Task> evaluate(String region, String tier, IntUnaryOperator varp, IntUnaryOperator varbit)
	{
		Map<String, List<Task>> tiers = diaries.get(region);
		List<Task> tasks = tiers == null ? null : tiers.get(tier);
		if (tasks == null)
		{
			return null;
		}
		List<DiariesData.Task> out = new ArrayList<>(tasks.size());
		for (Task t : tasks)
		{
			out.add(new DiariesData.Task(t.name, t.done(varp, varbit)));
		}
		return out;
	}
}
