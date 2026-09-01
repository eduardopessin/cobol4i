package io.proleap.cobol.runtime;

import java.math.BigDecimal;
import java.util.Map;

/**
 * COBOL INITIALIZE statement per IBM ILE COBOL Language Reference V7R3.
 *
 * Rules per IBM manual (pp335-336):
 * - ALPHABETIC, ALPHANUMERIC, ALPHANUMERIC-EDITED: initialized to SPACES
 * - NUMERIC, NUMERIC-EDITED: initialized to ZEROS
 * - FILLER items are NOT initialized (unless REPLACING specified)
 * - REPLACING phrase: initializes only items of the specified category
 * - Items with REDEFINES are skipped
 * - INDEX, POINTER, and BOOLEAN data items are not affected
 * - Group items: INITIALIZE is applied recursively to subordinate items
 */
public final class CobolInitialize {

	/** Category of a COBOL data item for INITIALIZE purposes. */
	public enum Category {
		ALPHABETIC, ALPHANUMERIC, ALPHANUMERIC_EDITED, NUMERIC, NUMERIC_EDITED, GROUP
	}

	private CobolInitialize() {
	}

	/**
	 * Initialize an alphanumeric field to SPACES.
	 * Per IBM manual: ALPHABETIC, ALPHANUMERIC, and ALPHANUMERIC-EDITED
	 * items are set to SPACES.
	 */
	public static String initializeAlphanumeric(final int length) {
		return CobolConstants.spaces(length);
	}

	/**
	 * Initialize a numeric field to ZEROS.
	 * Per IBM manual: NUMERIC and NUMERIC-EDITED items are set to ZEROS.
	 */
	public static BigDecimal initializeNumeric(final int decimalDigits) {
		return BigDecimal.ZERO.setScale(decimalDigits);
	}

	/**
	 * Initialize a numeric field to ZEROS (integer form).
	 */
	public static long initializeNumericInteger() {
		return 0L;
	}

	/**
	 * Initialize a numeric-edited field to ZEROS as string.
	 * Per IBM manual: NUMERIC-EDITED items are set to ZEROS.
	 */
	public static String initializeNumericEdited(final int length) {
		return CobolConstants.zeros(length);
	}

	/**
	 * Get the default initialization value for a category.
	 * Per IBM manual: default depends on category.
	 *
	 * @param category the category of the data item
	 * @param length   the length of the field (for alphanumeric)
	 * @param scale    the decimal digits (for numeric)
	 * @return the initialized value as Object (String or BigDecimal)
	 */
	public static Object initializeByCategory(final Category category, final int length, final int scale) {
		switch (category) {
		case ALPHABETIC:
		case ALPHANUMERIC:
		case ALPHANUMERIC_EDITED:
			return CobolConstants.spaces(length);
		case NUMERIC:
			return BigDecimal.ZERO.setScale(scale);
		case NUMERIC_EDITED:
			return CobolConstants.zeros(length);
		case GROUP:
			return CobolConstants.spaces(length);
		default:
			return CobolConstants.spaces(length);
		}
	}

	/**
	 * INITIALIZE with REPLACING phrase.
	 * Per IBM manual: REPLACING initializes only items matching the specified category.
	 * Example: INITIALIZE WS-RECORD REPLACING NUMERIC DATA BY 999.
	 *
	 * @param category     the category to replace
	 * @param replaceValue the replacement value
	 * @param length       field length for string types
	 * @param scale        decimal scale for numeric types
	 * @return the replacement value fitted to the field
	 */
	public static Object initializeReplacing(final Category category, final Object replaceValue,
			final int length, final int scale) {
		if (replaceValue == null) {
			return initializeByCategory(category, length, scale);
		}

		switch (category) {
		case ALPHABETIC:
		case ALPHANUMERIC:
		case ALPHANUMERIC_EDITED:
			if (replaceValue instanceof String) {
				return CobolMove.moveAlphanumericToAlphanumeric((String) replaceValue, length);
			}
			return CobolConstants.spaces(length);
		case NUMERIC:
			if (replaceValue instanceof BigDecimal) {
				return ((BigDecimal) replaceValue).setScale(scale, java.math.RoundingMode.HALF_UP);
			}
			if (replaceValue instanceof Number) {
				return BigDecimal.valueOf(((Number) replaceValue).doubleValue()).setScale(scale,
						java.math.RoundingMode.HALF_UP);
			}
			return BigDecimal.ZERO.setScale(scale);
		case NUMERIC_EDITED:
			if (replaceValue instanceof String) {
				return CobolMove.moveAlphanumericToAlphanumeric((String) replaceValue, length);
			}
			return CobolConstants.zeros(length);
		default:
			return initializeByCategory(category, length, scale);
		}
	}

	/**
	 * Initialize a group item using reflection.
	 * Per IBM manual: INITIALIZE applied to a group applies recursively
	 * to all subordinate elementary items, except FILLER, REDEFINES,
	 * INDEX, POINTER, and BOOLEAN items.
	 *
	 * Field naming convention expected in generated Java:
	 * - Fields named "filler" or starting with "filler_" are skipped
	 * - String fields → SPACES
	 * - BigDecimal fields → ZEROS
	 * - int/long fields → 0
	 *
	 * @param group       the group object to initialize
	 * @param replacings  optional map of Category → replacement value (null for default INITIALIZE)
	 */
	public static void initializeGroup(final Object group, final Map<Category, Object> replacings) {
		if (group == null) {
			return;
		}

		for (final java.lang.reflect.Field field : group.getClass().getDeclaredFields()) {
			final String name = field.getName();

			// Skip FILLER items (unless REPLACING specified for their category)
			if (isFiller(name) && (replacings == null || replacings.isEmpty())) {
				continue;
			}

			// Skip static and final fields (constants, not data items)
			if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
				continue;
			}

			field.setAccessible(true);
			try {
				initializeField(group, field, replacings);
			} catch (final IllegalAccessException e) {
				// Cannot access, skip
			}
		}
	}

	/**
	 * Initialize a group item with default values (no REPLACING).
	 */
	public static void initializeGroup(final Object group) {
		initializeGroup(group, null);
	}

	// --- Internal helpers ---

	private static void initializeField(final Object group, final java.lang.reflect.Field field,
			final Map<Category, Object> replacings) throws IllegalAccessException {
		final Class<?> type = field.getType();

		if (type == String.class) {
			final Category cat = Category.ALPHANUMERIC;
			if (replacings != null && replacings.containsKey(cat)) {
				final String current = (String) field.get(group);
				final int len = (current != null) ? current.length() : 10;
				field.set(group, initializeReplacing(cat, replacings.get(cat), len, 0));
			} else {
				final String current = (String) field.get(group);
				final int len = (current != null) ? current.length() : 10;
				field.set(group, CobolConstants.spaces(len));
			}
		} else if (type == BigDecimal.class) {
			final Category cat = Category.NUMERIC;
			if (replacings != null && replacings.containsKey(cat)) {
				final BigDecimal current = (BigDecimal) field.get(group);
				final int scale = (current != null) ? current.scale() : 0;
				field.set(group, initializeReplacing(cat, replacings.get(cat), 0, scale));
			} else {
				final BigDecimal current = (BigDecimal) field.get(group);
				final int scale = (current != null) ? current.scale() : 0;
				field.set(group, BigDecimal.ZERO.setScale(scale));
			}
		} else if (type == int.class) {
			field.setInt(group, 0);
		} else if (type == long.class) {
			field.setLong(group, 0L);
		}
	}

	private static boolean isFiller(final String fieldName) {
		return "filler".equalsIgnoreCase(fieldName) || fieldName.toLowerCase().startsWith("filler_");
	}
}
