package io.proleap.cobol.runtime;

/**
 * Centralised runtime debug flags.
 *
 * <p>All diagnostic/informational stderr/stdout emissions in the runtime must
 * guard on one of the flags below so that normal production runs (UAT, sweeps)
 * are not spammed. Exceptions, stack traces and real error messages are NOT
 * gated — only informational/diagnostic logs.
 *
 * <p>Flags are read once at class load; there is no runtime toggle.
 *
 * <h3>How to enable</h3>
 * <ul>
 *   <li>{@code -Dcobol.debug=true} — turns ON all debug logging (DEBUG,
 *       TIMING, SQL_TRACE, MOVECORR_TRACE).</li>
 *   <li>{@code -Dcobol.timing=true} — only {@code [TIMING]} lines.</li>
 *   <li>{@code -Dcobol.sql.trace=true} — only {@code [SQL-TRACE]} lines.</li>
 *   <li>{@code -Dcobol.movecorr.trace=true} — only {@code [MOVECORR-TRACE]} lines.</li>
 * </ul>
 *
 * <p>The master {@link #DEBUG} flag enables every category; the granular flags
 * let callers turn on a single category without the noise of the others.
 */
public final class DebugFlags {

	/**
	 * Master switch. When {@code -Dcobol.debug=true} is set, every debug
	 * category below is forced ON.
	 */
	public static final boolean DEBUG = Boolean.getBoolean("cobol.debug");

	/**
	 * {@code [TIMING]} lines emitted by {@code ProgramRunnerImpl} for each
	 * CALL. Enable with {@code -Dcobol.timing=true} or {@code -Dcobol.debug=true}.
	 */
	public static final boolean TIMING = DEBUG || Boolean.getBoolean("cobol.timing");

	/**
	 * {@code [SQL-TRACE]} / {@code [SQL]} lines emitted by {@code SqlServiceImpl}
	 * around every JDBC call. Enable with {@code -Dcobol.sql.trace=true} or
	 * {@code -Dcobol.debug=true}.
	 *
	 * <p>Kept backwards-compatible with the pre-existing {@code cobol.sql.trace}
	 * property name.
	 */
	public static final boolean SQL_TRACE = DEBUG || Boolean.getBoolean("cobol.sql.trace");

	/**
	 * {@code [MOVECORR-TRACE]} lines emitted by {@code CobolMove#moveCorresponding}.
	 * Enable with {@code -Dcobol.movecorr.trace=true} or {@code -Dcobol.debug=true}.
	 */
	public static final boolean MOVECORR_TRACE = DEBUG || Boolean.getBoolean("cobol.movecorr.trace");

	/**
	 * Strict mode for CALL errors emitted by {@code CallStatementRule}.
	 *
	 * <p>When {@code false} (default), an unhandled {@code Exception} inside a
	 * generated {@code try { programRunner.call(...) } catch (Exception e)}
	 * block is logged to stderr as
	 * {@code [CALL ERROR] <prog>: <class>: <message>} and then swallowed — this
	 * matches the historical behaviour needed for many COBOL programs that
	 * rely on {@code CALL ... ON EXCEPTION ... END-CALL} semantics continuing
	 * past transient errors.
	 *
	 * <p>When {@code true}, the generated catch block re-throws the exception
	 * wrapped in a {@link RuntimeException} so the caller sees the failure
	 * instead of silently running against empty / uninitialised LINKAGE
	 * fields. Intended for debugging sessions where a silent failure is
	 * suspected.
	 *
	 * <p>Enable with {@code -Dcobol.strict.call.errors=true} or
	 * {@code STRICT_CALL_ERRORS=1} in the environment.
	 */
	public static final boolean STRICT_CALL_ERRORS =
			Boolean.getBoolean("cobol.strict.call.errors")
			|| "1".equals(System.getenv("STRICT_CALL_ERRORS"))
			|| "true".equalsIgnoreCase(System.getenv("STRICT_CALL_ERRORS"));

	/**
	 * When {@code true} (default), the generated {@code [CALL ERROR]} stderr
	 * line is followed by a {@link Throwable#printStackTrace} of the captured
	 * exception. This is always safe — it runs only inside the non-strict
	 * catch branch, i.e. only when something has actually gone wrong — and
	 * makes silent swallow paths visible in harness stderr captures without
	 * changing runtime behaviour.
	 *
	 * <p>Disable with {@code -Dcobol.call.error.stacktrace=false} for noise-
	 * sensitive runs where only the summary line is desired.
	 */
	public static final boolean CALL_ERROR_STACKTRACE =
			!"false".equalsIgnoreCase(System.getProperty("cobol.call.error.stacktrace", "true"));

	/**
	 * Programs selected for detailed CALL tracing.
	 *
	 * <p>Set {@code -Dcobol.trace.programs=RPT001,RPT002} to emit
	 * {@code [CALL-TRACE]} lines for those programs only: inbound parameters,
	 * LINKAGE values on return, and caller parameters after BY REFERENCE
	 * copy-back. Names are matched case-insensitively.
	 *
	 * <p>Empty by default. The master {@link #DEBUG} switch traces every
	 * program regardless of this list.
	 */
	private static final java.util.Set<String> TRACE_PROGRAMS = parseTracePrograms();

	/**
	 * Maximum number of characters printed per value in {@code [CALL-TRACE]}
	 * output, so one CALL on a large group cannot flood the log.
	 * Override with {@code -Dcobol.trace.value.length=200}.
	 */
	public static final int TRACE_VALUE_LENGTH =
			Integer.getInteger("cobol.trace.value.length", 60);

	private static java.util.Set<String> parseTracePrograms() {
		final String configured = System.getProperty("cobol.trace.programs");

		if (configured == null || configured.isBlank()) {
			return java.util.Collections.emptySet();
		}

		final java.util.Set<String> result = new java.util.HashSet<>();

		for (final String name : configured.split(",")) {
			final String trimmed = name.trim();

			if (!trimmed.isEmpty()) {
				result.add(trimmed.toUpperCase(java.util.Locale.ROOT));
			}
		}

		return java.util.Collections.unmodifiableSet(result);
	}

	/**
	 * Returns {@code true} when the given COBOL program should emit
	 * {@code [CALL-TRACE]} diagnostics — either because {@link #DEBUG} is on,
	 * or because the program was named in {@code -Dcobol.trace.programs}.
	 *
	 * @param programName normalized COBOL program name; {@code null} is safe
	 */
	public static boolean isTraced(final String programName) {
		if (DEBUG) {
			return true;
		}

		if (programName == null || TRACE_PROGRAMS.isEmpty()) {
			return false;
		}

		return TRACE_PROGRAMS.contains(programName.toUpperCase(java.util.Locale.ROOT));
	}

	private DebugFlags() {
		// utility class
	}
}
