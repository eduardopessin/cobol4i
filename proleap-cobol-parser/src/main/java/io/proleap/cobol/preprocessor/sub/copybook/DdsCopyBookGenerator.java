package io.proleap.cobol.preprocessor.sub.copybook;

import java.io.File;
import java.io.IOException;
import java.io.StreamTokenizer;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates COBOL data description entries from DDS schema JSON files.
 * Uses a simple JSON tokenizer to avoid external dependencies (Jackson).
 */
public class DdsCopyBookGenerator {

	private static final Logger LOG = LoggerFactory.getLogger(DdsCopyBookGenerator.class);

	/**
	 * COBOL reserved words that cannot be used as data item names.
	 * DDS field names matching these are prefixed with "DDS-" to avoid parse errors.
	 */
	private static final Set<String> COBOL_RESERVED_WORDS = new HashSet<>();
	static {
		// Only include words that actually appear in DDS schemas and cause parse failures
		COBOL_RESERVED_WORDS.add("LABEL");
		COBOL_RESERVED_WORDS.add("ADDRESS");
		COBOL_RESERVED_WORDS.add("DATA");
		COBOL_RESERVED_WORDS.add("VALUE");
		COBOL_RESERVED_WORDS.add("VALUES");
		COBOL_RESERVED_WORDS.add("STATUS");
		COBOL_RESERVED_WORDS.add("SIZE");
		COBOL_RESERVED_WORDS.add("KEY");
		COBOL_RESERVED_WORDS.add("RECORD");
		COBOL_RESERVED_WORDS.add("FILE");
		COBOL_RESERVED_WORDS.add("BLOCK");
		COBOL_RESERVED_WORDS.add("INDEX");
		COBOL_RESERVED_WORDS.add("PROGRAM");
		COBOL_RESERVED_WORDS.add("SECTION");
		COBOL_RESERVED_WORDS.add("DIVISION");
		COBOL_RESERVED_WORDS.add("COPY");
		COBOL_RESERVED_WORDS.add("REPLACE");
		COBOL_RESERVED_WORDS.add("SIGN");
		COBOL_RESERVED_WORDS.add("DATE");
		COBOL_RESERVED_WORDS.add("TIME");
	}

	private final File schemaDirectory;

	public DdsCopyBookGenerator(final File schemaDirectory) {
		this.schemaDirectory = schemaDirectory;
	}

	public String generate(final String fileName, final String formatName, final String prefix) {
		final File schemaFile = findSchemaFile(fileName);

		if (schemaFile == null) {
			LOG.warn("DDS schema file not found for: {}", fileName);
			return "";
		}

		try {
			final String content = new String(Files.readAllBytes(schemaFile.toPath()));
			return generateFromJson(content, formatName, prefix, fileName);
		} catch (final IOException e) {
			LOG.error("Error reading DDS schema file: {}", schemaFile, e);
			return "";
		}
	}

	protected File findSchemaFile(final String fileName) {
		if (schemaDirectory == null || !schemaDirectory.isDirectory()) {
			return null;
		}

		final File[] files = schemaDirectory.listFiles();
		if (files == null) {
			return null;
		}

		for (final File file : files) {
			final String name = file.getName();
			if (name.toLowerCase().contains(fileName.toLowerCase()) && name.endsWith("_schema.json")) {
				return file;
			}
		}

		return null;
	}

	protected String generateFromJson(final String json, final String formatName, final String prefix,
			final String fileName) {
		final StringBuilder sb = new StringBuilder();

		// Simple extraction: find format blocks with "columns" arrays
		// Schema format: { "FORMAT_NAME": { "columns": [ { "name":"X", "type":"CHAR", "length":10 }, ... ] }, ... }
		final Map<String, List<Map<String, String>>> formats = parseFormats(json);

		if (formatName == null || "ALL-FORMATS".equalsIgnoreCase(formatName)) {
			for (final Map.Entry<String, List<Map<String, String>>> entry : formats.entrySet()) {
				generateFormatEntry(sb, entry.getKey(), entry.getValue());
			}
		} else {
			List<Map<String, String>> columns = formats.get(formatName);
			if (columns == null) {
				// DDS I/O format: IMG1-I, IMG1-O → strip -I/-O suffix, look up base format IMG1
				final String baseFormat = stripIoSuffix(formatName);
				if (baseFormat != null) {
					if ("ALL-FORMATS".equalsIgnoreCase(baseFormat)) {
						// ALL-FORMATS-I or ALL-FORMATS-O → generate all formats
						for (final Map.Entry<String, List<Map<String, String>>> entry : formats.entrySet()) {
							generateFormatEntry(sb, entry.getKey(), entry.getValue());
						}
						return sb.toString();
					}
					columns = formats.get(baseFormat);
				}
			}
			if (columns != null) {
				generateFormatEntry(sb, formatName, columns);
			} else {
				LOG.warn("Format {} not found in schema for file {}", formatName, fileName);
			}
		}

		return sb.toString();
	}

	/**
	 * Strips -I or -O suffix from DDS record format names.
	 * AS/400 display files have input (-I) and output (-O) variants of each format.
	 * E.g., IMG1-I and IMG1-O both refer to the IMG1 format.
	 */
	protected String stripIoSuffix(final String formatName) {
		if (formatName != null && formatName.length() > 2) {
			final String suffix = formatName.substring(formatName.length() - 2);
			if ("-I".equalsIgnoreCase(suffix) || "-O".equalsIgnoreCase(suffix)) {
				return formatName.substring(0, formatName.length() - 2);
			}
		}
		return null;
	}

	/**
	 * Simple JSON parser for the specific schema format we use.
	 * Extracts format names and their column definitions.
	 */
	protected Map<String, List<Map<String, String>>> parseFormats(final String json) {
		final Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();

		// Find each format block: "FORMAT_NAME" : { ... "columns" : [ ... ] }
		int pos = 0;
		while (pos < json.length()) {
			// Find next quoted key at top level
			final int keyStart = json.indexOf('"', pos);
			if (keyStart < 0) break;
			final int keyEnd = json.indexOf('"', keyStart + 1);
			if (keyEnd < 0) break;

			final String key = json.substring(keyStart + 1, keyEnd);
			pos = keyEnd + 1;

			// Skip to colon
			final int colon = json.indexOf(':', pos);
			if (colon < 0) break;
			pos = colon + 1;

			// Skip whitespace
			while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) pos++;

			if (pos >= json.length() || json.charAt(pos) != '{') {
				continue;
			}

			// Find matching closing brace
			final int blockEnd = findMatchingBrace(json, pos);
			if (blockEnd < 0) break;

			final String block = json.substring(pos, blockEnd + 1);

			// Find "columns" array within the block
			final int colIdx = block.indexOf("\"columns\"");
			if (colIdx >= 0) {
				final int arrStart = block.indexOf('[', colIdx);
				if (arrStart >= 0) {
					final int arrEnd = findMatchingBracket(block, arrStart);
					if (arrEnd >= 0) {
						final String columnsJson = block.substring(arrStart + 1, arrEnd);
						final List<Map<String, String>> columns = parseColumns(columnsJson);
						result.put(key, columns);
					}
				}
			}

			pos = blockEnd + 1;
		}

		return result;
	}

	protected List<Map<String, String>> parseColumns(final String columnsJson) {
		final List<Map<String, String>> columns = new ArrayList<>();

		int pos = 0;
		while (pos < columnsJson.length()) {
			final int objStart = columnsJson.indexOf('{', pos);
			if (objStart < 0) break;

			final int objEnd = findMatchingBrace(columnsJson, objStart);
			if (objEnd < 0) break;

			final String obj = columnsJson.substring(objStart + 1, objEnd);
			final Map<String, String> column = parseSimpleObject(obj);
			if (!column.isEmpty()) {
				columns.add(column);
			}

			pos = objEnd + 1;
		}

		return columns;
	}

	protected Map<String, String> parseSimpleObject(final String obj) {
		final Map<String, String> result = new LinkedHashMap<>();

		int pos = 0;
		while (pos < obj.length()) {
			final int keyStart = obj.indexOf('"', pos);
			if (keyStart < 0) break;
			final int keyEnd = obj.indexOf('"', keyStart + 1);
			if (keyEnd < 0) break;

			final String key = obj.substring(keyStart + 1, keyEnd);

			final int colon = obj.indexOf(':', keyEnd);
			if (colon < 0) break;

			pos = colon + 1;
			while (pos < obj.length() && Character.isWhitespace(obj.charAt(pos))) pos++;

			if (pos >= obj.length()) break;

			String value;
			if (obj.charAt(pos) == '"') {
				final int valEnd = obj.indexOf('"', pos + 1);
				if (valEnd < 0) break;
				value = obj.substring(pos + 1, valEnd);
				pos = valEnd + 1;
			} else if (obj.charAt(pos) == 'n' && obj.startsWith("null", pos)) {
				value = null;
				pos += 4;
			} else {
				// Number or boolean
				final int end = findValueEnd(obj, pos);
				value = obj.substring(pos, end).trim();
				pos = end;
			}

			if (value != null) {
				result.put(key, value);
			}
		}

		return result;
	}

	protected int findValueEnd(final String s, final int start) {
		int pos = start;
		while (pos < s.length() && s.charAt(pos) != ',' && s.charAt(pos) != '}' && s.charAt(pos) != ']'
				&& !Character.isWhitespace(s.charAt(pos))) {
			pos++;
		}
		return pos;
	}

	protected int findMatchingBrace(final String s, final int start) {
		int depth = 0;
		boolean inString = false;

		for (int i = start; i < s.length(); i++) {
			final char c = s.charAt(i);
			if (inString) {
				if (c == '"' && s.charAt(i - 1) != '\\') inString = false;
			} else {
				if (c == '"') inString = true;
				else if (c == '{') depth++;
				else if (c == '}') {
					depth--;
					if (depth == 0) return i;
				}
			}
		}
		return -1;
	}

	protected int findMatchingBracket(final String s, final int start) {
		int depth = 0;
		boolean inString = false;

		for (int i = start; i < s.length(); i++) {
			final char c = s.charAt(i);
			if (inString) {
				if (c == '"' && s.charAt(i - 1) != '\\') inString = false;
			} else {
				if (c == '"') inString = true;
				else if (c == '[') depth++;
				else if (c == ']') {
					depth--;
					if (depth == 0) return i;
				}
			}
		}
		return -1;
	}

	protected void generateFormatEntry(final StringBuilder sb, final String formatName,
			final List<Map<String, String>> columns) {
		// COBOL FIXED format: col 1-6=seq, col 7=indicator, col 8-11=Area A, col 12-72=Area B
		// Level 05 goes in Area A (col 8 = 7 spaces), children in Area B (col 12 = 11 spaces)
		sb.append("       05 ").append(formatName).append(".\n");

		for (final Map<String, String> column : columns) {
			generateColumnEntry(sb, column);
		}

		// Generate -INDIC sub-group for indicator columns (*INxx).
		// In AS/400 DDS, indicator fields are prefixed with '*IN' and are used to control
		// screen/print attributes. COBOL programs reference them via <FORMAT>-INDIC group,
		// e.g., "MOVE '0' TO IN50 OF DET-O-INDIC".
		generateIndicatorGroup(sb, formatName, columns);

		// For formats WITHOUT -I/-O suffix (e.g., COPY DDSR-IMG5 OF DSPFILA),
		// the AS/400 COBOL runtime also makes the fields accessible via <format>-I
		// and <format>-O sub-groups. Programs reference these (e.g., MOVE CORR IMG2-O TO IMG5-O).
		// Generate sibling groups with the same fields so both bare and -I/-O references resolve.
		if (stripIoSuffix(formatName) == null) {
			// No -I or -O suffix → generate -I and -O copies
			sb.append("       05 ").append(formatName).append("-I.\n");
			for (final Map<String, String> column : columns) {
				generateColumnEntry(sb, column);
			}
			generateIndicatorGroup(sb, formatName + "-I", columns);
			sb.append("       05 ").append(formatName).append("-O.\n");
			for (final Map<String, String> column : columns) {
				generateColumnEntry(sb, column);
			}
			generateIndicatorGroup(sb, formatName + "-O", columns);
		}
	}

	/**
	 * Generates a -INDIC sub-group for DDS indicator columns (*INxx).
	 * In AS/400 COBOL, when a DDS record format has indicator fields, the COPY DDSR
	 * expansion produces an additional <FORMAT>-INDIC group containing those indicators
	 * as PIC 1 fields (e.g., IN50 PIC 1 INDIC 50). Programs reference them as:
	 *   MOVE "0" TO IN50 OF DET-O-INDIC
	 * Only generates the group if there are indicator columns present.
	 */
	protected void generateIndicatorGroup(final StringBuilder sb, final String formatName,
			final List<Map<String, String>> columns) {
		// Collect indicator columns: those with names starting with '*IN'
		final List<Map<String, String>> indicatorColumns = new ArrayList<>();
		for (final Map<String, String> column : columns) {
			final String name = column.getOrDefault("name", "");
			if (name.startsWith("*IN")) {
				indicatorColumns.add(column);
			}
		}

		if (indicatorColumns.isEmpty()) {
			return;
		}

		sb.append("       05 ").append(formatName).append("-INDIC.\n");
		for (final Map<String, String> indCol : indicatorColumns) {
			// Strip the '*' prefix: *IN50 -> IN50, convert underscores to hyphens
			final String indName = indCol.getOrDefault("name", "").substring(1).replace('_', '-');
			// Extract indicator number from name (e.g., IN50 -> 50)
			final String indNumStr = indName.replaceAll("[^0-9]", "");
			if (!indNumStr.isEmpty()) {
				sb.append("           10 ").append(indName).append(" PIC 1 INDIC ").append(indNumStr).append(".\n");
			} else {
				// Fallback: just PIC 1 without INDIC clause
				sb.append("           10 ").append(indName).append(" PIC 1.\n");
			}
		}
	}

	protected void generateColumnEntry(final StringBuilder sb, final Map<String, String> column) {
		// IBM AS/400 COPY DDSR converts underscores to hyphens in COBOL field names
		String name = column.getOrDefault("name", "FILLER").replace('_', '-');

		// Skip DDS indicator fields (*INxx) — they are not referenced by name in COBOL programs;
		// programs use the INDIC array instead. Including them would produce '*' at column 14
		// which the COBOL parser treats as a comment indicator, causing parse failures.
		if (name.startsWith("*")) {
			return;
		}

		// Rename COBOL reserved words used as DDS field names to avoid parse errors.
		// E.g., DDS field "LABEL" becomes "DDS-LABEL" in the generated COBOL.
		if (COBOL_RESERVED_WORDS.contains(name.toUpperCase())) {
			name = "DDS-" + name;
		}

		final String type = column.getOrDefault("type", "CHAR");
		final int length = parseIntSafe(column.get("length"), 0);
		final int scale = parseIntSafe(column.get("scale"), 0);

		if ("VARCHAR".equalsIgnoreCase(type)) {
			// VARCHAR generates a group with LENGTH and DATA subfields
			sb.append("           10 ").append(name).append(".\n");
			sb.append("              15 ").append(name).append("-LENGTH PIC S9(4) COMP-4.\n");
			sb.append("              15 ").append(name).append("-DATA PIC X(").append(Math.max(length, 1)).append(").\n");
		} else {
			sb.append("           10 ").append(name);
			sb.append(" ").append(mapDbTypeToCobolPic(type, length, scale));
			sb.append(".\n");
		}
	}

	protected int parseIntSafe(final String s, final int defaultValue) {
		if (s == null || s.isEmpty()) return defaultValue;
		try {
			return Integer.parseInt(s);
		} catch (final NumberFormatException e) {
			return defaultValue;
		}
	}

	protected String mapDbTypeToCobolPic(final String dbType, final int length, final int scale) {
		switch (dbType.toUpperCase()) {
		case "CHAR":
			return "PIC X(" + length + ")";
		case "DECIMAL":
		case "NUMERIC":
		case "PACKED":
			if (scale > 0) {
				return "PIC S9(" + (length - scale) + ")V9(" + scale + ") COMP-3";
			} else {
				return "PIC S9(" + length + ") COMP-3";
			}
		case "SMALLINT":
			return "PIC S9(4) COMP-4";
		case "INTEGER":
		case "INT":
			return "PIC S9(9) COMP-4";
		case "BIGINT":
			return "PIC S9(18) COMP-4";
		case "FLOAT":
		case "REAL":
			return "COMP-1";
		case "DOUBLE":
			return "COMP-2";
		case "DATE":
			return "PIC X(10)";
		case "TIME":
			return "PIC X(8)";
		case "TIMESTMP":
		case "TIMESTAMP":
			return "PIC X(26)";
		case "BINARY":
		case "VARBINARY":
			return "PIC X(" + length + ")";
		case "ZONED":
			if (scale > 0) {
				return "PIC S9(" + (length - scale) + ")V9(" + scale + ")";
			} else {
				return "PIC S9(" + length + ")";
			}
		default:
			return "PIC X(" + Math.max(length, 1) + ")";
		}
	}
}
