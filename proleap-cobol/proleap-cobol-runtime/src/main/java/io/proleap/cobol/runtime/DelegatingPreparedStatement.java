package io.proleap.cobol.runtime;

import java.sql.*;
import java.io.*;
import java.math.BigDecimal;
import java.util.Calendar;
import java.net.URL;

/**
 * Base class that delegates all PreparedStatement methods to a wrapped instance.
 * Subclasses can override specific methods to add behavior.
 */
public class DelegatingPreparedStatement implements PreparedStatement {
    private final PreparedStatement delegate;

    public DelegatingPreparedStatement(PreparedStatement delegate) {
        this.delegate = delegate;
    }

    protected PreparedStatement getDelegate() { return delegate; }

    // PreparedStatement methods
    public ResultSet executeQuery() throws SQLException { return delegate.executeQuery(); }
    public int executeUpdate() throws SQLException { return delegate.executeUpdate(); }
    public void setNull(int i, int t) throws SQLException { delegate.setNull(i, t); }
    public void setBoolean(int i, boolean x) throws SQLException { delegate.setBoolean(i, x); }
    public void setByte(int i, byte x) throws SQLException { delegate.setByte(i, x); }
    public void setShort(int i, short x) throws SQLException { delegate.setShort(i, x); }
    public void setInt(int i, int x) throws SQLException { delegate.setInt(i, x); }
    public void setLong(int i, long x) throws SQLException { delegate.setLong(i, x); }
    public void setFloat(int i, float x) throws SQLException { delegate.setFloat(i, x); }
    public void setDouble(int i, double x) throws SQLException { delegate.setDouble(i, x); }
    public void setBigDecimal(int i, BigDecimal x) throws SQLException { delegate.setBigDecimal(i, x); }
    public void setString(int i, String x) throws SQLException { delegate.setString(i, x); }
    public void setBytes(int i, byte[] x) throws SQLException { delegate.setBytes(i, x); }
    public void setDate(int i, Date x) throws SQLException { delegate.setDate(i, x); }
    public void setTime(int i, Time x) throws SQLException { delegate.setTime(i, x); }
    public void setTimestamp(int i, Timestamp x) throws SQLException { delegate.setTimestamp(i, x); }
    public void setAsciiStream(int i, InputStream x, int l) throws SQLException { delegate.setAsciiStream(i, x, l); }
    @SuppressWarnings("deprecation")
    public void setUnicodeStream(int i, InputStream x, int l) throws SQLException { delegate.setUnicodeStream(i, x, l); }
    public void setBinaryStream(int i, InputStream x, int l) throws SQLException { delegate.setBinaryStream(i, x, l); }
    public void clearParameters() throws SQLException { delegate.clearParameters(); }
    public void setObject(int i, Object x, int t) throws SQLException { delegate.setObject(i, x, t); }
    public void setObject(int i, Object x) throws SQLException { delegate.setObject(i, x); }
    public boolean execute() throws SQLException { return delegate.execute(); }
    public void addBatch() throws SQLException { delegate.addBatch(); }
    public void setCharacterStream(int i, Reader r, int l) throws SQLException { delegate.setCharacterStream(i, r, l); }
    public void setRef(int i, Ref x) throws SQLException { delegate.setRef(i, x); }
    public void setBlob(int i, Blob x) throws SQLException { delegate.setBlob(i, x); }
    public void setClob(int i, Clob x) throws SQLException { delegate.setClob(i, x); }
    public void setArray(int i, Array x) throws SQLException { delegate.setArray(i, x); }
    public ResultSetMetaData getMetaData() throws SQLException { return delegate.getMetaData(); }
    public void setDate(int i, Date x, Calendar c) throws SQLException { delegate.setDate(i, x, c); }
    public void setTime(int i, Time x, Calendar c) throws SQLException { delegate.setTime(i, x, c); }
    public void setTimestamp(int i, Timestamp x, Calendar c) throws SQLException { delegate.setTimestamp(i, x, c); }
    public void setNull(int i, int t, String n) throws SQLException { delegate.setNull(i, t, n); }
    public void setURL(int i, URL x) throws SQLException { delegate.setURL(i, x); }
    public ParameterMetaData getParameterMetaData() throws SQLException { return delegate.getParameterMetaData(); }
    public void setRowId(int i, RowId x) throws SQLException { delegate.setRowId(i, x); }
    public void setNString(int i, String x) throws SQLException { delegate.setNString(i, x); }
    public void setNCharacterStream(int i, Reader x, long l) throws SQLException { delegate.setNCharacterStream(i, x, l); }
    public void setNClob(int i, NClob x) throws SQLException { delegate.setNClob(i, x); }
    public void setClob(int i, Reader r, long l) throws SQLException { delegate.setClob(i, r, l); }
    public void setBlob(int i, InputStream s, long l) throws SQLException { delegate.setBlob(i, s, l); }
    public void setNClob(int i, Reader r, long l) throws SQLException { delegate.setNClob(i, r, l); }
    public void setSQLXML(int i, SQLXML x) throws SQLException { delegate.setSQLXML(i, x); }
    public void setObject(int i, Object x, int t, int s) throws SQLException { delegate.setObject(i, x, t, s); }
    public void setAsciiStream(int i, InputStream x, long l) throws SQLException { delegate.setAsciiStream(i, x, l); }
    public void setBinaryStream(int i, InputStream x, long l) throws SQLException { delegate.setBinaryStream(i, x, l); }
    public void setCharacterStream(int i, Reader r, long l) throws SQLException { delegate.setCharacterStream(i, r, l); }
    public void setAsciiStream(int i, InputStream x) throws SQLException { delegate.setAsciiStream(i, x); }
    public void setBinaryStream(int i, InputStream x) throws SQLException { delegate.setBinaryStream(i, x); }
    public void setCharacterStream(int i, Reader r) throws SQLException { delegate.setCharacterStream(i, r); }
    public void setNCharacterStream(int i, Reader x) throws SQLException { delegate.setNCharacterStream(i, x); }
    public void setClob(int i, Reader r) throws SQLException { delegate.setClob(i, r); }
    public void setBlob(int i, InputStream s) throws SQLException { delegate.setBlob(i, s); }
    public void setNClob(int i, Reader r) throws SQLException { delegate.setNClob(i, r); }

    // Statement methods
    public ResultSet executeQuery(String sql) throws SQLException { return delegate.executeQuery(sql); }
    public int executeUpdate(String sql) throws SQLException { return delegate.executeUpdate(sql); }
    public void close() throws SQLException { delegate.close(); }
    public int getMaxFieldSize() throws SQLException { return delegate.getMaxFieldSize(); }
    public void setMaxFieldSize(int max) throws SQLException { delegate.setMaxFieldSize(max); }
    public int getMaxRows() throws SQLException { return delegate.getMaxRows(); }
    public void setMaxRows(int max) throws SQLException { delegate.setMaxRows(max); }
    public void setEscapeProcessing(boolean e) throws SQLException { delegate.setEscapeProcessing(e); }
    public int getQueryTimeout() throws SQLException { return delegate.getQueryTimeout(); }
    public void setQueryTimeout(int s) throws SQLException { delegate.setQueryTimeout(s); }
    public void cancel() throws SQLException { delegate.cancel(); }
    public SQLWarning getWarnings() throws SQLException { return delegate.getWarnings(); }
    public void clearWarnings() throws SQLException { delegate.clearWarnings(); }
    public void setCursorName(String name) throws SQLException { delegate.setCursorName(name); }
    public boolean execute(String sql) throws SQLException { return delegate.execute(sql); }
    public ResultSet getResultSet() throws SQLException { return delegate.getResultSet(); }
    public int getUpdateCount() throws SQLException { return delegate.getUpdateCount(); }
    public boolean getMoreResults() throws SQLException { return delegate.getMoreResults(); }
    public void setFetchDirection(int d) throws SQLException { delegate.setFetchDirection(d); }
    public int getFetchDirection() throws SQLException { return delegate.getFetchDirection(); }
    public void setFetchSize(int r) throws SQLException { delegate.setFetchSize(r); }
    public int getFetchSize() throws SQLException { return delegate.getFetchSize(); }
    public int getResultSetConcurrency() throws SQLException { return delegate.getResultSetConcurrency(); }
    public int getResultSetType() throws SQLException { return delegate.getResultSetType(); }
    public void addBatch(String sql) throws SQLException { delegate.addBatch(sql); }
    public void clearBatch() throws SQLException { delegate.clearBatch(); }
    public int[] executeBatch() throws SQLException { return delegate.executeBatch(); }
    public Connection getConnection() throws SQLException { return delegate.getConnection(); }
    public boolean getMoreResults(int c) throws SQLException { return delegate.getMoreResults(c); }
    public ResultSet getGeneratedKeys() throws SQLException { return delegate.getGeneratedKeys(); }
    public int executeUpdate(String sql, int a) throws SQLException { return delegate.executeUpdate(sql, a); }
    public int executeUpdate(String sql, int[] ci) throws SQLException { return delegate.executeUpdate(sql, ci); }
    public int executeUpdate(String sql, String[] cn) throws SQLException { return delegate.executeUpdate(sql, cn); }
    public boolean execute(String sql, int a) throws SQLException { return delegate.execute(sql, a); }
    public boolean execute(String sql, int[] ci) throws SQLException { return delegate.execute(sql, ci); }
    public boolean execute(String sql, String[] cn) throws SQLException { return delegate.execute(sql, cn); }
    public int getResultSetHoldability() throws SQLException { return delegate.getResultSetHoldability(); }
    public boolean isClosed() throws SQLException { return delegate.isClosed(); }
    public void setPoolable(boolean p) throws SQLException { delegate.setPoolable(p); }
    public boolean isPoolable() throws SQLException { return delegate.isPoolable(); }
    public void closeOnCompletion() throws SQLException { delegate.closeOnCompletion(); }
    public boolean isCloseOnCompletion() throws SQLException { return delegate.isCloseOnCompletion(); }
    public <T> T unwrap(Class<T> i) throws SQLException { return delegate.unwrap(i); }
    public boolean isWrapperFor(Class<?> i) throws SQLException { return delegate.isWrapperFor(i); }
}
