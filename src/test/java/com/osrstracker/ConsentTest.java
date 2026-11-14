package com.osrstracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ConsentTest
{
	private static final String URL = "http://127.0.0.1:8787";

	@Test
	public void nothingWithoutTheBoxTicked()
	{
		Consent.Decision d = Consent.decide(URL, false, "");
		assertFalse(d.isGranted());
		assertFalse(d.isChecked());
		assertEquals("", d.getBoundHost());
	}

	@Test
	public void tickingBindsConsentToTheCurrentHost()
	{
		Consent.Decision d = Consent.decide(URL, true, null);
		assertTrue(d.isGranted());
		assertEquals("127.0.0.1", d.getBoundHost());
	}

	@Test
	public void consentStandsForTheSameHost()
	{
		// Token retyped, settings reopened, client restarted, port or path changed: same host, no new question.
		assertTrue(Consent.decide(URL, true, "127.0.0.1").isGranted());
		assertTrue(Consent.decide("http://127.0.0.1:9999/relay", true, "127.0.0.1").isGranted());
		assertTrue(Consent.decide("HTTPS://Tracker.Example.org", true, "tracker.example.org").isGranted());
	}

	@Test
	public void aDifferentHostWithdrawsTheConsent()
	{
		Consent.Decision d = Consent.decide("https://other.example.org", true, "127.0.0.1");
		assertFalse(d.isGranted());
		assertFalse("box unticked so the player is asked again", d.isChecked());
		assertEquals("", d.getBoundHost());
	}

	@Test
	public void anInvalidUrlSendsNothingButKeepsTheConsent()
	{
		Consent.Decision d = Consent.decide("not a url", true, "127.0.0.1");
		assertFalse(d.isGranted());
		assertTrue(d.isChecked());
		assertEquals("127.0.0.1", d.getBoundHost());

		Consent.Decision fresh = Consent.decide("", true, "");
		assertFalse(fresh.isGranted());
		assertEquals("", fresh.getBoundHost());
	}

	@Test
	public void hostParsing()
	{
		assertEquals("127.0.0.1", Consent.host(" http://127.0.0.1:8787/ "));
		assertNull(Consent.host("127.0.0.1:8787"));
		assertNull(Consent.host(null));
	}
}
