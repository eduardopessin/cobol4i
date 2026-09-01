package io.proleap.cobol.runtime;

/**
 * Abstraction for COBOL file I/O operations.
 * Implementations can use JPA, JDBC, or flat file I/O.
 */
public interface FileControlService {

	void open(String fileName, OpenMode mode);

	void close(String fileName);

	Object read(String fileName);

	void write(String fileName);

	void rewrite(String fileName);

	void delete(String fileName);

	void delete(String fileName, boolean record);

	void start(String fileName, Object key);

	boolean isEndOfFile(String fileName);

	// IBM ILE subfile operations
	void writeSubfile(String fileName, String record, String format, Object indicators);

	Object readSubfile(String fileName, String format, Object indicators);

	void rewriteSubfile(String fileName, String record, String format, Object indicators);

	// IBM ILE format/indicators operations
	void write(String fileName, String format, Object indicators);

	Object read(String fileName, String format, Object indicators);

	void rewrite(String fileName, String format, Object indicators);

	// --- Overloads accepting FileControlEntry ---

	default void openInput(FileControlEntry entry) {
		try {
			open(entry.getExternalName(), OpenMode.INPUT);
			entry.setFileStatus("00");
		} catch (Exception e) {
			entry.setFileStatus("35");
		}
	}

	default void openOutput(FileControlEntry entry) {
		try {
			open(entry.getExternalName(), OpenMode.OUTPUT);
			entry.setFileStatus("00");
		} catch (Exception e) {
			entry.setFileStatus("35");
		}
	}

	default void openInputOutput(FileControlEntry entry) {
		try {
			open(entry.getExternalName(), OpenMode.I_O);
			entry.setFileStatus("00");
		} catch (Exception e) {
			entry.setFileStatus("35");
		}
	}

	default void openExtend(FileControlEntry entry) {
		try {
			open(entry.getExternalName(), OpenMode.EXTEND);
			entry.setFileStatus("00");
		} catch (Exception e) {
			entry.setFileStatus("35");
		}
	}

	default void close(FileControlEntry entry) {
		try {
			close(entry.getExternalName());
			entry.setFileStatus("00");
		} catch (Exception e) {
			entry.setFileStatus("42");
		}
	}

	default Object read(FileControlEntry entry) {
		Object result = read(entry.getExternalName());
		if (isEndOfFile(entry.getExternalName())) {
			entry.setFileStatus("10");
		} else {
			entry.setFileStatus("00");
		}
		return result;
	}

	default void read(FileControlEntry entry, Object into) {
		// Read from the file and populate the target object
		read(entry.getExternalName());
		if (isEndOfFile(entry.getExternalName())) {
			entry.setFileStatus("10");
		} else {
			entry.setFileStatus("00");
		}
	}

	default void read(FileControlEntry entry, Object into, String format, Object indicators) {
		// Read with INTO, FORMAT and INDICATORS (IBM ILE COBOL extension)
		read(entry.getExternalName(), format, indicators);
	}

	default Object read(FileControlEntry entry, String format, Object indicators) {
		return read(entry.getExternalName(), format, indicators);
	}

	default void write(FileControlEntry entry) {
		write(entry.getExternalName());
	}

	default void write(FileControlEntry entry, Object from) {
		write(entry.getExternalName());
	}

	default void write(FileControlEntry entry, String format, Object indicators) {
		write(entry.getExternalName(), format, indicators);
	}

	default void write(FileControlEntry entry, Object from, String format, Object indicators) {
		// WRITE ... FROM data FORMAT format INDICATORS indicators
		write(entry.getExternalName(), format, indicators);
	}

	default void rewrite(FileControlEntry entry) {
		rewrite(entry.getExternalName());
	}

	default void delete(FileControlEntry entry) {
		delete(entry.getExternalName());
	}

	default boolean isEndOfFile(FileControlEntry entry) {
		return isEndOfFile(entry.getExternalName());
	}

	default Object readSubfile(FileControlEntry entry, String format, Object indicators) {
		try {
			Object result = readSubfile(entry.getExternalName(), format, indicators);
			entry.setFileStatus(result != null ? "00" : "23");
			return result;
		} catch (Exception e) {
			entry.setFileStatus("23");
			return null;
		}
	}

	default Object readSubfile(FileControlEntry entry, Object into, String format, Object indicators) {
		try {
			Object result = readSubfile(entry.getExternalName(), format, indicators);
			entry.setFileStatus(result != null ? "00" : "23");
			return result;
		} catch (Exception e) {
			entry.setFileStatus("23");
			return null;
		}
	}

	default void writeSubfile(FileControlEntry entry, Object from, String format, Object indicators) {
		writeSubfile(entry.getExternalName(), from instanceof String ? (String) from : "", format, indicators);
	}

	// --- WRITE ADVANCING support ---

	/**
	 * WRITE ... AFTER/BEFORE ADVANCING PAGE / n LINES.
	 *
	 * @param fileName     the external file name
	 * @param from         the FROM data (may be null)
	 * @param advanceType  one of "AFTER_PAGE", "AFTER_LINES", "BEFORE_PAGE", "BEFORE_LINES"
	 * @param advanceCount number of lines (0 for PAGE)
	 */
	default void writeAdvancing(String fileName, Object from, String advanceType, int advanceCount) {
		write(fileName);
	}

	default void writeAdvancing(FileControlEntry entry, Object from, String advanceType, int advanceCount) {
		writeAdvancing(entry.getExternalName(), from, advanceType, advanceCount);
	}

	default void rewriteSubfile(FileControlEntry entry, String record, String format, Object indicators) {
		rewriteSubfile(entry.getExternalName(), record, format, indicators);
	}

	/**
	 * Checks whether the last I/O operation on the given file resulted in an
	 * INVALID KEY condition (file status class "2x": 21=sequence error,
	 * 22=duplicate key, 23=record not found, 24=boundary violation).
	 */
	default boolean isInvalidKey(FileControlEntry entry) {
		final String status = entry.getFileStatus();
		return status != null && status.length() >= 2 && status.charAt(0) == '2';
	}

	enum OpenMode {
		INPUT, OUTPUT, I_O, EXTEND
	}
}
