package io.proleap.cobol.runtime.impl;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.proleap.cobol.runtime.CobolProgram;
import io.proleap.cobol.runtime.CobolStopRunException;
import io.proleap.cobol.runtime.DebugFlags;
import io.proleap.cobol.runtime.FileControlService;
import io.proleap.cobol.runtime.ProgramRunner;
import io.proleap.cobol.runtime.SqlService;

/**
 * Implementation of ProgramRunner that resolves COBOL CALL statements
 * to Java class instances by program name.
 */
public class ProgramRunnerImpl implements ProgramRunner {

	private static final Logger LOG = LoggerFactory.getLogger(ProgramRunnerImpl.class);

	/**
	 * Enable verbose COBOL debug logging via {@code -Dcobol.debug=true}.
	 * Delegated to {@link DebugFlags#DEBUG} so all runtime debug categories
	 * share a single master switch.
	 */
	public static final boolean DEBUG = DebugFlags.DEBUG;

	private static int callDepth = 0;

	/**
	 * Base packages scanned when resolving a program name to a Java class via
	 * {@link #resolveProgramClass(String)}. The list starts with the empty
	 * string (default / unnamed package, used by the COBOL transformer for
	 * programs emitted into {@code generated/cobol/}) followed by the
	 * namespaces used by other language transformers (CL, RPG, ...) so the
	 * same runtime can host sub-programs from multiple source dialects.
	 *
	 * <p>Packages can be extended at startup via
	 * {@link #addProgramPackage(String)} or the {@code cobol.program.packages}
	 * system property (comma-separated). The list is intentionally ordered
	 * lowest-priority first so that a program defined in the default COBOL
	 * namespace always wins over a same-named class in a dialect package.</p>
	 */
	private static final java.util.List<String> PROGRAM_PACKAGES = new java.util.concurrent.CopyOnWriteArrayList<>();
	static {
		PROGRAM_PACKAGES.add(""); // default (unnamed) package — COBOL transformer output
		PROGRAM_PACKAGES.add("io.proleap.cobol.generated.cobol");
		PROGRAM_PACKAGES.add("io.proleap.cobol.generated.cl");
		// System property override / extension (e.g. -Dcobol.program.packages=io.proleap.cobol.generated.rpg,foo.bar)
		final String override = System.getProperty("cobol.program.packages");
		if (override != null && !override.isBlank()) {
			for (final String pkg : override.split(",")) {
				final String trimmed = pkg.trim();
				if (!trimmed.isEmpty() && !PROGRAM_PACKAGES.contains(trimmed)) {
					PROGRAM_PACKAGES.add(trimmed);
				}
			}
		}
	}

	/** Cache of resolved program-name → Class (or a sentinel for "not found"). */
	private static final java.util.concurrent.ConcurrentHashMap<String, Class<?>> RESOLVED_CLASS_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
	private static final Class<?> NOT_FOUND_SENTINEL = NotFoundSentinel.class;
	private static final class NotFoundSentinel { /* marker only */ }

	/**
	 * Register an additional base package to scan when resolving a program
	 * name to a class. Duplicates are ignored. Changes take effect
	 * immediately and invalidate the resolve cache.
	 *
	 * <p>Intended for harnesses that add generator dialects at runtime (for
	 * example the RPG or CL package may be contributed by a separate JAR
	 * that calls this method in its static initializer).</p>
	 */
	public static void addProgramPackage(final String basePackage) {
		if (basePackage == null) return;
		final String trimmed = basePackage.trim();
		// Allow "" (default package) explicitly; otherwise ignore blanks.
		if (trimmed.isEmpty() && !basePackage.isEmpty()) return;
		if (!PROGRAM_PACKAGES.contains(trimmed)) {
			PROGRAM_PACKAGES.add(trimmed);
			RESOLVED_CLASS_CACHE.clear(); // a newly-registered package may cover a previously-missing name
		}
	}

	/**
	 * Returns an unmodifiable snapshot of the packages currently scanned by
	 * {@link #resolveProgramClass(String)}. Primarily useful for tests and
	 * diagnostic logging.
	 */
	public static java.util.List<String> getProgramPackages() {
		return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(PROGRAM_PACKAGES));
	}

	/**
	 * Resolve a (COBOL or CL or …) program name to a loaded {@link Class}
	 * by scanning the configured base packages. Returns {@code null} when
	 * no package contains a class with the given simple name.
	 *
	 * <p>The lookup uses {@code Class.forName(fqn, false, contextLoader)}
	 * so static initializers of unrelated classes are NOT run during the
	 * scan; this matches the convention already established in
	 * {@code Dds2ReactServer} pre-scan.</p>
	 *
	 * <p>Results are cached (including negative lookups) to keep hot-path
	 * CALL resolution O(1) after the first hit.</p>
	 */
	public static Class<?> resolveProgramClass(final String programName) {
		if (programName == null) return null;
		final String simpleName = programName.replaceAll("'", "").replaceAll("\"", "").trim();
		if (simpleName.isEmpty()) return null;

		final Class<?> cached = RESOLVED_CLASS_CACHE.get(simpleName);
		if (cached != null) {
			return cached == NOT_FOUND_SENTINEL ? null : cached;
		}

		final ClassLoader cl = Thread.currentThread().getContextClassLoader();
		for (final String pkg : PROGRAM_PACKAGES) {
			final String fqn = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
			try {
				final Class<?> clazz = Class.forName(fqn, false, cl != null ? cl : ProgramRunnerImpl.class.getClassLoader());
				RESOLVED_CLASS_CACHE.put(simpleName, clazz);
				return clazz;
			} catch (final ClassNotFoundException ignored) {
				// try next package
			} catch (final Throwable t) {
				// Any other loading error (LinkageError, etc.) — skip this package but keep scanning.
				if (DEBUG) {
					System.err.println("[resolveProgramClass] " + fqn + " skipped: "
							+ t.getClass().getSimpleName() + ": " + t.getMessage());
				}
			}
		}
		RESOLVED_CLASS_CACHE.put(simpleName, NOT_FOUND_SENTINEL);
		return null;
	}

	private final Map<String, CobolProgram> programs = new HashMap<>();

	/** Set of programs that have been CANCEL'd and need re-initialization on next CALL. */
	private final java.util.Set<String> canceledPrograms = new java.util.HashSet<>();

	private final FileControlService fileControlService;

	private final SqlService sqlService;

	/**
	 * Stores the LINKAGE parameter values from the last CALL, for BY REFERENCE copy-back.
	 */
	private Object[] lastCallLinkageResult;

	public ProgramRunnerImpl() {
		this.fileControlService = null;
		this.sqlService = null;
	}

	public ProgramRunnerImpl(final FileControlService fileControlService, final SqlService sqlService) {
		this.fileControlService = fileControlService;
		this.sqlService = sqlService;
	}

	/**
	 * Register a program by name for CALL resolution.
	 */
	public void register(final String programName, final CobolProgram program) {
		programs.put(normalizeProgamName(programName), program);
	}

	/**
	 * Register a program by class, using the class simple name as program name.
	 */
	public void register(final CobolProgram program) {
		programs.put(normalizeProgamName(program.getClass().getSimpleName()), program);
	}

	/**
	 * CANCEL statement: marks a program for re-initialization on next CALL.
	 * Per IBM ILE COBOL: CANCEL releases the program's resources and ensures
	 * that the next CALL will reinitialize working storage to VALUE clauses.
	 */
	@Override
	public void cancel(final String programName) {
		final String normalized = normalizeProgamName(programName);
		canceledPrograms.add(normalized);
		if (DEBUG) {
			System.out.println("  ".repeat(callDepth) + "[CANCEL] " + normalized);
		}
	}

	@Override
	public Object call(final String programName, final Object... parameters) {
		final String normalized = normalizeProgamName(programName);

		lastCallLinkageResult = null;

		CobolProgram program = programs.get(normalized);

		// IBM ILE COBOL CANCEL semantics: In COBOL, CANCEL marks a program
		// for re-initialization on next CALL. In practice, nearly every CALL
		// is followed by CANCEL in the codebase, meaning each CALL should get
		// fresh working storage (VALUE ZEROS/SPACES). We implement this by
		// always creating a fresh instance for each CALL. This ensures
		// accumulators (PIC S9(09) VALUE ZEROS) start at zero each time,
		// preventing double-counting bugs when a program is called multiple
		// times within the same parent execution.
		if (program != null) {
			canceledPrograms.remove(normalized); // clear any explicit cancel flag
			try {
				final CobolProgram freshInstance = program.getClass().getDeclaredConstructor().newInstance();
				programs.put(normalized, freshInstance);
				program = freshInstance;
			} catch (final Exception e) {
				LOG.debug("Could not create fresh instance of {}, reusing existing: {}", normalized, e.getMessage());
				// Fall back to existing instance
			}
		}

		if (program == null) {
			// Last-chance lazy resolution: scan the configured base packages
			// (default + io.proleap.cobol.generated.cobol + io.proleap.cobol.generated.cl + ...)
			// before failing. This lets a harness that hasn't pre-registered
			// sub-programs still resolve CL/COBOL/RPG classes by name.
			final Class<?> resolved = resolveProgramClass(normalized);
			if (resolved != null && CobolProgram.class.isAssignableFrom(resolved)) {
				try {
					final CobolProgram instance = (CobolProgram) resolved.getDeclaredConstructor().newInstance();
					register(normalized, instance);
					program = instance;
					if (DEBUG) {
						System.out.println("  ".repeat(callDepth) + "[CALL] " + normalized
								+ " → lazy-resolved from " + resolved.getName());
					}
				} catch (final Exception e) {
					LOG.debug("Lazy instantiation failed for {}: {}", normalized, e.getMessage());
				}
			}
		}

		if (program == null) {
			if (DEBUG) {
				final String indent = "  ".repeat(callDepth);
				System.out.println(indent + "[CALL] " + normalized + " → NOT FOUND (runner@" + System.identityHashCode(this) + " has " + programs.size() + " programs)");
			}
			LOG.warn("Program not found: {} (normalized: {}). Runner has {} programs: {}", programName, normalized, programs.size(), programs.keySet());
			throw new RuntimeException("Program not found: " + programName);
		}

		if (DEBUG) {
			final String indent = "  ".repeat(callDepth);
			System.out.println(indent + "[CALL] " + normalized + " (runner@" + System.identityHashCode(this) + ", " + programs.size() + " progs, " + (parameters != null ? parameters.length : 0) + " params)");
		}

		program.init(fileControlService, sqlService, this);

		// Trace of inbound CALL parameters.
		// Enable for selected programs with -Dcobol.trace.programs=PROG1,PROG2
		// (or -Dcobol.debug=true for every program).
		if (DebugFlags.isTraced(normalized)) {
			final String indent = "  ".repeat(callDepth);
			System.out.println(indent + "[CALL-TRACE] CALL " + normalized + " params=" + formatTraceValues(parameters));
		}

		// Pass LINKAGE parameters (CALL USING semantics)
		final long t0 = System.nanoTime();
		if (parameters != null && parameters.length > 0) {
			setLinkageFieldsViaReflection(program, parameters);
		}
		final long t1 = System.nanoTime();
		final long sqlSnap = io.proleap.cobol.runtime.SqlTiming.snapshot();

		callDepth++;
		// Publish the current COBOL program name to SqlServiceImpl's thread-local
		// so [SQL-TRACE] lines can tag each query with its calling program.
		// No-op when -Dcobol.sql.trace=true is not set.
		SqlServiceImpl.pushCurrentProgram(normalized);
		try {
			program.procedureDivision();
		} catch (final CobolStopRunException e) {
			// Normal GOBACK from called program
		} catch (final RuntimeException e) {
			if (DEBUG) {
				final String indent = "  ".repeat(callDepth);
				System.out.println(indent + "[EXCEPTION] " + normalized + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
				if (e instanceof IndexOutOfBoundsException || (e.getCause() != null && e.getCause() instanceof IndexOutOfBoundsException)
						|| (e.getCause() != null && e.getCause() instanceof ArithmeticException)
						|| DebugFlags.isTraced(normalized)) {
					System.out.println(indent + "[EXCEPTION-STACKTRACE] " + normalized + ":");
					e.printStackTrace(System.out);
				}
			}
			throw e;
		} catch (final Exception e) {
			if (DEBUG) {
				final String indent = "  ".repeat(callDepth);
				System.out.println(indent + "[EXCEPTION] " + normalized + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
			}
			throw new RuntimeException(e);
		} finally {
			callDepth--;
			SqlServiceImpl.popCurrentProgram();
			// Debug code removed (was for Fix #34 599 investigation)
		}
		final long t2 = System.nanoTime();
		final long sqlNs = io.proleap.cobol.runtime.SqlTiming.since(sqlSnap);
		final long sqlMs = sqlNs / 1_000_000;

		// Trace of LINKAGE values after the called program returned.
		// Enable with -Dcobol.trace.programs=... (see DebugFlags).
		if (DebugFlags.isTraced(normalized)) {
			final String indent = "  ".repeat(callDepth);
			final Object[] postValues = getLinkageFieldsViaReflection(program);
			if (postValues != null) {
				System.out.println(indent + "[CALL-TRACE] RETURN " + normalized + " linkage=" + formatTraceValues(postValues));
			}
		}

		// Copy-back LINKAGE parameters (BY REFERENCE semantics)
		if (parameters != null && parameters.length > 0) {
			final Object[] updatedValues = getLinkageFieldsViaReflection(program);
			if (updatedValues != null) {
				lastCallLinkageResult = updatedValues;
				for (int i = 0; i < Math.min(parameters.length, updatedValues.length); i++) {
					if (updatedValues[i] != null) {
						// Handle Boolean↔String cross-type copy-back
						if (updatedValues[i] instanceof Boolean && parameters[i] instanceof String) {
							// Callee boolean → caller String: use \u0001/\u0000 (COBOL PIC 1 byte values)
							parameters[i] = ((Boolean) updatedValues[i]) ? "\u0001" : "\u0000";
						} else if (updatedValues[i] instanceof String && parameters[i] instanceof Boolean) {
							// Callee String → caller boolean: convert "1"/"\u0001" to true
							final String s = (String) updatedValues[i];
							parameters[i] = s.length() > 0 && (s.charAt(0) == '1' || s.charAt(0) == '\u0001');
						} else if (parameters[i] != null && !parameters[i].getClass().isAssignableFrom(updatedValues[i].getClass())
								&& !(updatedValues[i] instanceof String) && !(updatedValues[i] instanceof java.math.BigDecimal)
								&& !(updatedValues[i] instanceof Boolean)) {
							// Use flat-string copy for group-to-group copy-back (matching COBOL flat-memory semantics)
							// This is more reliable than field-by-field copyGroupBack for nested groups
							try {
								final String sourceFlat = io.proleap.cobol.runtime.CobolMove.groupToString(updatedValues[i]);
								final int targetSize = io.proleap.cobol.runtime.CobolMove.getGroupSize(parameters[i]);
								if (sourceFlat.length() == targetSize) {
									// Same size: direct flat-string copy (fastest, most reliable)
									io.proleap.cobol.runtime.CobolMove.moveStringToGroup(sourceFlat, parameters[i]);
								} else {
									// Size mismatch: caller and callee have different-sized groups for the
									// same COBOL parameter. In AS/400, CALL BY REFERENCE shares the same
									// memory block. The callee writes to its portion (first calleeSize bytes)
									// and the caller reads from the same physical offsets.
									// Emulate shared memory: overlay callee's bytes on the FIRST N bytes
									// of the caller's group, leaving bytes beyond calleeSize unchanged.
									final String callerFlat = io.proleap.cobol.runtime.CobolMove.groupToString(parameters[i]);
									final String merged;
									if (sourceFlat.length() < targetSize) {
										// Callee smaller than caller: overlay first calleeSize bytes, keep rest
										merged = sourceFlat + callerFlat.substring(sourceFlat.length());
									} else {
										// Callee larger than caller: truncate to caller size
										merged = sourceFlat.substring(0, targetSize);
									}
									io.proleap.cobol.runtime.CobolMove.moveStringToGroup(merged, parameters[i]);
									if (DEBUG) {
										System.out.println("  ".repeat(callDepth) + "[COPY-BACK] param " + i + " partial overlay: source=" + sourceFlat.length() + "b into target=" + targetSize + "b");
									}
								}
							} catch (final Exception e) {
								LOG.warn("Copy-back failed for param {}: {}", i, e.getMessage());
							}
						} else {
							parameters[i] = updatedValues[i];
						}
					}
				}
			}
		}
		final long t3 = System.nanoTime();

		// Trace of caller parameters after BY REFERENCE copy-back.
		if (DebugFlags.isTraced(normalized) && parameters != null) {
			final String indent = "  ".repeat(callDepth);
			System.out.println(indent + "[CALL-TRACE] COPY-BACK " + normalized + " params=" + formatTraceValues(parameters));
		}

		// TIMING LOG: emits a breakdown for every CALL slower than 10 ms.
		// Gated on DebugFlags.TIMING so production runs stay silent; enable with
		// -Dcobol.timing=true (or -Dcobol.debug=true for the master switch).
		if (DebugFlags.TIMING) {
			final long totalMs = (t3 - t0) / 1_000_000;
			final long linkageSetMs = (t1 - t0) / 1_000_000;
			final long execMs = (t2 - t1) / 1_000_000;
			final long copyBackMs = (t3 - t2) / 1_000_000;
			if (totalMs > 10) {
				System.out.println("[TIMING] " + normalized + " total=" + totalMs + "ms (linkageSet=" + linkageSetMs + "ms exec=" + execMs + "ms sql=" + sqlMs + "ms copyBack=" + copyBackMs + "ms) params=" + (parameters != null ? parameters.length : 0));
			}
		}

		return program.getReturnCode();
	}

	/**
	 * Returns the LINKAGE parameter values from the last CALL.
	 * Used by generated code for BY REFERENCE copy-back semantics.
	 * Returns null if the last call did not produce linkage results.
	 */
	public Object[] getLastCallLinkageResult() {
		return lastCallLinkageResult;
	}

	/**
	 * Sets LINKAGE fields on the target program by discovering field names from source
	 * and using reflection. When types don't match (e.g., caller's WkApiControlType vs
	 * callee's LkPARAMType), falls back to group-to-string conversion (flat byte copy).
	 */
	private void setLinkageFieldsViaReflection(final CobolProgram program, final Object[] parameters) {
		// Discover LINKAGE field names from the program's source file
		final String[] linkageNames = discoverLinkageFieldNames(program.getClass());
		if (linkageNames == null || linkageNames.length == 0) {
			// Fallback to setLinkageParameters
			program.setLinkageParameters(parameters);
			return;
		}

		if (DEBUG) {
			final String indent = "  ".repeat(callDepth);
			System.out.println(indent + "[LINKAGE SET] " + program.getClass().getSimpleName() + " fields=" + java.util.Arrays.toString(linkageNames) + " params=" + parameters.length);
		}

		final int count = Math.min(parameters.length, linkageNames.length);
		for (int i = 0; i < count; i++) {
			if (parameters[i] == null) continue;
			final String fieldName = linkageNames[i];

			try {
				final java.lang.reflect.Field f = findField(program.getClass(), fieldName);
				if (f == null) {
					if (DEBUG) {
						System.out.println("  ".repeat(callDepth) + "  [LINKAGE] field '" + fieldName + "' NOT FOUND in " + program.getClass().getSimpleName());
					}
					continue;
				}
				f.setAccessible(true);

				// Try direct assignment first
				if (f.getType().isAssignableFrom(parameters[i].getClass())) {
					f.set(program, parameters[i]);
					if (DEBUG) System.out.println("  ".repeat(callDepth) + "  [LINKAGE] " + fieldName + " = direct assign (" + f.getType().getSimpleName() + ")");
				} else if (parameters[i] instanceof String && f.getType() == String.class) {
					f.set(program, parameters[i]);
					if (DEBUG) System.out.println("  ".repeat(callDepth) + "  [LINKAGE] " + fieldName + " = String '" + ((String)parameters[i]).substring(0, Math.min(20, ((String)parameters[i]).length())) + "'");
				} else if (parameters[i] instanceof java.math.BigDecimal && f.getType() == java.math.BigDecimal.class) {
					f.set(program, parameters[i]);
					if (DEBUG) System.out.println("  ".repeat(callDepth) + "  [LINKAGE] " + fieldName + " = BigDecimal " + parameters[i]);
				} else if (parameters[i] instanceof Boolean && (f.getType() == boolean.class || f.getType() == Boolean.class)) {
					// Boolean→boolean: direct assign (handles primitive/wrapper mismatch)
					f.set(program, parameters[i]);
					if (DEBUG) System.out.println("  ".repeat(callDepth) + "  [LINKAGE] " + fieldName + " = boolean " + parameters[i]);
				} else if (parameters[i] instanceof String && (f.getType() == boolean.class || f.getType() == Boolean.class)) {
					// String→boolean: COBOL PIC X caller passing to PIC 1 callee
					final String s = (String) parameters[i];
					final boolean val = s.length() > 0 && (s.charAt(0) == '1' || s.charAt(0) == '\u0001');
					f.set(program, val);
					if (DEBUG) System.out.println("  ".repeat(callDepth) + "  [LINKAGE] " + fieldName + " = String→boolean '" + s + "'→" + val);
				} else if (parameters[i] instanceof Boolean && f.getType() == String.class) {
					// Boolean→String: COBOL PIC 1 caller passing to PIC X callee
					// Use \u0001/\u0000 (COBOL PIC 1 byte values B"1"/B"0") not "1"/"0" (display chars)
					final String val = ((Boolean) parameters[i]) ? "\u0001" : "\u0000";
					f.set(program, val);
					if (DEBUG) System.out.println("  ".repeat(callDepth) + "  [LINKAGE] " + fieldName + " = boolean→String " + parameters[i] + "→'" + (((Boolean) parameters[i]) ? "\\u0001" : "\\u0000") + "'");
				} else if (parameters[i] instanceof java.math.BigDecimal && f.getType() == String.class) {
					// BigDecimal→String: COBOL numeric caller (PIC 9) passing to alphanumeric callee (PIC X)
					// In COBOL, this is a flat byte copy of the numeric display representation.
					final java.math.BigDecimal bd = (java.math.BigDecimal) parameters[i];
					final String currentVal = (String) f.get(program);
					final int targetLen = currentVal != null ? currentVal.length() : 1;
					final String val = io.proleap.cobol.runtime.CobolMove.moveNumericToAlphanumeric(bd, targetLen);
					f.set(program, val);
					if (DEBUG) System.out.println("  ".repeat(callDepth) + "  [LINKAGE] " + fieldName + " = BigDecimal→String " + bd + "→'" + val + "'");
				} else if (parameters[i] instanceof String && f.getType() == java.math.BigDecimal.class) {
					// String→BigDecimal: COBOL alphanumeric caller (PIC X) passing to numeric callee (PIC 9)
					final String s = ((String) parameters[i]).trim();
					java.math.BigDecimal val;
					try {
						val = s.isEmpty() ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(s);
					} catch (final NumberFormatException nfe) {
						val = java.math.BigDecimal.ZERO;
					}
					f.set(program, val);
					if (DEBUG) System.out.println("  ".repeat(callDepth) + "  [LINKAGE] " + fieldName + " = String→BigDecimal '" + s + "'→" + val);
				} else {
					// Type mismatch — use group-to-string conversion (COBOL flat byte copy)
					final Object targetField = f.get(program);
					if (targetField != null) {
						final String sourceFlat = io.proleap.cobol.runtime.CobolMove.groupToString(parameters[i]);
						final int targetSize = io.proleap.cobol.runtime.CobolMove.getGroupSize(targetField);
						// Debug: log size mismatches for group-to-group mappings.
						// Informational — the code below handles the mismatch deterministically,
						// so gate on DEBUG to avoid noisy stderr in production runs.
						if (DEBUG && sourceFlat.length() != targetSize) {
							System.err.println("[SIZE MISMATCH] " + program.getClass().getSimpleName() + "." + fieldName
								+ " source=" + parameters[i].getClass().getSimpleName() + "(" + sourceFlat.length() + "b)"
								+ " target=" + targetField.getClass().getSimpleName() + "(" + targetSize + "b)");
						}
						// Truncate/pad to target size (COBOL semantics)
						final String flat;
						if (sourceFlat.length() <= targetSize) {
							flat = sourceFlat + " ".repeat(targetSize - sourceFlat.length());
						} else {
							flat = sourceFlat.substring(0, targetSize);
						}
						io.proleap.cobol.runtime.CobolMove.moveStringToGroup(flat, targetField);
						if (DEBUG) System.out.println("  ".repeat(callDepth) + "  [LINKAGE] " + fieldName + " = groupToString(" + sourceFlat.length() + "b→" + targetSize + "b)");
					} else {
						if (DEBUG) System.out.println("  ".repeat(callDepth) + "  [LINKAGE] " + fieldName + " = target is NULL, skip");
					}
				}
			} catch (final Exception e) {
				if (DEBUG) System.out.println("  ".repeat(callDepth) + "  [LINKAGE ERROR] " + fieldName + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
				LOG.debug("Failed to set LINKAGE field {} on {}: {}", fieldName, program.getClass().getSimpleName(), e.getMessage());
			}
		}
	}

	/**
	 * Gets LINKAGE field values from the program after execution (for copy-back).
	 */
	private Object[] getLinkageFieldsViaReflection(final CobolProgram program) {
		final String[] linkageNames = discoverLinkageFieldNames(program.getClass());
		if (linkageNames == null) return program.getLinkageParameters();

		final Object[] result = new Object[linkageNames.length];
		for (int i = 0; i < linkageNames.length; i++) {
			try {
				final java.lang.reflect.Field f = findField(program.getClass(), linkageNames[i]);
				if (f != null) {
					f.setAccessible(true);
					result[i] = f.get(program);
				}
			} catch (final Exception e) {
				// ignore
			}
		}
		return result;
	}

	/**
	 * Copies data from callee group to caller group using flat byte copy
	 * with element-level alignment for OCCURS (List) fields.
	 *
	 * In COBOL, CALL USING BY REFERENCE passes flat byte buffers. When structures
	 * have different sizes (e.g., callee has 941b/elem, caller has 929b/elem),
	 * each element is truncated/padded independently, not the total flat string.
	 */
	private void copyGroupBack(final Object source, final Object target) {
		if (source == null || target == null) return;

		// Guard: do not recurse into JDK classes (String, BigDecimal, etc.)
		// Only process COBOL group types (class names ending with "Type").
		// This prevents InaccessibleObjectException when reflection tries to
		// access internal fields like java.lang.String.hashIsZero.
		final boolean sourceIsGroup = io.proleap.cobol.runtime.CobolMove.isCobolGroupType(source.getClass());
		final boolean targetIsGroup = io.proleap.cobol.runtime.CobolMove.isCobolGroupType(target.getClass());

		if (!sourceIsGroup && !targetIsGroup) {
			// Neither is a COBOL group type — skip
			return;
		}
		if (sourceIsGroup && !targetIsGroup) {
			if (target instanceof String) {
				// Can't write back to an immutable String parameter — skip silently
				return;
			}
			// Source is a group, target is something else — skip
			return;
		}
		if (!sourceIsGroup && targetIsGroup) {
			if (source instanceof String) {
				// String source to group target: use moveStringToGroup
				io.proleap.cobol.runtime.CobolMove.moveStringToGroup((String) source, target);
				return;
			}
			// Non-group source to group target — skip
			return;
		}

		// Both are COBOL group types — walk target/source fields IN ORDER (positional)
		final java.lang.reflect.Field[] targetFields = getDataFields(target.getClass());
		final java.lang.reflect.Field[] sourceFields = getDataFields(source.getClass());
		final int count = Math.min(targetFields.length, sourceFields.length);

		for (int i = 0; i < count; i++) {
			try {
				final java.lang.reflect.Field tf = targetFields[i];
				final java.lang.reflect.Field sf = sourceFields[i];
				tf.setAccessible(true);
				sf.setAccessible(true);

				final Object sv = sf.get(source);
				final Object tv = tf.get(target);

				if (sv instanceof String && tv instanceof String) {
					final String ss = (String) sv;
					final int tlen = ((String) tv).length();
					tf.set(target, ss.length() >= tlen ? ss.substring(0, tlen) : ss + " ".repeat(tlen - ss.length()));
				} else if (sv instanceof java.math.BigDecimal && tf.getType() == java.math.BigDecimal.class) {
					tf.set(target, sv);
				} else if (sv instanceof Boolean && (tf.getType() == boolean.class || tf.getType() == Boolean.class)) {
					tf.set(target, sv);
				} else if (sv instanceof java.util.List && tv instanceof java.util.List) {
					// OCCURS: element by element
					final java.util.List<?> sl = (java.util.List<?>) sv;
					final java.util.List<?> tl = (java.util.List<?>) tv;
					for (int j = 0; j < Math.min(sl.size(), tl.size()); j++) {
						if (sl.get(j) != null && tl.get(j) != null) {
							copyGroupBack(sl.get(j), tl.get(j));
						}
					}
				} else if (sv instanceof String && tv != null
						&& io.proleap.cobol.runtime.CobolMove.isCobolGroupType(tv.getClass())) {
					// Source is a flat String (callee PIC X), target is a COBOL group (caller has sub-fields)
					// Use moveStringToGroup to distribute the string across the group's sub-fields
					final String ss = (String) sv;
					final int targetSize = io.proleap.cobol.runtime.CobolMove.getGroupSize(tv);
					final String padded = ss.length() >= targetSize ? ss.substring(0, targetSize)
							: ss + " ".repeat(targetSize - ss.length());
					io.proleap.cobol.runtime.CobolMove.moveStringToGroup(padded, tv);
				} else if (sv != null && tv instanceof String
						&& io.proleap.cobol.runtime.CobolMove.isCobolGroupType(sv.getClass())) {
					// Source is a COBOL group (callee has sub-fields), target is a flat String (caller PIC X)
					final String flat = io.proleap.cobol.runtime.CobolMove.groupToString(sv);
					final int tlen = ((String) tv).length();
					tf.set(target, flat.length() >= tlen ? flat.substring(0, tlen)
							: flat + " ".repeat(tlen - flat.length()));
				} else if (sv != null && tv != null
						&& io.proleap.cobol.runtime.CobolMove.isCobolGroupType(sv.getClass())
						&& io.proleap.cobol.runtime.CobolMove.isCobolGroupType(tv.getClass())) {
					copyGroupBack(sv, tv);
				}
			} catch (final Exception e) {
				// Skip this field pair
			}
		}
	}

	/** Returns non-synthetic, non-static COBOL data fields in declaration order (cached). */
	private static java.lang.reflect.Field[] getDataFields(final Class<?> clazz) {
		return io.proleap.cobol.runtime.CobolMove.getCachedFields(clazz);
	}

	private static java.lang.reflect.Field findField(Class<?> clazz, final String name) {
		while (clazz != null) {
			try {
				return clazz.getDeclaredField(name);
			} catch (final NoSuchFieldException e) {
				clazz = clazz.getSuperclass();
			}
		}
		return null;
	}

	/**
	 * Formats an array of COBOL values for trace output, truncating long
	 * group contents so a single CALL cannot flood the log.
	 */
	private static String formatTraceValues(final Object[] values) {
		if (values == null) {
			return "[]";
		}
		final int maxLen = DebugFlags.TRACE_VALUE_LENGTH;
		final StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < values.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			final Object v = values[i];
			if (v == null) {
				sb.append("null");
			} else if (v instanceof java.math.BigDecimal) {
				sb.append(v);
			} else if (v instanceof String) {
				final String s = (String) v;
				sb.append('\'').append(s, 0, Math.min(maxLen, s.length())).append('\'');
			} else {
				final String flat = io.proleap.cobol.runtime.CobolMove.groupToString(v);
				sb.append(v.getClass().getSimpleName())
						.append("(len=").append(flat.length()).append(':')
						.append(flat, 0, Math.min(maxLen, flat.length())).append(')');
			}
		}
		return sb.append(']').toString();
	}

	/**
	 * Directories scanned for the generated {@code <Program>.java} sources used
	 * to recover LINKAGE field names. Defaults to the working directory and
	 * {@code generated/cobol}; override with
	 * {@code -Dcobol.generated.sources=/path/one:/path/two}.
	 */
	private static String[] generatedSourceSearchPaths() {
		final String configured = System.getProperty("cobol.generated.sources");
		if (configured != null && !configured.isBlank()) {
			return configured.split(java.io.File.pathSeparator);
		}
		return new String[] { ".", "generated/cobol" };
	}

	/**
	 * Discovers LINKAGE field names by reading the .java source file and parsing
	 * PROCEDURE DIVISION USING comments.
	 */
	private static final java.util.concurrent.ConcurrentHashMap<String, String[]> linkageCache = new java.util.concurrent.ConcurrentHashMap<>();

	private String[] discoverLinkageFieldNames(final Class<?> clazz) {
		return linkageCache.computeIfAbsent(clazz.getName(), k -> {
			final String className = clazz.getSimpleName();
			// Try common source locations
			// Source lookup roots. Override with -Dcobol.generated.sources=/path/one:/path/two
			final String[] searchPaths = generatedSourceSearchPaths();
			for (final String dir : searchPaths) {
				final java.io.File f = new java.io.File(dir, className + ".java");
				if (f.exists()) {
					try {
						final String source = new String(java.nio.file.Files.readAllBytes(f.toPath()));
						return parseProcDivUsing(source);
					} catch (final Exception e) {
						// ignore
					}
				}
			}
			// Fallback: try classpath resource (works inside fat JAR where .java sources are bundled)
			try {
				final java.io.InputStream is = clazz.getClassLoader().getResourceAsStream(className + ".java");
				if (is != null) {
					try {
						final String source = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
						return parseProcDivUsing(source);
					} finally {
						is.close();
					}
				}
			} catch (final Exception e) {
				// ignore
			}
			return null;
		});
	}

	private static String[] parseProcDivUsing(final String source) {
		int idx = source.indexOf("PROCEDURE DIVISION USING");
		String startText = "PROCEDURE DIVISION USING";

		if (idx < 0) {
			// Handle multi-line case where PROCEDURE DIVISION is on one line
			// and USING is on the next comment line
			idx = source.indexOf("PROCEDURE DIVISION");
			if (idx < 0) return null;

			// Look ahead from the PROCEDURE DIVISION position to find USING
			// within the next few lines (in comment blocks)
			final String afterProcDiv = source.substring(idx + "PROCEDURE DIVISION".length());
			final int usingIdx = afterProcDiv.indexOf("USING");
			if (usingIdx < 0 || usingIdx > 500) return null; // USING must be nearby

			// Verify there's no other COBOL statement between PROCEDURE DIVISION and USING
			final String between = afterProcDiv.substring(0, usingIdx);
			// Only whitespace, comment markers, and line numbers should appear between them
			final String betweenClean = between.replaceAll("//\\s*\\(\\d+\\)\\s*", "").replaceAll("[\\s*>]", "");
			if (!betweenClean.isEmpty()) return null; // Something unexpected between them

			idx = idx + "PROCEDURE DIVISION".length() + usingIdx;
			startText = "USING";
		}

		final String fromUsing = source.substring(idx + startText.length());
		final java.util.List<String> params = new java.util.ArrayList<>();
		boolean foundPeriod = false;

		for (final String line : fromUsing.split("\n")) {
			String stripped = line;
			final int commentIdx = stripped.indexOf("//");
			if (commentIdx >= 0) {
				stripped = stripped.substring(commentIdx + 2);
				stripped = stripped.replaceFirst("^\\s*\\(\\d+\\)\\s*", "");
			}

			String trimmed = stripped.trim();
			if (trimmed.endsWith(".")) {
				trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
				foundPeriod = true;
			}

			if (!trimmed.isEmpty()) {
				for (final String token : trimmed.split("\\s+")) {
					final String clean = token.trim();
					if (!clean.isEmpty() && !clean.startsWith("*>") && !clean.equals("BY")
							&& !clean.equals("REFERENCE") && !clean.equals("CONTENT")
							&& !clean.equals("VALUE")) {
						params.add(clean.toLowerCase().replace("-", "_"));
					}
				}
			}

			if (foundPeriod) break;
		}

		return params.isEmpty() ? null : params.toArray(new String[0]);
	}

	private String normalizeProgamName(final String name) {
		if (name == null) {
			return "";
		}
		// Strip quotes and normalize to uppercase
		return name.replaceAll("'", "").replaceAll("\"", "").toUpperCase().trim();
	}
}
