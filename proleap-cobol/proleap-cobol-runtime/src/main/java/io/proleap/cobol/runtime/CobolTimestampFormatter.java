package io.proleap.cobol.runtime;

/**
 * Converts JDBC timestamp strings to DB2/400 COBOL format.
 * JDBC:  "2018-02-05 19:51:22.998758"
 * DB2:   "2018-02-05-19.51.22.998758"
 */
public class CobolTimestampFormatter {

	/**
	 * Convert JDBC timestamp format to DB2/400 format.
	 * Only converts if the string matches timestamp pattern.
	 */
	public static String toDb2Format(String jdbcTimestamp) {
		if (jdbcTimestamp == null || jdbcTimestamp.length() < 19) return jdbcTimestamp;
		// Check if it matches JDBC timestamp pattern: YYYY-MM-DD HH:MM:SS
		if (jdbcTimestamp.charAt(4) == '-' && jdbcTimestamp.charAt(7) == '-'
				&& jdbcTimestamp.charAt(10) == ' ' && jdbcTimestamp.charAt(13) == ':'
				&& jdbcTimestamp.charAt(16) == ':') {
			// Replace space with dash, colons with dots
			StringBuilder sb = new StringBuilder(jdbcTimestamp);
			sb.setCharAt(10, '-');
			sb.setCharAt(13, '.');
			sb.setCharAt(16, '.');
			return sb.toString();
		}
		return jdbcTimestamp;
	}
}
