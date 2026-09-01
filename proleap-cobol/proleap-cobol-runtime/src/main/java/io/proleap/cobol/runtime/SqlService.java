package io.proleap.cobol.runtime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Wrapper for EXEC SQL operations in converted COBOL programs.
 */
public interface SqlService extends AutoCloseable {

	PreparedStatement prepareStatement(String sql);

	void processResultSet(ResultSet rs);

	int getSqlCode();

	void setSqlCode(int sqlCode);

	String getSqlState();

	void commit();

	void rollback();

	// Dynamic SQL operations
	void prepareNamedStatement(String stmtName, String sql);

	void declareCursorForPrepared(String cursorName, String stmtName);

	// Cursor operations
	void openCursor(String cursorName);

	void openCursor(String cursorName, String sql, Object... params);

	ResultSet fetchCursor(String cursorName);

	void closeCursor(String cursorName);

	/** Returns the row count affected by the last executed statement (for GET DIAGNOSTICS ROW_COUNT). */
	int getUpdateCount();

	/** Returns the SQL text for a named prepared statement (for EXECUTE prepared-name). */
	String getNamedStatement(String stmtName);

	/** Release all JDBC resources (connections, cursors, statements). Default no-op for backward compatibility. */
	@Override
	default void close() {
		// no-op by default
	}
}
