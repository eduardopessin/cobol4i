package io.proleap.cobol.runtime;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Utility methods for COBOL data type operations.
 * Handles packed decimal, zoned decimal, and other COBOL-specific types.
 */
public final class CobolDataTypes {

	private CobolDataTypes() {
	}

	/**
	 * Converts a packed decimal (COMP-3) byte array to BigDecimal.
	 */
	public static BigDecimal fromPackedDecimal(final byte[] packed, final int scale) {
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < packed.length; i++) {
			final int high = (packed[i] >> 4) & 0x0F;
			final int low = packed[i] & 0x0F;

			if (i < packed.length - 1) {
				sb.append(high);
				sb.append(low);
			} else {
				sb.append(high);
				// Last nibble is the sign
				if (low == 0x0D || low == 0x0B) {
					sb.insert(0, '-');
				}
			}
		}

		return new BigDecimal(sb.toString()).movePointLeft(scale);
	}

	/**
	 * Converts a zoned decimal string to BigDecimal.
	 */
	public static BigDecimal fromZonedDecimal(final String zoned, final int scale) {
		if (zoned == null || zoned.isEmpty()) {
			return BigDecimal.ZERO;
		}
		return new BigDecimal(zoned.replaceAll("[^0-9.+-]", "")).movePointLeft(scale);
	}

	/**
	 * Converts a COMP-4/BINARY value to the appropriate Java type.
	 */
	public static long fromBinary(final byte[] binary) {
		long result = 0;
		for (final byte b : binary) {
			result = (result << 8) | (b & 0xFF);
		}
		return result;
	}

	/**
	 * Truncates or pads a numeric value to fit COBOL PIC specifications.
	 */
	public static BigDecimal fitToScale(final BigDecimal value, final int integerDigits, final int decimalDigits) {
		if (value == null) {
			return BigDecimal.ZERO.setScale(decimalDigits);
		}
		return value.setScale(decimalDigits, RoundingMode.HALF_UP);
	}

	/**
	 * MOVE alphanumeric: right-pad with spaces or truncate to length.
	 */
	public static String moveAlphanumeric(final String source, final int targetLength) {
		if (source == null) {
			return " ".repeat(targetLength);
		}
		if (source.length() >= targetLength) {
			return source.substring(0, targetLength);
		}
		return source + " ".repeat(targetLength - source.length());
	}

	/**
	 * MOVE numeric: convert string to BigDecimal with scale.
	 */
	public static BigDecimal moveNumeric(final String source, final int integerDigits, final int decimalDigits) {
		try {
			return new BigDecimal(source.trim()).setScale(decimalDigits, RoundingMode.HALF_UP);
		} catch (final NumberFormatException e) {
			return BigDecimal.ZERO.setScale(decimalDigits);
		}
	}

	/**
	 * MOVE CORRESPONDING: copy fields with matching names between groups.
	 * Implemented via reflection at runtime.
	 */
	public static void moveCorresponding(final Object source, final Object target) {
		if (source == null || target == null) {
			return;
		}

		for (final java.lang.reflect.Field sourceField : source.getClass().getDeclaredFields()) {
			try {
				final java.lang.reflect.Field targetField = target.getClass().getDeclaredField(sourceField.getName());
				sourceField.setAccessible(true);
				targetField.setAccessible(true);
				targetField.set(target, sourceField.get(source));
			} catch (final NoSuchFieldException e) {
				// No matching field, skip
			} catch (final IllegalAccessException e) {
				// Cannot access, skip
			}
		}
	}
}
