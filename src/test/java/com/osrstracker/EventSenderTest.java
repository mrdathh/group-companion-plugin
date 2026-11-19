package com.osrstracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.osrstracker.model.AccountRef;
import com.osrstracker.model.ContainerData;
import com.osrstracker.model.EventType;
import com.osrstracker.model.IngestEvent;
import com.osrstracker.model.Item;
import com.osrstracker.model.LevelUpData;
import com.osrstracker.model.LootHistoryData;
import com.osrstracker.model.SkillsData;
import com.osrstracker.model.Location;
import com.osrstracker.model.StatusData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/**
 * EventSender against a real HTTP server (MockWebServer) with a fake clock and executor. OkHttp runs its
 * async calls inline so every request and its response handling complete inside {@link FakeScheduler#advance}.
 */
public class EventSenderTest
{
	private static final String TOKEN = "osrs_test-token";
	private static final AccountRef ACCOUNT = new AccountRef("123456789012345678", "Tester", "NORMAL", 301);
	private static final AccountRef OTHER = new AccountRef("987", "Alt", "IRONMAN", 302);

	private final MockWebServer server = new MockWebServer();
	private volatile MockResponse nextResponse = ok();
	private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());
	private final AtomicInteger unauthorizedCalls = new AtomicInteger();

	private FakeScheduler scheduler;
	private EventSender sender;
	private int seq;

	private static MockResponse ok()
	{
		return new MockResponse().setResponseCode(200).setBody("{\"accepted\":[],\"duplicates\":[]}");
	}

	private static MockResponse status(int code)
	{
		return new MockResponse().setResponseCode(code).setBody("{\"error\":\"x\",\"message\":\"y\"}");
	}

	@Before
	public void setUp() throws Exception
	{
		server.setDispatcher(new okhttp3.mockwebserver.Dispatcher()
		{
			@Override
			public MockResponse dispatch(RecordedRequest request)
			{
				requests.add(request);
				return nextResponse;
			}

			@Override
			public MockResponse peek()
			{
				return nextResponse;
			}
		});
		server.start();

		AbstractExecutorService inline = new AbstractExecutorService()
		{
			@Override
			public void execute(Runnable command)
			{
				command.run();
			}

			@Override
			public void shutdown()
			{
			}

			@Override
			public List<Runnable> shutdownNow()
			{
				return Collections.emptyList();
			}

			@Override
			public boolean isShutdown()
			{
				return false;
			}

			@Override
			public boolean isTerminated()
			{
				return false;
			}

			@Override
			public boolean awaitTermination(long timeout, TimeUnit unit)
			{
				return true;
			}
		};
		OkHttpClient http = new OkHttpClient.Builder().dispatcher(new Dispatcher(inline)).build();

		scheduler = new FakeScheduler();
		sender = new EventSender(http, RuneLiteAPI.GSON, scheduler, scheduler.time);
		sender.configure(server.url("/").toString(), TOKEN, "client-1");
		sender.start(unauthorizedCalls::incrementAndGet);
	}

	@After
	public void tearDown() throws Exception
	{
		server.shutdown();
	}

	// ------------------------------------------------------------------ helpers

	private IngestEvent history()
	{
		return IngestEvent.create(EventType.LEVEL_UP, new LevelUpData("Attack", ++seq), scheduler.time.now());
	}

	private IngestEvent skills(int attackXp)
	{
		return IngestEvent.create(EventType.SKILLS, new SkillsData(Collections.singletonMap("Attack",
			new SkillsData.SkillValue(attackXp, 1)), 1, attackXp, 3), scheduler.time.now());
	}

	private IngestEvent container(String name)
	{
		return IngestEvent.create(EventType.CONTAINER, new ContainerData(name, Collections.emptyList()), scheduler.time.now());
	}

	private int requestCount()
	{
		return requests.size();
	}

	private JsonObject body(int index)
	{
		return new JsonParser().parse(requests.get(index).getBody().clone().readUtf8()).getAsJsonObject();
	}

	private JsonArray events(int index)
	{
		return body(index).getAsJsonArray("events");
	}

	// ------------------------------------------------------------------ batching and headers

	@Test
	public void batchesPendingEventsIntoOneRequestWithContractHeaders()
	{
		sender.enqueue(ACCOUNT, history());
		sender.enqueue(ACCOUNT, history());
		sender.enqueue(ACCOUNT, skills(100));
		assertEquals(0, requestCount());

		scheduler.advance(EventSender.FLUSH_DELAY_MS);

		assertEquals(1, requestCount());
		RecordedRequest r = requests.get(0);
		assertEquals("POST", r.getMethod());
		assertEquals("/api/ingest", r.getPath());
		assertEquals("Bearer " + TOKEN, r.getHeader("Authorization"));
		assertEquals(EventSender.PLUGIN_VERSION, r.getHeader("X-Plugin-Version"));
		assertEquals("application/json", r.getHeader("Content-Type"));

		JsonObject body = body(0);
		assertEquals(1, body.get("v").getAsInt());
		assertEquals("client-1", body.getAsJsonObject("client").get("id").getAsString());
		assertEquals("0.1.0", body.getAsJsonObject("client").get("plugin").getAsString());
		assertEquals("123456789012345678", body.getAsJsonObject("account").get("hash").getAsString());
		assertTrue(body.getAsJsonObject("account").get("hash").getAsJsonPrimitive().isString());
		JsonArray events = events(0);
		assertEquals(3, events.size());
		assertEquals("level_up", events.get(0).getAsJsonObject().get("type").getAsString());
		assertEquals("skills", events.get(2).getAsJsonObject().get("type").getAsString());
		assertEquals(0, sender.pendingHistory());
		assertEquals(0, sender.pendingStates());
	}

	@Test
	public void historyEventLeavesWellWithinOneSecond()
	{
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(100);
		assertEquals(1, requestCount());
	}

	@Test
	public void serverUrlKeepsItsPath()
	{
		assertEquals("http://127.0.0.1:8787/api/ingest", EventSender.parseEndpoint("http://127.0.0.1:8787").toString());
		assertEquals("http://127.0.0.1:8787/api/ingest", EventSender.parseEndpoint(" http://127.0.0.1:8787/ ").toString());
		assertEquals("https://example.org/relay/api/ingest", EventSender.parseEndpoint("https://example.org/relay/").toString());
		assertNull(EventSender.parseEndpoint("not a url"));
		assertNull(EventSender.parseEndpoint(""));
	}

	@Test
	public void sendsNothingUntilUrlAndTokenAreSet()
	{
		sender.configure(server.url("/").toString(), "  ", "client-1");
		sender.enqueue(ACCOUNT, history());
		sender.configure("", TOKEN, "client-1");
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(120_000);
		assertEquals(0, requestCount());
		assertEquals(0, sender.pendingHistory());
	}

	@Test
	public void oneRequestPerAccount()
	{
		sender.enqueue(ACCOUNT, history());
		sender.enqueue(OTHER, history());
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals(2, requestCount());
		assertEquals(ACCOUNT.getHash(), body(0).getAsJsonObject("account").get("hash").getAsString());
		assertEquals(2, events(0).size());
		assertEquals(OTHER.getHash(), body(1).getAsJsonObject("account").get("hash").getAsString());
		assertEquals(1, events(1).size());
	}

	// ------------------------------------------------------------------ status handling

	@Test
	public void status200DropsTheEvents()
	{
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		scheduler.advance(300_000);
		assertEquals(1, requestCount());
		assertEquals(0, sender.pendingHistory());
	}

	@Test
	public void status400DropsTheEventsWithoutRetry()
	{
		nextResponse = status(400);
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		nextResponse = ok();
		scheduler.advance(300_000);
		assertEquals(1, requestCount());
		assertEquals(0, sender.pendingHistory());

		// Later events are still sent.
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals(2, requestCount());
	}

	@Test
	public void status413DropsTheEvents()
	{
		nextResponse = new MockResponse().setResponseCode(413);
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		scheduler.advance(300_000);
		assertEquals(1, requestCount());
		assertEquals(0, sender.pendingHistory());
	}

	@Test
	public void status401StopsSendingAndWarnsOnceUntilTheTokenChanges()
	{
		nextResponse = status(401);
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals(1, requestCount());
		assertEquals(1, unauthorizedCalls.get());

		sender.enqueue(ACCOUNT, history());
		sender.setOnlineAccount(ACCOUNT);
		scheduler.advance(600_000);
		assertEquals("no requests, not even heartbeats, after a 401", 1, requestCount());
		assertEquals(1, unauthorizedCalls.get());
		assertEquals(2, sender.pendingHistory());

		nextResponse = ok();
		sender.configure(server.url("/").toString(), "osrs_new-token", "client-1");
		scheduler.advance(1);
		assertEquals(2, requestCount());
		assertEquals("Bearer osrs_new-token", requests.get(1).getHeader("Authorization"));
		assertEquals(2, events(1).size());
		assertEquals(0, sender.pendingHistory());
	}

	@Test
	public void joinCodeIsSentAsAHeaderOnlyWhileSet()
	{
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertNull(requests.get(0).getHeader("X-Join-Code"));

		assertFalse("a join code is not a new destination",
			sender.configure(server.url("/").toString(), TOKEN, "client-1", true, " ABCD-EFGH-JKMN "));
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals("ABCD-EFGH-JKMN", requests.get(1).getHeader("X-Join-Code"));
		assertEquals("Bearer " + TOKEN, requests.get(1).getHeader("Authorization"));
		// Also after the server accepted the token (always sent while set; the server ignores it then).
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals("ABCD-EFGH-JKMN", requests.get(2).getHeader("X-Join-Code"));

		sender.configure(server.url("/").toString(), TOKEN, "client-1", true, "");
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertNull(requests.get(3).getHeader("X-Join-Code"));
	}

	@Test
	public void aWrongJoinCodeStopsSendingUntilTheCodeChanges()
	{
		sender.configure(server.url("/").toString(), TOKEN, "client-1", true, "WRONG-CODE");
		nextResponse = status(401);
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals(1, requestCount());
		assertEquals(1, unauthorizedCalls.get());
		scheduler.advance(600_000);
		assertEquals(1, requestCount());

		nextResponse = ok();
		assertFalse(sender.configure(server.url("/").toString(), TOKEN, "client-1", true, "RIGHT-CODE"));
		scheduler.advance(1);
		assertEquals(2, requestCount());
		assertEquals("RIGHT-CODE", requests.get(1).getHeader("X-Join-Code"));
		assertEquals("the kept event goes out", 1, events(1).size());
	}

	@Test
	public void status403WhileJoiningStopsSendingLikeA401()
	{
		AtomicInteger refused = new AtomicInteger();
		sender.stop();
		sender.configure(server.url("/").toString(), TOKEN, "client-1", true, "ABCD-EFGH");
		sender.start(new EventSender.Listener()
		{
			@Override
			public void onUnauthorized()
			{
				unauthorizedCalls.incrementAndGet();
			}

			@Override
			public void onJoinRefused()
			{
				refused.incrementAndGet();
			}
		});
		nextResponse = status(403);
		sender.enqueue(ACCOUNT, history());
		sender.setOnlineAccount(ACCOUNT);
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		scheduler.advance(600_000);
		assertEquals("no retries and no heartbeats", 1, requestCount());
		assertEquals(1, refused.get());
		assertEquals(0, unauthorizedCalls.get());
		assertEquals(1, sender.pendingHistory());
	}

	@Test
	public void status5xxRetriesWithBackoffFrom5sUpTo60s()
	{
		nextResponse = status(503);
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals(1, requestCount());

		long[] expectedWaits = {5_000, 10_000, 20_000, 40_000, 60_000, 60_000};
		int expected = 1;
		for (long wait : expectedWaits)
		{
			scheduler.advance(wait - 1);
			assertEquals("no retry before " + wait + " ms", expected, requestCount());
			scheduler.advance(1);
			expected++;
			assertEquals("retry after " + wait + " ms", expected, requestCount());
		}
		assertEquals(1, sender.pendingHistory());

		nextResponse = ok();
		scheduler.advance(60_000);
		assertEquals(expected + 1, requestCount());
		assertEquals(0, sender.pendingHistory());

		// Backoff resets after a success.
		nextResponse = status(500);
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		int before = requestCount();
		scheduler.advance(5_000);
		assertEquals(before + 1, requestCount());
	}

	@Test
	public void newEventsWaitForTheRetryTimerDuringBackoff()
	{
		nextResponse = status(502);
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(1_000);
		assertEquals(1, requestCount());
		nextResponse = ok();
		scheduler.advance(4_000);
		assertEquals(2, requestCount());
		assertEquals(2, events(1).size());
	}

	@Test
	public void networkFailureIsRetried()
	{
		nextResponse = new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START);
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals(1, sender.pendingHistory());

		nextResponse = ok();
		scheduler.advance(EventSender.BACKOFF_MIN_MS);
		assertEquals(0, sender.pendingHistory());
		assertEquals(1, events(requestCount() - 1).size());
	}

	@Test
	public void keepsAtMost500HistoryEventsDroppingTheOldest()
	{
		nextResponse = status(503);
		List<String> ids = new ArrayList<>();
		IngestEvent first = history();
		ids.add(first.getId());
		sender.enqueue(ACCOUNT, first);
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		for (int i = 1; i < 600; i++)
		{
			IngestEvent e = history();
			ids.add(e.getId());
			sender.enqueue(ACCOUNT, e);
		}
		assertEquals(EventSender.MAX_HISTORY, sender.pendingHistory());

		nextResponse = ok();
		scheduler.advance(EventSender.BACKOFF_MIN_MS);
		List<String> sent = new ArrayList<>();
		for (int r = 1; r < requestCount(); r++)
		{
			for (int i = 0; i < events(r).size(); i++)
			{
				sent.add(events(r).get(i).getAsJsonObject().get("id").getAsString());
			}
		}
		assertEquals(ids.subList(100, 600), sent);
		assertEquals(0, sender.pendingHistory());
	}

	// ------------------------------------------------------------------ state snapshots

	@Test
	public void onlyTheNewestSnapshotPerStateKeyIsSent()
	{
		sender.enqueue(ACCOUNT, skills(1));
		sender.enqueue(ACCOUNT, container("inventory"));
		sender.enqueue(ACCOUNT, skills(2));
		sender.enqueue(ACCOUNT, container("bank"));
		scheduler.advance(EventSender.FLUSH_DELAY_MS);

		assertEquals(1, requestCount());
		JsonArray events = events(0);
		assertEquals(3, events.size());
		int skillsEvents = 0;
		for (int i = 0; i < events.size(); i++)
		{
			JsonObject e = events.get(i).getAsJsonObject();
			if (e.get("type").getAsString().equals("skills"))
			{
				skillsEvents++;
				assertEquals(2, e.getAsJsonObject("data").getAsJsonObject("skills").getAsJsonObject("Attack").get("xp").getAsInt());
			}
		}
		assertEquals(1, skillsEvents);
	}

	@Test
	public void newestSnapshotWinsOverOneQueuedForRetry()
	{
		nextResponse = status(503);
		sender.enqueue(ACCOUNT, skills(1));
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		sender.enqueue(ACCOUNT, skills(2));
		assertEquals(1, sender.pendingStates());

		nextResponse = ok();
		scheduler.advance(EventSender.BACKOFF_MIN_MS);
		JsonArray events = events(requestCount() - 1);
		assertEquals(1, events.size());
		assertEquals(2, events.get(0).getAsJsonObject().getAsJsonObject("data").get("totalXp").getAsInt());
	}

	@Test
	public void unchangedSnapshotIsNeverResent()
	{
		sender.enqueue(ACCOUNT, skills(5));
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals(1, requestCount());

		sender.enqueue(ACCOUNT, skills(5));
		scheduler.advance(120_000);
		assertEquals(1, requestCount());
		assertEquals(0, sender.pendingStates());

		sender.enqueue(ACCOUNT, skills(6));
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals(2, requestCount());

		// Back to the first content: changed relative to the last send, so it goes.
		sender.enqueue(ACCOUNT, skills(5));
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals(3, requestCount());
	}

	@Test
	public void snapshotThatFailedIsNotTreatedAsSent()
	{
		nextResponse = status(400);
		sender.enqueue(ACCOUNT, skills(5));
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		nextResponse = ok();
		sender.enqueue(ACCOUNT, skills(5));
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals(2, requestCount());
	}

	@Test
	public void pendingChangeRevertedToTheSentContentIsDropped()
	{
		sender.enqueue(ACCOUNT, skills(5));
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		sender.enqueue(ACCOUNT, skills(6));
		sender.enqueue(ACCOUNT, skills(5));
		scheduler.advance(120_000);
		assertEquals(1, requestCount());
	}

	@Test
	public void sameSnapshotPerAccountIsTrackedSeparately()
	{
		sender.enqueue(ACCOUNT, skills(5));
		sender.enqueue(OTHER, skills(5));
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals(2, requestCount());
	}

	// ------------------------------------------------------------------ live status (volatile)

	private IngestEvent statusEvent(int hp)
	{
		return IngestEvent.create(EventType.STATUS, new StatusData(new StatusData.CurMax(hp, 99), new StatusData.CurMax(1, 1),
			100, 100, 301, new Location(3222, 3218, 0, 12850, false, false, null), null), scheduler.time.now());
	}

	@Test
	public void statusIsNotQueuedWhileTheServerIsUnreachable()
	{
		nextResponse = status(503);
		sender.enqueue(ACCOUNT, history());
		sender.enqueue(ACCOUNT, statusEvent(50));
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals(1, requestCount());
		assertEquals("failed status is dropped, history kept", 0, sender.pendingStates());
		assertEquals(1, sender.pendingHistory());

		sender.enqueue(ACCOUNT, statusEvent(51));
		assertEquals("status offered during backoff is dropped", 0, sender.pendingStates());
		sender.enqueue(ACCOUNT, skills(1));
		assertEquals("other snapshots are still kept", 1, sender.pendingStates());

		nextResponse = ok();
		scheduler.advance(EventSender.BACKOFF_MIN_MS);
		JsonArray retried = events(requestCount() - 1);
		for (int i = 0; i < retried.size(); i++)
		{
			assertTrue(!"status".equals(retried.get(i).getAsJsonObject().get("type").getAsString()));
		}

		// Back online: status flows again.
		sender.enqueue(ACCOUNT, statusEvent(52));
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals("status", events(requestCount() - 1).get(0).getAsJsonObject().get("type").getAsString());
	}

	@Test
	public void statusIsNotQueuedAfterA401()
	{
		nextResponse = status(401);
		sender.enqueue(ACCOUNT, statusEvent(50));
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		sender.enqueue(ACCOUNT, statusEvent(51));
		assertEquals(0, sender.pendingStates());
	}

	@Test
	public void unchangedStatusIsDroppedUnlessARefreshIsRequested()
	{
		sender.enqueue(ACCOUNT, statusEvent(50));
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals(1, requestCount());

		sender.enqueue(ACCOUNT, statusEvent(50));
		scheduler.advance(5_000);
		assertEquals("unchanged status not resent", 1, requestCount());

		sender.enqueue(ACCOUNT, statusEvent(50), true);
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals("periodic refresh is sent although unchanged", 2, requestCount());
		assertEquals("status", events(1).get(0).getAsJsonObject().get("type").getAsString());
	}

	// ------------------------------------------------------------------ heartbeat

	@Test
	public void heartbeatEvery60sWhileOnlineWithNothingElseSent()
	{
		sender.setOnlineAccount(ACCOUNT);
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals(1, requestCount());

		scheduler.advance(EventSender.HEARTBEAT_MS - 1_000);
		assertEquals(1, requestCount());
		scheduler.advance(1_000);
		assertEquals(2, requestCount());
		assertEquals(0, events(1).size());
		assertEquals(ACCOUNT.getHash(), body(1).getAsJsonObject("account").get("hash").getAsString());

		// Something else sent at +30 s pushes the next heartbeat to +90 s.
		scheduler.advance(30_000);
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals(3, requestCount());
		scheduler.advance(EventSender.HEARTBEAT_MS - 1_000);
		assertEquals(3, requestCount());
		scheduler.advance(1_000);
		assertEquals(4, requestCount());
		assertEquals(0, events(3).size());
	}

	@Test
	public void heartbeatCarriesTheCurrentWorldAndStopsAfterLogout()
	{
		sender.setOnlineAccount(ACCOUNT);
		scheduler.advance(EventSender.HEARTBEAT_MS);
		int afterFirst = requestCount();
		assertTrue(afterFirst >= 1);

		AccountRef hopped = new AccountRef(ACCOUNT.getHash(), ACCOUNT.getRsn(), ACCOUNT.getType(), 420);
		sender.setOnlineAccount(hopped);
		scheduler.advance(EventSender.HEARTBEAT_MS);
		assertEquals(420, body(requestCount() - 1).getAsJsonObject("account").get("world").getAsInt());

		sender.setOnlineAccount(null);
		int count = requestCount();
		scheduler.advance(10 * EventSender.HEARTBEAT_MS);
		assertEquals(count, requestCount());
	}

	@Test
	public void requestUsesTheNewestAccountDetails()
	{
		AccountRef hopped = new AccountRef(ACCOUNT.getHash(), ACCOUNT.getRsn(), ACCOUNT.getType(), 420);
		sender.enqueue(ACCOUNT, history());
		sender.setOnlineAccount(hopped);
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals(420, body(0).getAsJsonObject("account").get("world").getAsInt());
	}

	// ------------------------------------------------------------------ loot_history travels alone

	private IngestEvent lootHistory(int kills)
	{
		return IngestEvent.create(EventType.LOOT_HISTORY, new LootHistoryData(Collections.singletonList(
			new LootHistoryData.Source("NPC", "Vorkath", kills, null, null, Collections.singletonList(
				new Item(536, 2, "Dragon bones", 2000, 0))))), scheduler.time.now());
	}

	private List<String> types(int request)
	{
		List<String> out = new ArrayList<>();
		for (int i = 0; i < events(request).size(); i++)
		{
			out.add(events(request).get(i).getAsJsonObject().get("type").getAsString());
		}
		return out;
	}

	@Test
	public void lootHistoryIsSentInARequestOfItsOwn()
	{
		sender.enqueue(ACCOUNT, history());
		sender.enqueue(ACCOUNT, skills(100));
		sender.enqueue(ACCOUNT, lootHistory(1));
		sender.enqueue(ACCOUNT, history());
		sender.enqueue(ACCOUNT, container("inventory"));
		scheduler.advance(EventSender.FLUSH_DELAY_MS);

		assertEquals(2, requestCount());
		// Everything else is still batched together, in order.
		assertEquals(java.util.Arrays.asList("level_up", "skills", "level_up", "container"), types(0));
		assertEquals(Collections.singletonList("loot_history"), types(1));
		assertEquals(ACCOUNT.getHash(), body(1).getAsJsonObject("account").get("hash").getAsString());
		assertEquals(0, sender.pendingHistory());
		assertEquals(0, sender.pendingStates());
	}

	@Test
	public void lootHistoryQueuedFirstGoesFirstAndAlone()
	{
		sender.enqueue(ACCOUNT, lootHistory(1));
		sender.enqueue(ACCOUNT, history());
		sender.enqueue(ACCOUNT, skills(100));
		scheduler.advance(EventSender.FLUSH_DELAY_MS);

		assertEquals(2, requestCount());
		assertEquals(Collections.singletonList("loot_history"), types(0));
		assertEquals(java.util.Arrays.asList("level_up", "skills"), types(1));
	}

	@Test
	public void lootHistoryIsSentAloneBetweenOtherRequests()
	{
		sender.enqueue(ACCOUNT, history());
		sender.enqueue(ACCOUNT, lootHistory(1));
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		// More events arrive later: the loot history is never batched with them.
		sender.enqueue(ACCOUNT, history());
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		boolean sentAlone = false;
		for (int i = 0; i < requestCount(); i++)
		{
			List<String> t = types(i);
			if (t.contains("loot_history"))
			{
				assertEquals(Collections.singletonList("loot_history"), t);
				sentAlone = true;
			}
		}
		assertTrue(sentAlone);
		assertEquals(0, sender.pendingStates());
		assertEquals(0, sender.pendingHistory());
	}

	@Test
	public void lootHistoryRetriedAfterAFailureIsStillAlone()
	{
		nextResponse = status(503);
		sender.enqueue(ACCOUNT, lootHistory(1));
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals(1, requestCount());
		assertEquals(1, sender.pendingStates());

		sender.enqueue(ACCOUNT, history());
		nextResponse = ok();
		scheduler.advance(EventSender.BACKOFF_MIN_MS);
		assertEquals(3, requestCount());
		assertEquals(Collections.singletonList("loot_history"), types(1));
		assertEquals(Collections.singletonList("level_up"), types(2));
	}

	@Test
	public void unchangedLootHistoryIsNotResent()
	{
		sender.enqueue(ACCOUNT, lootHistory(1));
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		sender.enqueue(ACCOUNT, lootHistory(1));
		scheduler.advance(EventSender.FLUSH_DELAY_MS);
		assertEquals(1, requestCount());
	}

	@Test
	public void finalFlushPrefersTheOtherEvents() throws Exception
	{
		sender.enqueue(ACCOUNT, lootHistory(1));
		sender.enqueue(ACCOUNT, history());
		sender.stop().get(5, TimeUnit.SECONDS);
		assertEquals(1, requestCount());
		assertEquals(Collections.singletonList("level_up"), types(0));
	}

	@Test
	public void finalFlushSendsALoneLootHistory() throws Exception
	{
		sender.enqueue(ACCOUNT, lootHistory(1));
		sender.stop().get(5, TimeUnit.SECONDS);
		assertEquals(1, requestCount());
		assertEquals(Collections.singletonList("loot_history"), types(0));
	}

	// ------------------------------------------------------------------ shutdown

	@Test
	public void stopFlushesImmediatelyAndCancelsTimers() throws Exception
	{
		sender.setOnlineAccount(ACCOUNT);
		sender.enqueue(ACCOUNT, history());
		CompletableFuture<Void> done = sender.stop();
		done.get(5, TimeUnit.SECONDS);
		assertEquals(1, requestCount());

		sender.enqueue(ACCOUNT, history());
		scheduler.advance(10 * EventSender.HEARTBEAT_MS);
		assertEquals(1, requestCount());
	}
}
