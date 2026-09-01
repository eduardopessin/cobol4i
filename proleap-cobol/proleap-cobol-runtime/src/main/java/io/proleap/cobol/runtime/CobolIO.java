package io.proleap.cobol.runtime;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * COBOL ACCEPT and DISPLAY operations per IBM ILE COBOL Language Reference V7R3.
 *
 * ACCEPT Format 2 (system information):
 * - DATE       → PIC 9(6) → YYMMDD
 * - DATE YYYYMMDD → PIC 9(8) → YYYYMMDD
 * - DAY        → PIC 9(5) → YYDDD (Julian day)
 * - DAY YYYYDDD → PIC 9(7) → YYYYDDD (Julian day)
 * - TIME       → PIC 9(8) → HHMMSSss (ss = hundredths of seconds)
 * - DAY-OF-WEEK → PIC 9(1) → 1=Monday, 2=Tuesday, ..., 7=Sunday
 *
 * DISPLAY: writes to SYSOUT (System.out).
 */
public final class CobolIO {

	private static final DateTimeFormatter FMT_YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
	private static final DateTimeFormatter FMT_YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

	private CobolIO() {
	}

	// ===================== ACCEPT FROM DATE =====================

	/**
	 * ACCEPT identifier FROM DATE.
	 * Per IBM manual: PIC 9(6) format YYMMDD.
	 */
	public static String acceptDate() {
		return LocalDate.now().format(FMT_YYMMDD);
	}

	/**
	 * ACCEPT identifier FROM DATE YYYYMMDD.
	 * Per IBM manual: PIC 9(8) format YYYYMMDD.
	 */
	public static String acceptDateYYYYMMDD() {
		return LocalDate.now().format(FMT_YYYYMMDD);
	}

	// ===================== ACCEPT FROM DAY =====================

	/**
	 * ACCEPT identifier FROM DAY.
	 * Per IBM manual: PIC 9(5) format YYDDD (Julian day of year).
	 */
	public static String acceptDay() {
		final LocalDate now = LocalDate.now();
		final int yy = now.getYear() % 100;
		final int ddd = now.getDayOfYear();
		return String.format("%02d%03d", yy, ddd);
	}

	/**
	 * ACCEPT identifier FROM DAY YYYYDDD.
	 * Per IBM manual: PIC 9(7) format YYYYDDD (Julian day of year).
	 */
	public static String acceptDayYYYYDDD() {
		final LocalDate now = LocalDate.now();
		return String.format("%04d%03d", now.getYear(), now.getDayOfYear());
	}

	// ===================== ACCEPT FROM TIME =====================

	/**
	 * ACCEPT identifier FROM TIME.
	 * Per IBM manual: PIC 9(8) format HHMMSSss where ss is hundredths of seconds.
	 */
	public static String acceptTime() {
		final LocalTime now = LocalTime.now();
		final int hh = now.getHour();
		final int mm = now.getMinute();
		final int ss = now.getSecond();
		final int hundredths = now.getNano() / 10_000_000; // Convert nanos to hundredths
		return String.format("%02d%02d%02d%02d", hh, mm, ss, hundredths);
	}

	// ===================== ACCEPT FROM DAY-OF-WEEK =====================

	/**
	 * ACCEPT identifier FROM DAY-OF-WEEK.
	 * Per IBM manual: PIC 9(1), 1=Monday through 7=Sunday.
	 * Java's DayOfWeek.getValue() already returns 1=Monday..7=Sunday.
	 */
	public static String acceptDayOfWeek() {
		return String.valueOf(LocalDate.now().getDayOfWeek().getValue());
	}

	/**
	 * ACCEPT identifier FROM DAY-OF-WEEK as integer.
	 */
	public static int acceptDayOfWeekInt() {
		return LocalDate.now().getDayOfWeek().getValue();
	}

	// ===================== ACCEPT FROM with specific datetime =====================

	/**
	 * ACCEPT FROM DATE with a specific date (for testing or batch processing).
	 */
	public static String acceptDate(final LocalDate date) {
		return date.format(FMT_YYMMDD);
	}

	public static String acceptDateYYYYMMDD(final LocalDate date) {
		return date.format(FMT_YYYYMMDD);
	}

	public static String acceptDay(final LocalDate date) {
		return String.format("%02d%03d", date.getYear() % 100, date.getDayOfYear());
	}

	public static String acceptDayYYYYDDD(final LocalDate date) {
		return String.format("%04d%03d", date.getYear(), date.getDayOfYear());
	}

	public static String acceptTime(final LocalTime time) {
		return String.format("%02d%02d%02d%02d",
				time.getHour(), time.getMinute(), time.getSecond(),
				time.getNano() / 10_000_000);
	}

	public static String acceptDayOfWeek(final LocalDate date) {
		return String.valueOf(date.getDayOfWeek().getValue());
	}

	// ===================== DISPLAY =====================

	/**
	 * DISPLAY ... UPON SYSOUT.
	 * Per IBM manual: DISPLAY sends data to the output device.
	 * On ILE, this is typically SYSOUT.
	 */
	public static void display(final String... values) {
		final StringBuilder sb = new StringBuilder();
		for (final String value : values) {
			if (value != null) {
				sb.append(value);
			}
		}
		System.out.println(sb.toString());
	}

	/**
	 * DISPLAY with numeric value.
	 */
	public static void display(final java.math.BigDecimal value) {
		System.out.println(value != null ? value.toPlainString() : "0");
	}

	/**
	 * DISPLAY with no advancing (no newline).
	 * Per IBM manual: WITH NO ADVANCING suppresses the line terminator.
	 */
	public static void displayNoAdvancing(final String... values) {
		final StringBuilder sb = new StringBuilder();
		for (final String value : values) {
			if (value != null) {
				sb.append(value);
			}
		}
		System.out.print(sb.toString());
	}

	// ===================== ACCEPT FROM console =====================

	/**
	 * ACCEPT identifier FROM CONSOLE.
	 * Per IBM manual: ACCEPT reads from SYSIN (standard input).
	 */
	public static String acceptFromConsole() {
		try {
			final java.io.BufferedReader reader = new java.io.BufferedReader(
					new java.io.InputStreamReader(System.in));
			final String line = reader.readLine();
			return (line != null) ? line : "";
		} catch (final java.io.IOException e) {
			return "";
		}
	}

	/**
	 * ACCEPT numeric value FROM CONSOLE.
	 */
	public static java.math.BigDecimal acceptNumericFromConsole(final int decimalDigits) {
		final String input = acceptFromConsole().trim();
		try {
			return new java.math.BigDecimal(input).setScale(decimalDigits, java.math.RoundingMode.HALF_UP);
		} catch (final NumberFormatException e) {
			return java.math.BigDecimal.ZERO.setScale(decimalDigits);
		}
	}
}
