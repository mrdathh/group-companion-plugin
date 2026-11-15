package com.osrstracker.model;

import com.google.gson.annotations.JsonAdapter;
import lombok.AllArgsConstructor;
import lombok.Value;

/** protocol.ts StatusData (volatile live status). */
@Value
@AllArgsConstructor
public class StatusData
{
	CurMax hp;
	CurMax prayer;
	/** 0-100. */
	int runEnergy;
	/** 0-100. */
	int specialAttack;
	int world;
	Location location;
	/** The NPC the player is fighting or talking to; never another player. Sent as null when none. */
	@JsonAdapter(value = WriteNull.class, nullSafe = false)
	Interacting interacting;
	/** Idle/combat signals; optional in the contract, left out when null. */
	ActivityData activity;

	public StatusData(CurMax hp, CurMax prayer, int runEnergy, int specialAttack, int world, Location location,
		Interacting interacting)
	{
		this(hp, prayer, runEnergy, specialAttack, world, location, interacting, null);
	}

	@Value
	public static class CurMax
	{
		int cur;
		int max;
	}

	@Value
	public static class Interacting
	{
		String name;
		Integer id;
		/** Actor.getHealthRatio(); -1 when the health bar is not shown. */
		int hpRatio;
		/** Actor.getHealthScale(); -1 when the health bar is not shown. */
		int hpScale;
	}
}
