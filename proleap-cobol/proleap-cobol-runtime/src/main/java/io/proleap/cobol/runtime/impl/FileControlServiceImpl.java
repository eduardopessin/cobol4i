package io.proleap.cobol.runtime.impl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.proleap.cobol.runtime.FileControlService;

/**
 * JDBC-based implementation of FileControlService.
 * Maps COBOL file I/O to SQL operations against DB2 tables.
 */
public class FileControlServiceImpl implements FileControlService {

	private static final Logger LOG = LoggerFactory.getLogger(FileControlServiceImpl.class);

	private final DataSource dataSource;

	private final Map<String, OpenMode> openFiles = new HashMap<>();

	private final Map<String, ResultSet> activeReads = new HashMap<>();

	private final Map<String, Map<String, Object>> currentRecords = new HashMap<>();

	public FileControlServiceImpl() {
		this.dataSource = null;
	}

	public FileControlServiceImpl(final DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public void open(final String fileName, final OpenMode mode) {
		LOG.debug("OPEN {} mode={}", fileName, mode);
		openFiles.put(fileName, mode);
	}

	@Override
	public void close(final String fileName) {
		LOG.debug("CLOSE {}", fileName);
		openFiles.remove(fileName);

		final ResultSet rs = activeReads.remove(fileName);
		if (rs != null) {
			try {
				rs.getStatement().close();
				rs.close();
			} catch (final SQLException e) {
				LOG.warn("Error closing result set for {}", fileName, e);
			}
		}
	}

	@Override
	public Object read(final String fileName) {
		LOG.debug("READ {}", fileName);
		try {
			ResultSet rs = activeReads.get(fileName);
			if (rs == null) {
				final Connection conn = dataSource.getConnection();
				final PreparedStatement ps = conn.prepareStatement("SELECT * FROM " + sanitize(fileName));
				rs = ps.executeQuery();
				activeReads.put(fileName, rs);
			}

			if (rs.next()) {
				final Map<String, Object> record = resultSetToMap(rs);
				currentRecords.put(fileName, record);
				return record;
			}

			return null;
		} catch (final SQLException e) {
			throw new RuntimeException("READ failed for " + fileName, e);
		}
	}

	@Override
	public void write(final String fileName) {
		LOG.debug("WRITE {}", fileName);
		final Map<String, Object> record = currentRecords.get(fileName);
		if (record == null || record.isEmpty()) {
			LOG.warn("WRITE {} with no current record", fileName);
			return;
		}

		try (Connection conn = dataSource.getConnection()) {
			final StringBuilder cols = new StringBuilder();
			final StringBuilder vals = new StringBuilder();
			for (final String col : record.keySet()) {
				if (cols.length() > 0) {
					cols.append(", ");
					vals.append(", ");
				}
				cols.append(col);
				vals.append("?");
			}

			final String sql = "INSERT INTO " + sanitize(fileName) + " (" + cols + ") VALUES (" + vals + ")";
			try (PreparedStatement ps = conn.prepareStatement(sql)) {
				int idx = 1;
				for (final Object val : record.values()) {
					ps.setObject(idx++, val);
				}
				ps.executeUpdate();
			}
		} catch (final SQLException e) {
			throw new RuntimeException("WRITE failed for " + fileName, e);
		}
	}

	@Override
	public void rewrite(final String fileName) {
		LOG.debug("REWRITE {}", fileName);
		// Rewrite requires key-based update; log warning for now
		LOG.warn("REWRITE {} - requires key column configuration for full support", fileName);
	}

	@Override
	public void delete(final String fileName) {
		delete(fileName, false);
	}

	@Override
	public void delete(final String fileName, final boolean record) {
		LOG.debug("DELETE {} record={}", fileName, record);
		if (!record) {
			try (Connection conn = dataSource.getConnection();
					PreparedStatement ps = conn.prepareStatement("DELETE FROM " + sanitize(fileName))) {
				ps.executeUpdate();
			} catch (final SQLException e) {
				throw new RuntimeException("DELETE failed for " + fileName, e);
			}
		}
	}

	@Override
	public void start(final String fileName, final Object key) {
		LOG.debug("START {} key={}", fileName, key);
		// Close any existing read cursor
		final ResultSet rs = activeReads.remove(fileName);
		if (rs != null) {
			try {
				rs.getStatement().close();
				rs.close();
			} catch (final SQLException e) {
				LOG.warn("Error closing result set for {}", fileName, e);
			}
		}
	}

	@Override
	public boolean isEndOfFile(final String fileName) {
		return !activeReads.containsKey(fileName);
	}

	@Override
	public void writeSubfile(final String fileName, final String record, final String format,
			final Object indicators) {
		LOG.debug("WRITE SUBFILE {} record={} format={}", fileName, record, format);
		write(fileName);
	}

	@Override
	public Object readSubfile(final String fileName, final String format, final Object indicators) {
		LOG.debug("READ SUBFILE {} format={}", fileName, format);
		return read(fileName);
	}

	@Override
	public void rewriteSubfile(final String fileName, final String record, final String format,
			final Object indicators) {
		LOG.debug("REWRITE SUBFILE {} record={} format={}", fileName, record, format);
		rewrite(fileName);
	}

	@Override
	public void write(final String fileName, final String format, final Object indicators) {
		LOG.debug("WRITE {} FORMAT {} INDICATORS {}", fileName, format, indicators);
		write(fileName);
	}

	@Override
	public Object read(final String fileName, final String format, final Object indicators) {
		LOG.debug("READ {} FORMAT {} INDICATORS {}", fileName, format, indicators);
		return read(fileName);
	}

	@Override
	public void rewrite(final String fileName, final String format, final Object indicators) {
		LOG.debug("REWRITE {} FORMAT {} INDICATORS {}", fileName, format, indicators);
		rewrite(fileName);
	}

	private Map<String, Object> resultSetToMap(final ResultSet rs) throws SQLException {
		final ResultSetMetaData meta = rs.getMetaData();
		final Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 1; i <= meta.getColumnCount(); i++) {
			map.put(meta.getColumnName(i), rs.getObject(i));
		}
		return map;
	}

	private String sanitize(final String name) {
		return name.replaceAll("[^a-zA-Z0-9_]", "");
	}
}
