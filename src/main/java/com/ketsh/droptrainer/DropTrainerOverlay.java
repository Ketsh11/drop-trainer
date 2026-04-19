package com.ketsh.droptrainer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.QuadCurve2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class DropTrainerOverlay extends Overlay
{
	private static final int INVENTORY_SIZE = 28;
	private static final Pattern SPLIT_PATTERN = Pattern.compile("\\s*,\\s*");
	private static final long APPROACH_DURATION_MS = 900L;
	private static final int LOOKAHEAD_TARGETS = 3;
	private static final int SAME_AXIS_THRESHOLD = 6;
	private static final int CURVE_OFFSET = 18;
	private static final long HIT_FLASH_MS = 420L;
	private static final long JUDGEMENT_FLASH_MS = 900L;
	private static final long RESULTS_FLASH_MS = 6000L;
	private static final Color POSITIVE_SPLASH = new Color(89, 255, 184, 255);
	private static final Color NEGATIVE_SPLASH = new Color(255, 84, 126, 255);

	private final Client client;
	private final DropTrainerPlugin plugin;
	private final DropTrainerConfig config;

	@Inject
	private DropTrainerOverlay(Client client, DropTrainerPlugin plugin, DropTrainerConfig config)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{

		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory == null)
		{
			if (config.dopamineMode())
			{
				drawResultsCard(graphics);
			}
			return null;
		}

		if (!plugin.isDropTrainerActive())
		{
			if (config.dopamineMode())
			{
				drawResultsCard(graphics);
			}
			return null;
		}

		Widget inventoryWidget = client.getWidget(InterfaceID.INVENTORY, 0);
		if (inventoryWidget == null || inventoryWidget.isHidden())
		{
			return null;
		}

		List<String> configuredNames = getConfiguredNames(config.dropItemNames(), SPLIT_PATTERN);
		if (configuredNames.isEmpty())
		{
			return null;
		}

		List<DropTarget> targets = buildTargets(
			client,
			inventory,
			configuredNames,
			inventoryWidget,
			-1);
		targets = orderTargetsBySequence(targets, plugin.getDropTrainerSlotSequence());

		if (targets.isEmpty())
		{
			return null;
		}

		drawInventoryMask(graphics, inventoryWidget);
		drawDropPath(graphics, targets);
		if (config.dopamineMode())
		{
			drawHud(graphics, inventoryWidget);
			drawJudgementSplash(graphics, inventoryWidget);
			drawAdBanner(graphics, inventoryWidget);
		}
		return null;
	}

	static List<String> getConfiguredNames(String raw, Pattern splitPattern)
	{
		List<String> names = new ArrayList<>();
		String trimmed = raw.trim();
		if (trimmed.isEmpty())
		{
			return names;
		}

		for (String token : splitPattern.split(trimmed))
		{
			String normalized = normalizeName(token);
			if (!normalized.isEmpty())
			{
				names.add(normalized);
			}
		}

		return names;
	}

	private static String normalizeName(String name)
	{
		return name == null ? "" : name.replace('\u00A0', ' ').trim().toLowerCase(Locale.ROOT);
	}

	private static List<DropTarget> orderTargetsBySequence(List<DropTarget> targets, List<Integer> slotSequence)
	{
		if (targets.isEmpty() || slotSequence == null || slotSequence.isEmpty())
		{
			return targets;
		}

		Map<Integer, DropTarget> targetBySlot = new HashMap<>();
		for (DropTarget target : targets)
		{
			targetBySlot.put(target.getSlot(), target);
		}

		List<DropTarget> orderedTargets = new ArrayList<>(targets.size());
		for (int slot : slotSequence)
		{
			DropTarget target = targetBySlot.get(slot);
			if (target != null)
			{
				orderedTargets.add(target);
			}
		}

		return orderedTargets.isEmpty() ? targets : orderedTargets;
	}

	static List<DropTarget> buildTargets(Client client, ItemContainer inventory, List<String> configuredNames, Widget inventoryWidget, int anchorSlot)
	{
		Map<String, Integer> configuredOrder = new HashMap<>();
		for (int i = 0; i < configuredNames.size(); i++)
		{
			configuredOrder.putIfAbsent(configuredNames.get(i), i);
		}

		List<DropTarget> targets = new ArrayList<>();
		Map<Integer, List<DropTarget>> groupedTargets = new HashMap<>();
		Item[] items = inventory.getItems();
		for (int slot = 0; slot < Math.min(items.length, INVENTORY_SIZE); slot++)
		{
			Item item = items[slot];
			if (item == null || item.getId() <= 0)
			{
				continue;
			}

			String normalizedName = normalizeName(client.getItemDefinition(item.getId()).getName());
			Integer order = configuredOrder.get(normalizedName);
			if (order == null)
			{
				continue;
			}

			Rectangle bounds = null;
			if (inventoryWidget != null)
			{
				Widget slotWidget = inventoryWidget.getChild(slot);
				if (slotWidget != null)
				{
					bounds = slotWidget.getBounds();
				}
			}

			groupedTargets.computeIfAbsent(order, ignored -> new ArrayList<>())
				.add(new DropTarget(order, slot, bounds, normalizedName));
		}

		List<Integer> orders = new ArrayList<>(groupedTargets.keySet());
		orders.sort(Integer::compareTo);
		for (int order : orders)
		{
			targets.addAll(orderTargetsForAimTrainer(groupedTargets.get(order), anchorSlot));
		}
		return targets;
	}

	private static List<DropTarget> orderTargetsForAimTrainer(List<DropTarget> group, int anchorSlot)
	{
		if (group == null || group.isEmpty())
		{
			return List.of();
		}

		List<DropTarget> remaining = new ArrayList<>(group);
		List<DropTarget> ordered = new ArrayList<>(group.size());
		DropTarget current = removeBestStartingTarget(remaining, anchorSlot);
		ordered.add(current);

		while (!remaining.isEmpty())
		{
			DropTarget next = pickFarthestTarget(current, remaining);
			remaining.remove(next);
			ordered.add(next);
			current = next;
		}

		return ordered;
	}

	private static DropTarget removeBestStartingTarget(List<DropTarget> remaining, int anchorSlot)
	{
		if (anchorSlot >= 0)
		{
			DropTarget anchoredStart = pickFarthestFromSlot(anchorSlot, remaining);
			remaining.remove(anchoredStart);
			return anchoredStart;
		}

		double bestDistance = Double.MAX_VALUE;
		DropTarget best = remaining.get(0);
		for (DropTarget target : remaining)
		{
			double distance = distanceToInventoryCenter(target);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = target;
			}
		}

		remaining.remove(best);
		return best;
	}

	private static DropTarget pickFarthestFromSlot(int slot, List<DropTarget> remaining)
	{
		DropTarget best = remaining.get(0);
		int bestScore = Integer.MIN_VALUE;
		for (DropTarget candidate : remaining)
		{
			int score = slotDistanceScore(slot, candidate.getSlot());
			if (score > bestScore)
			{
				bestScore = score;
				best = candidate;
			}
		}

		return best;
	}

	private static DropTarget pickFarthestTarget(DropTarget current, List<DropTarget> remaining)
	{
		DropTarget best = remaining.get(0);
		int bestScore = Integer.MIN_VALUE;
		for (DropTarget candidate : remaining)
		{
			int score = slotDistanceScore(current, candidate);
			if (score > bestScore)
			{
				bestScore = score;
				best = candidate;
			}
		}

		return best;
	}

	private static int slotDistanceScore(DropTarget a, DropTarget b)
	{
		return slotDistanceScore(a.getSlot(), b.getSlot());
	}

	private static int slotDistanceScore(int aSlot, int bSlot)
	{
		int rowDistance = Math.abs(getSlotRow(aSlot) - getSlotRow(bSlot));
		int colDistance = Math.abs(getSlotColumn(aSlot) - getSlotColumn(bSlot));
		return rowDistance * 100 + colDistance * 10 + Math.abs(aSlot - bSlot);
	}

	private static double distanceToInventoryCenter(DropTarget target)
	{
		double row = getSlotRow(target.getSlot());
		double col = getSlotColumn(target.getSlot());
		double rowCenter = 1.5;
		double colCenter = 3.0;
		return Math.hypot(row - rowCenter, col - colCenter);
	}

	private static int getSlotRow(int slot)
	{
		return slot / 4;
	}

	private static int getSlotColumn(int slot)
	{
		return slot % 4;
	}

	private void drawInventoryMask(Graphics2D graphics, Widget inventoryWidget)
	{
		Rectangle bounds = inventoryWidget.getBounds();
		graphics.setPaint(new GradientPaint(
			bounds.x, bounds.y, new Color(8, 10, 24, config.dopamineMode() ? 130 : 185),
			bounds.x + bounds.width, bounds.y + bounds.height, new Color(33, 10, 44, config.dopamineMode() ? 165 : 185)));
		graphics.fill(bounds);
	}

	private void drawDropPath(Graphics2D graphics, List<DropTarget> targets)
	{
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		Stroke originalStroke = graphics.getStroke();
		List<DropTarget> visibleTargets = targets.subList(0, Math.min(targets.size(), LOOKAHEAD_TARGETS));
		DropTarget current = visibleTargets.get(0);

		graphics.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		for (int i = 0; i + 1 < visibleTargets.size(); i++)
		{
			DropTarget target = visibleTargets.get(i);
			if (target.getBounds() == null || visibleTargets.get(i + 1).getBounds() == null)
			{
				continue;
			}
			Point center = target.getCenter();
			Point next = visibleTargets.get(i + 1).getCenter();
			graphics.setColor(new Color(255, 255, 255, 35));
			drawConnection(graphics, center, next, i);
			graphics.setColor(new Color(255, 126, 171, 180));
			drawConnection(graphics, center, next, i);
		}

		for (int i = visibleTargets.size() - 1; i >= 1; i--)
		{
			drawPreviewTarget(graphics, visibleTargets.get(i), visibleTargets.get(i).getCenter(), i);
		}

		drawActiveTarget(graphics, current, current.getCenter(), targets.size());
		graphics.setStroke(originalStroke);
	}

	private void drawPreviewTarget(Graphics2D graphics, DropTarget target, Point center, int depth)
	{
		Rectangle bounds = target.getBounds();
		if (bounds == null)
		{
			return;
		}
		int alpha = Math.max(55, 145 - depth * 35);
		graphics.setColor(new Color(255, 255, 255, 18));
		graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
		graphics.setColor(new Color(89, 196, 255, alpha));
		graphics.fillOval(center.x - 11, center.y - 11, 22, 22);
		graphics.setColor(new Color(255, 255, 255, alpha));
		graphics.drawOval(center.x - 11, center.y - 11, 22, 22);
	}

	private void drawActiveTarget(Graphics2D graphics, DropTarget target, Point center, int remainingCount)
	{
		Rectangle bounds = target.getBounds();
		if (bounds == null)
		{
			return;
		}
		float progress = getApproachProgress();
		int approachRadius = 18 + Math.round((1f - progress) * 24f);
		float pulse = config.dopamineMode() ? (float) (0.88 + 0.12 * Math.sin(System.currentTimeMillis() / 90.0)) : 1f;
		float rainbowPhase = (System.currentTimeMillis() % 1200L) / 1200f;
		Color activeColor = config.dopamineMode() ? rainbowColor(rainbowPhase) : new Color(255, 126, 171, 245);

		graphics.setColor(new Color(255, 255, 255, 28));
		graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);

		if (config.dopamineMode())
		{
			for (int i = 0; i < 3; i++)
			{
				int ring = approachRadius + i * 10;
				graphics.setColor(new Color(activeColor.getRed(), activeColor.getGreen(), activeColor.getBlue(), 90 - i * 20));
				graphics.draw(new Ellipse2D.Float(center.x - ring, center.y - ring, ring * 2f, ring * 2f));
			}
		}

		graphics.setColor(new Color(255, 255, 255, 60));
		graphics.fillOval(center.x - approachRadius, center.y - approachRadius, approachRadius * 2, approachRadius * 2);
		graphics.setColor(new Color(255, 214, 102, 230));
		graphics.drawOval(center.x - approachRadius, center.y - approachRadius, approachRadius * 2, approachRadius * 2);

		graphics.setColor(new Color(0, 0, 0, 220));
		graphics.fillOval(center.x - 22, center.y - 22, 44, 44);
		graphics.setColor(new Color(activeColor.getRed(), activeColor.getGreen(), activeColor.getBlue(), Math.min(255, Math.round(245 * pulse))));
		graphics.fillOval(center.x - 18, center.y - 18, 36, 36);
		graphics.setColor(Color.WHITE);
		graphics.drawOval(center.x - 18, center.y - 18, 36, 36);

		String text = "1";
		FontMetrics metrics = graphics.getFontMetrics();
		int textX = center.x - metrics.stringWidth(text) / 2;
		int textY = center.y + metrics.getAscent() / 2 - 3;
		graphics.setColor(Color.BLACK);
		graphics.drawString(text, textX + 1, textY + 1);
		graphics.setColor(Color.WHITE);
		graphics.drawString(text, textX, textY);
	}

	private float getApproachProgress()
	{
		long started = plugin.getDropTrainerAnimationStartMillis();
		if (started <= 0L)
		{
			return 1f;
		}

		long elapsed = System.currentTimeMillis() - started;
		long cycle = elapsed % APPROACH_DURATION_MS;
		return Math.min(1f, cycle / (float) APPROACH_DURATION_MS);
	}

	private void drawHud(Graphics2D graphics, Widget inventoryWidget)
	{
		Rectangle bounds = inventoryWidget.getBounds();
		int hudX = bounds.x + 8;
		int hudY = bounds.y - 80;
		int hudWidth = Math.max(110, bounds.width - 16);
		int hudHeight = 74;
		int potentialPoints = plugin.getCurrentDropTrainerPotentialPoints();
		long elapsedMillis = plugin.getCurrentDropTrainerElapsedMillis();
		float decayProgress = plugin.getCurrentDropTrainerDecayProgress();

		graphics.setPaint(new GradientPaint(
			hudX, hudY, new Color(28, 35, 63, 245),
			hudX + hudWidth, hudY + hudHeight, rainbowColor((System.currentTimeMillis() % 2000L) / 2000f)));
		graphics.fillRoundRect(hudX, hudY, hudWidth, hudHeight, 16, 16);
		graphics.setColor(new Color(255, 255, 255, 60));
		graphics.drawRoundRect(hudX, hudY, hudWidth, hudHeight, 16, 16);

		Font gradeFont = graphics.getFont().deriveFont(Font.BOLD, 40f);
		String liveGrade = getLiveGrade();
		FontMetrics gradeMetrics = graphics.getFontMetrics(gradeFont);
		int gradeTextWidth = gradeMetrics.stringWidth(liveGrade);
		int gradeTextX = hudX + hudWidth - gradeTextWidth - 19;
		int gradeCenterX = gradeTextX + gradeTextWidth / 2;
		int topTextWidth = Math.max(24, gradeTextX - hudX - 16);
		int bottomTextWidth = hudWidth - 20;

		String scoreLine = "Score " + plugin.getDropTrainerScore();
		String comboLine = plugin.getDropTrainerCombo() > 1 ? plugin.getDropTrainerCombo() + "x combo" : "combo ready";
		if (plugin.getDropTrainerMissCount() > 0)
		{
			comboLine += "  " + plugin.getDropTrainerMissCount()
				+ (plugin.getDropTrainerMissCount() == 1 ? " miss" : " misses");
		}
		else if (plugin.isCurrentDropTrainerPerfectRun())
		{
			comboLine += "  perfect";
		}
		String timerLine = "NOW " + potentialPoints + "  " + elapsedMillis + "ms";
		String hypeLine = getTimerHypeLine(potentialPoints);

		java.awt.Shape oldClip = graphics.getClip();
		graphics.setClip(new Rectangle(hudX + 8, hudY + 6, topTextWidth, 34));
		graphics.setColor(Color.BLACK);
		graphics.drawString(scoreLine, hudX + 11, hudY + 19);
		graphics.drawString(comboLine, hudX + 11, hudY + 35);
		graphics.setColor(Color.WHITE);
		graphics.drawString(scoreLine, hudX + 10, hudY + 18);
		graphics.setColor(new Color(255, 214, 102, 255));
		graphics.drawString(comboLine, hudX + 10, hudY + 34);
		graphics.setClip(new Rectangle(hudX + 8, hudY + 38, bottomTextWidth, 26));
		graphics.setColor(Color.BLACK);
		graphics.drawString(timerLine, hudX + 11, hudY + 49);
		graphics.drawString(hypeLine, hudX + 11, hudY + 61);
		graphics.setColor(getPotentialPointColor(potentialPoints));
		graphics.drawString(timerLine, hudX + 10, hudY + 48);
		graphics.setColor(new Color(89, 255, 184, 255));
		graphics.drawString(hypeLine, hudX + 10, hudY + 60);
		graphics.setClip(oldClip);

		drawDecayBar(graphics, hudX + 10, hudY + 66, hudWidth - 20, decayProgress, potentialPoints);

		drawGifStyleText(
			graphics,
			liveGrade,
			gradeTextX,
			hudY + 47,
			gradeFont,
			235,
			0.04f,
			11);

		drawHitSplash(graphics, gradeCenterX, hudY + 50);
	}

	private void drawDecayBar(Graphics2D graphics, int x, int y, int width, float progress, int potentialPoints)
	{
		int clampedWidth = Math.max(32, width);
		float clampedProgress = Math.max(0f, Math.min(1f, progress));
		int fillWidth = Math.max(0, Math.min(clampedWidth, Math.round((1f - clampedProgress) * clampedWidth)));
		Color barColor = getPotentialPointColor(potentialPoints);

		graphics.setColor(new Color(0, 0, 0, 120));
		graphics.fillRoundRect(x, y, clampedWidth, 7, 8, 8);
		if (fillWidth > 0)
		{
			graphics.setPaint(new GradientPaint(
				x, y, withAlpha(barColor, 255),
				x + fillWidth, y + 7, withAlpha(rainbowColor((System.currentTimeMillis() % 1100L) / 1100f), 255)));
			graphics.fillRoundRect(x, y, fillWidth, 7, 8, 8);
		}
		graphics.setColor(new Color(255, 255, 255, 90));
		graphics.drawRoundRect(x, y, clampedWidth, 7, 8, 8);
	}

	private Color getPotentialPointColor(int potentialPoints)
	{
		switch (potentialPoints)
		{
			case 300:
				return POSITIVE_SPLASH;
			case 180:
				return new Color(255, 214, 102, 255);
			case 100:
				return new Color(255, 145, 92, 255);
			default:
				return NEGATIVE_SPLASH;
		}
	}

	private String getTimerHypeLine(int potentialPoints)
	{
		if (plugin.getDropTrainerMissCount() > 0)
		{
			return "PERFECT RUN DEAD";
		}
		if (plugin.isCurrentDropTrainerPerfectRun())
		{
			return "S++ STILL ALIVE";
		}

		switch (potentialPoints)
		{
			case 300:
				return "PERFECT OR BUST";
			case 180:
				return "RANK MELTING";
			case 100:
				return "TOO SLOW";
			default:
				return "SALVAGE THE RUN";
		}
	}

	private void drawHitSplash(Graphics2D graphics, int centerX, int centerY)
	{
		long lastHit = plugin.getLastDropTrainerHitMillis();
		if (lastHit <= 0L)
		{
			return;
		}

		long age = System.currentTimeMillis() - lastHit;
		if (age > HIT_FLASH_MS)
		{
			return;
		}

		float progress = 1f - (age / (float) HIT_FLASH_MS);
		int alpha = Math.min(255, Math.max(0, Math.round(progress * 255)));
		int scale = 24 + Math.round((1f - progress) * 18);
		int lastPoints = plugin.getLastDropTrainerPoints();
		String hitText = lastPoints > 0 ? "+" + lastPoints : Integer.toString(lastPoints);
		Color splashColor = lastPoints >= 0 ? POSITIVE_SPLASH : NEGATIVE_SPLASH;

		graphics.setColor(new Color(255, 255, 255, Math.min(200, alpha / 2)));
		graphics.fillOval(centerX - scale / 2, centerY - scale / 2, scale, scale);

		FontMetrics metrics = graphics.getFontMetrics();
		int x = centerX - metrics.stringWidth(hitText) / 2;
		int y = centerY + metrics.getAscent() / 2 - 2;
		graphics.setColor(new Color(0, 0, 0, alpha));
		graphics.drawString(hitText, x + 1, y + 1);
		graphics.setColor(withAlpha(splashColor, alpha));
		graphics.drawString(hitText, x, y);
	}

	private void drawJudgementSplash(Graphics2D graphics, Widget inventoryWidget)
	{
		long lastHit = plugin.getLastDropTrainerHitMillis();
		if (lastHit <= 0L)
		{
			return;
		}

		long age = System.currentTimeMillis() - lastHit;
		if (age > JUDGEMENT_FLASH_MS)
		{
			return;
		}

		float progress = age / (float) JUDGEMENT_FLASH_MS;
		int alpha = Math.max(0, Math.min(255, Math.round((1f - progress) * 255)));
		Rectangle bounds = inventoryWidget.getBounds();
		String text = plugin.getLastDropTrainerJudgement();
		if (text == null || text.isEmpty())
		{
			return;
		}

		FontMetrics metrics = graphics.getFontMetrics();
		int x = bounds.x + bounds.width / 2 - metrics.stringWidth(text) / 2;
		int y = bounds.y + bounds.height / 2 - 40 - Math.round(progress * 16);

		for (int i = 0; i < 3; i++)
		{
			graphics.setColor(new Color(255, 255, 255, Math.max(0, alpha / (4 + i))));
			graphics.drawString(text, x + i + 1, y + i + 1);
		}

		Color splash = plugin.getLastDropTrainerPoints() < 0
			? NEGATIVE_SPLASH
			: rainbowColor((System.currentTimeMillis() % 900L) / 900f);
		graphics.setColor(new Color(0, 0, 0, alpha));
		graphics.drawString(text, x + 2, y + 2);
		graphics.setColor(new Color(splash.getRed(), splash.getGreen(), splash.getBlue(), alpha));
		graphics.drawString(text, x, y);
	}

	private void drawAdBanner(Graphics2D graphics, Widget inventoryWidget)
	{
		Rectangle bounds = inventoryWidget.getBounds();
		String banner = plugin.getDropTrainerCombo() >= 10 ? "INSANE DROP RUSH" : "MEGA CLICK BLAST";
		int bannerWidth = bounds.width - 20;
		int x = bounds.x + 10;
		int y = bounds.y + bounds.height + 8;

		graphics.setPaint(new GradientPaint(
			x, y, rainbowColor((System.currentTimeMillis() % 1400L) / 1400f),
			x + bannerWidth, y + 18, rainbowColor(((System.currentTimeMillis() + 400L) % 1400L) / 1400f)));
		graphics.fillRoundRect(x, y, bannerWidth, 18, 12, 12);
		graphics.setColor(new Color(255, 255, 255, 150));
		graphics.drawRoundRect(x, y, bannerWidth, 18, 12, 12);

		FontMetrics metrics = graphics.getFontMetrics();
		int textX = x + bannerWidth / 2 - metrics.stringWidth(banner) / 2;
		int textY = y + 13;
		graphics.setColor(Color.BLACK);
		graphics.drawString(banner, textX + 1, textY + 1);
		graphics.setColor(Color.WHITE);
		graphics.drawString(banner, textX, textY);
	}

	private Color rainbowColor(float phase)
	{
		return Color.getHSBColor(phase % 1f, 0.72f, 1f);
	}

	private void drawResultsCard(Graphics2D graphics)
	{
		if (!plugin.isDropTrainerResultsActive())
		{
			return;
		}

		long age = System.currentTimeMillis() - plugin.getDropTrainerResultsStartMillis();
		if (age > RESULTS_FLASH_MS)
		{
			return;
		}

		float fade = 1f - (age / (float) RESULTS_FLASH_MS);
		float appear = Math.min(1f, age / 320f);
		int alpha = Math.max(0, Math.min(255, Math.round(245 * fade)));
		Rectangle inventoryBounds = getInventoryBoundsFallback();
		int width = Math.max(214, inventoryBounds.width + 14);
		int height = 188;
		int x = inventoryBounds.x + (inventoryBounds.width - width) / 2;
		int y = inventoryBounds.y + 22;
		int leftPad = 16;
		int statX = x + leftPad;
		int titleY = y + 32;
		int scoreY = y + 64;
		int comboY = y + 87;
		int perfectsY = y + 110;
		int missesY = y + 133;
		int avgY = y + 156;
		int verdictY = y + 180;
		int gradeX = x + width - 70;
		int gradeY = y + 36;
		int slamOffset = Math.round((1f - appear) * 28f);
		double tilt = Math.sin(age / 85.0) * 0.02 * (1f - Math.min(1f, age / 900f));
		AffineTransform oldTransform = graphics.getTransform();
		graphics.translate(0, slamOffset);
		graphics.rotate(tilt, x + width / 2.0, y + height / 2.0);

		graphics.setPaint(new GradientPaint(
			x, y, new Color(34, 10, 52, alpha),
			x + width, y + height, new Color(255, 92, 168, alpha)));
		graphics.fillRoundRect(x, y, width, height, 22, 22);
		graphics.setColor(new Color(255, 255, 255, Math.min(120, alpha / 2)));
		for (int i = 0; i < 6; i++)
		{
			int stripeY = y + 14 + i * 23;
			graphics.fillRect(x + 10, stripeY, width - 20, 5);
		}
		graphics.setColor(new Color(0, 0, 0, Math.min(255, alpha)));
		graphics.fillRect(x + 8, y + 8, width - 16, 8);
		graphics.fillRect(x + 8, y + height - 16, width - 16, 8);
		graphics.setColor(new Color(255, 255, 255, alpha));
		graphics.drawRoundRect(x, y, width, height, 22, 22);

		Font originalFont = graphics.getFont();
		Font titleFont = originalFont.deriveFont(Font.BOLD, 24f);
		Font statFont = originalFont.deriveFont(Font.BOLD, 17f);
		Font verdictFont = originalFont.deriveFont(Font.BOLD, 22f);

		drawGifStyleText(graphics, getResultsGrade(), gradeX, gradeY, verdictFont.deriveFont(31f), alpha, 0.05f, 9);
		drawGifStyleText(graphics, "DROP RAMPAGE", statX, titleY, titleFont, alpha, -0.035f, 0);
		drawGifStyleText(graphics, "SCORE " + plugin.getDropTrainerLastSessionScore(), statX, scoreY, statFont, alpha, 0f, 1);
		drawGifStyleText(graphics, "MAX COMBO " + plugin.getDropTrainerLastSessionMaxCombo() + "X", statX, comboY, statFont, alpha, 0f, 2);
		drawGifStyleText(graphics, "PERFECTS " + plugin.getDropTrainerLastSessionPerfectHitCount() + "/" + plugin.getDropTrainerLastSessionDrops(), statX, perfectsY, statFont, alpha, 0f, 3);
		drawGifStyleText(graphics, "MISSES " + plugin.getDropTrainerLastSessionMissCount(), statX, missesY, statFont, alpha, 0f, 4);
		drawGifStyleText(graphics, "AVG " + plugin.getDropTrainerLastSessionAverageMillis() + "MS", statX, avgY, statFont, alpha, 0f, 5);
		drawGifStyleText(graphics, getResultsVerdict(), statX, verdictY, verdictFont, alpha, 0.02f, 6);

		graphics.setFont(originalFont);
		graphics.setTransform(oldTransform);
	}

	private Rectangle getInventoryBoundsFallback()
	{
		Widget inventoryWidget = client.getWidget(InterfaceID.INVENTORY, 0);
		if (inventoryWidget != null)
		{
			return inventoryWidget.getBounds();
		}

		return new Rectangle(0, 0, 240, 340);
	}

	private String getResultsVerdict()
	{
		String grade = plugin.getLastSessionDropTrainerGrade();
		if ("S++".equals(grade))
		{
			return "PERFECT EXECUTION";
		}
		if ("S+".equals(grade))
		{
			return "ABSURD PRECISION";
		}
		if ("S".equals(grade))
		{
			return "NEARLY ILLEGAL";
		}
		if ("A++".equals(grade))
		{
			return "LASER FOCUSED";
		}
		if (grade.startsWith("A"))
		{
			return "SHARP WORK";
		}
		if (grade.startsWith("B"))
		{
			return "SOLID CHAOS";
		}
		if (grade.startsWith("C"))
		{
			return "HELD TOGETHER";
		}
		if (grade.startsWith("D"))
		{
			return "BARELY SURVIVED";
		}
		if (grade.startsWith("F"))
		{
			return "TOTAL CARNAGE";
		}
		return "DROP MADNESS";
	}

	private String getResultsGrade()
	{
		return plugin.getLastSessionDropTrainerGrade();
	}

	private String getLiveGrade()
	{
		return plugin.getCurrentDropTrainerGrade();
	}

	private void drawGifStyleText(Graphics2D graphics, String text, int x, int y, Font font, int alpha, float rotation, int seed)
	{
		AffineTransform oldTransform = graphics.getTransform();
		Font oldFont = graphics.getFont();
		long now = System.currentTimeMillis();
		int jitterX = Math.round((float) Math.sin((now / 55.0) + seed) * 2f);
		int jitterY = Math.round((float) Math.cos((now / 70.0) + seed * 0.7) * 2f);
		int cyanOffsetX = -3 + Math.round((float) Math.sin((now / 80.0) + seed) * 2f);
		int cyanOffsetY = 3;
		int pinkOffsetX = 3 + Math.round((float) Math.cos((now / 65.0) + seed) * 2f);
		int pinkOffsetY = -1;
		Color mainColor = new Color(40, 235, 245);
		Color cyanShadow = new Color(0, 190, 255);
		Color pinkShadow = new Color(255, 54, 145);

		graphics.setFont(font);
		graphics.rotate(rotation, x, y);

		graphics.setColor(withAlpha(cyanShadow, alpha));
		graphics.drawString(text, x + jitterX + cyanOffsetX, y + jitterY + cyanOffsetY);
		graphics.setColor(withAlpha(pinkShadow, alpha));
		graphics.drawString(text, x + jitterX + pinkOffsetX, y + jitterY + pinkOffsetY);
		graphics.setColor(new Color(0, 0, 0, alpha));
		graphics.drawString(text, x + jitterX + 1, y + jitterY + 4);
		graphics.setColor(withAlpha(mainColor, alpha));
		graphics.drawString(text, x + jitterX, y + jitterY);

		// Add extra scanline-style ghost copies for that crunchy GIF feel.
		graphics.setColor(new Color(mainColor.getRed(), mainColor.getGreen(), mainColor.getBlue(), Math.max(0, alpha / 4)));
		graphics.drawString(text, x + jitterX + 1, y + jitterY + 1);
		graphics.drawString(text, x + jitterX - 1, y + jitterY + 2);

		graphics.setTransform(oldTransform);
		graphics.setFont(oldFont);
	}

	private Color withAlpha(Color color, int alpha)
	{
		return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
	}

	private void drawConnection(Graphics2D graphics, Point start, Point end, int segmentIndex)
	{
		if (Math.abs(start.y - end.y) <= SAME_AXIS_THRESHOLD)
		{
			int direction = segmentIndex % 2 == 0 ? -1 : 1;
			int controlX = (start.x + end.x) / 2;
			int controlY = start.y + direction * CURVE_OFFSET;
			graphics.draw(new QuadCurve2D.Float(start.x, start.y, controlX, controlY, end.x, end.y));
			return;
		}

		if (Math.abs(start.x - end.x) <= SAME_AXIS_THRESHOLD)
		{
			int direction = segmentIndex % 2 == 0 ? -1 : 1;
			int controlX = start.x + direction * CURVE_OFFSET;
			int controlY = (start.y + end.y) / 2;
			graphics.draw(new QuadCurve2D.Float(start.x, start.y, controlX, controlY, end.x, end.y));
			return;
		}

		graphics.drawLine(start.x, start.y, end.x, end.y);
	}
}












