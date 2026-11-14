package com.osrstracker;

import net.runelite.api.Client;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Quests: read 5 s after login, then every 60 s, and not because varbits change. Diaries: only diary varbits
 * trigger a read, at most once per 5 s. Counted through the client-thread hops each read makes.
 */
public class QuestCadenceTest
{
	private final FakeScheduler scheduler = new FakeScheduler();
	private final ClientThread clientThread = mock(ClientThread.class);
	private QuestDiaryCollector collector;

	@Before
	public void setUp()
	{
		TrackerSyncConfig config = mock(TrackerSyncConfig.class);
		when(config.sendQuests()).thenReturn(true);
		SessionTracker session = mock(SessionTracker.class);
		when(session.isActive()).thenReturn(true);
		collector = new QuestDiaryCollector(mock(Client.class), clientThread, session, config, scheduler, scheduler.time,
			new DiaryTasks(net.runelite.http.api.RuneLiteAPI.GSON));
	}

	private static VarbitChanged varbit(int varbitId)
	{
		VarbitChanged e = new VarbitChanged();
		e.setVarbitId(varbitId);
		e.setVarpId(-1);
		return e;
	}

	private static VarbitChanged varp(int varpId)
	{
		VarbitChanged e = new VarbitChanged();
		e.setVarbitId(-1);
		e.setVarpId(varpId);
		return e;
	}

	private void verifyReads(int n)
	{
		verify(clientThread, times(n)).invoke(any(Runnable.class));
	}

	@Test
	public void questsAfterLoginThenEvery60Seconds()
	{
		collector.onSessionStart(); // also schedules the diaries' login read at +5 s
		scheduler.advance(QuestDiaryCollector.LOGIN_SETTLE_MS - 1);
		verifyReads(0);
		scheduler.advance(1);
		verifyReads(2); // quests + diaries

		// Quest progress varps do not cause a read any more.
		for (int i = 0; i < 50; i++)
		{
			collector.onVarbitChanged(varp(VarPlayerID.QP));
			collector.onVarbitChanged(varbit(1234));
			scheduler.advance(1_000);
		}
		// 50 s later: still nothing new.
		verifyReads(2);
		scheduler.advance(QuestDiaryCollector.QUEST_INTERVAL_MS - 50_000);
		verifyReads(3);
		scheduler.advance(QuestDiaryCollector.QUEST_INTERVAL_MS);
		verifyReads(4);

		collector.onSessionEnd();
		scheduler.advance(10 * QuestDiaryCollector.QUEST_INTERVAL_MS);
		verifyReads(4);
	}

	@Test
	public void diaryVarbitsTriggerADiaryReadAtMostEvery5Seconds()
	{
		collector.onSessionStart();
		scheduler.advance(QuestDiaryCollector.LOGIN_SETTLE_MS);
		verifyReads(2);

		collector.onVarbitChanged(varbit(VarbitID.VARROCK_DIARY_EASY_COMPLETE));
		scheduler.advance(QuestDiaryCollector.MIN_INTERVAL_MS - 1);
		verifyReads(2);
		scheduler.advance(1);
		verifyReads(3);
	}
}
