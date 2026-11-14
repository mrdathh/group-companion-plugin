package com.osrstracker;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The automatic collection log read: never for a house adventure log, at most once per 500 ticks, reset on logout. */
public class CollectionLogCollectorTest
{
	private final Client client = mock(Client.class);
	private CollectionLogCollector collector;
	private int tick;

	@Before
	public void setUp()
	{
		TrackerSyncConfig config = mock(TrackerSyncConfig.class);
		when(config.sendCollectionLog()).thenReturn(true);
		SessionTracker session = mock(SessionTracker.class);
		when(session.isActive()).thenReturn(true);
		when(client.getTickCount()).thenAnswer(inv -> tick);
		collector = new CollectionLogCollector(client, session, config, mock(ItemMapper.class));
	}

	private void verifySearches(int times)
	{
		verify(client, times(times)).menuAction(eq(-1), eq(InterfaceID.Collection.SEARCH_TOGGLE), eq(MenuAction.CC_OP),
			eq(1), eq(-1), eq("Search"), any());
		verify(client, times(times)).runScript(CollectionLogCollector.SCRIPT_COLLECTION_SEARCH);
	}

	@Test
	public void houseAdventureLogIsNeverRead()
	{
		when(client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN)).thenReturn(1);
		collector.onLogOpened();
		verify(client, never()).menuAction(anyInt(), anyInt(), any(), anyInt(), anyInt(), anyString(), any());
		verify(client, never()).runScript(any());

		// Own log afterwards is read.
		when(client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN)).thenReturn(0);
		collector.onLogOpened();
		verifySearches(1);
	}

	@Test
	public void atMostOncePer500Ticks()
	{
		tick = 1000;
		collector.onLogOpened();
		verifySearches(1);
		tick = 1000 + CollectionLogCollector.MIN_TICKS_BETWEEN_READS - 1;
		collector.onLogOpened();
		verifySearches(1);
		tick = 1000 + CollectionLogCollector.MIN_TICKS_BETWEEN_READS;
		collector.onLogOpened();
		verifySearches(2);
	}

	@Test
	public void logoutResetsTheLimit()
	{
		tick = 10;
		collector.onLogOpened();
		GameStateChanged loginScreen = new GameStateChanged();
		loginScreen.setGameState(GameState.LOGIN_SCREEN);
		collector.onGameStateChanged(loginScreen);
		tick = 20;
		collector.onLogOpened();
		verifySearches(2);
	}
}
