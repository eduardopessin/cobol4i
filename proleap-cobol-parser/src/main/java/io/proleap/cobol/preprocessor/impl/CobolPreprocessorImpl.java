/*
 * Copyright (C) 2017, Ulrich Wolffgang <ulrich.wolffgang@proleap.io>
 * All rights reserved.
 *
 * This software may be modified and distributed under the terms
 * of the MIT license. See the LICENSE file for details.
 */

package io.proleap.cobol.preprocessor.impl;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.proleap.cobol.asg.params.CobolDialect;
import io.proleap.cobol.asg.params.CobolParserParams;
import io.proleap.cobol.preprocessor.CobolPreprocessor;
import io.proleap.cobol.preprocessor.sub.CobolLine;
import io.proleap.cobol.preprocessor.sub.document.CobolDocumentParser;
import io.proleap.cobol.preprocessor.sub.document.impl.CobolDocumentParserImpl;
import io.proleap.cobol.preprocessor.sub.line.reader.CobolLineReader;
import io.proleap.cobol.preprocessor.sub.line.reader.impl.CobolLineReaderImpl;
import io.proleap.cobol.preprocessor.sub.line.rewriter.CobolCommentEntriesMarker;
import io.proleap.cobol.preprocessor.sub.line.rewriter.CobolInlineCommentEntriesNormalizer;
import io.proleap.cobol.preprocessor.sub.line.rewriter.CobolLineIndicatorProcessor;
import io.proleap.cobol.preprocessor.sub.line.rewriter.impl.CobolCommentEntriesMarkerImpl;
import io.proleap.cobol.preprocessor.sub.line.rewriter.impl.CobolInlineCommentEntriesNormalizerImpl;
import io.proleap.cobol.preprocessor.sub.line.rewriter.impl.CobolLineIndicatorProcessorImpl;
import io.proleap.cobol.preprocessor.sub.line.writer.CobolLineWriter;
import io.proleap.cobol.preprocessor.sub.line.writer.impl.CobolLineWriterImpl;

public class CobolPreprocessorImpl implements CobolPreprocessor {

	private final static Logger LOG = LoggerFactory.getLogger(CobolPreprocessorImpl.class);

	protected CobolCommentEntriesMarker createCommentEntriesMarker() {
		return new CobolCommentEntriesMarkerImpl();
	}

	protected CobolDocumentParser createDocumentParser() {
		return new CobolDocumentParserImpl();
	}

	protected CobolInlineCommentEntriesNormalizer createInlineCommentEntriesNormalizer() {
		return new CobolInlineCommentEntriesNormalizerImpl();
	}

	protected CobolLineIndicatorProcessor createLineIndicatorProcessor() {
		return new CobolLineIndicatorProcessorImpl();
	}

	protected CobolLineReader createLineReader() {
		return new CobolLineReaderImpl();
	}

	protected CobolLineWriter createLineWriter() {
		return new CobolLineWriterImpl();
	}

	/**
	 * IBM ILE COBOL allows data items directly under DATA DIVISION without a
	 * WORKING-STORAGE SECTION header. This method injects the missing header
	 * so the ANTLR grammar can parse it.
	 */
	protected String injectImplicitWorkingStorageSection(final String code) {
		final Pattern dataDivPattern = Pattern.compile(
			"DATA\\s+DIVISION\\s*\\.", Pattern.CASE_INSENSITIVE);
		final Pattern sectionPattern = Pattern.compile(
			"^\\s*(?:FILE|WORKING[_-]STORAGE|LINKAGE|LOCAL[_-]STORAGE|COMMUNICATION|REPORT|SCREEN|DATA[_-]BASE|PROGRAM[_-]LIBRARY)\\s+SECTION\\s*\\.",
			Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
		final Pattern levelPattern = Pattern.compile(
			"^\\s*(?:0[1-9]|[1-4][0-9]|66|77|88)\\s",
			Pattern.MULTILINE);

		final Matcher ddm = dataDivPattern.matcher(code);
		if (!ddm.find()) {
			return code;
		}

		final int afterDataDiv = ddm.end();

		// Find the next non-blank, non-comment line after DATA DIVISION.
		final String rest = code.substring(afterDataDiv);
		final String[] restLines = rest.split("\\n", -1);
		int insertOffset = afterDataDiv;
		boolean foundSection = false;
		boolean foundLevel = false;

		for (final String line : restLines) {
			final String trimmed = line.trim();
			// skip blank lines and comment lines
			if (trimmed.isEmpty() || trimmed.startsWith("*>")) {
				insertOffset += line.length() + 1; // +1 for the newline
				continue;
			}
			// check if it's a section header
			if (sectionPattern.matcher(line).find()) {
				foundSection = true;
			}
			// check if it's a level number (data item)
			if (levelPattern.matcher(line).find()) {
				foundLevel = true;
			}
			break;
		}

		if (foundLevel && !foundSection) {
			final String injection = "\n       WORKING-STORAGE SECTION.\n";
			return code.substring(0, insertOffset) + injection + code.substring(insertOffset);
		}

		return code;
	}

	/**
	 * IBM ILE COBOL: a paragraph or section name starting in area A (columns 8-11)
	 * implicitly terminates the previous statement. If the previous non-blank,
	 * non-comment line does not end with a period, we inject one so the parser
	 * sees proper statement termination.
	 */
	protected String injectImplicitPeriodBeforeParagraphs(final String code) {
		final String[] lines = code.split("\n", -1);
		final StringBuilder sb = new StringBuilder();

		// Pattern: line where area A (positions 7-10 in the serialized line,
		// i.e. after 6-char sequence area + 1-char indicator) contains a
		// non-space character — indicating a paragraph/section name in area A.
		// The indicator must be a space (not a comment '*' or '/' or debug 'D').
		// The content must look like a paragraph name: WORD. (at end of line)
		// or WORD. followed by additional statements (e.g., EXIT.) on the same line.
		// Excludes DIVISION/SECTION headers and common non-paragraph keywords.
		final Pattern paragraphPattern = Pattern.compile(
			"^\\s{6} ([A-Za-z0-9][A-Za-z0-9_-]*)\\s*\\.(.*)$");
		final Pattern notParagraphPattern = Pattern.compile(
			"(?i)^(IDENTIFICATION|ENVIRONMENT|DATA|PROCEDURE|WORKING-STORAGE|LINKAGE|FILE|LOCAL-STORAGE|INPUT-OUTPUT|CONFIGURATION|I-O-CONTROL|FD|SD|COPY|EXEC|SELECT|EJECT|SKIP1|SKIP2|SKIP3)$");

		// Pattern: line starting with a COBOL data level number in area A or area B.
		// Matches level numbers 01-49, 66, 77, 88 followed by a space and a data name.
		// This detects data description entries that should be preceded by a period
		// if the previous statement hasn't been terminated.
		final Pattern dataLevelPattern = Pattern.compile(
			"^\\s{6}\\s+(0[1-9]|[1-4][0-9]|66|77|88)\\s+[A-Za-z].*");

		int lastNonBlankNonCommentIdx = -1;
		// Track whether we are in the PROCEDURE DIVISION.
		// Data level entries (01-49, 66, 77, 88) only appear in the DATA DIVISION.
		// In PROCEDURE DIVISION, numbers like 42 are numeric literals (e.g., in
		// IF X = 5 OR 42 conditions), not level numbers.
		boolean inProcedureDivision = false;
		final Pattern procedureDivisionPattern = Pattern.compile(
			"(?i).*PROCEDURE\\s+DIVISION.*");

		for (int i = 0; i < lines.length; i++) {
			final String line = lines[i];

			// Detect PROCEDURE DIVISION header (skip comment lines)
			if (!inProcedureDivision && line.length() > 6
					&& line.charAt(6) != '*' && line.charAt(6) != '/'
					&& procedureDivisionPattern.matcher(line).matches()) {
				inProcedureDivision = true;
			}

			// Check if this line is a paragraph/section name in area A
			if (line.length() >= 8) {
				final Matcher m = paragraphPattern.matcher(line);
				if (m.matches()) {
					final String candidateName = m.group(1);
					// Exclude known non-paragraph keywords (DIVISION/SECTION headers, etc.)
					if (!notParagraphPattern.matcher(candidateName).matches()) {
						// This looks like a paragraph name — check if previous
						// non-blank/non-comment line ended with a period
						if (lastNonBlankNonCommentIdx >= 0) {
							final String prevLine = lines[lastNonBlankNonCommentIdx];
							final String prevTrimmed = prevLine.trim();
							// Strip trailing commas — IBM ILE COBOL treats commas as
							// optional separators with no semantic meaning.
							final String prevTrimmedNoComma = prevTrimmed.replaceAll("[,\\s]+$", "");
							if (!prevTrimmedNoComma.isEmpty() && !prevTrimmedNoComma.endsWith(".")) {
								// Inject a period at the end of the previous line
								lines[lastNonBlankNonCommentIdx] = prevLine + ".";
								LOG.info("Injected implicit period at end of line {} before paragraph '{}'",
									lastNonBlankNonCommentIdx + 1, candidateName);
							}
						}
					}
				}
			}

			// Check if this line starts a new data description entry (level number)
			// and the previous non-blank/non-comment line doesn't end with a period.
			// IBM ILE COBOL allows data entries without explicit periods — the next
			// level number implicitly terminates the previous entry.
			// IMPORTANT: Only do this in the DATA DIVISION. In PROCEDURE DIVISION,
			// numbers like 42 are numeric literals, not level numbers (e.g.,
			// IF CD-PAG = 5 OR 8 OR 38 OR\n  42 OR 48 ... is a multi-line condition).
			if (!inProcedureDivision && line.length() >= 10) {
				final Matcher dm = dataLevelPattern.matcher(line);
				if (dm.matches()) {
					if (lastNonBlankNonCommentIdx >= 0) {
						final String prevLine = lines[lastNonBlankNonCommentIdx];
						final String prevTrimmed = prevLine.trim();
						// Strip trailing commas — in IBM ILE COBOL, commas are optional
						// separators with no semantic meaning. A stray comma after a
						// period-terminated entry (e.g., "PIC X(10).  ,") should not
						// prevent detection of the existing period terminator.
						final String prevTrimmedNoComma = prevTrimmed.replaceAll("[,\\s]+$", "");
						if (!prevTrimmedNoComma.isEmpty() && !prevTrimmedNoComma.endsWith(".")
								&& !prevTrimmed.toUpperCase().startsWith("DATA ")
								&& !prevTrimmed.toUpperCase().startsWith("WORKING-STORAGE ")
								&& !prevTrimmed.toUpperCase().startsWith("LINKAGE ")
								&& !prevTrimmed.toUpperCase().startsWith("FILE ")
								&& !prevTrimmed.toUpperCase().startsWith("LOCAL-STORAGE ")) {
							// Inject a period at the end of the previous line
							lines[lastNonBlankNonCommentIdx] = prevLine + ".";
							LOG.info("Injected implicit period at end of line {} before data level entry (line {})",
								lastNonBlankNonCommentIdx + 1, i + 1);
						}
					}
				}
			}

			// Track last non-blank, non-comment line
			final String trimmed = line.trim();
			if (!trimmed.isEmpty()) {
				// Check indicator area — position 6 in serialized line
				boolean isComment = false;
				if (line.length() > 6) {
					final char indicator = line.charAt(6);
					if (indicator == '*' || indicator == '/' || indicator == 'D' || indicator == 'd') {
						isComment = true;
					}
				}
				if (!isComment) {
					lastNonBlankNonCommentIdx = i;
				}
			}
		}

		for (int i = 0; i < lines.length; i++) {
			if (i > 0) {
				sb.append("\n");
			}
			sb.append(lines[i]);
		}
		return sb.toString();
	}

	/**
	 * IBM ILE COBOL TYPE/TYPEDEF expansion.
	 *
	 * Scans the preprocessed source for typedef definitions (IS TYPEDEF) and
	 * builds a mapping from typedef names to their underlying PIC/USAGE clauses.
	 * Then replaces all TYPE <name> usage references with the expanded clause.
	 *
	 * Supports chained typedefs (e.g., UNISESSION -> DATA-POINTER -> POINTER).
	 */
	protected String expandTypedefReferences(final String code) {
		final String[] lines = code.split("\n", -1);
		// Map from typedef name (upper-case) to its expansion (PIC/USAGE clause text)
		final Map<String, String> typedefs = new HashMap<>();

		// Pattern to match typedef definitions:
		//   01  LONG  IS TYPEDEF  PIC S9(9) COMP-4.
		//   01  DATA-POINTER  IS TYPEDEF  POINTER.
		//   01  PROC-POINTER  IS TYPEDEF  PROCEDURE-POINTER.
		// After preprocessing, the format is normalized (single spaces, etc.)
		final Pattern typedefPattern = Pattern.compile(
			"(?:01|77)\\s+(\\S+)\\s+IS\\s+TYPEDEF\\b(.*)$",
			Pattern.CASE_INSENSITIVE);

		// First pass: collect typedef definitions
		for (final String line : lines) {
			final String trimmed = line.trim();
			if (trimmed.startsWith("*>") || trimmed.isEmpty()) {
				continue;
			}
			final Matcher m = typedefPattern.matcher(trimmed);
			if (m.find()) {
				final String name = m.group(1).toUpperCase();
				String expansion = m.group(2).trim();
				// Remove trailing period
				if (expansion.endsWith(".")) {
					expansion = expansion.substring(0, expansion.length() - 1).trim();
				}
				if (!expansion.isEmpty()) {
					typedefs.put(name, expansion);
					LOG.debug("Collected typedef: {} -> {}", name, expansion);
				}
			}
		}

		if (typedefs.isEmpty()) {
			return code;
		}

		// Resolve chained typedefs: e.g., UNISESSION -> TYPE DATA-POINTER -> POINTER
		// Also resolve single-word expansions like "POINTER" -> "USAGE POINTER"
		final Map<String, String> resolved = new HashMap<>();
		for (final Map.Entry<String, String> entry : typedefs.entrySet()) {
			resolved.put(entry.getKey(), resolveTypedef(entry.getValue(), typedefs, 10));
		}

		LOG.info("Resolved typedefs: {}", resolved);

		// Second pass: replace TYPE <name> usage references (not typedef definitions)
		final StringBuilder sb = new StringBuilder();
		// Pattern for TYPE <name> in data description entries (not in LINKAGE TYPE contexts)
		// Matches: TYPE <word> or TYPE IS <word> at field level
		// Excludes IS TYPEDEF definitions (those should stay as-is for the grammar)
		// Uses lookbehind to ensure TYPE is preceded by whitespace (not part of a name like RC-TYPE)
		final Pattern typeUsagePattern = Pattern.compile(
			"(?<=\\s)TYPE\\s+(?!IS\\b)(?!TYPEDEF\\b)(IS\\s+)?([A-Za-z][A-Za-z0-9_-]*)\\b",
			Pattern.CASE_INSENSITIVE);

		for (int i = 0; i < lines.length; i++) {
			if (i > 0) {
				sb.append("\n");
			}

			final String line = lines[i];
			final String trimmed = line.trim();

			// Skip comments
			if (trimmed.startsWith("*>") || trimmed.isEmpty()) {
				sb.append(line);
				continue;
			}

			// For typedef definition lines that also contain TYPE references
			// (e.g., "01 UNISESSION IS TYPEDEF TYPE DATA-POINTER."),
			// still apply TYPE expansion - the IS TYPEDEF part stays, but
			// TYPE <name> is replaced with the resolved PIC/USAGE.
			// (We don't skip typedef lines anymore.)

			// Replace TYPE <name> references with expanded PIC/USAGE
			final Matcher usageMatcher = typeUsagePattern.matcher(line);
			final StringBuilder lineBuilder = new StringBuilder();
			int lastEnd = 0;

			while (usageMatcher.find()) {
				final String refName = usageMatcher.group(2).toUpperCase();

				// Don't replace if this is in a LINKAGE TYPE context (procedure calls)
				final String before = line.substring(0, usageMatcher.start()).toUpperCase();
				if (before.contains("LINKAGE")) {
					continue;
				}

				// Don't replace TYPE IS with known grammar keywords (SHORT_DATE, etc.)
				if ("SHORT_DATE".equalsIgnoreCase(refName) || "LONG_DATE".equalsIgnoreCase(refName)
						|| "NUMERIC_DATE".equalsIgnoreCase(refName) || "NUMERIC_TIME".equalsIgnoreCase(refName)
						|| "LONG_TIME".equalsIgnoreCase(refName) || "CLOB".equalsIgnoreCase(refName)
						|| "BLOB".equalsIgnoreCase(refName) || "DBCLOB".equalsIgnoreCase(refName)) {
					continue;
				}

				final String expansion = resolved.get(refName);
				if (expansion != null) {
					lineBuilder.append(line, lastEnd, usageMatcher.start());
					lineBuilder.append(expansion);
					lastEnd = usageMatcher.end();
				}
			}

			if (lastEnd > 0) {
				lineBuilder.append(line, lastEnd, line.length());
				sb.append(lineBuilder.toString());
			} else {
				sb.append(line);
			}
		}

		return sb.toString();
	}

	/**
	 * Recursively resolves a typedef expansion through the typedef chain.
	 * For example: TYPE DATA-POINTER -> POINTER -> USAGE POINTER.
	 */
	private String resolveTypedef(final String expansion, final Map<String, String> typedefs, final int maxDepth) {
		if (maxDepth <= 0 || expansion == null || expansion.isEmpty()) {
			return expansion;
		}

		// Check if the expansion is "TYPE <name>" referencing another typedef
		final Pattern typeRef = Pattern.compile(
			"^TYPE\\s+(?:IS\\s+)?([A-Za-z][A-Za-z0-9_-]*)$",
			Pattern.CASE_INSENSITIVE);
		final Matcher m = typeRef.matcher(expansion.trim());
		if (m.matches()) {
			final String refName = m.group(1).toUpperCase();
			final String refExpansion = typedefs.get(refName);
			if (refExpansion != null) {
				return resolveTypedef(refExpansion, typedefs, maxDepth - 1);
			}
		}

		// If it's a single keyword like POINTER or PROCEDURE-POINTER, add USAGE prefix
		final String upper = expansion.trim().toUpperCase();
		if ("POINTER".equals(upper) || "PROCEDURE-POINTER".equals(upper)) {
			// Map to PIC X(16) — pointer types become opaque string handles in Java
			return "PIC X(16)";
		}

		// If it's COMP-1 or COMP-2 without PIC, add PIC
		if ("COMP-1".equals(upper)) {
			return "PIC S9(9) COMP-1";
		}
		if ("COMP-2".equals(upper)) {
			return "PIC S9(18) COMP-2";
		}

		// Otherwise return as-is (it should be a PIC clause already)
		return expansion;
	}

	/**
	 * Strips trailing commas that appear after a period in COBOL data description
	 * entries. In IBM ILE COBOL, commas are optional separators with no semantic
	 * meaning. A stray comma after a period-terminated entry (e.g., "PIC X(10).  ,")
	 * causes the ProLeap parser to see an unexpected COMMACHAR token after the
	 * data entry is already terminated by DOT_FS.
	 */
	protected String stripTrailingCommasAfterPeriod(final String code) {
		// Match lines where a period is followed by optional spaces and a comma
		// at end of line (within the code area). Replace with just the part up
		// to and including the period. Uses MULTILINE so $ matches end of each line.
		return Pattern.compile("(\\.\\s*),\\s*$", Pattern.MULTILINE)
				.matcher(code).replaceAll("$1");
	}

	/**
	 * Whether the IBM i (ILE) source transformations should be applied.
	 *
	 * <p>These rewrite the source text before parsing, to accept constructs the
	 * upstream grammar rejects: PROCESS/CBL directives, optional commas,
	 * implicit WORKING-STORAGE headers, omitted sentence terminators and
	 * TYPEDEF references.
	 *
	 * <p>They are only correct for IBM i source. Applying them to standard
	 * COBOL rewrites code that never needed fixing, so the dialect must be
	 * requested explicitly: {@code params.setDialect(CobolDialect.IBM_ILE)}.
	 * Anything else — including no dialect at all — parses as upstream ProLeap
	 * always did.
	 */
	protected boolean isIbmILE(final CobolParserParams params) {
		return params != null && CobolDialect.IBM_ILE.equals(params.getDialect());
	}

	protected String parseDocument(final List<CobolLine> lines, final CobolParserParams params) {
		final String code = createLineWriter().serialize(lines);

		if (!isIbmILE(params)) {
			return createDocumentParser().processLines(code, params);
		}

		final String commaFixedCode = stripTrailingCommasAfterPeriod(code);
		final String normalizedCode = injectImplicitWorkingStorageSection(commaFixedCode);
		final String periodFixedCode = injectImplicitPeriodBeforeParagraphs(normalizedCode);
		final String result = createDocumentParser().processLines(periodFixedCode, params);
		// Strip PROCESS/CBL directives again after COPY expansion, since
		// a COPY member may itself contain PROCESS lines.
		final String postStripped = stripProcessDirectives(result);
		final String typedefExpanded = expandTypedefReferences(postStripped);
		return typedefExpanded;
	}

	@Override
	public String process(final File cobolFile, final CobolParserParams params) throws IOException {
		final Charset charset = params.getCharset();

		LOG.info("Preprocessing file {} with line format {} and charset {}.", cobolFile.getName(), params.getFormat(),
				charset);

		final String cobolFileContent = Files.readString(cobolFile.toPath(), charset);
		final String result = process(cobolFileContent, params);
		return result;
	}

	/**
	 * Strips PROCESS and CBL compiler directive lines from the raw COBOL source.
	 * These are compiler option directives (e.g., "PROCESS OPTIONS.") that have
	 * no effect on generated code and may contain options not recognized by the
	 * preprocessor grammar, causing parse failures.
	 *
	 * In fixed format, the PROCESS/CBL keyword appears in area A (columns 8-11).
	 * The directive may span continuation lines but typically is a single line
	 * ending with a period.
	 *
	 * <p>The keyword must be followed by whitespace or the sentence-terminating
	 * period. A hyphen means this is a user-defined name, not a directive:
	 * {@code PROCESS-ONE-LINE.} is a paragraph and must be preserved. Using
	 * {@code \b} here would be wrong, because a word boundary also matches
	 * before a hyphen.
	 */
	protected String stripProcessDirectives(final String cobolCode) {
		final String[] lines = cobolCode.split("\\r?\\n", -1);
		final StringBuilder result = new StringBuilder();
		final Pattern processPattern = Pattern.compile(
			"^.{6} (PROCESS|CBL)(\\s.*|\\s*\\.\\s*)$",
			Pattern.CASE_INSENSITIVE);
		boolean inDirective = false;
		boolean stripped = false;

		for (final String line : lines) {
			if (!inDirective) {
				if (processPattern.matcher(line).matches()) {
					inDirective = true;
					stripped = true;
					// Check if this line itself ends the directive (ends with period)
					if (line.trim().endsWith(".")) {
						inDirective = false;
					}
					// Skip this line (don't add to result)
					continue;
				}
				if (result.length() > 0) {
					result.append('\n');
				}
				result.append(line);
			} else {
				// We are inside a multi-line PROCESS directive.
				// Strip continuation lines until we find one ending with a period.
				if (line.trim().endsWith(".")) {
					inDirective = false;
				}
				// Skip this line (part of the directive)
				continue;
			}
		}

		if (stripped) {
			LOG.info("Stripped PROCESS/CBL compiler directive (including continuation lines)");
		}
		return result.toString();
	}

	@Override
	public String process(final String cobolCode, final CobolParserParams params) {
		final String strippedCode = isIbmILE(params) ? stripProcessDirectives(cobolCode) : cobolCode;
		final List<CobolLine> lines = readLines(strippedCode, params);
		final List<CobolLine> rewrittenLines = rewriteLines(lines);
		final String result = parseDocument(rewrittenLines, params);
		return result;
	}

	protected List<CobolLine> readLines(final String cobolCode, final CobolParserParams params) {
		final List<CobolLine> lines = createLineReader().processLines(cobolCode, params);
		return lines;
	}

	/**
	 * Normalizes lines of given COBOL source code, so that comment entries can be
	 * parsed and lines have a unified line format.
	 */
	protected List<CobolLine> rewriteLines(final List<CobolLine> lines) {
		final List<CobolLine> lineIndicatorProcessedLines = createLineIndicatorProcessor().processLines(lines);
		final List<CobolLine> normalizedInlineCommentEntriesLines = createInlineCommentEntriesNormalizer()
				.processLines(lineIndicatorProcessedLines);
		final List<CobolLine> result = createCommentEntriesMarker().processLines(normalizedInlineCommentEntriesLines);
		return result;
	}
}
