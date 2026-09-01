package io.proleap.cobol.runtime.msg;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime entry point for converted CL programs that execute
 * {@code SNDPGMMSG}. Holds the active {@link MessageQueueHandler} and routes
 * calls to it.
 *
 * <p>Hosts (Spring Boot wrapper, genericUI bridge, tests) register their
 * implementation via {@link #setHandler(MessageQueueHandler)}; converted
 * programs call {@link #sendProgramMessage(String, String)}. When no handler
 * is registered the {@link MessageQueueHandler#NOOP} default is used so that
 * batch runs don't fail.</p>
 *
 * <p>Intentionally JVM-wide (no per-session state) because {@code SNDPGMMSG}
 * itself carries no session context: it sends to the current job's caller
 * queue. If a future use-case needs per-session routing, the handler
 * implementation can consult a {@link ThreadLocal} rather than this class
 * growing state.</p>
 */
public final class MessageQueueService {

	private static final AtomicReference<MessageQueueHandler> HANDLER =
			new AtomicReference<>(MessageQueueHandler.NOOP);

	private MessageQueueService() {
		// utility class
	}

	/**
	 * Register the handler used by all {@code SNDPGMMSG}-emitted Java calls.
	 * Pass {@code null} to restore the no-op default.
	 */
	public static void setHandler(final MessageQueueHandler handler) {
		HANDLER.set(handler != null ? handler : MessageQueueHandler.NOOP);
	}

	/** Returns the currently-registered handler (never {@code null}). */
	public static MessageQueueHandler getHandler() {
		return HANDLER.get();
	}

	/**
	 * Convenience method converted CL programs call directly. Delegates to
	 * the registered handler.
	 */
	public static void sendProgramMessage(final String msgId, final String msgFile) {
		HANDLER.get().sendProgramMessage(msgId, msgFile);
	}
}
