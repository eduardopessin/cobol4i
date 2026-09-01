package io.proleap.cobol.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

/**
 * Tests for the {@code -Dcobol.trace.programs} selector used by
 * {@link DebugFlags#isTraced(String)}.
 *
 * <p>{@code DebugFlags} reads its system properties once at class load, so
 * these tests exercise the parsing/matching logic through a fresh class loader
 * rather than mutating the already-initialised constants.
 */
public class DebugFlagsTracePropertyTest {

	/**
	 * Loads a private copy of {@code DebugFlags} so the static initialiser runs
	 * against the system properties currently in effect.
	 */
	private static boolean isTracedWith(final String traceProperty, final String programName) throws Exception {
		final String previous = System.getProperty("cobol.trace.programs");

		if (traceProperty == null) {
			System.clearProperty("cobol.trace.programs");
		} else {
			System.setProperty("cobol.trace.programs", traceProperty);
		}

		// Child-first loader: reloads DebugFlags instead of delegating to the
		// already-initialised copy on the app class loader.
		try (IsolatedLoader loader = new IsolatedLoader()) {
			final Class<?> flags = loader.loadClass(DebugFlags.class.getName());
			final Method isTraced = flags.getMethod("isTraced", String.class);

			return (Boolean) isTraced.invoke(null, programName);
		} finally {
			if (previous == null) {
				System.clearProperty("cobol.trace.programs");
			} else {
				System.setProperty("cobol.trace.programs", previous);
			}
		}
	}

	private static final class IsolatedLoader extends ClassLoader implements AutoCloseable {

		private IsolatedLoader() {
			super(DebugFlagsTracePropertyTest.class.getClassLoader());
		}

		@Override
		protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
			if (!name.equals(DebugFlags.class.getName())) {
				return super.loadClass(name, resolve);
			}

			Class<?> loaded = findLoadedClass(name);

			if (loaded == null) {
				final String resource = name.replace('.', '/') + ".class";

				try (java.io.InputStream is = getParent().getResourceAsStream(resource)) {
					if (is == null) {
						throw new ClassNotFoundException(name);
					}

					final byte[] bytes = is.readAllBytes();
					loaded = defineClass(name, bytes, 0, bytes.length);
				} catch (final java.io.IOException e) {
					throw new ClassNotFoundException(name, e);
				}
			}

			if (resolve) {
				resolveClass(loaded);
			}

			return loaded;
		}

		@Override
		public void close() {
			// nothing to release; present so callers can use try-with-resources
		}
	}

	@Test
	public void noPropertyMeansNoTracing() throws Exception {
		assertFalse(isTracedWith(null, "PAYROLL"),
				"tracing must be off by default so normal runs stay silent");
	}

	@Test
	public void listedProgramIsTraced() throws Exception {
		assertTrue(isTracedWith("PAYROLL,BILLING", "PAYROLL"),
				"a program named in cobol.trace.programs must be traced");
	}

	@Test
	public void unlistedProgramIsNotTraced() throws Exception {
		assertFalse(isTracedWith("PAYROLL,BILLING", "INVOICE"),
				"a program absent from cobol.trace.programs must not be traced");
	}

	@Test
	public void matchingIsCaseInsensitiveAndTrimsWhitespace() throws Exception {
		assertTrue(isTracedWith(" payroll , billing ", "PAYROLL"),
				"names must match case-insensitively and tolerate spaces around commas");
	}

	@Test
	public void nullProgramNameIsSafe() throws Exception {
		assertFalse(isTracedWith("PAYROLL", null),
				"a null program name must not throw");
	}

	@Test
	public void blankPropertyMeansNoTracing() throws Exception {
		assertFalse(isTracedWith("   ", "PAYROLL"),
				"a blank property must behave like an unset one");
	}
}
