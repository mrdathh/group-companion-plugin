package com.osrstracker;

import java.util.Locale;
import lombok.Value;
import okhttp3.HttpUrl;

/**
 * One-time consent to send data to the configured server, bound to that server's host.
 *
 * <p>The player gives consent by ticking "Allow sending to this server" (RuneLite shows its standard
 * third-party warning once, on that click). The host it was given for is remembered in a hidden config key.
 * Retyping the token, reopening the settings or restarting the client do not ask again. Changing the
 * Server URL to a different host withdraws the consent (the box is unticked), so the player is asked again.
 */
final class Consent
{
	private Consent()
	{
	}

	/** What to do with the stored consent for the current settings. */
	@Value
	static class Decision
	{
		/** Data may be sent to the configured URL. */
		boolean granted;
		/** Value the consent checkbox should have (false = withdraw). */
		boolean checked;
		/** Host to remember ("" = none). */
		String boundHost;
	}

	/** Lower-cased host of a server URL, or null if it is not a valid http(s) URL. */
	static String host(String serverUrl)
	{
		if (serverUrl == null)
		{
			return null;
		}
		HttpUrl url = HttpUrl.parse(serverUrl.trim());
		return url == null ? null : url.host().toLowerCase(Locale.ROOT);
	}

	/**
	 * @param serverUrl the configured Server URL
	 * @param checked whether the consent box is ticked
	 * @param boundHost the host consent was given for ("" or null if none)
	 */
	static Decision decide(String serverUrl, boolean checked, String boundHost)
	{
		String host = host(serverUrl);
		String bound = boundHost == null ? "" : boundHost;
		if (!checked)
		{
			return new Decision(false, false, "");
		}
		if (bound.isEmpty())
		{
			// Just ticked: consent is for the host entered now (none yet if the URL is invalid).
			return host == null ? new Decision(false, true, "") : new Decision(true, true, host);
		}
		if (host != null && host.equals(bound))
		{
			return new Decision(true, true, bound);
		}
		if (host == null)
		{
			// URL cleared or being edited into something invalid: nothing can be sent, keep the consent.
			return new Decision(false, true, bound);
		}
		// A different destination is a new decision: withdraw and ask again.
		return new Decision(false, false, "");
	}
}
