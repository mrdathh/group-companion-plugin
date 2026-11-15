package com.osrstracker.model;

import com.google.gson.annotations.JsonAdapter;
import lombok.Value;

/**
 * protocol.ts ActivityData: signals for the app's idle alerts. Every field changes only on a transition
 * (input stopping or resuming, combat starting or ending, the character going idle or busy), so the
 * {@code status} it rides in stays unchanged, and unsent, while nothing happens.
 */
@Value
public class ActivityData
{
	/** Since when there has been no mouse or keyboard input, once that has lasted 10 s or more. */
	@JsonAdapter(value = WriteNull.class, nullSafe = false)
	String inputIdleSince;
	/** Estimated moment the game logs the player out for inactivity if no input arrives. */
	@JsonAdapter(value = WriteNull.class, nullSafe = false)
	String logoutAt;
	boolean inCombat;
	/** When combat last ended; null while in combat or before any combat this session. */
	@JsonAdapter(value = WriteNull.class, nullSafe = false)
	String combatEndedAt;
	/** Since when the character has had no animation, movement or interaction, once that has lasted 3 s. */
	@JsonAdapter(value = WriteNull.class, nullSafe = false)
	String actionIdleSince;
}
