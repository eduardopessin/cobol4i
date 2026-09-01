package io.proleap.cobol.runtime;

public class CobolStopRunException extends RuntimeException {
	public CobolStopRunException() {
		super("STOP RUN");
	}
}
