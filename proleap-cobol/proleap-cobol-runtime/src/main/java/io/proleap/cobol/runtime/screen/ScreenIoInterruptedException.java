package io.proleap.cobol.runtime.screen;

/**
 * Thrown by {@link ScreenIoHandler#exfmt} when the host cancels the session or
 * the program thread is interrupted mid-wait. Subclasses
 * {@link RuntimeException} so existing {@code CobolProgram.procedureDivision()}
 * signatures ({@code throws Exception}) don't need to change and batch programs
 * (which never see it) don't have to declare it.
 */
public class ScreenIoInterruptedException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String sessionId;

	public ScreenIoInterruptedException(final String sessionId, final String message) {
		super(message);
		this.sessionId = sessionId;
	}

	public ScreenIoInterruptedException(final String sessionId, final String message, final Throwable cause) {
		super(message, cause);
		this.sessionId = sessionId;
	}

	public String sessionId() {
		return sessionId;
	}
}
