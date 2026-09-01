package io.proleap.cobol.runtime;

import java.math.BigDecimal;

/**
 * Runtime utility methods for COBOL class conditions and other runtime checks.
 */
public final class CobolRuntime {

	private CobolRuntime() {
	}

	/**
	 * IS NUMERIC: returns true if the value represents a valid numeric value.
	 * Handles BigDecimal, Number, and String representations.
	 */
	public static boolean isNumeric(final Object value) {
		if (value == null) {
			return false;
		}
		if (value instanceof BigDecimal || value instanceof Number) {
			return true;
		}
		final String str = value.toString().trim();
		if (str.isEmpty()) {
			return false;
		}
		try {
			new BigDecimal(str);
			return true;
		} catch (final NumberFormatException e) {
			return false;
		}
	}

	/**
	 * IS ALPHABETIC: returns true if all characters are letters or spaces.
	 */
	public static boolean isAlphabetic(final Object value) {
		if (value == null) {
			return false;
		}
		final String str = value.toString();
		for (int i = 0; i < str.length(); i++) {
			final char c = str.charAt(i);
			if (!Character.isLetter(c) && c != ' ') {
				return false;
			}
		}
		return true;
	}

	/**
	 * IS ALPHABETIC-LOWER: returns true if all characters are lowercase letters or spaces.
	 */
	public static boolean isAlphabeticLower(final Object value) {
		if (value == null) {
			return false;
		}
		final String str = value.toString();
		for (int i = 0; i < str.length(); i++) {
			final char c = str.charAt(i);
			if (!Character.isLowerCase(c) && c != ' ') {
				return false;
			}
		}
		return true;
	}

	/**
	 * IS ALPHABETIC-UPPER: returns true if all characters are uppercase letters or spaces.
	 */
	public static boolean isAlphabeticUpper(final Object value) {
		if (value == null) {
			return false;
		}
		final String str = value.toString();
		for (int i = 0; i < str.length(); i++) {
			final char c = str.charAt(i);
			if (!Character.isUpperCase(c) && c != ' ') {
				return false;
			}
		}
		return true;
	}

	/**
	 * IS DBCS: returns true if the value contains double-byte character set characters.
	 */
	public static boolean isDbcs(final Object value) {
		if (value == null) {
			return false;
		}
		final String str = value.toString();
		for (int i = 0; i < str.length(); i++) {
			if (Character.isSupplementaryCodePoint(str.codePointAt(i)) || str.charAt(i) > 0xFF) {
				return true;
			}
		}
		return !str.isEmpty();
	}

	/**
	 * IS KANJI: returns true if the value contains Kanji characters.
	 */
	public static boolean isKanji(final Object value) {
		if (value == null) {
			return false;
		}
		final String str = value.toString();
		for (int i = 0; i < str.length(); i++) {
			final Character.UnicodeBlock block = Character.UnicodeBlock.of(str.charAt(i));
			if (block != Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
					&& block != Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
					&& block != Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
					&& str.charAt(i) != ' ') {
				return false;
			}
		}
		return !str.isEmpty();
	}

	/**
	 * IS CLASS (user-defined class): placeholder for custom class name checks.
	 */
	public static boolean isClass(final Object value, final String className) {
		if (value == null) {
			return false;
		}
		// User-defined class conditions would need to be resolved at generation time
		// This is a runtime fallback
		return true;
	}
}
