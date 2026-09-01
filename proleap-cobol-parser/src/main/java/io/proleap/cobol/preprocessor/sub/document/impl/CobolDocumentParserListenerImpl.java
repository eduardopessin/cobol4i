/*
 * Copyright (C) 2017, Ulrich Wolffgang <ulrich.wolffgang@proleap.io>
 * All rights reserved.
 *
 * This software may be modified and distributed under the terms
 * of the MIT license. See the LICENSE file for details.
 */

package io.proleap.cobol.preprocessor.sub.document.impl;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Scanner;
import java.util.Stack;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.proleap.cobol.CobolPreprocessorBaseListener;
import io.proleap.cobol.CobolPreprocessorParser;
import io.proleap.cobol.CobolPreprocessorParser.CopyDdsSourceContext;
import io.proleap.cobol.CobolPreprocessorParser.CopyDdsStatementContext;
import io.proleap.cobol.CobolPreprocessorParser.CopySourceContext;
import io.proleap.cobol.CobolPreprocessorParser.ReplaceClauseContext;
import io.proleap.cobol.CobolPreprocessorParser.ReplacingPhraseContext;
import io.proleap.cobol.asg.params.CobolParserParams;
import io.proleap.cobol.preprocessor.CobolPreprocessor;
import io.proleap.cobol.preprocessor.exception.CobolPreprocessorException;
import io.proleap.cobol.preprocessor.impl.CobolPreprocessorImpl;
import io.proleap.cobol.preprocessor.sub.CobolLine;
import io.proleap.cobol.preprocessor.sub.copybook.CobolWordCopyBookFinder;
import io.proleap.cobol.preprocessor.sub.copybook.DdsCopyBookGenerator;
import io.proleap.cobol.preprocessor.sub.copybook.FilenameCopyBookFinder;
import io.proleap.cobol.preprocessor.sub.copybook.LiteralCopyBookFinder;
import io.proleap.cobol.preprocessor.sub.copybook.impl.CobolWordCopyBookFinderImpl;
import io.proleap.cobol.preprocessor.sub.copybook.impl.FilenameCopyBookFinderImpl;
import io.proleap.cobol.preprocessor.sub.copybook.impl.LiteralCopyBookFinderImpl;
import io.proleap.cobol.preprocessor.sub.document.CobolDocumentParserListener;
import io.proleap.cobol.preprocessor.sub.util.TokenUtils;

/**
 * ANTLR visitor, which preprocesses a given COBOL program by executing COPY and
 * REPLACE statements.
 */
public class CobolDocumentParserListenerImpl extends CobolPreprocessorBaseListener
		implements CobolDocumentParserListener {

	private final static Logger LOG = LoggerFactory.getLogger(CobolDocumentParserListenerImpl.class);

	private final Stack<CobolDocumentContext> contexts = new Stack<CobolDocumentContext>();

	private final CobolParserParams params;

	private final BufferedTokenStream tokens;

	public CobolDocumentParserListenerImpl(final CobolParserParams params, final BufferedTokenStream tokens) {
		this.params = params;
		this.tokens = tokens;

		contexts.push(new CobolDocumentContext());
	}

	protected String buildLines(final String text, final String linePrefix) {
		final StringBuffer sb = new StringBuffer(text.length());
		final Scanner scanner = new Scanner(text);
		boolean firstLine = true;

		while (scanner.hasNextLine()) {
			if (!firstLine) {
				sb.append(CobolPreprocessor.NEWLINE);
			}

			final String line = scanner.nextLine();
			final String trimmedLine = line.trim();
			final String prefixedLine = linePrefix + CobolPreprocessor.WS + trimmedLine;
			final String suffixedLine = prefixedLine.replaceAll("(?i)(end-exec)",
					"$1 " + CobolPreprocessor.EXEC_END_TAG);

			sb.append(suffixedLine);
			firstLine = false;
		}

		scanner.close();
		return sb.toString();
	}

	@Override
	public CobolDocumentContext context() {
		return contexts.peek();
	}

	protected CobolWordCopyBookFinder createCobolWordCopyBookFinder() {
		return new CobolWordCopyBookFinderImpl();
	}

	protected FilenameCopyBookFinder createFilenameCopyBookFinder() {
		return new FilenameCopyBookFinderImpl();
	}

	protected LiteralCopyBookFinder createLiteralCopyBookFinder() {
		return new LiteralCopyBookFinderImpl();
	}

	@Override
	public void enterCompilerOptions(final CobolPreprocessorParser.CompilerOptionsContext ctx) {
		// push a new context for COMPILER OPTIONS terminals
		push();
	}

	@Override
	public void enterCopyDdsStatement(final CobolPreprocessorParser.CopyDdsStatementContext ctx) {
		// push a new context for COPY DDS terminals
		push();
	}

	@Override
	public void enterCopyStatement(final CobolPreprocessorParser.CopyStatementContext ctx) {
		// push a new context for COPY terminals
		push();
	}

	@Override
	public void enterEjectStatement(final CobolPreprocessorParser.EjectStatementContext ctx) {
		push();
	}

	@Override
	public void enterExecCicsStatement(final CobolPreprocessorParser.ExecCicsStatementContext ctx) {
		// push a new context for SQL terminals
		push();
	}

	@Override
	public void enterExecSqlImsStatement(final CobolPreprocessorParser.ExecSqlImsStatementContext ctx) {
		// push a new context for SQL IMS terminals
		push();
	}

	@Override
	public void enterExecSqlStatement(final CobolPreprocessorParser.ExecSqlStatementContext ctx) {
		// push a new context for SQL terminals
		push();
	}

	@Override
	public void enterReplaceArea(final CobolPreprocessorParser.ReplaceAreaContext ctx) {
		push();
	}

	@Override
	public void enterReplaceByStatement(final CobolPreprocessorParser.ReplaceByStatementContext ctx) {
		push();
	}

	@Override
	public void enterReplaceOffStatement(final CobolPreprocessorParser.ReplaceOffStatementContext ctx) {
		push();
	}

	@Override
	public void enterSkipStatement(final CobolPreprocessorParser.SkipStatementContext ctx) {
		push();
	}

	@Override
	public void enterTitleStatement(final CobolPreprocessorParser.TitleStatementContext ctx) {
		push();
	}

	@Override
	public void exitCompilerOptions(final CobolPreprocessorParser.CompilerOptionsContext ctx) {
		// throw away COMPILER OPTIONS terminals
		pop();
	}

	@Override
	public void exitCopyDdsStatement(final CobolPreprocessorParser.CopyDdsStatementContext ctx) {
		// throw away COPY DDS terminals
		pop();

		// a new context for the generated DDS content
		push();

		final CopyDdsSourceContext ddsSource = ctx.copyDdsSource();
		final String ddsContent = getDdsCopyBookContent(ctx);

		if (ddsContent != null && !ddsContent.isEmpty()) {
			context().write(ddsContent + CobolPreprocessor.NEWLINE);
		}

		final String content = context().read();
		pop();

		context().write(content);
	}

	@Override
	public void exitCopyStatement(final CobolPreprocessorParser.CopyStatementContext ctx) {
		// throw away COPY terminals
		pop();

		// a new context for the copy book content
		push();

		/*
		 * replacement phrase
		 */
		for (final ReplacingPhraseContext replacingPhrase : ctx.replacingPhrase()) {
			context().storeReplaceablesAndReplacements(replacingPhrase.replaceClause());
		}

		/*
		 * copy the copy book
		 */
		final CopySourceContext copySource = ctx.copySource();
		final String copyBookContent = getCopyBookContent(copySource, params);

		if (copyBookContent != null) {
			context().write(copyBookContent + CobolPreprocessor.NEWLINE);
			context().replaceReplaceablesByReplacements(tokens);
		}

		final String content = context().read();
		pop();

		context().write(content);
	}

	@Override
	public void exitEjectStatement(final CobolPreprocessorParser.EjectStatementContext ctx) {
		// throw away eject statement
		pop();
	}

	@Override
	public void exitExecCicsStatement(final CobolPreprocessorParser.ExecCicsStatementContext ctx) {
		// throw away EXEC CICS terminals
		pop();

		// a new context for the CICS statement
		push();

		/*
		 * text
		 */
		final String text = TokenUtils.getTextIncludingHiddenTokens(ctx, tokens);
		final String linePrefix = CobolLine.createBlankSequenceArea(params.getFormat())
				+ CobolPreprocessor.EXEC_CICS_TAG;
		final String lines = buildLines(text, linePrefix);

		context().write(lines);

		final String content = context().read();
		pop();

		context().write(content);
	}

	@Override
	public void exitExecSqlImsStatement(final CobolPreprocessorParser.ExecSqlImsStatementContext ctx) {
		// throw away EXEC SQLIMS terminals
		pop();

		// a new context for the SQLIMS statement
		push();

		/*
		 * text
		 */
		final String text = TokenUtils.getTextIncludingHiddenTokens(ctx, tokens);
		final String linePrefix = CobolLine.createBlankSequenceArea(params.getFormat())
				+ CobolPreprocessor.EXEC_SQLIMS_TAG;
		final String lines = buildLines(text, linePrefix);

		context().write(lines);

		final String content = context().read();
		pop();

		context().write(content);
	}

	@Override
	public void exitExecSqlStatement(final CobolPreprocessorParser.ExecSqlStatementContext ctx) {
		// throw away EXEC SQL terminals
		pop();

		/*
		 * IBM ILE COBOL: EXEC SQL INCLUDE <name> END-EXEC is equivalent to
		 * COPY <name>. It includes COBOL source code (paragraphs, data items),
		 * not just SQL. We must expand it like a COPY statement.
		 */
		final String rawText = TokenUtils.getTextIncludingHiddenTokens(ctx, tokens).trim();
		final java.util.regex.Matcher includeMatcher = java.util.regex.Pattern
				.compile("(?i)EXEC\\s+SQL\\s+INCLUDE\\s+(\\w+)\\s*END-EXEC")
				.matcher(rawText);

		if (includeMatcher.find()) {
			final String includeName = includeMatcher.group(1);
			// Skip SQLCA — it is handled separately by the transformer
			if (!"SQLCA".equalsIgnoreCase(includeName)) {
				final File includeFile = findSqlIncludeFile(includeName, params);
				if (includeFile != null) {
					LOG.info("Expanding EXEC SQL INCLUDE {} from {}", includeName, includeFile);
					push();
					try {
						final String includeContent = new CobolPreprocessorImpl().process(includeFile, params);
						if (includeContent != null) {
							context().write(includeContent + CobolPreprocessor.NEWLINE);
						}
					} catch (final IOException e) {
						LOG.warn("Error expanding EXEC SQL INCLUDE {}: {}", includeName, e.getMessage());
					}
					final String content = context().read();
					pop();
					context().write(content);
					return;
				} else {
					LOG.warn("Could not find file for EXEC SQL INCLUDE {}", includeName);
				}
			}
		}

		// Default: wrap as SQL statement
		push();

		/*
		 * text
		 */
		final String text = TokenUtils.getTextIncludingHiddenTokens(ctx, tokens);
		final String linePrefix = CobolLine.createBlankSequenceArea(params.getFormat())
				+ CobolPreprocessor.EXEC_SQL_TAG;
		final String lines = buildLines(text, linePrefix);

		context().write(lines);

		final String content = context().read();
		pop();

		context().write(content);
	}

	/**
	 * Finds a file for EXEC SQL INCLUDE by searching the copybook directories.
	 * IBM ILE COBOL EXEC SQL INCLUDE works like COPY — it includes COBOL source.
	 */
	protected File findSqlIncludeFile(final String name, final CobolParserParams params) {
		if (params.getCopyBookDirectories() == null) {
			return null;
		}
		final java.util.List<String> extensions = params.getCopyBookExtensions() != null
				? params.getCopyBookExtensions()
				: java.util.Arrays.asList("cbl", "cpy", "");
		for (final File dir : params.getCopyBookDirectories()) {
			if (dir == null || !dir.isDirectory()) {
				continue;
			}
			final File[] files = dir.listFiles();
			if (files == null) {
				continue;
			}
			for (final File candidate : files) {
				for (final String ext : extensions) {
					final String expected = ext == null || ext.isEmpty() ? name : name + "." + ext;
					if (expected.equalsIgnoreCase(candidate.getName())) {
						return candidate;
					}
				}
			}
		}
		return null;
	}

	@Override
	public void exitReplaceArea(final CobolPreprocessorParser.ReplaceAreaContext ctx) {
		/*
		 * replacement phrase
		 */
		final List<ReplaceClauseContext> replaceClauses = ctx.replaceByStatement().replaceClause();
		context().storeReplaceablesAndReplacements(replaceClauses);

		context().replaceReplaceablesByReplacements(tokens);
		final String content = context().read();

		pop();
		context().write(content);
	}

	@Override
	public void exitReplaceByStatement(final CobolPreprocessorParser.ReplaceByStatementContext ctx) {
		// throw away terminals
		pop();
	}

	@Override
	public void exitReplaceOffStatement(final CobolPreprocessorParser.ReplaceOffStatementContext ctx) {
		// throw away REPLACE OFF terminals
		pop();
	}

	@Override
	public void exitSkipStatement(final CobolPreprocessorParser.SkipStatementContext ctx) {
		// throw away skip statement
		pop();
	}

	@Override
	public void exitTitleStatement(final CobolPreprocessorParser.TitleStatementContext ctx) {
		// throw away title statement
		pop();
	}

	protected File findCopyBook(final CopySourceContext copySource, final CobolParserParams params) {
		final File result;

		// Extract library name if present (COPY xxx IN library)
		String libraryName = null;
		if (copySource.copyLibrary() != null) {
			libraryName = copySource.copyLibrary().getText();
		}

		if (copySource.cobolWord() != null) {
			result = new CobolWordCopyBookFinderImpl().findCopyBook(params, copySource.cobolWord(), libraryName);
		} else if (copySource.literal() != null) {
			result = createLiteralCopyBookFinder().findCopyBook(params, copySource.literal());
		} else if (copySource.filename() != null) {
			result = createFilenameCopyBookFinder().findCopyBook(params, copySource.filename());
		} else {
			LOG.warn("unknown copy book reference type {}", copySource);
			result = null;
		}

		return result;
	}

	protected String getDdsCopyBookContent(final CopyDdsStatementContext ctx) {
		final CopyDdsSourceContext ddsSource = ctx.copyDdsSource();
		final String sourceText = ddsSource.getText();

		// Parse prefix (DD-, DDR-, DDS-, DDSR-)
		String prefix;
		if (ddsSource.DD_PREFIX() != null) {
			prefix = "DD";
		} else if (ddsSource.DDR_PREFIX() != null) {
			prefix = "DDR";
		} else if (ddsSource.DDS_PREFIX() != null) {
			prefix = "DDS";
		} else if (ddsSource.DDSR_PREFIX() != null) {
			prefix = "DDSR";
		} else {
			prefix = "DD";
		}

		// Parse format name
		String formatName = null;
		if (ddsSource.ALL_FORMATS() != null) {
			formatName = null; // null means ALL-FORMATS
		} else if (ddsSource.IDENTIFIER() != null) {
			final String idText = ddsSource.IDENTIFIER().getText();
			// Handle ALL-FORMATS-I / ALL-FORMATS-O (output/input variants)
			if ("ALL-FORMATS-I".equalsIgnoreCase(idText) || "ALL-FORMATS-O".equalsIgnoreCase(idText)
					|| "ALL-FORMATS".equalsIgnoreCase(idText)) {
				formatName = null; // treat as ALL-FORMATS
			} else {
				formatName = idText;
			}
		}

		// Parse file name
		final String fileName = ctx.copyDdsFileName() != null ? ctx.copyDdsFileName().getText() : "";

		final File schemaDir = params.getDdsSchemaDirectory();
		if (schemaDir == null) {
			LOG.warn("DDS schema directory not configured, cannot resolve COPY DDS for: {}", sourceText);
			return "";
		}

		final DdsCopyBookGenerator generator = new DdsCopyBookGenerator(schemaDir);
		final String generated = generator.generate(fileName, formatName, prefix);

		if (generated == null || generated.isEmpty()) {
			// Schema file not found - try copybook file fallback, then generate minimal record
			LOG.info("DDS schema not found for {}, trying copybook file fallback", fileName);
			final File copyBookFile = findDdsCopyBookFile(fileName, params);
			if (copyBookFile != null) {
				try {
					return new CobolPreprocessorImpl().process(copyBookFile, params);
				} catch (final java.io.IOException e) {
					LOG.warn("Error reading DDS copybook file {}: {}", copyBookFile, e.getMessage());
				}
			}
			return generateMinimalDdsRecord(fileName, formatName);
		}

		return generated;
	}

	protected File findDdsCopyBookFile(final String fileName, final CobolParserParams params) {
		if (params.getCopyBookDirectories() == null) {
			return null;
		}
		final java.util.List<String> extensions = params.getCopyBookExtensions() != null
				? params.getCopyBookExtensions()
				: java.util.Arrays.asList("cbl", "cpy", "");
		for (final File dir : params.getCopyBookDirectories()) {
			if (dir == null || !dir.isDirectory()) {
				continue;
			}
			for (final File candidate : dir.listFiles()) {
				for (final String ext : extensions) {
					final String expected = ext == null || ext.isEmpty() ? fileName : fileName + "." + ext;
					if (expected.equalsIgnoreCase(candidate.getName())) {
						return candidate;
					}
				}
			}
		}
		return null;
	}

	protected String generateMinimalDdsRecord(final String fileName, final String formatName) {
		// Generate a minimal valid COBOL data description so the program parses
		// The format name becomes the group name at level 05
		final String recName = formatName != null ? formatName : fileName;
		final StringBuilder sb = new StringBuilder();
		sb.append("      05 ").append(recName).append("-RECORD PIC X(1).");
		return sb.toString();
	}

	protected String getCopyBookContent(final CopySourceContext copySource, final CobolParserParams params) {
		// Check if this is a DDS copy (COPY DDSR-ALL-FORMATS OF file / COPY DD-format OF file)
		final String ddsContent = tryResolveDdsCopy(copySource, params);
		if (ddsContent != null) {
			return ddsContent;
		}

		final File copyBook = findCopyBook(copySource, params);
		String result;

		if (copyBook == null) {
			throw new CobolPreprocessorException("Could not find copy book " + copySource.getText()
					+ " in directory of COBOL input file or copy books param object.");
		} else {
			try {
				result = new CobolPreprocessorImpl().process(copyBook, params);
			} catch (final IOException e) {
				result = null;
				LOG.warn(e.getMessage());
			}
		}

		return result;
	}

	/**
	 * Tries to resolve a COPY statement as a DDS copy.
	 * Detects patterns like: COPY DDSR-ALL-FORMATS OF VA000000
	 * The lexer tokenizes "DDSR-ALL-FORMATS" as a single IDENTIFIER,
	 * so we detect it here by inspecting the text.
	 *
	 * @return the generated COBOL content, or null if not a DDS copy
	 */
	protected String tryResolveDdsCopy(final CopySourceContext copySource, final CobolParserParams params) {
		// Get the copybook name (the part before OF/IN)
		String copyName = null;
		if (copySource.cobolWord() != null) {
			copyName = copySource.cobolWord().getText();
		} else if (copySource.literal() != null) {
			copyName = copySource.literal().getText().replace("\"", "").replace("'", "");
		}

		if (copyName == null) {
			return null;
		}

		final String upper = copyName.toUpperCase();

		// Detect DDS copy patterns: DD-xxx, DDR-xxx, DDS-xxx, DDSR-xxx
		String prefix = null;
		String formatPart = null;

		if (upper.startsWith("DDSR-")) {
			prefix = "DDSR";
			formatPart = copyName.substring(5);
		} else if (upper.startsWith("DDS-")) {
			prefix = "DDS";
			formatPart = copyName.substring(4);
		} else if (upper.startsWith("DDR-")) {
			prefix = "DDR";
			formatPart = copyName.substring(4);
		} else if (upper.startsWith("DD-")) {
			prefix = "DD";
			formatPart = copyName.substring(3);
		}

		if (prefix == null) {
			return null;
		}

		// Determine format name
		String formatName = null;
		if ("ALL-FORMATS".equalsIgnoreCase(formatPart)
				|| "ALL-FORMATS-I".equalsIgnoreCase(formatPart)
				|| "ALL-FORMATS-O".equalsIgnoreCase(formatPart)) {
			formatName = null; // null = ALL-FORMATS
		} else {
			formatName = formatPart;
		}

		// Get the file name from the library clause (OF/IN)
		String fileName = null;
		if (copySource.copyLibrary() != null) {
			fileName = copySource.copyLibrary().getText();
		}

		if (fileName == null || fileName.isEmpty()) {
			LOG.warn("DDS copy without file name: COPY {}", copyName);
			return "";
		}

		final File schemaDir = params.getDdsSchemaDirectory();
		if (schemaDir != null) {
			final DdsCopyBookGenerator generator = new DdsCopyBookGenerator(schemaDir);
			final String generated = generator.generate(fileName, formatName, prefix);
			if (generated != null && !generated.isEmpty()) {
				return generated;
			}
			// Schema generator returned empty (format not found in schema) — fall through to copybook fallback
			LOG.info("DDS schema for {} format {} returned empty, trying copybook fallback", fileName, formatName);
		}

		// No schema directory configured or schema incomplete - try to find a copybook file
		// named after the DDS file (e.g., VA000000.cbl in the copybook directories).
		// Only use copybook fallback for ALL-FORMATS requests — specific format requests
		// would get the entire file content which includes unwanted sibling formats.
		if (formatName == null) {
			if (schemaDir == null) {
				LOG.info("DDS copy without schema dir, trying copybook file for: {}", fileName);
			}
			final File copyBookFile = findDdsCopyBookFile(fileName, params);
			if (copyBookFile != null) {
				try {
					return new CobolPreprocessorImpl().process(copyBookFile, params);
				} catch (final java.io.IOException e) {
					LOG.warn("Error reading DDS copybook file {}: {}", copyBookFile, e.getMessage());
				}
			}
		} else {
			LOG.warn("DDS format {} not found in schema for {}, no copybook fallback for specific formats", formatName, fileName);
		}
		return generateMinimalDdsRecord(fileName, formatName);
	}

	/**
	 * Pops the current preprocessing context from the stack.
	 */
	protected CobolDocumentContext pop() {
		return contexts.pop();
	}

	/**
	 * Pushes a new preprocessing context onto the stack.
	 */
	protected CobolDocumentContext push() {
		return contexts.push(new CobolDocumentContext());
	}

	@Override
	public void visitTerminal(final TerminalNode node) {
		final int tokPos = node.getSourceInterval().a;
		context().write(TokenUtils.getHiddenTokensToLeft(tokPos, tokens));

		if (!TokenUtils.isEOF(node)) {
			final String text = node.getText();
			context().write(text);
		}
	}
}
