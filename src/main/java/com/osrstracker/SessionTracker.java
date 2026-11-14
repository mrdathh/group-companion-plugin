package com.osrstracker;

import com.osrstracker.model.AccountRef;
import com.osrstracker.model.EventType;
import com.osrstracker.model.IngestEvent;
import com.osrstracker.model.SessionData;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.VarbitID;

/**
 * Tracks the logged-in account and gates everything that is sent: an event is only emitted while a
 * session is active (logged in, name and account hash known, world not excluded).
 */
@Slf4j
@Singleton
public class SessionTracker
{
	/** Worlds on which nothing is sent (docs/PROTOCOL.md, "When things are sent"). */
	static final Set<WorldType> EXCLUDED_WORLDS = EnumSet.of(
		WorldType.SEASONAL,
		WorldType.DEADMAN,
		WorldType.FRESH_START_WORLD,
		WorldType.TOURNAMENT_WORLD,
		WorldType.BETA_WORLD,
		WorldType.NOSAVE_MODE,
		WorldType.QUEST_SPEEDRUNNING,
		WorldType.PVP_ARENA);

	private static final String[] ACCOUNT_TYPES = {
		"NORMAL", "IRONMAN", "ULTIMATE_IRONMAN", "HARDCORE_IRONMAN",
		"GROUP_IRONMAN", "HARDCORE_GROUP_IRONMAN", "UNRANKED_GROUP_IRONMAN",
	};

	/** Notified on the client thread (or the shutdown thread for the final end). */
	public interface Listener
	{
		/** A session started, or the server config changed during one: capture and emit every snapshot. */
		void onSessionStart();

		/**
		 * The session is ending (it is still active during this call, so already-captured data may be emitted).
		 * May run off the client thread (plugin stop, client shutdown): must not read game state.
		 */
		void onSessionEnd();
	}

	private final Client client;
	private final EventSender sender;
	private final TimeSource time;
	private final List<Listener> listeners = new CopyOnWriteArrayList<>();

	private volatile AccountRef account;
	private volatile boolean loginSent;
	private boolean awaitingLogin;

	@Inject
	SessionTracker(Client client, EventSender sender, TimeSource time)
	{
		this.client = client;
		this.sender = sender;
		this.time = time;
	}

	public void addListener(Listener listener)
	{
		listeners.add(listener);
	}

	public void clearListeners()
	{
		listeners.clear();
	}

	public boolean isActive()
	{
		return account != null;
	}

	/** Emit an event for the current session, stamped now. Ignored when no session is active. */
	public void emit(EventType type, Object data)
	{
		emit(type, data, time.now());
	}

	public void emit(EventType type, Object data, Instant at)
	{
		AccountRef a = account;
		if (a != null)
		{
			sender.enqueue(a, IngestEvent.create(type, data, at));
		}
	}

	/** Emit a snapshot now even if it is unchanged since the last accepted send (live-status refresh). */
	public void emitRefresh(EventType type, Object data)
	{
		AccountRef a = account;
		if (a != null)
		{
			sender.enqueue(a, IngestEvent.create(type, data, time.now()), true);
		}
	}

	// ------------------------------------------------------------------ driven by the plugin (client thread)

	public void onGameStateChanged(GameState state)
	{
		switch (state)
		{
			case LOGGED_IN:
				// Also fires after every region load; resolving is idempotent.
				awaitingLogin = true;
				break;
			case LOGIN_SCREEN:
				awaitingLogin = false;
				endSession();
				break;
			default:
				break;
		}
	}

	/** Called every game tick: completes a pending login once the name and account hash are known. */
	public void onGameTick()
	{
		if (!awaitingLogin)
		{
			return;
		}
		Player local = client.getLocalPlayer();
		long hash = client.getAccountHash();
		if (local == null || local.getName() == null || hash == -1)
		{
			return;
		}
		awaitingLogin = false;

		boolean allowed = isAllowedWorld(client.getWorldType());
		AccountRef next = new AccountRef(Long.toString(hash), local.getName(), readAccountType(), client.getWorld());
		AccountRef current = account;
		if (current != null && (!allowed || !current.getHash().equals(next.getHash())))
		{
			endSession();
			current = null;
		}
		if (!allowed)
		{
			log.debug("Tracker Sync: world {} is excluded, not sending", client.getWorld());
			return;
		}
		if (current == null)
		{
			startSession(next);
		}
		else if (!current.equals(next))
		{
			// World hop (or name/type change) within the same session: later requests carry the new values.
			account = next;
			sender.setOnlineAccount(next);
		}
	}

	/** The account type varbit changed (e.g. a hardcore ironman died). */
	public void refreshAccountType()
	{
		AccountRef a = account;
		if (a != null)
		{
			String type = readAccountType();
			if (!type.equals(a.getType()))
			{
				account = new AccountRef(a.getHash(), a.getRsn(), type, a.getWorld());
				sender.setOnlineAccount(account);
			}
		}
	}

	/** URL or token changed. If a session is running, announce it to the (possibly new) server. */
	public void onServerConfigChanged()
	{
		AccountRef a = account;
		if (a != null && sender.isConfigured())
		{
			sendLogin(a);
			listeners.forEach(Listener::onSessionStart);
		}
	}

	/** Capture and emit every snapshot again (client thread). */
	public void resnapshot()
	{
		if (account != null)
		{
			listeners.forEach(Listener::onSessionStart);
		}
	}

	/** Plugin stopped or client shutting down. */
	public void shutdown()
	{
		awaitingLogin = false;
		endSession();
	}

	/** Re-check the login state, e.g. when the plugin starts while already logged in. */
	public void requestLoginCheck()
	{
		awaitingLogin = true;
	}

	// ------------------------------------------------------------------ internals

	static boolean isAllowedWorld(Set<WorldType> worldTypes)
	{
		for (WorldType t : worldTypes)
		{
			if (EXCLUDED_WORLDS.contains(t))
			{
				return false;
			}
		}
		return true;
	}

	static String accountType(int varbitValue)
	{
		return varbitValue >= 0 && varbitValue < ACCOUNT_TYPES.length ? ACCOUNT_TYPES[varbitValue] : "NORMAL";
	}

	private String readAccountType()
	{
		return accountType(client.getVarbitValue(VarbitID.IRONMAN));
	}

	private void startSession(AccountRef next)
	{
		account = next;
		sender.setOnlineAccount(next);
		sendLogin(next);
		listeners.forEach(Listener::onSessionStart);
	}

	private void sendLogin(AccountRef a)
	{
		loginSent = sender.isConfigured();
		sender.enqueue(a, IngestEvent.create(EventType.LOGIN, new SessionData(a.getWorld()), time.now()));
	}

	private void endSession()
	{
		AccountRef a = account;
		if (a == null)
		{
			return;
		}
		// Collectors first, so loot or containers still waiting for their timer go out before the logout.
		listeners.forEach(Listener::onSessionEnd);
		if (loginSent)
		{
			sender.enqueue(a, IngestEvent.create(EventType.LOGOUT, new SessionData(a.getWorld()), time.now()));
		}
		account = null;
		loginSent = false;
		sender.setOnlineAccount(null);
	}
}
