package io.proleap.cobol.runtime.screen;

import java.util.List;
import java.util.Map;

/**
 * Service Provider Interface (SPI) for intercepting COBOL screen I/O operations
 * (EXFMT, WRITE/READ record format, WRITE/READ/CLEAR SUBFILE) emitted by generated
 * Java programs.
 * <p>
 * The ProLeap runtime invokes this handler — when one is registered on a
 * {@link io.proleap.cobol.runtime.CobolProgram} or on a
 * {@link io.proleap.cobol.runtime.FileControlService} decorator — instead of
 * sending 5250 data streams. Typical hosts are the Spring Boot REST wrapper or
 * a Vaadin/React render layer.
 * <p>
 * <b>Statelessness contract:</b> the runtime holds no session state. The host
 * passes a {@code sessionId} on handler construction/registration; the handler
 * associates runtime calls with that session. One handler instance per program
 * instance per session.
 * <p>
 * <b>No handler registered:</b> when no implementation is bound, the runtime
 * falls back to the pre-existing {@link io.proleap.cobol.runtime.FileControlService}
 * behaviour (logging + delegating to the underlying file implementation), so
 * batch programs and offline tests behave exactly as before.
 * <p>
 * See {@code docs/ux-java-screen-io-spec.md} §3–§6 for the REST contract this
 * SPI feeds.
 */
public interface ScreenIoHandler {

	/**
	 * Opaque session identifier assigned by the host. The runtime treats it as
	 * a string and never inspects it. Implementations typically use it to
	 * route inbound/outbound queues in the wrapper.
	 */
	String sessionId();

	/**
	 * Called on EXFMT (WRITE record format) for a non-subfile record. The
	 * runtime has already serialized the record group into {@code recordData}
	 * (a {@code Map<fieldName, value>}). After the handler returns, the runtime
	 * reads back input via {@link #readRecord(String)}.
	 * <p>
	 * This is "write the output half of the screen".
	 *
	 * @param displayFile the DDS display-file name (e.g. "DSPFILB")
	 * @param recordFormat the record-format name (e.g. "RB00055CTL")
	 * @param recordData output field values keyed by COBOL field name
	 * @param indicators current DDS indicator state (indices 1–99)
	 */
	void writeRecord(String displayFile, String recordFormat,
			Map<String, Object> recordData, IndicatorSnapshot indicators);

	/**
	 * Called on READ record format to fetch input typed by the user. Returns the
	 * updated record data (field → value). The returned map MUST contain every
	 * input-capable field of the record format; fields not modified should be
	 * echoed back as received.
	 *
	 * @param recordFormat the record-format name whose input is requested
	 * @return the user-filled field map (never {@code null})
	 */
	Map<String, Object> readRecord(String recordFormat);

	/**
	 * Called on WRITE SUBFILE to append a subfile record row. Rows are
	 * accumulated for the format; {@link #exfmt(String, String, IndicatorSnapshot)}
	 * flushes them to the client.
	 *
	 * @param recordFormat subfile record-format name (e.g. "RB00055SFL")
	 * @param rrn subfile relative record number (1-based)
	 * @param rowData field values for this row
	 */
	void writeSubfileRecord(String recordFormat, int rrn, Map<String, Object> rowData);

	/**
	 * Called on READ SUBFILE NEXT MODIFIED (or similar) to retrieve the next
	 * subfile row the user modified, in RRN order. Returns {@code null} when
	 * no more modified rows remain (mirroring status 23 / end-of-subfile).
	 *
	 * @param recordFormat subfile record-format name
	 * @return the next modified row or {@code null} when exhausted
	 */
	ModifiedSubfileRow readNextModifiedSubfileRow(String recordFormat);

	/**
	 * Called on CLEAR SUBFILE (SFLCLR / SFLINZ control) to drop all accumulated
	 * rows for the given format. Subsequent writeSubfileRecord calls restart
	 * at RRN 1.
	 */
	void clearSubfile(String recordFormat);

	/**
	 * Return the handler's current view of the 99 DDS indicators (*IN01..*IN99).
	 * <p>
	 * The returned array is 1-based: index {@code 0} is unused and slot
	 * {@code i} (for 1 &le; i &le; 99) reflects the on/off state of indicator
	 * {@code *INi}. Implementations MUST return a defensive copy — callers may
	 * mutate it without affecting handler state.
	 * <p>
	 * The runtime (typically via {@link ScreenIoFileControlService}) calls this
	 * after {@link #exfmt} / {@link #readRecord} / {@link #readNextModifiedSubfileRow}
	 * to pull back indicators toggled by user input (e.g. CF3 pressed &rArr;
	 * {@code *IN03} on) into the program's {@link io.proleap.cobol.runtime.IndicatorArray}.
	 * <p>
	 * This is the read half of the indicator-vector SPI introduced to fix the
	 * indicator-drift bug that blocks interactive programs (DDS
	 * conditioning on indicators set by the bridge but never seen by the
	 * program's vector). See {@code genericUI/HANDOFF-TO-PROLEAP.md} §1.
	 * <p>
	 * <b>Default:</b> returns an empty vector (all off) so legacy handlers keep
	 * compiling unchanged; callers that rely on indicator feedback should
	 * prefer handlers that override both methods.
	 *
	 * @return a fresh {@code boolean[100]} whose slots 1..99 mirror {@code *IN01..*IN99}
	 */
	default boolean[] getIndicatorVector() {
		return new boolean[100];
	}

	/**
	 * Overwrite the handler's indicator-vector snapshot with the values
	 * currently held by the program's {@link io.proleap.cobol.runtime.IndicatorArray}.
	 * <p>
	 * The input array is 1-based: slot {@code 0} is unused and slot {@code i}
	 * (for 1 &le; i &le; 99) is the on/off state of {@code *INi}. Implementations
	 * MUST take a defensive copy — the caller may reuse the array.
	 * <p>
	 * The runtime calls this before {@link #writeRecord} / {@link #exfmt} /
	 * {@link #writeSubfileRecord} so DDS conditioning (SFLDSP/SFLDSPCTL/
	 * SFLEND/SFLCLR, DSPATR, overlay) and AID-key translation decisions made
	 * by the handler always read from the program's current indicator state,
	 * not a stale local copy.
	 * <p>
	 * <b>Default:</b> no-op. Handlers that need indicator feedback MUST override.
	 *
	 * @param vector 1-based indicator states (slot 0 ignored). A {@code null}
	 *               value is treated as "all indicators off".
	 */
	default void setIndicatorVector(final boolean[] vector) {
		// no-op default keeps legacy handlers binary-compatible
	}

	/**
	 * Flushes pending writes (record + subfile rows + indicators) to the host
	 * and blocks until the user submits input. Returns the AID key pressed and
	 * cursor position; individual field values are fetched afterwards via
	 * {@link #readRecord(String)} / {@link #readNextModifiedSubfileRow(String)}.
	 * <p>
	 * If the host closes the session or the thread is interrupted, the
	 * implementation MUST throw {@link ScreenIoInterruptedException} so the
	 * runtime can unwind cleanly.
	 *
	 * @param displayFile the DDS display-file name
	 * @param primaryFormat the record format that triggered the EXFMT
	 * @param indicators current indicator state to send to the client
	 * @return the user's AID key + cursor context
	 * @throws ScreenIoInterruptedException if the session is closed mid-wait
	 */
	ExfmtResponse exfmt(String displayFile, String primaryFormat, IndicatorSnapshot indicators)
			throws ScreenIoInterruptedException;

	/**
	 * Immutable snapshot of DDS indicators 1..99 passed across the SPI boundary.
	 */
	final class IndicatorSnapshot {

		private final boolean[] values;

		public IndicatorSnapshot(final boolean[] values) {
			// Defensive copy; callers must not observe later mutations.
			this.values = values == null ? new boolean[100] : values.clone();
		}

		public boolean get(final int index) {
			if (index < 1 || index > 99) {
				return false;
			}
			return index < values.length && values[index];
		}

		public boolean[] rawCopy() {
			return values.clone();
		}
	}

	/**
	 * Response returned by {@link #exfmt}: AID key and optional cursor position.
	 * Field values themselves are fetched via {@link #readRecord(String)} and
	 * {@link #readNextModifiedSubfileRow(String)} after EXFMT returns.
	 */
	final class ExfmtResponse {

		private final String aidKey;
		private final String cursorRecord;
		private final String cursorField;
		private final Integer cursorSflRrn;
		private final List<Integer> responseIndicators;

		public ExfmtResponse(final String aidKey, final String cursorRecord, final String cursorField,
				final Integer cursorSflRrn, final List<Integer> responseIndicators) {
			this.aidKey = aidKey == null ? "ENTER" : aidKey;
			this.cursorRecord = cursorRecord;
			this.cursorField = cursorField;
			this.cursorSflRrn = cursorSflRrn;
			this.responseIndicators = responseIndicators == null ? List.of() : List.copyOf(responseIndicators);
		}

		public String aidKey() { return aidKey; }
		public String cursorRecord() { return cursorRecord; }
		public String cursorField() { return cursorField; }
		public Integer cursorSflRrn() { return cursorSflRrn; }
		public List<Integer> responseIndicators() { return responseIndicators; }
	}

	/**
	 * One modified row read back from a subfile, with its RRN and field map.
	 */
	final class ModifiedSubfileRow {

		private final int rrn;
		private final Map<String, Object> fields;

		public ModifiedSubfileRow(final int rrn, final Map<String, Object> fields) {
			this.rrn = rrn;
			this.fields = fields == null ? Map.of() : Map.copyOf(fields);
		}

		public int rrn() { return rrn; }
		public Map<String, Object> fields() { return fields; }
	}
}
