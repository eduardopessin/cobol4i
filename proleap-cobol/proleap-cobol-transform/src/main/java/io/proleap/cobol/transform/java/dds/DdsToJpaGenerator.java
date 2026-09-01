package io.proleap.cobol.transform.java.dds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates JPA @Entity Java classes from DB2 schema JSON files
 * extracted from IBM i DDS definitions.
 */
public class DdsToJpaGenerator {

    private static final String PACKAGE_NAME = "io.proleap.cobol.generated.entities";
    private static final String NEWLINE = System.lineSeparator();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path outputDir;

    public DdsToJpaGenerator(Path outputDir) {
        this.outputDir = outputDir;
    }

    public static void main(String[] args) throws IOException {
        Path outputDir;
        if (args.length > 0) {
            outputDir = Path.of(args[0]);
        } else {
            outputDir = Path.of("generated/entities");
        }

        DdsToJpaGenerator generator = new DdsToJpaGenerator(outputDir);

        String schemasDir = args.length > 1 ? args[1] : "extracted/schemas";
        File[] schemaFiles = new File(schemasDir).listFiles((dir, name) -> name.endsWith("_schema.json"));

        if (schemaFiles == null || schemaFiles.length == 0) {
            System.err.println("No schema files found in " + schemasDir);
            System.exit(1);
        }

        int totalGenerated = 0;
        for (File schemaFile : schemaFiles) {
            int count = generator.generateFromSchema(schemaFile.toPath());
            totalGenerated += count;
            System.out.println("Generated " + count + " entities from " + schemaFile.getName());
        }
        System.out.println("Total entities generated: " + totalGenerated);
    }

    /**
     * Reads a schema JSON file and generates JPA entity classes for all formats.
     *
     * @return number of entities generated
     */
    public int generateFromSchema(Path schemaFile) throws IOException {
        JsonNode root = objectMapper.readTree(schemaFile.toFile());
        int count = 0;

        Iterator<Map.Entry<String, JsonNode>> formats = root.fields();
        while (formats.hasNext()) {
            Map.Entry<String, JsonNode> entry = formats.next();
            String formatName = entry.getKey();
            JsonNode formatNode = entry.getValue();

            String description = formatNode.has("text") ? formatNode.get("text").asText() : "";
            JsonNode columnsNode = formatNode.get("columns");

            if (columnsNode == null || !columnsNode.isArray() || columnsNode.isEmpty()) {
                continue;
            }

            List<ColumnDef> columns = parseColumns(columnsNode);
            String javaSource = generateEntityClass(formatName, description, columns);

            String className = toClassName(formatName);
            Path outputFile = outputDir.resolve(className + ".java");
            Files.createDirectories(outputFile.getParent());
            Files.writeString(outputFile, javaSource);
            count++;
        }
        return count;
    }

    private List<ColumnDef> parseColumns(JsonNode columnsNode) {
        List<ColumnDef> columns = new ArrayList<>();
        for (JsonNode col : columnsNode) {
            ColumnDef def = new ColumnDef();
            def.name = col.get("name").asText();
            def.type = col.get("type").asText();
            def.length = col.has("length") && !col.get("length").isNull() ? col.get("length").asInt() : 0;
            def.scale = col.has("scale") && !col.get("scale").isNull() ? col.get("scale").asInt() : 0;
            def.precision = col.has("precision") && !col.get("precision").isNull() ? col.get("precision").asInt() : 0;
            def.nullable = col.has("nullable") && "Y".equals(col.get("nullable").asText());
            def.text = col.has("text") ? col.get("text").asText() : "";
            columns.add(def);
        }
        return columns;
    }

    String generateEntityClass(String formatName, String description, List<ColumnDef> columns) {
        StringBuilder sb = new StringBuilder();
        String className = toClassName(formatName);

        // Package and imports
        sb.append("package ").append(PACKAGE_NAME).append(";").append(NEWLINE).append(NEWLINE);

        // Collect imports based on used types
        Map<String, Boolean> imports = new LinkedHashMap<>();
        imports.put("jakarta.persistence.Column", true);
        imports.put("jakarta.persistence.Entity", true);
        imports.put("jakarta.persistence.Id", true);
        imports.put("jakarta.persistence.Table", true);

        boolean hasCompositeKey = columns.size() > 1 && needsCompositeKey(columns);
        if (hasCompositeKey) {
            imports.put("jakarta.persistence.EmbeddedId", true);
            imports.put("java.io.Serializable", true);
        }

        for (ColumnDef col : columns) {
            String javaType = mapToJavaType(col.type);
            if ("BigDecimal".equals(javaType)) {
                imports.put("java.math.BigDecimal", true);
            } else if ("LocalDate".equals(javaType)) {
                imports.put("java.time.LocalDate", true);
            } else if ("LocalTime".equals(javaType)) {
                imports.put("java.time.LocalTime", true);
            } else if ("LocalDateTime".equals(javaType)) {
                imports.put("java.time.LocalDateTime", true);
            }
            if (needsSizeAnnotation(col)) {
                imports.put("jakarta.validation.constraints.Size", true);
            }
            if (needsDigitsAnnotation(col)) {
                imports.put("jakarta.validation.constraints.Digits", true);
            }
        }

        for (String imp : imports.keySet()) {
            sb.append("import ").append(imp).append(";").append(NEWLINE);
        }
        sb.append(NEWLINE);

        // Class javadoc
        if (!description.isEmpty()) {
            sb.append("/**").append(NEWLINE);
            sb.append(" * ").append(description).append(NEWLINE);
            sb.append(" * <p>Generated from DDS format: ").append(formatName).append("</p>").append(NEWLINE);
            sb.append(" */").append(NEWLINE);
        }

        // Class annotations
        sb.append("@Entity").append(NEWLINE);
        sb.append("@Table(name = \"").append(formatName).append("\")").append(NEWLINE);
        sb.append("public class ").append(className);
        if (hasCompositeKey) {
            sb.append(" implements Serializable");
        }
        sb.append(" {").append(NEWLINE).append(NEWLINE);

        if (hasCompositeKey) {
            sb.append("    private static final long serialVersionUID = 1L;").append(NEWLINE).append(NEWLINE);
            // Generate EmbeddedId class
            generateEmbeddedIdClass(sb, className, columns);
        }

        // Fields
        boolean firstField = true;
        for (ColumnDef col : columns) {
            String javaType = mapToJavaType(col.type);
            String fieldName = toFieldName(col.name);

            if (!hasCompositeKey && firstField) {
                sb.append("    @Id").append(NEWLINE);
                firstField = false;
            }

            // @Column annotation
            sb.append("    @Column(name = \"").append(col.name).append("\"");
            if (isStringType(col.type) && col.length > 0) {
                sb.append(", length = ").append(col.length);
            }
            if (isDecimalType(col.type)) {
                int p = col.precision > 0 ? col.precision : col.length;
                if (p > 0) {
                    sb.append(", precision = ").append(p);
                }
                if (col.scale > 0) {
                    sb.append(", scale = ").append(col.scale);
                }
            }
            if (!col.nullable) {
                sb.append(", nullable = false");
            }
            sb.append(")").append(NEWLINE);

            // Validation annotations
            if (needsSizeAnnotation(col)) {
                sb.append("    @Size(max = ").append(col.length).append(")").append(NEWLINE);
            }
            if (needsDigitsAnnotation(col)) {
                int p = col.precision > 0 ? col.precision : col.length;
                sb.append("    @Digits(integer = ").append(p - col.scale)
                        .append(", fraction = ").append(col.scale).append(")").append(NEWLINE);
            }

            // Field comment
            if (!col.text.isEmpty()) {
                sb.append("    /** ").append(col.text).append(" */").append(NEWLINE);
            }

            sb.append("    private ").append(javaType).append(" ").append(fieldName).append(";").append(NEWLINE);
            sb.append(NEWLINE);
        }

        // Getters and setters
        for (ColumnDef col : columns) {
            String javaType = mapToJavaType(col.type);
            String fieldName = toFieldName(col.name);
            String capitalizedField = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);

            // Getter
            sb.append("    public ").append(javaType).append(" get").append(capitalizedField).append("() {").append(NEWLINE);
            sb.append("        return ").append(fieldName).append(";").append(NEWLINE);
            sb.append("    }").append(NEWLINE).append(NEWLINE);

            // Setter
            sb.append("    public void set").append(capitalizedField).append("(").append(javaType).append(" ").append(fieldName).append(") {").append(NEWLINE);
            sb.append("        this.").append(fieldName).append(" = ").append(fieldName).append(";").append(NEWLINE);
            sb.append("    }").append(NEWLINE).append(NEWLINE);
        }

        sb.append("}").append(NEWLINE);
        return sb.toString();
    }

    private void generateEmbeddedIdClass(StringBuilder sb, String className, List<ColumnDef> columns) {
        // Find key columns (heuristic: columns with KEY in name, or first column)
        List<ColumnDef> keyColumns = getKeyColumns(columns);
        if (keyColumns.size() <= 1) {
            return;
        }

        String idClassName = className + "Id";

        sb.append("    @jakarta.persistence.Embeddable").append(NEWLINE);
        sb.append("    public static class ").append(idClassName).append(" implements Serializable {").append(NEWLINE);
        sb.append("        private static final long serialVersionUID = 1L;").append(NEWLINE).append(NEWLINE);

        for (ColumnDef col : keyColumns) {
            String javaType = mapToJavaType(col.type);
            String fieldName = toFieldName(col.name);
            sb.append("        @Column(name = \"").append(col.name).append("\")").append(NEWLINE);
            sb.append("        private ").append(javaType).append(" ").append(fieldName).append(";").append(NEWLINE).append(NEWLINE);
        }

        // equals, hashCode, getters/setters for embedded id
        sb.append("        public ").append(idClassName).append("() {}").append(NEWLINE).append(NEWLINE);

        for (ColumnDef col : keyColumns) {
            String javaType = mapToJavaType(col.type);
            String fieldName = toFieldName(col.name);
            String cap = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            sb.append("        public ").append(javaType).append(" get").append(cap).append("() { return ").append(fieldName).append("; }").append(NEWLINE);
            sb.append("        public void set").append(cap).append("(").append(javaType).append(" ").append(fieldName).append(") { this.").append(fieldName).append(" = ").append(fieldName).append("; }").append(NEWLINE);
        }

        sb.append(NEWLINE);
        sb.append("        @Override").append(NEWLINE);
        sb.append("        public boolean equals(Object o) {").append(NEWLINE);
        sb.append("            if (this == o) return true;").append(NEWLINE);
        sb.append("            if (o == null || getClass() != o.getClass()) return false;").append(NEWLINE);
        sb.append("            ").append(idClassName).append(" that = (").append(idClassName).append(") o;").append(NEWLINE);
        sb.append("            return ");
        for (int i = 0; i < keyColumns.size(); i++) {
            String fieldName = toFieldName(keyColumns.get(i).name);
            if (i > 0) sb.append(" && ");
            sb.append("java.util.Objects.equals(").append(fieldName).append(", that.").append(fieldName).append(")");
        }
        sb.append(";").append(NEWLINE);
        sb.append("        }").append(NEWLINE).append(NEWLINE);

        sb.append("        @Override").append(NEWLINE);
        sb.append("        public int hashCode() {").append(NEWLINE);
        sb.append("            return java.util.Objects.hash(");
        for (int i = 0; i < keyColumns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(toFieldName(keyColumns.get(i).name));
        }
        sb.append(");").append(NEWLINE);
        sb.append("        }").append(NEWLINE);

        sb.append("    }").append(NEWLINE).append(NEWLINE);

        // Add the EmbeddedId field
        sb.append("    @EmbeddedId").append(NEWLINE);
        sb.append("    private ").append(idClassName).append(" id;").append(NEWLINE).append(NEWLINE);
    }

    private List<ColumnDef> getKeyColumns(List<ColumnDef> columns) {
        // Heuristic: columns whose name contains "KEY" or "COD" at start,
        // or ends with "ID", limited to first few non-nullable columns
        List<ColumnDef> keys = new ArrayList<>();
        for (ColumnDef col : columns) {
            String upper = col.name.toUpperCase();
            if (upper.contains("KEY") || upper.endsWith("ID") || upper.startsWith("COD")) {
                if (!col.nullable) {
                    keys.add(col);
                }
            }
        }
        // If no key heuristic matches, return just the first column
        if (keys.isEmpty() && !columns.isEmpty()) {
            keys.add(columns.get(0));
        }
        return keys;
    }

    private boolean needsCompositeKey(List<ColumnDef> columns) {
        List<ColumnDef> keys = getKeyColumns(columns);
        return keys.size() > 1;
    }

    static String mapToJavaType(String db2Type) {
        if (db2Type == null) return "String";
        switch (db2Type.toUpperCase()) {
            case "CHAR":
            case "VARCHAR":
            case "GRAPHIC":
            case "VARG":
                return "String";
            case "DECIMAL":
            case "NUMERIC":
            case "ZONED":
            case "PACKED":
            case "BINARY":
                return "BigDecimal";
            case "SMALLINT":
                return "Short";
            case "INTEGER":
                return "Integer";
            case "BIGINT":
                return "Long";
            case "DATE":
                // COBOL treats DATE columns as alphanumeric PIC X(10) strings (ISO format)
                return "String";
            case "TIME":
                // COBOL treats TIME columns as alphanumeric strings
                return "String";
            case "TIMESTMP":
                // COBOL treats TIMESTAMP columns as alphanumeric strings
                return "String";
            case "BLOB":
                return "byte[]";
            case "XML":
                return "String";
            default:
                return "String";
        }
    }

    private boolean isStringType(String type) {
        switch (type.toUpperCase()) {
            case "CHAR":
            case "VARCHAR":
            case "GRAPHIC":
            case "VARG":
            case "XML":
            case "DATE":
            case "TIME":
            case "TIMESTMP":
                return true;
            default:
                return false;
        }
    }

    private boolean isDecimalType(String type) {
        switch (type.toUpperCase()) {
            case "DECIMAL":
            case "NUMERIC":
            case "ZONED":
            case "PACKED":
            case "BINARY":
                return true;
            default:
                return false;
        }
    }

    private boolean needsSizeAnnotation(ColumnDef col) {
        return isStringType(col.type) && col.length > 0;
    }

    private boolean needsDigitsAnnotation(ColumnDef col) {
        return isDecimalType(col.type) && col.scale > 0;
    }

    static String toClassName(String formatName) {
        // Convert a DDS format name into a proper Java class name
        // Keep as-is but ensure first char is uppercase
        StringBuilder sb = new StringBuilder();
        boolean capitalize = true;
        for (char c : formatName.toCharArray()) {
            if (c == '_' || c == '-') {
                capitalize = true;
            } else if (capitalize) {
                sb.append(Character.toUpperCase(c));
                capitalize = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        String result = sb.toString();
        if (!result.isEmpty() && Character.isDigit(result.charAt(0))) {
            result = "_" + result;
        }
        return result;
    }

    static String toFieldName(String columnName) {
        // Convert column name to camelCase field name
        StringBuilder sb = new StringBuilder();
        boolean capitalize = false;
        boolean first = true;
        for (char c : columnName.toCharArray()) {
            if (c == '_' || c == '-') {
                capitalize = true;
            } else if (first) {
                sb.append(Character.toLowerCase(c));
                first = false;
            } else if (capitalize) {
                sb.append(Character.toUpperCase(c));
                capitalize = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        String result = sb.toString();
        if (!result.isEmpty() && Character.isDigit(result.charAt(0))) {
            result = "_" + result;
        }
        return result;
    }

    static class ColumnDef {
        String name;
        String type;
        int length;
        int scale;
        int precision;
        boolean nullable;
        String text;
    }
}
