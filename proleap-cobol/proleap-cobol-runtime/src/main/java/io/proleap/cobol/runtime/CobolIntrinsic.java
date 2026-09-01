package io.proleap.cobol.runtime;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;

/**
 * Runtime support for COBOL intrinsic functions.
 * <p>
 * Maps COBOL intrinsic function calls (e.g., FUNCTION TEST-DATE-TIME) to Java
 * helper methods. Returns BigDecimal to be compatible with COBOL numeric
 * comparisons (e.g., {@code .compareTo(BigDecimal.ZERO) == 0}).
 */
public final class CobolIntrinsic {

	private CobolIntrinsic() {
		// utility class
	}

	/**
	 * Implements the COBOL intrinsic function TEST-DATE-TIME.
	 * <p>
	 * Returns 0 if the date/time is valid, non-zero (position of first error)
	 * if invalid — matching the IBM ILE COBOL specification.
	 *
	 * @param value      the date/time value to validate (String in format yyyyMMdd
	 *                   for DATE, or other formats for TIME/TIMESTAMP)
	 * @param formatType the format type: "DATE", "TIME", or "TIMESTAMP"
	 * @return BigDecimal.ZERO if valid, BigDecimal.ONE if invalid
	 */
	/**
	 * FUNCTION REVERSE — reverses a string.
	 */
	/**
	 * FUNCTION NUMVAL - converts alphanumeric string to numeric value.
	 */
	public static BigDecimal numval(final String value) {
		if (value == null || value.trim().isEmpty()) {
			return BigDecimal.ZERO;
		}
		try {
			return new BigDecimal(value.trim().replaceAll("[^0-9.\\-+]", ""));
		} catch (NumberFormatException e) {
			return BigDecimal.ZERO;
		}
	}

	/**
	 * FUNCTION NUMVAL-C - converts alphanumeric string with currency/comma to numeric value.
	 * Strips currency signs, commas, and spaces before converting.
	 */
	public static BigDecimal numvalC(final String value) {
		if (value == null || value.trim().isEmpty()) {
			return BigDecimal.ZERO;
		}
		try {
			// Remove currency signs ($, €, £, etc.), commas, and spaces
			String cleaned = value.trim().replaceAll("[^0-9.\\-+]", "");
			if (cleaned.isEmpty()) {
				return BigDecimal.ZERO;
			}
			return new BigDecimal(cleaned);
		} catch (NumberFormatException e) {
			return BigDecimal.ZERO;
		}
	}

	/**
	 * FUNCTION UPPER-CASE - converts string to uppercase.
	 */
	public static String upperCase(final String value) {
		return value == null ? null : value.toUpperCase();
	}

	/**
	 * FUNCTION LOWER-CASE - converts string to lowercase.
	 */
	public static String lowerCase(final String value) {
		return value == null ? null : value.toLowerCase();
	}

	public static String reverse(final String value) {
		if (value == null) {
			return null;
		}
		return new StringBuilder(value).reverse().toString();
	}

	/**
	 * Converts an arbitrary value to a String for use in intrinsic functions.
	 * Handles String, BigDecimal, and group objects.
	 */
	private static String objectToString(final Object value) {
		if (value == null) return null;
		if (value instanceof String) return (String) value;
		if (value instanceof BigDecimal) return ((BigDecimal) value).toPlainString();
		// Group object — use CobolMove.groupToString
		try { return CobolMove.groupToString(value); }
		catch (Exception e) { return value.toString(); }
	}

	public static BigDecimal testDateTime(final String value, final String formatType, final String formatPattern) {
		if (value == null || value.trim().isEmpty()) {
			return BigDecimal.ONE;
		}

		final String trimmed = value.trim();
		final String javaPattern = cobolFormatToJava(formatPattern);

		if (javaPattern != null) {
			return tryParseDate(trimmed, javaPattern) ? BigDecimal.ZERO : BigDecimal.ONE;
		}

		// Fall back to type-based validation if pattern conversion fails
		return testDateTime(value, formatType);
	}

	/**
	 * Object overload for FUNCTION TEST-DATE-TIME (3-arg).
	 * Accepts group objects (converted via objectToString) or String values.
	 */
	public static BigDecimal testDateTime(final Object value, final String formatType, final String formatPattern) {
		return testDateTime(objectToString(value), formatType, formatPattern);
	}

	/**
	 * Converts a COBOL date format pattern to a Java DateTimeFormatter pattern.
	 * COBOL patterns: %d=day, %m=month, @Y=4-digit year, %y=2-digit year.
	 * Literal separators (-, /) are kept as-is.
	 */
	private static String cobolFormatToJava(final String cobolPattern) {
		if (cobolPattern == null || cobolPattern.isEmpty()) {
			return null;
		}

		// Strip surrounding quotes if present
		String pat = cobolPattern;
		if ((pat.startsWith("'") && pat.endsWith("'")) || (pat.startsWith("\"") && pat.endsWith("\""))) {
			pat = pat.substring(1, pat.length() - 1);
		}

		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < pat.length(); i++) {
			final char c = pat.charAt(i);
			if (c == '%' && i + 1 < pat.length()) {
				final char next = pat.charAt(i + 1);
				switch (next) {
				case 'd':
					sb.append("dd");
					i++;
					break;
				case 'm':
					sb.append("MM");
					i++;
					break;
				case 'y':
					sb.append("uu");
					i++;
					break;
				default:
					sb.append(c);
					break;
				}
			} else if (c == '@' && i + 1 < pat.length() && pat.charAt(i + 1) == 'Y') {
				sb.append("uuuu");
				i++;
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	/**
	 * Object overload for FUNCTION TEST-DATE-TIME (2-arg).
	 * Accepts group objects or String values.
	 */
	public static BigDecimal testDateTime(final Object value, final String formatType) {
		return testDateTime(objectToString(value), formatType);
	}

	public static BigDecimal testDateTime(final String value, final String formatType) {
		if (value == null || value.trim().isEmpty()) {
			return BigDecimal.ONE;
		}

		final String trimmed = value.trim();

		switch (formatType.toUpperCase()) {
		case "DATE":
			return isValidDate(trimmed) ? BigDecimal.ZERO : BigDecimal.ONE;
		case "TIME":
			return isValidTime(trimmed) ? BigDecimal.ZERO : BigDecimal.ONE;
		case "TIMESTAMP":
			return isValidTimestamp(trimmed) ? BigDecimal.ZERO : BigDecimal.ONE;
		default:
			// Unknown format type — treat as invalid
			return BigDecimal.ONE;
		}
	}

	/**
	 * FUNCTION ADD-DURATION — adds a duration to a date string.
	 * <p>
	 * COBOL: FUNCTION ADD-DURATION(date DAYS amount)
	 * Java:  CobolIntrinsic.addDuration(date, "DAYS", amount)
	 *
	 * @param date   the date string (yyyyMMdd or yyyy-MM-dd)
	 * @param unit   the duration unit: DAYS, MONTHS, YEARS, HOURS, MINUTES, SECONDS, MICROSECONDS
	 * @param amount the amount to add (may be negative for subtraction)
	 * @return the resulting date string in the same format as the input
	 */
	public static String addDuration(final String date, final String unit, final BigDecimal amount) {
		if (date == null || date.trim().isEmpty()) {
			return date;
		}
		final String trimmed = date.trim();
		final long qty = amount.longValue();

		// Determine input format
		final boolean isIso = trimmed.length() == 10 && trimmed.charAt(4) == '-';
		final String parsePattern = isIso ? "uuuu-MM-dd" : "uuuuMMdd";
		final String outputPattern = isIso ? "uuuu-MM-dd" : "uuuuMMdd";

		try {
			final DateTimeFormatter fmt = DateTimeFormatter.ofPattern(parsePattern)
					.withResolverStyle(ResolverStyle.STRICT);
			LocalDate ld = LocalDate.parse(trimmed, fmt);

			switch (unit.toUpperCase()) {
			case "DAYS":
				ld = ld.plusDays(qty);
				break;
			case "MONTHS":
				ld = ld.plusMonths(qty);
				break;
			case "YEARS":
				ld = ld.plusYears(qty);
				break;
			default:
				// HOURS, MINUTES, SECONDS, MICROSECONDS — for date-only values,
				// these have no effect; return unchanged.
				break;
			}

			final DateTimeFormatter outFmt = DateTimeFormatter.ofPattern(outputPattern);
			return ld.format(outFmt);
		} catch (final DateTimeParseException e) {
			// If we cannot parse, return the original value unchanged
			return date;
		}
	}

	/**
	 * FUNCTION SUBTRACT-DURATION — subtracts a duration from a date/timestamp string.
	 * Delegates to addDuration with negated amount.
	 */
	public static String subtractDuration(final String date, final String unit, final BigDecimal amount) {
		return addDuration(date, unit, amount.negate());
	}

	/**
	 * FUNCTION INTEGER-OF-DATE — converts a YYYYMMDD date to a Lillian day number.
	 * <p>
	 * The Lillian day system counts days since December 31, 1600 (day 0).
	 * January 1, 1601 is day 1.
	 * <p>
	 * COBOL: FUNCTION INTEGER-OF-DATE(20240315)
	 * Java:  CobolIntrinsic.integerOfDate(new BigDecimal(20240315))
	 *
	 * @param dateValue a BigDecimal representing a date in YYYYMMDD format
	 * @return the Lillian day number as BigDecimal
	 */
	public static BigDecimal integerOfDate(final BigDecimal dateValue) {
		final int dateInt = dateValue.intValue();
		final int year = dateInt / 10000;
		final int month = (dateInt % 10000) / 100;
		final int day = dateInt % 100;

		// Lillian day 1 = January 1, 1601
		final LocalDate epoch = LocalDate.of(1600, 12, 31);
		final LocalDate target = LocalDate.of(year, month, day);
		final long days = ChronoUnit.DAYS.between(epoch, target);
		return BigDecimal.valueOf(days);
	}

	/**
	 * FUNCTION DATE-OF-INTEGER — converts a Lillian day number back to YYYYMMDD.
	 * <p>
	 * The reverse of INTEGER-OF-DATE. Day 1 = January 1, 1601.
	 * <p>
	 * COBOL: FUNCTION DATE-OF-INTEGER(154874)
	 * Java:  CobolIntrinsic.dateOfInteger(new BigDecimal(154874))
	 *
	 * @param intValue a BigDecimal representing the Lillian day number
	 * @return the date as a BigDecimal in YYYYMMDD format
	 */
	public static BigDecimal dateOfInteger(final BigDecimal intValue) {
		final long days = intValue.longValue();
		final LocalDate epoch = LocalDate.of(1600, 12, 31);
		final LocalDate target = epoch.plusDays(days);
		final int dateInt = target.getYear() * 10000 + target.getMonthValue() * 100 + target.getDayOfMonth();
		return BigDecimal.valueOf(dateInt);
	}

	/**
	 * FUNCTION DAY-OF-INTEGER — converts a Lillian day number to YYYYDDD format.
	 * <p>
	 * Day 1 = January 1, 1601 (001). Returns year * 1000 + dayOfYear.
	 *
	 * @param intValue a BigDecimal representing the Lillian day number
	 * @return the date as a BigDecimal in YYYYDDD format
	 */
	public static BigDecimal dayOfInteger(final BigDecimal intValue) {
		final long days = intValue.longValue();
		final LocalDate epoch = LocalDate.of(1600, 12, 31);
		final LocalDate target = epoch.plusDays(days);
		final int result = target.getYear() * 1000 + target.getDayOfYear();
		return BigDecimal.valueOf(result);
	}

	/**
	 * FUNCTION INTEGER-OF-DAY — converts a YYYYDDD (Julian) date to a Lillian day number.
	 * <p>
	 * The reverse of DAY-OF-INTEGER.
	 *
	 * @param dateValue a BigDecimal representing a date in YYYYDDD format
	 * @return the Lillian day number as BigDecimal
	 */
	public static BigDecimal integerOfDay(final BigDecimal dateValue) {
		final int dateInt = dateValue.intValue();
		final int year = dateInt / 1000;
		final int dayOfYear = dateInt % 1000;
		final LocalDate epoch = LocalDate.of(1600, 12, 31);
		final LocalDate target = LocalDate.ofYearDay(year, dayOfYear);
		final long lillianDay = ChronoUnit.DAYS.between(epoch, target);
		return BigDecimal.valueOf(lillianDay);
	}

	/**
	 * Validates a date string in COBOL DATE format.
	 * Supports yyyyMMdd (8 digits), yyyy-MM-dd (10 chars with dashes),
	 * and dd/MM/yyyy or dd.MM.yyyy (10 chars with separators).
	 */
	private static boolean isValidDate(final String value) {
		// Try yyyyMMdd (standard COBOL DATE format, 8 digits)
		if (value.length() == 8) {
			return tryParseDate(value, "uuuuMMdd");
		}
		// Try yyyy-MM-dd (ISO format, 10 chars)
		if (value.length() == 10 && value.charAt(4) == '-') {
			return tryParseDate(value, "uuuu-MM-dd");
		}
		// Try dd/MM/yyyy
		if (value.length() == 10 && value.charAt(2) == '/') {
			return tryParseDate(value, "dd/MM/uuuu");
		}
		// Try dd.MM.yyyy
		if (value.length() == 10 && value.charAt(2) == '.') {
			return tryParseDate(value, "dd.MM.uuuu");
		}
		return false;
	}

	private static boolean tryParseDate(final String value, final String pattern) {
		try {
			final DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern)
					.withResolverStyle(ResolverStyle.STRICT);
			LocalDate.parse(value, fmt);
			return true;
		} catch (final DateTimeParseException e) {
			return false;
		}
	}

	/**
	 * Validates a time string in COBOL TIME format (HHmmss, 6 digits).
	 */
	private static boolean isValidTime(final String value) {
		if (value.length() < 6) {
			return false;
		}
		try {
			final int hh = Integer.parseInt(value.substring(0, 2));
			final int mm = Integer.parseInt(value.substring(2, 4));
			final int ss = Integer.parseInt(value.substring(4, 6));
			return hh >= 0 && hh <= 23 && mm >= 0 && mm <= 59 && ss >= 0 && ss <= 59;
		} catch (final NumberFormatException e) {
			return false;
		}
	}

	/**
	 * Validates a timestamp string (yyyyMMddHHmmss or longer, at least 14 digits).
	 */
	private static boolean isValidTimestamp(final String value) {
		if (value.length() < 14) {
			return false;
		}
		return isValidDate(value.substring(0, 8)) && isValidTime(value.substring(8, 14));
	}

	/**
	 * Implements COBOL FUNCTION TRIM(string) — trims leading and trailing spaces.
	 * Single-argument form per IBM ILE COBOL specification.
	 */
	public static String trim(final String value) {
		if (value == null) {
			return "";
		}
		return value.trim();
	}

	/**
	 * Implements COBOL FUNCTION TRIM(string, trimChar).
	 * Trims leading and trailing occurrences of the specified character.
	 */
	public static String trim(final String value, final String trimChar) {
		if (value == null) {
			return "";
		}
		if (trimChar == null || trimChar.isEmpty()) {
			return value.trim();
		}
		final char c = trimChar.charAt(0);
		int start = 0;
		int end = value.length();
		while (start < end && value.charAt(start) == c) {
			start++;
		}
		while (end > start && value.charAt(end - 1) == c) {
			end--;
		}
		return value.substring(start, end);
	}

	/**
	 * Implements COBOL FUNCTION TRIM(string TRAILING) — trims trailing spaces only.
	 * Equivalent to FUNCTION TRIMR in some COBOL dialects.
	 */
	public static String trimr(final String value) {
		if (value == null) {
			return "";
		}
		return value.replaceAll("\\s+$", "");
	}

	/**
	 * Implements COBOL FUNCTION TRIM(string LEADING) — trims leading spaces only.
	 * Equivalent to FUNCTION TRIML in some COBOL dialects.
	 */
	public static String triml(final String value) {
		if (value == null) {
			return "";
		}
		return value.replaceAll("^\\s+", "");
	}

	/**
	 * FUNCTION MOD — returns the remainder of integer division.
	 * <p>
	 * COBOL: FUNCTION MOD(a, b)
	 * Returns a - (b * FUNCTION INTEGER(a / b))
	 * per the IBM ILE COBOL Language Reference.
	 *
	 * @param a the dividend
	 * @param b the divisor
	 * @return the modulo result as BigDecimal
	 */
	public static BigDecimal mod(final BigDecimal a, final BigDecimal b) {
		if (b == null || b.compareTo(BigDecimal.ZERO) == 0) {
			return BigDecimal.ZERO;
		}
		// COBOL MOD: a - (b * INTEGER(a/b))
		// INTEGER rounds toward negative infinity (floor)
		final BigDecimal quotient = a.divide(b, 0, java.math.RoundingMode.FLOOR);
		return a.subtract(b.multiply(quotient));
	}

	/**
	 * FUNCTION SUM — sums an array of BigDecimal values.
	 * Used for FUNCTION SUM(array(ALL)) patterns.
	 */
	public static BigDecimal sum(final BigDecimal... values) {
		if (values == null || values.length == 0) return BigDecimal.ZERO;
		BigDecimal total = BigDecimal.ZERO;
		for (final BigDecimal v : values) {
			if (v != null) total = total.add(v);
		}
		return total;
	}

	/**
	 * Overload for sum with Object argument (for generated code that passes incorrect types).
	 */
	public static BigDecimal sum(final Object value) {
		if (value instanceof BigDecimal) return (BigDecimal) value;
		if (value instanceof BigDecimal[]) return sum((BigDecimal[]) value);
		return BigDecimal.ZERO;
	}

	/**
	 * FUNCTION CURRENT-DATE — returns current date/time as a 21-char string.
	 * Format: YYYYMMDDHHMMSShhGshmm (year, month, day, hour, min, sec, hundredths,
	 * GMT sign, GMT hours, GMT minutes).
	 */
	public static String currentDate() {
		LocalDateTime now = LocalDateTime.now();
		String dateTime = now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "00";
		// GMT offset: +0000 (simplified)
		return dateTime + "+0000";
	}

	/**
	 * FUNCTION LENGTH — returns the length of a data item.
	 * For strings, returns string length; for BigDecimal, returns digit count;
	 * for groups, returns the group string representation length.
	 */
	public static BigDecimal length(final Object value) {
		if (value == null) {
			return BigDecimal.ZERO;
		}
		if (value instanceof String) {
			return BigDecimal.valueOf(((String) value).length());
		}
		if (value instanceof BigDecimal) {
			return BigDecimal.valueOf(((BigDecimal) value).toPlainString().length());
		}
		// For group objects, try CobolMove.groupToString
		try {
			String s = CobolMove.groupToString(value);
			return BigDecimal.valueOf(s.length());
		} catch (Exception e) {
			return BigDecimal.ZERO;
		}
	}

	/**
	 * FUNCTION CHAR — returns the character at the specified position in the
	 * program collating sequence. In ASCII, this is simply the character
	 * with the code point equal to (argument - 1), per IBM ILE COBOL specification.
	 * Named "charFunction" because "char" is a Java reserved word.
	 *
	 * @param position the ordinal position (1-based) in the collating sequence
	 * @return the character as a String
	 */
	public static String charFunction(final BigDecimal position) {
		if (position == null) {
			return "\u0000";
		}
		int pos = position.intValue();
		// COBOL CHAR is 1-based: CHAR(1) = X'00', CHAR(2) = X'01', etc.
		int codePoint = pos - 1;
		if (codePoint < 0 || codePoint > 0xFFFF) {
			return "\u0000";
		}
		return String.valueOf((char) codePoint);
	}

	/**
	 * FUNCTION ORD — returns the ordinal position of a character in the
	 * program collating sequence. Inverse of FUNCTION CHAR.
	 *
	 * @param value the character whose ordinal position to return
	 * @return ordinal position (1-based)
	 */
	public static BigDecimal ord(final String value) {
		if (value == null || value.isEmpty()) {
			return BigDecimal.ZERO;
		}
		return BigDecimal.valueOf((int) value.charAt(0) + 1);
	}

	/**
	 * Implements the IBM ILE COBOL FUNCTION FIND-DURATION.
	 * <p>
	 * Calculates the duration between two date values in the specified unit.
	 * The dates should be in ISO format (yyyy-MM-dd) or COBOL date format (yyyyMMdd).
	 *
	 * @param startDate  the start date string
	 * @param endDate    the end date string
	 * @param durationUnit the unit of duration ("DAYS", "MONTHS", "YEARS")
	 * @return the duration between the two dates in the specified unit
	 */
	public static BigDecimal findDuration(final String startDate, final String endDate, final String durationUnit) {
		try {
			LocalDate start = parseFlexibleDate(startDate);
			LocalDate end = parseFlexibleDate(endDate);
			if (start == null || end == null) {
				return BigDecimal.ZERO;
			}
			String unit = durationUnit != null ? durationUnit.trim().toUpperCase() : "DAYS";
			switch (unit) {
				case "DAYS":
					return BigDecimal.valueOf(ChronoUnit.DAYS.between(start, end));
				case "MONTHS":
					return BigDecimal.valueOf(ChronoUnit.MONTHS.between(start, end));
				case "YEARS":
					return BigDecimal.valueOf(ChronoUnit.YEARS.between(start, end));
				default:
					return BigDecimal.valueOf(ChronoUnit.DAYS.between(start, end));
			}
		} catch (Exception e) {
			return BigDecimal.ZERO;
		}
	}

	/**
	 * FUNCTION NATIONAL-OF — converts an alphanumeric string to national (UTF-16).
	 * In Java, strings are already Unicode, so this is essentially an identity operation.
	 * Returns a String representation suitable for further processing.
	 *
	 * @param value the alphanumeric string to convert
	 * @return the national representation (same string in Java)
	 */
	public static String nationalOf(final String value) {
		return value == null ? "" : value;
	}

	/** Object overload for FUNCTION NATIONAL-OF. */
	public static String nationalOf(final Object value) {
		return objectToString(value);
	}

	/**
	 * FUNCTION DISPLAY-OF — converts a national string back to alphanumeric display format.
	 * Optionally uses a CCSID for encoding conversion.
	 * In Java, strings are already Unicode, so this is essentially an identity operation.
	 *
	 * @param value the national string
	 * @param ccsid the target CCSID (ignored in Java — all strings are UTF-16)
	 * @return the display representation
	 */
	public static String displayOf(final Object value, final BigDecimal ccsid) {
		return objectToString(value);
	}

	/** Single-arg overload for FUNCTION DISPLAY-OF. */
	public static String displayOf(final Object value) {
		return objectToString(value);
	}

	/**
	 * FUNCTION CONVERT-DATE-TIME — converts a date/time value to the specified output format.
	 * <p>
	 * IBM ILE COBOL: FUNCTION CONVERT-DATE-TIME(value DATE|TIME|TIMESTAMP outputPattern)
	 * <p>
	 * The input value is auto-detected from common formats (yyyyMMdd, yyyy-MM-dd,
	 * yyyyMMddHHmmss, the 21-char CURRENT-DATE format, etc.).
	 * The output format uses COBOL-style patterns:
	 * <ul>
	 *   <li>%Y or @Y — 4-digit year</li>
	 *   <li>%y — 2-digit year</li>
	 *   <li>%m — 2-digit month</li>
	 *   <li>%d — 2-digit day</li>
	 *   <li>%H — 2-digit hour (24h)</li>
	 *   <li>%M — 2-digit minute</li>
	 *   <li>%S — 2-digit second</li>
	 *   <li>Literal separators (-, /, .) are passed through</li>
	 * </ul>
	 *
	 * @param value        the date/time value to convert (String)
	 * @param formatType   the output type keyword: "DATE", "TIME", "TIMESTAMP"
	 * @param formatPattern the output format pattern (COBOL-style, e.g., "%Y-%m-%d")
	 * @return the formatted date/time string
	 */
	public static String convertDateTime(final String value, final String formatType, final String formatPattern) {
		if (value == null || value.trim().isEmpty()) {
			return value;
		}
		final String trimmed = value.trim();

		// Parse the input date/time from the value (auto-detect format)
		LocalDateTime dateTime = parseFlexibleDateTime(trimmed);
		if (dateTime == null) {
			// Cannot parse — return original value unchanged
			return value;
		}

		// Convert the COBOL output pattern to Java DateTimeFormatter pattern
		final String javaPattern = cobolOutputFormatToJava(formatPattern);
		if (javaPattern == null || javaPattern.isEmpty()) {
			// No valid output pattern — return input value
			return value;
		}

		try {
			final DateTimeFormatter outFmt = DateTimeFormatter.ofPattern(javaPattern);
			return dateTime.format(outFmt);
		} catch (final Exception e) {
			return value;
		}
	}

	/** Object overload for FUNCTION CONVERT-DATE-TIME. */
	public static String convertDateTime(final Object value, final String formatType, final String formatPattern) {
		return convertDateTime(objectToString(value), formatType, formatPattern);
	}

	/**
	 * Parses a date/time string from various common COBOL formats.
	 * Supports: yyyyMMdd, yyyy-MM-dd, yyyyMMddHHmmss, 21-char CURRENT-DATE,
	 * and other common variants.
	 */
	private static LocalDateTime parseFlexibleDateTime(String value) {
		if (value == null) return null;
		value = value.trim();

		// 21-char CURRENT-DATE format: YYYYMMDDHHMMSShhGshmm (e.g., "20260413143025000+0000")
		// or at least the first 14 chars are date+time
		if (value.length() >= 14 && !value.contains("-") && !value.contains("/")) {
			try {
				String dtPart = value.substring(0, 14);
				return LocalDateTime.parse(dtPart, DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
						.withResolverStyle(ResolverStyle.STRICT));
			} catch (DateTimeParseException e) {
				// fall through
			}
		}

		// yyyyMMdd (8 digits, date only)
		if (value.length() == 8 && !value.contains("-")) {
			try {
				LocalDate ld = LocalDate.parse(value, DateTimeFormatter.ofPattern("uuuuMMdd")
						.withResolverStyle(ResolverStyle.STRICT));
				return ld.atStartOfDay();
			} catch (DateTimeParseException e) {
				// fall through
			}
		}

		// yyyy-MM-dd (ISO date, 10 chars)
		if (value.length() == 10 && value.charAt(4) == '-') {
			try {
				LocalDate ld = LocalDate.parse(value);
				return ld.atStartOfDay();
			} catch (DateTimeParseException e) {
				// fall through
			}
		}

		// dd/MM/yyyy
		if (value.length() == 10 && value.charAt(2) == '/') {
			try {
				LocalDate ld = LocalDate.parse(value, DateTimeFormatter.ofPattern("dd/MM/uuuu")
						.withResolverStyle(ResolverStyle.STRICT));
				return ld.atStartOfDay();
			} catch (DateTimeParseException e) {
				// fall through
			}
		}

		// dd.MM.yyyy
		if (value.length() == 10 && value.charAt(2) == '.') {
			try {
				LocalDate ld = LocalDate.parse(value, DateTimeFormatter.ofPattern("dd.MM.uuuu")
						.withResolverStyle(ResolverStyle.STRICT));
				return ld.atStartOfDay();
			} catch (DateTimeParseException e) {
				// fall through
			}
		}

		return null;
	}

	/**
	 * Converts a COBOL output format pattern to a Java DateTimeFormatter pattern.
	 * Handles: %Y (yyyy), %y (yy), %m (MM), %d (dd), %H (HH), %M (mm), %S (ss),
	 * @Y (yyyy). Literal separators are passed through.
	 */
	private static String cobolOutputFormatToJava(final String cobolPattern) {
		if (cobolPattern == null || cobolPattern.isEmpty()) {
			return null;
		}

		// Strip surrounding quotes if present
		String pat = cobolPattern;
		if ((pat.startsWith("'") && pat.endsWith("'")) || (pat.startsWith("\"") && pat.endsWith("\""))) {
			pat = pat.substring(1, pat.length() - 1);
		}

		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < pat.length(); i++) {
			final char c = pat.charAt(i);
			if (c == '%' && i + 1 < pat.length()) {
				final char next = pat.charAt(i + 1);
				switch (next) {
				case 'Y':
					sb.append("uuuu");
					i++;
					break;
				case 'y':
					sb.append("uu");
					i++;
					break;
				case 'm':
					sb.append("MM");
					i++;
					break;
				case 'd':
					sb.append("dd");
					i++;
					break;
				case 'H':
					sb.append("HH");
					i++;
					break;
				case 'M':
					sb.append("mm");
					i++;
					break;
				case 'S':
					sb.append("ss");
					i++;
					break;
				default:
					sb.append(c);
					break;
				}
			} else if (c == '@' && i + 1 < pat.length() && pat.charAt(i + 1) == 'Y') {
				sb.append("uuuu");
				i++;
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private static LocalDate parseFlexibleDate(String dateStr) {
		if (dateStr == null) return null;
		dateStr = dateStr.trim();
		if (dateStr.length() == 8 && !dateStr.contains("-")) {
			// yyyyMMdd format
			try {
				return LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT));
			} catch (DateTimeParseException e) {
				return null;
			}
		} else if (dateStr.contains("-")) {
			// ISO format yyyy-MM-dd
			try {
				return LocalDate.parse(dateStr);
			} catch (DateTimeParseException e) {
				return null;
			}
		}
		return null;
	}
}
