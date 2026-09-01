package io.proleap.cobol.transform.java.util;

import java.math.BigDecimal;

public class JavaLiteralUtils {

	private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);
	private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
	private static final BigDecimal INT_MAX = BigDecimal.valueOf(Integer.MAX_VALUE);
	private static final BigDecimal INT_MIN = BigDecimal.valueOf(Integer.MIN_VALUE);

	public static String mapToLiteral(final BigDecimal value) {
		if (value == null) {
			return "null";
		} else if (BigDecimal.ZERO.compareTo(value) == 0 && value.scale() <= 0) {
			return "BigDecimal.ZERO";
		} else if (BigDecimal.ONE.compareTo(value) == 0 && value.scale() <= 0) {
			return "BigDecimal.ONE";
		} else if (BigDecimal.TEN.compareTo(value) == 0 && value.scale() <= 0) {
			return "BigDecimal.TEN";
		} else if (value.scale() <= 0 && value.compareTo(LONG_MIN) >= 0 && value.compareTo(LONG_MAX) <= 0) {
			final boolean needsLongSuffix = value.compareTo(INT_MAX) > 0 || value.compareTo(INT_MIN) < 0;
			return String.format("BigDecimal.valueOf(%s%s)", value.toBigInteger(), needsLongSuffix ? "L" : "");
		} else if (value.scale() > 0 && value.compareTo(LONG_MIN) >= 0 && value.compareTo(LONG_MAX) <= 0) {
			return String.format("BigDecimal.valueOf(%s)", value);
		} else {
			return String.format("new BigDecimal(\"%s\")", value.toPlainString());
		}
	}

	public static String mapToLiteral(final Boolean value) {
		return String.valueOf(value);
	}

	public static String mapToLiteral(final String value) {
		final String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
		return String.format("\"%s\"", escaped);
	}
}
