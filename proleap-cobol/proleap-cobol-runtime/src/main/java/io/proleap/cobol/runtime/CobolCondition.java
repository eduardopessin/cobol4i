package io.proleap.cobol.runtime;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * COBOL 88-level condition names per IBM ILE COBOL Language Reference V7R3.
 *
 * Condition names (level 88):
 * - VALUE literal: true when associated variable equals the literal
 * - VALUE literal THRU literal: true when variable is in the range (inclusive)
 * - Multiple VALUES: true when variable matches any of the specified values
 * - SET condition-name TO TRUE: places the first VALUE in the conditional variable
 * - SET condition-name TO FALSE: places the FALSE IS value (if defined)
 */
public final class CobolCondition {

	private CobolCondition() {
	}

	// ===================== Single VALUE test =====================

	/**
	 * Test 88-level condition with a single alphanumeric VALUE.
	 * Per IBM manual: condition-name is true when the conditional variable
	 * equals the specified value.
	 */
	public static boolean testCondition(final String variable, final String value) {
		return CobolComparison.alphanumericEqual(variable, value);
	}

	/**
	 * Test 88-level condition with a single numeric VALUE.
	 */
	public static boolean testCondition(final BigDecimal variable, final BigDecimal value) {
		return CobolComparison.numericEqual(variable, value);
	}

	/**
	 * Test 88-level condition with a single integer VALUE.
	 */
	public static boolean testCondition(final int variable, final int value) {
		return variable == value;
	}

	/**
	 * Test 88-level condition with a single long VALUE.
	 */
	public static boolean testCondition(final long variable, final long value) {
		return variable == value;
	}

	// ===================== VALUE THRU (range) test =====================

	/**
	 * Test 88-level condition with VALUE low THRU high (alphanumeric range).
	 * Per IBM manual: condition is true when the conditional variable
	 * is >= low and <= high using nonnumeric comparison rules.
	 */
	public static boolean testConditionThru(final String variable, final String low, final String high) {
		return CobolComparison.alphanumericGreaterOrEqual(variable, low)
				&& CobolComparison.alphanumericLessOrEqual(variable, high);
	}

	/**
	 * Test 88-level condition with VALUE low THRU high (numeric range).
	 * Per IBM manual: inclusive range test using algebraic comparison.
	 */
	public static boolean testConditionThru(final BigDecimal variable, final BigDecimal low,
			final BigDecimal high) {
		return CobolComparison.numericGreaterOrEqual(variable, low)
				&& CobolComparison.numericLessOrEqual(variable, high);
	}

	/**
	 * Test 88-level condition with VALUE low THRU high (integer range).
	 */
	public static boolean testConditionThru(final int variable, final int low, final int high) {
		return variable >= low && variable <= high;
	}

	/**
	 * Test 88-level condition with VALUE low THRU high (long range).
	 */
	public static boolean testConditionThru(final long variable, final long low, final long high) {
		return variable >= low && variable <= high;
	}

	// ===================== Multiple VALUES test =====================

	/**
	 * Test 88-level condition with multiple alphanumeric VALUES.
	 * Per IBM manual: condition is true if variable matches any of the values.
	 */
	public static boolean testConditionMultiple(final String variable, final String... values) {
		for (final String value : values) {
			if (CobolComparison.alphanumericEqual(variable, value)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Test 88-level condition with multiple numeric VALUES.
	 */
	public static boolean testConditionMultiple(final BigDecimal variable, final BigDecimal... values) {
		for (final BigDecimal value : values) {
			if (CobolComparison.numericEqual(variable, value)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Test 88-level condition with multiple integer VALUES.
	 */
	public static boolean testConditionMultiple(final int variable, final int... values) {
		for (final int value : values) {
			if (variable == value) {
				return true;
			}
		}
		return false;
	}

	// ===================== Combined VALUES and THRU test =====================

	/**
	 * Represents a single value or a range (THRU) for 88-level conditions.
	 */
	public static class ValueOrRange {
		private final String singleValue;
		private final String lowValue;
		private final String highValue;
		private final boolean isRange;

		/** Single VALUE. */
		public ValueOrRange(final String value) {
			this.singleValue = value;
			this.lowValue = null;
			this.highValue = null;
			this.isRange = false;
		}

		/** VALUE low THRU high. */
		public ValueOrRange(final String low, final String high) {
			this.singleValue = null;
			this.lowValue = low;
			this.highValue = high;
			this.isRange = true;
		}

		public boolean test(final String variable) {
			if (isRange) {
				return testConditionThru(variable, lowValue, highValue);
			}
			return testCondition(variable, singleValue);
		}
	}

	/**
	 * Represents a numeric value or range for 88-level conditions.
	 */
	public static class NumericValueOrRange {
		private final BigDecimal singleValue;
		private final BigDecimal lowValue;
		private final BigDecimal highValue;
		private final boolean isRange;

		public NumericValueOrRange(final BigDecimal value) {
			this.singleValue = value;
			this.lowValue = null;
			this.highValue = null;
			this.isRange = false;
		}

		public NumericValueOrRange(final BigDecimal low, final BigDecimal high) {
			this.singleValue = null;
			this.lowValue = low;
			this.highValue = high;
			this.isRange = true;
		}

		public boolean test(final BigDecimal variable) {
			if (isRange) {
				return testConditionThru(variable, lowValue, highValue);
			}
			return testCondition(variable, singleValue);
		}
	}

	/**
	 * Test 88-level with mixed VALUES and THRU ranges (alphanumeric).
	 * Per IBM manual: condition is true if variable matches any single value
	 * or falls within any THRU range.
	 */
	public static boolean testConditionMixed(final String variable, final List<ValueOrRange> valuesAndRanges) {
		for (final ValueOrRange vor : valuesAndRanges) {
			if (vor.test(variable)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Test 88-level with mixed VALUES and THRU ranges (numeric).
	 */
	public static boolean testConditionMixed(final BigDecimal variable,
			final List<NumericValueOrRange> valuesAndRanges) {
		for (final NumericValueOrRange vor : valuesAndRanges) {
			if (vor.test(variable)) {
				return true;
			}
		}
		return false;
	}

	// ===================== SET condition-name TO TRUE =====================

	/**
	 * SET condition-name TO TRUE.
	 * Per IBM manual (p413): places the first VALUE associated with
	 * the condition-name into the conditional variable.
	 * The caller should pass the first VALUE literal.
	 *
	 * For alphanumeric: returns the first VALUE fitted to field length.
	 */
	public static String setToTrue(final String firstValue, final int fieldLength) {
		return CobolMove.moveAlphanumericToAlphanumeric(firstValue, fieldLength);
	}

	/**
	 * SET condition-name TO TRUE for numeric.
	 * Returns the first VALUE.
	 */
	public static BigDecimal setToTrue(final BigDecimal firstValue) {
		return (firstValue != null) ? firstValue : BigDecimal.ZERO;
	}

	/**
	 * SET condition-name TO TRUE for integer.
	 */
	public static int setToTrueInt(final int firstValue) {
		return firstValue;
	}

	// ===================== SET condition-name TO FALSE =====================

	/**
	 * SET condition-name TO FALSE.
	 * Per IBM manual: places the FALSE IS value into the conditional variable.
	 * Only valid if the 88-level has a FALSE IS clause.
	 */
	public static String setToFalse(final String falseValue, final int fieldLength) {
		return CobolMove.moveAlphanumericToAlphanumeric(falseValue, fieldLength);
	}

	public static BigDecimal setToFalse(final BigDecimal falseValue) {
		return (falseValue != null) ? falseValue : BigDecimal.ZERO;
	}

	public static int setToFalseInt(final int falseValue) {
		return falseValue;
	}
}
