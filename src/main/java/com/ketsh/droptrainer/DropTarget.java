package com.ketsh.droptrainer;

import java.awt.Point;
import java.awt.Rectangle;
import lombok.Value;

@Value
class DropTarget
{
	int configuredOrder;
	int slot;
	Rectangle bounds;
	String itemName;

	Point getCenter()
	{
		if (bounds == null)
		{
			return new Point(0, 0);
		}
		return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
	}
}

