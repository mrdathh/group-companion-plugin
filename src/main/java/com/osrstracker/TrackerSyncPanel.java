package com.osrstracker;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import okhttp3.HttpUrl;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Small side panel: copy the configured token, copy the app's one-tap login link, and a one-line status. It uses only
 * the plugin's own settings; it reads nothing from the server and never shows or logs the token. Swing EDT only.
 */
public class TrackerSyncPanel extends PluginPanel
{
	static final String COPY_TOKEN = "Copy token";
	static final String COPY_LINK = "Copy app login link";
	static final String COPIED = "Copied";
	static final int COPIED_MS = 2_000;
	/** Shown under the buttons; never contains the token. */
	static final String HELP = "<html>Sign in to the app with the login link (open it on your phone) or the token."
		+ " Joined with a join code? This plugin made your token: to play on another PC too, copy it into the plugin"
		+ " there. A new token would make you a new member.</html>";

	private final TrackerSyncConfig config;
	private final Consumer<String> clipboard;
	private final JButton copyToken = new JButton(COPY_TOKEN);
	private final JButton copyLink = new JButton(COPY_LINK);
	private final JLabel status = new JLabel();
	private final JLabel help = new JLabel(HELP);

	TrackerSyncPanel(TrackerSyncConfig config)
	{
		this(config, TrackerSyncPanel::toSystemClipboard);
	}

	/** {@code clipboard} receives the copied text (the system clipboard outside tests). */
	TrackerSyncPanel(TrackerSyncConfig config, Consumer<String> clipboard)
	{
		this.config = config;
		this.clipboard = clipboard;
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JLabel title = new JLabel("Group Companion");
		title.setFont(title.getFont().deriveFont(Font.BOLD));
		title.setForeground(Color.WHITE);

		JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 6));
		buttons.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttons.add(copyToken);
		buttons.add(copyLink);
		copyToken.addActionListener(e -> copy(copyToken, COPY_TOKEN, token()));
		copyLink.addActionListener(e -> copy(copyLink, COPY_LINK, appLoginLink(config.serverUrl(), config.token())));

		status.setForeground(Color.LIGHT_GRAY);
		help.setForeground(Color.GRAY);
		help.setFont(help.getFont().deriveFont(help.getFont().getSize2D() - 1f));
		JPanel bottom = new JPanel(new BorderLayout(0, 8));
		bottom.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bottom.add(status, BorderLayout.NORTH);
		bottom.add(help, BorderLayout.CENTER);
		add(title, BorderLayout.NORTH);
		add(buttons, BorderLayout.CENTER);
		add(bottom, BorderLayout.SOUTH);
		refresh();
	}

	/** Re-read the settings (EDT). */
	void refresh()
	{
		boolean hasToken = token() != null;
		boolean hasLink = appLoginLink(config.serverUrl(), config.token()) != null;
		copyToken.setEnabled(hasToken);
		copyToken.setToolTipText(hasToken ? "Copies your token, e.g. to paste into the app's login or the plugin on another PC"
			: "Set a token or a join code in the plugin settings first");
		copyLink.setEnabled(hasLink);
		copyLink.setToolTipText(hasLink ? "Copies a link that signs the app in on another device"
			: "Set the Server URL and a token or a join code in the plugin settings first");
		status.setText(statusLine(Consent.host(config.serverUrl()) != null, hasToken, config.consent()));
	}

	private void copy(JButton button, String label, String text)
	{
		if (text == null)
		{
			return;
		}
		clipboard.accept(text);
		button.setText(COPIED);
		Timer t = new Timer(COPIED_MS, e -> button.setText(label));
		t.setRepeats(false);
		t.start();
	}

	private String token()
	{
		String t = config.token();
		return t == null || t.trim().isEmpty() ? null : t.trim();
	}

	/** "URL set · token set · sending allowed", naming what is missing. */
	static String statusLine(boolean urlSet, boolean tokenSet, boolean consent)
	{
		return "<html>" + (urlSet ? "URL set" : "No server URL") + " · " + (tokenSet ? "token set" : "no token") + " · "
			+ (consent ? "sending allowed" : "\"Allow sending\" not ticked") + "</html>";
	}

	/**
	 * {@code <origin of the server URL>/#token=<token>}, the app's one-tap sign-in link; null when the URL is not a
	 * valid http(s) URL or the token is empty.
	 */
	static String appLoginLink(String serverUrl, String token)
	{
		if (serverUrl == null || token == null || token.trim().isEmpty())
		{
			return null;
		}
		HttpUrl url = HttpUrl.parse(serverUrl.trim());
		if (url == null)
		{
			return null;
		}
		String host = url.host().contains(":") ? "[" + url.host() + "]" : url.host();
		boolean defaultPort = url.port() == HttpUrl.defaultPort(url.scheme());
		return url.scheme() + "://" + host + (defaultPort ? "" : ":" + url.port()) + "/#token=" + token.trim();
	}

	/** The way RuneLite core plugins copy text. */
	private static void toSystemClipboard(String text)
	{
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
	}

	/** A simple original 16×16 icon: a sync circle with an arrow head. */
	static BufferedImage icon()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0xE0A33A));
		g.setStroke(new BasicStroke(2f));
		g.drawArc(2, 2, 12, 12, 40, 280);
		g.fillPolygon(new int[]{11, 15, 15}, new int[]{3, 2, 7}, 3);
		g.dispose();
		return img;
	}

	/** Run on the Swing EDT. */
	static void onEdt(Runnable r)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			r.run();
		}
		else
		{
			SwingUtilities.invokeLater(r);
		}
	}
}
