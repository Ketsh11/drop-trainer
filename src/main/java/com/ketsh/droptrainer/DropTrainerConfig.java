package com.ketsh.droptrainer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("droptrainer")
public interface DropTrainerConfig extends Config
{
	@ConfigItem(
		keyName = "enableDropTrainer",
		name = "Enable drop trainer",
		description = "Show an arcade-style overlay on the inventory for configured drops when your inventory is full",
		position = 1
	)
	default boolean enableDropTrainer()
	{
		return true;
	}

	@ConfigItem(
		keyName = "dropItemNames",
		name = "Drop item names",
		description = "Comma-separated item names to target in drop order, for example: trout, salmon, leaping trout",
		position = 2
	)
	default String dropItemNames()
	{
		return "";
	}

	@ConfigItem(
		keyName = "dopamineMode",
		name = "Dopamine mode",
		description = "Adds score, combo, hit flashes, and louder arcade presentation to the drop trainer",
		position = 3
	)
	default boolean dopamineMode()
	{
		return true;
	}

	@ConfigItem(
		keyName = "dropTrainerDifficulty",
		name = "Drop trainer difficulty",
		description = "Controls how strict the timing windows and rank thresholds are",
		position = 4
	)
	default DropTrainerDifficulty dropTrainerDifficulty()
	{
		return DropTrainerDifficulty.HARD;
	}
}
