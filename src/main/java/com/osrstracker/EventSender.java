package com.osrstracker;

import com.google.gson.Gson;
import com.osrstracker.model.AccountRef;
import com.osrstracker.model.ClientInfo;
import com.osrstracker.model.EventType;
import com.osrstracker.model.IngestEvent;
import com.osrstracker.model.IngestRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Owns the outgoing queue: batching, retry with backoff, the history cap, newest-snapshot-per-state-key,
 * unchanged-snapshot suppression, solo events (sent alone, see {@link EventType#isSolo()}) and the heartbeat.
 * Thread-safe; all network IO goes through
 * {@link Call#enqueue} so no caller ever blocks.
 */
@Slf4j
@Singleton
public class EventSender
{
	public static final String PLUGIN_VERSION = "0.1.0";

	static final int MAX_HISTORY = 500;
	static final int MAX_EVENTS_PER_REQUEST = 250;
	static final long FLUSH_DELAY_MS = 50;
	static final long HEARTBEAT_MS = 60_000;
	static final long BACKOFF_MIN_MS = 5_000;
	static final long BACKOFF_MAX_MS = 60_000;
	static final long CALL_TIMEOUT_S = 15;

	private static final MediaType JSON = MediaType.parse("application/json");

	/** Callbacks into the plugin; invoked from OkHttp threads. */
	public interface Listener
	{
		void onUnauthorized();
	}

	private final OkHttpClient http;
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final TimeSource time;

	private HttpUrl endpoint;
	private String token;
	private String clientId = "";
	private Listener listener;

	private final Deque<Pending> history = new ArrayDeque<>();
	/** accountHash|stateKey -> newest pending snapshot. */
	private final Map<String, Pending> states = new LinkedHashMap<>();
	/** accountHash|stateKey -> JSON of the data last accepted by the server. */
	private final Map<String, String> lastSentState = new HashMap<>();
	/** State keys of snapshots in the request currently in flight. */
	private final Set<String> inFlightStateKeys = new HashSet<>();

	private AccountRef onlineAccount;
	private boolean running;
	private boolean inFlight;
	private boolean unauthorized;
	private boolean loggedRejection;
	private long backoffMs;
	private long retryAt;
	private long lastRequestAt;
	private long seq;
	private ScheduledFuture<?> flushFuture;
	private ScheduledFuture<?> heartbeatFuture;

	@Inject
	public EventSender(OkHttpClient http, Gson gson, ScheduledExecutorService executor, TimeSource time)
	{
		this.http = http;
		this.gson = gson;
		this.executor = executor;
		this.time = time;
	}

	// ------------------------------------------------------------------ lifecycle and configuration

	public synchronized void start(Listener listener)
	{
		this.listener = listener;
		running = true;
		scheduleFlush(0);
		scheduleHeartbeat();
	}

	/**
	 * Final flush (ignores a request already in flight) and stop all timers.
	 *
	 * @return completes when the final request finished or failed, or immediately if there was nothing to send
	 */
	public CompletableFuture<Void> stop()
	{
		CompletableFuture<Void> done;
		synchronized (this)
		{
			done = sendNow(true);
			running = false;
			cancel(flushFuture);
			cancel(heartbeatFuture);
			flushFuture = null;
			heartbeatFuture = null;
		}
		return done;
	}

	/** As {@link #configure(String, String, String, boolean)} with consent given. */
	public synchronized boolean configure(String serverUrl, String token, String clientId)
	{
		return configure(serverUrl, token, clientId, true);
	}

	/**
	 * Apply the configured server URL and token. Invalid or blank values, or no consent, leave the sender
	 * unconfigured (nothing is queued or sent).
	 *
	 * @return true if the effective destination (endpoint or token, or whether sending is allowed) changed
	 */
	public synchronized boolean configure(String serverUrl, String token, String clientId, boolean consent)
	{
		HttpUrl newEndpoint = consent ? parseEndpoint(serverUrl) : null;
		String newToken = token == null || token.trim().isEmpty() ? null : token.trim();
		boolean changed = !String.valueOf(newEndpoint).equals(String.valueOf(endpoint)) || !String.valueOf(newToken).equals(String.valueOf(this.token));
		this.endpoint = newEndpoint;
		this.token = newToken;
		this.clientId = clientId == null ? "" : clientId;
		if (changed)
		{
			unauthorized = false;
			loggedRejection = false;
			backoffMs = 0;
			retryAt = 0;
			// The new server may hold nothing yet: allow every snapshot to be sent again.
			lastSentState.clear();
			scheduleFlush(0);
		}
		return changed;
	}

	public synchronized boolean isConfigured()
	{
		return endpoint != null && token != null;
	}

	/** The account to keep online with heartbeats, or null after logout. */
	public synchronized void setOnlineAccount(AccountRef account)
	{
		this.onlineAccount = account;
		scheduleHeartbeat();
	}

	static HttpUrl parseEndpoint(String serverUrl)
	{
		if (serverUrl == null || serverUrl.trim().isEmpty())
		{
			return null;
		}
		HttpUrl base = HttpUrl.parse(serverUrl.trim());
		if (base == null)
		{
			return null;
		}
		return base.newBuilder().addPathSegments("api/ingest").build();
	}

	// ------------------------------------------------------------------ queueing

	/** Queue an event for the given account. Dropped when the sender is not configured. */
	public void enqueue(AccountRef account, IngestEvent event)
	{
		enqueue(account, event, false);
	}

	/**
	 * Queue an event. With {@code resendUnchanged} a state snapshot is sent even if it equals the last accepted
	 * one (used only for the periodic live-status refresh).
	 */
	public synchronized void enqueue(AccountRef account, IngestEvent event, boolean resendUnchanged)
	{
		if (!isConfigured())
		{
			return;
		}
		EventType type = EventType.fromWireName(event.getType());
		boolean isVolatile = type != null && type.isVolatile();
		if (isVolatile && (backoffMs > 0 || unauthorized))
		{
			return; // live status is never queued while the server is unreachable
		}
		String stateKey = EventType.stateKey(event);
		Pending p = new Pending(account, event, stateKey == null ? null : account.getHash() + "|" + stateKey,
			stateKey == null ? null : gson.toJson(event.getData()), seq++, isVolatile, resendUnchanged,
			type != null && type.isSolo());
		if (p.stateKey != null)
		{
			// Unchanged since the last accepted send, and no other version pending or in flight: nothing to do.
			// (Otherwise it is queued and the check is repeated when the next batch is built.)
			if (!resendUnchanged && p.dataJson.equals(lastSentState.get(p.stateKey)) && !states.containsKey(p.stateKey)
				&& !inFlightStateKeys.contains(p.stateKey))
			{
				return;
			}
			states.remove(p.stateKey);
			states.put(p.stateKey, p);
		}
		else
		{
			history.addLast(p);
			trimHistory();
		}
		scheduleFlush(FLUSH_DELAY_MS);
	}

	private void trimHistory()
	{
		while (history.size() > MAX_HISTORY)
		{
			Pending dropped = history.removeFirst();
			log.debug("Queue full, dropping oldest {} event", dropped.event.getType());
		}
	}

	synchronized int pendingHistory()
	{
		return history.size();
	}

	synchronized int pendingStates()
	{
		return states.size();
	}

	// ------------------------------------------------------------------ flushing

	private void scheduleFlush(long delayMs)
	{
		if (!running || inFlight || flushFuture != null || !isConfigured() || unauthorized
			|| (history.isEmpty() && states.isEmpty()))
		{
			return;
		}
		long wait = Math.max(delayMs, retryAt - time.nowMillis());
		flushFuture = executor.schedule(this::onFlushTimer, Math.max(0, wait), TimeUnit.MILLISECONDS);
	}

	private void onFlushTimer()
	{
		synchronized (this)
		{
			flushFuture = null;
			if (!running || inFlight)
			{
				return;
			}
			sendNow(false);
		}
	}

	/** Must hold the lock. Sends one batch; with {@code force} it ignores a request already in flight. */
	private CompletableFuture<Void> sendNow(boolean force)
	{
		CompletableFuture<Void> done = new CompletableFuture<>();
		if (!isConfigured() || unauthorized || (inFlight && !force))
		{
			done.complete(null);
			return done;
		}
		Batch batch = takeBatch(force);
		if (batch == null)
		{
			done.complete(null);
			return done;
		}
		send(batch, done);
		return done;
	}

	private void sendHeartbeat()
	{
		Batch batch = new Batch(onlineAccount, Collections.emptyList());
		send(batch, new CompletableFuture<>());
	}

	/**
	 * Must hold the lock. Removes the next request's events for one account, oldest account first: up to
	 * {@link #MAX_EVENTS_PER_REQUEST} events, or one solo event alone.
	 */
	private Batch takeBatch(boolean finalFlush)
	{
		states.values().removeIf(p -> !p.resendUnchanged && p.dataJson.equals(lastSentState.get(p.stateKey)));

		String hash;
		if (!history.isEmpty())
		{
			hash = history.peekFirst().account.getHash();
		}
		else if (!states.isEmpty())
		{
			hash = states.values().iterator().next().account.getHash();
		}
		else
		{
			return null;
		}

		// Solo events (loot_history) go in a request of their own. The oldest pending event decides: a solo one is sent
		// alone, otherwise a batch of everything else, so neither can hold the other back for more than one request.
		// The final flush prefers the other events (e.g. logout); a solo snapshot left then is resent next session.
		Pending oldestSolo = null;
		long oldestOther = Long.MAX_VALUE;
		for (Pending p : history)
		{
			if (p.account.getHash().equals(hash))
			{
				if (p.solo)
				{
					oldestSolo = oldestSolo == null || p.seq < oldestSolo.seq ? p : oldestSolo;
				}
				else
				{
					oldestOther = Math.min(oldestOther, p.seq);
				}
			}
		}
		for (Pending p : states.values())
		{
			if (p.account.getHash().equals(hash))
			{
				if (p.solo)
				{
					oldestSolo = oldestSolo == null || p.seq < oldestSolo.seq ? p : oldestSolo;
				}
				else
				{
					oldestOther = Math.min(oldestOther, p.seq);
				}
			}
		}
		if (oldestSolo != null && (oldestOther == Long.MAX_VALUE || (!finalFlush && oldestSolo.seq < oldestOther)))
		{
			if (oldestSolo.stateKey != null)
			{
				states.remove(oldestSolo.stateKey);
			}
			else
			{
				history.remove(oldestSolo);
			}
			return new Batch(accountFor(hash, oldestSolo.account), Collections.singletonList(oldestSolo));
		}

		List<Pending> taken = new ArrayList<>();
		for (Iterator<Pending> it = history.iterator(); it.hasNext() && taken.size() < MAX_EVENTS_PER_REQUEST; )
		{
			Pending p = it.next();
			if (p.account.getHash().equals(hash) && !p.solo)
			{
				taken.add(p);
				it.remove();
			}
		}
		for (Iterator<Pending> it = states.values().iterator(); it.hasNext() && taken.size() < MAX_EVENTS_PER_REQUEST; )
		{
			Pending p = it.next();
			if (p.account.getHash().equals(hash) && !p.solo)
			{
				taken.add(p);
				it.remove();
			}
		}
		taken.sort(Comparator.comparingLong(p -> p.seq));
		return new Batch(accountFor(hash, taken.get(taken.size() - 1).account), taken);
	}

	/** Must hold the lock. The account details to send: the online account's newest ones when it is that account. */
	private AccountRef accountFor(String hash, AccountRef fallback)
	{
		return onlineAccount != null && onlineAccount.getHash().equals(hash) ? onlineAccount : fallback;
	}

	/** Must hold the lock. */
	private void send(Batch batch, CompletableFuture<Void> done)
	{
		List<IngestEvent> events = new ArrayList<>(batch.items.size());
		for (Pending p : batch.items)
		{
			events.add(p.event);
			if (p.stateKey != null)
			{
				inFlightStateKeys.add(p.stateKey);
			}
		}
		IngestRequest body = new IngestRequest(IngestRequest.PROTOCOL_VERSION, new ClientInfo(clientId, PLUGIN_VERSION), batch.account, events);
		Request request = new Request.Builder()
			.url(endpoint)
			.header("Authorization", "Bearer " + token)
			.header("X-Plugin-Version", PLUGIN_VERSION)
			.post(RequestBody.create(JSON, gson.toJson(body).getBytes(StandardCharsets.UTF_8)))
			.build();

		inFlight = true;
		lastRequestAt = time.nowMillis();
		Call call = http.newCall(request);
		call.timeout().timeout(CALL_TIMEOUT_S, TimeUnit.SECONDS);
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Group Companion request failed: {}", e.toString());
				onResult(batch, -1, null);
				done.complete(null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					String detail = null;
					if (r.code() >= 400 && r.code() < 500 && r.body() != null)
					{
						// The server's ApiError explains a rejection; kept short for the log.
						try
						{
							String text = r.body().string();
							detail = text.length() > 300 ? text.substring(0, 300) : text;
						}
						catch (IOException e)
						{
							detail = e.toString();
						}
					}
					onResult(batch, r.code(), detail);
				}
				finally
				{
					done.complete(null);
				}
			}
		});
	}

	private void onResult(Batch batch, int code, String detail)
	{
		boolean notifyUnauthorized = false;
		synchronized (this)
		{
			inFlight = false;
			for (Pending p : batch.items)
			{
				if (p.stateKey != null)
				{
					inFlightStateKeys.remove(p.stateKey);
				}
			}
			if (code >= 200 && code < 300)
			{
				for (Pending p : batch.items)
				{
					if (p.stateKey != null)
					{
						lastSentState.put(p.stateKey, p.dataJson);
					}
				}
				backoffMs = 0;
				retryAt = 0;
			}
			else if (code == 401)
			{
				requeue(batch);
				states.values().removeIf(p -> p.isVolatile);
				notifyUnauthorized = !unauthorized;
				unauthorized = true;
				log.warn("Group Companion: server rejected the token (401); sending stopped until the token or URL changes");
			}
			else if (code == -1 || code >= 500 || code == 408 || code == 429)
			{
				requeue(batch);
				backoffMs = backoffMs == 0 ? BACKOFF_MIN_MS : Math.min(backoffMs * 2, BACKOFF_MAX_MS);
				retryAt = time.nowMillis() + backoffMs;
				states.values().removeIf(p -> p.isVolatile);
				log.debug("Group Companion: server unreachable ({}), retrying in {} ms", code, backoffMs);
			}
			else
			{
				// 400, 413 and any other client error: the server will never accept these events.
				if (!loggedRejection)
				{
					loggedRejection = true;
					log.warn("Group Companion: server rejected {} event(s) ({}) with HTTP {}: {}; dropped",
						batch.items.size(), eventTypes(batch), code, detail);
				}
				backoffMs = 0;
				retryAt = 0;
			}

			if (running)
			{
				if (!history.isEmpty() || !states.isEmpty())
				{
					scheduleFlush(0);
				}
				scheduleHeartbeat();
			}
		}
		if (notifyUnauthorized && listener != null)
		{
			listener.onUnauthorized();
		}
	}

	/** Must hold the lock. Put a failed batch back in front, keeping the cap and newer snapshots. */
	private void requeue(Batch batch)
	{
		List<Pending> items = batch.items;
		for (int i = items.size() - 1; i >= 0; i--)
		{
			Pending p = items.get(i);
			if (p.stateKey == null)
			{
				history.addFirst(p);
			}
		}
		trimHistory();
		Map<String, Pending> merged = new LinkedHashMap<>();
		for (Pending p : items)
		{
			// Volatile snapshots (live status) are dropped rather than retried.
			if (p.stateKey != null && !p.isVolatile && !states.containsKey(p.stateKey))
			{
				merged.put(p.stateKey, p);
			}
		}
		merged.putAll(states);
		states.clear();
		states.putAll(merged);
	}

	// ------------------------------------------------------------------ heartbeat

	private void scheduleHeartbeat()
	{
		cancel(heartbeatFuture);
		heartbeatFuture = null;
		if (!running || onlineAccount == null)
		{
			return;
		}
		long wait = Math.max(lastRequestAt + HEARTBEAT_MS - time.nowMillis(), 1_000);
		heartbeatFuture = executor.schedule(this::onHeartbeatTimer, wait, TimeUnit.MILLISECONDS);
	}

	private void onHeartbeatTimer()
	{
		synchronized (this)
		{
			heartbeatFuture = null;
			if (!running || onlineAccount == null)
			{
				return;
			}
			long now = time.nowMillis();
			boolean idle = !inFlight && history.isEmpty() && states.isEmpty() && now >= retryAt;
			if (idle && isConfigured() && !unauthorized && now - lastRequestAt >= HEARTBEAT_MS)
			{
				sendHeartbeat();
			}
			else
			{
				scheduleHeartbeat();
			}
		}
	}

	private static String eventTypes(Batch batch)
	{
		java.util.Set<String> types = new java.util.TreeSet<>();
		for (Pending p : batch.items)
		{
			types.add(p.event.getType());
		}
		return String.join(",", types);
	}

	private static void cancel(ScheduledFuture<?> f)
	{
		if (f != null)
		{
			f.cancel(false);
		}
	}

	// ------------------------------------------------------------------ types

	private static final class Pending
	{
		final AccountRef account;
		final IngestEvent event;
		/** accountHash|stateKey for state events, null for history events. */
		final String stateKey;
		final String dataJson;
		final long seq;
		final boolean isVolatile;
		final boolean resendUnchanged;
		/** Sent in a request of its own ({@link EventType#isSolo()}). */
		final boolean solo;

		Pending(AccountRef account, IngestEvent event, String stateKey, String dataJson, long seq, boolean isVolatile,
			boolean resendUnchanged, boolean solo)
		{
			this.resendUnchanged = resendUnchanged;
			this.solo = solo;
			this.account = account;
			this.event = event;
			this.stateKey = stateKey;
			this.dataJson = dataJson;
			this.seq = seq;
			this.isVolatile = isVolatile;
		}
	}

	private static final class Batch
	{
		final AccountRef account;
		final List<Pending> items;

		Batch(AccountRef account, List<Pending> items)
		{
			this.account = account;
			this.items = items;
		}
	}
}
