package io.proleap.cobol.runtime.impl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.proleap.cobol.runtime.DebugFlags;
import io.proleap.cobol.runtime.SqlService;

/**
 * JDBC-based implementation of SqlService.
 * Wraps java.sql operations and manages SQLCODE/SQLSTATE.
 */
public class SqlServiceImpl implements SqlService {

	private static final Logger LOG = LoggerFactory.getLogger(SqlServiceImpl.class);

	/**
	 * SQL-trace flag: when {@code -Dcobol.sql.trace=true} (or the master
	 * {@code -Dcobol.debug=true}) is set on the JVM, every EXEC SQL (prepare,
	 * open cursor, fetch, execute, commit/rollback) is logged to
	 * {@code System.err} with the final SQL, bound parameters and resulting
	 * row count. Off by default so normal runs are not spammed.
	 *
	 * The read happens once at class load — there is no runtime toggle.
	 * Call sites guard with {@link #isSqlTraceEnabled()} so the no-op path is
	 * a single static boolean read.
	 */
	static final boolean SQL_TRACE_ENABLED = DebugFlags.SQL_TRACE;

	/**
	 * Per-thread stack of CURRENT PROGRAM names, maintained by
	 * {@link ProgramRunnerImpl#call(String, Object...)} so that each SQL trace
	 * line can name the calling COBOL program (e.g. {@code prog=PAYROLL}).
	 * The stack lets nested CALLs attribute their SQL correctly.
	 *
	 * When {@link #SQL_TRACE_ENABLED} is false the stack is never touched.
	 */
	private static final ThreadLocal<java.util.Deque<String>> CURRENT_PROGRAM_STACK =
		ThreadLocal.withInitial(java.util.ArrayDeque::new);

	/**
	 * Push a program name onto the current-program stack. Called by
	 * {@link ProgramRunnerImpl} around each {@code procedureDivision()} invocation.
	 * No-op when SQL trace is disabled.
	 */
	public static void pushCurrentProgram(final String programName) {
		if (!SQL_TRACE_ENABLED) {
			return;
		}
		CURRENT_PROGRAM_STACK.get().push(programName == null ? "?" : programName);
	}

	/**
	 * Pop the most recently pushed program name. Called by
	 * {@link ProgramRunnerImpl} in the {@code finally} block of each CALL.
	 * No-op when SQL trace is disabled or the stack is empty.
	 */
	public static void popCurrentProgram() {
		if (!SQL_TRACE_ENABLED) {
			return;
		}
		final java.util.Deque<String> stack = CURRENT_PROGRAM_STACK.get();
		if (!stack.isEmpty()) {
			stack.pop();
		}
	}

	/**
	 * @return name of the currently-executing COBOL program, or {@code "?"} if
	 *         the stack is empty (e.g. SQL issued outside any CALL).
	 */
	private static String currentProgramName() {
		final java.util.Deque<String> stack = CURRENT_PROGRAM_STACK.get();
		return stack.isEmpty() ? "?" : stack.peek();
	}

	/**
	 * @return {@code true} when {@code -Dcobol.sql.trace=true} was set at JVM
	 *         start. Callers must guard every trace-logging site with this so
	 *         normal runs pay only a static-field read.
	 */
	private static boolean isSqlTraceEnabled() {
		return SQL_TRACE_ENABLED;
	}

	/**
	 * Best-effort classification of a SQL statement for the trace prefix
	 * ({@code type=SELECT}, {@code type=UPDATE}, etc.). Never throws; falls back
	 * to {@code OTHER} when the statement cannot be classified.
	 */
	private static String classifySql(final String sql) {
		if (sql == null) return "NULL";
		final String t = sql.trim();
		if (t.isEmpty()) return "EMPTY";
		final int sp = t.indexOf(' ');
		final String first = (sp > 0 ? t.substring(0, sp) : t).toUpperCase();
		switch (first) {
			case "SELECT":
			case "INSERT":
			case "UPDATE":
			case "DELETE":
			case "VALUES":
			case "CALL":
			case "MERGE":
				return first;
			default:
				return "OTHER";
		}
	}

	/**
	 * Format a parameter value for the trace log. Strings are single-quoted,
	 * {@code null} becomes {@code NULL}, everything else uses {@link String#valueOf(Object)}.
	 * Keeps long strings intact — the trace is for humans running queries
	 * against DB2/PG, not for pretty-printing.
	 */
	private static String formatTraceParam(final Object value) {
		if (value == null) return "NULL";
		if (value instanceof String) return "'" + ((String) value) + "'";
		if (value instanceof Character) return "'" + value + "'";
		return String.valueOf(value);
	}

	/**
	 * Short tag for a Java parameter object — e.g. {@code VARCHAR} for String,
	 * {@code NUMERIC} for BigDecimal, {@code NULL} for nulls. Used only in the
	 * trace prefix to help readers map bind slots to column types when running
	 * the queries manually.
	 */
	private static String traceParamTypeTag(final Object value) {
		if (value == null) return "NULL";
		if (value instanceof String) return "VARCHAR";
		if (value instanceof java.math.BigDecimal) return "NUMERIC";
		if (value instanceof Integer || value instanceof Long
				|| value instanceof Short || value instanceof Byte) {
			return "INTEGER";
		}
		if (value instanceof Double || value instanceof Float) return "FLOAT";
		if (value instanceof Boolean) return "BOOLEAN";
		if (value instanceof java.sql.Date) return "DATE";
		if (value instanceof java.sql.Time) return "TIME";
		if (value instanceof java.sql.Timestamp) return "TIMESTAMP";
		return value.getClass().getSimpleName().toUpperCase();
	}

	/**
	 * Emit the {@code [SQL-TRACE] >>>} pre-execution line plus one line per
	 * bound parameter. Guard callers with {@link #isSqlTraceEnabled()} so this
	 * is not invoked on the happy path.
	 */
	private static void traceSqlBefore(final String phase, final String sql, final java.util.Map<Integer, Object> params) {
		System.err.println("[SQL-TRACE] >>> prog=" + currentProgramName()
			+ " phase=" + phase
			+ " type=" + classifySql(sql)
			+ " sql=" + sql);
		if (params != null && !params.isEmpty()) {
			// Params are keyed by JDBC parameter index (1-based); print in index order.
			final java.util.TreeMap<Integer, Object> ordered = new java.util.TreeMap<>(params);
			for (final java.util.Map.Entry<Integer, Object> e : ordered.entrySet()) {
				System.err.println("[SQL-TRACE]     param[" + e.getKey() + "] ("
					+ traceParamTypeTag(e.getValue()) + ") = " + formatTraceParam(e.getValue()));
			}
		}
	}

	/**
	 * Emit the {@code [SQL-TRACE] <<<} post-execution line.
	 * {@code rows} is the number of affected (update/delete) or returned rows.
	 */
	private static void traceSqlAfter(final String phase, final long rows) {
		System.err.println("[SQL-TRACE] <<< prog=" + currentProgramName()
			+ " phase=" + phase + " rows=" + rows);
	}

	/**
	 * Emit a simple {@code [SQL-TRACE]} info line (no params, no rows) — for
	 * events like COMMIT/ROLLBACK/CLOSE CURSOR where there is nothing to bind
	 * and no row count to report.
	 */
	private static void traceSqlEvent(final String event, final String detail) {
		System.err.println("[SQL-TRACE] " + event + " prog=" + currentProgramName()
			+ (detail == null ? "" : " " + detail));
	}

	private final DataSource dataSource;

	private int sqlCode = 0;

	private String sqlState = "00000";

	private int lastUpdateCount = 0;

	/**
	 * Connection for non-cursor (inline) SQL operations.
	 * Released and returned to the pool when the next operation starts or on close().
	 * This ensures at most 1 connection is held for transient SQL (SELECT INTO, INSERT, etc.).
	 */
	private Connection currentConnection;

	private final Map<String, PreparedStatement> cursorStatements = new HashMap<>();

	private final Map<String, ResultSet> cursorResultSets = new HashMap<>();

	/**
	 * Per-cursor connection tracking. Each open cursor holds its own connection
	 * from the pool; the connection is returned when the cursor is closed.
	 */
	private final Map<String, Connection> cursorConnections = new HashMap<>();

	private final Map<String, String> cursorSql = new HashMap<>();

	private final Map<String, String> namedStatements = new HashMap<>();

	private final Map<String, Boolean> cursorForUpdate = new HashMap<>();

	/**
	 * Per-cursor most-recently-fetched ctid (PostgreSQL physical row ID).
	 * Used to rewrite WHERE CURRENT OF <cursor> as WHERE ctid = ? in PG mode,
	 * because the PG JDBC driver does not support DB2/400's positioned-update
	 * protocol over the cursorName + CONCUR_UPDATABLE pathway.
	 *
	 * The ctid is read on demand from the cursor's ResultSet at UPDATE/DELETE time;
	 * this map is unused at present but reserved for future caching if needed.
	 */
	@SuppressWarnings("unused")
	private final Map<String, String> cursorCtids = new HashMap<>();

	/**
	 * When true, adaptSql() translates DB2/400 SQL to PostgreSQL-compatible SQL.
	 * Auto-detected from the JDBC connection metadata on first use.
	 */
	private Boolean postgresMode = null;

	/**
	 * Cache of table/view name -> primary-key / unique-index column list.
	 * Populated lazily from information_schema when PostgreSQL mode is active.
	 * Used by adaptSql() Rule 9 to inject ORDER BY for deterministic cursor results.
	 * A null value means "lookup not yet attempted"; empty string means "no PK found".
	 */
	private final Map<String, String> pkColumnsCache = new HashMap<>();

	public SqlServiceImpl() {
		this.dataSource = null;
	}

	public SqlServiceImpl(final DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/**
	 * Pattern to detect "WHERE CURRENT OF <cursor>" in UPDATE/DELETE statements.
	 * Group 1 is the cursor name. Used by prepareStatement() to rewrite the
	 * positioned-update clause when running against PostgreSQL.
	 */
	private static final Pattern WHERE_CURRENT_OF =
		Pattern.compile("(?i)\\bWHERE\\s+CURRENT\\s+OF\\s+(\\w+)\\b");

	/**
	 * Detect a "WHERE CURRENT OF <cursor>" clause and return the cursor name,
	 * or null if absent. Shared by both PG and DB2 paths in prepareStatement().
	 */
	private String detectPositionedCursorName(final String sql) {
		if (sql == null) return null;
		final java.util.regex.Matcher m = WHERE_CURRENT_OF.matcher(sql);
		return m.find() ? m.group(1) : null;
	}

	@Override
	public PreparedStatement prepareStatement(final String sql) {
		String fixedSql = fixQuotes(sql);
		// Detect "WHERE CURRENT OF <cursor>" once; both PG (rewrite to ctid)
		// and DB2 (route to cursor's connection) need the cursor name.
		final String positionedCursorName = detectPositionedCursorName(fixedSql);
		final boolean pg = isPostgresMode();
		// PG: rewrite positioned UPDATE/DELETE (WHERE CURRENT OF <cursor>) to WHERE ctid = ?::tid.
		// The PG JDBC driver does not implement DB2/400's positioned-update protocol,
		// so we use ctid (PG's physical row identifier) instead. The cursor's SELECT
		// has been augmented with ", ctid AS __ctid__" in openCursor(); the ctid for
		// the current row is read from the cursor's ResultSet at executeUpdate() time
		// and bound as the last parameter.
		if (pg && positionedCursorName != null) {
			fixedSql = WHERE_CURRENT_OF.matcher(fixedSql).replaceFirst("WHERE ctid = ?::tid");
			LOG.debug("PG positioned-update rewrite: cursor={} sql={}", positionedCursorName, fixedSql);
		}
		// DB2/AS400: jt400 supports WHERE CURRENT OF natively, but the UPDATE/DELETE
		// must run on the SAME physical Connection that opened the cursor. Otherwise
		// the driver cannot find the named cursor and returns SQLCODE -501.
		// We route prepareStatement onto the cursor's connection (no SQL rewrite),
		// and we DO NOT release that connection after the UPDATE — the cursor may
		// still be in use; releasing it would close the cursor and drop subsequent
		// FETCH results. The connection is returned to the pool by closeCursor().
		final boolean db2PositionedRoute = !pg && positionedCursorName != null;
		if (db2PositionedRoute) {
			// Cursor names are case-insensitive in SQL; normalise the lookup.
			Connection cursorConn = cursorConnections.get(positionedCursorName);
			if (cursorConn == null) {
				for (final Map.Entry<String, Connection> e : cursorConnections.entrySet()) {
					if (e.getKey() != null && e.getKey().equalsIgnoreCase(positionedCursorName)) {
						cursorConn = e.getValue();
						break;
					}
				}
			}
			if (cursorConn == null) {
				// Fail-fast: silent fall-through to currentConnection would just
				// reproduce the original -501 with a confusing root cause.
				throw new RuntimeException(
					"WHERE CURRENT OF " + positionedCursorName
					+ ": cursor is not open on any connection — "
					+ "OPEN " + positionedCursorName + " must precede the positioned UPDATE/DELETE. SQL: " + sql);
			}
			try {
				if (ProgramRunnerImpl.DEBUG) {
					System.out.println("  ".repeat(Math.min(10, Thread.currentThread().getStackTrace().length - 5))
						+ "[SQL/CURSOR " + positionedCursorName + "] " + fixedSql.substring(0, Math.min(80, fixedSql.length())));
				}
				final long __sqlT0 = System.nanoTime();
				final java.sql.PreparedStatement __rawPs;
				try {
					__rawPs = cursorConn.prepareStatement(fixedSql);
				} finally {
					io.proleap.cobol.runtime.SqlTiming.addNanos(System.nanoTime() - __sqlT0);
				}
				final SafePreparedStatement sps = new SafePreparedStatement(
					__rawPs, fixedSql, false);
				// Intentionally NO connectionReleaser: the cursor still owns this connection.
				return sps;
			} catch (final SQLException e) {
				sqlCode = e.getErrorCode();
				sqlState = e.getSQLState();
				if (ProgramRunnerImpl.DEBUG) {
					System.out.println("  ".repeat(Math.min(10, Thread.currentThread().getStackTrace().length - 5))
						+ "[SQL ERROR/CURSOR " + positionedCursorName + "] " + e.getErrorCode() + ": " + e.getMessage());
				}
				throw new RuntimeException("prepareStatement (positioned, cursor=" + positionedCursorName + ") failed: " + sql, e);
			}
		}
		// Release previous non-cursor connection before acquiring a new one.
		// This ensures at most 1 transient connection is held at any time.
		releaseCurrentConnection();
		// Retry once with a fresh connection on failure (handles transient connection issues)
		for (int attempt = 0; attempt < 2; attempt++) {
			try {
				final Connection conn = getConnection();
				if (ProgramRunnerImpl.DEBUG) {
					System.out.println("  ".repeat(Math.min(10, Thread.currentThread().getStackTrace().length - 5)) + "[SQL] " + fixedSql.substring(0, Math.min(80, fixedSql.length())));
				}
				final long __sqlT0 = System.nanoTime();
				final java.sql.PreparedStatement __rawPs;
				try {
					__rawPs = conn.prepareStatement(fixedSql);
				} finally {
					io.proleap.cobol.runtime.SqlTiming.addNanos(System.nanoTime() - __sqlT0);
				}
				final SafePreparedStatement sps = new SafePreparedStatement(__rawPs, fixedSql, pg);
				sps.setConnectionReleaser(this::releaseCurrentConnection);
				if (pg && positionedCursorName != null) {
					sps.setPositionedCursor(positionedCursorName, this);
				}
				return sps;
			} catch (final SQLException e) {
				sqlCode = e.getErrorCode();
				sqlState = e.getSQLState();
				if (attempt == 0) {
					LOG.warn("prepareStatement failed (attempt 1), retrying with fresh connection: {} - SQLCODE={}", sql, sqlCode);
					// Force a new connection on retry
					releaseCurrentConnection();
				} else {
					if (ProgramRunnerImpl.DEBUG) {
						System.out.println("  ".repeat(Math.min(10, Thread.currentThread().getStackTrace().length - 5)) + "[SQL ERROR] " + e.getErrorCode() + ": " + e.getMessage());
					}
					throw new RuntimeException("prepareStatement failed: " + sql, e);
				}
			}
		}
		throw new RuntimeException("prepareStatement failed after retries: " + sql);
	}

	@Override
	public void processResultSet(final ResultSet rs) {
		// Default implementation: no-op, consumers access ResultSet directly
		LOG.debug("processResultSet called");
	}

	@Override
	public int getSqlCode() {
		return sqlCode;
	}

	@Override
	public void setSqlCode(final int sqlCode) {
		this.sqlCode = sqlCode;
	}

	@Override
	public String getSqlState() {
		return sqlState;
	}

	@Override
	public void commit() {
		if (isSqlTraceEnabled()) {
			traceSqlEvent("COMMIT", null);
		}
		try {
			if (currentConnection != null && !currentConnection.getAutoCommit()) {
				currentConnection.commit();
				LOG.debug("COMMIT (main connection)");
			}
			// Also commit any cursor connections that have manual commit
			for (final Map.Entry<String, Connection> entry : cursorConnections.entrySet()) {
				final Connection cc = entry.getValue();
				if (cc != null && !cc.getAutoCommit()) {
					cc.commit();
					LOG.debug("COMMIT (cursor {})", entry.getKey());
				}
			}
			sqlCode = 0;
			sqlState = "00000";
		} catch (final SQLException e) {
			sqlCode = e.getErrorCode();
			sqlState = e.getSQLState();
			throw new RuntimeException("COMMIT failed", e);
		}
	}

	@Override
	public void rollback() {
		if (isSqlTraceEnabled()) {
			traceSqlEvent("ROLLBACK", null);
		}
		try {
			if (currentConnection != null && !currentConnection.getAutoCommit()) {
				currentConnection.rollback();
				LOG.debug("ROLLBACK (main connection)");
			}
			// Also rollback any cursor connections that have manual commit
			for (final Map.Entry<String, Connection> entry : cursorConnections.entrySet()) {
				final Connection cc = entry.getValue();
				if (cc != null && !cc.getAutoCommit()) {
					cc.rollback();
					LOG.debug("ROLLBACK (cursor {})", entry.getKey());
				}
			}
			sqlCode = 0;
			sqlState = "00000";
		} catch (final SQLException e) {
			sqlCode = e.getErrorCode();
			sqlState = e.getSQLState();
			throw new RuntimeException("ROLLBACK failed", e);
		}
	}

	@Override
	public void openCursor(final String cursorName) {
		LOG.debug("OPEN CURSOR {}", cursorName);
		try {
			String sql = cursorSql.get(cursorName);
			if (sql == null) {
				LOG.warn("No SQL registered for cursor {}", cursorName);
				sqlCode = -502;
				return;
			}
			// Resolve dynamic cursor bound to a named prepared statement
			if (sql.startsWith("__prepared__:")) {
				final String stmtName = sql.substring("__prepared__:".length());
				sql = namedStatements.get(stmtName);
				if (sql == null) {
					LOG.warn("No SQL found for prepared statement {}", stmtName);
					sqlCode = -518;
					return;
				}
				cursorSql.put(cursorName, sql);
			}

			sql = fixQuotes(sql);
			final boolean forUpdate = isForUpdate(sql);
			if (forUpdate) {
				sql = stripForUpdate(sql);
				LOG.debug("Stripped FOR UPDATE, SQL is now: {}", sql);
			}
			// PG: inject ctid into cursor SELECT for positioned-update support
			if (forUpdate && isPostgresMode()) {
				sql = injectCtidIntoCursorSelect(sql);
			}
			if (dataSource == null) {
				throw new SQLException(
						"Database not configured. Start the server with --db-url, --db-user, --db-password to enable SQL support.");
			}
			// Each cursor gets its own connection from the pool, returned in closeCursor().
			long __sqlT0 = System.nanoTime();
			final Connection conn;
			try {
				conn = dataSource.getConnection();
			} finally {
				io.proleap.cobol.runtime.SqlTiming.addNanos(System.nanoTime() - __sqlT0);
			}
			cursorConnections.put(cursorName, conn);
			final int rsType = forUpdate ? ResultSet.TYPE_FORWARD_ONLY : ResultSet.TYPE_SCROLL_INSENSITIVE;
			final int rsConcurrency = forUpdate ? ResultSet.CONCUR_UPDATABLE : ResultSet.CONCUR_READ_ONLY;
			__sqlT0 = System.nanoTime();
			final PreparedStatement ps;
			try {
				ps = conn.prepareStatement(sql, rsType, rsConcurrency);
			} finally {
				io.proleap.cobol.runtime.SqlTiming.addNanos(System.nanoTime() - __sqlT0);
			}
			ps.setCursorName(cursorName.toUpperCase());
			if (isSqlTraceEnabled()) {
				traceSqlBefore("OPEN-CURSOR:" + cursorName, sql, null);
			}
			__sqlT0 = System.nanoTime();
			final ResultSet rs;
			try {
				rs = ps.executeQuery();
			} finally {
				io.proleap.cobol.runtime.SqlTiming.addNanos(System.nanoTime() - __sqlT0);
			}
			if (isSqlTraceEnabled()) {
				// Row count unknown for streaming cursors; report 0 as "opened".
				traceSqlAfter("OPEN-CURSOR:" + cursorName, 0);
			}
			cursorStatements.put(cursorName, ps);
			cursorResultSets.put(cursorName, rs);
			cursorForUpdate.put(cursorName, forUpdate);
			sqlCode = 0;
		} catch (final SQLException e) {
			sqlCode = e.getErrorCode();
			sqlState = e.getSQLState() != null ? e.getSQLState() : "58004";
			LOG.warn("OPEN CURSOR failed: {} - SQLCODE={}, SQLSTATE={}", cursorName, sqlCode, sqlState, e);
		}
	}

	@Override
	public void openCursor(final String cursorName, final String sql, final Object... params) {
		String fixedSql = fixQuotes(sql);
		LOG.debug("OPEN CURSOR {} with SQL: {}", cursorName, fixedSql);
		try {
			final boolean forUpdate = isForUpdate(fixedSql);
			if (forUpdate) {
				fixedSql = stripForUpdate(fixedSql);
				LOG.debug("Stripped FOR UPDATE, SQL is now: {}", fixedSql);
			}
			// PG: inject ctid into cursor SELECT for positioned-update support
			if (forUpdate && isPostgresMode()) {
				fixedSql = injectCtidIntoCursorSelect(fixedSql);
			}
			if (dataSource == null) {
				throw new SQLException(
						"Database not configured. Start the server with --db-url, --db-user, --db-password to enable SQL support.");
			}
			// Each cursor gets its own connection from the pool, returned in closeCursor().
			long __sqlT0 = System.nanoTime();
			final Connection conn;
			try {
				conn = dataSource.getConnection();
			} finally {
				io.proleap.cobol.runtime.SqlTiming.addNanos(System.nanoTime() - __sqlT0);
			}
			cursorConnections.put(cursorName, conn);
			final int rsType = forUpdate ? ResultSet.TYPE_FORWARD_ONLY : ResultSet.TYPE_SCROLL_INSENSITIVE;
			final int rsConcurrency = forUpdate ? ResultSet.CONCUR_UPDATABLE : ResultSet.CONCUR_READ_ONLY;
			__sqlT0 = System.nanoTime();
			final PreparedStatement ps;
			try {
				ps = conn.prepareStatement(fixedSql, rsType, rsConcurrency);
			} finally {
				io.proleap.cobol.runtime.SqlTiming.addNanos(System.nanoTime() - __sqlT0);
			}
			ps.setCursorName(cursorName.toUpperCase());
			// Use SafePreparedStatement parameter handling for cursor queries too:
			// setString for String params, setBigDecimal for BigDecimal params.
			// This matches the behavior of inline SELECT queries (fix from SafePreparedStatement)
			// and avoids Data type mismatch errors with the jt400 driver on DATE/TIME columns.
			final SafePreparedStatement safe = new SafePreparedStatement(ps, fixedSql, isPostgresMode());
			for (int i = 0; i < params.length; i++) {
				safe.setObject(i + 1, params[i]);
			}
			// Log cursor params for FILE200800 (article data queries)
			if (ProgramRunnerImpl.DEBUG && fixedSql.contains("FILE200800")) {
				final StringBuilder sb = new StringBuilder();
				sb.append("[CURSOR FILE200800] ").append(cursorName).append(" params=[");
				for (int i = 0; i < params.length; i++) {
					if (i > 0) sb.append(", ");
					sb.append(params[i] == null ? "null" : ("'" + params[i] + "'"));
				}
				sb.append("]");
				LOG.info(sb.toString());
			}
			if (isSqlTraceEnabled()) {
				final java.util.LinkedHashMap<Integer, Object> traceParams = new java.util.LinkedHashMap<>();
				for (int i = 0; i < params.length; i++) {
					traceParams.put(i + 1, params[i]);
				}
				traceSqlBefore("OPEN-CURSOR:" + cursorName, fixedSql, traceParams);
			}
			ResultSet rs;
			try {
				final long __sqlT1 = System.nanoTime();
				try {
					rs = ps.executeQuery();
				} finally {
					io.proleap.cobol.runtime.SqlTiming.addNanos(System.nanoTime() - __sqlT1);
				}
			} catch (final SQLException execErr) {
				// PostgreSQL defers type-checking to execute time. When a BigDecimal parameter
				// is sent for a CHAR column, executeQuery fails with SQLSTATE 42883
				// "operator does not exist: character = numeric". Retry with all params as
				// Strings so PostgreSQL can cast text to the target column type.
				if (isPostgresMode() && "42883".equals(execErr.getSQLState())) {
					LOG.info("OPEN CURSOR {} retrying with typed params (was: {})", cursorName, execErr.getMessage());
					ps.close();
					final long __sqlT2 = System.nanoTime();
					final PreparedStatement ps2;
					try {
						ps2 = conn.prepareStatement(fixedSql, rsType, rsConcurrency);
					} finally {
						io.proleap.cobol.runtime.SqlTiming.addNanos(System.nanoTime() - __sqlT2);
					}
					ps2.setCursorName(cursorName.toUpperCase());
					// Use ParameterMetaData to detect actual column types and send
					// parameters with the correct JDBC type (String for CHAR, BigDecimal for NUMERIC).
					java.sql.ParameterMetaData pmd = null;
					try {
						pmd = ps2.getParameterMetaData();
					} catch (final SQLException pmdErr) {
						LOG.debug("ParameterMetaData not available for cursor {}, falling back", cursorName);
					}
					for (int i = 0; i < params.length; i++) {
						if (params[i] == null) {
							ps2.setNull(i + 1, java.sql.Types.VARCHAR);
						} else {
							final String strVal = params[i].toString();
							boolean isNumericCol = false;
							if (pmd != null) {
								try {
									final int sqlType = pmd.getParameterType(i + 1);
									isNumericCol = (sqlType == java.sql.Types.NUMERIC
										|| sqlType == java.sql.Types.DECIMAL
										|| sqlType == java.sql.Types.INTEGER
										|| sqlType == java.sql.Types.SMALLINT
										|| sqlType == java.sql.Types.BIGINT
										|| sqlType == java.sql.Types.DOUBLE
										|| sqlType == java.sql.Types.FLOAT
										|| sqlType == java.sql.Types.REAL);
								} catch (final SQLException typeErr) {
									// ignore — fall back to String
								}
							}
							if (isNumericCol) {
								final String trimmed = strVal.trim();
								if (!trimmed.isEmpty()) {
									try {
										ps2.setBigDecimal(i + 1, new java.math.BigDecimal(trimmed));
									} catch (final NumberFormatException nfe) {
										ps2.setString(i + 1, strVal);
									}
								} else {
									ps2.setBigDecimal(i + 1, java.math.BigDecimal.ZERO);
								}
							} else {
								ps2.setString(i + 1, strVal);
							}
						}
					}
					final long __sqlT3 = System.nanoTime();
					try {
						rs = ps2.executeQuery();
					} finally {
						io.proleap.cobol.runtime.SqlTiming.addNanos(System.nanoTime() - __sqlT3);
					}
					cursorStatements.put(cursorName, ps2);
					cursorResultSets.put(cursorName, rs);
					cursorForUpdate.put(cursorName, forUpdate);
					sqlCode = 0;
					sqlState = "00000";
					if (isSqlTraceEnabled()) {
						traceSqlAfter("OPEN-CURSOR:" + cursorName + "(retry)", 0);
					}
					return;
				}
				throw execErr;
			}
			cursorStatements.put(cursorName, ps);
			cursorResultSets.put(cursorName, rs);
			cursorForUpdate.put(cursorName, forUpdate);
			sqlCode = 0;
			sqlState = "00000";
			if (isSqlTraceEnabled()) {
				traceSqlAfter("OPEN-CURSOR:" + cursorName, 0);
			}
		} catch (final SQLException e) {
			sqlCode = e.getErrorCode();
			sqlState = e.getSQLState() != null ? e.getSQLState() : "58004";
			LOG.warn("OPEN CURSOR failed: {} - SQLCODE={}, SQLSTATE={}", cursorName, sqlCode, sqlState, e);
		}
	}

	/**
	 * Check if SQL contains FOR UPDATE clause.
	 */
	private boolean isForUpdate(final String sql) {
		return sql != null && sql.toUpperCase().contains("FOR UPDATE");
	}

	/**
	 * In PostgreSQL mode, inject ", ctid AS __ctid__" into the SELECT list of a
	 * cursor query so we can later rewrite WHERE CURRENT OF <cursor> as
	 * WHERE ctid = ? in dependent UPDATE/DELETE statements.
	 *
	 * Only applied when the cursor is declared FOR UPDATE (or otherwise eligible
	 * for positioned updates). Skips queries that already have ctid in the list,
	 * GROUP BY/aggregate queries, and SELECT *  (PostgreSQL exposes ctid implicitly).
	 *
	 * Returns the original SQL untouched if injection is not safe.
	 */
	private String injectCtidIntoCursorSelect(final String sql) {
		if (sql == null) return sql;
		final String upper = sql.toUpperCase();
		if (!upper.trim().startsWith("SELECT")) return sql;
		if (upper.contains("__CTID__")) return sql;
		if (upper.contains(" GROUP BY ") || upper.contains(" UNION ")
				|| upper.contains(" DISTINCT ") || upper.contains("COUNT(")
				|| upper.contains("SUM(") || upper.contains("AVG(")) {
			return sql;
		}
		// Find the FROM keyword position; inject "ctid AS __ctid__" just before it.
		final java.util.regex.Matcher fromMatcher =
			java.util.regex.Pattern.compile("(?i)\\s+FROM\\s+").matcher(sql);
		if (!fromMatcher.find()) return sql;
		final int fromStart = fromMatcher.start();
		final String head = sql.substring(0, fromStart);
		final String tail = sql.substring(fromStart);
		// Don't inject for SELECT * — the ctid pseudo-column is implicit and
		// accessible by name even when not in the projection list.
		if (head.matches("(?is)\\s*SELECT\\s+\\*\\s*")) return sql;
		return head + ", ctid AS __ctid__" + tail;
	}

	/**
	 * Strip FOR UPDATE [OF col1, col2, ...] from SQL.
	 * DB2/400 tables without journaling cannot use FOR UPDATE with commitment
	 * control over JDBC.  The positioned update (WHERE CURRENT OF) still works
	 * via setCursorName() + CONCUR_UPDATABLE without the FOR UPDATE clause in
	 * the SELECT.
	 */
	private String stripForUpdate(final String sql) {
		return sql.replaceAll("(?i)\\s+FOR\\s+UPDATE(\\s+OF\\s+[\\w,\\s]+)?", "");
	}

	@Override
	public ResultSet fetchCursor(final String cursorName) {
		LOG.debug("FETCH CURSOR {}", cursorName);
		if (isSqlTraceEnabled()) {
			traceSqlEvent("FETCH-CURSOR", "cursor=" + cursorName);
		}
		final ResultSet rs = cursorResultSets.get(cursorName);
		if (rs == null) {
			sqlCode = -501;
			return null;
		}
		return rs;
	}

	@Override
	public void closeCursor(final String cursorName) {
		LOG.debug("CLOSE CURSOR {}", cursorName);
		if (isSqlTraceEnabled()) {
			traceSqlEvent("CLOSE-CURSOR", "cursor=" + cursorName);
		}
		try {
			final ResultSet rs = cursorResultSets.remove(cursorName);
			final PreparedStatement ps = cursorStatements.remove(cursorName);
			final Connection conn = cursorConnections.remove(cursorName);
			cursorForUpdate.remove(cursorName);
			if (rs != null) {
				rs.close();
			}
			if (ps != null) {
				ps.close();
			}
			// Return the cursor's connection to the pool
			if (conn != null) {
				try { conn.close(); } catch (final SQLException ce) { LOG.debug("Error closing cursor connection: {}", ce.getMessage()); }
			}
			sqlCode = 0;
		} catch (final SQLException e) {
			sqlCode = e.getErrorCode();
			sqlState = e.getSQLState();
			LOG.warn("Error closing cursor {}", cursorName, e);
		}
	}

	/**
	 * Register SQL for a named cursor (called during DECLARE CURSOR processing).
	 */
	public void declareCursor(final String cursorName, final String sql) {
		cursorSql.put(cursorName, sql);
	}

	@Override
	public void prepareNamedStatement(final String stmtName, final String sql) {
		final String fixedSql = fixQuotes(sql);
		LOG.debug("PREPARE {} FROM '{}'", stmtName, fixedSql);
		if (isSqlTraceEnabled()) {
			traceSqlEvent("PREPARE", "stmt=" + stmtName + " sql=" + fixedSql);
		}
		namedStatements.put(stmtName, fixedSql);
		sqlCode = 0;
		sqlState = "00000";
	}

	@Override
	public void declareCursorForPrepared(final String cursorName, final String stmtName) {
		LOG.debug("DECLARE {} CURSOR FOR {}", cursorName, stmtName);
		final String sql = namedStatements.get(stmtName);
		if (sql != null) {
			cursorSql.put(cursorName, sql);
		} else {
			// Statement will be bound later when PREPARE is executed at runtime
			// Store a reference so openCursor can look it up
			cursorSql.put(cursorName, "__prepared__:" + stmtName);
		}
	}

	/**
	 * Convert COBOL double-quoted string literals to SQL single quotes.
	 * Dynamic SQL built via STRING uses double quotes (COBOL literal content),
	 * but JDBC needs single quotes for string literals.
	 * Also applies DB2-to-PostgreSQL translation when postgresMode is active.
	 */
	private String fixQuotes(final String sql) {
		String fixed = sql.replaceAll("\"([^\"]*)\"", "'$1'");
		return adaptSql(fixed);
	}

	/**
	 * Detect whether the current JDBC connection is to PostgreSQL.
	 * Caches the result for the lifetime of this SqlServiceImpl.
	 * Uses a short-lived connection for detection to avoid holding pool resources.
	 */
	private boolean isPostgresMode() {
		if (postgresMode != null) {
			return postgresMode;
		}
		if (dataSource == null) {
			postgresMode = false;
			return false;
		}
		// Use a short-lived connection just for metadata detection
		try (final Connection conn = dataSource.getConnection()) {
			final String productName = conn.getMetaData().getDatabaseProductName();
			postgresMode = productName != null && productName.toLowerCase().contains("postgres");
			if (!postgresMode) {
				// Fallback: check JDBC URL (covers cases where metadata is unavailable)
				final String url = conn.getMetaData().getURL();
				if (url != null && url.toLowerCase().contains("postgresql")) {
					postgresMode = true;
				}
			}
			if (postgresMode) {
				LOG.info("PostgreSQL mode detected — adaptSql() will translate DB2 SQL");
			} else {
				LOG.info("DB2 mode detected (product: {})", productName);
			}
		} catch (final SQLException e) {
			LOG.warn("Could not detect database product (will retry next call): {}", e.getMessage());
			// Do NOT cache false — the connection may not be ready yet.
			// Return false for this call, but allow retry on next call.
			return false;
		}
		return postgresMode;
	}

	/**
	 * Look up the primary-key (or unique-index) columns for a table/view in PostgreSQL.
	 * For views, resolves to the underlying base table first (recursively, up to
	 * {@link #MAX_VIEW_RESOLUTION_DEPTH} levels, with cycle protection).
	 * Results are cached in {@link #pkColumnsCache}.
	 *
	 * @param tableName lower-case table or view name (e.g. "vr200800")
	 * @return comma-separated PK column list (e.g. "codsoc, refpres, taipres, codori"),
	 *         or empty string if no PK/unique index was found.
	 */
	private String lookupPkColumns(final String tableName) {
		if (pkColumnsCache.containsKey(tableName)) {
			return pkColumnsCache.get(tableName);
		}
		String columns = "";
		if (dataSource == null) {
			pkColumnsCache.put(tableName, columns);
			return columns;
		}
		// Use a short-lived connection for metadata lookup to avoid holding pool resources
		try (final Connection conn = dataSource.getConnection()) {
			columns = lookupPkColumnsRecursive(conn, tableName, 0, new java.util.HashSet<String>());
			if (columns.isEmpty()) {
				LOG.debug("No PK/unique index found for table {}", tableName);
			} else {
				LOG.debug("PK columns for {}: {}", tableName, columns);
			}
		} catch (final SQLException e) {
			LOG.debug("Could not look up PK for {}: {}", tableName, e.getMessage());
		}
		pkColumnsCache.put(tableName, columns);
		return columns;
	}

	/**
	 * Maximum recursion depth when resolving view -> base table chains.
	 * Protects against cyclic or deeply-nested view definitions.
	 */
	private static final int MAX_VIEW_RESOLUTION_DEPTH = 3;

	/**
	 * Recursively look up PK columns for a relation.
	 *
	 * <p>If {@code relName} is a base table with a PK/unique index, returns its
	 * columns. If it is a view, parses the view definition via {@code pg_views}
	 * to find the first {@code FROM <identifier>} token and recurses on that
	 * identifier (up to {@link #MAX_VIEW_RESOLUTION_DEPTH} levels).
	 *
	 * <p>This method does not touch {@link #pkColumnsCache} for intermediate
	 * relations; only {@link #lookupPkColumns(String)} caches the final result
	 * under the originally-requested name.
	 *
	 * <p>Behaviour for base tables is unchanged: when pg_index returns a PK or
	 * unique index, the columns are returned immediately without the view lookup.
	 *
	 * @param conn     live connection for metadata queries
	 * @param relName  relation name (lower-case)
	 * @param depth    current recursion depth
	 * @param visited  set of already-visited relation names (cycle guard)
	 * @return comma-separated PK column list, or empty string if none found
	 * @throws SQLException if a metadata query fails
	 */
	private String lookupPkColumnsRecursive(final Connection conn, final String relName,
			final int depth, final java.util.Set<String> visited) throws SQLException {
		if (depth >= MAX_VIEW_RESOLUTION_DEPTH) {
			LOG.debug("PK resolution depth limit reached at {}", relName);
			return "";
		}
		if (!visited.add(relName)) {
			LOG.debug("PK resolution cycle detected at {}", relName);
			return "";
		}

		// Step 1: direct PK/unique-index lookup via pg_index + pg_attribute.
		// Works for base tables; returns nothing for views.
		try (final PreparedStatement ps = conn.prepareStatement(
				"SELECT a.attname " +
				"FROM pg_index i " +
				"JOIN pg_class c ON c.oid = i.indrelid " +
				"JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY(i.indkey) " +
				"WHERE c.relname = ? AND i.indisunique " +
				"ORDER BY i.indisprimary DESC, array_position(i.indkey, a.attnum)")) {
			ps.setString(1, relName);
			try (final ResultSet rs = ps.executeQuery()) {
				final StringBuilder sb = new StringBuilder();
				while (rs.next()) {
					if (sb.length() > 0) sb.append(", ");
					sb.append(rs.getString(1));
				}
				if (sb.length() > 0) {
					return sb.toString();
				}
			}
		}

		// Step 2: no PK found. Check whether this relation is a view and, if so,
		// parse its definition to find the underlying base relation and recurse.
		// Guard: only this branch is new behaviour; base tables return above.
		String viewDef = null;
		try (final PreparedStatement ps = conn.prepareStatement(
				"SELECT definition FROM pg_views WHERE viewname = ? LIMIT 1")) {
			ps.setString(1, relName);
			try (final ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					viewDef = rs.getString(1);
				}
			}
		} catch (final SQLException e) {
			// pg_views unavailable (non-PG or permissions): no resolution possible.
			LOG.debug("pg_views lookup failed for {}: {}", relName, e.getMessage());
			return "";
		}
		if (viewDef == null) {
			// Not a view and no PK: genuinely no ordering key available.
			return "";
		}

		final String baseRel = extractFirstFromIdentifier(viewDef);
		if (baseRel == null || baseRel.equals(relName)) {
			LOG.debug("Could not extract base relation from view {} definition", relName);
			return "";
		}
		LOG.debug("View {} -> base relation {} (depth {})", relName, baseRel, depth);
		return lookupPkColumnsRecursive(conn, baseRel, depth + 1, visited);
	}

	/**
	 * Extract the first {@code FROM <identifier>} token from a SQL fragment
	 * (typically a {@code pg_views.definition} body). Handles schema-qualified
	 * names ({@code schema.table}) and optional double-quotes, returning only
	 * the final identifier lower-cased to match {@code pg_class.relname}.
	 *
	 * @param sql SQL text to scan (may be null)
	 * @return lower-case relation name, or null if no FROM clause was found
	 */
	private static String extractFirstFromIdentifier(final String sql) {
		if (sql == null) return null;
		final java.util.regex.Matcher m = java.util.regex.Pattern.compile(
				"(?i)\\bFROM\\s+(?:\"?([\\w$]+)\"?\\.)?\"?([\\w$]+)\"?")
				.matcher(sql);
		if (m.find()) {
			return m.group(2).toLowerCase();
		}
		return null;
	}

	/**
	 * Cast bare '?' placeholders that sit directly inside string-handling
	 * functions (LENGTH, RTRIM, LTRIM, TRIM, UPPER, LOWER, SUBSTR, SUBSTRING)
	 * to an explicit string type. Both PostgreSQL and DB2/400 reject untyped
	 * placeholders in these positions (PG: SQLSTATE 42P08; DB2/400: SQLCODE
	 * -901). The cast type differs by dialect:
	 *   - PostgreSQL: CAST(? AS TEXT)
	 *   - DB2/400:    CAST(? AS VARCHAR(4000))   (DB2 requires explicit length)
	 *
	 * Existing CAST(?) wrappers are not re-matched because, after rewriting,
	 * the '?' no longer sits directly after the string-function open paren.
	 *
	 * @param sql  SQL text to rewrite
	 * @param isPg true for PostgreSQL (use TEXT), false for DB2 (use VARCHAR(4000))
	 */
	private String castUntypedParamsInStringFunctions(final String sql, final boolean isPg) {
		if (sql == null) return sql;
		final java.util.regex.Pattern stringFnArg = java.util.regex.Pattern.compile(
			"(?i)\\b(RTRIM|LTRIM|TRIM|UPPER|LOWER|LENGTH|SUBSTR|SUBSTRING)\\s*\\(\\s*\\?(\\s*[,)])");
		final String castType = isPg ? "TEXT" : "VARCHAR(4000)";
		final StringBuilder sb = new StringBuilder();
		int last = 0;
		final java.util.regex.Matcher m = stringFnArg.matcher(sql);
		while (m.find()) {
			sb.append(sql, last, m.start());
			sb.append(m.group(1)).append("(CAST(? AS ").append(castType).append(")").append(m.group(2));
			last = m.end();
		}
		if (last == 0) {
			return sql;
		}
		sb.append(sql, last, sql.length());
		return sb.toString();
	}

	/**
	 * Translate DB2/400 SQL to PostgreSQL-compatible SQL.
	 * Called on every SQL string before passing to JDBC.
	 * When not in PostgreSQL mode, returns the SQL unchanged.
	 *
	 * Rules handle patterns that cannot be done with PostgreSQL compatibility functions:
	 * - CURRENT TIMESTAMP/DATE/TIME (space to underscore)
	 * - USER -> CURRENT_USER
	 * - CHAR(expr, EUR/ISO/USA) -> TO_CHAR with date format
	 * - FETCH FIRST n ROWS ONLY -> LIMIT n
	 * - OPTIMIZE FOR n ROWS -> strip
	 * - Date arithmetic with DAY/MONTH keywords
	 * - Scalar MAX(a,b,c) -> GREATEST
	 * - DATE(expr) -> CAST(expr AS DATE)
	 * - INTEGER(expr) -> CAST(expr AS INTEGER)
	 * - DECIMAL(expr[, p[, s]]) -> CAST(expr AS NUMERIC[(p[,s])])
	 * - CHAR(expr) (without 2nd arg) -> CAST(expr AS TEXT)
	 * - FOR FETCH ONLY -> FOR READ ONLY
	 */
	private String adaptSql(final String sql) {
		if (sql == null) {
			return sql;
		}
		if (!isPostgresMode()) {
			// DB2/AS400: Rule 15 still applies (DB2 also rejects untyped '?' inside
			// LENGTH/RTRIM/etc. with SQLCODE -901). Use VARCHAR(4000) — DB2/400
			// requires explicit length for VARCHAR casts; 4000 is well within the
			// parameter limit and large enough for any COBOL field.
			return castUntypedParamsInStringFunctions(sql, false);
		}

		String result = sql;

		// Rule 1: CURRENT DATE / CURRENT TIMESTAMP / CURRENT TIME (space -> underscore)
		result = result.replaceAll("(?i)CURRENT\\s+TIMESTAMP", "CURRENT_TIMESTAMP");
		result = result.replaceAll("(?i)CURRENT\\s+DATE", "CURRENT_DATE");
		result = result.replaceAll("(?i)CURRENT\\s+TIME(?!STAMP)", "CURRENT_TIME");

		// Rule 2: USER -> CURRENT_USER (in assignments and values, not function calls)
		result = result.replaceAll("(?i)(=\\s*)USER\\b(?!\\s*\\()", "$1CURRENT_USER");
		result = result.replaceAll("(?i)(,\\s*)USER\\b(?!\\s*\\()", "$1CURRENT_USER");

		// Rule 3: CHAR(expr, EUR/ISO/USA) -> TO_CHAR with format
		result = result.replaceAll(
			"(?i)CHAR\\s*\\(([^,]+),\\s*EUR\\s*\\)",
			"TO_CHAR(CAST($1 AS DATE), 'DD.MM.YYYY')");
		result = result.replaceAll(
			"(?i)CHAR\\s*\\(([^,]+),\\s*ISO\\s*\\)",
			"TO_CHAR(CAST($1 AS DATE), 'YYYY-MM-DD')");
		result = result.replaceAll(
			"(?i)CHAR\\s*\\(([^,]+),\\s*USA\\s*\\)",
			"TO_CHAR(CAST($1 AS DATE), 'MM/DD/YYYY')");

		// Rule 4: FETCH FIRST n ROWS ONLY -> LIMIT n
		result = result.replaceAll(
			"(?i)FETCH\\s+FIRST\\s+(\\d+)\\s+ROWS?\\s+ONLY",
			"LIMIT $1");

		// Rule 5: OPTIMIZE FOR n ROWS -> strip (PG ignores this hint)
		result = result.replaceAll(
			"(?i)\\s+OPTIMIZE\\s+FOR\\s+\\d+\\s+ROWS",
			"");

		// Rule 6: Date arithmetic with DAY/DAYS keyword
		result = result.replaceAll(
			"(?i)(CURRENT_DATE)\\s*([+-])\\s*(\\d+)\\s+DAYS?\\b",
			"($1 $2 $3)");
		result = result.replaceAll(
			"(?i)(\\w+\\.\\w+)\\s*([+-])\\s*(\\d+)\\s+DAYS?\\b",
			"($1 $2 $3)");

		// Rule 7: Date arithmetic with MONTH/MONTHS keyword
		result = result.replaceAll(
			"(?i)(\\S+)\\s*\\+\\s*(\\d+)\\s+MONTHS?\\b",
			"($1 + INTERVAL '$2 months')");
		result = result.replaceAll(
			"(?i)(\\S+)\\s*-\\s*(\\d+)\\s+MONTHS?\\b",
			"($1 - INTERVAL '$2 months')");

		// Rule 8: Scalar MAX(a,b,c) -> GREATEST (only with 2+ commas)
		result = result.replaceAll(
			"(?i)\\bMAX\\s*\\(([^)]+,[^)]+,[^)]+)\\)",
			"GREATEST($1)");

		// Rule 10: DATE(expr) -> CAST(expr AS DATE)
		// DB2 supports DATE() as a cast function; PG only has the type-cast syntax.
		// Use a non-greedy capture and ensure no comma inside (not a multi-arg function).
		result = result.replaceAll(
			"(?i)\\bDATE\\s*\\(([^,()]+)\\)",
			"CAST($1 AS DATE)");

		// Rule 11: INTEGER(expr) -> CAST(expr AS INTEGER)
		// Same rationale as DATE().
		result = result.replaceAll(
			"(?i)\\bINTEGER\\s*\\(([^,()]+)\\)",
			"CAST($1 AS INTEGER)");

		// Rule 12: DECIMAL(expr[, precision[, scale]]) -> CAST(expr AS NUMERIC[(p[,s])])
		// Try the 3-arg form first, then 2-arg, then 1-arg, so the longest match wins.
		result = result.replaceAll(
			"(?i)\\bDECIMAL\\s*\\(([^,()]+),\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)",
			"CAST($1 AS NUMERIC($2,$3))");
		result = result.replaceAll(
			"(?i)\\bDECIMAL\\s*\\(([^,()]+),\\s*(\\d+)\\s*\\)",
			"CAST($1 AS NUMERIC($2))");
		result = result.replaceAll(
			"(?i)\\bDECIMAL\\s*\\(([^,()]+)\\)",
			"CAST($1 AS NUMERIC)");

		// Rule 13: CHAR(expr) without a 2nd arg -> CAST(expr AS TEXT)
		// MUST run AFTER Rule 3 (which handles CHAR(expr, EUR/ISO/USA)). The "[^,()]+"
		// in the capture forbids commas/parens inside, so it cannot match the 2-arg
		// form already rewritten by Rule 3 (which becomes TO_CHAR(...)).
		result = result.replaceAll(
			"(?i)\\bCHAR\\s*\\(([^,()]+)\\)",
			"CAST($1 AS TEXT)");

		// Rule 14: FOR FETCH ONLY -> FOR READ ONLY (synonym in PG)
		result = result.replaceAll(
			"(?i)\\bFOR\\s+FETCH\\s+ONLY\\b",
			"FOR READ ONLY");

		// Rule 15: Cast bare '?' placeholders inside string functions to TEXT.
		// Applied to both PG (CAST(? AS TEXT)) and DB2 (CAST(? AS VARCHAR(4000))).
		result = castUntypedParamsInStringFunctions(result, true);

		// Rule 9: Deterministic ORDER BY for SELECT without ORDER BY.
		// DB2/400 returns rows in arrival sequence (close to primary-key order).
		// PostgreSQL returns in heap order (effectively random after VACUUM/UPDATE).
		// For COBOL programs that FETCH just the first row from a cursor, this
		// difference causes wrong data.  Inject ORDER BY <pk-columns> to match DB2.
		final String upper = result.toUpperCase();
		if (upper.trim().startsWith("SELECT") && !upper.contains("ORDER BY")
				&& !upper.contains("COUNT(") && !upper.contains("SUM(")
				&& !upper.contains("MAX(") && !upper.contains("MIN(")
				&& !upper.contains("GREATEST(")) {
			// Extract table/view name from FROM clause
			final java.util.regex.Matcher fromMatcher =
				java.util.regex.Pattern.compile("(?i)\\bFROM\\s+(\\w+)")
					.matcher(result);
			if (fromMatcher.find()) {
				final String tbl = fromMatcher.group(1).toLowerCase();
				final String pkCols = lookupPkColumns(tbl);
				if (!pkCols.isEmpty()) {
					// Append ORDER BY before any trailing LIMIT/FOR UPDATE
					final java.util.regex.Matcher tailMatcher =
						java.util.regex.Pattern.compile("(?i)(\\s+(?:LIMIT|FOR\\s+UPDATE)\\b.*)$")
							.matcher(result);
					if (tailMatcher.find()) {
						result = result.substring(0, tailMatcher.start())
							+ " ORDER BY " + pkCols
							+ tailMatcher.group(1);
					} else {
						result = result + " ORDER BY " + pkCols;
					}
				}
			}
		}

		if (!result.equals(sql)) {
			LOG.debug("adaptSql: {} -> {}", sql, result);
		}

		return result;
	}

	@Override
	public int getUpdateCount() {
		return lastUpdateCount;
	}

	@Override
	public String getNamedStatement(final String stmtName) {
		return namedStatements.get(stmtName);
	}

	/**
	 * Tracks the last executeUpdate row count for GET DIAGNOSTICS ROW_COUNT.
	 */
	public void setLastUpdateCount(final int count) {
		this.lastUpdateCount = count;
	}

	/**
	 * Read the ctid (PostgreSQL physical row ID) of the row currently positioned
	 * by the named cursor. Used by SafePreparedStatement to bind the ctid as the
	 * last parameter of a rewritten WHERE ctid = ? clause for positioned updates.
	 *
	 * @return the ctid string (e.g. "(0,42)") or null if the cursor or column
	 *         is not available.
	 */
	String getCurrentCtidForCursor(final String cursorName) {
		final ResultSet rs = cursorResultSets.get(cursorName);
		if (rs == null) {
			LOG.warn("getCurrentCtidForCursor: no cursor {} open", cursorName);
			return null;
		}
		try {
			// Try the aliased label first (injected by injectCtidIntoCursorSelect()),
			// then the implicit pseudo-column (works when SELECT * was used).
			try {
				return rs.getString("__ctid__");
			} catch (final SQLException e) {
				return rs.getString("ctid");
			}
		} catch (final SQLException e) {
			LOG.warn("getCurrentCtidForCursor: could not read ctid for cursor {}: {}", cursorName, e.getMessage());
			return null;
		}
	}

	private Connection getConnection() throws SQLException {
		if (dataSource == null) {
			throw new SQLException(
					"Database not configured. Start the server with --db-url, --db-user, --db-password to enable SQL support.");
		}
		// All paths below touch the pool and/or the network (isValid() pings the
		// server). Account for the whole block as JDBC time.
		final long __sqlT0 = System.nanoTime();
		try {
			if (currentConnection == null || currentConnection.isClosed()) {
				currentConnection = dataSource.getConnection();
				// Do NOT set autoCommit(false) — COBOL on AS/400 uses *NONE commitment
				// control by default, and non-journaled tables cause SQLCODE -7008.
				// Leave autoCommit at its default (true).
			}
			// Validate the connection is still usable (handles stale connections)
			try {
				if (!currentConnection.isValid(5)) {
					LOG.warn("Connection is no longer valid, reconnecting");
					try { currentConnection.close(); } catch (final SQLException ce) { /* ignore */ }
					currentConnection = dataSource.getConnection();
				}
			} catch (final SQLException e) {
				LOG.warn("Connection validation failed, reconnecting: {}", e.getMessage());
				try { currentConnection.close(); } catch (final SQLException ce) { /* ignore */ }
				currentConnection = dataSource.getConnection();
			}
			return currentConnection;
		} finally {
			io.proleap.cobol.runtime.SqlTiming.addNanos(System.nanoTime() - __sqlT0);
		}
	}

	/**
	 * Return the current non-cursor connection to the pool (if any).
	 * Called before each new prepareStatement() to avoid holding stale connections,
	 * and during close() for final cleanup.
	 */
	private void releaseCurrentConnection() {
		if (currentConnection != null) {
			try { currentConnection.close(); } catch (final SQLException e) { LOG.debug("Error releasing connection: {}", e.getMessage()); }
			currentConnection = null;
		}
	}

	/**
	 * Release all JDBC resources: close all open cursors, release all connections
	 * back to the pool. Called when this SqlServiceImpl is no longer needed.
	 *
	 * With HikariCP, Connection.close() returns the connection to the pool
	 * rather than destroying it.
	 */
	@Override
	public void close() {
		// Close all open cursors (which also releases their connections)
		for (final String cursorName : new java.util.ArrayList<>(cursorResultSets.keySet())) {
			closeCursor(cursorName);
		}
		// Also release any cursor connections that might have been left without a ResultSet
		for (final Connection conn : cursorConnections.values()) {
			try { if (conn != null) conn.close(); } catch (final SQLException e) { /* ignore */ }
		}
		cursorConnections.clear();
		// Release the non-cursor connection
		releaseCurrentConnection();
	}

	/**
	 * Safety net: release connections when this instance is garbage-collected.
	 * Callers should call close() explicitly; this is a fallback to prevent
	 * HikariCP pool exhaustion when callers don't close properly.
	 */
	@SuppressWarnings("deprecation")
	@Override
	protected void finalize() throws Throwable {
		try {
			if (currentConnection != null || !cursorConnections.isEmpty()) {
				LOG.debug("SqlServiceImpl.finalize() releasing leaked connections");
				close();
			}
		} finally {
			super.finalize();
		}
	}

	/**
	 * Wrapper for PreparedStatement that converts setObject(idx, String) to setString(idx, String).
	 * This fixes Data type mismatch errors when COBOL PIC X fields are mapped to DB2 DATE/TIME columns.
	 * The AS/400 JDBC driver (jt400) handles String-to-DATE conversion in setString but not in setObject.
	 */
	static class SafePreparedStatement extends io.proleap.cobol.runtime.DelegatingPreparedStatement {
		private final String sql;
		private boolean pgMode;
		/** Tracks original parameter values for executeQuery retry on type mismatch. */
		private final java.util.Map<Integer, Object> paramValues = new java.util.LinkedHashMap<>();
		/** Lazily-fetched ParameterMetaData for PostgreSQL type-aware parameter binding. */
		private java.sql.ParameterMetaData cachedPmd = null;
		private boolean pmdFetched = false;
		/** Callback to release the underlying connection after the SQL operation completes. */
		private Runnable connectionReleaser;
		/**
		 * For PG positioned updates: name of the cursor whose current row's ctid
		 * must be bound as the last parameter before executeUpdate(). Null when
		 * this PS is not a rewritten WHERE CURRENT OF statement.
		 */
		private String positionedCursorName;
		/** Owning SqlServiceImpl, used to look up the cursor's current ctid. */
		private SqlServiceImpl owningService;
		SafePreparedStatement(final java.sql.PreparedStatement delegate) {
			this(delegate, null, false);
		}
		SafePreparedStatement(final java.sql.PreparedStatement delegate, final String sql) {
			this(delegate, sql, false);
		}
		SafePreparedStatement(final java.sql.PreparedStatement delegate, final String sql, final boolean pgMode) {
			super(delegate);
			this.sql = sql;
			this.pgMode = pgMode;
		}
		void setConnectionReleaser(final Runnable releaser) {
			this.connectionReleaser = releaser;
		}

		/**
		 * Mark this PS as a rewritten WHERE CURRENT OF positioned-update.
		 * The owning SqlServiceImpl will be queried for the current ctid of
		 * {@code cursorName} just before executeUpdate(), and that ctid will be
		 * bound as the last parameter.
		 */
		void setPositionedCursor(final String cursorName, final SqlServiceImpl service) {
			this.positionedCursorName = cursorName;
			this.owningService = service;
		}

		/**
		 * If this PS is a rewritten WHERE CURRENT OF statement, look up the
		 * current ctid of the named cursor and bind it as the last parameter
		 * (slot = totalParameterCount). Returns true on success, false if the
		 * ctid could not be obtained (caller should let executeUpdate fail).
		 */
		private boolean bindPositionedCursorCtidIfNeeded() {
			if (positionedCursorName == null || owningService == null) {
				return true;
			}
			final String ctid = owningService.getCurrentCtidForCursor(positionedCursorName);
			if (ctid == null) {
				LOG.warn("Positioned UPDATE/DELETE: no ctid available for cursor {}", positionedCursorName);
				return false;
			}
			try {
				final int slot = getDelegate().getParameterMetaData().getParameterCount();
				getDelegate().setString(slot, ctid);
				return true;
			} catch (final java.sql.SQLException e) {
				LOG.warn("Positioned UPDATE/DELETE: could not bind ctid for cursor {}: {}", positionedCursorName, e.getMessage());
				return false;
			}
		}

		/**
		 * Lazily fetch and cache ParameterMetaData for this statement.
		 *
		 * Now fetched in BOTH PG and DB2 modes — the AS/400 jt400 driver supports
		 * ParameterMetaData and we need it in DB2 mode for Rule 16 (DATE/TIME
		 * binding via setDate/setTime instead of setString) and to know which
		 * SQL type to pass to setNull on a DATE/TIME column.
		 *
		 * Returns null only if the JDBC driver throws when asked for PMD.
		 */
		private java.sql.ParameterMetaData getPmd() {
			if (!pmdFetched) {
				pmdFetched = true;
				try {
					cachedPmd = getDelegate().getParameterMetaData();
				} catch (final java.sql.SQLException e) {
					LOG.debug("ParameterMetaData not available: {}", e.getMessage());
				}
			}
			return cachedPmd;
		}

		/**
		 * Override setNull to use the actual SQL type from ParameterMetaData
		 * instead of the type the caller passed.
		 *
		 * Why: ProLeap emits {@code ps.setNull(idx, java.sql.Types.VARCHAR)} for
		 * any host variable with a COBOL SQL indicator, regardless of the underlying
		 * column type. PostgreSQL rejects setNull(VARCHAR) on a DATE/TIME column
		 * with SQLSTATE 42883 ("operator does not exist"). Look the type up via
		 * PMD and forward the correct one. If PMD is unavailable or returns
		 * Types.OTHER, fall back to the caller-supplied type so existing behaviour
		 * is preserved on drivers without PMD support.
		 */
		@Override
		public void setNull(final int parameterIndex, final int sqlType) throws java.sql.SQLException {
			final int actualType = getParameterSqlType(parameterIndex);
			if (actualType != java.sql.Types.OTHER && actualType != sqlType) {
				try {
					getDelegate().setNull(parameterIndex, actualType);
					return;
				} catch (final java.sql.SQLException retry) {
					// Driver unhappy with the resolved type — fall back to caller's choice.
					LOG.debug("setNull with PMD type {} failed at param {}, retrying with caller type {}: {}",
							actualType, parameterIndex, sqlType, retry.getMessage());
				}
			}
			getDelegate().setNull(parameterIndex, sqlType);
		}

		/**
		 * Check if the parameter at the given index is a numeric SQL type
		 * according to ParameterMetaData.
		 */
		private boolean isNumericParameter(final int parameterIndex) {
			final int sqlType = getParameterSqlType(parameterIndex);
			return isNumericSqlType(sqlType);
		}

		/**
		 * Look up the JDBC SQL type for the given parameter index, or
		 * {@link java.sql.Types#OTHER} if PMD is unavailable. Centralised so
		 * setNull/setObject overrides can route DATE/TIME columns correctly.
		 *
		 * Note: ParameterMetaData is also fetched in DB2 mode (the DB2 jt400 driver
		 * supports it). Previously we restricted PMD lookup to PG mode, which meant
		 * DATE/TIME columns under DB2 only got special handling via the post-failure
		 * catch block in setObject — that catch never fired for setNull, leaving
		 * Types.VARCHAR being sent for a DATE column (Rule 16 fix).
		 */
		private int getParameterSqlType(final int parameterIndex) {
			final java.sql.ParameterMetaData pmd = getPmd();
			if (pmd == null) {
				return java.sql.Types.OTHER;
			}
			try {
				return pmd.getParameterType(parameterIndex);
			} catch (final java.sql.SQLException e) {
				return java.sql.Types.OTHER;
			}
		}

		private static boolean isNumericSqlType(final int sqlType) {
			return (sqlType == java.sql.Types.NUMERIC
				|| sqlType == java.sql.Types.DECIMAL
				|| sqlType == java.sql.Types.INTEGER
				|| sqlType == java.sql.Types.SMALLINT
				|| sqlType == java.sql.Types.BIGINT
				|| sqlType == java.sql.Types.DOUBLE
				|| sqlType == java.sql.Types.FLOAT
				|| sqlType == java.sql.Types.REAL);
		}

		private static boolean isDateSqlType(final int sqlType) {
			return sqlType == java.sql.Types.DATE;
		}

		private static boolean isTimeSqlType(final int sqlType) {
			return sqlType == java.sql.Types.TIME || sqlType == java.sql.Types.TIME_WITH_TIMEZONE;
		}

		private static boolean isTimestampSqlType(final int sqlType) {
			return sqlType == java.sql.Types.TIMESTAMP || sqlType == java.sql.Types.TIMESTAMP_WITH_TIMEZONE;
		}

		/**
		 * Try to bind a COBOL PIC X string to a DATE/TIME/TIMESTAMP parameter using
		 * the typed setter so PostgreSQL (and DB2 via jt400) accepts the value.
		 *
		 * Rule 16 (DATE/TIME UPDATE binding):
		 *   COBOL stores dates as PIC X(10) "YYYY-MM-DD" and times as PIC X(8)
		 *   "HH:MM:SS" (or "HH.MM.SS"). When ProLeap binds these via setObject/
		 *   setString the AS/400 native runtime accepts them implicitly, but
		 *   PostgreSQL refuses with SQLSTATE 42883 ("operator does not exist:
		 *   date = character varying"). The DB2 jt400 driver also rejects spaces
		 *   on a DATE column ("Data type mismatch"). Detect these cases via
		 *   ParameterMetaData and dispatch to setDate/setTime/setTimestamp,
		 *   falling back to setNull when the COBOL value is blank/zero (the COBOL
		 *   semantics on AS/400 are that a blank DATE column means "no value").
		 *
		 * @return true if the value was bound (caller should not call setString),
		 *         false if no special handling applied (caller should fall through).
		 */
		private boolean trySetTemporalFromString(final int parameterIndex, final String s) throws java.sql.SQLException {
			final int sqlType = getParameterSqlType(parameterIndex);
			if (!isDateSqlType(sqlType) && !isTimeSqlType(sqlType) && !isTimestampSqlType(sqlType)) {
				return false;
			}
			final String trimmed = s == null ? "" : s.trim();
			// Empty/blank COBOL value → NULL on the SQL side (matches AS/400 behaviour
			// where a PIC X DATE field full of spaces is treated as "no value" by DB2/400).
			if (trimmed.isEmpty()) {
				getDelegate().setNull(parameterIndex, sqlType);
				return true;
			}
			try {
				if (isDateSqlType(sqlType)) {
					// Accept "YYYY-MM-DD" (canonical) and "YYYY/MM/DD" (some COBOL flows).
					final String iso = trimmed.length() == 10 && trimmed.charAt(4) == '/' && trimmed.charAt(7) == '/'
						? trimmed.replace('/', '-') : trimmed;
					if (iso.length() == 10 && iso.charAt(4) == '-' && iso.charAt(7) == '-') {
						getDelegate().setDate(parameterIndex, java.sql.Date.valueOf(iso));
						return true;
					}
				} else if (isTimeSqlType(sqlType)) {
					// Accept "HH:MM:SS" (canonical) and "HH.MM.SS" (DB2/400 default).
					final String norm = trimmed.length() >= 8 && (trimmed.charAt(2) == '.' || trimmed.charAt(2) == ':')
						? trimmed.substring(0, 8).replace('.', ':') : trimmed;
					if (norm.length() == 8 && norm.charAt(2) == ':' && norm.charAt(5) == ':') {
						getDelegate().setTime(parameterIndex, java.sql.Time.valueOf(norm));
						return true;
					}
				} else if (isTimestampSqlType(sqlType)) {
					// COBOL timestamps are typically "YYYY-MM-DD HH:MM:SS[.ffffff]" or "YYYY-MM-DD-HH.MM.SS.ffffff".
					String ts = trimmed;
					if (ts.length() >= 19 && ts.charAt(10) == '-') {
						ts = ts.substring(0, 10) + " " + ts.substring(11).replace('.', ':');
						// Re-introduce fractional separator if present (after position 19)
						if (ts.length() > 19) {
							ts = ts.substring(0, 19) + "." + ts.substring(20).replace(':', '.');
						}
					}
					try {
						getDelegate().setTimestamp(parameterIndex, java.sql.Timestamp.valueOf(ts));
						return true;
					} catch (final IllegalArgumentException badTs) {
						// fall through to caller's String path
					}
				}
			} catch (final IllegalArgumentException parseErr) {
				LOG.debug("Rule 16: temporal parse failed for parameter {} value '{}': {}",
						parameterIndex, s, parseErr.getMessage());
			}
			// Could not parse — let caller try setString, which will surface a
			// meaningful error if the column truly cannot accept the value.
			return false;
		}

		/**
		 * Re-bind a tracked parameter map onto a freshly-prepared statement, using
		 * ParameterMetaData (when available) to pick the correct setter for each
		 * column type (numeric, date, time, timestamp, character).
		 *
		 * Used by both executeQuery and executeUpdate retry paths after PostgreSQL
		 * returns SQLSTATE 42883 from the first execute. Centralised so that
		 * Rule 16 (DATE/TIME via setDate/setTime) is applied identically in both.
		 */
		private static void bindParamsWithPmd(final java.sql.PreparedStatement retryPs,
				final java.util.Map<Integer, Object> paramValues) throws java.sql.SQLException {
			java.sql.ParameterMetaData pmd = null;
			try {
				pmd = retryPs.getParameterMetaData();
			} catch (final java.sql.SQLException pmdErr) {
				LOG.debug("ParameterMetaData not available for retry: {}", pmdErr.getMessage());
			}
			for (final java.util.Map.Entry<Integer, Object> entry : paramValues.entrySet()) {
				final int idx = entry.getKey();
				final Object val = entry.getValue();
				int sqlType = java.sql.Types.OTHER;
				if (pmd != null) {
					try { sqlType = pmd.getParameterType(idx); } catch (final java.sql.SQLException te) { /* ignore */ }
				}
				if (val == null) {
					// Use the actual column type when known so PG accepts the NULL.
					retryPs.setNull(idx, sqlType != java.sql.Types.OTHER ? sqlType : java.sql.Types.VARCHAR);
					continue;
				}
				final String strVal = val.toString();
				if (isDateSqlType(sqlType) || isTimeSqlType(sqlType) || isTimestampSqlType(sqlType)) {
					final String trimmed = strVal.trim();
					if (trimmed.isEmpty()) {
						retryPs.setNull(idx, sqlType);
						continue;
					}
					try {
						if (isDateSqlType(sqlType)) {
							final String iso = trimmed.length() == 10 && trimmed.charAt(4) == '/' && trimmed.charAt(7) == '/'
								? trimmed.replace('/', '-') : trimmed;
							retryPs.setDate(idx, java.sql.Date.valueOf(iso));
							continue;
						} else if (isTimeSqlType(sqlType)) {
							final String norm = trimmed.length() >= 8 && (trimmed.charAt(2) == '.' || trimmed.charAt(2) == ':')
								? trimmed.substring(0, 8).replace('.', ':') : trimmed;
							retryPs.setTime(idx, java.sql.Time.valueOf(norm));
							continue;
						} else if (isTimestampSqlType(sqlType)) {
							retryPs.setTimestamp(idx, java.sql.Timestamp.valueOf(trimmed));
							continue;
						}
					} catch (final IllegalArgumentException parseErr) {
						LOG.debug("retry: temporal parse failed for parameter {} value '{}': {}",
								idx, strVal, parseErr.getMessage());
						// fall through to setString
					}
				}
				if (isNumericSqlType(sqlType)) {
					final String trimmed = strVal.trim();
					if (!trimmed.isEmpty()) {
						try {
							retryPs.setBigDecimal(idx, new java.math.BigDecimal(trimmed));
							continue;
						} catch (final NumberFormatException nfe) {
							retryPs.setString(idx, strVal);
							continue;
						}
					} else {
						retryPs.setBigDecimal(idx, java.math.BigDecimal.ZERO);
						continue;
					}
				}
				retryPs.setString(idx, strVal);
			}
		}

		@Override
		public void setObject(final int parameterIndex, final Object x) throws java.sql.SQLException {
			// Track original value for executeQuery retry on type mismatch
			paramValues.put(parameterIndex, x);
			if (ProgramRunnerImpl.DEBUG && sql != null && sql.contains("JRNL210100")) {
				LOG.info("  [SQL PARAM {}] {} ({})", parameterIndex,
						x == null ? "null" : String.valueOf(x),
						x == null ? "null" : x.getClass().getSimpleName());
			}
			if (ProgramRunnerImpl.DEBUG && sql != null && sql.contains("FILE210200")) {
				System.out.println("[SQL-FILE210200] param " + parameterIndex + " = '" +
						(x == null ? "null" : String.valueOf(x)) + "' (" +
						(x == null ? "null" : x.getClass().getSimpleName()) + ")");
			}
			if (x instanceof String) {
				final String s = (String) x;
				// Rule 16: DATE/TIME/TIMESTAMP columns receive COBOL PIC X strings
				// like "2026-04-18" / "10:23:45". setString works on AS/400 jt400
				// for some shapes but PostgreSQL refuses ("operator does not exist:
				// date = character varying"). Use PMD to detect the real column
				// type and dispatch to setDate/setTime/setTimestamp directly. This
				// also normalises blank values to setNull so DH0001UC's UPDATE
				// against a DATE/TIME column pair succeeds on PG.
				if (trySetTemporalFromString(parameterIndex, s)) {
					return;
				}
				// PostgreSQL is strict about type casts. Use ParameterMetaData to
				// determine the actual column type and set the parameter accordingly.
				// Only send as BigDecimal if PMD confirms the column is numeric.
				// This avoids "operator does not exist: character = numeric" errors
				// that previously required retry logic.
				if (pgMode) {
					final String trimmed = s.trim();
					if (!trimmed.isEmpty() && isNumericParameter(parameterIndex)) {
						// Column IS numeric — send as BigDecimal
						try {
							getDelegate().setBigDecimal(parameterIndex, new java.math.BigDecimal(trimmed));
							return;
						} catch (NumberFormatException nfe) { /* not a valid number, use setString */ }
						catch (java.sql.SQLException e2) { /* setBigDecimal failed, use setString */ }
					}
					// Column is NOT numeric (CHAR/VARCHAR/etc.) or PMD unavailable — use setString
				}
				try {
					getDelegate().setString(parameterIndex, s);
				} catch (java.sql.SQLException e) {
					// DB2/jt400 retry: implicitly converts String to INTEGER/DECIMAL/DATE/TIME.

					// DATE format: YYYY-MM-DD (10 chars)
					if (s.length() == 10 && s.charAt(4) == '-' && s.charAt(7) == '-') {
						try {
							getDelegate().setDate(parameterIndex, java.sql.Date.valueOf(s));
							return;
						} catch (Exception e2) { /* fall through */ }
					}
					// TIME format: HH:MM:SS or HH.MM.SS (8 chars)
					if (s.length() >= 8 && (s.charAt(2) == ':' || s.charAt(2) == '.')) {
						try {
							final String timeStr = s.substring(0, 8).replace('.', ':');
							getDelegate().setTime(parameterIndex, java.sql.Time.valueOf(timeStr));
							return;
						} catch (Exception e2) { /* fall through */ }
					}
					// Numeric string -> INTEGER/DECIMAL column (PostgreSQL does not cast varchar to int)
					// COBOL PIC X fields often hold numeric values like "1" for CODSOC
					final String trimmed = s.trim();
					if (!trimmed.isEmpty()) {
						try {
							getDelegate().setBigDecimal(parameterIndex, new java.math.BigDecimal(trimmed));
							return;
						} catch (NumberFormatException nfe) { /* not numeric, fall through */ }
						catch (java.sql.SQLException e3) { /* setBigDecimal also failed, fall through */ }
					}
					throw e;
				}
			} else if (x instanceof java.math.BigDecimal) {
				if (pgMode) {
					// PostgreSQL: use PMD to determine if column is numeric or character.
					// If character, send as String to avoid type mismatch.
					if (!isNumericParameter(parameterIndex) && getPmd() != null) {
						// PMD says column is NOT numeric (CHAR/VARCHAR) — send as String
						getDelegate().setString(parameterIndex, ((java.math.BigDecimal) x).toPlainString());
					} else {
						// Column is numeric or PMD unavailable — try setBigDecimal, fallback to setString
						try {
							getDelegate().setBigDecimal(parameterIndex, (java.math.BigDecimal) x);
						} catch (java.sql.SQLException e) {
							getDelegate().setString(parameterIndex, ((java.math.BigDecimal) x).toPlainString());
						}
					}
				} else {
					getDelegate().setBigDecimal(parameterIndex, (java.math.BigDecimal) x);
				}
			} else {
				getDelegate().setObject(parameterIndex, x);
			}
		}

		@Override
		public java.sql.ResultSet executeQuery() throws java.sql.SQLException {
			if (isSqlTraceEnabled()) {
				traceSqlBefore("EXEC-QUERY", sql, paramValues);
			}
			java.sql.ResultSet rs;
			try {
				final long __sqlT0 = System.nanoTime();
				try {
					rs = getDelegate().executeQuery();
				} finally {
					io.proleap.cobol.runtime.SqlTiming.addNanos(System.nanoTime() - __sqlT0);
				}
			} catch (final java.sql.SQLException execErr) {
				// PostgreSQL defers type-checking to execute time. When a BigDecimal
				// parameter is sent for a CHAR column (or vice versa), executeQuery
				// fails with SQLSTATE 42883 "operator does not exist: character = numeric".
				// Retry with a FRESH PreparedStatement and all params as Strings so PG
				// can cast text to the target column type.
				// We must use a new PS because PostgreSQL's JDBC driver may leave the
				// original PS in an unusable state after a failed execute (the underlying
				// transaction is aborted even with autocommit, and clearParameters +
				// re-execute on the same PS object can silently fail or return wrong results).
				if (pgMode && "42883".equals(execErr.getSQLState()) && !paramValues.isEmpty() && sql != null) {
					LOG.info("executeQuery retrying with fresh PS (was: {})", execErr.getMessage());
					try {
						final java.sql.Connection conn = getDelegate().getConnection();
						final long __sqlT1 = System.nanoTime();
						final java.sql.PreparedStatement retryPs;
						try {
							retryPs = conn.prepareStatement(sql);
						} finally {
							io.proleap.cobol.runtime.SqlTiming.addNanos(System.nanoTime() - __sqlT1);
						}
						bindParamsWithPmd(retryPs, paramValues);
						final long __sqlT2 = System.nanoTime();
						try {
							rs = retryPs.executeQuery();
						} finally {
							io.proleap.cobol.runtime.SqlTiming.addNanos(System.nanoTime() - __sqlT2);
						}
					} catch (final java.sql.SQLException retryErr) {
						// If the fresh PS also fails, throw the original error
						LOG.warn("executeQuery retry also failed (sql={}): {}", sql != null ? sql.substring(0, Math.min(60, sql.length())) : "?", retryErr.getMessage());
						throw execErr;
					}
				} else {
					throw execErr;
				}
			}
			if (ProgramRunnerImpl.DEBUG && sql != null && sql.contains("JRNL210100")) {
				// Log whether the query returned results
				try {
					final boolean hasRows = rs.isBeforeFirst();
					LOG.info("  [SQL RESULT] hasRows={} for: {}", hasRows, sql);
				} catch (final java.sql.SQLException e) {
					LOG.info("  [SQL RESULT] (could not check hasRows) for: {}", sql);
				}
			}
			if (ProgramRunnerImpl.DEBUG && sql != null && sql.contains("FILE210200")) {
				try {
					final boolean hasRows = rs.isBeforeFirst();
					System.out.println("[SQL-FILE210200] hasRows=" + hasRows + " for: " + sql);
				} catch (final java.sql.SQLException e) {
					System.out.println("[SQL-FILE210200] (could not check hasRows) for: " + sql);
				}
			}
			if (isSqlTraceEnabled()) {
				// SELECT row counts are unknown until the consumer iterates. Emit
				// hasRows (cheap, non-destructive on forward-only ResultSets) so the
				// trace line has some signal without perturbing cursor position.
				long indicator = -1;
				try {
					indicator = rs.isBeforeFirst() ? 1 : 0;
				} catch (final java.sql.SQLException ignore) {
					// isBeforeFirst is optional on some drivers — leave as -1.
				}
				System.err.println("[SQL-TRACE] <<< prog=" + currentProgramName()
					+ " phase=EXEC-QUERY hasRows=" + (indicator < 0 ? "?" : String.valueOf(indicator == 1)));
			}
			return rs;
		}

		@Override
		public int executeUpdate() throws java.sql.SQLException {
			if (isSqlTraceEnabled()) {
				traceSqlBefore("EXEC-UPDATE", sql, paramValues);
			}
			try {
				// PG positioned UPDATE/DELETE: bind ctid of cursor's current row as last param
				bindPositionedCursorCtidIfNeeded();
				final int result;
				try {
					final long __sqlT0 = System.nanoTime();
					try {
						result = getDelegate().executeUpdate();
					} finally {
						io.proleap.cobol.runtime.SqlTiming.addNanos(System.nanoTime() - __sqlT0);
					}
				} catch (final java.sql.SQLException execErr) {
					if (pgMode && "42883".equals(execErr.getSQLState()) && !paramValues.isEmpty() && sql != null) {
						LOG.info("executeUpdate retrying with fresh PS (was: {})", execErr.getMessage());
						try {
							final java.sql.Connection conn = getDelegate().getConnection();
							final long __sqlT1 = System.nanoTime();
							final java.sql.PreparedStatement retryPs;
							try {
								retryPs = conn.prepareStatement(sql);
							} finally {
								io.proleap.cobol.runtime.SqlTiming.addNanos(System.nanoTime() - __sqlT1);
							}
							bindParamsWithPmd(retryPs, paramValues);
							// Re-bind ctid for positioned UPDATE/DELETE on the retry PS too.
							if (positionedCursorName != null && owningService != null) {
								final String ctid = owningService.getCurrentCtidForCursor(positionedCursorName);
								if (ctid != null) {
									try {
										final int slot = retryPs.getParameterMetaData().getParameterCount();
										retryPs.setString(slot, ctid);
									} catch (final java.sql.SQLException ctidErr) {
										LOG.warn("Positioned UPDATE/DELETE retry: could not bind ctid: {}", ctidErr.getMessage());
									}
								}
							}
							final long __sqlT2 = System.nanoTime();
							final int retryResult;
							try {
								retryResult = retryPs.executeUpdate();
							} finally {
								io.proleap.cobol.runtime.SqlTiming.addNanos(System.nanoTime() - __sqlT2);
							}
							retryPs.close();
							if (isSqlTraceEnabled()) {
								traceSqlAfter("EXEC-UPDATE(retry)", retryResult);
							}
							return retryResult;
						} catch (final java.sql.SQLException retryErr) {
							LOG.warn("executeUpdate retry also failed: {}", retryErr.getMessage());
							throw execErr;
						}
					}
					throw execErr;
				}
				if (isSqlTraceEnabled()) {
					traceSqlAfter("EXEC-UPDATE", result);
				}
				return result;
			} finally {
				// Connection is no longer needed after UPDATE/INSERT/DELETE.
				// Release it immediately to return it to the pool.
				if (connectionReleaser != null) {
					connectionReleaser.run();
				}
			}
		}
	}
}
