package io.proleap.cobol.transform.java.rules.lang.procedure.execsql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.ExecSqlStatementContext;
import io.proleap.cobol.asg.metamodel.Program;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry.DataDescriptionEntryType;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.data.datadescription.OccursClause;
import io.proleap.cobol.asg.metamodel.IntegerLiteral;
import io.proleap.cobol.asg.metamodel.valuestmt.IntegerLiteralValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.ValueStmt;
import io.proleap.cobol.asg.metamodel.procedure.execsql.ExecSqlStatement;
import io.proleap.cobol.transform.java.identifier.JavaIdentifierService;
import io.proleap.cobol.transform.java.identifier.variable.JavaVariableIdentifierService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class ExecSqlStatementRule extends CobolTransformRule<ExecSqlStatementContext, ExecSqlStatement> {

	// Allow optional whitespace after the colon — some COBOL sources use "=: FILE500400.CODSOC" (space after colon)
	private static final Pattern HOST_VAR_PATTERN = Pattern.compile(":\\s*([A-Za-z][A-Za-z0-9_-]*(?:\\s*\\.\\s*[A-Za-z][A-Za-z0-9_-]*)*)");

	/**
	 * Stores static cursor declarations (cursorName -> {sql, hostVars}) so that
	 * a standalone OPEN CURSOR can retrieve the SQL and parameters.
	 */
	private final Map<String, CursorDeclaration> declaredCursors = new HashMap<>();

	private static final class CursorDeclaration {
		final String parameterizedSql;
		final List<String> hostVars;
		final List<String> indicatorVars;

		CursorDeclaration(final String parameterizedSql, final List<String> hostVars, final List<String> indicatorVars) {
			this.parameterizedSql = parameterizedSql;
			this.hostVars = hostVars;
			this.indicatorVars = indicatorVars;
		}
	}

	/**
	 * Result of extracting host variables from SQL, separating data vars from indicator vars.
	 * In COBOL embedded SQL, the pattern :DATA_VAR :INDICATOR_VAR means the second var
	 * is a null indicator — it should NOT become a ? parameter in the SQL.
	 */
	private static final class HostVarExtraction {
		final String parameterizedSql;
		final List<String> hostVars;       // data variables only (become ? in SQL)
		final List<String> indicatorVars;  // parallel list: indicator var name or null

		HostVarExtraction(final String parameterizedSql, final List<String> hostVars, final List<String> indicatorVars) {
			this.parameterizedSql = parameterizedSql;
			this.hostVars = hostVars;
			this.indicatorVars = indicatorVars;
		}
	}

	/**
	 * Extracts host variables from SQL text, detecting :DATA :INDICATOR pairs.
	 * Two consecutive :host_vars separated only by whitespace (no comma, no SQL keyword between them)
	 * are treated as a data+indicator pair. Only the data var becomes a ? parameter.
	 * The indicator var is removed from the SQL text entirely.
	 *
	 * @param sql the SQL text with :host_var references
	 * @return extraction result with parameterized SQL, data vars, and indicator vars
	 */
	private HostVarExtraction extractHostVars(final String sql) {
		final List<String> hostVars = new ArrayList<>();
		final List<String> indicatorVars = new ArrayList<>();

		// COBOL comment lines are now filtered at the ASG level (ScopeImpl).
		// No regex stripping here — the old regex matched SELECT * as a comment.
		final String cleanSql = sql;

		// Find all host variable positions
		final Matcher matcher = HOST_VAR_PATTERN.matcher(cleanSql);
		final List<int[]> matches = new ArrayList<>(); // [start, end]
		final List<String> matchNames = new ArrayList<>();
		while (matcher.find()) {
			matches.add(new int[]{matcher.start(), matcher.end()});
			// Normalize: remove whitespace around dots in qualified names (e.g., "TABLE . FIELD" → "TABLE.FIELD")
			matchNames.add(matcher.group(1).replaceAll("\\s*\\.\\s*", "."));
		}

		// Identify which matches are indicators (second of a space-separated pair)
		// An indicator follows a data var with only whitespace between them (no comma, no keyword)
		final java.util.Set<Integer> indicatorIndices = new java.util.HashSet<>();
		for (int i = 0; i < matches.size() - 1; i++) {
			if (indicatorIndices.contains(i)) {
				continue; // this one is already an indicator, skip
			}
			final int endOfFirst = matches.get(i)[1];
			final int startOfSecond = matches.get(i + 1)[0];
			final String between = cleanSql.substring(endOfFirst, startOfSecond);
			// If only whitespace between two :vars (no comma, no SQL keyword), second is indicator
			if (between.matches("\\s+")) {
				indicatorIndices.add(i + 1);
			}
		}

		// Build parameterized SQL: replace data vars with ?, remove indicator vars entirely
		final StringBuilder result = new StringBuilder();
		int lastPos = 0;
		for (int i = 0; i < matches.size(); i++) {
			final int start = matches.get(i)[0];
			final int end = matches.get(i)[1];
			if (indicatorIndices.contains(i)) {
				// Indicator var: remove it (and leading whitespace) from the SQL
				// Find the whitespace before this indicator to remove it too
				int wsStart = start;
				while (wsStart > lastPos && Character.isWhitespace(cleanSql.charAt(wsStart - 1))) {
					wsStart--;
				}
				result.append(cleanSql, lastPos, wsStart);
				lastPos = end;
				// Record indicator association with previous data var
				// (already added when processing the data var)
			} else {
				// Data var: replace with ?
				result.append(cleanSql, lastPos, start);
				result.append("?");
				lastPos = end;
				hostVars.add(matchNames.get(i));
				// Check if next match is its indicator
				if (indicatorIndices.contains(i + 1)) {
					indicatorVars.add(matchNames.get(i + 1));
				} else {
					indicatorVars.add(null);
				}
			}
		}
		result.append(cleanSql, lastPos, cleanSql.length());

		// Safety cleanup: if any qualified host variable was only partially replaced
		// (e.g., :TABLE.FIELD → ?.FIELD instead of ?), remove the leftover ".FIELD" suffix
		String parameterizedSql = result.toString().replaceAll("\\?\\s*\\.\\s*\\w+", "?");

		return new HostVarExtraction(parameterizedSql, hostVars, indicatorVars);
	}

	@Inject
	private JavaIdentifierService javaIdentifierService;

	@Inject
	private io.proleap.cobol.transform.java.identifier.variable.JavaFileDescriptionEntryIdentifierService javaFileDescriptionEntryIdentifierService;

	@Inject
	private JavaVariableIdentifierService javaVariableIdentifierService;

	@Override
	public void apply(final ExecSqlStatementContext ctx, final ExecSqlStatement execSqlStatement,
			final RuleContext rc) {
		final String execSqlText = execSqlStatement.getExecSqlText();

		if (execSqlText == null || execSqlText.trim().isEmpty()) {
			return;
		}


		// Strip EXEC SQL ... END-EXEC envelope before processing
		String trimmedSql = execSqlText.trim();
		trimmedSql = trimmedSql.replaceAll("(?i)^EXEC\\s+SQL\\s+", "");
		trimmedSql = trimmedSql.replaceAll("(?i)\\s*END-EXEC\\s*$", "");
		// Note: COBOL comment lines inside EXEC SQL are now filtered at the ASG level
		// (ScopeImpl.addExecSqlStatement), so no regex stripping needed here.
		// The old regex (?m)^\\s*\\*.*$ was buggy: it matched SELECT * as a comment.
		trimmedSql = trimmedSql.trim();

		// Detect merged PREPARE...DECLARE...OPEN chain for dynamic SQL
		// Pattern: PREPARE name FROM :hostvar END-EXEC EXEC SQL DECLARE cursor CURSOR [SCROLL] FOR name END-EXEC EXEC SQL OPEN cursor
		final java.util.regex.Matcher dynamicChainMatcher = Pattern.compile(
				"(?i)PREPARE\\s+(\\w+)\\s+FROM\\s+:(\\S+)\\s+END-EXEC\\s+EXEC\\s+SQL\\s+" +
				"DECLARE\\s+(\\w+)\\s+(?:SCROLL\\s+)?CURSOR\\s+(?:WITH\\s+HOLD\\s+)?FOR\\s+\\1\\s+END-EXEC\\s+EXEC\\s+SQL\\s+" +
				"OPEN\\s+\\3")
				.matcher(trimmedSql);
		if (dynamicChainMatcher.find()) {
			final String cursorName = dynamicChainMatcher.group(3).toLowerCase();
			final String hostVar = resolveHostVar(dynamicChainMatcher.group(2), rc);
			rc.p("// PREPARE %s FROM :%s + DECLARE %s CURSOR + OPEN %s (dynamic SQL)",
					dynamicChainMatcher.group(1), dynamicChainMatcher.group(2),
					cursorName, cursorName);
			rc.pNl();
			rc.p("sqlService.openCursor(\"%s\", %s.toString().trim());", cursorName, hostVar);
			rc.pNl();
			rc.p("sqlcode = BigDecimal.valueOf(sqlService.getSqlCode()); sqlstate = sqlService.getSqlState();");
			rc.pNl(execSqlStatement);
			return;
		}

		// Detect standalone PREPARE name FROM :hostvar (not merged with DECLARE/OPEN)
		final java.util.regex.Matcher prepareMatcher = Pattern.compile(
				"(?i)^PREPARE\\s+(\\w+)\\s+FROM\\s+:(\\S+)\\s*$")
				.matcher(trimmedSql);
		if (prepareMatcher.find()) {
			final String stmtName = prepareMatcher.group(1).toLowerCase();
			final String hostVar = resolveHostVar(prepareMatcher.group(2), rc);
			rc.p("// PREPARE %s FROM :%s (dynamic SQL - statement stored for later DECLARE/OPEN)",
					prepareMatcher.group(1), prepareMatcher.group(2));
			rc.pNl();
			rc.p("sqlService.prepareNamedStatement(\"%s\", %s.toString().trim());", stmtName, hostVar);
			rc.pNl();
			rc.p("sqlcode = BigDecimal.valueOf(sqlService.getSqlCode()); sqlstate = sqlService.getSqlState();");
			rc.pNl(execSqlStatement);
			return;
		}

		final String upperSql = trimmedSql.toUpperCase();

		if (upperSql.startsWith("INCLUDE") || upperSql.startsWith("WHENEVER")) {
			rc.p("// SQL directive: %s", trimmedSql);
			rc.pNl(execSqlStatement);
			return;
		}

		if (upperSql.startsWith("BEGIN DECLARE") || upperSql.startsWith("END DECLARE")) {
			rc.p("// %s", trimmedSql);
			rc.pNl(execSqlStatement);
			return;
		}

		// GET DIAGNOSTICS :hostvar = ROW_COUNT → use JDBC getUpdateCount()
		if (upperSql.startsWith("GET DIAGNOSTICS")) {
			final java.util.regex.Matcher diagMatcher = Pattern
					.compile("(?i)GET\\s+DIAGNOSTICS\\s+:\\s*(\\S+)\\s*=\\s*ROW_COUNT")
					.matcher(trimmedSql);
			if (diagMatcher.find()) {
				final String hostVar = resolveHostVar(diagMatcher.group(1), rc);
				rc.p("%s = BigDecimal.valueOf(sqlService.getUpdateCount());", hostVar);
				rc.pNl(execSqlStatement);
			} else {
				rc.p("// Unsupported GET DIAGNOSTICS: %s", trimmedSql);
				rc.pNl(execSqlStatement);
			}
			return;
		}

		// EXECUTE prepared-name [USING :hostvars] → execute the named prepared statement
		if (upperSql.startsWith("EXECUTE") && !upperSql.startsWith("EXECUTE IMMEDIATE")) {
			final java.util.regex.Matcher execMatcher = Pattern
					.compile("(?i)^EXECUTE\\s+(\\w+)(?:\\s+USING\\s+(.+))?$")
					.matcher(trimmedSql);
			if (execMatcher.find()) {
				final String stmtName = execMatcher.group(1).toLowerCase();
				rc.p("{ String _dynSql = sqlService.getNamedStatement(\"%s\");", stmtName);
				rc.pNl();
				rc.p("if (_dynSql != null) {");
				rc.pNl();
				rc.getPrinter().indent();
				rc.p("java.sql.PreparedStatement ps = sqlService.prepareStatement(_dynSql);");
				rc.pNl();
				rc.p("try {");
				rc.pNl();
				rc.getPrinter().indent();
				rc.p("int _rowsAffected = ps.executeUpdate();");
				rc.pNl();
				rc.p("((io.proleap.cobol.runtime.impl.SqlServiceImpl) sqlService).setLastUpdateCount(_rowsAffected);");
				rc.pNl();
				rc.p("if (_rowsAffected == 0) { sqlService.setSqlCode(100); }");
				rc.pNl();
				rc.p("sqlcode = BigDecimal.valueOf(sqlService.getSqlCode()); sqlstate = sqlService.getSqlState();");
				rc.pNl();
				rc.getPrinter().unindent();
				rc.p("} catch (java.sql.SQLException sqlex) {");
				rc.pNl();
				rc.getPrinter().indent();
				rc.p("sqlcode = BigDecimal.valueOf(sqlex.getErrorCode() != 0 ? sqlex.getErrorCode() : -1);");
				rc.pNl();
				rc.p("sqlstate = sqlex.getSQLState() != null ? sqlex.getSQLState() : \"58004\";");
				rc.pNl();
				rc.p("sqlerrmc = sqlex.getMessage();");
				rc.pNl();
				rc.getPrinter().unindent();
				rc.p("}");
				rc.pNl();
				rc.getPrinter().unindent();
				rc.p("} }");
				rc.pNl(execSqlStatement);
				return;
			}
		}

		// DECLARE CURSOR → store cursor SQL and generate openCursor call if OPEN is merged
		if (upperSql.startsWith("DECLARE")) {
			final java.util.regex.Matcher cursorMatcher = Pattern
					.compile("(?i)DECLARE\\s+(\\w+)\\s+(?:SCROLL\\s+)?CURSOR\\s+(?:WITH\\s+HOLD\\s+)?FOR\\s+(.+)")
					.matcher(trimmedSql);
			if (cursorMatcher.find()) {
				final String cursorName = cursorMatcher.group(1).toLowerCase();
				String cursorSql = cursorMatcher.group(2).trim();

				// Check if OPEN (and optionally FETCH) was merged into the same EXEC SQL block
				// Pattern: ... END-EXEC EXEC SQL OPEN <cursor> [END-EXEC EXEC SQL FETCH ...]
				String mergedOpenCursor = null;
				String mergedFetchSql = null;
				// First try the three-way merge: DECLARE + OPEN + FETCH
				final java.util.regex.Matcher mergedOpenFetchMatcher = Pattern
						.compile("(?i)(.+?)\\s+END-EXEC\\s+EXEC\\s+SQL\\s+OPEN\\s+(\\w+)\\s+END-EXEC\\s+EXEC\\s+SQL\\s+(FETCH\\s+.+)$")
						.matcher(cursorSql);
				if (mergedOpenFetchMatcher.find()) {
					cursorSql = mergedOpenFetchMatcher.group(1).trim();
					mergedOpenCursor = mergedOpenFetchMatcher.group(2).toLowerCase();
					mergedFetchSql = mergedOpenFetchMatcher.group(3).trim();
				} else {
					// Fall back to two-way merge: DECLARE + OPEN only
					final java.util.regex.Matcher mergedOpenMatcher = Pattern
							.compile("(?i)(.+?)\\s+END-EXEC\\s+EXEC\\s+SQL\\s+OPEN\\s+(\\w+)\\s*$")
							.matcher(cursorSql);
					if (mergedOpenMatcher.find()) {
						cursorSql = mergedOpenMatcher.group(1).trim();
						mergedOpenCursor = mergedOpenMatcher.group(2).toLowerCase();
					}
				}

				// Check if FOR clause references a named prepared statement (not inline SQL)
				// e.g., DECLARE C1 CURSOR FOR S1 (where S1 is a PREPARE name)
				final boolean isDynamicRef = cursorSql.matches("(?i)^\\w+$");
				if (isDynamicRef) {
					// Dynamic cursor: references a previously PREPAREd statement
					rc.p("// DECLARE %s CURSOR FOR %s (dynamic - bound to prepared statement)",
							cursorName, cursorSql);
					rc.pNl();
					rc.p("sqlService.declareCursorForPrepared(\"%s\", \"%s\");",
							cursorName, cursorSql.toLowerCase());
					rc.pNl();

					// If OPEN was merged, generate the openCursor call
					if (mergedOpenCursor != null) {
						rc.p("sqlService.openCursor(\"%s\");", mergedOpenCursor);
						rc.pNl();
						rc.p("sqlcode = BigDecimal.valueOf(sqlService.getSqlCode()); sqlstate = sqlService.getSqlState();");
						rc.pNl();
					}

					// If FETCH was merged, generate the fetch code
					if (mergedFetchSql != null) {
						generateMergedFetch(mergedFetchSql, rc);
					}

					rc.pNl(execSqlStatement);
					return;
				}

				// Extract host variables from the cursor SQL and build parameterized version
				// Uses indicator-aware extraction to avoid extra ? for :data :indicator pairs
				final HostVarExtraction cursorExtraction = extractHostVars(cursorSql);
				final List<String> cursorHostVars = cursorExtraction.hostVars;
				final List<String> cursorIndicatorVars = cursorExtraction.indicatorVars;
				final String parameterizedCursorSql = cursorExtraction.parameterizedSql;

				// DB2/400: wrap untyped parameter markers inside TRIM with CAST (SQL0418 fix)
				String fixedCursorSql = parameterizedCursorSql.replaceAll(
						"(?i)TRIM\\s*\\(\\s*\\?\\s*\\)", "TRIM(CAST(? AS VARCHAR(20000)))");
				// Quote SQL reserved words used as column names
				fixedCursorSql = quoteReservedColumnNames(fixedCursorSql);

				rc.p("// Cursor %s declared: %s", cursorName, escapeSql(cursorSql));
				rc.pNl();

				// Store cursor declaration for later standalone OPEN
				declaredCursors.put(cursorName, new CursorDeclaration(fixedCursorSql, cursorHostVars, cursorIndicatorVars));

				// If OPEN was merged, generate the openCursor call
				if (mergedOpenCursor != null) {
					generateOpenCursor(mergedOpenCursor, fixedCursorSql, cursorHostVars, rc);
				}

				// If FETCH was merged, generate the fetch code
				if (mergedFetchSql != null) {
					rc.pNl();
					generateMergedFetch(mergedFetchSql, rc);
				}

				rc.pNl(execSqlStatement);
				return;
			}
		}

		// OPEN CURSOR → sqlService.openCursor with cursor's SQL
		if (upperSql.startsWith("OPEN")) {
			final java.util.regex.Matcher openMatcher = Pattern.compile("(?i)OPEN\\s+(\\w+)").matcher(trimmedSql);
			if (openMatcher.find()) {
				final String cursorName = openMatcher.group(1).toLowerCase();
				final CursorDeclaration decl = declaredCursors.get(cursorName);
				if (decl != null) {
					// Static cursor: use stored SQL and host variables from DECLARE
					generateOpenCursor(cursorName, decl.parameterizedSql, decl.hostVars, rc);
				} else {
					// Dynamic cursor (bound via declareCursorForPrepared) or unknown
					rc.p("sqlService.openCursor(\"%s\");", cursorName);
					rc.pNl();
					rc.p("sqlcode = BigDecimal.valueOf(sqlService.getSqlCode()); sqlstate = sqlService.getSqlState();");
				}
				rc.pNl(execSqlStatement);
				return;
			}
		}

		// FETCH CURSOR → sqlService.fetchCursor
		if (upperSql.startsWith("FETCH")) {
			// FETCH ... FOR N ROWS INTO :array :indicator — multi-row fetch into OCCURS arrays
			final java.util.regex.Matcher forRowsMatcher = Pattern
					.compile("(?i)FETCH\\s+(?:(?:NEXT|PRIOR|FIRST|LAST)\\s+)?(?:RELATIVE\\s+(?::\\w+(?:-\\w+)*|\\d+)\\s+)?(?:FROM\\s+)?(\\w+)\\s+FOR\\s+(\\d+|:\\w+(?:-\\w+)*)\\s+ROWS?\\s+INTO\\s+(.+)")
					.matcher(trimmedSql);
			if (forRowsMatcher.find()) {
				final String cursorName = forRowsMatcher.group(1).toLowerCase();
				final String rowCountRaw = forRowsMatcher.group(2);
				final boolean rowCountIsVar = rowCountRaw.startsWith(":");
				final int rowCountLiteral = rowCountIsVar ? 0 : Integer.parseInt(rowCountRaw);
				final String rowCountExpr = rowCountIsVar ? resolveHostVar(rowCountRaw.substring(1), rc) : String.valueOf(rowCountLiteral);
				final String intoVars = forRowsMatcher.group(3).trim();
				// Extract relative position var if present
				final java.util.regex.Matcher relMatcher = Pattern
						.compile("(?i)FETCH\\s+RELATIVE\\s+(?::(\\w+(?:-\\w+)*)|(\\d+))").matcher(trimmedSql);
				final String relativeVar;
				final String relativeLiteral;
				if (relMatcher.find()) {
					relativeVar = relMatcher.group(1) != null ? resolveHostVar(relMatcher.group(1), rc) : null;
					relativeLiteral = relMatcher.group(2);
				} else {
					relativeVar = null;
					relativeLiteral = null;
				}

				// Extract host variables and indicator variables
				final List<String> fetchVars = new ArrayList<>();
				final List<String> fetchVarCobolNames = new ArrayList<>();
				final List<String> fetchIndicators = new ArrayList<>();
				final String[] chunks = intoVars.split(",");
				for (final String chunk : chunks) {
					final Matcher chunkMatcher = HOST_VAR_PATTERN.matcher(chunk.trim());
					final List<String> chunkVars = new ArrayList<>();
					while (chunkMatcher.find()) {
						chunkVars.add(chunkMatcher.group(1));
					}
					if (chunkVars.size() >= 1) {
						fetchVars.add(resolveHostVar(chunkVars.get(0), rc));
						fetchVarCobolNames.add(chunkVars.get(0));
					}
					if (chunkVars.size() >= 2) {
						fetchIndicators.add(resolveHostVar(chunkVars.get(1), rc));
					} else {
						fetchIndicators.add(null);
					}
				}

				// Generate multi-row fetch loop
				rc.p("{");
				rc.pNl();
				rc.getPrinter().indent();
				rc.p("java.sql.ResultSet rs_%s = sqlService.fetchCursor(\"%s\");", cursorName, cursorName);
				rc.pNl();
				rc.p("int fetchRowCount_%s = 0;", cursorName);
				rc.pNl();
				rc.p("if (rs_%s != null) {", cursorName);
				rc.pNl();
				rc.getPrinter().indent();
				// FETCH RELATIVE: advance cursor N-1 rows before iterating (IBM ILE COBOL Language Reference, FETCH statement)
				// Skip rows via rs.next() rather than rs.absolute() because the jt400 driver does not
				// reliably honor absolute() on SCROLL_INSENSITIVE cursors bound to a named cursor
				// (setCursorName). Forward skipping via next() works on both FORWARD_ONLY and SCROLL
				// cursors and matches DB2 AS/400 FETCH RELATIVE semantics from a before-first position.
				if (relativeVar != null) {
					rc.p("{ int _relPos = %s.intValue(); for (int _skip = 1; _skip < _relPos && rs_%s.next(); _skip++) { /* skip */ } }",
							relativeVar, cursorName);
					rc.pNl();
				} else if (relativeLiteral != null) {
					final int litVal = Integer.parseInt(relativeLiteral);
					if (litVal > 1) {
						rc.p("for (int _skip = 1; _skip < %d && rs_%s.next(); _skip++) { /* skip */ }", litVal, cursorName);
						rc.pNl();
					}
				}
				final String multiRowFetchMethod = fetchOrientationMethod(trimmedSql);
				rc.p("for (int fetchIdx_%s = 0; fetchIdx_%s < %s && rs_%s.%s(); fetchIdx_%s++) {",
						cursorName, cursorName, rowCountIsVar ? rowCountExpr + ".intValue()" : String.valueOf(rowCountLiteral), cursorName, multiRowFetchMethod, cursorName);
				rc.pNl();
				rc.getPrinter().indent();
				rc.p("fetchRowCount_%s++;", cursorName);
				rc.pNl();
				int colIndex = 1;
				// Track overall column offset (0-based) for indicator array indexing
				int indicatorColOffset = 0;
				// Limit rs.get() calls to the number of SELECT columns to avoid
				// Column Index out of range when the INTO structure has more fields than the SELECT list
				final int selectColLimit = countSelectColumns(cursorName);

				// Pre-resolve indicator metadata once per data variable group
				// so we can emit indicator assignments inline with each column read
				final List<String> indRowPaths = new ArrayList<>();
				final List<String> indChildNames = new ArrayList<>();
				final List<Boolean> indChildIsArrayFlags = new ArrayList<>();
				final List<Integer> indChildOccursSizes = new ArrayList<>();
				for (int i = 0; i < fetchVars.size(); i++) {
					if (i < fetchIndicators.size() && fetchIndicators.get(i) != null) {
						final String indicatorPath = fetchIndicators.get(i);
						final DataDescriptionEntry indEntry = findIndicatorEntry(indicatorPath, rc);
						String indChildName = null;
						boolean indChildIsArray = false;
						int indChildOccurs = -1;
						if (indEntry instanceof DataDescriptionEntryGroup) {
							for (final DataDescriptionEntry indChild : ((DataDescriptionEntryGroup) indEntry).getDataDescriptionEntries()) {
								if (indChild.getDataDescriptionEntryType() == DataDescriptionEntryType.CONDITION) continue;
								if (indChild instanceof DataDescriptionEntryGroup) {
									final DataDescriptionEntryGroup indChildGrp = (DataDescriptionEntryGroup) indChild;
									if (indChildGrp.getOccursClauses() != null && !indChildGrp.getOccursClauses().isEmpty()) {
										indChildName = javaVariableIdentifierService.mapToIdentifier(indChild);
										indChildIsArray = indChildGrp.getDataDescriptionEntries().isEmpty()
												|| indChildGrp.getDataDescriptionEntries().stream()
														.allMatch(e -> e.getDataDescriptionEntryType() == DataDescriptionEntryType.CONDITION);
										indChildOccurs = getOccursSize(indChildGrp);
										break;
									}
								}
								if (indChildName == null) {
									indChildName = javaVariableIdentifierService.mapToIdentifier(indChild);
									indChildIsArray = true;
									// For non-OCCURS children treated as arrays, try to get size from parent
									if (indChild instanceof DataDescriptionEntryGroup) {
										indChildOccurs = getOccursSize((DataDescriptionEntryGroup) indChild);
									}
								}
							}
						}
						final String indRowPath = (indEntry != null)
								? buildFetchElementPath(indEntry, cursorName)
								: indicatorPath + ".get(fetchIdx_" + cursorName + ")";
						indRowPaths.add(indRowPath);
						indChildNames.add(indChildName);
						indChildIsArrayFlags.add(indChildIsArray);
						indChildOccursSizes.add(indChildOccurs);
					} else {
						indRowPaths.add(null);
						indChildNames.add(null);
						indChildIsArrayFlags.add(false);
						indChildOccursSizes.add(-1);
					}
				}

				for (int i = 0; i < fetchVars.size(); i++) {
					if (selectColLimit > 0 && colIndex > selectColLimit) break;
					final String cobolVarName = (i < fetchVarCobolNames.size()) ? fetchVarCobolNames.get(i) : null;
					final String leafName = cobolVarName != null && cobolVarName.contains(".")
							? cobolVarName.substring(cobolVarName.lastIndexOf('.') + 1) : cobolVarName;
					if (leafName == null) continue;

					// Find the DataDescriptionEntry for this INTO variable
					// Use qualified name (e.g., BASE000301.LIBELLE) when available to avoid
					// matching a same-named field in a different copybook
					final DataDescriptionEntry intoEntry = findEntryForVar(
							cobolVarName != null ? cobolVarName : leafName, rc);

					// Expand leaf fields from this entry or its parent,
					// treating VARCHAR sub-groups as single DB columns
					List<FetchColumnMapping> columnMappings = new ArrayList<>();
					if (intoEntry instanceof DataDescriptionEntryGroup) {
						if (isVarcharGroup(intoEntry)) {
							// The INTO variable itself is a VARCHAR group (e.g., :BASE000301.ALIASEXTID)
							// Treat as a single DB column mapping to LENGTH + DATA sub-fields
							final List<DataDescriptionEntry> vcChildren = new ArrayList<>();
							for (final DataDescriptionEntry vcChild : ((DataDescriptionEntryGroup) intoEntry).getDataDescriptionEntries()) {
								if (vcChild.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION) {
									vcChildren.add(vcChild);
								}
							}
							final String lengthPath = buildFetchElementPath(vcChildren.get(0), cursorName);
							final String dataPath = buildFetchElementPath(vcChildren.get(1), cursorName);
							columnMappings.add(new FetchColumnMapping(lengthPath, dataPath));
						} else {
							collectFetchColumnMappings((DataDescriptionEntryGroup) intoEntry, columnMappings, cursorName);
							// If no children (e.g., DDS COPY expanded to parent level), try parent
							if (columnMappings.isEmpty() && intoEntry.getParentDataDescriptionEntryGroup() != null) {
								collectFetchColumnMappings(intoEntry.getParentDataDescriptionEntryGroup(), columnMappings, cursorName);
							}
						}
					}

					// Retrieve pre-resolved indicator metadata for this data variable
					final boolean hasIndicator = i < indRowPaths.size() && indRowPaths.get(i) != null;
					final String indRowPath = hasIndicator ? indRowPaths.get(i) : null;
					final String indChildName = hasIndicator ? indChildNames.get(i) : null;
					final boolean indChildIsArray = hasIndicator ? indChildIsArrayFlags.get(i) : false;
					final int indOccursSize = hasIndicator ? indChildOccursSizes.get(i) : -1;

					if (!columnMappings.isEmpty()) {
						for (final FetchColumnMapping mapping : columnMappings) {
							if (selectColLimit > 0 && colIndex > selectColLimit) break;
							if (mapping.isVarchar) {
								// Pad _vc to the DATA field's PIC X width (see SELECT INTO varchar fix)
								rc.p("{ String _vc = CobolResultSetHelper.safeGetString(rs_%s, %d); if (_vc != null) { %s = CobolMove.moveAlphanumericToAlphanumeric(_vc, %s.length()); %s = new BigDecimal(_vc.length()); } }",
										cursorName, colIndex, mapping.dataPath, mapping.dataPath, mapping.lengthPath);
							} else {
								if (mapping.isNumeric) {
									rc.p("%s = CobolResultSetHelper.safeBigDecimal(rs_%s, %d);", mapping.fetchPath, cursorName, colIndex);
								} else {
									// Pad/truncate to PIC X width to preserve flat byte layout
									rc.p("{ String _s = CobolResultSetHelper.safeGetString(rs_%s, %d); if (_s != null) %s = CobolMove.moveAlphanumericToAlphanumeric(_s, %s.length()); }",
											cursorName, colIndex, mapping.fetchPath, mapping.fetchPath);
								}
							}
							rc.pNl();
							// Set wasNull indicator immediately after reading each column
							if (hasIndicator) {
								emitIndicatorAssignment(rc, indRowPath, indChildName, indChildIsArray, indicatorColOffset, cursorName, indOccursSize);
								rc.pNl();
							}
							colIndex++;
							indicatorColOffset++;
						}
					} else {
						// Scalar variable in an OCCURS array
						final String fetchPath = (intoEntry != null) ? buildFetchElementPath(intoEntry, cursorName) : fetchVars.get(i);
						if (intoEntry != null && isNumericEntry(intoEntry)) {
							rc.p("%s = CobolResultSetHelper.safeBigDecimal(rs_%s, %d);", fetchPath, cursorName, colIndex);
						} else {
							// Pad/truncate to PIC X width to preserve flat byte layout
							rc.p("{ String _s = CobolResultSetHelper.safeGetString(rs_%s, %d); if (_s != null) %s = CobolMove.moveAlphanumericToAlphanumeric(_s, %s.length()); }",
									cursorName, colIndex, fetchPath, fetchPath);
						}
						rc.pNl();
						// Set wasNull indicator immediately after reading each column
						if (hasIndicator) {
							emitIndicatorAssignment(rc, indRowPath, indChildName, indChildIsArray, indicatorColOffset, cursorName, indOccursSize);
							rc.pNl();
						}
						colIndex++;
						indicatorColOffset++;
					}
				}
				rc.getPrinter().unindent();
				rc.p("}");
				rc.pNl();
				rc.getPrinter().unindent();
				rc.p("}");
				rc.pNl();
				rc.p("sqlerrd[2] = new BigDecimal(fetchRowCount_%s);", cursorName);
				rc.pNl();
				rc.p("if (fetchRowCount_%s < %s) { sqlerrd[4] = BigDecimal.valueOf(100); }",
						cursorName, rowCountIsVar ? rowCountExpr + ".intValue()" : String.valueOf(rowCountLiteral));
				rc.pNl();
				rc.p("sqlcode = BigDecimal.valueOf(fetchRowCount_%s > 0 ? 0 : 100);", cursorName);
				rc.pNl();
				rc.p("sqlstate = fetchRowCount_%s > 0 ? \"00000\" : \"02000\";", cursorName);
				rc.pNl();
				rc.getPrinter().unindent();
				rc.p("}");
				rc.pNl(execSqlStatement);
				return;
			}

			final java.util.regex.Matcher fetchMatcher = Pattern
					.compile("(?i)FETCH\\s+(?:(?:NEXT|PRIOR|FIRST|LAST)\\s+)?(?:FROM\\s+)?(\\w+)\\s+INTO\\s+(.+)").matcher(trimmedSql);
			if (fetchMatcher.find()) {
				final String cursorName = fetchMatcher.group(1).toLowerCase();
				final String intoVars = fetchMatcher.group(2).trim();
				// Extract host variables and optional indicator variables from INTO clause
				// Pattern: :hostvar :indicator, :hostvar2 :indicator2, ...
				// Indicators follow host vars separated by space (not comma)
				final List<String> fetchVars = new ArrayList<>();
				final List<String> fetchVarRawNames = new ArrayList<>();
				final List<String> fetchIndicators = new ArrayList<>();
				final List<String> fetchIndicatorRawNames = new ArrayList<>();
				final Matcher fetchHostMatcher = HOST_VAR_PATTERN.matcher(intoVars);
				final List<String> allVars = new ArrayList<>();
				while (fetchHostMatcher.find()) {
					allVars.add(fetchHostMatcher.group(1));
				}
				// Pair host vars and indicators: vars separated by comma are host vars,
				// vars following a host var without comma are indicators
				// Simple heuristic: split by comma first, each chunk has 1 or 2 vars
				final String[] chunks = intoVars.split(",");
				for (final String chunk : chunks) {
					final Matcher chunkMatcher = HOST_VAR_PATTERN.matcher(chunk.trim());
					final List<String> chunkVars = new ArrayList<>();
					while (chunkMatcher.find()) {
						chunkVars.add(chunkMatcher.group(1));
					}
					if (chunkVars.size() >= 1) {
						fetchVars.add(resolveHostVar(chunkVars.get(0), rc));
						fetchVarRawNames.add(chunkVars.get(0));
					}
					if (chunkVars.size() >= 2) {
						fetchIndicators.add(resolveHostVar(chunkVars.get(1), rc));
						fetchIndicatorRawNames.add(chunkVars.get(1));
					} else {
						fetchIndicators.add(null);
						fetchIndicatorRawNames.add(null);
					}
				}
				rc.p("{");
				rc.pNl();
				rc.getPrinter().indent();
				rc.p("java.sql.ResultSet rs_%s = sqlService.fetchCursor(\"%s\");", cursorName, cursorName);
				rc.pNl();
				final String fetchMethod = fetchOrientationMethod(trimmedSql);
				rc.p("if (rs_%s != null && rs_%s.%s()) {", cursorName, cursorName, fetchMethod);
				rc.pNl();
				rc.getPrinter().indent();
				int colIndex = 1;
				int indicatorColOffset = 0;
				for (int i = 0; i < fetchVars.size(); i++) {
					final String fetchVar = fetchVars.get(i);
					// Check if this host var resolves to a group field (e.g., VARCHAR structure)
					// If so, set the data sub-field instead of the group itself
					final String rawVarName = (i < fetchVarRawNames.size()) ? fetchVarRawNames.get(i) : null;
					final String leafName = rawVarName != null && rawVarName.contains(".")
							? rawVarName.substring(rawVarName.lastIndexOf('.') + 1) : rawVarName;
					// Use qualified name (rawVarName) when available for accurate group resolution
					// Unqualified leafName may match a different field (e.g., simple PIC X vs VARCHAR group)
					final DataDescriptionEntry qualEntry = (rawVarName != null) ? findEntryForVar(rawVarName, rc)
							: (leafName != null ? findEntryForVar(leafName, rc) : null);
					final List<DataDescriptionEntry> groupEntries;
					if (qualEntry instanceof DataDescriptionEntryGroup) {
						groupEntries = new ArrayList<>();
						collectLeafFieldEntries((DataDescriptionEntryGroup) qualEntry, groupEntries);
					} else {
						groupEntries = (leafName != null) ? resolveGroupFieldEntries(leafName, rc) : null;
					}
					// Determine if the indicator variable is an OCCURS array
					final String indRawName = (i < fetchIndicatorRawNames.size()) ? fetchIndicatorRawNames.get(i) : null;
					final String indJavaPath = (i < fetchIndicators.size()) ? fetchIndicators.get(i) : null;
					final DataDescriptionEntry indEntry = (indRawName != null) ? findEntryForVar(indRawName, rc) : null;
					final boolean indIsArray = indEntry instanceof DataDescriptionEntryGroup
							&& ((DataDescriptionEntryGroup) indEntry).getOccursClauses() != null
							&& !((DataDescriptionEntryGroup) indEntry).getOccursClauses().isEmpty();
					final int indOccursSize = indIsArray ? getOccursSize((DataDescriptionEntryGroup) indEntry) : -1;
					if (groupEntries != null && !groupEntries.isEmpty()) {
						// Group field: expand using expandGroupForSelectInto which correctly
						// treats VARCHAR sub-groups (LENGTH+DATA) as single DB columns
						final DataDescriptionEntry groupEntry = qualEntry != null ? qualEntry
								: findEntryForVar(rawVarName != null ? rawVarName : leafName, rc);
						final List<String[]> expandedVars = new ArrayList<>();
						expandGroupForSelectInto(groupEntry, fetchVar, expandedVars, rc);
						for (final String[] varInfo : expandedVars) {
							if ("varchar".equals(varInfo[1])) {
								// VARCHAR group: single DB column populates both LENGTH and DATA sub-fields
								// Pad _vc to the DATA field's PIC X width (see SELECT INTO varchar fix)
								final String lengthPath = varInfo[2];
								final String dataPath = varInfo[3];
								rc.p("{ String _vc = CobolResultSetHelper.safeGetString(rs_%s, %d); if (_vc != null) { %s = CobolMove.moveAlphanumericToAlphanumeric(_vc, %s.length()); %s = new BigDecimal(_vc.length()); } }",
										cursorName, colIndex, dataPath, dataPath, lengthPath);
							} else if ("numeric".equals(varInfo[1])) {
								rc.p("%s = CobolResultSetHelper.safeBigDecimal(rs_%s, %d);", varInfo[0], cursorName, colIndex);
							} else {
								// Pad/truncate to PIC X width to preserve flat byte layout
								rc.p("{ String _s = CobolResultSetHelper.safeGetString(rs_%s, %d); if (_s != null) %s = CobolMove.moveAlphanumericToAlphanumeric(_s, %s.length()); }",
										cursorName, colIndex, varInfo[0], varInfo[0]);
							}
							rc.pNl();
							// Set null indicator per column within the group
							if (indJavaPath != null) {
								if (indIsArray) {
									if (indOccursSize < 0 || indicatorColOffset < indOccursSize) {
										rc.p("%s[%d] = rs_%s.wasNull() ? BigDecimal.valueOf(-1) : BigDecimal.ZERO;",
												indJavaPath, indicatorColOffset, cursorName);
									}
								} else {
									rc.p("%s = rs_%s.wasNull() ? BigDecimal.valueOf(-1) : BigDecimal.ZERO;",
											indJavaPath, cursorName);
								}
								rc.pNl();
							}
							colIndex++;
							indicatorColOffset++;
						}
					} else {
						// Check if the target variable is numeric
						// Use qualified name to disambiguate fields with same leaf name
						final String scalarLeaf = rawVarName != null && rawVarName.contains(".")
								? rawVarName.substring(rawVarName.lastIndexOf('.') + 1) : rawVarName;
						final DataDescriptionEntry scalarEntry = (rawVarName != null) ? findEntryForVar(rawVarName, rc) : null;
						if (scalarEntry != null && isNumericEntry(scalarEntry)) {
							rc.p("%s = CobolResultSetHelper.safeBigDecimal(rs_%s, %d);", fetchVar, cursorName, colIndex);
						} else {
							// Pad/truncate to PIC X width to preserve flat byte layout
							rc.p("{ String _s = CobolResultSetHelper.safeGetString(rs_%s, %d); if (_s != null) %s = CobolMove.moveAlphanumericToAlphanumeric(_s, %s.length()); }",
									cursorName, colIndex, fetchVar, fetchVar);
						}
						rc.pNl();
						// Set null indicator if present
						if (indJavaPath != null) {
							if (indIsArray) {
								if (indOccursSize < 0 || indicatorColOffset < indOccursSize) {
									rc.p("%s[%d] = rs_%s.wasNull() ? BigDecimal.valueOf(-1) : BigDecimal.ZERO;",
											indJavaPath, indicatorColOffset, cursorName);
								}
							} else {
								rc.p("%s = rs_%s.wasNull() ? BigDecimal.valueOf(-1) : BigDecimal.ZERO;",
										indJavaPath, cursorName);
							}
							rc.pNl();
						}
						colIndex++;
						indicatorColOffset++;
					}
				}
				rc.p("sqlcode = BigDecimal.valueOf(sqlService.getSqlCode()); sqlstate = sqlService.getSqlState();");
				rc.pNl();
				rc.getPrinter().unindent();
				rc.p("} else {");
				rc.pNl();
				rc.getPrinter().indent();
				rc.p("sqlcode = BigDecimal.valueOf(100); sqlstate = \"02000\";");
				rc.pNl();
				rc.getPrinter().unindent();
				rc.p("}");
				rc.pNl();
				rc.getPrinter().unindent();
				rc.p("}");
				rc.pNl(execSqlStatement);
				return;
			}
		}

		// CLOSE CURSOR
		if (upperSql.startsWith("CLOSE")) {
			final java.util.regex.Matcher closeMatcher = Pattern.compile("(?i)CLOSE\\s+(\\w+)").matcher(trimmedSql);
			if (closeMatcher.find()) {
				final String cursorName = closeMatcher.group(1).toLowerCase();
				rc.p("sqlService.closeCursor(\"%s\");", cursorName);
				rc.pNl();
				rc.p("sqlcode = BigDecimal.valueOf(sqlService.getSqlCode()); sqlstate = sqlService.getSqlState();");
				rc.pNl(execSqlStatement);
				return;
			}
		}

		// SQL SET :hostvar = expression — assigns expression result to host variable.
		// Equivalent to SELECT expression FROM SYSIBM.SYSDUMMY1 INTO :hostvar.
		// Pattern: SET :OUTPUT_VAR = expr([... :INPUT_VAR ...])
		if (upperSql.startsWith("SET")) {
			final Matcher setMatcher = Pattern.compile(
					"(?i)SET\\s+:(\\S+)\\s*=\\s*(.+)").matcher(trimmedSql);
			if (setMatcher.find()) {
				final String outputVarName = setMatcher.group(1);
				final String expression = setMatcher.group(2).trim();
				final String outputVar = resolveHostVarForWrite(outputVarName, rc);

				// Extract input host vars from the expression
				final HostVarExtraction setExtraction = extractHostVars("SELECT " + expression + " FROM SYSIBM.SYSDUMMY1");
				final String selectSql = setExtraction.parameterizedSql;

				rc.p("{");
				rc.pNl();
				rc.getPrinter().indent();
				rc.p("java.sql.PreparedStatement ps = sqlService.prepareStatement(\"%s\");",
						selectSql.replace("\"", "\\\""));
				rc.pNl();

				// Set input parameters
				for (int i = 0; i < setExtraction.hostVars.size(); i++) {
					final String inputVar = resolveHostVar(setExtraction.hostVars.get(i), rc);
					rc.p("ps.setObject(%d, %s);", i + 1, inputVar);
					rc.pNl();
				}

				rc.p("try {");
				rc.pNl();
				rc.getPrinter().indent();
				rc.p("java.sql.ResultSet rs = ps.executeQuery();");
				rc.pNl();
				rc.p("if (rs.next()) {");
				rc.pNl();
				rc.getPrinter().indent();
				// Read result: use CobolResultSetHelper for type-safe retrieval
				final String readExpr = String.format("CobolResultSetHelper.safeGetValue(rs, 1, %s)", outputVar);
				emitSqlAssignment(rc, outputVar, readExpr);
				rc.pNl();
				rc.getPrinter().unindent();
				rc.p("}");
				rc.pNl();
				rc.p("sqlcode = BigDecimal.valueOf(sqlService.getSqlCode()); sqlstate = sqlService.getSqlState();");
				rc.pNl();
				rc.getPrinter().unindent();
				rc.p("} catch (java.sql.SQLException sqlex) {");
				rc.pNl();
				rc.getPrinter().indent();
				rc.p("sqlcode = BigDecimal.valueOf(sqlex.getErrorCode() != 0 ? sqlex.getErrorCode() : -1);");
				rc.pNl();
				rc.p("sqlstate = sqlex.getSQLState() != null ? sqlex.getSQLState() : \"58004\";");
				rc.pNl();
				rc.p("sqlerrmc = sqlex.getMessage();");
				rc.pNl();
				rc.getPrinter().unindent();
				rc.p("}");
				rc.pNl();
				rc.getPrinter().unindent();
				rc.p("}");
				rc.pNl(execSqlStatement);
				return;
			}
		}

		// Extract host variables and replace with ?, indicator-aware
		// :DATA_VAR :INDICATOR_VAR pairs → only DATA_VAR becomes ?, INDICATOR removed from SQL
		final HostVarExtraction extraction = extractHostVars(trimmedSql);
		final List<String> hostVars = new ArrayList<>(extraction.hostVars);
		final List<String> indicatorVars = new ArrayList<>(extraction.indicatorVars);
		final String parameterizedSql = extraction.parameterizedSql;

		// Detect INTO clause for SELECT/VALUES statements - extract target vars and remove from SQL/hostVars
		final List<String> selectIntoVars = new ArrayList<>();
		final List<String> selectIntoIndicators = new ArrayList<>();
		String actualSql = parameterizedSql;
		final boolean isValuesInto = upperSql.startsWith("VALUES") && upperSql.contains("INTO");
		final boolean isSelectLike = upperSql.startsWith("SELECT") || isValuesInto;
		if (isValuesInto) {
			// Convert VALUES (expr1, expr2) INTO :var1, :var2 → SELECT expr1, expr2 FROM SYSIBM.SYSDUMMY1
			// First extract INTO vars and optional indicators from original SQL
			final Matcher intoOrigMatcher = Pattern.compile(
					"(?i)\\bINTO\\s+(:[A-Za-z][A-Za-z0-9_.-]*(?:\\s+:[A-Za-z][A-Za-z0-9_.-]*)?(?:\\s*,\\s*:[A-Za-z][A-Za-z0-9_.-]*(?:\\s+:[A-Za-z][A-Za-z0-9_.-]*)?)*)").matcher(trimmedSql);
			if (intoOrigMatcher.find()) {
				final String intoClause = intoOrigMatcher.group(1);
				final String[] intoChunks = intoClause.split(",");
				for (final String chunk : intoChunks) {
					final Matcher chunkMatcher = HOST_VAR_PATTERN.matcher(chunk.trim());
					final List<String> chunkVars = new ArrayList<>();
					while (chunkMatcher.find()) {
						chunkVars.add(chunkMatcher.group(1));
					}
					if (chunkVars.size() >= 1) {
						selectIntoVars.add(chunkVars.get(0));
						final int idx = hostVars.indexOf(chunkVars.get(0));
						if (idx >= 0) {
							hostVars.remove(idx);
							indicatorVars.remove(idx);
						}
					}
					if (chunkVars.size() >= 2) {
						selectIntoIndicators.add(chunkVars.get(1));
					} else {
						selectIntoIndicators.add(null);
					}
				}
			}
			// Remove INTO ?, ?, ... from parameterized SQL (indicators already stripped, so no extra ?)
			actualSql = actualSql.replaceFirst("(?i)\\bINTO\\s+\\?(?:\\s*,\\s*\\?)*\\s*", " ");
			actualSql = actualSql.replaceFirst("(?i)^VALUES\\s*\\(", "SELECT ");
			// Remove trailing ) before any remaining clauses
			actualSql = actualSql.replaceFirst("\\)\\s*$", " FROM SYSIBM.SYSDUMMY1");
		}
		if (upperSql.startsWith("SELECT")) {
			// Find INTO clause with host vars and optional indicator vars (space-separated)
			// Pattern: INTO :var1[ :ind1][, :var2[ :ind2]]*
			final Matcher intoOrigMatcher = Pattern.compile(
					"(?i)\\bINTO\\s+(:[A-Za-z][A-Za-z0-9_.-]*(?:\\s+:[A-Za-z][A-Za-z0-9_.-]*)?(?:\\s*,\\s*:[A-Za-z][A-Za-z0-9_.-]*(?:\\s+:[A-Za-z][A-Za-z0-9_.-]*)?)*)").matcher(trimmedSql);
			if (intoOrigMatcher.find()) {
				final String intoClause = intoOrigMatcher.group(1);
				// Split by comma to get chunks; each chunk has 1 host var + optional indicator
				final String[] intoChunks = intoClause.split(",");
				for (final String chunk : intoChunks) {
					final Matcher chunkMatcher = HOST_VAR_PATTERN.matcher(chunk.trim());
					final List<String> chunkVars = new ArrayList<>();
					while (chunkMatcher.find()) {
						chunkVars.add(chunkMatcher.group(1));
					}
					if (chunkVars.size() >= 1) {
						selectIntoVars.add(chunkVars.get(0));
						final int idx = hostVars.indexOf(chunkVars.get(0));
						if (idx >= 0) {
							hostVars.remove(idx);
							indicatorVars.remove(idx);
						}
					}
					if (chunkVars.size() >= 2) {
						selectIntoIndicators.add(chunkVars.get(1));
					} else {
						selectIntoIndicators.add(null);
					}
				}
			}
			// Remove entire INTO clause from parameterized SQL (indicators already stripped, so no extra ?)
			actualSql = actualSql.replaceFirst("(?i)\\bINTO\\s+\\?(?:\\s*,\\s*\\?)*\\s*", " ");
		}

		// Resolve host vars to qualified Java paths
		final List<String> resolvedVars = new ArrayList<>();
		for (final String hostVar : hostVars) {
			resolvedVars.add(resolveHostVar(hostVar, rc));
		}

		// Expand group host variables in INSERT VALUES context
		// When INSERT INTO TABLE VALUES (:GROUP :INDICATOR_ARRAY), expand GROUP into individual columns
		// Each expanded leaf produces its own ps.setObject() call and its own ? in VALUES clause
		// Type info is stored in insertExpandedTypeInfo for use in the parameter-setting loop
		final List<String[]> insertExpandedTypeInfo = new ArrayList<>();
		if (upperSql.startsWith("INSERT")) {
			final List<String> newResolvedVars = new ArrayList<>();
			final List<String> newHostVars = new ArrayList<>();
			final List<String> newIndicatorVars = new ArrayList<>();
			boolean anyExpanded = false;

			for (int hvi = 0; hvi < resolvedVars.size(); hvi++) {
				final String hostVarCobol = hostVars.get(hvi);
				final DataDescriptionEntry entry = findEntryForVar(hostVarCobol, rc);
				final String indVar = (hvi < indicatorVars.size()) ? indicatorVars.get(hvi) : null;

				if (entry instanceof DataDescriptionEntryGroup
						&& ((DataDescriptionEntryGroup) entry).getDataDescriptionEntries().stream()
								.anyMatch(e -> e.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION)
						&& !isVarcharGroup(entry)) {
					// Expand group to leaf fields
					final String resolvedParent = resolvedVars.get(hvi);
					final List<String[]> leafFields = new ArrayList<>();
					expandGroupForSelectInto(entry, resolvedParent, leafFields, rc);

					for (final String[] leaf : leafFields) {
						newResolvedVars.add(leaf[0]);
						newHostVars.add(null); // null signals expanded field — use type info instead
						newIndicatorVars.add(indVar);
						insertExpandedTypeInfo.add(leaf);
					}
					anyExpanded = true;
				} else {
					newResolvedVars.add(resolvedVars.get(hvi));
					newHostVars.add(hostVarCobol);
					newIndicatorVars.add(indVar);
					insertExpandedTypeInfo.add(null);
				}
			}

			if (anyExpanded) {
				resolvedVars.clear();
				resolvedVars.addAll(newResolvedVars);
				hostVars.clear();
				hostVars.addAll(newHostVars);
				indicatorVars.clear();
				indicatorVars.addAll(newIndicatorVars);
			}
		}

		// Resolve INTO target variables - expand groups to individual fields
		// Each entry: [0]=resolvedJavaPath, [1]="string"|"numeric"|"varchar" (type hint)
		// For "varchar" entries, [2]=lengthFieldPath, [3]=dataFieldPath
		final List<String[]> resolvedIntoVars = new ArrayList<>();
		// Track which selectIntoVars index each resolvedIntoVar belongs to (for indicators)
		final List<Integer> varToGroupMap = new ArrayList<>();
		for (int si = 0; si < selectIntoVars.size(); si++) {
			final String intoVar = selectIntoVars.get(si);
			final int sizeBefore = resolvedIntoVars.size();
			// Use qualified lookup to find the correct DataDescriptionEntry
			// (avoids matching a same-named leaf field in a different record)
			final DataDescriptionEntry qualifiedEntry = findEntryForVar(intoVar, rc);
			// Check if the variable is a GROUP - if so, expand to leaf fields
			final String leafName = intoVar.contains(".") ? intoVar.substring(intoVar.lastIndexOf('.') + 1) : intoVar;
			// Determine if the entry is a group with non-condition children
			final boolean isGroup = qualifiedEntry instanceof DataDescriptionEntryGroup
					&& ((DataDescriptionEntryGroup) qualifiedEntry).getDataDescriptionEntries().stream()
							.anyMatch(e -> e.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION);
			if (isGroup) {
				final String resolvedParent = resolveHostVarForWrite(intoVar, rc);
				// Expand children, but detect VARCHAR sub-groups (LENGTH+DATA pattern)
				// and treat them as a single DB column
				expandGroupForSelectInto(qualifiedEntry, resolvedParent, resolvedIntoVars, rc);
			} else {
				final String resolved = resolveHostVarForWrite(intoVar, rc);
				final String cobolName = qualifiedEntry != null && qualifiedEntry.getName() != null
						? qualifiedEntry.getName().toUpperCase().replace("-", "_") : null;
				resolvedIntoVars.add(new String[]{resolved, qualifiedEntry != null && isNumericEntry(qualifiedEntry) ? "numeric" : "string", cobolName});
			}
			// Map all new entries to this selectIntoVar index
			for (int k = sizeBefore; k < resolvedIntoVars.size(); k++) {
				varToGroupMap.add(si);
			}
		}

		// After INSERT group expansion, fix the VALUES clause to have the right number of ? placeholders
		if (upperSql.startsWith("INSERT") && !insertExpandedTypeInfo.isEmpty()
				&& insertExpandedTypeInfo.stream().anyMatch(java.util.Objects::nonNull)) {
			// Count total ? needed (= resolvedVars.size() after expansion)
			final StringBuilder qmarks = new StringBuilder();
			for (int i = 0; i < resolvedVars.size(); i++) {
				if (i > 0) qmarks.append(", ");
				qmarks.append("?");
			}
			// Handle both VALUES (...) and VALUES ? (without parentheses)
			String replaced = actualSql.replaceFirst("(?i)VALUES\\s*\\([^)]*\\)", "VALUES (" + qmarks + ")");
			if (replaced.equals(actualSql)) {
				// No parenthesized VALUES found — try VALUES ? without parens
				replaced = actualSql.replaceFirst("(?i)VALUES\\s+\\?", "VALUES (" + qmarks + ")");
			}
			actualSql = replaced;
		}

		// DB2/400 does not allow untyped parameter markers inside TRIM().
		// Wrap with CAST so the driver knows the data type (SQL0418 fix).
		actualSql = actualSql.replaceAll("(?i)TRIM\\s*\\(\\s*\\?\\s*\\)", "TRIM(CAST(? AS VARCHAR(20000)))");

		// Quote SQL reserved words used as column names (e.g., DESC, ORDER, KEY)
		actualSql = quoteReservedColumnNames(actualSql);

		rc.p("{");
		rc.pNl();
		rc.getPrinter().indent();
		rc.p("java.sql.PreparedStatement ps = sqlService.prepareStatement(\"%s\");", escapeSql(actualSql));
		rc.pNl();

		// Wrap parameter binding AND execution in the same try/catch block.
		// The jt400 driver may throw SQLException during setObject/setString when
		// COBOL PIC X host variables contain values incompatible with DB2 DATE/TIME
		// column types (e.g., spaces or zeros passed to a DATE column).
		// Previously, setObject calls were outside the try block, causing uncaught
		// RuntimeExceptions that bypassed SQLCODE error handling.
		if (isSelectLike) {
			rc.p("try {");
			rc.pNl();
			rc.getPrinter().indent();
		} else if (!resolvedVars.isEmpty()) {
			rc.p("try {");
			rc.pNl();
			rc.getPrinter().indent();
		}

		int paramIndex = 1;
		for (int hvi = 0; hvi < resolvedVars.size(); hvi++) {
			final String javaVar = resolvedVars.get(hvi);
			final String hostVarCobol = hostVars.get(hvi);
			// Resolve indicator variable if present
			final String indVarCobol = (hvi < indicatorVars.size()) ? indicatorVars.get(hvi) : null;
			final String indVarJava = (indVarCobol != null) ? resolveHostVar(indVarCobol, rc) : null;
			// Detect if indicator variable is an OCCURS array (BigDecimal[])
			final DataDescriptionEntry indEntry = (indVarCobol != null) ? findEntryForVar(indVarCobol, rc) : null;
			final boolean indIsArray = indEntry instanceof DataDescriptionEntryGroup
					&& ((DataDescriptionEntryGroup) indEntry).getOccursClauses() != null
					&& !((DataDescriptionEntryGroup) indEntry).getOccursClauses().isEmpty();
			// Detect VARCHAR group host variables (LENGTH + DATA children)
			// Pass full qualified name so findEntryForVar resolves within the correct parent
			// For expanded INSERT fields (hostVarCobol is null), skip findEntryForVar
			final DataDescriptionEntry hvEntry = (hostVarCobol != null) ? findEntryForVar(hostVarCobol, rc) : null;
			// Check if this field was expanded from INSERT group — use type info
			final String[] expandedInfo = (hvi < insertExpandedTypeInfo.size()) ? insertExpandedTypeInfo.get(hvi) : null;
			if (indVarJava != null) {
				// Indicator variable present: if indicator < 0, set NULL; otherwise set value
				// For OCCURS arrays, index by field position (hvi)
				final String indAccess = indIsArray ? indVarJava + "[" + hvi + "]" : indVarJava;
				rc.p("if (%s.intValue() < 0) { ps.setNull(%d, java.sql.Types.VARCHAR); } else {",
						indAccess, paramIndex);
				rc.pNl();
				rc.getPrinter().indent();
				if (hvEntry != null && isVarcharGroup(hvEntry)) {
					final List<DataDescriptionEntry> vcChildren = new ArrayList<>();
					for (final DataDescriptionEntry vcChild : ((DataDescriptionEntryGroup) hvEntry).getDataDescriptionEntries()) {
						if (vcChild.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION) {
							vcChildren.add(vcChild);
						}
					}
					final String lengthField = javaVar + "." + javaVariableIdentifierService.mapToIdentifier(vcChildren.get(0));
					final String dataField = javaVar + "." + javaVariableIdentifierService.mapToIdentifier(vcChildren.get(1));
					rc.p("ps.setString(%d, %s != null ? %s.substring(0, %s.intValue()) : null);",
							paramIndex, dataField, dataField, lengthField);
				} else if (expandedInfo != null && "varchar".equals(expandedInfo[1])) {
					rc.p("ps.setString(%d, %s != null ? %s.substring(0, %s.intValue()) : null);",
							paramIndex, expandedInfo[3], expandedInfo[3], expandedInfo[2]);
				} else {
					rc.p("ps.setObject(%d, %s);", paramIndex, javaVar);
				}
				rc.pNl();
				rc.getPrinter().unindent();
				rc.p("}");
			} else if (hvEntry != null && isVarcharGroup(hvEntry)) {
				// VARCHAR group: extract data sub-field trimmed to length sub-field
				final List<DataDescriptionEntry> vcChildren = new ArrayList<>();
				for (final DataDescriptionEntry vcChild : ((DataDescriptionEntryGroup) hvEntry).getDataDescriptionEntries()) {
					if (vcChild.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION) {
						vcChildren.add(vcChild);
					}
				}
				final String lengthField = javaVar + "." + javaVariableIdentifierService.mapToIdentifier(vcChildren.get(0));
				final String dataField = javaVar + "." + javaVariableIdentifierService.mapToIdentifier(vcChildren.get(1));
				rc.p("ps.setString(%d, %s != null ? %s.substring(0, %s.intValue()) : null);",
						paramIndex, dataField, dataField, lengthField);
			} else if (expandedInfo != null && "varchar".equals(expandedInfo[1])) {
				// Expanded INSERT VARCHAR field: set data trimmed to length
				rc.p("ps.setString(%d, %s != null ? %s.substring(0, %s.intValue()) : null);",
						paramIndex, expandedInfo[3], expandedInfo[3], expandedInfo[2]);
			} else {
				rc.p("ps.setObject(%d, %s);", paramIndex, javaVar);
			}
			rc.pNl();
			paramIndex++;
		}

		if (isSelectLike) {
			rc.p("java.sql.ResultSet rs = ps.executeQuery();");
			rc.pNl();
			rc.p("if (rs.next()) {");
			rc.pNl();
			rc.getPrinter().indent();
			rc.p("sqlcode = BigDecimal.ZERO; sqlstate = \"00000\";");
			rc.pNl();
			// Populate INTO target variables from ResultSet, with null indicators
			if (!resolvedIntoVars.isEmpty()) {
				// Resolve indicator variables (may be arrays for group INTO targets)
				final List<String> resolvedIndicators = new ArrayList<>();
				for (final String indVar : selectIntoIndicators) {
					resolvedIndicators.add(indVar != null ? resolveHostVar(indVar, rc) : null);
				}

				// Detect SELECT * to use column-name-based access (column order in DB
				// may differ from COBOL structure order, causing misaligned reads).
				// Match SELECT * ... FROM (the INTO clause may appear between * and FROM)
				final boolean isSelectStar = upperSql.matches("(?s).*\\bSELECT\\s+\\*\\s+.*\\bFROM\\b.*")
						|| upperSql.matches("(?s).*\\bSELECT\\s+\\w+\\.\\*\\s+.*\\bFROM\\b.*");

				int colIdx = 1;

				for (int vi = 0; vi < resolvedIntoVars.size(); vi++) {
					final String[] varInfo = resolvedIntoVars.get(vi);
					// Get COBOL column name (last element of varInfo array) for SELECT * access
					final String cobolColName = (isSelectStar && varInfo.length > 0) ? varInfo[varInfo.length - 1] : null;
					final boolean useColName = isSelectStar && cobolColName != null && !cobolColName.isEmpty();

					if ("varchar".equals(varInfo[1])) {
						// VARCHAR group: single DB column populates both LENGTH and DATA sub-fields
						// IMPORTANT: Pad _vc to the DATA field's PIC X width to preserve the flat
						// byte layout for groupToString/moveStringToGroup. JDBC may return trimmed
						// strings for VARCHAR columns, but COBOL expects the DATA portion to be
						// left-justified and space-padded to the full PIC X(n) width.
						final String lengthPath = varInfo[2];
						final String dataPath = varInfo[3];
						if (useColName) {
							// Column-name access: skip field if column not found (returns null)
							rc.p("{ String _vc = CobolResultSetHelper.safeGetString(rs, \"%s\"); if (_vc != null) { %s = CobolMove.moveAlphanumericToAlphanumeric(_vc, %s.length()); %s = new BigDecimal(_vc.length()); } }",
									cobolColName, dataPath, dataPath, lengthPath);
						} else {
							rc.p("{ String _vc = CobolResultSetHelper.safeGetString(rs, %d); if (_vc != null) { %s = CobolMove.moveAlphanumericToAlphanumeric(_vc, %s.length()); %s = new BigDecimal(_vc.length()); } }",
									colIdx, dataPath, dataPath, lengthPath);
						}
					} else if ("numeric".equals(varInfo[1])) {
						if (useColName) {
							// Column-name access: skip field if column not found
							rc.p("{ BigDecimal _bd = CobolResultSetHelper.safeBigDecimalByName(rs, \"%s\"); if (_bd != null) %s = _bd; }",
									cobolColName, varInfo[0]);
						} else {
							rc.p("%s = CobolResultSetHelper.safeBigDecimal(rs, %d);", varInfo[0], colIdx);
						}
					} else {
						if (useColName) {
							// Column-name access: pad/truncate to PIC X width to preserve flat byte layout
							rc.p("{ String _s = CobolResultSetHelper.safeGetString(rs, \"%s\"); if (_s != null) %s = CobolMove.moveAlphanumericToAlphanumeric(_s, %s.length()); }",
									cobolColName, varInfo[0], varInfo[0]);
						} else {
							rc.p("{ String _s = CobolResultSetHelper.safeGetString(rs, %d); if (_s != null) %s = CobolMove.moveAlphanumericToAlphanumeric(_s, %s.length()); }",
									colIdx, varInfo[0], varInfo[0]);
						}
					}
					rc.pNl();

					// Generate null indicator code if indicator variable is present
					final int groupIdx = (vi < varToGroupMap.size()) ? varToGroupMap.get(vi) : -1;
					if (groupIdx >= 0 && groupIdx < resolvedIndicators.size() && resolvedIndicators.get(groupIdx) != null) {
						final String indPath = resolvedIndicators.get(groupIdx);
						// Determine field position within the indicator array
						// Count how many fields before this one belong to the same group
						int fieldPos = 0;
						for (int prev = 0; prev < vi; prev++) {
							if (prev < varToGroupMap.size() && varToGroupMap.get(prev) == groupIdx) {
								fieldPos++;
							}
						}
						// Check if indicator is an OCCURS array
						final DataDescriptionEntry indEntry = findEntryForVar(
								selectIntoIndicators.get(groupIdx), rc);
						final boolean isArray = indEntry instanceof DataDescriptionEntryGroup
								&& ((DataDescriptionEntryGroup) indEntry).getOccursClauses() != null
								&& !((DataDescriptionEntryGroup) indEntry).getOccursClauses().isEmpty();
						if (isArray) {
							final int selIndOccursSize = getOccursSize((DataDescriptionEntryGroup) indEntry);
							if (selIndOccursSize < 0 || fieldPos < selIndOccursSize) {
								rc.p("%s[%d] = rs.wasNull() ? BigDecimal.valueOf(-1) : BigDecimal.ZERO;",
										indPath, fieldPos);
							}
						} else {
							rc.p("%s = rs.wasNull() ? BigDecimal.valueOf(-1) : BigDecimal.ZERO;", indPath);
						}
						rc.pNl();
					}
					colIdx++;
				}
			}
			rc.getPrinter().unindent();
			rc.p("} else {");
			rc.pNl();
			rc.getPrinter().indent();
			rc.p("sqlcode = BigDecimal.valueOf(100); sqlstate = \"02000\";");
			rc.pNl();
			rc.getPrinter().unindent();
			rc.p("}");
			rc.pNl();
			rc.getPrinter().unindent();
			rc.p("} catch (java.sql.SQLException sqlex) {");
			rc.pNl();
			rc.getPrinter().indent();
			rc.p("sqlcode = BigDecimal.valueOf(sqlex.getErrorCode() != 0 ? sqlex.getErrorCode() : -1);");
			rc.pNl();
			rc.p("sqlstate = sqlex.getSQLState() != null ? sqlex.getSQLState() : \"58004\";");
			rc.pNl();
			rc.p("sqlerrmc = sqlex.getMessage();");
			rc.pNl();
			rc.getPrinter().unindent();
			rc.p("}");
		} else {
			// For INSERT/UPDATE/DELETE: if we already opened a try block for parameter
			// binding (resolvedVars not empty), just emit the execution; otherwise open try now
			if (resolvedVars.isEmpty()) {
				rc.p("try {");
				rc.pNl();
				rc.getPrinter().indent();
			}
			rc.p("int _rowsAffected = ps.executeUpdate();");
			rc.pNl();
			rc.p("((io.proleap.cobol.runtime.impl.SqlServiceImpl) sqlService).setLastUpdateCount(_rowsAffected);");
			rc.pNl();
			rc.p("if (_rowsAffected == 0) { sqlService.setSqlCode(100); }");
			rc.pNl();
			rc.p("sqlcode = BigDecimal.valueOf(sqlService.getSqlCode()); sqlstate = sqlService.getSqlState();");
			rc.pNl();
			rc.getPrinter().unindent();
			rc.p("} catch (java.sql.SQLException sqlex) {");
			rc.pNl();
			rc.getPrinter().indent();
			rc.p("sqlcode = BigDecimal.valueOf(sqlex.getErrorCode() != 0 ? sqlex.getErrorCode() : -1);");
			rc.pNl();
			rc.p("sqlstate = sqlex.getSQLState() != null ? sqlex.getSQLState() : \"58004\";");
			rc.pNl();
			rc.p("sqlerrmc = sqlex.getMessage();");
			rc.pNl();
			rc.getPrinter().unindent();
			rc.p("}");
		}
		rc.pNl();

		rc.getPrinter().unindent();
		rc.p("}");

		rc.pNl(execSqlStatement);
	}

	/**
	 * Generates the openCursor call with parameterized SQL and host variable bindings.
	 */
	/**
	 * Generates Java code for a FETCH FROM cursor FOR n ROWS INTO :hostvar that was
	 * merged into the same EXEC SQL block as DECLARE/OPEN. This handles the case where
	 * no RELATIVE keyword is present (no rs.absolute() positioning).
	 */
	private void generateMergedFetch(final String fetchSql, final RuleContext rc) {
		final java.util.regex.Matcher fetchMatcher = Pattern
				.compile("(?i)FETCH\\s+(?:(?:NEXT|PRIOR|FIRST|LAST)\\s+)?(?:FROM\\s+)?(\\w+)\\s+FOR\\s+(\\d+|:\\w+(?:-\\w+)*)\\s+ROWS?\\s+INTO\\s+(.+)")
				.matcher(fetchSql);
		if (!fetchMatcher.find()) {
			// Not a multi-row fetch pattern; try single-row FETCH INTO
			final java.util.regex.Matcher singleFetchMatcher = Pattern
					.compile("(?i)FETCH\\s+(?:(?:NEXT|PRIOR|FIRST|LAST)\\s+)?(?:FROM\\s+)?(\\w+)\\s+INTO\\s+(.+)")
					.matcher(fetchSql);
			if (singleFetchMatcher.find()) {
				final String cursorName = singleFetchMatcher.group(1).toLowerCase();
				final String intoVars = singleFetchMatcher.group(2).trim();
				// Extract host variables
				final List<String> fetchVars = new ArrayList<>();
				final List<String> fetchIndicators = new ArrayList<>();
				final String[] chunks = intoVars.split(",");
				for (final String chunk : chunks) {
					final Matcher chunkMatcher = HOST_VAR_PATTERN.matcher(chunk.trim());
					final List<String> chunkVars = new ArrayList<>();
					while (chunkMatcher.find()) {
						chunkVars.add(chunkMatcher.group(1));
					}
					if (chunkVars.size() >= 1) {
						fetchVars.add(resolveHostVar(chunkVars.get(0), rc));
					}
					if (chunkVars.size() >= 2) {
						fetchIndicators.add(resolveHostVar(chunkVars.get(1), rc));
					} else {
						fetchIndicators.add(null);
					}
				}
				rc.p("{ java.sql.ResultSet rs_%s = sqlService.fetchCursor(\"%s\");", cursorName, cursorName);
				rc.pNl();
				final String mergedFetchMethod = fetchOrientationMethod(fetchSql);
				rc.p("if (rs_%s != null && rs_%s.%s()) {", cursorName, cursorName, mergedFetchMethod);
				rc.pNl();
				rc.getPrinter().indent();
				for (int i = 0; i < fetchVars.size(); i++) {
					// Pad/truncate to PIC X width to preserve flat byte layout
					rc.p("{ String _s = CobolResultSetHelper.safeGetString(rs_%s, %d); if (_s != null) %s = CobolMove.moveAlphanumericToAlphanumeric(_s, %s.length()); }",
							cursorName, i + 1, fetchVars.get(i), fetchVars.get(i));
					rc.pNl();
				}
				rc.getPrinter().unindent();
				rc.p("sqlcode = BigDecimal.ZERO; sqlstate = \"00000\";");
				rc.pNl();
				rc.p("} else { sqlcode = BigDecimal.valueOf(100); sqlstate = \"02000\"; } }");
				rc.pNl();
			}
			return;
		}
		final String cursorName = fetchMatcher.group(1).toLowerCase();
		final String rowCountRaw = fetchMatcher.group(2);
		final boolean rowCountIsVar = rowCountRaw.startsWith(":");
		final int rowCountLiteral = rowCountIsVar ? 0 : Integer.parseInt(rowCountRaw);
		final String rowCountExpr = rowCountIsVar ? resolveHostVar(rowCountRaw.substring(1), rc) : String.valueOf(rowCountLiteral);
		final String intoVars = fetchMatcher.group(3).trim();

		// Extract host variables and indicator variables
		final List<String> fetchVars = new ArrayList<>();
		final List<String> fetchVarCobolNames = new ArrayList<>();
		final List<String> fetchIndicators = new ArrayList<>();
		final String[] chunks = intoVars.split(",");
		for (final String chunk : chunks) {
			final Matcher chunkMatcher = HOST_VAR_PATTERN.matcher(chunk.trim());
			final List<String> chunkVars = new ArrayList<>();
			while (chunkMatcher.find()) {
				chunkVars.add(chunkMatcher.group(1));
			}
			if (chunkVars.size() >= 1) {
				fetchVars.add(resolveHostVar(chunkVars.get(0), rc));
				fetchVarCobolNames.add(chunkVars.get(0));
			}
			if (chunkVars.size() >= 2) {
				fetchIndicators.add(resolveHostVar(chunkVars.get(1), rc));
			} else {
				fetchIndicators.add(null);
			}
		}

		// Generate multi-row fetch loop (no RELATIVE positioning)
		rc.p("{");
		rc.pNl();
		rc.getPrinter().indent();
		rc.p("java.sql.ResultSet rs_%s = sqlService.fetchCursor(\"%s\");", cursorName, cursorName);
		rc.pNl();
		rc.p("int fetchRowCount_%s = 0;", cursorName);
		rc.pNl();
		rc.p("if (rs_%s != null) {", cursorName);
		rc.pNl();
		rc.getPrinter().indent();
		final String mergedMultiRowFetchMethod = fetchOrientationMethod(fetchSql);
		rc.p("for (int fetchIdx_%s = 0; fetchIdx_%s < %s && rs_%s.%s(); fetchIdx_%s++) {",
				cursorName, cursorName, rowCountIsVar ? rowCountExpr + ".intValue()" : String.valueOf(rowCountLiteral), cursorName, mergedMultiRowFetchMethod, cursorName);
		rc.pNl();
		rc.getPrinter().indent();
		rc.p("fetchRowCount_%s++;", cursorName);
		rc.pNl();
		int colIndex = 1;
		// Limit rs.get() calls to the number of SELECT columns to avoid
		// Column Index out of range when the INTO structure has more fields than the SELECT list
		final int selectColLimit = countSelectColumns(cursorName);

		for (int i = 0; i < fetchVars.size(); i++) {
			if (selectColLimit > 0 && colIndex > selectColLimit) break;
			final String cobolVarName = (i < fetchVarCobolNames.size()) ? fetchVarCobolNames.get(i) : null;
			final String leafName = cobolVarName != null && cobolVarName.contains(".")
					? cobolVarName.substring(cobolVarName.lastIndexOf('.') + 1) : cobolVarName;
			if (leafName == null) continue;

			final DataDescriptionEntry intoEntry = findEntryForVar(
					cobolVarName != null ? cobolVarName : leafName, rc);

			List<FetchColumnMapping> columnMappings = new ArrayList<>();
			if (intoEntry instanceof DataDescriptionEntryGroup) {
				if (isVarcharGroup(intoEntry)) {
					// The INTO variable itself is a VARCHAR group (e.g., :BASE000301.ALIASEXTID)
					final List<DataDescriptionEntry> vcChildren = new ArrayList<>();
					for (final DataDescriptionEntry vcChild : ((DataDescriptionEntryGroup) intoEntry).getDataDescriptionEntries()) {
						if (vcChild.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION) {
							vcChildren.add(vcChild);
						}
					}
					final String lengthPath = buildFetchElementPath(vcChildren.get(0), cursorName);
					final String dataPath = buildFetchElementPath(vcChildren.get(1), cursorName);
					columnMappings.add(new FetchColumnMapping(lengthPath, dataPath));
				} else {
					collectFetchColumnMappings((DataDescriptionEntryGroup) intoEntry, columnMappings, cursorName);
					if (columnMappings.isEmpty() && intoEntry.getParentDataDescriptionEntryGroup() != null) {
						collectFetchColumnMappings(intoEntry.getParentDataDescriptionEntryGroup(), columnMappings, cursorName);
					}
				}
			}

			if (!columnMappings.isEmpty()) {
				for (final FetchColumnMapping mapping : columnMappings) {
					if (selectColLimit > 0 && colIndex > selectColLimit) break;
					if (mapping.isVarchar) {
						// Pad _vc to the DATA field's PIC X width (see SELECT INTO varchar fix)
						rc.p("{ String _vc = CobolResultSetHelper.safeGetString(rs_%s, %d); if (_vc != null) { %s = CobolMove.moveAlphanumericToAlphanumeric(_vc, %s.length()); %s = new BigDecimal(_vc.length()); } }",
								cursorName, colIndex, mapping.dataPath, mapping.dataPath, mapping.lengthPath);
					} else {
						if (mapping.isNumeric) {
							rc.p("%s = CobolResultSetHelper.safeBigDecimal(rs_%s, %d);", mapping.fetchPath, cursorName, colIndex);
						} else {
							// Pad/truncate to PIC X width to preserve flat byte layout
							rc.p("{ String _s = CobolResultSetHelper.safeGetString(rs_%s, %d); if (_s != null) %s = CobolMove.moveAlphanumericToAlphanumeric(_s, %s.length()); }",
									cursorName, colIndex, mapping.fetchPath, mapping.fetchPath);
						}
					}
					rc.pNl();
					colIndex++;
				}
			} else {
				final String fetchPath = (intoEntry != null) ? buildFetchElementPath(intoEntry, cursorName) : fetchVars.get(i);
				if (intoEntry != null && isNumericEntry(intoEntry)) {
					rc.p("%s = CobolResultSetHelper.safeBigDecimal(rs_%s, %d);", fetchPath, cursorName, colIndex);
				} else {
					// Pad/truncate to PIC X width to preserve flat byte layout
					rc.p("{ String _s = CobolResultSetHelper.safeGetString(rs_%s, %d); if (_s != null) %s = CobolMove.moveAlphanumericToAlphanumeric(_s, %s.length()); }",
							cursorName, colIndex, fetchPath, fetchPath);
				}
				rc.pNl();
				colIndex++;
			}
		}
		rc.getPrinter().unindent();
		rc.p("}");
		rc.pNl();
		rc.getPrinter().unindent();
		rc.p("}");
		rc.pNl();
		rc.p("sqlerrd[2] = new BigDecimal(fetchRowCount_%s);", cursorName);
		rc.pNl();
		rc.p("if (fetchRowCount_%s < %s) { sqlerrd[4] = BigDecimal.valueOf(100); }",
				cursorName, rowCountIsVar ? rowCountExpr + ".intValue()" : String.valueOf(rowCountLiteral));
		rc.pNl();
		rc.p("sqlcode = BigDecimal.valueOf(fetchRowCount_%s > 0 ? 0 : 100);", cursorName);
		rc.pNl();
		rc.p("sqlstate = fetchRowCount_%s > 0 ? \"00000\" : \"02000\";", cursorName);
		rc.pNl();
		rc.getPrinter().unindent();
		rc.p("}");
		rc.pNl();
	}

	private void generateOpenCursor(final String cursorName, final String parameterizedSql,
			final List<String> cursorHostVars, final RuleContext rc) {
		if (cursorHostVars.isEmpty()) {
			rc.p("sqlService.openCursor(\"%s\", \"%s\");", cursorName, escapeSql(parameterizedSql));
		} else {
			final List<String> resolvedVars = new ArrayList<>();
			for (final String hostVar : cursorHostVars) {
				resolvedVars.add(resolveHostVar(hostVar, rc));
			}
			rc.p("sqlService.openCursor(\"%s\", \"%s\", %s);", cursorName, escapeSql(parameterizedSql),
					String.join(", ", resolvedVars));
		}
		rc.pNl();
		rc.p("sqlcode = BigDecimal.valueOf(sqlService.getSqlCode()); sqlstate = sqlService.getSqlState();");
	}

	/**
	 * Resolves a COBOL host variable name to its qualified Java path using the ASG.
	 * Supports qualified names like VA000000.KEY1 (COBOL OF/IN qualification).
	 */
	private String resolveHostVar(final String cobolName, final RuleContext rc) {
		// Handle qualified names like VA000000.KEY1
		if (cobolName.contains(".")) {
			final String[] parts = cobolName.split("\\.");
			// Resolve the leaf field name (last part), searching within the parent (first part)
			final String parentName = parts[0];
			final String fieldName = parts[parts.length - 1];

			final Program program = rc.getProgram();
			if (program != null) {
				for (final var cu : program.getCompilationUnits()) {
					for (final var pu : cu.getProgramUnits()) {
						// Search ALL matching entries for the parent name, not just the first.
						// A leaf field (e.g., LISTAGEM PIC X(10) inside RB00012CB0) may shadow
						// a group with the same name (e.g., 01 LISTAGEM with subfields CODSOC, TIPMOV).
						final List<DataDescriptionEntry> parentEntries = findAllDataDescriptionEntries(parentName, pu.getDataDivision());
						for (final DataDescriptionEntry parentEntry : parentEntries) {
							if (parentEntry instanceof DataDescriptionEntryGroup) {
								DataDescriptionEntry fieldEntry = searchInGroup(fieldName, (DataDescriptionEntryGroup) parentEntry);
								if (fieldEntry != null) {
									// Check if this field is a child of a group-over-elementary REDEFINES
									final DataDescriptionEntryGroup fieldParent = fieldEntry.getParentDataDescriptionEntryGroup();
									if (fieldParent != null && isGroupOverElementaryRedefinesSql(fieldParent, pu.getDataDivision())) {
										return buildGroupOverElementaryGetterPath(fieldEntry, fieldParent);
									}
									return buildQualifiedPath(fieldEntry);
								}
								// VARCHAR fallback: try fieldName-DATA (DDS VARCHAR columns create GROUP with -DATA suffix)
								fieldEntry = searchInGroup(fieldName + "-DATA", (DataDescriptionEntryGroup) parentEntry);
								if (fieldEntry != null) {
									// If it's a VARCHAR group, resolve to the -DATA-DATA sub-field (actual data portion)
									if (fieldEntry instanceof DataDescriptionEntryGroup) {
										final DataDescriptionEntry dataChild = searchInGroup(fieldName + "-DATA-DATA",
												(DataDescriptionEntryGroup) fieldEntry);
										if (dataChild != null) {
											return buildQualifiedPath(dataChild);
										}
									}
									return buildQualifiedPath(fieldEntry);
								}
							}
						}
						// If we found group entries but none contained the field, use the first group + field name
						for (final DataDescriptionEntry parentEntry : parentEntries) {
							if (parentEntry instanceof DataDescriptionEntryGroup) {
								return buildQualifiedPath(parentEntry) + "." + fieldName.toLowerCase().replace("-", "_");
							}
						}
					}
				}
			}
			// Fallback: convert both parts to Java identifiers
			final StringBuilder sb = new StringBuilder();
			for (int i = 0; i < parts.length; i++) {
				if (i > 0) sb.append(".");
				sb.append(parts[i].toLowerCase().replace("-", "_"));
			}
			return sb.toString();
		}

		final Program program = rc.getProgram();
		if (program == null) {
			return cobolName.toLowerCase().replace("-", "_");
		}

		// Search all compilation units for a matching data description entry
		for (final var cu : program.getCompilationUnits()) {
			for (final var pu : cu.getProgramUnits()) {
				final DataDescriptionEntry found = findDataDescriptionEntry(cobolName, pu.getDataDivision());
				if (found != null) {
					// REDEFINES fields are generated as getter methods, not direct fields.
					// Use the getter pattern (e.g., getLk_numrown()) instead of the variable path.
					if (found instanceof DataDescriptionEntryGroup) {
						final DataDescriptionEntryGroup foundGroup = (DataDescriptionEntryGroup) found;
						if (foundGroup.getRedefinesClause() != null && foundGroup.getRedefinesClause().getRedefinesCall() != null) {
							return buildRedefinesGetterPath(found);
						}
					}
					// Check if this field is a child of a group-over-elementary REDEFINES.
					// In that case, the parent group is replaced by getters/setters, so we must
					// use the getter (e.g., getLkqtdstruct_Lkqtdval()) instead of dotted field
					// access (e.g., lkqtdstruct.lkqtdval).
					final DataDescriptionEntryGroup parentGroup = found.getParentDataDescriptionEntryGroup();
					if (parentGroup != null && isGroupOverElementaryRedefinesSql(parentGroup, pu.getDataDivision())) {
						return buildGroupOverElementaryGetterPath(found, parentGroup);
					}
					return buildQualifiedPath(found);
				}
			}
		}

		return cobolName.toLowerCase().replace("-", "_");
	}

	/**
	 * Resolves a host variable for write (lvalue) context. For REDEFINES fields, resolves
	 * to the original (redefined) field path instead of the getter expression, because
	 * getters cannot be used as assignment targets.
	 * <p>
	 * Exception: when the REDEFINES entry has a PIC clause (elementary-over-group REDEFINES,
	 * e.g., {@code 01 W-DATA-RED REDEFINES W-DATA PIC X(10)}), the original field is a group
	 * (inner class type) that cannot be passed to safeGetValue. In this case, use the
	 * getter expression so that emitSqlAssignment converts it to a setter call.
	 */
	private String resolveHostVarForWrite(final String cobolName, final RuleContext rc) {
		final DataDescriptionEntry entry = findEntryForVar(cobolName, rc);
		if (entry instanceof DataDescriptionEntryGroup) {
			final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) entry;
			if (group.getRedefinesClause() != null && group.getRedefinesClause().getRedefinesCall() != null) {
				// Check if this REDEFINES entry has a PIC clause (elementary-over-group).
				// If so, the original field is a group type (inner class) — we must use
				// the getter/setter pattern instead of redirecting to the group field.
				if (group.getPictureClause() != null) {
					// Elementary-over-group REDEFINES: use getter so emitSqlAssignment
					// converts to setter (e.g., setW_data_red(value))
					return buildRedefinesGetterPath(entry);
				}
				// Group-over-group or group-over-elementary: resolve to the original field
				final String origName = group.getRedefinesClause().getRedefinesCall().getName();
				return resolveHostVar(origName, rc);
			}
		}
		// Fallback: resolve normally, but if the result is a getter (ends with "()")
		// it cannot be used as an assignment target, so convert to a setter pattern
		final String resolved = resolveHostVar(cobolName, rc);
		if (resolved.endsWith("()") && resolved.contains("get")) {
			// This is a getter — store as a special marker that emitSqlAssignment can detect
			// The caller must handle this case using the setter form
			return resolved;
		}
		return resolved;
	}

	/**
	 * Emits a SQL assignment statement. If the target is a getter (REDEFINES),
	 * uses the corresponding setter method instead of direct assignment.
	 */
	private void emitSqlAssignment(final RuleContext rc, final String outputVar, final String valueExpr) {
		if (outputVar.endsWith("()") && outputVar.contains("get")) {
			// Convert getter to setter: getXxx() -> setXxx(value)
			final String setterName = "s" + outputVar.substring(1, outputVar.length() - 2);
			final String prefix = outputVar.substring(0, outputVar.lastIndexOf('.') + 1);
			final String getterMethod = outputVar.substring(outputVar.lastIndexOf('.') + 1);
			final String setterMethod = "s" + getterMethod.substring(1, getterMethod.length() - 2);
			if (prefix.isEmpty()) {
				rc.p("%s(%s);", setterMethod, valueExpr);
			} else {
				rc.p("%s%s(%s);", prefix, setterMethod, valueExpr);
			}
		} else {
			rc.p("%s = %s;", outputVar, valueExpr);
		}
	}

	/**
	 * Builds a getter expression for a REDEFINES field.
	 * E.g., for a field LK-NUMROWN that REDEFINES LK-NUMROW under parent LK,
	 * produces: lk.getLk_numrown()
	 */
	private String buildRedefinesGetterPath(final DataDescriptionEntry entry) {
		final String variableId = javaVariableIdentifierService.mapToIdentifier(entry);
		final String getterName = "get" + Character.toUpperCase(variableId.charAt(0)) + variableId.substring(1);

		final DataDescriptionEntryGroup parentGroup = entry.getParentDataDescriptionEntryGroup();
		if (parentGroup != null) {
			final List<String> parts = new ArrayList<>();
			DataDescriptionEntry current = parentGroup;
			while (current != null) {
				parts.add(javaVariableIdentifierService.mapToIdentifier(current));
				current = current.getParentDataDescriptionEntryGroup();
			}
			Collections.reverse(parts);
			return String.join(".", parts) + "." + getterName + "()";
		} else {
			return getterName + "()";
		}
	}

	/**
	 * Checks whether a DataDescriptionEntryGroup is a group that REDEFINES an elementary
	 * (non-group) field. Used to detect group-over-elementary REDEFINES patterns where
	 * fields are generated as getters/setters rather than direct field access.
	 */
	private boolean isGroupOverElementaryRedefinesSql(final DataDescriptionEntryGroup group,
			final io.proleap.cobol.asg.metamodel.data.DataDivision dataDivision) {
		if (group.getRedefinesClause() == null || group.getRedefinesClause().getRedefinesCall() == null) {
			return false;
		}
		// Must have non-condition children (be a true group)
		final boolean hasNonConditionChildren = group.getDataDescriptionEntries().stream()
				.anyMatch(e -> e.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION);
		if (!hasNonConditionChildren) {
			return false;
		}
		// Find the base (redefined) entry
		final String baseName = group.getRedefinesClause().getRedefinesCall().getName();
		DataDescriptionEntry baseDde = null;
		// Search among siblings
		final DataDescriptionEntryGroup parent = group.getParentDataDescriptionEntryGroup();
		if (parent != null) {
			for (final DataDescriptionEntry sibling : parent.getDataDescriptionEntries()) {
				if (baseName.equalsIgnoreCase(sibling.getName())) {
					baseDde = sibling;
					break;
				}
			}
		} else if (dataDivision != null) {
			// Top-level: search in data division sections
			baseDde = findDataDescriptionEntry(baseName, dataDivision);
		}
		if (baseDde == null) {
			return false;
		}
		// The base must be elementary (not a group with non-condition children)
		if (baseDde.getDataDescriptionEntryType() == DataDescriptionEntryType.GROUP) {
			final DataDescriptionEntryGroup baseGroup = (DataDescriptionEntryGroup) baseDde;
			final boolean baseHasChildren = baseGroup.getDataDescriptionEntries().stream()
					.anyMatch(e -> e.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION);
			if (baseHasChildren) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Builds a getter expression for a child of a group-over-elementary REDEFINES.
	 * E.g., for LkQtdVal inside LkQtdStruct REDEFINES LkQtd, returns
	 * "getLkqtdstruct_Lkqtdval()" (top-level) or "parent.getLkqtdstruct_Lkqtdval()" (nested).
	 */
	private String buildGroupOverElementaryGetterPath(final DataDescriptionEntry childEntry,
			final DataDescriptionEntryGroup redefinesGroup) {
		final String groupId = javaVariableIdentifierService.mapToIdentifier(redefinesGroup);
		final String capGroupId = Character.toUpperCase(groupId.charAt(0)) + groupId.substring(1);
		final String childId = javaVariableIdentifierService.mapToIdentifier(childEntry);
		final String capChildId = capGroupId + "_" + Character.toUpperCase(childId.charAt(0)) + childId.substring(1);
		final String getterName = "get" + capChildId;

		// Build the parent hierarchy path (skip the REDEFINES group itself, go to its parent)
		final DataDescriptionEntryGroup grandparent = redefinesGroup.getParentDataDescriptionEntryGroup();
		if (grandparent != null) {
			final List<String> parts = new ArrayList<>();
			DataDescriptionEntry current = grandparent;
			while (current != null) {
				parts.add(javaVariableIdentifierService.mapToIdentifier(current));
				current = current.getParentDataDescriptionEntryGroup();
			}
			Collections.reverse(parts);
			return String.join(".", parts) + "." + getterName + "()";
		} else {
			return getterName + "()";
		}
	}

	/**
	 * Finds a DataDescriptionEntry by COBOL name in the data division.
	 */
	private DataDescriptionEntry findDataDescriptionEntry(final String name,
			final io.proleap.cobol.asg.metamodel.data.DataDivision dataDivision) {
		if (dataDivision == null) {
			return null;
		}
		DataDescriptionEntry result = searchEntries(name, dataDivision.getWorkingStorageSection());
		if (result == null) {
			result = searchEntries(name, dataDivision.getLinkageSection());
		}
		// Also search FileSection FD entries
		if (result == null) {
			final io.proleap.cobol.asg.metamodel.data.file.FileSection fileSection = dataDivision.getFileSection();
			if (fileSection != null && fileSection.getFileDescriptionEntries() != null) {
				for (final io.proleap.cobol.asg.metamodel.data.file.FileDescriptionEntry fde : fileSection.getFileDescriptionEntries()) {
					result = searchEntries(name, fde);
					if (result != null) {
						break;
					}
				}
			}
		}
		return result;
	}

	/**
	 * Finds ALL DataDescriptionEntries matching the given COBOL name.
	 * Unlike findDataDescriptionEntry which returns only the first match,
	 * this returns all matches so the caller can pick the right one
	 * (e.g., a group entry when resolving qualified names like LISTAGEM.CODSOC).
	 */
	private List<DataDescriptionEntry> findAllDataDescriptionEntries(final String name,
			final io.proleap.cobol.asg.metamodel.data.DataDivision dataDivision) {
		final List<DataDescriptionEntry> results = new ArrayList<>();
		if (dataDivision == null) {
			return results;
		}
		searchAllEntries(name, dataDivision.getWorkingStorageSection(), results);
		searchAllEntries(name, dataDivision.getLinkageSection(), results);
		final io.proleap.cobol.asg.metamodel.data.file.FileSection fileSection = dataDivision.getFileSection();
		if (fileSection != null && fileSection.getFileDescriptionEntries() != null) {
			for (final io.proleap.cobol.asg.metamodel.data.file.FileDescriptionEntry fde : fileSection.getFileDescriptionEntries()) {
				searchAllEntries(name, fde, results);
			}
		}
		return results;
	}

	/**
	 * Collects all DataDescriptionEntries matching the given name (including nested entries).
	 */
	private void searchAllEntries(final String name,
			final io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryContainer container,
			final List<DataDescriptionEntry> results) {
		if (container == null) {
			return;
		}
		for (final DataDescriptionEntry entry : container.getDataDescriptionEntries()) {
			if (namesMatchHyphenInsensitive(name, entry.getName())) {
				results.add(entry);
			}
			if (entry instanceof DataDescriptionEntryGroup) {
				searchAllEntriesInGroup(name, (DataDescriptionEntryGroup) entry, results);
			}
		}
	}

	private void searchAllEntriesInGroup(final String name,
			final DataDescriptionEntryGroup group,
			final List<DataDescriptionEntry> results) {
		for (final DataDescriptionEntry entry : group.getDataDescriptionEntries()) {
			if (namesMatchHyphenInsensitive(name, entry.getName())) {
				results.add(entry);
			}
			if (entry instanceof DataDescriptionEntryGroup) {
				searchAllEntriesInGroup(name, (DataDescriptionEntryGroup) entry, results);
			}
		}
	}

	/**
	 * Compares two COBOL names with hyphen-insensitive matching.
	 * DDS-generated copybooks may have field names with different hyphenation
	 * than the COBOL source (e.g., IND-CAT vs INDCAT, DTA-CRI vs DTACRI).
	 */
	private boolean namesMatchHyphenInsensitive(final String a, final String b) {
		if (a == null || b == null) {
			return false;
		}
		if (a.equalsIgnoreCase(b)) {
			return true;
		}
		// Compare with hyphens and underscores stripped
		return a.toUpperCase().replace("-", "").replace("_", "")
				.equals(b.toUpperCase().replace("-", "").replace("_", ""));
	}

	/**
	 * Checks if 'truncatedName' is an AS/400 DDS truncated form of 'longName'.
	 * AS/400 truncates field names > 10 chars to first 5 chars + 5-digit sequence.
	 * This method checks prefix match only (caller handles sequence counting).
	 */
	private boolean isDdsTruncatedMatch(final String truncatedName, final String longName) {
		if (truncatedName == null || longName == null) {
			return false;
		}
		final String truncUpper = truncatedName.toUpperCase();
		// The truncated name is exactly 10 chars (with underscores/hyphens), ending with 5 digits
		if (truncUpper.length() != 10 || longName.length() <= 10) {
			return false;
		}
		if (!truncUpper.matches("[A-Z0-9_-]{5}\\d{5}")) {
			return false;
		}
		// Compare first 5 chars, normalizing hyphens to underscores
		final String truncPrefix = truncUpper.substring(0, 5).replace("-", "_");
		final String longPrefix = longName.toUpperCase().substring(0, Math.min(5, longName.length())).replace("-", "_");
		return truncPrefix.equals(longPrefix);
	}

	private DataDescriptionEntry searchEntries(final String name,
			final io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryContainer container) {
		if (container == null) {
			return null;
		}
		// Pass 1: exact case-insensitive match (no hyphen stripping).
		// This prevents WURL matching W-URL when both exist as distinct fields.
		for (final DataDescriptionEntry entry : container.getDataDescriptionEntries()) {
			if (name.equalsIgnoreCase(entry.getName())) {
				return entry;
			}
			if (entry instanceof DataDescriptionEntryGroup) {
				final DataDescriptionEntry found = searchInGroupExact(name, (DataDescriptionEntryGroup) entry);
				if (found != null) {
					return found;
				}
			}
		}
		// Pass 2: hyphen-insensitive match (e.g., CD-TRANS matches CDTRANS)
		for (final DataDescriptionEntry entry : container.getDataDescriptionEntries()) {
			if (namesMatchHyphenInsensitive(name, entry.getName())) {
				return entry;
			}
			if (entry instanceof DataDescriptionEntryGroup) {
				final DataDescriptionEntry found = searchInGroup(name, (DataDescriptionEntryGroup) entry);
				if (found != null) {
					return found;
				}
			}
		}
		// Pass 3: DDS truncated name matching (e.g., DATDR00001 → DATDRELINAC)
		final String nameStripped = name.toUpperCase().replace("-", "").replace("_", "");
		if (nameStripped.length() == 10 && nameStripped.matches("[A-Z]{5}\\d{5}")) {
			final String prefix = nameStripped.substring(0, 5);
			final int targetSeq = Integer.parseInt(nameStripped.substring(5));
			int seq = 0;
			for (final DataDescriptionEntry entry : container.getDataDescriptionEntries()) {
				if (isDdsTruncatedMatch(name, entry.getName())) {
					seq++;
					if (seq == targetSeq) {
						return entry;
					}
				}
				if (entry instanceof DataDescriptionEntryGroup) {
					final DataDescriptionEntry found = searchInGroupTruncated(name, (DataDescriptionEntryGroup) entry);
					if (found != null) {
						return found;
					}
				}
			}
		}
		return null;
	}

	private DataDescriptionEntry searchInGroup(final String name, final DataDescriptionEntryGroup group) {
		// Pass 1: exact match (case-insensitive) to avoid collisions like CDTRANS vs CD-TRANS
		for (final DataDescriptionEntry entry : group.getDataDescriptionEntries()) {
			if (name.equalsIgnoreCase(entry.getName())) {
				return entry;
			}
			if (entry instanceof DataDescriptionEntryGroup) {
				final DataDescriptionEntry found = searchInGroupExact(name, (DataDescriptionEntryGroup) entry);
				if (found != null) {
					return found;
				}
			}
		}
		// Pass 2: hyphen-insensitive match (e.g., CD-TRANS matches CDTRANS)
		for (final DataDescriptionEntry entry : group.getDataDescriptionEntries()) {
			if (namesMatchHyphenInsensitive(name, entry.getName())) {
				return entry;
			}
			if (entry instanceof DataDescriptionEntryGroup) {
				final DataDescriptionEntry found = searchInGroup(name, (DataDescriptionEntryGroup) entry);
				if (found != null) {
					return found;
				}
			}
		}
		// Fallback: DDS truncated name matching (e.g., EXTPA00001 → EXTPARTNERID)
		final DataDescriptionEntry truncated = searchInGroupTruncated(name, group);
		if (truncated != null) {
			return truncated;
		}
		return null;
	}

	private DataDescriptionEntry searchInGroupExact(final String name, final DataDescriptionEntryGroup group) {
		for (final DataDescriptionEntry entry : group.getDataDescriptionEntries()) {
			if (name.equalsIgnoreCase(entry.getName())) {
				return entry;
			}
			if (entry instanceof DataDescriptionEntryGroup) {
				final DataDescriptionEntry found = searchInGroupExact(name, (DataDescriptionEntryGroup) entry);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	/**
	 * Searches within a group for a field matching via DDS truncated name convention.
	 */
	private DataDescriptionEntry searchInGroupTruncated(final String name, final DataDescriptionEntryGroup group) {
		final String nameStripped = name.toUpperCase().replace("-", "").replace("_", "");
		if (nameStripped.length() != 10 || !nameStripped.matches("[A-Z]{5}\\d{5}")) {
			return null;
		}
		final String prefix = nameStripped.substring(0, 5);
		final int targetSeq = Integer.parseInt(nameStripped.substring(5));
		int seq = 0;
		for (final DataDescriptionEntry entry : group.getDataDescriptionEntries()) {
			if (isDdsTruncatedMatch(name, entry.getName())) {
				seq++;
				if (seq == targetSeq) {
					return entry;
				}
			}
			if (entry instanceof DataDescriptionEntryGroup) {
				final DataDescriptionEntry found = searchInGroupTruncated(name, (DataDescriptionEntryGroup) entry);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	/**
	 * Builds a qualified Java path from a DataDescriptionEntry by walking up the parent hierarchy.
	 * If the entry belongs to a FileDescriptionEntry (FD), prepends the FD content identifier.
	 */
	private String buildQualifiedPath(final DataDescriptionEntry entry) {
		final List<String> parts = new ArrayList<>();
		DataDescriptionEntry current = entry;
		while (current != null) {
			parts.add(javaVariableIdentifierService.mapToIdentifier(current));
			current = current.getParentDataDescriptionEntryGroup();
		}
		Collections.reverse(parts);

		// Check if the topmost entry belongs to a FileDescriptionEntry by walking the ANTLR context
		DataDescriptionEntry topEntry = entry;
		while (topEntry.getParentDataDescriptionEntryGroup() != null) {
			topEntry = topEntry.getParentDataDescriptionEntryGroup();
		}
		if (topEntry.getCtx() != null) {
			org.antlr.v4.runtime.tree.ParseTree ctx = topEntry.getCtx().getParent();
			while (ctx != null) {
				if (ctx instanceof io.proleap.cobol.CobolParser.FileDescriptionEntryContext) {
					// Find the FD entry in the program to get its identifier
					final io.proleap.cobol.asg.metamodel.Program program = entry.getProgram();
					if (program != null) {
						for (final var cu : program.getCompilationUnits()) {
							for (final var pu : cu.getProgramUnits()) {
								if (pu.getDataDivision() != null && pu.getDataDivision().getFileSection() != null) {
									for (final io.proleap.cobol.asg.metamodel.data.file.FileDescriptionEntry fde : pu.getDataDivision().getFileSection().getFileDescriptionEntries()) {
										if (fde.getCtx() == ctx) {
											parts.add(0, javaFileDescriptionEntryIdentifierService.mapToIdentifier(fde));
											return String.join(".", parts);
										}
									}
								}
							}
						}
					}
					break;
				}
				ctx = (ctx instanceof org.antlr.v4.runtime.ParserRuleContext) ? ((org.antlr.v4.runtime.ParserRuleContext) ctx).getParent() : null;
			}
		}

		return String.join(".", parts);
	}

	/**
	 * Resolves group fields for a SELECT INTO target (returns the field names of the group's children).
	 */
	private List<String> resolveGroupFields(final String cobolName, final RuleContext rc) {
		final Program program = rc.getProgram();
		if (program == null) {
			return null;
		}

		for (final var cu : program.getCompilationUnits()) {
			for (final var pu : cu.getProgramUnits()) {
				final DataDescriptionEntry found = findDataDescriptionEntry(cobolName, pu.getDataDivision());
				if (found instanceof DataDescriptionEntryGroup) {
					final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) found;
					final List<String> fields = new ArrayList<>();
					collectLeafFields(group, fields);
					return fields;
				}
			}
		}
		return null;
	}

	/**
	 * Collects all leaf (non-group) field identifiers from a group, recursively.
	 */
	private void collectLeafFields(final DataDescriptionEntryGroup group, final List<String> fields) {
		for (final DataDescriptionEntry child : group.getDataDescriptionEntries()) {
			if (child.getDataDescriptionEntryType() == DataDescriptionEntryType.CONDITION) {
				continue;
			}
			if (child instanceof DataDescriptionEntryGroup) {
				final DataDescriptionEntryGroup childGroup = (DataDescriptionEntryGroup) child;
				if (childGroup.getDataDescriptionEntries().isEmpty()) {
					fields.add(javaVariableIdentifierService.mapToIdentifier(child));
				} else {
					collectLeafFields(childGroup, fields);
				}
			} else {
				fields.add(javaVariableIdentifierService.mapToIdentifier(child));
			}
		}
	}

	/**
	 * Resolves group fields as DataDescriptionEntry objects (for type detection).
	 */
	private List<DataDescriptionEntry> resolveGroupFieldEntries(final String cobolName, final RuleContext rc) {
		final Program program = rc.getProgram();
		if (program == null) {
			return null;
		}
		for (final var cu : program.getCompilationUnits()) {
			for (final var pu : cu.getProgramUnits()) {
				final DataDescriptionEntry found = findDataDescriptionEntry(cobolName, pu.getDataDivision());
				if (found instanceof DataDescriptionEntryGroup) {
					final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) found;
					final List<DataDescriptionEntry> entries = new ArrayList<>();
					collectLeafFieldEntries(group, entries);
					return entries;
				}
			}
		}
		return null;
	}

	private void collectLeafFieldEntries(final DataDescriptionEntryGroup group, final List<DataDescriptionEntry> entries) {
		for (final DataDescriptionEntry child : group.getDataDescriptionEntries()) {
			if (child.getDataDescriptionEntryType() == DataDescriptionEntryType.CONDITION) {
				continue;
			}
			if (child instanceof DataDescriptionEntryGroup) {
				final DataDescriptionEntryGroup childGroup = (DataDescriptionEntryGroup) child;
				if (childGroup.getDataDescriptionEntries().isEmpty()) {
					entries.add(child);
				} else {
					collectLeafFieldEntries(childGroup, entries);
				}
			} else {
				entries.add(child);
			}
		}
	}

	/**
	 * Represents a single DB column mapping for multi-row FETCH.
	 * For VARCHAR groups, a single column maps to both LENGTH and DATA sub-fields.
	 * For regular fields, it maps to a single leaf field.
	 */
	private static final class FetchColumnMapping {
		final boolean isVarchar;
		final boolean isNumeric;
		final String fetchPath;    // for non-varchar
		final String lengthPath;   // for varchar
		final String dataPath;     // for varchar

		FetchColumnMapping(final String fetchPath, final boolean isNumeric) {
			this.isVarchar = false;
			this.isNumeric = isNumeric;
			this.fetchPath = fetchPath;
			this.lengthPath = null;
			this.dataPath = null;
		}

		FetchColumnMapping(final String lengthPath, final String dataPath) {
			this.isVarchar = true;
			this.isNumeric = false;
			this.fetchPath = null;
			this.lengthPath = lengthPath;
			this.dataPath = dataPath;
		}
	}

	/**
	 * Counts the number of columns in the SELECT list of a cursor's SQL.
	 * Returns -1 if the cursor is not found, uses SELECT *, or the SQL cannot be parsed.
	 * When the result is -1, callers should not limit the number of rs.get() calls.
	 */
	private int countSelectColumns(final String cursorName) {
		final CursorDeclaration decl = declaredCursors.get(cursorName);
		if (decl == null) {
			return -1;
		}
		final String sql = decl.parameterizedSql;
		if (sql == null) {
			return -1;
		}
		// Find the SELECT ... FROM portion (case-insensitive)
		final java.util.regex.Matcher selMatcher = Pattern
				.compile("(?i)\\bSELECT\\s+(.*?)\\s+FROM\\b")
				.matcher(sql);
		if (!selMatcher.find()) {
			return -1;
		}
		final String columnList = selMatcher.group(1).trim();
		// SELECT * or SELECT alias.* means all columns — don't limit
		if (columnList.equals("*") || columnList.matches("\\w+\\.\\*")) {
			return -1;
		}
		// Count columns by splitting on commas, but respect parenthesized expressions
		// like COALESCE(A, B) which contain commas inside parens
		int count = 0;
		int depth = 0;
		for (int i = 0; i < columnList.length(); i++) {
			final char c = columnList.charAt(i);
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
			} else if (c == ',' && depth == 0) {
				count++;
			}
		}
		return count + 1; // number of commas + 1 = number of columns
	}

	/**
	 * Collects column mappings for multi-row FETCH, treating VARCHAR sub-groups
	 * (LENGTH + DATA children) as single DB columns instead of expanding to two leaf fields.
	 */
	private void collectFetchColumnMappings(final DataDescriptionEntryGroup group,
			final List<FetchColumnMapping> mappings, final String cursorName) {
		for (final DataDescriptionEntry child : group.getDataDescriptionEntries()) {
			if (child.getDataDescriptionEntryType() == DataDescriptionEntryType.CONDITION) {
				continue;
			}
			if (child instanceof DataDescriptionEntryGroup) {
				final DataDescriptionEntryGroup childGroup = (DataDescriptionEntryGroup) child;
				if (isVarcharGroup(childGroup)) {
					// VARCHAR group: single DB column populates both LENGTH and DATA sub-fields
					final List<DataDescriptionEntry> vcChildren = new ArrayList<>();
					for (final DataDescriptionEntry vcChild : childGroup.getDataDescriptionEntries()) {
						if (vcChild.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION) {
							vcChildren.add(vcChild);
						}
					}
					final String lengthPath = buildFetchElementPath(vcChildren.get(0), cursorName);
					final String dataPath = buildFetchElementPath(vcChildren.get(1), cursorName);
					mappings.add(new FetchColumnMapping(lengthPath, dataPath));
				} else if (childGroup.getDataDescriptionEntries().isEmpty()) {
					final String fetchPath = buildFetchElementPath(child, cursorName);
					mappings.add(new FetchColumnMapping(fetchPath, isNumericEntry(child)));
				} else {
					// Recurse into non-VARCHAR sub-groups
					collectFetchColumnMappings(childGroup, mappings, cursorName);
				}
			} else {
				final String fetchPath = buildFetchElementPath(child, cursorName);
				mappings.add(new FetchColumnMapping(fetchPath, isNumericEntry(child)));
			}
		}
	}

	/**
	 * Detects if a group entry represents a DB2 VARCHAR host variable structure.
	 * A VARCHAR group has exactly two non-condition children: *-LENGTH (numeric) and *-DATA (alphanumeric).
	 */
	private boolean isVarcharGroup(final DataDescriptionEntry entry) {
		if (!(entry instanceof DataDescriptionEntryGroup)) {
			return false;
		}
		final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) entry;
		final List<DataDescriptionEntry> children = new ArrayList<>();
		for (final DataDescriptionEntry child : group.getDataDescriptionEntries()) {
			if (child.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION) {
				children.add(child);
			}
		}
		if (children.size() != 2) {
			return false;
		}
		final String name0 = children.get(0).getName();
		final String name1 = children.get(1).getName();
		if (name0 == null || name1 == null) {
			return false;
		}
		// Check for *-LENGTH / *-DATA or *-L / *-V pattern (case-insensitive)
		// The *-L / *-V pattern is used by IBM ILE COBOL for level-49 VARCHAR host variables
		final String upper0 = name0.toUpperCase();
		final String upper1 = name1.toUpperCase();
		return ((upper0.endsWith("-LENGTH") && upper1.endsWith("-DATA"))
				|| (upper0.endsWith("-L") && upper1.endsWith("-V")))
				&& isNumericEntry(children.get(0));
	}

	/**
	 * Expands a group entry for SELECT INTO result mapping, treating VARCHAR sub-groups
	 * as single DB columns instead of expanding to two leaf fields.
	 * Each entry in resolvedIntoVars has:
	 *   [0]=resolvedJavaPath, [1]=type ("string"|"numeric"|"varchar"),
	 *   [2]=lengthPath (varchar only), [3]=dataPath (varchar only),
	 *   [last]=COBOL column name (upper-cased, for column-name-based RS access)
	 */
	private void expandGroupForSelectInto(final DataDescriptionEntry groupEntry, final String resolvedParent,
			final List<String[]> resolvedIntoVars, final RuleContext rc) {
		if (!(groupEntry instanceof DataDescriptionEntryGroup)) {
			final String cobolName = groupEntry.getName() != null ? groupEntry.getName().toUpperCase().replace("-", "_") : null;
			resolvedIntoVars.add(new String[]{resolvedParent, isNumericEntry(groupEntry) ? "numeric" : "string", cobolName});
			return;
		}
		// If the entry itself is a VARCHAR group (e.g., a single :HOST.COMMUNE in the INTO list),
		// treat it as one DB column instead of expanding its LENGTH+DATA children as two columns.
		if (isVarcharGroup(groupEntry)) {
			final List<DataDescriptionEntry> vcChildren = new ArrayList<>();
			for (final DataDescriptionEntry vcChild : ((DataDescriptionEntryGroup) groupEntry).getDataDescriptionEntries()) {
				if (vcChild.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION) {
					vcChildren.add(vcChild);
				}
			}
			final String lengthPath = resolvedParent + "." + javaVariableIdentifierService.mapToIdentifier(vcChildren.get(0));
			final String dataPath = resolvedParent + "." + javaVariableIdentifierService.mapToIdentifier(vcChildren.get(1));
			final String cobolName = groupEntry.getName() != null ? groupEntry.getName().toUpperCase().replace("-", "_") : null;
			resolvedIntoVars.add(new String[]{resolvedParent, "varchar", lengthPath, dataPath, cobolName});
			return;
		}
		final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) groupEntry;
		for (final DataDescriptionEntry child : group.getDataDescriptionEntries()) {
			if (child.getDataDescriptionEntryType() == DataDescriptionEntryType.CONDITION) {
				continue;
			}
			if (child instanceof DataDescriptionEntryGroup) {
				final DataDescriptionEntryGroup childGroup = (DataDescriptionEntryGroup) child;
				if (isVarcharGroup(childGroup)) {
					// VARCHAR group: treat as single DB column with special "varchar" type hint
					// Find the LENGTH and DATA sub-field paths
					final List<DataDescriptionEntry> vcChildren = new ArrayList<>();
					for (final DataDescriptionEntry vcChild : childGroup.getDataDescriptionEntries()) {
						if (vcChild.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION) {
							vcChildren.add(vcChild);
						}
					}
					final String childPath = resolvedParent + "." + javaVariableIdentifierService.mapToIdentifier(childGroup);
					final String lengthPath = childPath + "." + javaVariableIdentifierService.mapToIdentifier(vcChildren.get(0));
					final String dataPath = childPath + "." + javaVariableIdentifierService.mapToIdentifier(vcChildren.get(1));
					final String cobolName = childGroup.getName() != null ? childGroup.getName().toUpperCase().replace("-", "_") : null;
					resolvedIntoVars.add(new String[]{childPath, "varchar", lengthPath, dataPath, cobolName});
				} else if (childGroup.getDataDescriptionEntries().isEmpty()) {
					final String relPath = buildRelativePath(child, groupEntry);
					final String cobolName = child.getName() != null ? child.getName().toUpperCase().replace("-", "_") : null;
					resolvedIntoVars.add(new String[]{resolvedParent + "." + relPath, isNumericEntry(child) ? "numeric" : "string", cobolName});
				} else {
					// Recurse into non-VARCHAR sub-groups
					final String childPath = resolvedParent + "." + javaVariableIdentifierService.mapToIdentifier(childGroup);
					expandGroupForSelectInto(childGroup, childPath, resolvedIntoVars, rc);
				}
			} else {
				final String relPath = buildRelativePath(child, groupEntry);
				final String cobolName = child.getName() != null ? child.getName().toUpperCase().replace("-", "_") : null;
				resolvedIntoVars.add(new String[]{resolvedParent + "." + relPath, isNumericEntry(child) ? "numeric" : "string", cobolName});
			}
		}
	}

	/**
	 * Determines if a DataDescriptionEntry represents a numeric field (COMP, COMP-3, S9 PIC).
	 */
	private boolean isNumericEntry(final DataDescriptionEntry entry) {
		if (entry instanceof DataDescriptionEntryGroup) {
			final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) entry;
			final var picClause = group.getPictureClause();
			if (picClause != null) {
				final String pic = picClause.getPictureString().toUpperCase();
				return pic.contains("9") && !pic.contains("X");
			}
		}
		return false;
	}

	/**
	 * Finds the DataDescriptionEntry for a host variable name.
	 */
	private DataDescriptionEntry findEntryForVar(final String cobolName, final RuleContext rc) {
		final Program program = rc.getProgram();
		if (program == null) {
			return null;
		}
		// When the host variable is qualified (e.g., LKTJ0032.NOME), resolve the leaf
		// within the specific parent group to avoid matching a same-named field in a
		// different record (e.g., a VARCHAR group NOME in JRNL003200 vs a simple PIC X
		// NOME in LKTJ0032).
		if (cobolName.contains(".")) {
			final String parentName = cobolName.substring(0, cobolName.indexOf('.'));
			final String leafName = cobolName.substring(cobolName.lastIndexOf('.') + 1);
			for (final var cu : program.getCompilationUnits()) {
				for (final var pu : cu.getProgramUnits()) {
					final DataDescriptionEntry parentEntry = findDataDescriptionEntry(parentName, pu.getDataDivision());
					if (parentEntry instanceof DataDescriptionEntryGroup) {
						final DataDescriptionEntry found = searchInGroup(leafName, (DataDescriptionEntryGroup) parentEntry);
						if (found != null) {
							return found;
						}
					}
				}
			}
			// Fall through to global search if qualified lookup didn't find anything
		}
		final String simpleName = cobolName.contains(".") ? cobolName.substring(cobolName.lastIndexOf('.') + 1) : cobolName;
		for (final var cu : program.getCompilationUnits()) {
			for (final var pu : cu.getProgramUnits()) {
				final DataDescriptionEntry found = findDataDescriptionEntry(simpleName, pu.getDataDivision());
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	/**
	 * Emits a null-indicator assignment for a single column in a multi-row FETCH.
	 * Must be called immediately after the column value is read from the ResultSet,
	 * because rs.wasNull() only reflects the last column read.
	 *
	 * COBOL indicator structure: IND-ARRAY OCCURS n TIMES -> INDS PIC S9(4) OCCURS m TIMES
	 * Generated as: indRowPath.indChildName[colOffset] = rs_cursor.wasNull() ? -1 : 0
	 *
	 * @param indChildOccursSize the OCCURS size of the indicator child array (-1 if unknown).
	 *        When colOffset >= indChildOccursSize, the assignment is skipped to avoid
	 *        ArrayIndexOutOfBoundsException (COBOL only maps indicators up to the OCCURS limit).
	 */
	private void emitIndicatorAssignment(final RuleContext rc, final String indRowPath,
			final String indChildName, final boolean indChildIsArray,
			final int colOffset, final String cursorName, final int indChildOccursSize) {
		if (indChildName != null) {
			// Skip indicator assignment if column index exceeds the indicator array OCCURS size
			if (indChildOccursSize > 0 && colOffset >= indChildOccursSize) {
				return;
			}
			if (indChildIsArray) {
				// OCCURS leaf generates BigDecimal[] — use array indexing
				rc.p("%s.%s[%d] = rs_%s.wasNull() ? BigDecimal.valueOf(-1) : BigDecimal.ZERO;",
						indRowPath, indChildName, colOffset, cursorName);
			} else {
				// OCCURS group generates List — use .set()
				rc.p("%s.%s.set(%d, rs_%s.wasNull() ? BigDecimal.valueOf(-1) : BigDecimal.ZERO);",
						indRowPath, indChildName, colOffset, cursorName);
			}
		} else {
			// Flat indicator: set directly on the row element
			rc.p("%s = rs_%s.wasNull() ? BigDecimal.valueOf(-1) : BigDecimal.ZERO;",
					indRowPath, cursorName);
		}
	}

	/**
	 * Returns the OCCURS size (max count) for a DataDescriptionEntryGroup, or -1 if unknown.
	 * For OCCURS 20 TIMES, returns 20.
	 */
	private int getOccursSize(final DataDescriptionEntryGroup grp) {
		if (grp == null || grp.getOccursClauses() == null || grp.getOccursClauses().isEmpty()) {
			return -1;
		}
		for (final OccursClause occursClause : grp.getOccursClauses()) {
			final ValueStmt fromValueStmt = occursClause.getFrom();
			if (fromValueStmt instanceof IntegerLiteralValueStmt) {
				final IntegerLiteralValueStmt fromIntLit = (IntegerLiteralValueStmt) fromValueStmt;
				final IntegerLiteral from = fromIntLit.getLiteral();
				final int fromValue = (from == null) ? 0 : from.getValue().intValue();
				final IntegerLiteral to = occursClause.getTo();
				final int toValue = (to == null) ? 0 : to.getValue().intValue();
				return Math.max(fromValue, toValue);
			}
		}
		return -1;
	}

	/**
	 * Builds a qualified Java path for a DataDescriptionEntry, inserting .get(fetchIdx_CURSOR)
	 * at the OCCURS level so multi-row FETCH assigns to the correct array element.
	 */
	private String buildFetchElementPath(final DataDescriptionEntry entry, final String cursorName) {
		final List<DataDescriptionEntry> hierarchy = new ArrayList<>();
		DataDescriptionEntry current = entry;
		while (current != null) {
			hierarchy.add(current);
			current = current.getParentDataDescriptionEntryGroup();
		}
		Collections.reverse(hierarchy);

		final StringBuilder result = new StringBuilder();
		for (int i = 0; i < hierarchy.size(); i++) {
			if (i > 0) {
				result.append(".");
			}
			result.append(javaVariableIdentifierService.mapToIdentifier(hierarchy.get(i)));

			// Insert .get(fetchIdx) at the OCCURS level
			if (hierarchy.get(i) instanceof DataDescriptionEntryGroup) {
				final DataDescriptionEntryGroup grp = (DataDescriptionEntryGroup) hierarchy.get(i);
				if (grp.getOccursClauses() != null && !grp.getOccursClauses().isEmpty()) {
					result.append(".get(fetchIdx_").append(cursorName).append(")");
				}
			}
		}
		return result.toString();
	}

	/**
	 * Builds the relative path from a top-level group entry to a leaf entry.
	 * For direct children, returns just the field name (e.g., "codsoc").
	 * For nested groups (e.g., VARCHAR), includes intermediate groups (e.g., "desclong.desclong_length").
	 */
	private String buildRelativePath(final DataDescriptionEntry leaf, final DataDescriptionEntry topGroup) {
		final List<String> segments = new ArrayList<>();
		DataDescriptionEntry current = leaf;
		while (current != null && current != topGroup) {
			segments.add(javaVariableIdentifierService.mapToIdentifier(current));
			current = current.getParentDataDescriptionEntryGroup();
		}
		Collections.reverse(segments);
		return String.join(".", segments);
	}

	/**
	 * Finds the DataDescriptionEntry for a resolved indicator path (e.g., "tb_ind.ind_array").
	 * Searches for the last segment in the data division.
	 */
	private DataDescriptionEntry findIndicatorEntry(final String resolvedPath, final RuleContext rc) {
		// Extract the leaf field name from the resolved path
		final String leaf = resolvedPath.contains(".") ? resolvedPath.substring(resolvedPath.lastIndexOf('.') + 1) : resolvedPath;
		// Convert Java identifier back to COBOL-style name for lookup (underscore → hyphen)
		final String cobolName = leaf.replace("_", "-");
		DataDescriptionEntry entry = findEntryForVar(cobolName, rc);
		if (entry == null) {
			entry = findEntryForVar(leaf, rc);
		}
		return entry;
	}

	/**
	 * Extracts the FETCH orientation from SQL text and returns the corresponding
	 * JDBC ResultSet navigation method name.
	 * <p>
	 * FETCH NEXT → "next", FETCH PRIOR → "previous", FETCH FIRST → "first",
	 * FETCH LAST → "last", FETCH (no orientation) → "next" (default).
	 * </p>
	 */
	private String fetchOrientationMethod(final String fetchSql) {
		final java.util.regex.Matcher m = Pattern
				.compile("(?i)FETCH\\s+(NEXT|PRIOR|FIRST|LAST)\\b").matcher(fetchSql);
		if (m.find()) {
			final String orientation = m.group(1).toUpperCase();
			switch (orientation) {
				case "PRIOR":
					return "previous";
				case "FIRST":
					return "first";
				case "LAST":
					return "last";
				default:
					return "next";
			}
		}
		return "next"; // default: FETCH without orientation keyword
	}

	/** SQL reserved words that must be quoted when used as column/table names. */
	private static final java.util.Set<String> SQL_RESERVED_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
			"ADD", "ALL", "ALTER", "AND", "ANY", "AS", "ASC", "BETWEEN", "BY", "CASE", "CHECK",
			"COLUMN", "CONSTRAINT", "CREATE", "CROSS", "CURRENT", "CURRENT_DATE", "CURRENT_TIME",
			"CURRENT_USER", "CURSOR", "DATABASE", "DEFAULT", "DELETE", "DESC",
			"DISTINCT", "DROP", "ELSE", "END", "ESCAPE", "EXCEPT", "EXISTS", "FETCH", "FOR",
			"FOREIGN", "FROM", "FULL", "GRANT", "GROUP", "HAVING", "IN", "INDEX", "INNER", "INSERT",
			"INTERSECT", "INTO", "IS", "JOIN", "KEY", "LEFT", "LEVEL", "LIKE", "LIMIT", "NOT", "NULL",
			"OF", "ON", "OPEN", "OR", "ORDER", "OUTER", "PRIMARY", "REFERENCES", "REVOKE", "RIGHT",
			"ROLLBACK", "ROW", "ROWS", "SELECT", "SESSION", "SET", "TABLE", "THEN", "TO",
			"TRANSACTION", "TRIGGER", "UNION", "UNIQUE", "UPDATE", "USING", "VALUES",
			"VIEW", "WHEN", "WHERE", "WITH", "WORK", "YEAR", "ZONE"
	));

	/**
	 * Quotes SQL reserved words that appear as unquoted identifiers (column/table names)
	 * in the given SQL string. Only quotes words that appear in identifier positions
	 * (preceded by SET, comma, dot, or opening paren in UPDATE/INSERT contexts, or as
	 * column names after SELECT or in column lists).
	 * <p>
	 * Uses a conservative approach: only quotes a word boundary match of a known reserved
	 * word when it appears as a column name (after SET, comma, or as part of column = value).
	 * </p>
	 */
	private String quoteReservedColumnNames(final String sql) {
		// Quote reserved words that appear as column identifiers in SET clause (UPDATE ... SET col = ...)
		// Pattern: word boundary + reserved word + optional whitespace + = (but not ==)
		String result = sql;
		for (final String word : SQL_RESERVED_WORDS) {
			// Match the reserved word when used as a column name before = in SET clause
			// or after comma/SET keyword. Case-insensitive.
			result = result.replaceAll(
					"(?i)\\b(" + word + ")\\b(\\s*=\\s*(?!=))",
					"\"$1\"$2");
			// Match the reserved word when used as a column name after a dot (table.DESC)
			result = result.replaceAll(
					"(?i)(\\.)(" + word + ")\\b(\\s*=\\s*(?!=))",
					"$1\"$2\"$3");
			// Match in column lists: after comma or opening paren when followed by comma or closing paren
			// (for INSERT INTO table (DESC, ...) patterns)
			result = result.replaceAll(
					"(?i)([,(]\\s*)\\b(" + word + ")\\b(\\s*[,)])",
					"$1\"$2\"$3");
		}
		return result;
	}

	@Override
	public Class<ExecSqlStatementContext> from() {
		return ExecSqlStatementContext.class;
	}

	private String escapeSql(final String sql) {
		String cleaned = sql;
		// Strip EXEC SQL / END-EXEC markers (in case any remain)
		cleaned = cleaned.replaceAll("(?i)^\\s*EXEC\\s+SQL\\s+", "");
		cleaned = cleaned.replaceAll("(?i)\\s*END-EXEC\\s*$", "");
		// DB2/400 accepts double-quoted string literals, but JDBC/standard SQL requires single quotes
		// (double quotes denote identifiers in standard SQL). Convert "value" → 'value'.
		// But preserve double-quoted SQL identifiers (reserved words used as column names like "DESC").
		final StringBuffer sb = new StringBuffer();
		final java.util.regex.Matcher dqMatcher = Pattern.compile("\"([^\"]*)\"").matcher(cleaned);
		while (dqMatcher.find()) {
			final String content = dqMatcher.group(1);
			if (SQL_RESERVED_WORDS.contains(content.toUpperCase())) {
				// Preserve double quotes for SQL identifier quoting of reserved words
				dqMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("\"" + content + "\""));
			} else {
				// Convert to single quotes (DB2/400 string literal)
				dqMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("'" + content + "'"));
			}
		}
		dqMatcher.appendTail(sb);
		cleaned = sb.toString();
		// Remove COBOL inline comments (*>)
		cleaned = cleaned.replaceAll("\\*>.*?(\\n|$)", " ");
		return cleaned.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "").trim();
	}
}
