package io.proleap.cobol.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * COBOL STRING, UNSTRING, and INSPECT operations per IBM ILE COBOL Language Reference V7R3.
 *
 * INSPECT formats:
 * - Format 1: TALLYING (CHARACTERS, ALL, LEADING with BEFORE/AFTER INITIAL)
 * - Format 2: REPLACING (CHARACTERS, ALL, LEADING, FIRST BY with BEFORE/AFTER)
 * - Format 3: TALLYING and REPLACING combined
 * - Format 4: CONVERTING (character-by-character translation with BEFORE/AFTER)
 *
 * STRING: concatenation with DELIMITED BY SIZE/literal, WITH POINTER, ON OVERFLOW
 * UNSTRING: splitting with DELIMITER IN, COUNT IN, WITH POINTER, ON OVERFLOW, TALLYING IN
 */
public final class CobolStringOps {

	/** Tallying type for INSPECT TALLYING. */
	public enum TallyType {
		CHARACTERS, ALL, LEADING
	}

	/** Replacing type for INSPECT REPLACING. */
	public enum ReplaceType {
		CHARACTERS, ALL, LEADING, FIRST
	}

	private CobolStringOps() {
	}

	// ===================== INSPECT TALLYING (Format 1) =====================

	/**
	 * INSPECT source TALLYING count FOR CHARACTERS [BEFORE/AFTER INITIAL boundary].
	 * Per IBM manual: counts character positions in the inspected item.
	 */
	public static int inspectTallyingCharacters(final String source, final String beforeInitial,
			final String afterInitial) {
		if (source == null || source.isEmpty()) {
			return 0;
		}
		final int[] range = computeRange(source, beforeInitial, afterInitial);
		return range[1] - range[0];
	}

	/**
	 * INSPECT source TALLYING count FOR ALL|LEADING target [BEFORE/AFTER INITIAL boundary].
	 * Per IBM manual: comparison proceeds left to right, non-overlapping.
	 * ALL counts every occurrence. LEADING counts only contiguous occurrences from the left.
	 */
	public static int inspectTallying(final String source, final String target, final TallyType type,
			final String beforeInitial, final String afterInitial) {
		if (source == null || target == null || target.isEmpty()) {
			return 0;
		}
		final int[] range = computeRange(source, beforeInitial, afterInitial);
		final int start = range[0];
		final int end = range[1];
		int count = 0;
		boolean leadingBroken = false;

		int idx = start;
		while (idx <= end - target.length()) {
			if (source.substring(idx, idx + target.length()).equals(target)) {
				if (type == TallyType.LEADING && leadingBroken) {
					break;
				}
				count++;
				idx += target.length();
			} else {
				if (type == TallyType.LEADING) {
					leadingBroken = true;
					break;
				}
				idx++;
			}
		}
		return count;
	}

	// ===================== INSPECT REPLACING (Format 2) =====================

	/**
	 * INSPECT source REPLACING CHARACTERS BY replacement [BEFORE/AFTER INITIAL boundary].
	 * Per IBM manual: each character in the range is replaced by the first character of replacement.
	 */
	public static String inspectReplacingCharacters(final String source, final char replacement,
			final String beforeInitial, final String afterInitial) {
		if (source == null || source.isEmpty()) {
			return source;
		}
		final int[] range = computeRange(source, beforeInitial, afterInitial);
		final char[] chars = source.toCharArray();
		for (int i = range[0]; i < range[1]; i++) {
			chars[i] = replacement;
		}
		return new String(chars);
	}

	/**
	 * INSPECT source REPLACING ALL|LEADING|FIRST target BY replacement [BEFORE/AFTER INITIAL boundary].
	 * Per IBM manual: target and replacement must be same length.
	 * ALL replaces every occurrence. LEADING replaces contiguous from left.
	 * FIRST replaces only the first occurrence.
	 */
	public static String inspectReplacing(final String source, final String target, final String replacement,
			final ReplaceType type, final String beforeInitial, final String afterInitial) {
		if (source == null || target == null || target.isEmpty() || replacement == null) {
			return source;
		}
		final int[] range = computeRange(source, beforeInitial, afterInitial);
		final int start = range[0];
		final int end = range[1];
		final StringBuilder sb = new StringBuilder(source);
		boolean firstDone = false;

		int idx = start;
		while (idx <= end - target.length()) {
			if (sb.substring(idx, idx + target.length()).equals(target)) {
				if (type == ReplaceType.FIRST && firstDone) {
					break;
				}
				sb.replace(idx, idx + target.length(), replacement);
				idx += replacement.length();
				firstDone = true;
				if (type == ReplaceType.FIRST) {
					break;
				}
			} else {
				if (type == ReplaceType.LEADING && firstDone) {
					break;
				}
				if (type == ReplaceType.LEADING) {
					break;
				}
				idx++;
			}
		}
		return sb.toString();
	}

	// ===================== INSPECT CONVERTING (Format 4) =====================

	/**
	 * INSPECT source CONVERTING fromChars TO toChars [BEFORE/AFTER INITIAL boundary].
	 * Per IBM manual: each character in fromChars is converted to the corresponding
	 * character in toChars. fromChars and toChars must be the same length.
	 * Conversion is character-by-character, left to right through the source.
	 */
	public static String inspectConverting(final String source, final String fromChars, final String toChars,
			final String beforeInitial, final String afterInitial) {
		if (source == null || fromChars == null || toChars == null) {
			return source;
		}
		if (fromChars.length() != toChars.length()) {
			return source;
		}
		final int[] range = computeRange(source, beforeInitial, afterInitial);
		final char[] chars = source.toCharArray();
		for (int i = range[0]; i < range[1]; i++) {
			final int pos = fromChars.indexOf(chars[i]);
			if (pos >= 0) {
				chars[i] = toChars.charAt(pos);
			}
		}
		return new String(chars);
	}

	// ===================== STRING statement =====================

	/**
	 * Result of a STRING operation.
	 */
	public static class StringResult {
		private final String value;
		private final int pointer;
		private final boolean overflow;

		public StringResult(final String value, final int pointer, final boolean overflow) {
			this.value = value;
			this.pointer = pointer;
			this.overflow = overflow;
		}

		public String getValue() {
			return value;
		}

		/** Returns 1-based pointer position after the operation. */
		public int getPointer() {
			return pointer;
		}

		public boolean isOverflow() {
			return overflow;
		}
	}

	/**
	 * A source item with its delimiter for STRING statement.
	 */
	public static class StringSource {
		private final String value;
		private final String delimiter;
		private final boolean delimiterIsSize;

		/**
		 * @param value           source value
		 * @param delimiter       delimiter string (null if DELIMITED BY SIZE)
		 * @param delimiterIsSize true if DELIMITED BY SIZE
		 */
		public StringSource(final String value, final String delimiter, final boolean delimiterIsSize) {
			this.value = value;
			this.delimiter = delimiter;
			this.delimiterIsSize = delimiterIsSize;
		}

		/** Gets the effective source value (up to delimiter or full if SIZE). */
		public String getEffectiveValue() {
			if (value == null) {
				return "";
			}
			if (delimiterIsSize || delimiter == null) {
				return value;
			}
			final int pos = value.indexOf(delimiter);
			if (pos >= 0) {
				return value.substring(0, pos);
			}
			return value;
		}
	}

	/**
	 * STRING source-1 DELIMITED BY ... INTO target WITH POINTER pointer-var.
	 * Per IBM manual: characters are transferred left to right into the target
	 * starting at the pointer position (1-based). If overflow occurs, the
	 * operation stops and ON OVERFLOW is triggered.
	 *
	 * @param sources       list of source items with delimiters
	 * @param target        current target value (determines max length)
	 * @param pointer       1-based starting position in target
	 * @return StringResult with final value, pointer, and overflow flag
	 */
	public static StringResult string(final List<StringSource> sources, final String target, final int pointer) {
		if (target == null) {
			return new StringResult("", pointer, true);
		}
		final int maxLen = target.length();
		final char[] result = target.toCharArray();
		int pos = pointer - 1; // Convert to 0-based
		boolean overflow = false;

		if (pos < 0 || pos >= maxLen) {
			return new StringResult(target, pointer, true);
		}

		for (final StringSource source : sources) {
			final String effective = source.getEffectiveValue();
			for (int i = 0; i < effective.length(); i++) {
				if (pos >= maxLen) {
					overflow = true;
					break;
				}
				result[pos] = effective.charAt(i);
				pos++;
			}
			if (overflow) {
				break;
			}
		}

		return new StringResult(new String(result), pos + 1, overflow);
	}

	/**
	 * Simplified STRING - concatenate parts into target with size limit.
	 */
	public static String stringSimple(final String target, final int targetLength, final String... parts) {
		final StringBuilder sb = new StringBuilder();
		for (final String part : parts) {
			if (part != null) {
				sb.append(part);
			}
		}
		final String result = sb.toString();
		if (result.length() >= targetLength) {
			return result.substring(0, targetLength);
		}
		return result + CobolConstants.spaces(targetLength - result.length());
	}

	// ===================== UNSTRING statement =====================

	/**
	 * Result of an UNSTRING operation for one receiving field.
	 */
	public static class UnstringField {
		private final String value;
		private final String delimiter;
		private final int count;

		public UnstringField(final String value, final String delimiter, final int count) {
			this.value = value;
			this.delimiter = delimiter;
			this.count = count;
		}

		public String getValue() {
			return value;
		}

		/** The actual delimiter that caused the split (DELIMITER IN). */
		public String getDelimiter() {
			return delimiter;
		}

		/** Character count transferred (COUNT IN). */
		public int getCount() {
			return count;
		}
	}

	/**
	 * Result of a complete UNSTRING operation.
	 */
	public static class UnstringResult {
		private final List<UnstringField> fields;
		private final int pointer;
		private final int tallyCount;
		private final boolean overflow;

		public UnstringResult(final List<UnstringField> fields, final int pointer,
				final int tallyCount, final boolean overflow) {
			this.fields = fields;
			this.pointer = pointer;
			this.tallyCount = tallyCount;
			this.overflow = overflow;
		}

		public List<UnstringField> getFields() {
			return fields;
		}

		/** 1-based pointer after the operation. */
		public int getPointer() {
			return pointer;
		}

		/** TALLYING IN value - number of receiving fields acted upon. */
		public int getTallyCount() {
			return tallyCount;
		}

		public boolean isOverflow() {
			return overflow;
		}
	}

	/**
	 * UNSTRING source DELIMITED BY [ALL] delimiter [OR [ALL] delimiter]...
	 * INTO field-1 [DELIMITER IN d1] [COUNT IN c1] ...
	 * WITH POINTER pointer TALLYING IN tally ON OVERFLOW ...
	 *
	 * Per IBM manual: characters are examined left to right. When a delimiter
	 * is found, characters up to it are moved to the current receiving field.
	 * ALL causes consecutive delimiters to be treated as one.
	 *
	 * @param source        the source string to unstring
	 * @param delimiters    list of delimiter strings
	 * @param allFlags      for each delimiter, whether ALL is specified
	 * @param numFields     number of receiving fields
	 * @param pointer       1-based starting position
	 * @return UnstringResult
	 */
	public static UnstringResult unstring(final String source, final List<String> delimiters,
			final List<Boolean> allFlags, final int numFields, final int pointer) {
		final List<UnstringField> fields = new ArrayList<>();
		boolean overflow = false;

		if (source == null || source.isEmpty()) {
			for (int i = 0; i < numFields; i++) {
				fields.add(new UnstringField("", "", 0));
			}
			return new UnstringResult(fields, pointer, 0, false);
		}

		int pos = pointer - 1; // Convert to 0-based
		if (pos < 0) {
			pos = 0;
		}

		int fieldsUsed = 0;

		while (fieldsUsed < numFields && pos <= source.length()) {
			if (pos == source.length()) {
				fields.add(new UnstringField("", "", 0));
				fieldsUsed++;
				break;
			}

			// Find the next delimiter
			int delimPos = -1;
			int delimIdx = -1;
			int delimLength = 0;

			for (int d = 0; d < delimiters.size(); d++) {
				final String delim = delimiters.get(d);
				if (delim == null || delim.isEmpty()) {
					continue;
				}
				final int found = source.indexOf(delim, pos);
				if (found >= 0 && (delimPos < 0 || found < delimPos)) {
					delimPos = found;
					delimIdx = d;
					delimLength = delim.length();
				}
			}

			if (delimPos < 0) {
				// No delimiter found - take rest of string
				final String value = source.substring(pos);
				fields.add(new UnstringField(value, "", value.length()));
				fieldsUsed++;
				pos = source.length();
			} else {
				// Take characters up to delimiter
				final String value = source.substring(pos, delimPos);
				final String foundDelim = delimiters.get(delimIdx);
				fields.add(new UnstringField(value, foundDelim, value.length()));
				fieldsUsed++;
				pos = delimPos + delimLength;

				// Handle ALL - skip consecutive occurrences of the same delimiter
				if (delimIdx < allFlags.size() && allFlags.get(delimIdx)) {
					while (pos + delimLength <= source.length()
							&& source.substring(pos, pos + delimLength).equals(foundDelim)) {
						pos += delimLength;
					}
				}
			}
		}

		// Check for overflow: more data remaining after all fields filled
		if (pos < source.length() && fieldsUsed >= numFields) {
			overflow = true;
		}

		return new UnstringResult(fields, pos + 1, fieldsUsed, overflow);
	}

	/**
	 * Simple UNSTRING by single delimiter (backward compatible).
	 */
	public static String[] unstringSimple(final String source, final String delimiter) {
		if (source == null) {
			return new String[0];
		}
		if (delimiter == null || delimiter.isEmpty()) {
			return new String[] { source };
		}
		return source.split(java.util.regex.Pattern.quote(delimiter), -1);
	}

	// ===================== INSPECT TALLYING convenience helpers =====================

	/**
	 * Counts leading occurrences of a single character in a string.
	 * Useful for INSPECT TALLYING ... FOR LEADING SPACES (or any character).
	 *
	 * @param source    the string to inspect
	 * @param character the character to count
	 * @return number of leading occurrences
	 */
	public static int tallyLeading(final String source, final char character) {
		if (source == null) {
			return 0;
		}
		int count = 0;
		for (int i = 0; i < source.length(); i++) {
			if (source.charAt(i) == character) {
				count++;
			} else {
				break;
			}
		}
		return count;
	}

	/**
	 * Counts all occurrences of a single character in a string.
	 *
	 * @param source    the string to inspect
	 * @param character the character to count
	 * @return number of occurrences
	 */
	public static int tallyAll(final String source, final char character) {
		if (source == null) {
			return 0;
		}
		int count = 0;
		for (int i = 0; i < source.length(); i++) {
			if (source.charAt(i) == character) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Counts all character positions in a string (TALLYING FOR CHARACTERS).
	 *
	 * @param source the string to inspect
	 * @return length of the string
	 */
	public static int tallyCharacters(final String source) {
		return source == null ? 0 : source.length();
	}

	// ===================== Internal helpers =====================

	/**
	 * Computes the effective range [start, end) within source based on
	 * BEFORE INITIAL and AFTER INITIAL boundaries.
	 */
	private static int[] computeRange(final String source, final String beforeInitial, final String afterInitial) {
		int start = 0;
		int end = source.length();

		if (afterInitial != null && !afterInitial.isEmpty()) {
			final int pos = source.indexOf(afterInitial);
			if (pos >= 0) {
				start = pos + afterInitial.length();
			} else {
				// AFTER INITIAL not found: no characters inspected
				return new int[] { 0, 0 };
			}
		}

		if (beforeInitial != null && !beforeInitial.isEmpty()) {
			final int pos = source.indexOf(beforeInitial, start);
			if (pos >= 0) {
				end = pos;
			}
			// If BEFORE INITIAL not found, end stays at source.length()
		}

		if (start > end) {
			start = end;
		}

		return new int[] { start, end };
	}

	/**
	 * FUNCTION TRIM — removes leading and trailing spaces.
	 * IBM ILE COBOL: FUNCTION TRIM(string)
	 */
	public static String functionTrim(final String value) {
		return value == null ? "" : value.trim();
	}

	/**
	 * FUNCTION TRIM trailing — removes trailing spaces only.
	 * IBM ILE COBOL: FUNCTION TRIM(string TRAILING)
	 */
	public static String functionTrimR(final String value) {
		if (value == null) return "";
		int end = value.length();
		while (end > 0 && value.charAt(end - 1) == ' ') {
			end--;
		}
		return value.substring(0, end);
	}

	/**
	 * FUNCTION TRIM leading — removes leading spaces only.
	 * IBM ILE COBOL: FUNCTION TRIM(string LEADING)
	 */
	public static String functionTrimL(final String value) {
		if (value == null) return "";
		int start = 0;
		while (start < value.length() && value.charAt(start) == ' ') {
			start++;
		}
		return value.substring(start);
	}
}
