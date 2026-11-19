package com.osrstracker;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Random;

/**
 * Joining a server with its join code: when the Token field is empty and a join code is set, the plugin makes the
 * player's personal token itself, in the server's token format ({@code osrs_} + 32 random bytes, base64url without
 * padding). The server creates the player's user for that token on the first request that carries the join code.
 */
final class JoinToken
{
	static final String PREFIX = "osrs_";
	static final int RANDOM_BYTES = 32;

	private static final SecureRandom RANDOM = new SecureRandom();

	private JoinToken()
	{
	}

	/** True when a token should be made: no token yet, and a join code to join with. */
	static boolean needed(String token, String joinCode)
	{
		return isBlank(token) && !isBlank(joinCode);
	}

	static String generate()
	{
		return generate(RANDOM);
	}

	static String generate(Random random)
	{
		byte[] bytes = new byte[RANDOM_BYTES];
		random.nextBytes(bytes);
		return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** The join code to send, or null for none (blank). */
	static String headerValue(String joinCode)
	{
		return isBlank(joinCode) ? null : joinCode.trim();
	}

	private static boolean isBlank(String s)
	{
		return s == null || s.trim().isEmpty();
	}
}
