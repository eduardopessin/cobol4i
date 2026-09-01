package io.proleap.cobol.runtime.msg;

/**
 * Service Provider Interface (SPI) for IBM i {@code SNDPGMMSG} (Send Program
 * Message) operations emitted by converted CL programs.
 *
 * <p>On the AS/400 a CL statement such as
 * {@code SNDPGMMSG MSGID(MSG00003) MSGF(APPMSG) TOPGMQ(*PRV (&PGM))} sends an
 * entry from a message file to a program message queue. In the Java port the
 * runtime has no message-queue infrastructure, so converted programs call
 * {@link #sendProgramMessage(String, String)} on this handler instead. A host
 * (Spring Boot wrapper, test harness, or a future AS/400-integration layer)
 * supplies a concrete implementation; batch runs with no host see the
 * {@link #NOOP} default and continue without failing.</p>
 *
 * <p><b>Why an SPI and not a hard-coded logger.info:</b> production runs on
 * AS/400 actually queue these messages — downstream operators depend on them
 * for audit / authorization notification. A silent
 * {@code logger.info} hides real business behaviour; a stubbed implementation
 * lets tests assert that the message was sent with the right id.</p>
 *
 * <p>Mirrors the style of {@link io.proleap.cobol.runtime.screen.ScreenIoHandler}
 * (stateless SPI, default no-op fallback).</p>
 */
public interface MessageQueueHandler {

	/**
	 * Send a message identified by {@code msgId} from message file
	 * {@code msgFile} to the caller's program message queue (i.e. the
	 * equivalent of CL {@code SNDPGMMSG MSGID(msgId) MSGF(msgFile)
	 * TOPGMQ(*PRV)}).
	 *
	 * @param msgId   the message identifier (e.g. {@code "MSG00003"})
	 * @param msgFile the message file (e.g. {@code "APPMSG"}); may be empty
	 *                when the CL program relied on the default message file
	 */
	void sendProgramMessage(String msgId, String msgFile);

	/**
	 * Default no-op handler used when no host has registered one. Swallows
	 * the message so that batch/offline runs of converted CL programs do not
	 * fail.
	 */
	MessageQueueHandler NOOP = (msgId, msgFile) -> { /* no-op */ };
}
