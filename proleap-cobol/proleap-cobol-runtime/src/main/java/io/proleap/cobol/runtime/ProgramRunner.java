package io.proleap.cobol.runtime;

/**
 * Interface for invoking other COBOL programs (CALL statement).
 */
public interface ProgramRunner {

	Object call(String programName, Object... parameters);

	/**
	 * Returns the LINKAGE parameter values from the last CALL.
	 * Used by generated code for BY REFERENCE copy-back semantics.
	 * Returns null if the last call did not produce linkage results.
	 */
	default Object[] getLastCallLinkageResult() {
		return null;
	}

	/**
	 * CANCEL statement: marks a program for re-initialization on next CALL.
	 * Per IBM ILE COBOL: CANCEL releases the program's resources and ensures
	 * that the next CALL will reinitialize working storage to VALUE clauses.
	 */
	default void cancel(String programName) {
		// Default no-op for backward compatibility
	}
}
