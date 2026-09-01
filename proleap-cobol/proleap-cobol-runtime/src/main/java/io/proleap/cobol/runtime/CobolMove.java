package io.proleap.cobol.runtime;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * COBOL MOVE statement semantics per IBM ILE COBOL Language Reference V7R3.
 *
 * Rules implemented:
 * - Alphanumeric to alphanumeric: left-justified, space-padded/truncated on right
 * - Alphanumeric to numeric: de-edit, right-justify digits, zero-fill on left
 * - Numeric to numeric: decimal point alignment, zero-fill, truncate if needed
 * - Numeric to alphanumeric: move as if alphanumeric (external representation)
 * - Group moves: treated as alphanumeric regardless of subordinate types
 * - MOVE CORRESPONDING: match by name between groups
 * - Figurative constants: SPACES, ZEROS, HIGH-VALUES, LOW-VALUES fill entire field
 */
public final class CobolMove {

	/** Category of a COBOL data item for MOVE rules. */
	public enum Category {
		ALPHABETIC, ALPHANUMERIC, ALPHANUMERIC_EDITED, NUMERIC_INTEGER, NUMERIC_DECIMAL, NUMERIC_EDITED, GROUP
	}

	private CobolMove() {
	}

	// --- Alphanumeric MOVE (left-justified, space-padded/truncated) ---

	/**
	 * MOVE alphanumeric source to alphanumeric target.
	 * Per IBM manual: characters are moved from left to right.
	 * If source is shorter, target is space-padded on the right.
	 * If source is longer, target is truncated on the right.
	 */
	public static String moveAlphanumericToAlphanumeric(final String source, final int targetLength) {
		final String src = (source == null) ? "" : source;
		if (src.length() >= targetLength) {
			return src.substring(0, targetLength);
		}
		return src + CobolConstants.spaces(targetLength - src.length());
	}

	/**
	 * MOVE alphanumeric source to numeric target.
	 * Per IBM manual: the source is treated as an unsigned integer.
	 * Non-numeric characters are stripped. The value is de-edited and
	 * stored right-justified with zero-fill on the left.
	 */
	public static BigDecimal moveAlphanumericToNumeric(final String source, final int integerDigits,
			final int decimalDigits) {
		if (source == null || source.isBlank()) {
			return BigDecimal.ZERO.setScale(decimalDigits);
		}
		final String cleaned = source.trim().replaceAll("[^0-9.+\\-]", "");
		if (cleaned.isEmpty()) {
			return BigDecimal.ZERO.setScale(decimalDigits);
		}
		try {
			final BigDecimal value = new BigDecimal(cleaned);
			return fitToNumericPicture(value, integerDigits, decimalDigits);
		} catch (final NumberFormatException e) {
			return BigDecimal.ZERO.setScale(decimalDigits);
		}
	}

	// --- Numeric MOVE (decimal alignment) ---

	/**
	 * MOVE numeric source to numeric target.
	 * Per IBM manual: decimal point alignment is performed.
	 * Excess digits on either side are truncated.
	 * Missing digits are zero-filled.
	 */
	public static BigDecimal moveNumericToNumeric(final BigDecimal source, final int integerDigits,
			final int decimalDigits) {
		if (source == null) {
			return BigDecimal.ZERO.setScale(decimalDigits);
		}
		return fitToNumericPicture(source, integerDigits, decimalDigits);
	}

	/**
	 * COMPUTE ROUNDED: same as moveNumericToNumeric but uses HALF_UP
	 * rounding instead of truncation when adjusting scale.
	 */
	public static BigDecimal moveNumericToNumericRounded(final BigDecimal source, final int integerDigits,
			final int decimalDigits) {
		if (source == null) {
			return BigDecimal.ZERO.setScale(decimalDigits);
		}
		return fitToNumericPicture(source, integerDigits, decimalDigits, RoundingMode.HALF_UP);
	}

	/**
	 * MOVE numeric source to alphanumeric target.
	 * Per IBM manual: the numeric value is moved as if it were alphanumeric.
	 * The external decimal representation is used.
	 */
	public static String moveNumericToAlphanumeric(final BigDecimal source, final int targetLength) {
		if (source == null) {
			return CobolConstants.spaces(targetLength);
		}
		final String repr = source.toPlainString();
		return moveAlphanumericToAlphanumeric(repr, targetLength);
	}

	/**
	 * MOVE numeric source to alphanumeric target with source PIC digit count.
	 * Per IBM manual: the sending field is de-edited to its external decimal
	 * representation (with leading zeros per PIC), then moved as alphanumeric.
	 * E.g., PIC 9(03) VALUE 5 → "005" → MOVE to PIC X(03) → "005".
	 *
	 * This overload uses the BigDecimal's scale as a best-effort fallback
	 * for the source decimal digits. Prefer the 4-arg overload when the
	 * source PIC decimal digits are known at code-generation time.
	 */
	public static String moveNumericToAlphanumeric(final BigDecimal source, final int sourceIntegerDigits,
			final int targetLength) {
		if (source == null) {
			return CobolConstants.spaces(targetLength);
		}
		// Use BigDecimal's scale as best-effort for source decimal digits
		final int sourceDecimalDigits = Math.max(source.scale(), 0);
		return moveNumericToAlphanumeric(source, sourceIntegerDigits, sourceDecimalDigits, targetLength);
	}

	/**
	 * MOVE numeric source to alphanumeric target with full source PIC info.
	 * Per IBM ILE COBOL manual: the sending field's DISPLAY representation is
	 * moved as alphanumeric. For PIC 9(n)V9(m), all n+m digits are output
	 * contiguously WITHOUT a decimal point character.
	 * E.g., PIC 9(03)V9(02) VALUE 23.00 → "23000" → MOVE to PIC X(05) → "23000".
	 * E.g., PIC 9(03) VALUE 5 → "005" → MOVE to PIC X(03) → "005".
	 *
	 * @param source              the numeric value
	 * @param sourceIntegerDigits number of integer digit positions (9s before V)
	 * @param sourceDecimalDigits number of decimal digit positions (9s after V)
	 * @param targetLength        the length of the target PIC X field
	 */
	public static String moveNumericToAlphanumeric(final BigDecimal source, final int sourceIntegerDigits,
			final int sourceDecimalDigits, final int targetLength) {
		if (source == null) {
			return CobolConstants.spaces(targetLength);
		}
		final int totalSourceDigits = sourceIntegerDigits + sourceDecimalDigits;
		final String repr;
		if (totalSourceDigits > 0) {
			// Scale the value to remove the implied decimal: multiply by 10^sourceDecimalDigits.
			// E.g., PIC 9(3)V9(2) value 23.00: movePointRight(2) → 2300, format %05d → "02300".
			// E.g., PIC 9(3)V9(2) value 0: movePointRight(2) → 0, format %05d → "00000".
			final BigDecimal scaled = source.abs().movePointRight(sourceDecimalDigits)
					.setScale(0, java.math.RoundingMode.DOWN);
			repr = String.format("%0" + totalSourceDigits + "d", scaled.toBigInteger());
			if (source.signum() < 0) {
				return moveAlphanumericToAlphanumeric("-" + repr, targetLength);
			}
		} else {
			repr = source.toPlainString();
		}
		return moveAlphanumericToAlphanumeric(repr, targetLength);
	}

	/**
	 * MOVE numeric source to numeric-edited target (e.g., PIC +9(09), PIC -9(05)).
	 * Per IBM ILE COBOL manual: the value is formatted according to the edit pattern.
	 * The sign character (+/-) occupies one position, followed by zero-padded digits.
	 * <p>
	 * Examples:
	 * <ul>
	 *   <li>PIC +9(09), value 0 → "+000000000"</li>
	 *   <li>PIC +9(09), value -100 → "-000000100"</li>
	 *   <li>PIC -9(05), value 42 → " 00042" (positive: space instead of minus)</li>
	 *   <li>PIC -9(05), value -42 → "-00042"</li>
	 * </ul>
	 *
	 * @param source       the numeric value to format
	 * @param picPattern   the PIC string (e.g., "+9(09)", "-9(05)")
	 * @param targetLength the total length of the target field including sign
	 * @return the formatted string
	 */
	public static String moveNumericToNumericEdited(final BigDecimal source, final String picPattern,
			final int targetLength) {
		if (source == null) {
			return CobolConstants.spaces(targetLength);
		}
		final String upperPic = (picPattern != null) ? picPattern.toUpperCase() : "";

		// Count digit positions (9s) in the PIC pattern, separately for integer and decimal parts
		int integerDigits = 0;
		int decimalDigits = 0;
		boolean afterV = false;
		for (int i = 0; i < upperPic.length(); i++) {
			final char c = upperPic.charAt(i);
			if (c == 'V') {
				afterV = true;
			} else if (c == '9') {
				// Check if followed by (n) repeat count
				if (i + 1 < upperPic.length() && upperPic.charAt(i + 1) == '(') {
					final int closeIdx = upperPic.indexOf(')', i + 1);
					if (closeIdx > i + 2) {
						try {
							final int repeatCount = Integer.parseInt(upperPic.substring(i + 2, closeIdx));
							if (afterV) {
								decimalDigits += repeatCount;
							} else {
								integerDigits += repeatCount;
							}
							i = closeIdx; // skip past the closing paren
							continue;
						} catch (final NumberFormatException e) {
							// fall through to count as single 9
						}
					}
				}
				if (afterV) {
					decimalDigits++;
				} else {
					integerDigits++;
				}
			}
		}
		final int digitCount = integerDigits + decimalDigits;
		final int effectiveDigitCount = (digitCount == 0) ? (targetLength - 1) : digitCount;

		// Scale the source value to remove the implied decimal (V): multiply by 10^decimalDigits
		// so that PIC +9(6)V9(6) with value 1.000000 becomes 1000000 -> "+000001000000"
		final BigDecimal scaled = (decimalDigits > 0)
				? source.movePointRight(decimalDigits).setScale(0, java.math.RoundingMode.HALF_UP)
				: source.setScale(0, java.math.RoundingMode.HALF_UP);
		final long intVal = scaled.longValue();

		if (upperPic.startsWith("+")) {
			// PIC +9(n): show + for positive, - for negative
			final String sign = (intVal < 0) ? "-" : "+";
			final String digits = String.format("%0" + effectiveDigitCount + "d", Math.abs(intVal));
			final String result = sign + digits;
			// When the REDEFINES (PIC +9(n)) is smaller than the backing PIC X field,
			// pad with spaces to targetLength so the full backing field size is preserved.
			// In COBOL, MOVE to the redefining item only touches its bytes; the rest
			// keep their previous value (typically spaces from INITIALIZE).
			if (result.length() >= targetLength) {
				return result.substring(0, targetLength);
			}
			return result + CobolConstants.spaces(targetLength - result.length());
		} else if (upperPic.startsWith("-")) {
			// PIC -9(n): show - for negative, space for positive
			final String sign = (intVal < 0) ? "-" : " ";
			final String digits = String.format("%0" + effectiveDigitCount + "d", Math.abs(intVal));
			final String result = sign + digits;
			// Same padding logic as above for PIC -9(n)
			if (result.length() >= targetLength) {
				return result.substring(0, targetLength);
			}
			return result + CobolConstants.spaces(targetLength - result.length());
		}

		// Handle Z (zero-suppression), B (blank insertion), * (check-protect) edited patterns
		if (upperPic.indexOf('Z') >= 0 || upperPic.indexOf('*') >= 0 || upperPic.indexOf('B') >= 0) {
			return formatNumericEdited(Math.abs(intVal), upperPic, targetLength);
		}

		// Fallback: plain numeric with leading zeros
		final String digits = String.format("%0" + targetLength + "d", Math.abs(intVal));
		return digits.length() >= targetLength ? digits.substring(digits.length() - targetLength) : digits;
	}

	/**
	 * Overload for moveNumericToNumericEdited that accepts a String source.
	 * Parses the string as a BigDecimal and delegates to the BigDecimal version.
	 * Used when the transformer generates moveNumericToNumericEdited on a reference
	 * modification or substring result (which is always String in Java).
	 */
	public static String moveNumericToNumericEdited(final String source, final String picPattern,
			final int targetLength) {
		if (source == null || source.trim().isEmpty()) {
			return CobolConstants.spaces(targetLength);
		}
		try {
			return moveNumericToNumericEdited(new BigDecimal(source.trim()), picPattern, targetLength);
		} catch (final NumberFormatException e) {
			return moveAlphanumericToAlphanumeric(source, targetLength);
		}
	}

	/**
	 * Moves an alphanumeric (String) source to a numeric-edited target.
	 * Tries to parse the string as a number and format it according to the PIC pattern.
	 * If parsing fails (not a valid number), falls back to alphanumeric move (space-padded).
	 *
	 * @param source       the alphanumeric string value
	 * @param picPattern   the PIC string (e.g., "+9(05)", "-9(05)")
	 * @param targetLength the total length of the target field
	 * @return the formatted string
	 */
	public static String moveAlphanumericToNumericEdited(final String source, final String picPattern,
			final int targetLength) {
		if (source == null) {
			return CobolConstants.spaces(targetLength);
		}
		try {
			final BigDecimal numericValue = new BigDecimal(source.trim());
			return moveNumericToNumericEdited(numericValue, picPattern, targetLength);
		} catch (final NumberFormatException e) {
			// Source is not a valid number — fall back to alphanumeric move
			return moveAlphanumericToAlphanumeric(source, targetLength);
		}
	}

	/**
	 * Formats a numeric value according to a numeric-edited PIC pattern
	 * containing Z (zero suppress), B (blank), * (check protect), etc.
	 */
	private static String formatNumericEdited(final long absVal, final String upperPic, final int targetLength) {
		// Count digit positions (Z, *, 9) to determine how many digits we need
		int digitPositions = 0;
		for (int i = 0; i < upperPic.length(); i++) {
			final char c = upperPic.charAt(i);
			if (c == 'Z' || c == '*' || c == '9') {
				// Check for repeat count (n)
				if (i + 1 < upperPic.length() && upperPic.charAt(i + 1) == '(') {
					final int closeIdx = upperPic.indexOf(')', i + 1);
					if (closeIdx > i + 2) {
						try {
							digitPositions += Integer.parseInt(upperPic.substring(i + 2, closeIdx));
							i = closeIdx;
							continue;
						} catch (final NumberFormatException e) { /* fall through */ }
					}
				}
				digitPositions++;
			}
		}

		// Format the number with leading zeros to fill all digit positions
		final String numStr = String.format("%0" + Math.max(digitPositions, 1) + "d", absVal);

		// Walk the PIC pattern and build the result
		final StringBuilder result = new StringBuilder();
		int numIdx = 0;
		boolean suppressionActive = true; // Z/* suppress leading zeros

		// Expand the PIC pattern (handle repeat counts)
		final StringBuilder expanded = new StringBuilder();
		for (int i = 0; i < upperPic.length(); i++) {
			final char c = upperPic.charAt(i);
			if (i + 1 < upperPic.length() && upperPic.charAt(i + 1) == '(') {
				final int closeIdx = upperPic.indexOf(')', i + 1);
				if (closeIdx > i + 2) {
					try {
						final int count = Integer.parseInt(upperPic.substring(i + 2, closeIdx));
						for (int j = 0; j < count; j++) {
							expanded.append(c);
						}
						i = closeIdx;
						continue;
					} catch (final NumberFormatException e) { /* fall through */ }
				}
			}
			expanded.append(c);
		}

		final String picExpanded = expanded.toString();
		for (int i = 0; i < picExpanded.length(); i++) {
			final char picChar = picExpanded.charAt(i);
			switch (picChar) {
			case 'Z':
				if (numIdx < numStr.length()) {
					final char digit = numStr.charAt(numIdx++);
					if (suppressionActive && digit == '0') {
						result.append(' ');
					} else {
						suppressionActive = false;
						result.append(digit);
					}
				} else {
					result.append(' ');
				}
				break;
			case '*':
				if (numIdx < numStr.length()) {
					final char digit = numStr.charAt(numIdx++);
					if (suppressionActive && digit == '0') {
						result.append('*');
					} else {
						suppressionActive = false;
						result.append(digit);
					}
				} else {
					result.append('*');
				}
				break;
			case '9':
				suppressionActive = false;
				if (numIdx < numStr.length()) {
					result.append(numStr.charAt(numIdx++));
				} else {
					result.append('0');
				}
				break;
			case 'B':
				result.append(' ');
				break;
			case ',':
				// Comma: if suppression is active, replace with space
				if (suppressionActive) {
					result.append(' ');
				} else {
					result.append(',');
				}
				break;
			case '.':
				// Decimal point: stops suppression
				suppressionActive = false;
				result.append('.');
				break;
			case '/':
				result.append('/');
				break;
			default:
				result.append(picChar);
				break;
			}
		}

		// Pad/truncate to target length
		final String res = result.toString();
		if (res.length() >= targetLength) {
			return res.substring(0, targetLength);
		}
		return String.format("%-" + targetLength + "s", res);
	}

	// --- Group MOVE (always alphanumeric) ---

	/**
	 * MOVE group item to alphanumeric target.
	 * Per IBM manual: when the source is a group item, the group is treated
	 * as a single alphanumeric field whose contents are the concatenation of
	 * its subordinate items in declaration order.
	 * This overload accepts an Object (generated group type) and serializes
	 * it by concatenating all declared fields via reflection.
	 */
	public static String moveAlphanumericToAlphanumeric(final Object source, final int targetLength) {
		if (source == null) {
			return CobolConstants.spaces(targetLength);
		}
		if (source instanceof String) {
			return moveAlphanumericToAlphanumeric((String) source, targetLength);
		}
		// Serialize group type by concatenating fields in declaration order
		final String serialized = groupToString(source);
		return moveAlphanumericToAlphanumeric(serialized, targetLength);
	}

	/**
	 * MOVE to/from group item.
	 * Per IBM manual: when either source or target is a group item,
	 * the move is treated as an alphanumeric-to-alphanumeric move
	 * regardless of the types of the subordinate items.
	 */
	public static String moveGroup(final String source, final int targetLength) {
		return moveAlphanumericToAlphanumeric(source, targetLength);
	}

	/**
	 * Serialize a group type object to its alphanumeric representation
	 * by concatenating all declared fields in order.
	 * Per IBM ILE COBOL: a group item is treated as a contiguous
	 * alphanumeric string formed by its subordinate items.
	 */

	/** Cache of COBOL data fields per class (avoids repeated getDeclaredFields + filtering). */
	private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.lang.reflect.Field[]> fieldCache = new java.util.concurrent.ConcurrentHashMap<>();

	/** Cache of group size (flat byte length) per class, computed from a prototype instance. */
	private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Integer> groupSizeCache = new java.util.concurrent.ConcurrentHashMap<>();

	/** Returns the cached COBOL data fields for a class. */
	public static java.lang.reflect.Field[] getCachedFields(final Class<?> clazz) {
		return fieldCache.computeIfAbsent(clazz, c -> {
			final java.util.List<java.lang.reflect.Field> result = new java.util.ArrayList<>();
			for (final java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (f.isSynthetic() || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
				final Class<?> ft = f.getType();
				if (ft == String.class || ft == java.math.BigDecimal.class
						|| ft == boolean.class || ft == Boolean.class
						|| ft == java.util.List.class
						|| isCobolGroupType(ft)) {
					f.setAccessible(true);
					result.add(f);
				}
			}
			return result.toArray(new java.lang.reflect.Field[0]);
		});
	}

	/** Returns the cached flat byte size of a group instance. */
	public static int getGroupSize(final Object group) {
		if (group == null) return 0;
		// For primitive COBOL types (String, BigDecimal, boolean), each field has
		// its own size determined by its PIC clause - do NOT cache by class since
		// different fields of the same type can have different sizes.
		// E.g., PIC X(3) and PIC X(9) are both String but have size 3 and 9.
		if (group instanceof String) {
			return ((String) group).length();
		}
		if (group instanceof java.math.BigDecimal) {
			return groupToString(group).length();
		}
		if (group instanceof Boolean) {
			return 1;
		}
		// For COBOL group types (inner classes), compute size DETERMINISTICALLY
		// by walking the fields and using @CobolFieldWidth annotations for BigDecimal
		// fields. This avoids the non-determinism of groupToString(group).length()
		// where BigDecimal.toPlainString() varies by value (e.g. "0" vs "1234567").
		return groupSizeCache.computeIfAbsent(group.getClass(), c -> computeDeterministicGroupSize(c, group));
	}

	/**
	 * Computes the flat byte size of a COBOL group type deterministically.
	 * Uses @CobolFieldWidth annotations for BigDecimal fields and creates
	 * a prototype instance for fields without annotations to get their
	 * initial (default) size. This ensures the computed size is stable
	 * regardless of the current field values.
	 */
	private static int computeDeterministicGroupSize(final Class<?> clazz, final Object instance) {
		// Create a prototype instance with default values to get deterministic sizes
		// for fields without @CobolFieldWidth annotations
		final Object prototype = createPrototype(clazz, instance);
		int total = 0;
		for (final java.lang.reflect.Field field : getCachedFields(clazz)) {
			try {
				final Object value = field.get(prototype);
				if (value instanceof String) {
					total += ((String) value).length();
				} else if (value instanceof java.math.BigDecimal) {
					// Use @CobolFieldWidth annotation for deterministic width
					final CobolFieldWidth ann = field.getAnnotation(CobolFieldWidth.class);
					if (ann != null && ann.value() > 0) {
						total += ann.value();
					} else {
						// Fallback: use the prototype's initial value length
						total += ((java.math.BigDecimal) value).toPlainString().length();
					}
				} else if (value instanceof Boolean) {
					total += 1;
				} else if (value instanceof java.util.List) {
					for (final Object element : (java.util.List<?>) value) {
						if (element != null && isCobolGroupType(element.getClass())) {
							total += computeDeterministicGroupSize(element.getClass(), element);
						}
					}
				} else if (value != null && isCobolGroupType(value.getClass())) {
					total += computeDeterministicGroupSize(value.getClass(), value);
				}
			} catch (final IllegalAccessException e) {
				// skip inaccessible fields
			}
		}
		return total;
	}

	/**
	 * Creates a prototype (default-initialized) instance of a COBOL group type.
	 * If possible, creates a fresh instance via the no-arg constructor to get
	 * default field values. Falls back to the provided instance if instantiation fails.
	 */
	private static Object createPrototype(final Class<?> clazz, final Object fallback) {
		try {
			// COBOL group types are inner classes -- they need an enclosing instance.
			// Try to find a no-arg constructor first (static inner class or top-level).
			final java.lang.reflect.Constructor<?>[] ctors = clazz.getDeclaredConstructors();
			for (final java.lang.reflect.Constructor<?> ctor : ctors) {
				final Class<?>[] paramTypes = ctor.getParameterTypes();
				if (paramTypes.length == 0) {
					// No-arg constructor (static class)
					ctor.setAccessible(true);
					return ctor.newInstance();
				}
				if (paramTypes.length == 1 && paramTypes[0] == clazz.getEnclosingClass()) {
					// Inner class constructor taking enclosing instance
					// Use the enclosing instance from the fallback object if available
					ctor.setAccessible(true);
					// Get the enclosing instance from the fallback -- inner classes have
					// a synthetic field "this$N" pointing to the enclosing instance
					Object enclosing = null;
					for (final java.lang.reflect.Field f : clazz.getDeclaredFields()) {
						if (f.getName().startsWith("this$")) {
							f.setAccessible(true);
							enclosing = f.get(fallback);
							break;
						}
					}
					if (enclosing != null) {
						return ctor.newInstance(enclosing);
					}
				}
			}
		} catch (final Exception e) {
			// Fall through to use the provided fallback instance
		}
		return fallback;
	}

	public static String groupToString(final Object group) {
		// Sanitize internal boolean control chars to display chars.
		// SET condition TO TRUE on PIC 1 REDEFINES PIC X stores
		// \u0000 (B"0") / \u0001 (B"1") internally for correct condition
		// checks. When serializing, these must become '0'/'1' to match
		// AS/400 EBCDIC behavior. This applies to both output serialization
		// and linkage copy-back. Condition checks use direct field access
		// (not groupToString), so this doesn't affect IF evaluations
		// within the same program. Cross-program condition checks after
		// copy-back will see '0'/'1' instead of '\u0000'/'\u0001'.
		final String raw = groupToString(group, new java.util.IdentityHashMap<>(), 0);
		// Fast path: no control chars
		if (raw.indexOf('\u0000') < 0 && raw.indexOf('\u0001') < 0) {
			return raw;
		}
		// Sanitize boolean control chars to display chars
		final StringBuilder sb = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++) {
			final char ch = raw.charAt(i);
			if (ch == '\u0000') {
				sb.append('0');
			} else if (ch == '\u0001') {
				sb.append('1');
			} else {
				sb.append(ch);
			}
		}
		return sb.toString();
	}

	/**
	 * Checks whether the given class represents a COBOL group data type.
	 * Generated COBOL group types have class names ending with "Type".
	 */
	public static boolean isCobolGroupType(final Class<?> clazz) {
		return clazz != null && clazz.getSimpleName().endsWith("Type");
	}

	private static String groupToString(final Object group, final java.util.IdentityHashMap<Object, Boolean> visited, final int depth) {
		if (group == null) {
			return "";
		}
		if (group instanceof String) {
			return (String) group;
		}
		if (group instanceof java.math.BigDecimal) {
			return ((java.math.BigDecimal) group).toPlainString();
		}
		if (group instanceof Boolean) {
			return ((Boolean) group) ? "1" : "0";
		}
		// Cycle/depth guard
		if (depth > 50) return "";
		if (visited.containsKey(group)) return "";
		visited.put(group, Boolean.TRUE);

		// Handle OCCURS arrays (List<ElementType>)
		if (group instanceof java.util.List) {
			final StringBuilder listSb = new StringBuilder();
			for (final Object element : (java.util.List<?>) group) {
				listSb.append(groupToString(element, visited, depth + 1));
			}
			return listSb.toString();
		}
		// Only recurse into COBOL group types (class name ends with "Type")
		if (!isCobolGroupType(group.getClass())) {
			return "";
		}
		final StringBuilder sb = new StringBuilder();
		for (final java.lang.reflect.Field field : getCachedFields(group.getClass())) {
			try {
				final Object value = field.get(group);
				if (value instanceof String) {
					sb.append((String) value);
				} else if (value instanceof java.math.BigDecimal) {
					// Use @CobolFieldWidth annotation if present to produce
					// fixed-width zero-padded output for group serialization.
					// COBOL group items are flat byte buffers where numeric DISPLAY
					// fields are zero-padded (e.g., PIC 9(09) VALUE 0 = "000000000").
					// Using space-padded output would misalign subsequent fields
					// when deserializing via moveStringToGroup.
					final CobolFieldWidth ann = field.getAnnotation(CobolFieldWidth.class);
					if (ann != null && ann.value() > 0) {
						final java.math.BigDecimal bd = (java.math.BigDecimal) value;
						final int width = ann.value();
						// Scale the value to remove implied decimal: multiply by 10^scale
						// E.g., PIC 9(3)V9(2) value 23.45 -> scale=2 -> movePointRight(2) -> 2345 -> "02345"
						final int scale = Math.max(bd.scale(), 0);
						final java.math.BigDecimal scaled = bd.abs().movePointRight(scale)
								.setScale(0, java.math.RoundingMode.DOWN);
						String formatted = String.format("%0" + width + "d", scaled.toBigInteger());
						// Truncate to width if too long
						if (formatted.length() > width) {
							formatted = formatted.substring(formatted.length() - width);
						}
						// Preserve sign for negative values
						if (bd.signum() < 0) {
							formatted = "-" + formatted.substring(1);
						}
						sb.append(formatted);
					} else {
						sb.append(((java.math.BigDecimal) value).toPlainString());
					}
				} else if (value instanceof Boolean) {
					sb.append(((Boolean) value) ? "1" : "0");
				} else if (value instanceof java.util.List) {
					for (final Object element : (java.util.List<?>) value) {
						sb.append(groupToString(element, visited, depth + 1));
					}
				} else if (value != null && isCobolGroupType(value.getClass())) {
					sb.append(groupToString(value, visited, depth + 1));
				}
			} catch (final IllegalAccessException e) {
				// skip inaccessible fields
			}
		}
		return sb.toString();
	}

	/**
	 * Distribute a string's characters back into a group item's sub-fields.
	 * This is the inverse of groupToString() and is needed for INSPECT REPLACING
	 * on group items: the string result must be written back into the sub-fields.
	 *
	 * @param source the string to distribute
	 * @param group  the group item object whose sub-fields will be set
	 */
	public static void moveStringToGroup(final String source, final Object group) {
		moveStringToGroup(source, group, new java.util.IdentityHashMap<>(), 0);
	}

	private static void moveStringToGroup(final String source, final Object group,
			final java.util.IdentityHashMap<Object, Boolean> visited, final int depth) {
		if (group == null || source == null) {
			return;
		}
		if (group instanceof String) {
			// Cannot reassign; caller must handle String targets directly
			return;
		}
		// Cycle/depth guard
		if (depth > 50) return;
		if (visited.containsKey(group)) return;
		visited.put(group, Boolean.TRUE);
		// Only process COBOL group types
		if (!isCobolGroupType(group.getClass())) {
			return;
		}
		int offset = 0;
		for (final java.lang.reflect.Field field : getCachedFields(group.getClass())) {
			try {
				final Object currentValue = field.get(group);
				if (currentValue instanceof String) {
					final int len = ((String) currentValue).length();
					final String slice = safeSubstring(source, offset, len);
					field.set(group, slice);
					offset += len;
				} else if (currentValue instanceof java.math.BigDecimal) {
					// Use @CobolFieldWidth annotation if present for fixed-width fields
					final CobolFieldWidth ann = field.getAnnotation(CobolFieldWidth.class);
					final int len;
					final int impliedDecimals;
					if (ann != null && ann.value() > 0) {
						len = ann.value();
						impliedDecimals = ann.decimalDigits();
					} else {
						len = ((java.math.BigDecimal) currentValue).toPlainString().length();
						impliedDecimals = 0;
					}
					final String slice = safeSubstring(source, offset, len);
					try {
						final String trimmed = slice.trim();
						if (!trimmed.isEmpty()) {
							java.math.BigDecimal parsed = new java.math.BigDecimal(trimmed);
							// Restore implied decimal: groupToString serializes PIC S9(3)V9(2) value 23.00
							// as "02300" (scaled by 10^decimalDigits). We must reverse that scaling.
							if (impliedDecimals > 0) {
								parsed = parsed.movePointLeft(impliedDecimals);
							}
							field.set(group, parsed);
						}
						// All-spaces slice: keep existing BigDecimal value unchanged
					} catch (final NumberFormatException e) {
						// Non-numeric content: keep existing BigDecimal value unchanged
					}
					offset += len;
				} else if (currentValue instanceof Boolean) {
					// boolean field: '1' = true, anything else = false
					final String slice = safeSubstring(source, offset, 1);
					field.set(group, "1".equals(slice));
					offset += 1;
				} else if (currentValue instanceof java.util.List) {
					// OCCURS: iterate list elements and recurse into each
					for (final Object element : (java.util.List<?>) currentValue) {
						if (element != null && isCobolGroupType(element.getClass())) {
							// Use cached size instead of full groupToString
							final int len = getGroupSize(element);
							final String slice = safeSubstring(source, offset, len);
							moveStringToGroup(slice, element, visited, depth + 1);
							// Remove from visited so sibling elements can be processed
							visited.remove(element);
							offset += len;
						}
					}
				} else if (currentValue != null && isCobolGroupType(currentValue.getClass())) {
					// Nested group item: use cached size
					final int len = getGroupSize(currentValue);
					final String slice = safeSubstring(source, offset, len);
					moveStringToGroup(slice, currentValue, visited, depth + 1);
					offset += len;
				}
			} catch (final IllegalAccessException e) {
				// skip
			}
		}
	}

	private static String safeSubstring(final String s, final int offset, final int length) {
		if (offset >= s.length()) {
			return CobolConstants.spaces(length);
		}
		final int end = Math.min(offset + length, s.length());
		final String slice = s.substring(offset, end);
		if (slice.length() < length) {
			return slice + CobolConstants.spaces(length - slice.length());
		}
		return slice;
	}

	// --- Alphanumeric-to-numeric conversion for arithmetic ---

	/**
	 * Converts an alphanumeric (String) value to BigDecimal for use in
	 * arithmetic statements (ADD, SUBTRACT, MULTIPLY, DIVIDE, COMPUTE).
	 * Per IBM ILE COBOL: when an alphanumeric field is used in an arithmetic
	 * context, it is treated as an unsigned integer. Non-numeric characters
	 * are stripped; blank/null returns zero.
	 */
	public static BigDecimal toBigDecimal(final String source) {
		if (source == null || source.isBlank()) {
			return BigDecimal.ZERO;
		}
		final String cleaned = source.trim().replaceAll("[^0-9.+\\-]", "");
		if (cleaned.isEmpty()) {
			return BigDecimal.ZERO;
		}
		try {
			return new BigDecimal(cleaned);
		} catch (final NumberFormatException e) {
			return BigDecimal.ZERO;
		}
	}

	// --- Figurative constant MOVEs ---

	/**
	 * MOVE SPACES to alphanumeric target.
	 */
	public static String moveSpaces(final int targetLength) {
		return CobolConstants.spaces(targetLength);
	}

	/**
	 * MOVE ZEROS to numeric target.
	 */
	public static BigDecimal moveZerosToNumeric(final int decimalDigits) {
		return BigDecimal.ZERO.setScale(decimalDigits);
	}

	/**
	 * MOVE ZEROS to alphanumeric target.
	 */
	public static String moveZerosToAlphanumeric(final int targetLength) {
		return CobolConstants.zeros(targetLength);
	}

	/**
	 * MOVE HIGH-VALUES to alphanumeric target.
	 */
	public static String moveHighValues(final int targetLength) {
		return CobolConstants.highValues(targetLength);
	}

	/**
	 * MOVE LOW-VALUES to alphanumeric target.
	 */
	public static String moveLowValues(final int targetLength) {
		return CobolConstants.lowValues(targetLength);
	}

	/**
	 * MOVE ALL literal to target.
	 */
	public static String moveAllLiteral(final String literal, final int targetLength) {
		return CobolConstants.allLiteral(literal, targetLength);
	}

	// --- MOVE CORRESPONDING ---

	/**
	 * MOVE CORRESPONDING source TO target.
	 * Per IBM manual: for each source subordinate item, if a subordinate item
	 * with the same name exists in the target group, a MOVE is performed
	 * from that source item to the target item. Uses reflection.
	 */
	public static void moveCorresponding(final Object source, final Object target) {
		if (source == null || target == null) {
			return;
		}
		final boolean trace = DebugFlags.MOVECORR_TRACE; // enable with -Dcobol.movecorr.trace=true or -Dcobol.debug=true
		for (final java.lang.reflect.Field sourceField : getCachedFields(source.getClass())) {
			try {
				java.lang.reflect.Field targetField = null;
				try {
					targetField = target.getClass().getDeclaredField(sourceField.getName());
				} catch (NoSuchFieldException e) {
					// Try getCachedFields approach for fields not directly declared
					for (final java.lang.reflect.Field f : getCachedFields(target.getClass())) {
						if (f.getName().equals(sourceField.getName())) {
							targetField = f;
							break;
						}
					}
					if (targetField == null) throw e;
				}
				sourceField.setAccessible(true);
				if (trace && "taddrsta".equals(sourceField.getName())) {
					System.out.println("[MOVECORR-TRACE] found taddrsta in source=" + source.getClass().getName() + ", srcVal='" + sourceField.get(source) + "' target=" + target.getClass().getName());
				}
				targetField.setAccessible(true);
				final Object value = sourceField.get(source);

				if (value instanceof String && targetField.getType() == String.class) {
					final String current = (String) targetField.get(target);
					final int len = (current != null) ? current.length() : ((String) value).length();
					targetField.set(target, moveAlphanumericToAlphanumeric((String) value, len));
				} else if (value instanceof BigDecimal && targetField.getType() == BigDecimal.class) {
					targetField.set(target, value);
				} else if (value != null && isCobolGroupType(value.getClass()) && isCobolGroupType(targetField.getType())) {
					// Group type mismatch — recurse with moveCorresponding to match by name
					// (using flat byte copy can cause misalignment when structures differ)
					final Object targetObj = targetField.get(target);
					if (targetObj != null) {
						final int srcSize = getGroupSize(value);
						final int tgtSize = getGroupSize(targetObj);
						if (srcSize == tgtSize) {
							// Same size: flat byte copy is safe and fast
							final String flat = groupToString(value);
							moveStringToGroup(flat, targetObj);
						} else {
							// Size mismatch: recurse with moveCorresponding
							moveCorresponding(value, targetObj);
						}
					}
				} else if (value != null && targetField.getType().isAssignableFrom(value.getClass())) {
					targetField.set(target, value);
				} else if (value instanceof Boolean && (targetField.getType() == boolean.class || targetField.getType() == Boolean.class)) {
					targetField.set(target, value);
				}
				// else: incompatible types, skip
			} catch (final NoSuchFieldException e) {
				// No matching field in target, skip per IBM spec
				if (trace) System.out.println("[MOVECORR-TRACE] NoSuchField: " + sourceField.getName() + " in " + target.getClass().getSimpleName());
			} catch (final IllegalAccessException e) {
				// Cannot access, skip
				if (trace) System.out.println("[MOVECORR-TRACE] IllegalAccess: " + sourceField.getName() + " - " + e.getMessage());
			} catch (final IllegalArgumentException e) {
				// Type mismatch, skip
				if (trace) System.out.println("[MOVECORR-TRACE] IllegalArg: " + sourceField.getName() + " - " + e.getMessage());
			} catch (final Exception e) {
				if (trace) System.out.println("[MOVECORR-TRACE] Exception: " + sourceField.getName() + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
			}
		}
	}

	// --- Internal helpers ---

	/**
	 * Fits a BigDecimal value to a COBOL PIC numeric specification.
	 * Performs decimal alignment, truncation of excess integer digits,
	 * and scale adjustment.
	 */
	private static BigDecimal fitToNumericPicture(final BigDecimal value, final int integerDigits,
			final int decimalDigits) {
		return fitToNumericPicture(value, integerDigits, decimalDigits, RoundingMode.DOWN);
	}

	private static BigDecimal fitToNumericPicture(final BigDecimal value, final int integerDigits,
			final int decimalDigits, final RoundingMode roundingMode) {
		// Set scale with specified rounding mode for decimal alignment
		BigDecimal scaled = value.setScale(decimalDigits, roundingMode);

		// Truncate integer part if it exceeds the PIC specification
		if (integerDigits > 0) {
			final BigDecimal maxValue = BigDecimal.TEN.pow(integerDigits).subtract(BigDecimal.ONE)
					.setScale(decimalDigits);
			final BigDecimal minValue = maxValue.negate();

			if (scaled.compareTo(maxValue) > 0) {
				// Truncate excess high-order digits
				final BigDecimal divisor = BigDecimal.TEN.pow(integerDigits);
				scaled = scaled.remainder(divisor).setScale(decimalDigits);
			} else if (scaled.compareTo(minValue) < 0) {
				final BigDecimal divisor = BigDecimal.TEN.pow(integerDigits);
				scaled = scaled.remainder(divisor).setScale(decimalDigits);
			}
		}

		return scaled;
	}

	/**
	 * Overlays a portion of a base string with a replacement string.
	 * Used for group-over-elementary REDEFINES: writing to a child field
	 * modifies the corresponding bytes of the base field.
	 *
	 * @param base  the original string (may be null or shorter than expected)
	 * @param overlay  the replacement string for the specified region
	 * @param start  start index (0-based, inclusive)
	 * @param end  end index (0-based, exclusive)
	 * @return the modified string with the overlay applied
	 */
	public static String overlayString(final String base, final String overlay, final int start, final int end) {
		// Ensure base is at least 'end' characters long, padding with spaces
		String padded = base != null ? base : "";
		if (padded.length() < end) {
			padded = padded + " ".repeat(end - padded.length());
		}

		// Ensure overlay is exactly the right length
		String overlayPadded = overlay != null ? overlay : "";
		final int regionLen = end - start;
		if (overlayPadded.length() < regionLen) {
			overlayPadded = overlayPadded + " ".repeat(regionLen - overlayPadded.length());
		} else if (overlayPadded.length() > regionLen) {
			overlayPadded = overlayPadded.substring(0, regionLen);
		}

		return padded.substring(0, start) + overlayPadded + padded.substring(end);
	}

	// --- Numeric ↔ digit-string conversions for group REDEFINES on numeric base ---

	/**
	 * Converts a BigDecimal to a fixed-width digit string for REDEFINES overlay semantics.
	 * For example, PIC 9(9)V9(1) with value 123.4 → "0000001234" (9 integer + 1 decimal digits).
	 * The result has no decimal point — just contiguous digits, zero-padded on the left.
	 *
	 * @param value         the numeric value (may be null → all zeros)
	 * @param integerDigits number of integer digit positions (the 9s before V)
	 * @param decimalDigits number of decimal digit positions (the 9s after V)
	 * @return a string of exactly (integerDigits + decimalDigits) characters
	 */
	public static String numericToDigitString(final BigDecimal value, final int integerDigits, final int decimalDigits) {
		final int totalLen = integerDigits + decimalDigits;
		if (value == null) {
			return "0".repeat(totalLen);
		}
		// Scale the value to remove the implied decimal: multiply by 10^decimalDigits
		final BigDecimal scaled = value.abs().movePointRight(decimalDigits).setScale(0, RoundingMode.DOWN);
		final String digits = scaled.toBigInteger().toString();
		// Left-pad with zeros to totalLen, or truncate from the left if too long
		if (digits.length() >= totalLen) {
			return digits.substring(digits.length() - totalLen);
		}
		return "0".repeat(totalLen - digits.length()) + digits;
	}

	/**
	 * Converts a fixed-width digit string back to a BigDecimal, restoring the implied decimal.
	 * For example, "0000001234" with decimalDigits=1 → BigDecimal 000000123.4.
	 *
	 * @param digitString   the digit string (must be all digits, no sign or decimal point)
	 * @param decimalDigits number of decimal digit positions (the 9s after V in the base PIC)
	 * @return the parsed BigDecimal value
	 */
	public static BigDecimal digitStringToNumeric(final String digitString, final int decimalDigits) {
		if (digitString == null || digitString.isBlank()) {
			return BigDecimal.ZERO;
		}
		try {
			final BigDecimal raw = new BigDecimal(digitString.trim());
			if (decimalDigits > 0) {
				return raw.movePointLeft(decimalDigits);
			}
			return raw;
		} catch (final NumberFormatException e) {
			return BigDecimal.ZERO;
		}
	}
}
