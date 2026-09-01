package io.proleap.cobol.runtime;

/**
 * COBOL reference modification per IBM ILE COBOL Language Reference V7R3.
 *
 * Reference modification: identifier(leftmost-position : length)
 * - leftmost-position is 1-based
 * - length is optional; if omitted, extends to end of field
 * - Both must be positive integers
 * - leftmost-position must not exceed field length
 * - leftmost-position + length - 1 must not exceed field length
 */
public final class CobolReference {

	private CobolReference() {
	}

	/**
	 * Reference modification: extract substring from an Object source.
	 * Handles group items by converting them to String via CobolMove.groupToString().
	 *
	 * @param source           the field value (String or group item Object)
	 * @param leftmostPosition 1-based starting position
	 * @param length           number of characters (0 or negative means to end)
	 * @return the referenced substring
	 */
	public static String referenceModification(final Object source, final int leftmostPosition, final int length) {
		final String str = source instanceof String ? (String) source : CobolMove.groupToString(source);
		return referenceModification(str, leftmostPosition, length);
	}

	/**
	 * Reference modification: extract to end of string from an Object source.
	 *
	 * @param source           the field value (String or group item Object)
	 * @param leftmostPosition 1-based starting position
	 * @return the referenced substring from position to end
	 */
	public static String referenceModification(final Object source, final int leftmostPosition) {
		final String str = source instanceof String ? (String) source : CobolMove.groupToString(source);
		return referenceModification(str, leftmostPosition, 0);
	}

	/**
	 * Reference modification: extract substring.
	 * identifier(leftmostPosition : length)
	 *
	 * Per IBM manual: leftmost-position is 1-based, length is number of characters.
	 * If length is omitted, use the remainder of the string.
	 *
	 * @param value           the full field value
	 * @param leftmostPosition 1-based starting position
	 * @param length          number of characters (0 or negative means to end)
	 * @return the referenced substring
	 */
	private static String referenceModification(final String value, final int leftmostPosition, final int length) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		// Convert 1-based to 0-based
		final int start = leftmostPosition - 1;
		if (start < 0 || start >= value.length()) {
			return "";
		}

		final int end;
		if (length <= 0) {
			end = value.length();
		} else {
			end = Math.min(start + length, value.length());
		}

		return value.substring(start, end);
	}

	/**
	 * Reference modification: extract to end of string.
	 * identifier(leftmostPosition :)
	 *
	 * @param value           the full field value
	 * @param leftmostPosition 1-based starting position
	 * @return the referenced substring from position to end
	 */
	private static String referenceModification(final String value, final int leftmostPosition) {
		return referenceModification(value, leftmostPosition, 0);
	}

	/**
	 * Reference modification: set substring within a field.
	 * MOVE source TO identifier(leftmostPosition : length)
	 *
	 * Per IBM manual: the reference-modified identifier is the receiving field.
	 * Characters are replaced starting at leftmostPosition for length characters.
	 *
	 * @param target          the full target field value
	 * @param leftmostPosition 1-based starting position
	 * @param length          number of characters to replace
	 * @param source          the source value to move in
	 * @return the modified target string
	 */
	public static String setReferenceModification(final String target, final int leftmostPosition,
			final int length, final String source) {
		if (target == null) {
			return "";
		}
		final int start = leftmostPosition - 1;
		if (start < 0 || start >= target.length() || length <= 0) {
			return target;
		}

		final int end = Math.min(start + length, target.length());
		final int slotSize = end - start;

		// Prepare source: pad or truncate to fit the slot
		final String src = (source == null) ? "" : source;
		final String fitted;
		if (src.length() >= slotSize) {
			fitted = src.substring(0, slotSize);
		} else {
			fitted = src + CobolConstants.spaces(slotSize - src.length());
		}

		return target.substring(0, start) + fitted + target.substring(end);
	}

	/**
	 * Validates reference modification parameters.
	 * Per IBM manual: leftmost-position must be >= 1,
	 * length must be >= 1, and leftmost-position + length - 1 must not
	 * exceed the number of characters in the data item.
	 *
	 * @param fieldLength     length of the data item
	 * @param leftmostPosition 1-based starting position
	 * @param length          number of characters
	 * @return true if the reference modification is valid
	 */
	public static boolean isValidReference(final int fieldLength, final int leftmostPosition, final int length) {
		if (leftmostPosition < 1) {
			return false;
		}
		if (leftmostPosition > fieldLength) {
			return false;
		}
		if (length < 1) {
			return false;
		}
		return (leftmostPosition + length - 1) <= fieldLength;
	}
}
