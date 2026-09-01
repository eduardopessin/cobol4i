package io.proleap.cobol.runtime;

import java.math.BigDecimal;

/**
 * COBOL comparison operations per IBM ILE COBOL Language Reference V7R3.
 *
 * Comparison rules:
 * - Nonnumeric (NN): left-to-right, shorter operand padded with spaces on the right
 * - Numeric (NU): algebraic comparison by value (sign and magnitude)
 * - Group items always compared as alphanumeric
 * - Class conditions: NUMERIC, ALPHABETIC, ALPHABETIC-LOWER, ALPHABETIC-UPPER
 * - Sign conditions: POSITIVE, NEGATIVE, ZERO
 */
public final class CobolComparison {

	private CobolComparison() {
	}

	// ===================== Nonnumeric comparison =====================

	/**
	 * Compare two alphanumeric values per COBOL nonnumeric rules.
	 * Per IBM manual: comparison proceeds left to right, character by character.
	 * If operands differ in length, the shorter is padded with spaces on the right.
	 * Returns negative if a < b, zero if equal, positive if a > b.
	 */
	public static int compareAlphanumeric(final String a, final String b) {
		final String sa = (a == null) ? "" : a;
		final String sb = (b == null) ? "" : b;
		final int maxLen = Math.max(sa.length(), sb.length());

		for (int i = 0; i < maxLen; i++) {
			final char ca = (i < sa.length()) ? sa.charAt(i) : ' ';
			final char cb = (i < sb.length()) ? sb.charAt(i) : ' ';
			if (ca != cb) {
				return Character.compare(ca, cb);
			}
		}
		return 0;
	}

	/**
	 * Alphanumeric EQUAL TO.
	 */
	public static boolean alphanumericEqual(final String a, final String b) {
		return compareAlphanumeric(a, b) == 0;
	}

	/**
	 * Alphanumeric LESS THAN.
	 */
	public static boolean alphanumericLessThan(final String a, final String b) {
		return compareAlphanumeric(a, b) < 0;
	}

	/**
	 * Alphanumeric GREATER THAN.
	 */
	public static boolean alphanumericGreaterThan(final String a, final String b) {
		return compareAlphanumeric(a, b) > 0;
	}

	/**
	 * Alphanumeric LESS THAN OR EQUAL TO.
	 */
	public static boolean alphanumericLessOrEqual(final String a, final String b) {
		return compareAlphanumeric(a, b) <= 0;
	}

	/**
	 * Alphanumeric GREATER THAN OR EQUAL TO.
	 */
	public static boolean alphanumericGreaterOrEqual(final String a, final String b) {
		return compareAlphanumeric(a, b) >= 0;
	}

	// ===================== Numeric comparison =====================

	/**
	 * Compare two numeric values per COBOL numeric rules.
	 * Per IBM manual: comparison is algebraic (by sign and magnitude).
	 * Returns negative if a < b, zero if equal, positive if a > b.
	 */
	public static int compareNumeric(final BigDecimal a, final BigDecimal b) {
		final BigDecimal sa = (a == null) ? BigDecimal.ZERO : a;
		final BigDecimal sb = (b == null) ? BigDecimal.ZERO : b;
		return sa.compareTo(sb);
	}

	public static boolean numericEqual(final BigDecimal a, final BigDecimal b) {
		return compareNumeric(a, b) == 0;
	}

	public static boolean numericLessThan(final BigDecimal a, final BigDecimal b) {
		return compareNumeric(a, b) < 0;
	}

	public static boolean numericGreaterThan(final BigDecimal a, final BigDecimal b) {
		return compareNumeric(a, b) > 0;
	}

	public static boolean numericLessOrEqual(final BigDecimal a, final BigDecimal b) {
		return compareNumeric(a, b) <= 0;
	}

	public static boolean numericGreaterOrEqual(final BigDecimal a, final BigDecimal b) {
		return compareNumeric(a, b) >= 0;
	}

	// ===================== Mixed-type numeric comparison =====================

	/**
	 * Compare BigDecimal to String, converting the String to BigDecimal.
	 * This handles cases where the transformer generates
	 * compareNumeric(BigDecimal, moveNumericToAlphanumeric(...)).
	 */
	public static int compareNumeric(final BigDecimal a, final String b) {
		final BigDecimal sa = (a == null) ? BigDecimal.ZERO : a;
		BigDecimal sb;
		try {
			sb = (b == null || b.trim().isEmpty()) ? BigDecimal.ZERO : new BigDecimal(b.trim());
		} catch (NumberFormatException e) {
			sb = BigDecimal.ZERO;
		}
		return sa.compareTo(sb);
	}

	/**
	 * Compare two String values numerically, converting both to BigDecimal.
	 * Handles cases where the transformer generates compareNumeric(String, String).
	 */
	public static int compareNumeric(final String a, final String b) {
		BigDecimal sa;
		try {
			sa = (a == null || a.trim().isEmpty()) ? BigDecimal.ZERO : new BigDecimal(a.trim());
		} catch (NumberFormatException e) {
			sa = BigDecimal.ZERO;
		}
		BigDecimal sb;
		try {
			sb = (b == null || b.trim().isEmpty()) ? BigDecimal.ZERO : new BigDecimal(b.trim());
		} catch (NumberFormatException e) {
			sb = BigDecimal.ZERO;
		}
		return sa.compareTo(sb);
	}

	/**
	 * Compare String to BigDecimal, converting the String to BigDecimal.
	 */
	public static int compareNumeric(final String a, final BigDecimal b) {
		BigDecimal sa;
		try {
			sa = (a == null || a.trim().isEmpty()) ? BigDecimal.ZERO : new BigDecimal(a.trim());
		} catch (NumberFormatException e) {
			sa = BigDecimal.ZERO;
		}
		final BigDecimal sb = (b == null) ? BigDecimal.ZERO : b;
		return sa.compareTo(sb);
	}

	/**
	 * Compare BigDecimal to String using alphanumeric rules.
	 * In COBOL, when comparing a numeric field (PIC 9(n)) to an alphanumeric
	 * literal like "000", the numeric field's display representation is used.
	 * PIC 9(03) VALUE 0 displays as "000", not "0".
	 * We zero-pad the BigDecimal's digit string to the target length to match
	 * COBOL display behaviour.
	 */
	public static int compareAlphanumeric(final BigDecimal a, final String b) {
		final String sa = numericToDisplayString(a, b == null ? 0 : b.length());
		return compareAlphanumeric(sa, b);
	}

	/**
	 * Compare String to BigDecimal using alphanumeric rules.
	 */
	public static int compareAlphanumeric(final String a, final BigDecimal b) {
		final String sb = numericToDisplayString(b, a == null ? 0 : a.length());
		return compareAlphanumeric(a, sb);
	}

	/**
	 * Compare two BigDecimals using alphanumeric rules.
	 * This handles cases where the transformer generates
	 * compareAlphanumeric(BigDecimal, BigDecimal).
	 */
	public static int compareAlphanumeric(final BigDecimal a, final BigDecimal b) {
		final String sa = (a == null) ? "0" : a.toPlainString();
		final String sb = (b == null) ? "0" : b.toPlainString();
		return compareAlphanumeric(sa, sb);
	}

	/**
	 * Convert BigDecimal to COBOL display format for alphanumeric comparison.
	 * Removes decimal point and zero-pads to target length.
	 * PIC 9(03) VALUE 0 → "000", PIC 9(03)V9(02) VALUE 1.50 → "00150"
	 */
	private static String numericToDisplayString(final BigDecimal val, final int targetLen) {
		if (val == null) {
			if (targetLen <= 0) return "0";
			StringBuilder sb = new StringBuilder(targetLen);
			for (int i = 0; i < targetLen; i++) sb.append('0');
			return sb.toString();
		}
		String plain = val.abs().toPlainString();
		// Remove decimal point to match COBOL display representation
		plain = plain.replace(".", "");
		// Zero-pad left to target length
		if (targetLen > plain.length()) {
			StringBuilder sb = new StringBuilder(targetLen);
			for (int i = plain.length(); i < targetLen; i++) {
				sb.append('0');
			}
			sb.append(plain);
			plain = sb.toString();
		}
		// For negative values, the display representation includes a sign indicator
		if (val.signum() < 0) {
			return "-" + plain;
		}
		return plain;
	}

	/**
	 * Compare Object to String using alphanumeric rules.
	 * This handles cases where the transformer generates
	 * compareAlphanumeric(CustomType, String) — e.g., VARCHAR group types.
	 * Converts the Object to its string representation using CobolMove.groupToString().
	 */
	public static int compareAlphanumeric(final Object a, final String b) {
		final String sa = objectToAlphanumericString(a);
		return compareAlphanumeric(sa, b);
	}

	/**
	 * Compare String to Object using alphanumeric rules.
	 * Handles compareAlphanumeric(String, CustomType).
	 */
	public static int compareAlphanumeric(final String a, final Object b) {
		final String sb = objectToAlphanumericString(b);
		return compareAlphanumeric(a, sb);
	}

	/**
	 * Compare two Objects using alphanumeric rules.
	 * Handles compareAlphanumeric(CustomType, CustomType).
	 */
	public static int compareAlphanumeric(final Object a, final Object b) {
		final String sa = objectToAlphanumericString(a);
		final String sb = objectToAlphanumericString(b);
		return compareAlphanumeric(sa, sb);
	}

	/**
	 * Convert an object (possibly a COBOL group type) to its alphanumeric string representation.
	 */
	private static String objectToAlphanumericString(final Object obj) {
		if (obj == null) return "";
		if (obj instanceof String) return (String) obj;
		if (obj instanceof BigDecimal) return ((BigDecimal) obj).toPlainString();
		try {
			return CobolMove.groupToString(obj);
		} catch (final Exception e) {
			return obj.toString();
		}
	}

	// ===================== Class conditions =====================

	/**
	 * IS NUMERIC - true if value contains only digits, sign, and decimal point.
	 * Per IBM manual: for alphanumeric items, NUMERIC is true if all characters
	 * are digits (0-9). For numeric items, the operational sign must be valid.
	 */
	public static boolean isNumeric(final String value) {
		if (value == null || value.isEmpty()) {
			return false;
		}
		boolean hasDigit = false;
		boolean hasDecimal = false;
		boolean hasSign = false;
		for (int i = 0; i < value.length(); i++) {
			final char c = value.charAt(i);
			if (c >= '0' && c <= '9') {
				hasDigit = true;
			} else if (c == '.' && !hasDecimal) {
				hasDecimal = true;
			} else if ((c == '+' || c == '-') && i == 0 && !hasSign) {
				hasSign = true;
			} else {
				return false;
			}
		}
		return hasDigit;
	}

	/**
	 * IS ALPHABETIC - true if value contains only letters (A-Z, a-z) and spaces.
	 * Per IBM manual: ALPHABETIC test is true if operand contains only
	 * characters A through Z, a through z, and space.
	 */
	public static boolean isAlphabetic(final String value) {
		if (value == null || value.isEmpty()) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			final char c = value.charAt(i);
			if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == ' ')) {
				return false;
			}
		}
		return true;
	}

	/**
	 * IS ALPHABETIC-LOWER - true if value contains only lowercase letters and spaces.
	 * Per IBM manual: true if operand contains only a-z and space.
	 */
	public static boolean isAlphabeticLower(final String value) {
		if (value == null || value.isEmpty()) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			final char c = value.charAt(i);
			if (!((c >= 'a' && c <= 'z') || c == ' ')) {
				return false;
			}
		}
		return true;
	}

	/**
	 * IS ALPHABETIC-UPPER - true if value contains only uppercase letters and spaces.
	 * Per IBM manual: true if operand contains only A-Z and space.
	 */
	public static boolean isAlphabeticUpper(final String value) {
		if (value == null || value.isEmpty()) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			final char c = value.charAt(i);
			if (!((c >= 'A' && c <= 'Z') || c == ' ')) {
				return false;
			}
		}
		return true;
	}

	// ===================== Sign conditions =====================

	/**
	 * IS POSITIVE - true if value > 0.
	 * Per IBM manual: value is positive if it is greater than zero.
	 */
	public static boolean isPositive(final BigDecimal value) {
		return value != null && value.compareTo(BigDecimal.ZERO) > 0;
	}

	/**
	 * IS NEGATIVE - true if value < 0.
	 * Per IBM manual: value is negative if it is less than zero.
	 */
	public static boolean isNegative(final BigDecimal value) {
		return value != null && value.compareTo(BigDecimal.ZERO) < 0;
	}

	/**
	 * IS ZERO - true if value == 0.
	 * Per IBM manual: value is zero if it is equal to zero.
	 */
	public static boolean isZero(final BigDecimal value) {
		return value == null || value.compareTo(BigDecimal.ZERO) == 0;
	}

	/**
	 * IS ZERO for alphanumeric - true if all characters are '0'.
	 */
	public static boolean isZero(final String value) {
		return CobolConstants.isZeros(value);
	}

	// ===================== Mixed-type comparison =====================

	/**
	 * Compare numeric to alphanumeric per COBOL rules.
	 * Per IBM manual: when comparing numeric to nonnumeric, the numeric
	 * operand is converted to its external decimal representation and
	 * compared as alphanumeric.
	 */
	public static int compareNumericToAlphanumeric(final BigDecimal numeric, final String alphanumeric) {
		final String numStr = (numeric == null) ? "0" : numeric.toPlainString();
		return compareAlphanumeric(numStr, alphanumeric);
	}
}
