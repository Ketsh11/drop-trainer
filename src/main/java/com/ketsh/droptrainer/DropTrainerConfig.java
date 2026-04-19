package com.ketsh.droptrainer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("droptrainer")
public interface DropTrainerConfig extends Config
{

	@ConfigItem(
		keyName = "dropItemNames",
		name = "Drop item names",
		description = "Comma-separated item names to target in drop order, for example: trout, salmon, leaping trout",
		position = 1
	)
	default String dropItemNames()
	{
		return "";
	}

	@ConfigItem(
		keyName = "dopamineMode",
		name = "Dopamine mode",
		description = "Adds score, combo, hit flashes, and louder arcade presentation to the drop trainer",
		position = 2
	)
	default boolean dopamineMode()
	{
		return true;
	}

	@ConfigItem(
		keyName = "dropTrainerDifficulty",
		name = "Drop trainer difficulty",
		description = "Controls how strict the timing windows and rank thresholds are",
		position = 3
	)
	default DropTrainerDifficulty dropTrainerDifficulty()
	{
		return DropTrainerDifficulty.HARD;
	}
}

