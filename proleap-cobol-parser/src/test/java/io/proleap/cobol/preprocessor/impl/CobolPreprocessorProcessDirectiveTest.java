package io.proleap.cobol.preprocessor.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression tests for {@link CobolPreprocessorImpl#stripProcessDirectives}.
 *
 * <p>IBM i COBOL sources start with compiler directives such as
 * {@code PROCESS NOMONOPRC.}, which must be removed before parsing. The
 * stripper must not confuse them with user-defined names that merely begin
 * with the same letters: {@code PROCESS-ONE-LINE.} is a paragraph, and
 * deleting it silently drops that paragraph's body from the generated code.
 */
public class CobolPreprocessorProcessDirectiveTest {

	private final CobolPreprocessorImpl preprocessor = new CobolPreprocessorImpl();

	/** Builds a fixed-format line: 6 columns of sequence area, then the text. */
	private static String fixedLine(final String areaAText) {
		return "      " + " " + areaAText;
	}

	private String strip(final String... lines) {
		return preprocessor.stripProcessDirectives(String.join("\n", lines));
	}

	@Test
	public void stripsPlainProcessDirective() {
		final String result = strip(
				fixedLine("PROCESS NOMONOPRC."),
				fixedLine("IDENTIFICATION DIVISION."));

		assertFalse("PROCESS directive must be removed", result.contains("NOMONOPRC"));
		assertTrue("following lines must survive", result.contains("IDENTIFICATION DIVISION."));
	}

	@Test
	public void stripsCblDirective() {
		final String result = strip(
				fixedLine("CBL OPTION."),
				fixedLine("IDENTIFICATION DIVISION."));

		assertFalse("CBL directive must be removed", result.contains("OPTION"));
		assertTrue(result.contains("IDENTIFICATION DIVISION."));
	}

	@Test
	public void stripsMultiLineProcessDirective() {
		final String result = strip(
				fixedLine("PROCESS NOMONOPRC"),
				fixedLine("        VARCHAR DATETIME."),
				fixedLine("IDENTIFICATION DIVISION."));

		assertFalse(result.contains("NOMONOPRC"));
		assertFalse("continuation line must be removed too", result.contains("VARCHAR DATETIME"));
		assertTrue(result.contains("IDENTIFICATION DIVISION."));
	}

	/**
	 * A paragraph named PROCESS-... must survive. A {@code \b} word boundary
	 * after the keyword also matches before a hyphen, which previously deleted
	 * these paragraphs and produced empty auto-generated stubs in their place.
	 */
	@Test
	public void keepsParagraphNamedProcessHyphen() {
		final String result = strip(
				fixedLine("PROCESS-ONE-LINE."),
				fixedLine("    MOVE 1 TO WS-N."));

		assertTrue("PROCESS-ONE-LINE is a paragraph, not a directive",
				result.contains("PROCESS-ONE-LINE."));
		assertTrue("its body must survive", result.contains("MOVE 1 TO WS-N."));
	}

	@Test
	public void keepsParagraphNamedCblHyphen() {
		final String result = strip(fixedLine("CBL-CHECK-INPUT."));

		assertTrue("CBL-CHECK-INPUT is a paragraph, not a directive",
				result.contains("CBL-CHECK-INPUT."));
	}

	@Test
	public void keepsParagraphStartingWithProcessPrefix() {
		final String result = strip(fixedLine("PROCESSING-LOOP."));

		assertTrue(result.contains("PROCESSING-LOOP."));
	}

	@Test
	public void keepsSourceWithoutDirectives() {
		final String result = strip(
				fixedLine("IDENTIFICATION DIVISION."),
				fixedLine("PROGRAM-ID. DEMO."));

		assertTrue(result.contains("IDENTIFICATION DIVISION."));
		assertTrue(result.contains("PROGRAM-ID. DEMO."));
	}
}
