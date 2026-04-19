package com.ketsh.droptrainer;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Drop Trainer",
	description = "Turns inventory dropping into an arcade-style reflex trainer",
	tags = {"drop", "inventory", "trainer", "osu", "clicking"}
)
public class DropTrainerPlugin extends Plugin
{
	private static final Pattern DROP_ITEM_SPLIT_PATTERN = Pattern.compile("\\s*,\\s*");
	private static final int DROP_TRAINER_PERFECT_POINTS = 300;
	private static final int DROP_TRAINER_GREAT_POINTS = 180;
	private static final int DROP_TRAINER_GOOD_POINTS = 100;
	private static final int DROP_TRAINER_LATE_POINTS = 25;
	private static final int DROP_TRAINER_MISS_PENALTY = 180;

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private DropTrainerConfig config;

	@Inject
	private DropTrainerOverlay dropTrainerOverlay;

	@Getter
	private boolean dropTrainerActive;

	@Getter
	private int dropTrainerTargetSlot = -1;

	@Getter
	private long dropTrainerAnimationStartMillis;

	@Getter
	private int dropTrainerScore;

	@Getter
	private int dropTrainerCombo;

	@Getter
	private int lastDropTrainerPoints;

	@Getter
	private long lastDropTrainerHitMillis;

	@Getter
	private String lastDropTrainerJudgement = "";

	@Getter
	private boolean dropTrainerResultsActive;

	@Getter
	private long dropTrainerResultsStartMillis;

	@Getter
	private int dropTrainerLastSessionScore;

	@Getter
	private int dropTrainerLastSessionMaxCombo;

	@Getter
	private int dropTrainerLastSessionDrops;

	@Getter
	private long dropTrainerLastSessionAverageMillis;

	@Getter
	private int dropTrainerLastSessionMissCount;

	@Getter
	private int dropTrainerLastSessionPerfectHitCount;

	@Getter
	private DropTrainerDifficulty dropTrainerSessionDifficulty = DropTrainerDifficulty.HARD;

	@Getter
	private DropTrainerDifficulty dropTrainerLastSessionDifficulty = DropTrainerDifficulty.HARD;

	@Getter
	private final List<Integer> dropTrainerSlotSequence = new ArrayList<>();

	private int dropTrainerMaxCombo;
	private int dropTrainerDropCount;
	private long dropTrainerReactionTotalMillis;

	@Getter
	private int dropTrainerMissCount;

	@Getter
	private int dropTrainerPerfectHitCount;

	private final int[] previousInventoryItemIds = new int[28];

	@Override
	protected void startUp()
	{
		overlayManager.add(dropTrainerOverlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(dropTrainerOverlay);
		resetDropTrainer();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState gameState = event.getGameState();
		if (gameState == GameState.LOADING || gameState == GameState.LOGIN_SCREEN || gameState == GameState.HOPPING)
		{
			resetDropTrainer();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client == null || client.getLocalPlayer() == null)
		{
			return;
		}

		updateDropTrainerState();
	}

	private void updateDropTrainerState()
	{

		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory == null)
		{
			resetDropTrainer();
			return;
		}

		List<String> configuredNames = DropTrainerOverlay.getConfiguredNames(config.dropItemNames(), DROP_ITEM_SPLIT_PATTERN);
		if (configuredNames.isEmpty())
		{
			resetDropTrainer();
			return;
		}

		List<DropTarget> targets = DropTrainerOverlay.buildTargets(client, inventory, configuredNames, null, -1);
		if (targets.isEmpty())
		{
			resetDropTrainer();
			return;
		}

		if (!dropTrainerActive && inventory.count() >= 28)
		{
			dropTrainerActive = true;
			dropTrainerAnimationStartMillis = System.currentTimeMillis();
			dropTrainerScore = 0;
			dropTrainerCombo = 0;
			lastDropTrainerPoints = 0;
			lastDropTrainerHitMillis = 0L;
			lastDropTrainerJudgement = "";
			dropTrainerResultsActive = false;
			dropTrainerResultsStartMillis = 0L;
			dropTrainerMaxCombo = 0;
			dropTrainerDropCount = 0;
			dropTrainerReactionTotalMillis = 0L;
			dropTrainerMissCount = 0;
			dropTrainerPerfectHitCount = 0;
			dropTrainerSessionDifficulty = getConfiguredDropTrainerDifficulty();
			rebuildDropTrainerSlotSequence(targets);
			captureInventorySnapshot(inventory);
		}

		if (!dropTrainerActive)
		{
			clearExpiredDropTrainerResults();
			return;
		}

		processDropTrainerChanges(inventory);
		pruneDropTrainerSlotSequence(targets);
		if (dropTrainerSlotSequence.isEmpty())
		{
			resetDropTrainer();
			return;
		}

		int newTargetSlot = dropTrainerSlotSequence.get(0);
		if (newTargetSlot != dropTrainerTargetSlot)
		{
			dropTrainerTargetSlot = newTargetSlot;
			dropTrainerAnimationStartMillis = System.currentTimeMillis();
		}

		captureInventorySnapshot(inventory);
	}

	private void resetDropTrainer()
	{
		if (dropTrainerActive && dropTrainerDropCount > 0)
		{
			dropTrainerLastSessionScore = dropTrainerScore;
			dropTrainerLastSessionMaxCombo = dropTrainerMaxCombo;
			dropTrainerLastSessionDrops = dropTrainerDropCount;
			dropTrainerLastSessionAverageMillis = dropTrainerReactionTotalMillis / dropTrainerDropCount;
			dropTrainerLastSessionMissCount = dropTrainerMissCount;
			dropTrainerLastSessionPerfectHitCount = dropTrainerPerfectHitCount;
			dropTrainerLastSessionDifficulty = dropTrainerSessionDifficulty;
			dropTrainerResultsActive = true;
			dropTrainerResultsStartMillis = System.currentTimeMillis();
		}

		dropTrainerActive = false;
		dropTrainerTargetSlot = -1;
		dropTrainerAnimationStartMillis = 0L;
		dropTrainerScore = 0;
		dropTrainerCombo = 0;
		lastDropTrainerPoints = 0;
		lastDropTrainerHitMillis = 0L;
		lastDropTrainerJudgement = "";
		dropTrainerMaxCombo = 0;
		dropTrainerDropCount = 0;
		dropTrainerReactionTotalMillis = 0L;
		dropTrainerMissCount = 0;
		dropTrainerPerfectHitCount = 0;
		dropTrainerSessionDifficulty = DropTrainerDifficulty.HARD;
		dropTrainerSlotSequence.clear();
		clearInventorySnapshot();
	}

	private void clearExpiredDropTrainerResults()
	{
		if (!dropTrainerResultsActive)
		{
			return;
		}

		if (System.currentTimeMillis() - dropTrainerResultsStartMillis > 6000L)
		{
			dropTrainerResultsActive = false;
			dropTrainerResultsStartMillis = 0L;
		}
	}

	private void processDropTrainerChanges(ItemContainer inventory)
	{
		if (dropTrainerSlotSequence.isEmpty())
		{
			return;
		}

		Set<Integer> changedSlots = getChangedInventorySlots(inventory);
		if (changedSlots.isEmpty())
		{
			return;
		}

		List<Integer> matchedSlots = new ArrayList<>();
		for (int slot : dropTrainerSlotSequence)
		{
			int previousItemId = previousInventoryItemIds[slot];
			if (previousItemId <= 0)
			{
				break;
			}

			int currentItemId = getInventoryItemId(inventory, slot);
			if (currentItemId == previousItemId)
			{
				break;
			}

			matchedSlots.add(slot);
		}

		boolean comboBroken = false;
		for (int slot : changedSlots)
		{
			if (matchedSlots.contains(slot))
			{
				continue;
			}

			if (previousInventoryItemIds[slot] <= 0)
			{
				continue;
			}

			breakDropTrainerCombo();
			comboBroken = true;
			break;
		}

		if (comboBroken || matchedSlots.isEmpty())
		{
			return;
		}

		long now = System.currentTimeMillis();
		for (int i = 0; i < matchedSlots.size(); i++)
		{
			long elapsed = Math.max(0L, now - dropTrainerAnimationStartMillis);
			awardDropTrainerHit(elapsed);
			if (!dropTrainerSlotSequence.isEmpty())
			{
				dropTrainerSlotSequence.remove(0);
			}
			dropTrainerTargetSlot = dropTrainerSlotSequence.isEmpty() ? -1 : dropTrainerSlotSequence.get(0);
			dropTrainerAnimationStartMillis = now;
		}
	}

	private void rebuildDropTrainerSlotSequence(List<DropTarget> targets)
	{
		dropTrainerSlotSequence.clear();
		for (DropTarget target : targets)
		{
			dropTrainerSlotSequence.add(target.getSlot());
		}
	}

	private void pruneDropTrainerSlotSequence(List<DropTarget> targets)
	{
		Set<Integer> validSlots = new HashSet<>();
		for (DropTarget target : targets)
		{
			validSlots.add(target.getSlot());
		}

		dropTrainerSlotSequence.removeIf(slot -> !validSlots.contains(slot));
	}

	private Set<Integer> getChangedInventorySlots(ItemContainer inventory)
	{
		Set<Integer> changedSlots = new HashSet<>();
		for (int slot = 0; slot < previousInventoryItemIds.length; slot++)
		{
			if (getInventoryItemId(inventory, slot) != previousInventoryItemIds[slot])
			{
				changedSlots.add(slot);
			}
		}

		return changedSlots;
	}

	private void captureInventorySnapshot(ItemContainer inventory)
	{
		for (int slot = 0; slot < previousInventoryItemIds.length; slot++)
		{
			previousInventoryItemIds[slot] = getInventoryItemId(inventory, slot);
		}
	}

	private void clearInventorySnapshot()
	{
		for (int slot = 0; slot < previousInventoryItemIds.length; slot++)
		{
			previousInventoryItemIds[slot] = -1;
		}
	}

	private void breakDropTrainerCombo()
	{
		dropTrainerCombo = 0;
		dropTrainerMissCount++;
		lastDropTrainerPoints = -DROP_TRAINER_MISS_PENALTY;
		lastDropTrainerHitMillis = System.currentTimeMillis();
		lastDropTrainerJudgement = "MISS";
		dropTrainerScore = Math.max(0, dropTrainerScore - DROP_TRAINER_MISS_PENALTY);
	}

	private void awardDropTrainerHit(long elapsed)
	{
		int points = computeDropTrainerPoints(elapsed);
		dropTrainerCombo++;
		dropTrainerMaxCombo = Math.max(dropTrainerMaxCombo, dropTrainerCombo);
		dropTrainerDropCount++;
		dropTrainerReactionTotalMillis += elapsed;
		if (points == DROP_TRAINER_PERFECT_POINTS)
		{
			dropTrainerPerfectHitCount++;
		}
		lastDropTrainerPoints = points;
		lastDropTrainerHitMillis = System.currentTimeMillis();
		lastDropTrainerJudgement = getDropTrainerJudgement(points);
		dropTrainerScore += points + Math.max(0, dropTrainerCombo - 1) * 12;
	}

	private int computeDropTrainerPoints(long elapsedMillis)
	{
		if (elapsedMillis <= dropTrainerSessionDifficulty.getPerfectWindowMillis())
		{
			return DROP_TRAINER_PERFECT_POINTS;
		}
		if (elapsedMillis <= dropTrainerSessionDifficulty.getGreatWindowMillis())
		{
			return DROP_TRAINER_GREAT_POINTS;
		}
		if (elapsedMillis <= dropTrainerSessionDifficulty.getGoodWindowMillis())
		{
			return DROP_TRAINER_GOOD_POINTS;
		}
		return DROP_TRAINER_LATE_POINTS;
	}

	long getCurrentDropTrainerElapsedMillis()
	{
		if (!dropTrainerActive || dropTrainerAnimationStartMillis <= 0L)
		{
			return 0L;
		}

		return Math.max(0L, System.currentTimeMillis() - dropTrainerAnimationStartMillis);
	}

	int getCurrentDropTrainerPotentialPoints()
	{
		return computeDropTrainerPoints(getCurrentDropTrainerElapsedMillis());
	}

	float getCurrentDropTrainerDecayProgress()
	{
		long elapsed = getCurrentDropTrainerElapsedMillis();
		long perfectWindowMillis = dropTrainerSessionDifficulty.getPerfectWindowMillis();
		long greatWindowMillis = dropTrainerSessionDifficulty.getGreatWindowMillis();
		long goodWindowMillis = dropTrainerSessionDifficulty.getGoodWindowMillis();
		long decayTailMillis = dropTrainerSessionDifficulty.getDecayTailMillis();

		if (elapsed <= perfectWindowMillis)
		{
			return elapsed / (float) perfectWindowMillis;
		}
		if (elapsed <= greatWindowMillis)
		{
			return 0.25f + ((elapsed - perfectWindowMillis)
				/ (float) (greatWindowMillis - perfectWindowMillis)) * 0.25f;
		}
		if (elapsed <= goodWindowMillis)
		{
			return 0.5f + ((elapsed - greatWindowMillis)
				/ (float) (goodWindowMillis - greatWindowMillis)) * 0.25f;
		}

		return Math.min(1f, 0.75f + Math.min(1f, (elapsed - goodWindowMillis)
			/ (float) decayTailMillis) * 0.25f);
	}

	private String getDropTrainerJudgement(int points)
	{
		switch (points)
		{
			case DROP_TRAINER_PERFECT_POINTS:
				return "PERFECT";
			case DROP_TRAINER_GREAT_POINTS:
				return "CLEAN";
			case DROP_TRAINER_GOOD_POINTS:
				return "GOOD";
			default:
				return "LATE";
		}
	}

	int getCurrentDropTrainerAverageMillis()
	{
		return dropTrainerDropCount <= 0 ? 0 : (int) (dropTrainerReactionTotalMillis / dropTrainerDropCount);
	}

	boolean isCurrentDropTrainerPerfectRun()
	{
		return dropTrainerMissCount == 0
			&& dropTrainerPerfectHitCount == dropTrainerDropCount;
	}

	String getCurrentDropTrainerGrade()
	{
		return computeDropTrainerGrade(
			dropTrainerScore,
			dropTrainerDropCount,
			dropTrainerPerfectHitCount,
			dropTrainerMissCount);
	}

	String getLastSessionDropTrainerGrade()
	{
		return computeDropTrainerGrade(
			dropTrainerLastSessionScore,
			dropTrainerLastSessionDrops,
			dropTrainerLastSessionPerfectHitCount,
			dropTrainerLastSessionMissCount);
	}

	private String computeDropTrainerGrade(int score, int drops, int perfectHits, int misses)
	{
		if (drops <= 0 && misses <= 0)
		{
			return "C";
		}

		if (drops > 0 && misses == 0 && perfectHits == drops)
		{
			return "S++";
		}

		double performance = computeDropTrainerPerformance(score, drops, misses);
		if (misses == 0 && performance >= 14.75)
		{
			return "S+";
		}
		if (misses == 0 && performance >= 14.5)
		{
			return "S";
		}
		if (misses == 0 && performance >= 14.0)
		{
			return "A++";
		}
		if (performance >= 14.0)
		{
			return "A+";
		}
		if (performance >= 13.0)
		{
			return "A";
		}
		if (performance >= 12.0)
		{
			return "A-";
		}
		if (performance >= 11.0)
		{
			return "B+";
		}
		if (performance >= 10.0)
		{
			return "B";
		}
		if (performance >= 9.0)
		{
			return "B-";
		}
		if (performance >= 8.0)
		{
			return "C+";
		}
		if (performance >= 7.0)
		{
			return "C";
		}
		if (performance >= 6.0)
		{
			return "C-";
		}
		if (performance >= 5.0)
		{
			return "D+";
		}
		if (performance >= 4.0)
		{
			return "D";
		}
		if (performance >= 3.0)
		{
			return "D-";
		}
		if (performance >= 2.0)
		{
			return "F+";
		}
		if (performance >= 1.0)
		{
			return "F";
		}
		return "F-";
	}

	private double computeDropTrainerPerformance(int score, int drops, int misses)
	{
		int attempts = Math.max(1, drops + misses);
		double normalized = score / (attempts * 20.0);
		return Math.max(0.0, Math.min(15.0, normalized));
	}

	private DropTrainerDifficulty getConfiguredDropTrainerDifficulty()
	{
		DropTrainerDifficulty difficulty = config.dropTrainerDifficulty();
		return difficulty == null ? DropTrainerDifficulty.HARD : difficulty;
	}

	private int getInventoryItemId(ItemContainer inventory, int slot)
	{
		if (slot < 0)
		{
			return -1;
		}

		Item item = inventory.getItem(slot);
		return item == null ? -1 : item.getId();
	}

	@Provides
	DropTrainerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DropTrainerConfig.class);
	}
}



