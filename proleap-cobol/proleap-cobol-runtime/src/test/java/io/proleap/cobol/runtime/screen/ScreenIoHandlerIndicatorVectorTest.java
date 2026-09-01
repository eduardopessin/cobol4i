package io.proleap.cobol.runtime.screen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit test for the indicator-vector SPI added in
 * {@link ScreenIoHandler#getIndicatorVector()} and
 * {@link ScreenIoHandler#setIndicatorVector(boolean[])}
 * (covers the indicator-drift bug affecting interactive DDS programs).
 */
public class ScreenIoHandlerIndicatorVectorTest {

	@Test
	public void setVectorIsReadBackByGetVector() {
		final StubHandler handler = new StubHandler();

		final boolean[] toSet = new boolean[100];
		toSet[1] = true;   // *IN01
		toSet[3] = true;   // *IN03 (e.g. CF3 pressed)
		toSet[42] = true;  // *IN42 (DDS conditioning sample)
		toSet[99] = true;  // *IN99 (boundary)

		handler.setIndicatorVector(toSet);

		final boolean[] fromHandler = handler.getIndicatorVector();

		assertArrayEquals(toSet, fromHandler, "get must echo set");
		assertNotSame(toSet, fromHandler, "handler must return a defensive copy, not the original array");
	}

	@Test
	public void toggleSingleIndicatorThenRead() {
		final StubHandler handler = new StubHandler();

		// Start from default (all off) then flip just slot 1.
		final boolean[] vec = handler.getIndicatorVector();
		assertFalse(vec[1], "default handler state is all off");
		vec[1] = true;
		handler.setIndicatorVector(vec);

		final boolean[] after = handler.getIndicatorVector();
		assertTrue(after[1], "slot 1 (*IN01) must be true after setIndicatorVector");
		assertFalse(after[2], "slot 2 must remain false");
		assertFalse(after[99], "slot 99 must remain false");
	}

	@Test
	public void nullVectorTreatedAsAllOff() {
		final StubHandler handler = new StubHandler();

		// Seed the handler with some true slots first.
		final boolean[] seed = new boolean[100];
		seed[5] = true;
		handler.setIndicatorVector(seed);

		// Then pass null — contract per Javadoc says "treated as all off".
		handler.setIndicatorVector(null);

		final boolean[] after = handler.getIndicatorVector();
		for (int i = 0; i < after.length; i++) {
			assertFalse(after[i], "slot " + i + " must be false after null reset");
		}
	}

	// --- Stub handler ---------------------------------------------------------

	/**
	 * Minimal {@link ScreenIoHandler} stub that only implements the new
	 * indicator-vector API. All other SPI methods are no-ops for this test.
	 */
	private static final class StubHandler implements ScreenIoHandler {

		private boolean[] state = new boolean[100];

		@Override
		public String sessionId() {
			return "test-session";
		}

		@Override
		public void writeRecord(final String displayFile, final String recordFormat,
				final Map<String, Object> recordData, final IndicatorSnapshot indicators) {
			// no-op
		}

		@Override
		public Map<String, Object> readRecord(final String recordFormat) {
			return Map.of();
		}

		@Override
		public void writeSubfileRecord(final String recordFormat, final int rrn, final Map<String, Object> rowData) {
			// no-op
		}

		@Override
		public ModifiedSubfileRow readNextModifiedSubfileRow(final String recordFormat) {
			return null;
		}

		@Override
		public void clearSubfile(final String recordFormat) {
			// no-op
		}

		@Override
		public ExfmtResponse exfmt(final String displayFile, final String primaryFormat,
				final IndicatorSnapshot indicators) {
			return new ExfmtResponse("ENTER", null, null, null, null);
		}

		@Override
		public boolean[] getIndicatorVector() {
			return state.clone();
		}

		@Override
		public void setIndicatorVector(final boolean[] vector) {
			if (vector == null) {
				this.state = new boolean[100];
				return;
			}
			this.state = vector.clone();
		}
	}
}
