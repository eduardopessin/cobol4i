package io.proleap.cobol.runtime.screen;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import io.proleap.cobol.runtime.FileControlEntry;
import io.proleap.cobol.runtime.FileControlService;
import io.proleap.cobol.runtime.IndicatorArray;

/**
 * Decorating {@link FileControlService} that routes display-file operations
 * (record-format WRITE/READ and subfile WRITE/READ) through the
 * {@link ScreenIoHandler} bound on {@link ScreenIoContext}, falling back to
 * the wrapped service for every other operation.
 * <p>
 * When no handler is bound to the current thread, this decorator is a pure
 * pass-through — so batch programs, offline UAT runs and existing tests see
 * <b>zero</b> behaviour change.
 * <p>
 * <b>Usage:</b>
 * <pre>
 *   FileControlService base = new FileControlServiceImpl(dataSource);
 *   FileControlService fcs  = new ScreenIoFileControlService(base);
 *   programRunner.register(program);
 *   program.init(fcs, sqlService, programRunner);
 *
 *   // Host thread, per session:
 *   ScreenIoContext.bind(myHandler);
 *   try { program.procedureDivision(); } finally { ScreenIoContext.clear(); }
 * </pre>
 * <p>
 * This class does NOT own the handler and does NOT inspect
 * {@code CobolProgram} state — session identity lives entirely in the host,
 * per the stateless-runtime rule in docs/ux-java-screen-io-spec.md §6.
 */
public class ScreenIoFileControlService implements FileControlService {

	private final FileControlService delegate;

	/**
	 * Accumulates subfile rows between WRITE SUBFILE and the subsequent EXFMT
	 * (WRITE of a CTL format) on a per-thread basis. Keyed by display-file
	 * name, then by subfile record-format name.
	 */
	private static final ThreadLocal<Map<String, Map<String, Integer>>> SUBFILE_RRN =
			ThreadLocal.withInitial(HashMap::new);

	public ScreenIoFileControlService(final FileControlService delegate) {
		if (delegate == null) {
			throw new IllegalArgumentException("delegate FileControlService must not be null");
		}
		this.delegate = delegate;
	}

	// --- Screen-aware overrides --------------------------------------------

	@Override
	public void write(final String fileName, final String format, final Object indicators) {
		final ScreenIoHandler handler = ScreenIoContext.current();
		if (handler == null) {
			delegate.write(fileName, format, indicators);
			return;
		}
		// EXFMT: write the record half then flush + block for input.
		pushIndicators(handler, indicators);
		handler.writeRecord(fileName, format, Map.of(), snapshot(indicators));
		handler.exfmt(fileName, format, snapshot(indicators));
		pullIndicators(handler, indicators);
	}

	@Override
	public Object read(final String fileName, final String format, final Object indicators) {
		final ScreenIoHandler handler = ScreenIoContext.current();
		if (handler == null) {
			return delegate.read(fileName, format, indicators);
		}
		pushIndicators(handler, indicators);
		final Object result = handler.readRecord(format);
		pullIndicators(handler, indicators);
		return result;
	}

	@Override
	public void writeSubfile(final String fileName, final String record, final String format,
			final Object indicators) {
		final ScreenIoHandler handler = ScreenIoContext.current();
		if (handler == null) {
			delegate.writeSubfile(fileName, record, format, indicators);
			return;
		}
		pushIndicators(handler, indicators);
		final int rrn = nextRrn(fileName, format);
		final Map<String, Object> row = recordToMap(record);
		handler.writeSubfileRecord(format, rrn, row);
	}

	@Override
	public Object readSubfile(final String fileName, final String format, final Object indicators) {
		final ScreenIoHandler handler = ScreenIoContext.current();
		if (handler == null) {
			return delegate.readSubfile(fileName, format, indicators);
		}
		pushIndicators(handler, indicators);
		final Object result = handler.readNextModifiedSubfileRow(format);
		pullIndicators(handler, indicators);
		return result;
	}

	@Override
	public void rewriteSubfile(final String fileName, final String record, final String format,
			final Object indicators) {
		final ScreenIoHandler handler = ScreenIoContext.current();
		if (handler == null) {
			delegate.rewriteSubfile(fileName, record, format, indicators);
			return;
		}
		pushIndicators(handler, indicators);
		// REWRITE SUBFILE is an in-place update; surface it as a write with the
		// row's current rrn tracked by the handler. Wrapper decides merge
		// semantics.
		handler.writeSubfileRecord(format, -1, recordToMap(record));
	}

	@Override
	public void rewrite(final String fileName, final String format, final Object indicators) {
		final ScreenIoHandler handler = ScreenIoContext.current();
		if (handler == null) {
			delegate.rewrite(fileName, format, indicators);
			return;
		}
		pushIndicators(handler, indicators);
		// REWRITE of a record format = UPDATE visual record; treat as writeRecord
		// without a blocking EXFMT (no user interaction required).
		handler.writeRecord(fileName, format, Map.of(), snapshot(indicators));
	}

	// --- Pass-through for non-screen operations ----------------------------

	@Override public void open(final String fileName, final OpenMode mode) { delegate.open(fileName, mode); }
	@Override public void close(final String fileName) { delegate.close(fileName); }
	@Override public Object read(final String fileName) { return delegate.read(fileName); }
	@Override public void write(final String fileName) { delegate.write(fileName); }
	@Override public void rewrite(final String fileName) { delegate.rewrite(fileName); }
	@Override public void delete(final String fileName) { delegate.delete(fileName); }
	@Override public void delete(final String fileName, final boolean record) { delegate.delete(fileName, record); }
	@Override public void start(final String fileName, final Object key) { delegate.start(fileName, key); }
	@Override public boolean isEndOfFile(final String fileName) { return delegate.isEndOfFile(fileName); }

	// --- Helpers -----------------------------------------------------------

	private int nextRrn(final String fileName, final String format) {
		final Map<String, Integer> perFile = SUBFILE_RRN.get()
				.computeIfAbsent(fileName, k -> new LinkedHashMap<>());
		final int current = perFile.getOrDefault(format, 0) + 1;
		perFile.put(format, current);
		return current;
	}

	/**
	 * Called by the host when it receives a CLEAR SUBFILE signal or when a new
	 * session begins on this thread — drops the per-thread RRN counters.
	 */
	public static void resetSubfileCounters() {
		SUBFILE_RRN.remove();
	}

	private static ScreenIoHandler.IndicatorSnapshot snapshot(final Object indicators) {
		if (indicators instanceof IndicatorArray) {
			final IndicatorArray arr = (IndicatorArray) indicators;
			final boolean[] copy = new boolean[100];
			for (int i = 1; i <= 99; i++) {
				copy[i] = arr.get(i);
			}
			return new ScreenIoHandler.IndicatorSnapshot(copy);
		}
		return new ScreenIoHandler.IndicatorSnapshot(new boolean[100]);
	}

	/**
	 * Push the program's current {@link IndicatorArray} values into the
	 * handler's indicator-vector view before any {@code writeRecord}/
	 * {@code writeSubfileRecord}/{@code exfmt}/{@code readRecord} call, so the
	 * handler evaluates DDS conditioning (SFLDSP/SFLDSPCTL/SFLEND/SFLCLR,
	 * DSPATR, overlay, AID translation) against the same state the generated
	 * program holds. Silent no-op when indicators is not an IndicatorArray
	 * (batch paths, mocks) — preserves legacy behaviour.
	 */
	private static void pushIndicators(final ScreenIoHandler handler, final Object indicators) {
		if (handler == null || !(indicators instanceof IndicatorArray)) {
			return;
		}
		final IndicatorArray arr = (IndicatorArray) indicators;
		final boolean[] copy = new boolean[100];
		for (int i = 1; i <= 99; i++) {
			copy[i] = arr.get(i);
		}
		handler.setIndicatorVector(copy);
	}

	/**
	 * Pull the handler's indicator-vector view back into the program's
	 * {@link IndicatorArray} after any read/exfmt call, so indicators toggled
	 * by user input (e.g. CF3 &rArr; {@code *IN03} on) or recomputed by the
	 * handler (e.g. SFLEND reached) reach the program before the next
	 * statement executes. Silent no-op when indicators is not an
	 * IndicatorArray.
	 */
	private static void pullIndicators(final ScreenIoHandler handler, final Object indicators) {
		if (handler == null || !(indicators instanceof IndicatorArray)) {
			return;
		}
		final boolean[] fromHandler = handler.getIndicatorVector();
		if (fromHandler == null) {
			return;
		}
		final IndicatorArray arr = (IndicatorArray) indicators;
		final int limit = Math.min(fromHandler.length - 1, 99);
		for (int i = 1; i <= limit; i++) {
			arr.set(i, fromHandler[i]);
		}
	}

	/**
	 * Best-effort conversion of the {@code record} argument (already serialised
	 * by the generated code as a String via CobolMove.groupToString) into a
	 * field map. Real structured serialisation is handled by the wrapper using
	 * DDS schemas; this method only preserves the raw string so nothing is lost
	 * on the way out. Downstream consumers should use the schema to split it.
	 */
	private static Map<String, Object> recordToMap(final String record) {
		final Map<String, Object> m = new LinkedHashMap<>();
		m.put("__raw", record == null ? "" : record);
		return m;
	}

	// --- FileControlEntry overloads needed because default methods may be
	// overridden in other callers. Delegate all of them verbatim so session
	// state stays out of this class.

	@Override public void openInput(final FileControlEntry entry) { delegate.openInput(entry); }
	@Override public void openOutput(final FileControlEntry entry) { delegate.openOutput(entry); }
	@Override public void openInputOutput(final FileControlEntry entry) { delegate.openInputOutput(entry); }
	@Override public void openExtend(final FileControlEntry entry) { delegate.openExtend(entry); }
	@Override public void close(final FileControlEntry entry) { delegate.close(entry); }
	@Override public Object read(final FileControlEntry entry) { return delegate.read(entry); }
	@Override public void read(final FileControlEntry entry, final Object into) { delegate.read(entry, into); }

	@Override
	public void read(final FileControlEntry entry, final Object into, final String format, final Object indicators) {
		final ScreenIoHandler handler = ScreenIoContext.current();
		if (handler == null) {
			delegate.read(entry, into, format, indicators);
			return;
		}
		pushIndicators(handler, indicators);
		handler.readRecord(format);
		pullIndicators(handler, indicators);
	}

	@Override
	public Object read(final FileControlEntry entry, final String format, final Object indicators) {
		final ScreenIoHandler handler = ScreenIoContext.current();
		if (handler == null) {
			return delegate.read(entry, format, indicators);
		}
		pushIndicators(handler, indicators);
		final Object result = handler.readRecord(format);
		pullIndicators(handler, indicators);
		return result;
	}

	@Override public void write(final FileControlEntry entry) { delegate.write(entry); }
	@Override public void write(final FileControlEntry entry, final Object from) { delegate.write(entry, from); }

	@Override
	public void write(final FileControlEntry entry, final String format, final Object indicators) {
		write(entry.getExternalName(), format, indicators);
	}

	@Override
	public void write(final FileControlEntry entry, final Object from, final String format, final Object indicators) {
		final ScreenIoHandler handler = ScreenIoContext.current();
		if (handler == null) {
			delegate.write(entry, from, format, indicators);
			return;
		}
		pushIndicators(handler, indicators);
		final ScreenIoHandler.IndicatorSnapshot snap = snapshot(indicators);
		handler.writeRecord(entry.getExternalName(), format, recordToMap(from == null ? "" : from.toString()), snap);
		handler.exfmt(entry.getExternalName(), format, snap);
		pullIndicators(handler, indicators);
	}

	@Override public void rewrite(final FileControlEntry entry) { delegate.rewrite(entry); }
	@Override public void delete(final FileControlEntry entry) { delegate.delete(entry); }
	@Override public boolean isEndOfFile(final FileControlEntry entry) { return delegate.isEndOfFile(entry); }

	@Override
	public Object readSubfile(final FileControlEntry entry, final String format, final Object indicators) {
		return readSubfile(entry.getExternalName(), format, indicators);
	}

	@Override
	public Object readSubfile(final FileControlEntry entry, final Object into, final String format,
			final Object indicators) {
		return readSubfile(entry.getExternalName(), format, indicators);
	}

	@Override
	public void writeSubfile(final FileControlEntry entry, final Object from, final String format,
			final Object indicators) {
		writeSubfile(entry.getExternalName(), from == null ? "" : from.toString(), format, indicators);
	}

	@Override
	public void writeAdvancing(final String fileName, final Object from, final String advanceType,
			final int advanceCount) {
		delegate.writeAdvancing(fileName, from, advanceType, advanceCount);
	}

	@Override
	public void writeAdvancing(final FileControlEntry entry, final Object from, final String advanceType,
			final int advanceCount) {
		delegate.writeAdvancing(entry, from, advanceType, advanceCount);
	}

	@Override
	public void rewriteSubfile(final FileControlEntry entry, final String record, final String format,
			final Object indicators) {
		rewriteSubfile(entry.getExternalName(), record, format, indicators);
	}

	@Override
	public boolean isInvalidKey(final FileControlEntry entry) {
		return delegate.isInvalidKey(entry);
	}
}
