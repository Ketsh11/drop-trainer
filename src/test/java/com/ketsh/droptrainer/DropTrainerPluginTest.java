package com.ketsh.droptrainer;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class DropTrainerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(DropTrainerPlugin.class);
		RuneLite.main(args);
	}
}
