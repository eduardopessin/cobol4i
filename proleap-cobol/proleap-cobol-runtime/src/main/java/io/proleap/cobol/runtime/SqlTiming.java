package io.proleap.cobol.runtime;

import java.util.concurrent.Callable;

/**
 * Per-thread JDBC time accumulator used by {@code [TIMING]} logging to break
 * the callee's {@code exec} time into "inside JDBC" vs "Java CPU" portions.
 *
 * <p>The value is stored in a {@link ThreadLocal} as an accumulated {@code long}
 * number of nanoseconds. Callers take a {@link #snapshot()} before a region of
 * interest and then call {@link #since(long)} after that region to get the
 * nanoseconds that were spent inside wrapped JDBC calls during the region.
 *
 * <p>This class records timing only. It never changes JDBC behaviour.
 */
public final class SqlTiming {

	private static final ThreadLocal<Long> SQL_NANOS = ThreadLocal.withInitial(() -> 0L);

	private SqlTiming() {
		// utility class
	}

	/**
	 * Returns the current accumulated nanoseconds for the calling thread.
	 * Use with {@link #since(long)} to bracket a region.
	 */
	public static long snapshot() {
		return SQL_NANOS.get();
	}

	/**
	 * Returns the nanoseconds accumulated on the calling thread since the given
	 * snapshot value.
	 */
	public static long since(final long snapshotValue) {
		return SQL_NANOS.get() - snapshotValue;
	}

	/**
	 * Adds the given delta (nanoseconds) to the calling thread's accumulator.
	 */
	public static void addNanos(final long delta) {
		SQL_NANOS.set(SQL_NANOS.get() + delta);
	}

	/**
	 * Runs the given JDBC operation and adds its elapsed time to the thread-local
	 * accumulator. Any exception thrown by the operation is propagated unchanged.
	 */
	public static <T> T timed(final Callable<T> op) throws Exception {
		final long t0 = System.nanoTime();
		try {
			return op.call();
		} finally {
			addNanos(System.nanoTime() - t0);
		}
	}

	/**
	 * Runs the given JDBC-touching Runnable and adds its elapsed time to the
	 * thread-local accumulator. Any RuntimeException is propagated unchanged.
	 */
	public static void timedVoid(final Runnable op) {
		final long t0 = System.nanoTime();
		try {
			op.run();
		} finally {
			addNanos(System.nanoTime() - t0);
		}
	}
}
