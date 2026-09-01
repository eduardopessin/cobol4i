package io.proleap.cobol.runtime;

/**
 * COBOL figurative constants per IBM ILE COBOL Language Reference V7R3.
 * ZERO/ZEROS/ZEROES, SPACE/SPACES, HIGH-VALUE/HIGH-VALUES,
 * LOW-VALUE/LOW-VALUES, QUOTE/QUOTES, ALL literal, NULL/NULLS.
 */
public final class CobolConstants {

	private CobolConstants() {
	}

	// --- Figurative constant values ---

	/** ZERO / ZEROS / ZEROES - numeric zero or character '0' depending on context. */
	public static final char ZERO_CHAR = '0';
	public static final String ZERO_STRING = "0";
	public static final java.math.BigDecimal ZERO_NUMERIC = java.math.BigDecimal.ZERO;

	/** SPACE / SPACES - one or more space characters. */
	public static final char SPACE_CHAR = ' ';
	public static final String SPACE_STRING = " ";

	/** HIGH-VALUE / HIGH-VALUES - highest value in collating sequence (0xFF). */
	public static final char HIGH_VALUE_CHAR = (char) 0xFF;

	/** LOW-VALUE / LOW-VALUES - lowest value in collating sequence (0x00). */
	public static final char LOW_VALUE_CHAR = (char) 0x00;

	/** QUOTE / QUOTES - quotation mark character. */
	public static final char QUOTE_CHAR = '"';
	public static final String QUOTE_STRING = "\"";

	// --- Methods to generate figurative constant strings of a given length ---

	/**
	 * Returns ZEROS repeated to fill the given length.
	 * Per IBM manual: when used in alphanumeric context, ZERO is character '0'.
	 */
	public static String zeros(final int length) {
		return repeat(ZERO_CHAR, length);
	}

	/**
	 * Returns SPACES repeated to fill the given length.
	 */
	public static String spaces(final int length) {
		return repeat(SPACE_CHAR, length);
	}

	/**
	 * Returns HIGH-VALUES repeated to fill the given length.
	 */
	public static String highValues(final int length) {
		return repeat(HIGH_VALUE_CHAR, length);
	}

	/**
	 * Returns LOW-VALUES repeated to fill the given length.
	 */
	public static String lowValues(final int length) {
		return repeat(LOW_VALUE_CHAR, length);
	}

	/**
	 * Returns QUOTES repeated to fill the given length.
	 */
	public static String quotes(final int length) {
		return repeat(QUOTE_CHAR, length);
	}

	/**
	 * ALL literal - repeats the given literal to fill the target length.
	 * Per IBM manual: the literal is repeated character by character to fill
	 * the receiving field, truncated on the right if needed.
	 */
	public static String allLiteral(final String literal, final int targetLength) {
		if (literal == null || literal.isEmpty() || targetLength <= 0) {
			return "";
		}
		final StringBuilder sb = new StringBuilder(targetLength);
		int idx = 0;
		while (sb.length() < targetLength) {
			sb.append(literal.charAt(idx));
			idx = (idx + 1) % literal.length();
		}
		return sb.toString();
	}

	/**
	 * Determines if a string represents SPACES (all space characters).
	 */
	public static boolean isSpaces(final String value) {
		if (value == null || value.isEmpty()) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			if (value.charAt(i) != SPACE_CHAR) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Determines if a BigDecimal value is SPACES.
	 * A numeric value is never SPACES in COBOL — this always returns false.
	 * Overload exists to avoid compile errors when transformer generates
	 * isSpaces() on a numeric field.
	 */
	public static boolean isSpaces(final java.math.BigDecimal value) {
		return false;
	}

	/**
	 * Determines if a group-level data item is SPACES.
	 * Serializes the group to a string using CobolMove.groupToString() and checks
	 * if all characters are spaces. In COBOL, comparing a group to SPACES means
	 * comparing the serialized alphanumeric content.
	 */
	public static boolean isSpaces(final Object group) {
		if (group == null) return false;
		if (group instanceof String) return isSpaces((String) group);
		if (group instanceof java.math.BigDecimal) return false;
		try {
			final String serialized = CobolMove.groupToString(group);
			return isSpaces(serialized);
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Determines if a string represents ZEROS (all '0' characters).
	 */
	public static boolean isZeros(final String value) {
		if (value == null || value.isEmpty()) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			if (value.charAt(i) != ZERO_CHAR) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Determines if a BigDecimal value is ZEROS (equals zero).
	 * Used when a numeric field (e.g. SQLCODE) is compared to figurative constant ZEROS.
	 */
	public static boolean isZeros(final java.math.BigDecimal value) {
		return value != null && value.signum() == 0;
	}

	/**
	 * Determines if a string represents HIGH-VALUES.
	 */
	public static boolean isHighValues(final String value) {
		if (value == null || value.isEmpty()) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			if (value.charAt(i) != HIGH_VALUE_CHAR) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Determines if a string represents LOW-VALUES.
	 */
	public static boolean isLowValues(final String value) {
		if (value == null || value.isEmpty()) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			if (value.charAt(i) != LOW_VALUE_CHAR) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Implements FUNCTION TEST-DATE-TIME(value, DATE).
	 * Returns true if the value is a valid date in ISO format (YYYY-MM-DD)
	 * or a valid numeric date (YYYYMMDD). Per IBM ILE COBOL Language Reference V7R3,
	 * TEST-DATE-TIME returns 0 (valid) or 1 (invalid).
	 */
	public static boolean testDateTimeIsValid(final String value) {
		if (value == null || value.trim().isEmpty()) {
			return false;
		}
		String trimmed = value.trim();
		// Try ISO format YYYY-MM-DD
		if (trimmed.length() == 10 && trimmed.charAt(4) == '-' && trimmed.charAt(7) == '-') {
			try {
				int year = Integer.parseInt(trimmed.substring(0, 4));
				int month = Integer.parseInt(trimmed.substring(5, 7));
				int day = Integer.parseInt(trimmed.substring(8, 10));
				if (year >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= 31) {
					return true;
				}
			} catch (NumberFormatException e) {
				return false;
			}
		}
		// Try numeric format YYYYMMDD
		if (trimmed.length() == 8) {
			try {
				int year = Integer.parseInt(trimmed.substring(0, 4));
				int month = Integer.parseInt(trimmed.substring(4, 6));
				int day = Integer.parseInt(trimmed.substring(6, 8));
				if (year >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= 31) {
					return true;
				}
			} catch (NumberFormatException e) {
				return false;
			}
		}
		return false;
	}

	/**
	 * Safe access to OCCURS array elements.
	 * In COBOL, accessing beyond OCCURS bounds reads adjacent memory (typically
	 * spaces/zeros). In Java, ArrayList.get() throws IndexOutOfBoundsException.
	 * This method auto-expands the list with default-constructed elements when
	 * the index is beyond the current size, matching COBOL's permissive behavior.
	 *
	 * @param list  the OCCURS array (List)
	 * @param index 0-based index
	 * @param <T>   element type
	 * @return the element at the given index, auto-created if beyond bounds
	 */
	@SuppressWarnings("unchecked")
	public static <T> T safeGet(final java.util.List<T> list, final int index) {
		if (index < 0) {
			// Negative index: return first element or create default
			if (!list.isEmpty()) {
				return list.get(0);
			}
			return null;
		}
		if (index < list.size()) {
			return list.get(index);
		}
		// Auto-expand the list to accommodate the index
		if (!list.isEmpty()) {
			final T prototype = list.get(0);
			while (list.size() <= index) {
				try {
					final T newElem = (T) prototype.getClass().getDeclaredConstructor().newInstance();
					list.add(newElem);
				} catch (final Exception e) {
					// If we can't create a new instance, try inner class constructor
					try {
						final Class<?> enclosing = prototype.getClass().getEnclosingClass();
						if (enclosing != null) {
							// Inner class needs enclosing instance - get from existing element
							final java.lang.reflect.Field outerField = findOuterField(prototype.getClass());
							if (outerField != null) {
								outerField.setAccessible(true);
								final Object outerInstance = outerField.get(prototype);
								final java.lang.reflect.Constructor<T> ctor =
									(java.lang.reflect.Constructor<T>) prototype.getClass().getDeclaredConstructor(enclosing);
								ctor.setAccessible(true);
								final T newElem = ctor.newInstance(outerInstance);
								list.add(newElem);
							} else {
								return list.get(list.size() - 1);
							}
						} else {
							return list.get(list.size() - 1);
						}
					} catch (final Exception e2) {
						// Last resort: return the last available element
						return list.get(list.size() - 1);
					}
				}
			}
			return list.get(index);
		}
		return null;
	}

	/** Find the synthetic outer-class field in an inner class. */
	private static java.lang.reflect.Field findOuterField(final Class<?> innerClass) {
		for (final java.lang.reflect.Field f : innerClass.getDeclaredFields()) {
			if (f.isSynthetic() && f.getName().startsWith("this$")) {
				return f;
			}
		}
		return null;
	}

	private static String repeat(final char c, final int length) {
		if (length <= 0) {
			return "";
		}
		final char[] chars = new char[length];
		java.util.Arrays.fill(chars, c);
		return new String(chars);
	}
}
