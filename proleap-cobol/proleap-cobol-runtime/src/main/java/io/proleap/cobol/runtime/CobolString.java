package io.proleap.cobol.runtime;

/**
 * COBOL string operations (INSPECT, STRING, UNSTRING).
 */
public final class CobolString {

	private CobolString() {
	}

	/**
	 * INSPECT TALLYING - count occurrences of a character or string.
	 */
	public static int inspectTallying(final String source, final String target) {
		if (source == null || target == null || target.isEmpty()) {
			return 0;
		}
		int count = 0;
		int idx = 0;
		while ((idx = source.indexOf(target, idx)) != -1) {
			count++;
			idx += target.length();
		}
		return count;
	}

	/**
	 * INSPECT REPLACING - replace all occurrences.
	 */
	public static String inspectReplacing(final String source, final String target, final String replacement) {
		if (source == null || target == null) {
			return source;
		}
		return source.replace(target, replacement);
	}

	/**
	 * STRING - concatenate COBOL strings with DELIMITED BY.
	 */
	public static String string(final String... parts) {
		final StringBuilder sb = new StringBuilder();
		for (final String part : parts) {
			if (part != null) {
				sb.append(part);
			}
		}
		return sb.toString();
	}

	/**
	 * UNSTRING - split a string by delimiter.
	 */
	public static String[] unstring(final String source, final String delimiter) {
		if (source == null) {
			return new String[0];
		}
		if (delimiter == null || delimiter.isEmpty()) {
			return new String[]{source};
		}
		return source.split(java.util.regex.Pattern.quote(delimiter), -1);
	}
}
