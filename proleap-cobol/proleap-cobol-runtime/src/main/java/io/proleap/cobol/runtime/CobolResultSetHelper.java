package io.proleap.cobol.runtime;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;

/**
 * Helper for safely reading ResultSet columns when the JDBC driver
 * cannot convert the underlying SQL type to the requested Java type.
 * <p>
 * JT400 (IBM Toolbox for Java) throws NumberFormatException when
 * getBigDecimal is called on DATE/CHAR columns. This helper falls
 * back to getString and parses manually.
 */
public final class CobolResultSetHelper {

	private CobolResultSetHelper() {
	}

	/**
	 * Safe replacement for {@code rs.getString(col)} that returns DB2/400-compatible
	 * defaults when the column value is NULL.
	 * <p>
	 * In COBOL on DB2/400, when a DATE/TIMESTAMP/TIME column is NULL and no indicator
	 * variable is used, the host variable receives a default value rather than blanks:
	 * <ul>
	 *   <li>DATE → "0001-01-01"</li>
	 *   <li>TIMESTAMP → "0001-01-01-00.00.00.000000"</li>
	 *   <li>TIME → "00.00.00"</li>
	 *   <li>Other types → "" (empty string)</li>
	 * </ul>
	 */
	public static String safeGetString(final ResultSet rs, final int col) {
		try {
			final String val = rs.getString(col);
			if (val != null) {
				// Check column type to convert JDBC format to AS/400 format
				final ResultSetMetaData meta = rs.getMetaData();
				final int type = meta.getColumnType(col);
				if (type == Types.DATE) {
					// JT400 getString() returns dates in connection format (e.g., YY/MM/DD
					// for system naming), but COBOL on AS/400 uses ISO format (YYYY-MM-DD)
					// when SELECT INTO a PIC X(10) host variable.  Convert via getDate()
					// which always returns a java.sql.Date whose toString() is YYYY-MM-DD.
					try {
						final java.sql.Date d = rs.getDate(col);
						if (d != null) {
							return d.toString();  // Returns YYYY-MM-DD (ISO format)
						}
					} catch (final Exception e2) {
						// fall through to raw getString value
					}
					return val;
				}
				if (type == Types.TIMESTAMP) {
					// JDBC format:  yyyy-MM-dd HH:mm:ss.ffffff
					// AS/400 format: yyyy-MM-dd-HH.mm.ss.ffffff
					return convertTimestampToAs400Format(val);
				}
				if (type == Types.TIME) {
					// JDBC format:  HH:mm:ss
					// AS/400 format: HH.mm.ss
					return val.replace(':', '.');
				}
				return val;
			}
			// NULL value: return DB2/400 default based on column type
			final ResultSetMetaData meta = rs.getMetaData();
			final int type = meta.getColumnType(col);
			if (type == Types.DATE) {
				return "0001-01-01";
			}
			if (type == Types.TIMESTAMP) {
				return "0001-01-01-00.00.00.000000";
			}
			if (type == Types.TIME) {
				return "00.00.00";
			}
			return "";
		} catch (final Exception e) {
			return "";
		}
	}

	/**
	 * Column-name-based variant of safeGetString for SELECT * queries where the
	 * database column order may differ from the COBOL structure field order.
	 * Returns null if the column does not exist in the result set (caller should
	 * skip the assignment to preserve the COBOL field's initial value).
	 */
	public static String safeGetString(final ResultSet rs, final String colName) {
		try {
			final int col = rs.findColumn(colName);
			return safeGetString(rs, col);
		} catch (final java.sql.SQLException e) {
			// Column not found in result set - return null so caller preserves field default
			return null;
		} catch (final Exception e) {
			return null;
		}
	}

	/**
	 * Column-name-based variant of safeBigDecimal for SELECT * queries where the
	 * database column order may differ from the COBOL structure field order.
	 * Returns null if the column does not exist in the result set.
	 */
	public static BigDecimal safeBigDecimalByName(final ResultSet rs, final String colName) {
		try {
			final int col = rs.findColumn(colName);
			return safeBigDecimal(rs, col);
		} catch (final java.sql.SQLException e) {
			// Column not found in result set
			return null;
		} catch (final Exception e) {
			return null;
		}
	}

	/**
	 * Converts a JDBC timestamp string to AS/400 native format.
	 * JDBC: "2026-03-24 16:37:22.570000" -> AS/400: "2026-03-24-16.37.22.570000"
	 * The space between date and time becomes a hyphen, and colons become dots.
	 */
	private static String convertTimestampToAs400Format(final String jdbcTimestamp) {
		if (jdbcTimestamp == null || jdbcTimestamp.length() < 19) {
			return jdbcTimestamp;
		}
		final StringBuilder sb = new StringBuilder(jdbcTimestamp);
		// Replace space between date and time with hyphen (position 10)
		if (sb.length() > 10 && sb.charAt(10) == ' ') {
			sb.setCharAt(10, '-');
		}
		// Replace colons with dots in time portion (positions 13 and 16)
		if (sb.length() > 13 && sb.charAt(13) == ':') {
			sb.setCharAt(13, '.');
		}
		if (sb.length() > 16 && sb.charAt(16) == ':') {
			sb.setCharAt(16, '.');
		}
		return sb.toString();
	}

	/**
	 * Safe replacement for {@code rs.getBigDecimal(col)}.
	 * Falls back to getString + parse when the driver cannot convert.
	 */
	public static BigDecimal safeBigDecimal(final ResultSet rs, final int col) {
		try {
			final BigDecimal bd = rs.getBigDecimal(col);
			return bd != null ? bd : BigDecimal.ZERO;
		} catch (final Exception e) {
			try {
				final String s = rs.getString(col);
				if (s != null && !s.trim().isEmpty()) {
					return new BigDecimal(s.trim());
				}
			} catch (final Exception e2) {
				// ignore – return ZERO
			}
			return BigDecimal.ZERO;
		}
	}

	/**
	 * Reads a SQL SET result from a ResultSet into a BigDecimal variable.
	 * Used by generated code for: SET :numericVar = expression
	 */
	public static BigDecimal safeGetValue(final ResultSet rs, final int col, final BigDecimal defaultVal) {
		return safeBigDecimal(rs, col);
	}

	/**
	 * Reads a SQL SET result from a ResultSet into a String variable.
	 * Used by generated code for: SET :stringVar = expression (e.g., CURRENT DATE)
	 */
	public static String safeGetValue(final ResultSet rs, final int col, final String defaultVal) {
		return safeGetString(rs, col);
	}
}
