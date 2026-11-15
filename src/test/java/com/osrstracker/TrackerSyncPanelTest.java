package com.osrstracker;

import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TrackerSyncPanelTest
{
	@Test
	public void appLoginLinkUsesTheUrlsOrigin()
	{
		assertEquals("https://tracker.example.org/#token=osrs_abc", TrackerSyncPanel.appLoginLink("https://tracker.example.org", "osrs_abc"));
		assertEquals("https://tracker.example.org/#token=osrs_abc", TrackerSyncPanel.appLoginLink(" https://tracker.example.org/ ", " osrs_abc "));
		assertEquals("https://example.org/#token=t", TrackerSyncPanel.appLoginLink("https://example.org/relay/api/", "t"));
		assertEquals("http://127.0.0.1:8787/#token=t", TrackerSyncPanel.appLoginLink("http://127.0.0.1:8787/x?y=1", "t"));
		assertEquals("https://example.org/#token=t", TrackerSyncPanel.appLoginLink("https://example.org:443", "t"));
		assertEquals("https://example.org:8443/#token=t", TrackerSyncPanel.appLoginLink("https://example.org:8443/", "t"));
		assertEquals("http://[::1]:8787/#token=t", TrackerSyncPanel.appLoginLink("http://[::1]:8787", "t"));
	}

	@Test
	public void noLinkForEmptyOrInvalidValues()
	{
		assertNull(TrackerSyncPanel.appLoginLink("", "t"));
		assertNull(TrackerSyncPanel.appLoginLink(null, "t"));
		assertNull(TrackerSyncPanel.appLoginLink("not a url", "t"));
		assertNull(TrackerSyncPanel.appLoginLink("https://example.org", ""));
		assertNull(TrackerSyncPanel.appLoginLink("https://example.org", "   "));
		assertNull(TrackerSyncPanel.appLoginLink("https://example.org", null));
	}

	private static JButton button(TrackerSyncPanel panel, String label)
	{
		List<JButton> found = new ArrayList<>();
		collect(panel, found);
		return found.stream().filter(b -> b.getText().equals(label)).findFirst().orElseThrow(AssertionError::new);
	}

	private static void collect(java.awt.Container c, List<JButton> out)
	{
		for (java.awt.Component child : c.getComponents())
		{
			if (child instanceof JButton)
			{
				out.add((JButton) child);
			}
			else if (child instanceof java.awt.Container)
			{
				collect((java.awt.Container) child, out);
			}
		}
	}

	@Test
	public void buttonsCopyAndAreDisabledWithoutAToken()
	{
		TrackerSyncConfig config = mock(TrackerSyncConfig.class);
		when(config.serverUrl()).thenReturn("https://tracker.example.org");
		when(config.token()).thenReturn("");
		List<String> copied = new ArrayList<>();
		TrackerSyncPanel panel = new TrackerSyncPanel(config, copied::add);
		assertFalse(button(panel, TrackerSyncPanel.COPY_TOKEN).isEnabled());
		assertFalse(button(panel, TrackerSyncPanel.COPY_LINK).isEnabled());

		when(config.token()).thenReturn("osrs_secret");
		when(config.consent()).thenReturn(true);
		panel.refresh();
		JButton token = button(panel, TrackerSyncPanel.COPY_TOKEN);
		JButton link = button(panel, TrackerSyncPanel.COPY_LINK);
		assertTrue(token.isEnabled());
		token.doClick();
		link.doClick();
		assertEquals("osrs_secret", copied.get(0));
		assertEquals("https://tracker.example.org/#token=osrs_secret", copied.get(1));
		assertEquals(TrackerSyncPanel.COPIED, token.getText());
	}

	@Test
	public void statusLineNamesWhatIsMissingAndNeverTheToken()
	{
		assertEquals("<html>URL set · token set · sending allowed</html>", TrackerSyncPanel.statusLine(true, true, true));
		assertEquals("<html>No server URL · no token · \"Allow sending\" not ticked</html>", TrackerSyncPanel.statusLine(false, false, false));
	}
}
