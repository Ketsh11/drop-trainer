package com.ketsh.droptrainer;

enum DropTrainerDifficulty
{
	EASY("Easy", 460L, 720L, 1000L, 920L, 540, 760),
	MEDIUM("Medium", 400L, 620L, 900L, 840L, 480, 690),
	HARD("Hard", 340L, 540L, 800L, 760L, 420, 620);

	private final String displayName;
	private final long perfectWindowMillis;
	private final long greatWindowMillis;
	private final long goodWindowMillis;
	private final long decayTailMillis;
	private final int sGradeAverageMillis;
	private final int aGradeAverageMillis;

	DropTrainerDifficulty(
		String displayName,
		long perfectWindowMillis,
		long greatWindowMillis,
		long goodWindowMillis,
		long decayTailMillis,
		int sGradeAverageMillis,
		int aGradeAverageMillis)
	{
		this.displayName = displayName;
		this.perfectWindowMillis = perfectWindowMillis;
		this.greatWindowMillis = greatWindowMillis;
		this.goodWindowMillis = goodWindowMillis;
		this.decayTailMillis = decayTailMillis;
		this.sGradeAverageMillis = sGradeAverageMillis;
		this.aGradeAverageMillis = aGradeAverageMillis;
	}

	long getPerfectWindowMillis()
	{
		return perfectWindowMillis;
	}

	long getGreatWindowMillis()
	{
		return greatWindowMillis;
	}

	long getGoodWindowMillis()
	{
		return goodWindowMillis;
	}

	long getDecayTailMillis()
	{
		return decayTailMillis;
	}

	int getSGradeAverageMillis()
	{
		return sGradeAverageMillis;
	}

	int getAGradeAverageMillis()
	{
		return aGradeAverageMillis;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}

