package io.proleap.cobol.runtime;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * COBOL arithmetic operations per IBM ILE COBOL Language Reference V7R3.
 *
 * Implements ADD, SUBTRACT, MULTIPLY, DIVIDE, COMPUTE with:
 * - ROUNDED phrase (HALF_UP rounding)
 * - ON SIZE ERROR detection (overflow beyond PIC digits or division by zero)
 * - Decimal alignment per COBOL rules
 * - Remainder from DIVIDE ... REMAINDER
 */
public final class CobolArithmetic {

	/** Result of an arithmetic operation, including size error status. */
	public static class ArithResult {
		private final BigDecimal value;
		private final boolean sizeError;

		public ArithResult(final BigDecimal value, final boolean sizeError) {
			this.value = value;
			this.sizeError = sizeError;
		}

		public BigDecimal getValue() {
			return value;
		}

		public boolean isSizeError() {
			return sizeError;
		}
	}

	/** Result of a DIVIDE with REMAINDER. */
	public static class DivideResult {
		private final BigDecimal quotient;
		private final BigDecimal remainder;
		private final boolean sizeError;

		public DivideResult(final BigDecimal quotient, final BigDecimal remainder, final boolean sizeError) {
			this.quotient = quotient;
			this.remainder = remainder;
			this.sizeError = sizeError;
		}

		public BigDecimal getQuotient() {
			return quotient;
		}

		public BigDecimal getRemainder() {
			return remainder;
		}

		public boolean isSizeError() {
			return sizeError;
		}
	}

	private CobolArithmetic() {
	}

	// --- ADD ---

	/**
	 * ADD a TO b GIVING result.
	 * Per IBM manual: operands are added algebraically.
	 */
	public static BigDecimal add(final BigDecimal a, final BigDecimal b) {
		return safeValue(a).add(safeValue(b));
	}

	/**
	 * ADD with ROUNDED and SIZE ERROR detection.
	 */
	public static ArithResult addWithSizeError(final BigDecimal a, final BigDecimal b, final int integerDigits,
			final int decimalDigits, final boolean rounded) {
		final BigDecimal result = add(a, b);
		return applyPicture(result, integerDigits, decimalDigits, rounded);
	}

	// --- SUBTRACT ---

	/**
	 * SUBTRACT a FROM b.
	 * Per IBM manual: subtrahend is subtracted from minuend.
	 */
	public static BigDecimal subtract(final BigDecimal subtrahend, final BigDecimal minuend) {
		return safeValue(minuend).subtract(safeValue(subtrahend));
	}

	/**
	 * SUBTRACT with ROUNDED and SIZE ERROR detection.
	 */
	public static ArithResult subtractWithSizeError(final BigDecimal subtrahend, final BigDecimal minuend,
			final int integerDigits, final int decimalDigits, final boolean rounded) {
		final BigDecimal result = subtract(subtrahend, minuend);
		return applyPicture(result, integerDigits, decimalDigits, rounded);
	}

	// --- MULTIPLY ---

	/**
	 * MULTIPLY a BY b.
	 */
	public static BigDecimal multiply(final BigDecimal a, final BigDecimal b) {
		return safeValue(a).multiply(safeValue(b));
	}

	/**
	 * MULTIPLY with ROUNDED and SIZE ERROR detection.
	 */
	public static ArithResult multiplyWithSizeError(final BigDecimal a, final BigDecimal b, final int integerDigits,
			final int decimalDigits, final boolean rounded) {
		final BigDecimal result = multiply(a, b);
		return applyPicture(result, integerDigits, decimalDigits, rounded);
	}

	// --- DIVIDE ---

	/**
	 * DIVIDE a INTO b (result = b / a).
	 * Per IBM manual: DIVIDE a INTO b means b / a.
	 */
	public static BigDecimal divideInto(final BigDecimal divisor, final BigDecimal dividend) {
		if (safeValue(divisor).compareTo(BigDecimal.ZERO) == 0) {
			return BigDecimal.ZERO;
		}
		return safeValue(dividend).divide(safeValue(divisor), MathContext.DECIMAL128);
	}

	/**
	 * DIVIDE a BY b (result = a / b).
	 * Per IBM manual: DIVIDE a BY b means a / b.
	 */
	public static BigDecimal divideBy(final BigDecimal dividend, final BigDecimal divisor) {
		if (safeValue(divisor).compareTo(BigDecimal.ZERO) == 0) {
			return BigDecimal.ZERO;
		}
		return safeValue(dividend).divide(safeValue(divisor), MathContext.DECIMAL128);
	}

	/**
	 * DIVIDE with ROUNDED and SIZE ERROR detection.
	 */
	public static ArithResult divideWithSizeError(final BigDecimal dividend, final BigDecimal divisor,
			final int integerDigits, final int decimalDigits, final boolean rounded) {
		if (safeValue(divisor).compareTo(BigDecimal.ZERO) == 0) {
			// Division by zero is always a SIZE ERROR per IBM manual
			return new ArithResult(BigDecimal.ZERO.setScale(decimalDigits), true);
		}
		final BigDecimal result = safeValue(dividend).divide(safeValue(divisor), MathContext.DECIMAL128);
		return applyPicture(result, integerDigits, decimalDigits, rounded);
	}

	/**
	 * DIVIDE ... REMAINDER.
	 * Per IBM manual: quotient = integer part of division, remainder = dividend - (quotient * divisor).
	 */
	public static DivideResult divideWithRemainder(final BigDecimal dividend, final BigDecimal divisor,
			final int quotientIntDigits, final int quotientDecDigits, final boolean rounded) {
		if (safeValue(divisor).compareTo(BigDecimal.ZERO) == 0) {
			return new DivideResult(BigDecimal.ZERO.setScale(quotientDecDigits),
					BigDecimal.ZERO, true);
		}
		final BigDecimal dend = safeValue(dividend);
		final BigDecimal dsor = safeValue(divisor);

		// Calculate quotient
		final ArithResult quotientResult = applyPicture(
				dend.divide(dsor, MathContext.DECIMAL128),
				quotientIntDigits, quotientDecDigits, rounded);

		// Remainder = dividend - (quotient * divisor)
		final BigDecimal remainder = dend.subtract(quotientResult.getValue().multiply(dsor));

		return new DivideResult(quotientResult.getValue(), remainder, quotientResult.isSizeError());
	}

	// --- COMPUTE ---

	/**
	 * COMPUTE result = expression, with ROUNDED and SIZE ERROR.
	 * The expression is evaluated by the caller; this method applies PIC constraints.
	 */
	public static ArithResult compute(final BigDecimal expressionResult, final int integerDigits,
			final int decimalDigits, final boolean rounded) {
		return applyPicture(safeValue(expressionResult), integerDigits, decimalDigits, rounded);
	}

	// --- Exponentiation ---

	/**
	 * COBOL ** (exponentiation) operator.
	 * Per IBM manual: exponentiation has highest precedence after unary operators.
	 */
	public static BigDecimal power(final BigDecimal base, final BigDecimal exponent) {
		final BigDecimal b = safeValue(base);
		final BigDecimal e = safeValue(exponent);

		try {
			final int intExp = e.intValueExact();
			return b.pow(intExp, MathContext.DECIMAL128);
		} catch (final ArithmeticException ex) {
			// Non-integer exponent: use double math
			final double result = Math.pow(b.doubleValue(), e.doubleValue());
			if (Double.isInfinite(result) || Double.isNaN(result)) {
				return BigDecimal.ZERO;
			}
			return BigDecimal.valueOf(result);
		}
	}

	// --- Internal helpers ---

	/**
	 * Applies PIC constraints (scale + integer digit limit) and detects SIZE ERROR.
	 * Per IBM manual:
	 * - ROUNDED: if excess >= 5, increment least significant retained digit
	 * - SIZE ERROR: when result exceeds PIC integer capacity
	 */
	private static ArithResult applyPicture(final BigDecimal value, final int integerDigits,
			final int decimalDigits, final boolean rounded) {
		final RoundingMode mode = rounded ? RoundingMode.HALF_UP : RoundingMode.DOWN;
		final BigDecimal scaled = value.setScale(decimalDigits, mode);

		// Check for size error: integer part exceeds PIC capacity
		if (integerDigits > 0) {
			final BigDecimal absValue = scaled.abs();
			final BigDecimal maxValue = BigDecimal.TEN.pow(integerDigits);
			if (absValue.compareTo(maxValue) >= 0) {
				// SIZE ERROR: result does not fit
				return new ArithResult(scaled, true);
			}
		}

		return new ArithResult(scaled, false);
	}

	private static BigDecimal safeValue(final BigDecimal value) {
		return value != null ? value : BigDecimal.ZERO;
	}
}
