package com.example.orders;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Equivalence harness: runs the generated program and the refactored one, then
 * compares their output byte for byte.
 *
 * <p>This is what makes the second migration step safe. The generated Java is
 * a faithful but unidiomatic translation of the COBOL; the refactored class is
 * a human/LLM rewrite. Neither is trusted on its own — the generated version
 * acts as the executable specification, and any refactoring that changes
 * observable behaviour fails here.
 *
 * <p>Deliberately dependency-free (no JUnit) so it can run from
 * {@code run.sh} with nothing but a JDK.
 *
 * <p>Exit code 0 = identical, 1 = differences found.
 */
public final class EquivalenceTest {

	public static void main(final String[] args) throws Exception {
		final String generated = runGenerated();
		final String refactored = runRefactored();

		final boolean identical = generated.equals(refactored);

		System.out.println("=== EQUIVALENCE CHECK ===");
		System.out.printf("generated : %d bytes, %d lines%n", generated.length(), countLines(generated));
		System.out.printf("refactored: %d bytes, %d lines%n", refactored.length(), countLines(refactored));
		System.out.println();

		if (identical) {
			System.out.println("RESULT: IDENTICAL — refactoring preserved behaviour.");
			System.exit(0);
		}

		System.out.println("RESULT: DIFFERENCES FOUND");
		System.out.println();
		reportDifferences(generated, refactored);
		System.exit(1);
	}

	/**
	 * Runs the generated {@code ORDRPT} class, capturing stdout.
	 *
	 * <p>Loaded reflectively so this file compiles even when the generated
	 * sources have not been produced yet.
	 */
	private static String runGenerated() throws Exception {
		final PrintStream originalOut = System.out;
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

		try {
			System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));

			final Class<?> ordrpt = Class.forName("ORDRPT");
			final Object instance = ordrpt.getDeclaredConstructor().newInstance();
			ordrpt.getMethod("procedureDivision").invoke(instance);
		} finally {
			System.setOut(originalOut);
		}

		return buffer.toString(StandardCharsets.UTF_8);
	}

	/** Runs the refactored report over the same sample data. */
	private static String runRefactored() {
		final StringBuilder sb = new StringBuilder();
		new OrderReport(sb).run(OrderReport.sampleHeader(), OrderReport.sampleLines());
		return sb.toString();
	}

	private static int countLines(final String text) {
		return text.isEmpty() ? 0 : text.split("\n", -1).length;
	}

	/** Prints the first differing lines, with visible markers for whitespace. */
	private static void reportDifferences(final String generated, final String refactored) {
		final List<String> a = List.of(generated.split("\n", -1));
		final List<String> b = List.of(refactored.split("\n", -1));
		final int max = Math.max(a.size(), b.size());

		int shown = 0;

		for (int i = 0; i < max && shown < 10; i++) {
			final String left = i < a.size() ? a.get(i) : "<missing>";
			final String right = i < b.size() ? b.get(i) : "<missing>";

			if (!left.equals(right)) {
				System.out.printf("line %d:%n", i + 1);
				System.out.printf("  generated : [%s]%n", left);
				System.out.printf("  refactored: [%s]%n", right);
				shown++;
			}
		}

		if (shown == 0) {
			System.out.println("(line counts differ but no line-level mismatch found)");
		}
	}
}
