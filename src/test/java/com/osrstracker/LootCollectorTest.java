package com.osrstracker;

import com.osrstracker.model.EventType;
import com.osrstracker.model.Item;
import com.osrstracker.model.LootData;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import net.runelite.api.ChatMessageType;
import net.runelite.api.NPCComposition;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.plugins.loottracker.LootTrackerPlugin;
import net.runelite.http.api.loottracker.LootRecordType;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Loot source selection (no double-sending), item building, kill counts and PvP anonymisation. */
public class LootCollectorTest
{
	private final SessionTracker session = mock(SessionTracker.class);
	private final TrackerSyncConfig config = mock(TrackerSyncConfig.class);
	private final ItemMapper items = mock(ItemMapper.class);
	private final PluginManager pluginManager = mock(PluginManager.class);
	private final LootTrackerPlugin lootTracker = mock(LootTrackerPlugin.class);
	private LootCollector collector;

	@Before
	public void setUp()
	{
		when(session.isActive()).thenReturn(true);
		when(config.sendLoot()).thenReturn(true);
		when(pluginManager.getPlugins()).thenReturn(Collections.singletonList(lootTracker));
		when(items.toItem(anyInt(), anyInt())).thenAnswer(inv ->
		{
			int id = inv.getArgument(0);
			int qty = inv.getArgument(1);
			// Noted dragon bones (537) canonicalise to 536.
			int canonical = id == 537 ? 536 : id;
			return new Item(canonical, qty, "Item " + canonical, 100, 10);
		});
		collector = new LootCollector(session, config, items, pluginManager, new TimeSource());
	}

	private LootData emitted()
	{
		ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
		verify(session).emit(eq(EventType.LOOT), data.capture(), any(Instant.class));
		return (LootData) data.getValue();
	}

	private static NPCComposition npc(String name, int id, int combat)
	{
		NPCComposition c = mock(NPCComposition.class);
		when(c.getName()).thenReturn(name);
		when(c.getId()).thenReturn(id);
		when(c.getCombatLevel()).thenReturn(combat);
		return c;
	}

	@Test
	public void lootTrackerEventIsSentWithMergedCanonicalItemsAndKillCount()
	{
		when(pluginManager.isPluginActive(lootTracker)).thenReturn(true);
		collector.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", "Your Vorkath kill count is: <col=ff0000>57</col>.", null, 0));
		collector.onLootReceived(new LootReceived("Vorkath", 732, LootRecordType.NPC,
			Arrays.asList(new ItemStack(536, 1), new ItemStack(537, 2), new ItemStack(995, 300)), 1, 8061));
		verify(session, never()).emit(any(EventType.class), any(), any(Instant.class));

		collector.flushHeld(); // what the next GameTick does

		LootData d = emitted();
		assertEquals("NPC", d.getSource().getType());
		assertEquals("Vorkath", d.getSource().getName());
		assertEquals(Integer.valueOf(8061), d.getSource().getId());
		assertEquals(Integer.valueOf(732), d.getSource().getCombatLevel());
		assertEquals(Integer.valueOf(57), d.getKillCount());
		assertEquals(2, d.getItems().size());
		assertEquals(536, d.getItems().get(0).getId());
		assertEquals(3, d.getItems().get(0).getQty());
		assertEquals((3 + 300) * 100L, d.getTotalGe());
	}

	@Test
	public void serverNpcLootIsIgnoredWhileTheLootTrackerIsActive()
	{
		when(pluginManager.isPluginActive(lootTracker)).thenReturn(true);
		collector.onServerNpcLoot(new ServerNpcLoot(npc("Goblin", 3029, 2), Collections.singletonList(new ItemStack(526, 1))));
		collector.flushHeld();
		verify(session, never()).emit(any(EventType.class), any(), any(Instant.class));
	}

	@Test
	public void serverNpcLootIsUsedWhenTheLootTrackerIsOff()
	{
		when(pluginManager.isPluginActive(lootTracker)).thenReturn(false);
		collector.onServerNpcLoot(new ServerNpcLoot(npc("<col=ffff00>Goblin</col>", 3029, 2), Collections.singletonList(new ItemStack(526, 1))));
		collector.flushHeld();
		LootData d = emitted();
		assertEquals("NPC", d.getSource().getType());
		assertEquals("Goblin", d.getSource().getName());
		assertEquals(Integer.valueOf(3029), d.getSource().getId());
		assertNull(d.getKillCount());
	}

	@Test
	public void pvpLootDoesNotNameTheOtherPlayer()
	{
		collector.onLootReceived(new LootReceived("Some Pker", 126, LootRecordType.PLAYER,
			Collections.singletonList(new ItemStack(995, 1000)), 1, null));
		collector.flushHeld();
		LootData d = emitted();
		assertEquals("PLAYER", d.getSource().getType());
		assertEquals("Player", d.getSource().getName());
		assertNull(d.getSource().getId());
		assertNull(d.getSource().getCombatLevel());
	}

	@Test
	public void killCountIsUsedOnceAndMatchesRaidNames()
	{
		collector.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", "Your completed Chambers of Xeric count is: 12.", null, 0));
		assertEquals(Integer.valueOf(12), collector.takeKillCount("Chambers of Xeric"));
		assertNull(collector.takeKillCount("Chambers of Xeric"));

		collector.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", "Your Gauntlet completion count is: 3.", null, 0));
		assertEquals(Integer.valueOf(3), collector.takeKillCount("The Gauntlet"));
	}

	@Test
	public void gauntletCountsAttachToTheHunllef()
	{
		// Both counts pending at once: each Hunllef takes its own, not the other one's.
		collector.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", "Your Gauntlet completion count is: <col=ff0000>7</col>.", null, 0));
		collector.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", "Your Corrupted Gauntlet completion count is: <col=ff0000>42</col>.", null, 0));
		assertEquals(Integer.valueOf(42), collector.takeKillCount("Corrupted Hunllef"));
		assertEquals(Integer.valueOf(7), collector.takeKillCount("Crystalline Hunllef"));
		assertNull(collector.takeKillCount("Corrupted Hunllef"));

		collector.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", "Your Sol Heredit kill count is: 2.", null, 0));
		assertEquals(Integer.valueOf(2), collector.takeKillCount("Fortis Colosseum"));
	}

	@Test
	public void chestSourcesMatchTheirCountLines()
	{
		collector.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", "Your Barrows chest count is: 5.", null, 0));
		assertEquals(Integer.valueOf(5), collector.takeKillCount("Barrows"));
		collector.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", "Your subdued Wintertodt count is: 9.", null, 0));
		assertEquals(Integer.valueOf(9), collector.takeKillCount("Supply crate (Wintertodt)"));
		collector.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", "Your Tempoross kill count is: 3.", null, 0));
		assertEquals(Integer.valueOf(3), collector.takeKillCount("Reward pool (Tempoross)"));
		collector.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", "Your Lunar Chest count is: 4.", null, 0));
		assertEquals(Integer.valueOf(4), collector.takeKillCount("Lunar Chest"));
		collector.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", "Your completed Tombs of Amascut: Expert Mode count is: 6.", null, 0));
		assertEquals(Integer.valueOf(6), collector.takeKillCount("Tombs of Amascut"));
	}

	@Test
	public void nothingWhenLootIsDisabledOrEmpty()
	{
		when(config.sendLoot()).thenReturn(false);
		collector.onLootReceived(new LootReceived("Vorkath", 732, LootRecordType.NPC, Collections.singletonList(new ItemStack(536, 1)), 1, 8061));
		when(config.sendLoot()).thenReturn(true);
		collector.onLootReceived(new LootReceived("Imp", 2, LootRecordType.NPC, Collections.emptyList(), 1, 3134));
		collector.flushHeld();
		verify(session, times(0)).emit(any(EventType.class), any(), any(Instant.class));
	}
}
