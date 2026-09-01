package io.proleap.cobol.runtime;

/**
 * IBM i indicator array support.
 * Represents the 99 indicators used in IBM i COBOL programs.
 */
public class IndicatorArray {

	private final boolean[] indicators;

	public IndicatorArray() {
		this.indicators = new boolean[100]; // 0-99, index 0 unused
	}

	public boolean get(final int index) {
		checkBounds(index);
		return indicators[index];
	}

	public void set(final int index, final boolean value) {
		checkBounds(index);
		indicators[index] = value;
	}

	public void setOn(final int index) {
		set(index, true);
	}

	public void setOff(final int index) {
		set(index, false);
	}

	public void clear() {
		java.util.Arrays.fill(indicators, false);
	}

	private void checkBounds(final int index) {
		if (index < 1 || index > 99) {
			throw new IllegalArgumentException("Indicator index must be between 1 and 99, got: " + index);
		}
	}
}
