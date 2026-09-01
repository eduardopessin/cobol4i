package io.proleap.cobol.runtime.screen;

/**
 * Thread-local holder for the active {@link ScreenIoHandler}.
 * <p>
 * The host (the Spring Boot wrapper) registers a handler <b>before</b>
 * invoking {@code CobolProgram.procedureDivision()} on the session's virtual
 * thread, and clears it when the program returns. Any ProLeap runtime
 * component (e.g. the {@link io.proleap.cobol.runtime.screen.ScreenIoFileControlService}
 * decorator) can then consult {@link #current()} to decide whether to route a
 * screen I/O call to the handler or fall back to legacy behaviour.
 * <p>
 * <b>Zero regression:</b> when nothing is bound for the current thread,
 * {@link #current()} returns {@code null} and every runtime hook defaults to
 * pre-existing behaviour. Batch programs and offline tests never touch this
 * class.
 */
public final class ScreenIoContext {

	private static final ThreadLocal<ScreenIoHandler> ACTIVE = new ThreadLocal<>();

	private ScreenIoContext() {
	}

	/** Returns the handler bound to the current thread, or {@code null}. */
	public static ScreenIoHandler current() {
		return ACTIVE.get();
	}

	/**
	 * Binds a handler to the current thread. Intended for the host wrapper.
	 * Pass {@code null} to clear (or prefer {@link #clear()}).
	 */
	public static void bind(final ScreenIoHandler handler) {
		if (handler == null) {
			ACTIVE.remove();
		} else {
			ACTIVE.set(handler);
		}
	}

	/** Clears the handler binding for the current thread. */
	public static void clear() {
		ACTIVE.remove();
	}
}
