package com.osrstracker;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class JoinTokenTest
{
	@Test
	public void tokensHaveTheServersFormatAndDiffer()
	{
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 50; i++)
		{
			String t = JoinToken.generate();
			// The server accepts exactly ^osrs_[A-Za-z0-9_-]{43}$.
			assertTrue(t, t.matches("^osrs_[A-Za-z0-9_-]{43}$"));
			assertTrue("unique", seen.add(t));
		}
		// Deterministic with a seeded source: base64url without padding of 32 bytes.
		assertEquals(JoinToken.generate(new Random(7)), JoinToken.generate(new Random(7)));
		byte[] zeros = new byte[32];
		Random allZero = new Random()
		{
			@Override
			public void nextBytes(byte[] bytes)
			{
				System.arraycopy(zeros, 0, bytes, 0, bytes.length);
			}
		};
		assertEquals("osrs_" + repeat('A', 43), JoinToken.generate(allZero));
	}

	@Test
	public void aTokenIsMadeOnlyWhenTheTokenIsEmptyAndAJoinCodeIsSet()
	{
		assertTrue(JoinToken.needed("", "ABCD-EFGH"));
		assertTrue(JoinToken.needed(null, "ABCD-EFGH"));
		assertTrue(JoinToken.needed("   ", "ABCD-EFGH"));
		assertFalse(JoinToken.needed("osrs_mine", "ABCD-EFGH"));
		assertFalse(JoinToken.needed("", ""));
		assertFalse(JoinToken.needed("", "  "));
		assertFalse(JoinToken.needed("", null));
	}

	@Test
	public void headerValueIsTrimmedOrNull()
	{
		assertEquals("ABCD-EFGH", JoinToken.headerValue("  ABCD-EFGH "));
		assertNull(JoinToken.headerValue(""));
		assertNull(JoinToken.headerValue(" "));
		assertNull(JoinToken.headerValue(null));
	}

	private static String repeat(char c, int n)
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n; i++)
		{
			sb.append(c);
		}
		return sb.toString();
	}
}
