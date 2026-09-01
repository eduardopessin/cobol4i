package io.proleap.cobol.runtime.msg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the SNDPGMMSG SPI added so converted CL programs (e.g.
 * SNDPGMMSG) can notify a host instead of silently logging to stdout.
 */
public class MessageQueueServiceTest {

	@AfterEach
	public void restoreDefault() {
		MessageQueueService.setHandler(null); // restores NOOP
	}

	@Test
	public void defaultHandlerIsNoopAndNeverNull() {
		final MessageQueueHandler h = MessageQueueService.getHandler();
		assertNotNull(h, "default handler must never be null — batch runs rely on safe fallback");
		assertSame(MessageQueueHandler.NOOP, h,
				"starting state must be NOOP so CL programs don't fail when no host is registered");

		// Exercise — must not throw.
		MessageQueueService.sendProgramMessage("MSG00003", "APPMSG");
	}

	@Test
	public void setHandlerIsObservedBySend() {
		final List<String[]> captured = new ArrayList<>();
		MessageQueueService.setHandler((id, file) -> captured.add(new String[] { id, file }));

		MessageQueueService.sendProgramMessage("MSG00003", "APPMSG");
		MessageQueueService.sendProgramMessage("MSG00004", "APPMSG");

		assertEquals(2, captured.size());
		assertEquals("MSG00003", captured.get(0)[0]);
		assertEquals("APPMSG", captured.get(0)[1]);
		assertEquals("MSG00004", captured.get(1)[0]);
	}

	@Test
	public void setHandlerNullRestoresNoop() {
		MessageQueueService.setHandler((id, file) -> { throw new AssertionError("should not be called"); });
		MessageQueueService.setHandler(null);
		assertSame(MessageQueueHandler.NOOP, MessageQueueService.getHandler());
		// Must not invoke the previous (throwing) handler.
		MessageQueueService.sendProgramMessage("IGNORED", "IGNORED");
	}
}
