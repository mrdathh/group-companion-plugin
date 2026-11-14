package com.osrstracker;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.osrstracker.model.AccountRef;
import com.osrstracker.model.ClientInfo;
import com.osrstracker.model.ClogItemData;
import com.osrstracker.model.CollectionLogData;
import com.osrstracker.model.ContainerData;
import com.osrstracker.model.ContainerItem;
import com.osrstracker.model.DeathData;
import com.osrstracker.model.DiariesData;
import com.osrstracker.model.EventType;
import com.osrstracker.model.IngestEvent;
import com.osrstracker.model.IngestRequest;
import com.osrstracker.model.Item;
import com.osrstracker.model.LevelUpData;
import com.osrstracker.model.LootData;
import com.osrstracker.model.QuestsData;
import com.osrstracker.model.SessionData;
import com.osrstracker.model.SkillsData;
import com.osrstracker.model.BankDepositData;
import com.osrstracker.model.LoadoutsData;
import com.osrstracker.model.KingdomData;
import com.osrstracker.model.CombatAchievementsData;
import com.osrstracker.model.KillCountsData;
import com.osrstracker.model.Location;
import com.osrstracker.model.SlayerData;
import com.osrstracker.model.StatusData;
import com.osrstracker.model.TimersData;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.http.api.RuneLiteAPI;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Every event type serialised with RuneLite's own Gson (what the plugin gets injected) and compared with
 * JSON written by hand from the server project's shared/protocol.ts (snapshot: docs/PROTOCOL.md). Comparison is structural (key order does not matter).
 */
public class ProtocolJsonTest
{
	private static final Gson GSON = RuneLiteAPI.GSON;
	private static final Instant AT = Instant.parse("2026-11-14T16:20:00.123Z");
	private static final String ID = "3f2c1a9e-0000-4000-8000-000000000001";

	private static IngestEvent event(EventType type, Object data)
	{
		IngestEvent generated = IngestEvent.create(type, data, AT);
		return new IngestEvent(ID, generated.getType(), generated.getAt(), generated.getData());
	}

	private static void assertJson(String expected, Object actual)
	{
		JsonElement e = new JsonParser().parse(expected);
		JsonElement a = new JsonParser().parse(GSON.toJson(actual));
		assertEquals(e, a);
	}

	private static String envelope(String type, String data)
	{
		return "{\"id\":\"" + ID + "\",\"type\":\"" + type + "\",\"at\":\"2026-11-14T16:20:00.123Z\",\"data\":" + data + "}";
	}

	@Test
	public void timestampsAreUtcWithMilliseconds()
	{
		assertEquals("2026-11-14T16:20:00.000Z", IngestEvent.formatTime(Instant.parse("2026-11-14T16:20:00Z")));
		assertEquals("2026-01-02T03:04:05.006Z", IngestEvent.formatTime(Instant.parse("2026-01-02T03:04:05.006789Z")));
	}

	@Test
	public void generatedIdsAreUuids()
	{
		IngestEvent e = IngestEvent.create(EventType.LOGIN, new SessionData(301), AT);
		assertTrue(e.getId().matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
	}

	@Test
	public void request()
	{
		IngestRequest req = new IngestRequest(1,
			new ClientInfo("b1e4c3d2-1111-4222-8333-444455556666", "0.1.0"),
			new AccountRef("-7206031417462364190", "Some Name", "HARDCORE_IRONMAN", 302),
			Collections.singletonList(event(EventType.LOGIN, new SessionData(302))));
		assertJson("{\"v\":1,"
			+ "\"client\":{\"id\":\"b1e4c3d2-1111-4222-8333-444455556666\",\"plugin\":\"0.1.0\"},"
			+ "\"account\":{\"hash\":\"-7206031417462364190\",\"rsn\":\"Some Name\",\"type\":\"HARDCORE_IRONMAN\",\"world\":302},"
			+ "\"events\":[" + envelope("login", "{\"world\":302}") + "]}", req);
	}

	@Test
	public void requestOfAGroupIronman()
	{
		IngestRequest req = new IngestRequest(1, new ClientInfo("c", "0.1.0"),
			new AccountRef("1", "A", "GROUP_IRONMAN", 301, "The Irons"), Collections.emptyList());
		assertJson("{\"v\":1,\"client\":{\"id\":\"c\",\"plugin\":\"0.1.0\"},"
			+ "\"account\":{\"hash\":\"1\",\"rsn\":\"A\",\"type\":\"GROUP_IRONMAN\",\"world\":301,\"group\":\"The Irons\"},\"events\":[]}", req);
	}

	@Test
	public void heartbeatHasEmptyEventList()
	{
		IngestRequest req = new IngestRequest(1, new ClientInfo("c", "0.1.0"),
			new AccountRef("1", "A", "NORMAL", 301), Collections.emptyList());
		assertJson("{\"v\":1,\"client\":{\"id\":\"c\",\"plugin\":\"0.1.0\"},"
			+ "\"account\":{\"hash\":\"1\",\"rsn\":\"A\",\"type\":\"NORMAL\",\"world\":301},\"events\":[]}", req);
	}

	@Test
	public void login()
	{
		assertJson(envelope("login", "{\"world\":301}"), event(EventType.LOGIN, new SessionData(301)));
	}

	@Test
	public void logout()
	{
		assertJson(envelope("logout", "{\"world\":301}"), event(EventType.LOGOUT, new SessionData(301)));
	}

	@Test
	public void lootNpcWithKillCount()
	{
		LootData data = new LootData(new LootData.Source("NPC", "Vorkath", 8061, 732), 57,
			Arrays.asList(new Item(536, 2, "Dragon bones", 2500, 1), new Item(11286, 1, "Draconic visage", 3000000, 60000)),
			3005000);
		assertJson(envelope("loot", "{\"source\":{\"type\":\"NPC\",\"name\":\"Vorkath\",\"id\":8061,\"combatLevel\":732},"
			+ "\"killCount\":57,"
			+ "\"items\":[{\"id\":536,\"qty\":2,\"name\":\"Dragon bones\",\"ge\":2500,\"ha\":1},"
			+ "{\"id\":11286,\"qty\":1,\"name\":\"Draconic visage\",\"ge\":3000000,\"ha\":60000}],"
			+ "\"totalGe\":3005000}"), event(EventType.LOOT, data));
	}

	@Test
	public void lootEventWithoutOptionalFields()
	{
		LootData data = new LootData(new LootData.Source("EVENT", "Barrows", null, null), null,
			Collections.singletonList(new Item(995, 5000, "Coins", 1, 0)), 5000);
		assertJson(envelope("loot", "{\"source\":{\"type\":\"EVENT\",\"name\":\"Barrows\"},"
			+ "\"items\":[{\"id\":995,\"qty\":5000,\"name\":\"Coins\",\"ge\":1,\"ha\":0}],\"totalGe\":5000}"),
			event(EventType.LOOT, data));
	}

	@Test
	public void levelUp()
	{
		assertJson(envelope("level_up", "{\"skill\":\"Woodcutting\",\"level\":70}"),
			event(EventType.LEVEL_UP, new LevelUpData("Woodcutting", 70)));
	}

	@Test
	public void deathByNpc()
	{
		DeathData data = new DeathData(new DeathData.Killer("NPC", "Zulrah", 2042), new DeathData.Location(2268, 3070, 0, 9007));
		assertJson(envelope("death", "{\"killer\":{\"type\":\"NPC\",\"name\":\"Zulrah\",\"id\":2042},"
			+ "\"location\":{\"x\":2268,\"y\":3070,\"plane\":0,\"regionId\":9007}}"), event(EventType.DEATH, data));
	}

	@Test
	public void deathByPlayerHasNoName()
	{
		DeathData data = new DeathData(DeathCollector.killerOf(org.mockito.Mockito.mock(net.runelite.api.Player.class)),
			new DeathData.Location(3100, 3600, 0, 12342));
		assertJson(envelope("death", "{\"killer\":{\"type\":\"PLAYER\"},"
			+ "\"location\":{\"x\":3100,\"y\":3600,\"plane\":0,\"regionId\":12342}}"), event(EventType.DEATH, data));
	}

	@Test
	public void deathWithUnknownKiller()
	{
		DeathData data = new DeathData(null, new DeathData.Location(1, 2, 3, 4));
		assertJson(envelope("death", "{\"location\":{\"x\":1,\"y\":2,\"plane\":3,\"regionId\":4}}"), event(EventType.DEATH, data));
	}

	@Test
	public void clogItem()
	{
		assertJson(envelope("clog_item", "{\"name\":\"Pet snakeling\",\"id\":12921}"),
			event(EventType.CLOG_ITEM, new ClogItemData("Pet snakeling", 12921)));
		assertJson(envelope("clog_item", "{\"name\":\"Pet snakeling\"}"),
			event(EventType.CLOG_ITEM, new ClogItemData("Pet snakeling", null)));
	}

	@Test
	public void skills()
	{
		Map<String, SkillsData.SkillValue> skills = new LinkedHashMap<>();
		skills.put("Attack", new SkillsData.SkillValue(13034431, 99));
		skills.put("Hitpoints", new SkillsData.SkillValue(1154, 10));
		SkillsData data = new SkillsData(skills, 109, 13035585L, 70);
		assertJson(envelope("skills", "{\"skills\":{\"Attack\":{\"xp\":13034431,\"level\":99},\"Hitpoints\":{\"xp\":1154,\"level\":10}},"
			+ "\"totalLevel\":109,\"totalXp\":13035585,\"combatLevel\":70}"), event(EventType.SKILLS, data));
	}

	@Test
	public void inventoryContainerHasNoEquipSlot()
	{
		ContainerData data = new ContainerData("inventory",
			Collections.singletonList(new ContainerItem(new Item(385, 3, "Shark", 900, 150), 4, null)));
		assertJson(envelope("container", "{\"container\":\"inventory\",\"items\":[{\"id\":385,\"qty\":3,\"name\":\"Shark\",\"ge\":900,\"ha\":150,\"slot\":4}]}"),
			event(EventType.CONTAINER, data));
	}

	@Test
	public void equipmentContainerHasEquipSlot()
	{
		ContainerData data = new ContainerData("equipment",
			Collections.singletonList(new ContainerItem(new Item(4151, 1, "Abyssal whip", 1500000, 72000), 3, "weapon")));
		assertJson(envelope("container", "{\"container\":\"equipment\",\"items\":[{\"id\":4151,\"qty\":1,\"name\":\"Abyssal whip\","
			+ "\"ge\":1500000,\"ha\":72000,\"slot\":3,\"equipSlot\":\"weapon\"}]}"), event(EventType.CONTAINER, data));
	}

	@Test
	public void quests()
	{
		Map<String, String> quests = new LinkedHashMap<>();
		quests.put("Cook's Assistant", "FINISHED");
		quests.put("Dragon Slayer I", "IN_PROGRESS");
		quests.put("Monkey Madness II", "NOT_STARTED");
		assertJson(envelope("quests", "{\"quests\":{\"Cook's Assistant\":\"FINISHED\",\"Dragon Slayer I\":\"IN_PROGRESS\","
			+ "\"Monkey Madness II\":\"NOT_STARTED\"},\"questPoints\":42}"), event(EventType.QUESTS, new QuestsData(quests, 42)));
	}

	@Test
	public void diariesOmitTaskCounts()
	{
		Map<String, DiariesData.TierProgress> tiers = new LinkedHashMap<>();
		tiers.put("Easy", new DiariesData.TierProgress(true, null, null));
		tiers.put("Medium", new DiariesData.TierProgress(false, null, null));
		Map<String, Map<String, DiariesData.TierProgress>> diaries = new LinkedHashMap<>();
		diaries.put("Lumbridge & Draynor", tiers);
		assertJson(envelope("diaries", "{\"diaries\":{\"Lumbridge & Draynor\":{\"Easy\":{\"complete\":true},\"Medium\":{\"complete\":false}}}}"),
			event(EventType.DIARIES, new DiariesData(diaries)));
	}

	@Test
	public void collectionLog()
	{
		Map<String, Integer> items = new LinkedHashMap<>();
		items.put("12921", 1);
		items.put("4151", 3);
		Map<String, String> names = new LinkedHashMap<>();
		names.put("12921", "Pet snakeling");
		names.put("4151", "Abyssal whip");
		assertJson(envelope("collection_log", "{\"items\":{\"12921\":1,\"4151\":3},"
			+ "\"names\":{\"12921\":\"Pet snakeling\",\"4151\":\"Abyssal whip\"},\"obtained\":2,\"total\":1692}"),
			event(EventType.COLLECTION_LOG, new CollectionLogData(items, names, 2, 1692)));
	}

	private static final String LOCATION_JSON = "{\"x\":3222,\"y\":3218,\"plane\":0,\"regionId\":12850,\"instanced\":false,\"onBoat\":false}";

	@Test
	public void statusFightingAnNpc()
	{
		StatusData data = new StatusData(new StatusData.CurMax(55, 99), new StatusData.CurMax(43, 70), 87, 50, 302,
			new Location(3222, 3218, 0, 12850, false, false, null),
			new StatusData.Interacting("Abyssal demon", 415, 12, 30));
		assertJson(envelope("status", "{\"hp\":{\"cur\":55,\"max\":99},\"prayer\":{\"cur\":43,\"max\":70},"
			+ "\"runEnergy\":87,\"specialAttack\":50,\"world\":302,\"location\":" + LOCATION_JSON + ","
			+ "\"interacting\":{\"name\":\"Abyssal demon\",\"id\":415,\"hpRatio\":12,\"hpScale\":30}}"), event(EventType.STATUS, data));
	}

	@Test
	public void statusNotInteractingSendsExplicitNull()
	{
		StatusData data = new StatusData(new StatusData.CurMax(10, 10), new StatusData.CurMax(1, 1), 100, 100, 301,
			new Location(3222, 3218, 0, 12850, false, false, null), null);
		String json = RuneLiteAPI.GSON.toJson(event(EventType.STATUS, data));
		assertTrue(json, json.contains("\"interacting\":null"));
		assertJson(envelope("status", "{\"hp\":{\"cur\":10,\"max\":10},\"prayer\":{\"cur\":1,\"max\":1},"
			+ "\"runEnergy\":100,\"specialAttack\":100,\"world\":301,\"location\":" + LOCATION_JSON + ",\"interacting\":null}"),
			event(EventType.STATUS, data));
	}

	@Test
	public void statusOnABoatAndInAHouse()
	{
		StatusData boat = new StatusData(new StatusData.CurMax(10, 10), new StatusData.CurMax(1, 1), 0, 0, 301,
			new Location(3050, 3200, 0, 12082, false, true, null), null);
		assertJson(envelope("status", "{\"hp\":{\"cur\":10,\"max\":10},\"prayer\":{\"cur\":1,\"max\":1},\"runEnergy\":0,"
			+ "\"specialAttack\":0,\"world\":301,\"location\":{\"x\":3050,\"y\":3200,\"plane\":0,\"regionId\":12082,"
			+ "\"instanced\":false,\"onBoat\":true},\"interacting\":null}"), event(EventType.STATUS, boat));

		StatusData house = new StatusData(new StatusData.CurMax(10, 10), new StatusData.CurMax(1, 1), 0, 0, 301,
			new Location(2953, 3224, 0, 11826, true, false, true), null);
		assertJson(envelope("status", "{\"hp\":{\"cur\":10,\"max\":10},\"prayer\":{\"cur\":1,\"max\":1},\"runEnergy\":0,"
			+ "\"specialAttack\":0,\"world\":301,\"location\":{\"x\":2953,\"y\":3224,\"plane\":0,\"regionId\":11826,"
			+ "\"instanced\":true,\"onBoat\":false,\"house\":true},\"interacting\":null}"), event(EventType.STATUS, house));
	}

	@Test
	public void timers()
	{
		TimersData data = new TimersData(Arrays.asList(
			new TimersData.Timer("farming:herb:ardougne", "farming", "Herb", "Ardougne", "Ranarr", 207, "GROWING",
				"2026-11-14T17:40:00.000Z"),
			new TimersData.Timer("farming:allotment:falador-north", "farming", "Allotment", "Falador (North)", null, null,
				"EMPTY", null),
			new TimersData.Timer("birdhouse:meadow-north", "birdhouse", "Bird house", "Mushroom Meadow (North)",
				"Yew bird house", 22201, "READY", "2026-11-14T16:00:00.000Z")));
		assertJson(envelope("timers", "{\"timers\":["
			+ "{\"id\":\"farming:herb:ardougne\",\"kind\":\"farming\",\"category\":\"Herb\",\"place\":\"Ardougne\","
			+ "\"produce\":\"Ranarr\",\"itemId\":207,\"state\":\"GROWING\",\"readyAt\":\"2026-11-14T17:40:00.000Z\"},"
			+ "{\"id\":\"farming:allotment:falador-north\",\"kind\":\"farming\",\"category\":\"Allotment\","
			+ "\"place\":\"Falador (North)\",\"state\":\"EMPTY\",\"readyAt\":null},"
			+ "{\"id\":\"birdhouse:meadow-north\",\"kind\":\"birdhouse\",\"category\":\"Bird house\","
			+ "\"place\":\"Mushroom Meadow (North)\",\"produce\":\"Yew bird house\",\"itemId\":22201,\"state\":\"READY\","
			+ "\"readyAt\":\"2026-11-14T16:00:00.000Z\"}]}"), event(EventType.TIMERS, data));
	}

	@Test
	public void slayer()
	{
		assertJson(envelope("slayer", "{\"task\":{\"name\":\"Abyssal demons\",\"remaining\":87,\"initial\":180,"
			+ "\"location\":\"Catacombs of Kourend\"},\"points\":1234,\"streak\":56}"),
			event(EventType.SLAYER, new SlayerData(new SlayerData.Task("Abyssal demons", 87, 180, "Catacombs of Kourend"), 1234, 56)));
		assertJson(envelope("slayer", "{\"task\":{\"name\":\"Cows\",\"remaining\":3,\"initial\":15},\"points\":0,\"streak\":1}"),
			event(EventType.SLAYER, new SlayerData(new SlayerData.Task("Cows", 3, 15, null), 0, 1)));
		assertJson(envelope("slayer", "{\"task\":null,\"points\":10,\"streak\":4}"),
			event(EventType.SLAYER, new SlayerData(null, 10, 4)));
	}

	@Test
	public void killCounts()
	{
		Map<String, Integer> counts = new LinkedHashMap<>();
		counts.put("Chambers of Xeric", 12);
		counts.put("Vorkath", 301);
		assertJson(envelope("kill_counts", "{\"counts\":{\"Chambers of Xeric\":12,\"Vorkath\":301}}"),
			event(EventType.KILL_COUNTS, new KillCountsData(counts)));
	}

	@Test
	public void combatAchievementsWithoutTiersOrTasks()
	{
		assertJson(envelope("combat_achievements", "{\"points\":431,\"completed\":152}"),
			event(EventType.COMBAT_ACHIEVEMENTS, new CombatAchievementsData(431, 152)));
	}

	@Test
	public void newStateKeysAreTheirTypes()
	{
		assertEquals("status", EventType.stateKey(event(EventType.STATUS, null)));
		assertEquals("timers", EventType.stateKey(event(EventType.TIMERS, null)));
		assertEquals("slayer", EventType.stateKey(event(EventType.SLAYER, null)));
		assertEquals("kill_counts", EventType.stateKey(event(EventType.KILL_COUNTS, null)));
		assertEquals("combat_achievements", EventType.stateKey(event(EventType.COMBAT_ACHIEVEMENTS, null)));
		assertTrue(EventType.STATUS.isVolatile());
		assertTrue(!EventType.SLAYER.isVolatile());
	}

	@Test
	public void potionStorageCountsDoses()
	{
		ContainerData data = new ContainerData("potion_storage",
			Collections.singletonList(new ContainerItem(new Item(2452, 37, "Antifire potion(1)", 120, 18), 4, null, "dose")));
		assertJson(envelope("container", "{\"container\":\"potion_storage\",\"items\":[{\"id\":2452,\"qty\":37,"
			+ "\"name\":\"Antifire potion(1)\",\"ge\":120,\"ha\":18,\"slot\":4,\"unit\":\"dose\"}]}"),
			event(EventType.CONTAINER, data));
	}

	@Test
	public void estimatedBag()
	{
		ContainerData data = new ContainerData("fish_barrel",
			Collections.singletonList(new ContainerItem(new Item(383, 12, "Raw shark", 700, 120), 0, null)), true);
		assertJson(envelope("container", "{\"container\":\"fish_barrel\",\"items\":[{\"id\":383,\"qty\":12,"
			+ "\"name\":\"Raw shark\",\"ge\":700,\"ha\":120,\"slot\":0}],\"estimated\":true}"),
			event(EventType.CONTAINER, data));
		// Read from the game: no "estimated" field at all.
		assertJson(envelope("container", "{\"container\":\"rune_pouch\",\"items\":[]}"),
			event(EventType.CONTAINER, new ContainerData("rune_pouch", Collections.emptyList())));
	}

	@Test
	public void bankDeposit()
	{
		assertJson(envelope("bank_deposit", "{\"items\":[{\"id\":1513,\"qty\":27,\"name\":\"Magic logs\",\"ge\":1100,\"ha\":192}]}"),
			event(EventType.BANK_DEPOSIT, new BankDepositData(Collections.singletonList(new Item(1513, 27, "Magic logs", 1100, 192)))));
		assertEquals(null, EventType.stateKey(event(EventType.BANK_DEPOSIT, null)));
	}

	@Test
	public void loadouts()
	{
		LoadoutsData.Loadout full = new LoadoutsData.Loadout("Vorkath", "Vorkath", "inventory_setups",
			Collections.singletonList(new ContainerItem(new Item(11785, 1, "Armadyl crossbow", 30000000, 60000), 3, "weapon")),
			Collections.singletonList(new ContainerItem(new Item(385, 1, "Shark", 900, 150), 0, null)),
			Collections.singletonList(new Item(560, 500, "Death rune", 200, 108)), "Bring antifire");
		LoadoutsData.Loadout bare = new LoadoutsData.Loadout("Skilling", "Skilling", "inventory_setups",
			Collections.emptyList(), Collections.emptyList(), null, null);
		assertJson(envelope("loadouts", "{\"loadouts\":["
			+ "{\"id\":\"Vorkath\",\"name\":\"Vorkath\",\"source\":\"inventory_setups\","
			+ "\"equipment\":[{\"id\":11785,\"qty\":1,\"name\":\"Armadyl crossbow\",\"ge\":30000000,\"ha\":60000,\"slot\":3,\"equipSlot\":\"weapon\"}],"
			+ "\"inventory\":[{\"id\":385,\"qty\":1,\"name\":\"Shark\",\"ge\":900,\"ha\":150,\"slot\":0}],"
			+ "\"runePouch\":[{\"id\":560,\"qty\":500,\"name\":\"Death rune\",\"ge\":200,\"ha\":108}],"
			+ "\"notes\":\"Bring antifire\"},"
			+ "{\"id\":\"Skilling\",\"name\":\"Skilling\",\"source\":\"inventory_setups\",\"equipment\":[],\"inventory\":[]}]}"),
			event(EventType.LOADOUTS, new LoadoutsData(java.util.Arrays.asList(full, bare))));
		assertEquals("loadouts", EventType.stateKey(event(EventType.LOADOUTS, null)));
	}

	@Test
	public void diariesWithTasks()
	{
		Map<String, DiariesData.TierProgress> tiers = new LinkedHashMap<>();
		tiers.put("Easy", new DiariesData.TierProgress(false, 1, 2, java.util.Arrays.asList(
			new DiariesData.Task("Steal a cake.", true), new DiariesData.Task("Use the altar.", false))));
		Map<String, Map<String, DiariesData.TierProgress>> diaries = new LinkedHashMap<>();
		diaries.put("Ardougne", tiers);
		assertJson(envelope("diaries", "{\"diaries\":{\"Ardougne\":{\"Easy\":{\"complete\":false,\"done\":1,\"total\":2,"
			+ "\"tasks\":[{\"name\":\"Steal a cake.\",\"done\":true},{\"name\":\"Use the altar.\",\"done\":false}]}}}}"),
			event(EventType.DIARIES, new DiariesData(diaries)));
	}

	@Test
	public void slayerDetail()
	{
		SlayerData data = new SlayerData(new SlayerData.Task("Gargoyles", 50, 180, null), 900, 120, "Duradel", 3,
			java.util.Arrays.asList("Black demons", "Spiritual creatures"), Collections.singletonList("Gargoyle smasher"));
		assertJson(envelope("slayer", "{\"task\":{\"name\":\"Gargoyles\",\"remaining\":50,\"initial\":180},\"points\":900,"
			+ "\"streak\":120,\"master\":\"Duradel\",\"wildernessStreak\":3,\"blocked\":[\"Black demons\",\"Spiritual creatures\"],"
			+ "\"unlocks\":[\"Gargoyle smasher\"]}"), event(EventType.SLAYER, data));
	}

	@Test
	public void kingdom()
	{
		Map<String, Integer> workers = new LinkedHashMap<>();
		workers.put("Wood", 5);
		workers.put("Fishing", 5);
		assertJson(envelope("kingdom", "{\"approval\":75,\"coffer\":750000,\"workers\":{\"Wood\":5,\"Fishing\":5},\"royalTrouble\":true}"),
			event(EventType.KINGDOM, new KingdomData(75, 750000, workers, true)));
		assertEquals("kingdom", EventType.stateKey(event(EventType.KINGDOM, null)));
	}

	@Test
	public void combatAchievementTiersAndTasks()
	{
		Map<String, CombatAchievementsData.TierCount> tiers = new LinkedHashMap<>();
		tiers.put("Easy", new CombatAchievementsData.TierCount(1, 2));
		CombatAchievementsData data = new CombatAchievementsData(1, 1, tiers, java.util.Arrays.asList(
			new CombatAchievementsData.Task(0, "Noxious Foe", "Easy", "Aberrant Spectre", true),
			new CombatAchievementsData.Task(5, "Into the Den of Giants", "Easy", null, false)));
		assertJson(envelope("combat_achievements", "{\"points\":1,\"completed\":1,\"tiers\":{\"Easy\":{\"done\":1,\"total\":2}},"
			+ "\"tasks\":[{\"id\":0,\"name\":\"Noxious Foe\",\"tier\":\"Easy\",\"monster\":\"Aberrant Spectre\",\"done\":true},"
			+ "{\"id\":5,\"name\":\"Into the Den of Giants\",\"tier\":\"Easy\",\"done\":false}]}"),
			event(EventType.COMBAT_ACHIEVEMENTS, data));
	}

	@Test
	public void tearsOfGuthixTimer()
	{
		TimersData data = new TimersData(Collections.singletonList(new TimersData.Timer("tears_of_guthix", "tears_of_guthix",
			"Weekly", "Tears of Guthix", null, null, "GROWING", "2026-11-21T00:00:00.000Z")));
		assertJson(envelope("timers", "{\"timers\":[{\"id\":\"tears_of_guthix\",\"kind\":\"tears_of_guthix\",\"category\":\"Weekly\","
			+ "\"place\":\"Tears of Guthix\",\"state\":\"GROWING\",\"readyAt\":\"2026-11-21T00:00:00.000Z\"}]}"),
			event(EventType.TIMERS, data));
	}

	@Test
	public void stateKeys()
	{
		assertEquals("skills", EventType.stateKey(event(EventType.SKILLS, null)));
		assertEquals("container:bank", EventType.stateKey(event(EventType.CONTAINER, new ContainerData("bank", Collections.emptyList()))));
		assertEquals("collection_log", EventType.stateKey(event(EventType.COLLECTION_LOG, null)));
		assertEquals(null, EventType.stateKey(event(EventType.LOOT, null)));
	}
}
