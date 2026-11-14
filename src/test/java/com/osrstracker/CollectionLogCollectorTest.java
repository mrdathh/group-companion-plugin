package com.osrstracker;

import com.osrstracker.model.ClogItemData;
import com.osrstracker.model.CollectionLogData;
import com.osrstracker.model.CollectionLogSummaryData;
import com.osrstracker.model.EventType;
import java.time.LocalDate;
import java.util.List;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.ScriptEvent;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Whole-log read (capture whoever opened the search, never toggling it off, adventure log, limits), new items from
 * chat and popup, and the login summary.
 */
public class CollectionLogCollectorTest
{
	private final Client client = mock(Client.class);
	private final SessionTracker session = mock(SessionTracker.class);
	private final ItemMapper items = mock(ItemMapper.class);
	private final FakeScheduler scheduler = new FakeScheduler();
	private CollectionLogCollector collector;
	private int tick;

	@Before
	public void setUp()
	{
		TrackerSyncConfig config = mock(TrackerSyncConfig.class);
		when(config.sendCollectionLog()).thenReturn(true);
		when(session.isActive()).thenReturn(true);
		when(client.getTickCount()).thenAnswer(inv -> tick);
		Widget content = mock(Widget.class);
		when(client.getWidget(InterfaceID.Collection.CONTENT)).thenReturn(content);
		when(items.nameOf(anyInt())).thenAnswer(inv -> "Item " + inv.getArgument(0));
		when(items.recentIdForName(anyString())).thenReturn(null);
		ClientThread clientThread = mock(ClientThread.class);
		doAnswer(inv ->
		{
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(clientThread).invoke(any(Runnable.class));
		collector = new CollectionLogCollector(client, clientThread, session, config, items, scheduler, scheduler.time);
	}

	// ------------------------------------------------------------------ helpers

	private void script(int id, Object... args)
	{
		ScriptPreFired e = new ScriptPreFired(id);
		if (args.length > 0)
		{
			ScriptEvent se = mock(ScriptEvent.class);
			when(se.getArguments()).thenReturn(args);
			e.setScriptEvent(se);
		}
		collector.onScriptPreFired(e);
	}

	private void open()
	{
		script(CollectionLogCollector.SCRIPT_COLLECTION_SETUP);
	}

	private void draw(int itemId, int qty)
	{
		script(CollectionLogCollector.SCRIPT_COLLECTION_DRAW_ITEM, 4100, itemId, qty);
	}

	private void nextTick()
	{
		tick++;
		collector.onGameTick(new GameTick());
	}

	private void verifySearches(int times)
	{
		verify(client, times(times)).menuAction(eq(-1), eq(InterfaceID.Collection.SEARCH_TOGGLE), eq(MenuAction.CC_OP),
			eq(1), eq(-1), eq("Search"), any());
		verify(client, times(times)).runScript(CollectionLogCollector.SCRIPT_COLLECTION_SEARCH);
	}

	/** When the next search is opened by this plugin, the game draws these items (id, qty pairs). */
	private void searchDraws(int... idQty)
	{
		doAnswer(inv ->
		{
			for (int i = 0; i < idQty.length; i += 2)
			{
				draw(idQty[i], idQty[i + 1]);
			}
			return null;
		}).when(client).runScript(CollectionLogCollector.SCRIPT_COLLECTION_SEARCH);
	}

	private List<Object> emitted(EventType type, int times)
	{
		ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
		verify(session, times(times)).emit(eq(type), data.capture());
		return data.getAllValues();
	}

	// ------------------------------------------------------------------ full read

	@Test
	public void ownSearchIsOpenedOnTheNextTickAndTheItemsAreSent()
	{
		tick = 100;
		searchDraws(4151, 1, 12921, 2, 11840, 0);
		open();
		verifySearches(0); // not before other plugins had their turn
		nextTick();
		verifySearches(1);
		nextTick(); // draws stopped for a tick
		CollectionLogData d = (CollectionLogData) emitted(EventType.COLLECTION_LOG, 1).get(0);
		assertEquals(2, d.getItems().size());
		assertEquals(Integer.valueOf(2), d.getItems().get("12921"));
		assertEquals("Item 4151", d.getNames().get("4151"));
	}

	@Test
	public void itemsDrawnBeforeOurTurnAreCapturedWithoutAnotherClick()
	{
		// Another plugin (e.g. Group Ironmen Tracker) opens the search while the log is being set up: its draws
		// arrive after the set-up script started but before this plugin would click.
		when(client.getVarpValue(VarPlayerID.COLLECTION_COUNT)).thenReturn(3);
		when(client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX)).thenReturn(1700);
		tick = 50;
		open();
		draw(4151, 1);
		draw(12921, 1);
		draw(11840, 4);
		draw(20000, 0);
		when(client.getVarbitValue(VarbitID.FLOATER_IS_SEARCHING)).thenReturn(1);
		nextTick();
		verifySearches(0);
		CollectionLogData d = (CollectionLogData) emitted(EventType.COLLECTION_LOG, 1).get(0);
		assertEquals(3, d.getItems().size());
		assertEquals(3, d.getObtained());
		assertEquals(1700, d.getTotal());
	}

	@Test
	public void searchAlreadyOnIsNotToggledOff()
	{
		when(client.getVarbitValue(VarbitID.FLOATER_IS_SEARCHING)).thenReturn(1);
		tick = 10;
		open();
		nextTick();
		verifySearches(0);
		// Its items arrive a little later: still captured.
		draw(4151, 1);
		nextTick();
		assertEquals(1, emitted(EventType.COLLECTION_LOG, 1).size());
	}

	@Test
	public void nothingDrawnGivesUpAfterTheTimeout()
	{
		tick = 10;
		open();
		for (int i = 0; i <= CollectionLogCollector.READ_TIMEOUT_TICKS + 1; i++)
		{
			nextTick();
		}
		draw(4151, 1); // no longer reading
		nextTick();
		verify(session, never()).emit(eq(EventType.COLLECTION_LOG), any());
	}

	@Test
	public void aSinglePageIsNotTakenForTheWholeLog()
	{
		when(client.getVarpValue(VarPlayerID.COLLECTION_COUNT)).thenReturn(300);
		tick = 10;
		open();
		draw(4151, 1);
		nextTick();
		verify(session, never()).emit(eq(EventType.COLLECTION_LOG), any());
	}

	@Test
	public void houseAdventureLogIsNeverRead()
	{
		when(client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN)).thenReturn(1);
		tick = 10;
		open();
		draw(4151, 1);
		nextTick();
		nextTick();
		verify(client, never()).menuAction(anyInt(), anyInt(), any(), anyInt(), anyInt(), anyString(), any());
		verify(client, never()).runScript(any());
		verify(session, never()).emit(eq(EventType.COLLECTION_LOG), any());

		// The player's own log afterwards is read.
		when(client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN)).thenReturn(0);
		searchDraws(4151, 1);
		open();
		nextTick();
		nextTick();
		verifySearches(1);
		emitted(EventType.COLLECTION_LOG, 1);
	}

	@Test
	public void adventureLogOpeningMidReadDropsTheRead()
	{
		tick = 10;
		open();
		draw(4151, 1);
		when(client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN)).thenReturn(1);
		draw(12921, 1);
		nextTick();
		verify(session, never()).emit(eq(EventType.COLLECTION_LOG), any());
	}

	@Test
	public void firstReadIsNotLimitedThenOncePer500Ticks()
	{
		searchDraws(4151, 1);
		tick = 1000;
		// A failed first attempt (nothing drawn) does not start the limit.
		doAnswer(inv -> null).when(client).runScript(CollectionLogCollector.SCRIPT_COLLECTION_SEARCH);
		open();
		for (int i = 0; i <= CollectionLogCollector.READ_TIMEOUT_TICKS + 1; i++)
		{
			nextTick();
		}
		searchDraws(4151, 1);
		open();
		nextTick();
		nextTick();
		emitted(EventType.COLLECTION_LOG, 1);
		int readAt = tick;

		tick = readAt + CollectionLogCollector.MIN_TICKS_BETWEEN_READS - 1;
		open();
		nextTick();
		nextTick();
		emitted(EventType.COLLECTION_LOG, 1);

		tick = readAt + CollectionLogCollector.MIN_TICKS_BETWEEN_READS;
		open();
		nextTick();
		nextTick();
		emitted(EventType.COLLECTION_LOG, 2);
	}

	@Test
	public void logoutResetsTheLimit()
	{
		searchDraws(4151, 1);
		tick = 10;
		open();
		nextTick();
		nextTick();
		GameStateChanged loginScreen = new GameStateChanged();
		loginScreen.setGameState(GameState.LOGIN_SCREEN);
		collector.onGameStateChanged(loginScreen);
		tick = 20;
		open();
		nextTick();
		nextTick();
		emitted(EventType.COLLECTION_LOG, 2);
	}

	// ------------------------------------------------------------------ new items

	@Test
	public void popupTextIsParsed()
	{
		assertEquals("Pet snakeling", CollectionLogCollector.parsePopup("Collection log", "New item:<br><br><col=ffffff>Pet snakeling</col>"));
		assertEquals("Abyssal whip", CollectionLogCollector.parsePopup("<col=ff981f>Collection log</col>", "New item:Abyssal whip"));
		assertNull(CollectionLogCollector.parsePopup("Combat Task Completed!", "New item: Abyssal whip"));
		assertNull(CollectionLogCollector.parsePopup("Collection log", "Something else"));
		assertNull(CollectionLogCollector.parsePopup(null, null));
	}

	@Test
	public void popupSendsTheItem()
	{
		when(client.getVarcStrValue(VarClientID.NOTIFICATION_TITLE)).thenReturn("Collection log");
		when(client.getVarcStrValue(VarClientID.NOTIFICATION_MAIN)).thenReturn("New item:<br>Pet snakeling");
		when(items.recentIdForName("Pet snakeling")).thenReturn(12921);
		script(CollectionLogCollector.SCRIPT_NOTIFICATION_DELAY); // no start seen: ignored
		nextTick();
		verify(session, never()).emit(eq(EventType.CLOG_ITEM), any());

		script(CollectionLogCollector.SCRIPT_NOTIFICATION_START);
		script(CollectionLogCollector.SCRIPT_NOTIFICATION_DELAY);
		nextTick();
		ClogItemData d = (ClogItemData) emitted(EventType.CLOG_ITEM, 1).get(0);
		assertEquals("Pet snakeling", d.getName());
		assertEquals(Integer.valueOf(12921), d.getId());
	}

	@Test
	public void chatAndPopupForTheSameItemSendOneEventWithNameOnlyWhenNoIdIsKnown()
	{
		tick = 500;
		collector.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "",
			"New item added to your collection log: <col=ef1020>Tiny tempor</col>", null, 0));
		nextTick();
		tick += 10; // the popup waited behind another popup
		when(client.getVarcStrValue(VarClientID.NOTIFICATION_TITLE)).thenReturn("Collection log");
		when(client.getVarcStrValue(VarClientID.NOTIFICATION_MAIN)).thenReturn("New item:<br>Tiny tempor");
		script(CollectionLogCollector.SCRIPT_NOTIFICATION_START);
		script(CollectionLogCollector.SCRIPT_NOTIFICATION_DELAY);
		nextTick();
		ClogItemData d = (ClogItemData) emitted(EventType.CLOG_ITEM, 1).get(0);
		assertEquals("Tiny tempor", d.getName());
		assertNull(d.getId());

		// A different item is not deduplicated; the same name long after is (in theory) a new report.
		collector.addNewItem("Big swordfish", "chat");
		tick += CollectionLogCollector.NEW_ITEM_DEDUPE_TICKS + 1;
		collector.addNewItem("Tiny tempor", "chat");
		nextTick();
		emitted(EventType.CLOG_ITEM, 3);
	}

	// ------------------------------------------------------------------ summary

	@Test
	public void summaryIsBuiltFromTheVars()
	{
		int[] tabs = {120, 500, 10, 200, 30, 600, 5, 300, 20, 0};
		int[] ids = new int[12];
		int[] days = new int[12];
		java.util.Arrays.fill(ids, -1);
		ids[0] = 12921;
		days[0] = 9000;
		ids[1] = 4151;
		days[1] = 8990;
		ids[2] = 11840;
		days[2] = 0;
		CollectionLogSummaryData d = CollectionLogCollector.buildSummary(185, 1700, tabs, ids, days, 9001,
			LocalDate.of(2026, 11, 15), 3);
		assertEquals(185, d.getObtained());
		assertEquals(1700, d.getTotal());
		assertEquals(4, d.getTabs().size()); // "Other" has no total yet
		assertEquals("Bosses", d.getTabs().get(0).getTab());
		assertEquals(120, d.getTabs().get(0).getObtained());
		assertEquals(500, d.getTabs().get(0).getTotal());
		assertEquals("Minigames", d.getTabs().get(3).getTab());
		assertEquals(3, d.getRecent().size());
		assertEquals(12921, d.getRecent().get(0).getItemId());
		assertEquals("2026-11-14T00:00:00.000Z", d.getRecent().get(0).getAt());
		assertEquals("2026-11-04T00:00:00.000Z", d.getRecent().get(1).getAt());
		assertNull(d.getRecent().get(2).getAt());
		assertEquals("both", d.getNewItemNotification());

		// Unknown current day: no dates.
		CollectionLogSummaryData noDay = CollectionLogCollector.buildSummary(185, 1700, tabs, ids, days, 0,
			LocalDate.of(2026, 11, 15), 1);
		assertNull(noDay.getRecent().get(0).getAt());
	}

	@Test
	public void summaryIsNotBuiltBeforeTheCountsLoad()
	{
		assertNull(CollectionLogCollector.buildSummary(0, 0, new int[10], new int[12], new int[12], 0, LocalDate.now(), 0));
		// A fresh account with nothing obtained yet still has a total.
		assertEquals(0, CollectionLogCollector.buildSummary(0, 1700, new int[10], new int[12], new int[12], 0,
			LocalDate.now(), 0).getObtained());
	}

	@Test
	public void notificationSettingValues()
	{
		assertEquals("off", CollectionLogCollector.notificationSetting(0));
		assertEquals("chat", CollectionLogCollector.notificationSetting(1));
		assertEquals("popup", CollectionLogCollector.notificationSetting(2));
		assertEquals("both", CollectionLogCollector.notificationSetting(3));
		assertNull(CollectionLogCollector.notificationSetting(4));
		assertNull(CollectionLogCollector.notificationSetting(-1));
	}

	@Test
	public void summaryWaitsForTheCountsAfterLoginThenFollowsChanges()
	{
		collector.onSessionStart();
		scheduler.advance(CollectionLogCollector.SUMMARY_LOGIN_SETTLE_MS);
		verify(session, never()).emit(eq(EventType.COLLECTION_LOG_SUMMARY), any());

		// The counts arrive; the retry picks them up.
		when(client.getVarpValue(VarPlayerID.COLLECTION_COUNT)).thenReturn(185);
		when(client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX)).thenReturn(1700);
		scheduler.advance(CollectionLogCollector.SUMMARY_RETRY_MS);
		CollectionLogSummaryData d = (CollectionLogSummaryData) emitted(EventType.COLLECTION_LOG_SUMMARY, 1).get(0);
		assertEquals(185, d.getObtained());
		assertTrue(d.getTabs().isEmpty());
		assertTrue(d.getRecent().isEmpty());
		assertEquals("off", d.getNewItemNotification());

		// A new item changes the count: sent again.
		when(client.getVarpValue(VarPlayerID.COLLECTION_COUNT)).thenReturn(186);
		VarbitChanged change = new VarbitChanged();
		change.setVarbitId(-1);
		change.setVarpId(VarPlayerID.COLLECTION_COUNT);
		collector.onVarbitChanged(change);
		scheduler.advance(CollectionLogCollector.SUMMARY_MIN_INTERVAL_MS);
		List<Object> all = emitted(EventType.COLLECTION_LOG_SUMMARY, 2);
		assertEquals(186, ((CollectionLogSummaryData) all.get(1)).getObtained());

		// Other vars do not trigger it.
		change.setVarpId(VarPlayerID.COLLECTION_COUNT + 1000);
		collector.onVarbitChanged(change);
		scheduler.advance(60_000);
		emitted(EventType.COLLECTION_LOG_SUMMARY, 2);
	}

	@Test
	public void summaryGivesUpWhenTheCountsNeverLoad()
	{
		collector.onSessionStart();
		scheduler.advance(10 * 60_000);
		verify(session, never()).emit(eq(EventType.COLLECTION_LOG_SUMMARY), any());
		assertEquals(0, scheduler.pendingTasks());
	}
}
