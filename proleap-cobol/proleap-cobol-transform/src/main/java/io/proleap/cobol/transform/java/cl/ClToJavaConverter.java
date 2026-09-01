package io.proleap.cobol.transform.java.cl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts IBM i CL (Control Language) programs to Java orchestration classes.
 *
 * <p>Parses CL source files and generates Java classes that replicate the
 * orchestration logic: program calls, job submission, file overrides,
 * variable manipulation, conditional logic, and error handling.</p>
 */
public class ClToJavaConverter {

    // Generated CL classes live in the default (no) package so the
    // ProgramRunnerImpl test loader can find them via Class.forName(<program>).
    // This matches the convention used by the COBOL transformer (generated
    // COBOL Java classes in generated/cobol/ also have no package).
    private static final String PACKAGE_NAME = "";
    private static final String NL = System.lineSeparator();

    private final Path outputDir;

    public ClToJavaConverter(Path outputDir) {
        this.outputDir = outputDir;
    }

    // ── Main entry point ────────────────────────────────────────────────

    public static void main(String[] args) throws IOException {
        Path outputDir = args.length > 0 ? Path.of(args[0]) : Path.of("generated/cl");
        ClToJavaConverter converter = new ClToJavaConverter(outputDir);

        List<Path> sourceDirs = new ArrayList<>();
        if (args.length > 1) {
            for (int i = 1; i < args.length; i++) {
                sourceDirs.add(Path.of(args[i]));
            }
        } else {
            sourceDirs.add(Path.of("extracted/source_cl"));
        }

        int total = 0;
        for (Path dir : sourceDirs) {
            File[] clFiles = dir.toFile().listFiles((d, name) -> name.endsWith(".cl"));
            if (clFiles == null) {
                System.err.println("No .cl files found in " + dir);
                continue;
            }
            int count = 0;
            for (File clFile : clFiles) {
                try {
                    converter.convert(clFile.toPath());
                    count++;
                } catch (Exception e) {
                    System.err.println("WARN: Failed to convert " + clFile.getName() + ": " + e.getMessage());
                }
            }
            System.out.println("Converted " + count + " CL programs from " + dir);
            total += count;
        }
        System.out.println("Total converted: " + total);
    }

    // ── Public API ──────────────────────────────────────────────────────

    public void convert(Path clFile) throws IOException {
        String source = Files.readString(clFile);
        String fileName = clFile.getFileName().toString().replaceFirst("\\.cl$", "");
        String className = toClassName(fileName);

        ClProgram program = parse(source, fileName);
        String java = generate(program, className);

        Path outFile = outputDir.resolve(className + ".java");
        Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, java);
    }

    // ── CL Parser ───────────────────────────────────────────────────────

    ClProgram parse(String source, String fileName) {
        ClProgram program = new ClProgram();
        program.name = fileName;

        // Strip comments and join continuation lines
        String cleaned = stripComments(source);
        cleaned = joinContinuations(cleaned);

        // Split into individual CL statements
        List<ClStatement> statements = tokenizeStatements(cleaned);

        for (ClStatement stmt : statements) {
            String cmd = stmt.command.toUpperCase();
            switch (cmd) {
                case "PGM":
                    parsePgm(stmt, program);
                    break;
                case "DCL":
                    parseDcl(stmt, program);
                    break;
                case "DCLF":
                    parseDclf(stmt, program);
                    break;
                case "ENDPGM":
                    // no-op
                    break;
                default:
                    program.body.add(stmt);
                    break;
            }
        }
        return program;
    }

    private String stripComments(String source) {
        // Remove /* ... */ comments (can span lines)
        return source.replaceAll("/\\*.*?\\*/", " ").replaceAll("/\\*[^*]*$", " ");
    }

    private String joinContinuations(String source) {
        // CL continuation: line ends with +, next line continues
        // Also collapse multiple spaces
        StringBuilder sb = new StringBuilder();
        for (String line : source.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.endsWith("+")) {
                sb.append(trimmed, 0, trimmed.length() - 1).append(" ");
            } else {
                sb.append(trimmed).append("\n");
            }
        }
        return sb.toString();
    }

    private List<ClStatement> tokenizeStatements(String source) {
        List<ClStatement> statements = new ArrayList<>();
        for (String line : source.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            ClStatement stmt = new ClStatement();

            // Check for label: LABEL: CMD ...
            Matcher labelMatch = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*):\\s*(.*)$").matcher(trimmed);
            if (labelMatch.matches()) {
                stmt.label = labelMatch.group(1);
                trimmed = labelMatch.group(2).trim();
            }

            if (trimmed.isEmpty()) {
                // Label-only line
                if (stmt.label != null) {
                    stmt.command = "LABEL";
                    stmt.rawText = "";
                    statements.add(stmt);
                }
                continue;
            }

            // Extract command name
            Matcher cmdMatch = Pattern.compile("^([A-Za-z][A-Za-z0-9]*)\\b(.*)$").matcher(trimmed);
            if (cmdMatch.matches()) {
                stmt.command = cmdMatch.group(1).toUpperCase();
                stmt.rawText = cmdMatch.group(2).trim();
                parseParameters(stmt);
                statements.add(stmt);
            }
        }
        return statements;
    }

    private void parseParameters(ClStatement stmt) {
        // Parse keyword parameters with balanced parentheses: KEY(value with %SST(&x 1 2))
        String text = stmt.rawText;
        int i = 0;
        while (i < text.length()) {
            // Skip whitespace
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
            if (i >= text.length()) break;

            // Look for KEYWORD(
            int keyStart = i;
            while (i < text.length() && Character.isLetterOrDigit(text.charAt(i)) || (i < text.length() && text.charAt(i) == '_')) i++;
            if (i >= text.length() || text.charAt(i) != '(') {
                i++;
                continue;
            }
            String keyword = text.substring(keyStart, i).toUpperCase();
            i++; // skip '('

            // Extract balanced value
            int depth = 1;
            int valueStart = i;
            while (i < text.length() && depth > 0) {
                char c = text.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') depth--;
                if (depth > 0) i++;
            }
            if (depth == 0) {
                String value = text.substring(valueStart, i).trim();
                stmt.params.put(keyword, value);
                i++; // skip closing ')'
            }
        }
    }

    private void parsePgm(ClStatement stmt, ClProgram program) {
        String parm = stmt.params.get("PARM");
        if (parm != null) {
            for (String p : parm.split("\\s+")) {
                String clean = p.replaceAll("[&()]", "").trim();
                if (!clean.isEmpty()) {
                    program.parameters.add(clean);
                }
            }
        }
    }

    private void parseDcl(ClStatement stmt, ClProgram program) {
        String var = stmt.params.get("VAR");
        String type = stmt.params.get("TYPE");
        String len = stmt.params.get("LEN");
        String value = stmt.params.get("VALUE");

        if (var != null) {
            ClVariable v = new ClVariable();
            v.name = var.replace("&", "").trim();
            v.type = type != null ? type : "*CHAR";
            v.length = len != null ? parseLength(len) : 0;
            v.initialValue = value;
            program.variables.put(v.name, v);
        }
    }

    private void parseDclf(ClStatement stmt, ClProgram program) {
        String file = stmt.params.get("FILE");
        if (file != null) {
            program.declaredFiles.add(file);
        }
    }

    private int parseLength(String len) {
        try {
            return Integer.parseInt(len.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ── Java Code Generator ─────────────────────────────────────────────

    String generate(ClProgram program, String className) {
        StringBuilder sb = new StringBuilder();

        // Package: empty for default package, matching COBOL transformer convention
        if (PACKAGE_NAME != null && !PACKAGE_NAME.isEmpty()) {
            sb.append("package ").append(PACKAGE_NAME).append(";").append(NL).append(NL);
        }

        // Imports
        sb.append("import java.math.BigDecimal;").append(NL);
        sb.append("import java.util.concurrent.ExecutorService;").append(NL);
        sb.append("import java.util.concurrent.Executors;").append(NL);
        sb.append("import io.proleap.cobol.runtime.CobolProgram;").append(NL);
        sb.append(NL);

        // Class header — must extend CobolProgram so ProgramRunnerImpl can register it
        sb.append("/**").append(NL);
        sb.append(" * Converted from CL (Control Language) program: ").append(program.name).append(NL);
        sb.append(" *").append(NL);
        sb.append(" * PROCEDURE DIVISION USING");
        for (String p : program.parameters) {
            sb.append(" ").append(toFieldName(p));
        }
        sb.append(".").append(NL);
        sb.append(" */").append(NL);
        sb.append("public class ").append(className).append(" extends CobolProgram {").append(NL).append(NL);

        // Executor for SBMJOB (created lazily, only used by CLs that submit jobs)
        sb.append("    private final ExecutorService executorService = Executors.newCachedThreadPool();").append(NL);
        sb.append(NL);

        // Default no-arg constructor — required by ProgramRunnerImpl reflection
        sb.append("    public ").append(className).append("() {}").append(NL).append(NL);

        // File declarations as comments
        for (String file : program.declaredFiles) {
            sb.append("    // DCLF FILE(").append(file).append(")").append(NL);
        }
        if (!program.declaredFiles.isEmpty()) sb.append(NL);

        // LINKAGE-equivalent fields (PARM(...)) declared as instance fields so that
        // ProgramRunnerImpl can read/write them by name via reflection (BY REFERENCE).
        for (String pName : program.parameters) {
            String javaType = getJavaTypeForVar(program, pName);
            String fieldName = toFieldName(pName);
            sb.append("    public ").append(javaType).append(" ").append(fieldName)
                    .append(" = ").append(defaultValue(javaType)).append(";").append(NL);
        }
        if (!program.parameters.isEmpty()) sb.append(NL);

        // WORKING-STORAGE-equivalent fields (DCL of non-PARM variables)
        for (Map.Entry<String, ClVariable> entry : program.variables.entrySet()) {
            ClVariable v = entry.getValue();
            // Skip parameters (already declared above as PARM linkage fields)
            if (program.parameters.contains(v.name)) continue;
            String jType = mapClTypeToJava(v.type);
            String fieldName = toFieldName(v.name);
            if (v.initialValue != null) {
                sb.append("    private ").append(jType).append(" ").append(fieldName)
                        .append(" = ").append(formatJavaValue(v.initialValue, jType)).append(";").append(NL);
            } else {
                sb.append("    private ").append(jType).append(" ").append(fieldName)
                        .append(" = ").append(defaultValue(jType)).append(";").append(NL);
            }
        }
        if (!program.variables.isEmpty()) sb.append(NL);

        // setLinkageParameters override — positional copy from CALL args into our PARM fields
        sb.append("    @Override").append(NL);
        sb.append("    public void setLinkageParameters(final Object... parameters) {").append(NL);
        sb.append("        if (parameters == null) return;").append(NL);
        for (int i = 0; i < program.parameters.size(); i++) {
            String fieldName = toFieldName(program.parameters.get(i));
            String javaType = getJavaTypeForVar(program, program.parameters.get(i));
            sb.append("        if (parameters.length > ").append(i)
                    .append(" && parameters[").append(i).append("] != null) {").append(NL);
            if ("String".equals(javaType)) {
                sb.append("            this.").append(fieldName).append(" = parameters[")
                        .append(i).append("] instanceof String ? (String) parameters[")
                        .append(i).append("] : String.valueOf(parameters[").append(i).append("]);").append(NL);
            } else if ("java.math.BigDecimal".equals(javaType) || "BigDecimal".equals(javaType)) {
                sb.append("            this.").append(fieldName).append(" = parameters[")
                        .append(i).append("] instanceof java.math.BigDecimal ? (java.math.BigDecimal) parameters[")
                        .append(i).append("] : new java.math.BigDecimal(String.valueOf(parameters[").append(i).append("]));").append(NL);
            } else if ("int".equals(javaType)) {
                sb.append("            this.").append(fieldName).append(" = parameters[")
                        .append(i).append("] instanceof Number ? ((Number) parameters[")
                        .append(i).append("]).intValue() : Integer.parseInt(String.valueOf(parameters[").append(i).append("]));").append(NL);
            } else if ("boolean".equals(javaType)) {
                sb.append("            this.").append(fieldName).append(" = Boolean.parseBoolean(String.valueOf(parameters[")
                        .append(i).append("]));").append(NL);
            } else {
                sb.append("            this.").append(fieldName).append(" = (").append(javaType)
                        .append(") parameters[").append(i).append("];").append(NL);
            }
            sb.append("        }").append(NL);
        }
        sb.append("    }").append(NL).append(NL);

        // getLinkageParameters override — return our PARM fields for BY REFERENCE copy-back
        sb.append("    @Override").append(NL);
        sb.append("    public Object[] getLinkageParameters() {").append(NL);
        sb.append("        return new Object[]{");
        for (int i = 0; i < program.parameters.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("this.").append(toFieldName(program.parameters.get(i)));
        }
        sb.append("};").append(NL);
        sb.append("    }").append(NL).append(NL);

        // procedureDivision — the body of the CL program
        sb.append("    @Override").append(NL);
        sb.append("    public void procedureDivision() throws Exception {").append(NL);
        generateBody(sb, program.body, program, 2);
        sb.append("    }").append(NL).append(NL);

        // Shutdown method for executor (called by tests if needed)
        sb.append("    public void shutdown() {").append(NL);
        sb.append("        executorService.shutdown();").append(NL);
        sb.append("    }").append(NL).append(NL);

        // Helper: pad a String on the right with spaces to a fixed length
        // (replicates AS/400 fixed-length character semantics for RTVJOBA results).
        sb.append("    private static String padRight(String s, int len) {").append(NL);
        sb.append("        if (s == null) s = \"\";").append(NL);
        sb.append("        if (s.length() >= len) return s.substring(0, len);").append(NL);
        sb.append("        StringBuilder b = new StringBuilder(s);").append(NL);
        sb.append("        while (b.length() < len) b.append(' ');").append(NL);
        sb.append("        return b.toString();").append(NL);
        sb.append("    }").append(NL);

        sb.append("}").append(NL);
        return sb.toString();
    }

    private void generateBody(StringBuilder sb, List<ClStatement> stmts, ClProgram program, int indentLevel) {
        String indent = "    ".repeat(indentLevel);
        int i = 0;

        while (i < stmts.size()) {
            ClStatement stmt = stmts.get(i);

            // Emit label
            if (stmt.label != null) {
                sb.append(indent).append("// ").append(stmt.label).append(":").append(NL);
            }

            switch (stmt.command) {
                case "LABEL":
                    // Already handled above
                    break;

                case "CALL":
                    generateCall(sb, stmt, indent);
                    break;

                case "SBMJOB":
                    generateSbmjob(sb, stmt, indent);
                    break;

                case "SBMJOBJS":
                    generateSbmjobJs(sb, stmt, indent);
                    break;

                case "OVRDBF":
                    generateOvrdbf(sb, stmt, indent);
                    break;

                case "MONMSG":
                    generateMonmsg(sb, stmt, indent, stmts, i);
                    break;

                case "SNDPGMMSG":
                    generateSndpgmmsg(sb, stmt, indent);
                    break;

                case "CHGVAR":
                    generateChgvar(sb, stmt, indent);
                    break;

                case "IF":
                    i = generateIf(sb, stmt, stmts, i, program, indentLevel);
                    break;

                case "ELSE":
                    // Handled inside IF generation
                    generateElseStandalone(sb, stmt, indent);
                    break;

                case "DO":
                    // Start of DO block, find matching ENDDO
                    break;

                case "ENDDO":
                    // End of block
                    break;

                case "GOTO":
                    generateGoto(sb, stmt, indent);
                    break;

                case "CHKOBJ":
                    generateChkobj(sb, stmt, indent);
                    break;

                case "DLTPGM":
                case "DLTMOD":
                case "DLTF":
                case "DLTSPLF":
                    generateDelete(sb, stmt, indent);
                    break;

                case "CRTDUPOBJ":
                    generateCrtdupobj(sb, stmt, indent);
                    break;

                case "CRTBNDCL":
                case "CRTCLMOD":
                case "CRTPGM":
                case "CRTSQLCBLI":
                case "CRTCBLMOD":
                case "CRTPLIPGM":
                case "CRTPRTF":
                case "CRTDSPF":
                    generateCreate(sb, stmt, indent);
                    break;

                case "CHGLIBL":
                    generateChglibl(sb, stmt, indent);
                    break;

                case "CHGJOB":
                    sb.append(indent).append("// CHGJOB: ").append(stmt.rawText).append(NL);
                    break;

                case "RTVJOBA":
                    generateRtvjoba(sb, stmt, indent);
                    break;

                case "RTVSYSVAL":
                    generateRtvsysval(sb, stmt, indent);
                    break;

                case "DSPJOBLOG":
                    sb.append(indent).append("// DSPJOBLOG (no-op in Java port)").append(NL);
                    break;

                case "CLRPFM":
                    generateClrpfm(sb, stmt, indent);
                    break;

                case "RCVF":
                    generateRcvf(sb, stmt, indent);
                    break;

                case "RUNSQLSTM":
                    generateRunSql(sb, stmt, indent);
                    break;

                case "RTVDTAARA":
                    generateRtvdtaara(sb, stmt, indent);
                    break;

                case "ADDENVVAR":
                    generateAddEnvVar(sb, stmt, indent);
                    break;

                case "DSPFD":
                    generateDspfd(sb, stmt, indent);
                    break;

                case "QSH":
                    generateQsh(sb, stmt, indent);
                    break;

                case "CHGDTAARA":
                    generateChgdtaara(sb, stmt, indent);
                    break;

                case "STRJRNPF":
                case "ENDJRNPF":
                    sb.append(indent).append("// ").append(stmt.command).append(": ").append(stmt.rawText).append(NL);
                    break;

                default:
                    // Unknown CL command — preserve as comment so the Java compiles.
                    sb.append(indent).append("// ").append(stmt.command).append(": ")
                            .append(escapeJava(stmt.rawText)).append(NL);
                    break;
            }
            i++;
        }
    }

    // ── Statement Generators ────────────────────────────────────────────

    private void generateCall(StringBuilder sb, ClStatement stmt, String indent) {
        String pgm = stmt.params.get("PGM");
        String parm = stmt.params.get("PARM");
        if (pgm == null) {
            pgm = extractFirstArg(stmt.rawText);
        }

        sb.append(indent).append("programRunner.call(").append(toStringArg(pgm));
        if (parm != null) {
            for (String p : splitParams(parm)) {
                sb.append(", ").append(toJavaExpr(p));
            }
        }
        sb.append(");").append(NL);
    }

    private void generateSbmjob(StringBuilder sb, ClStatement stmt, String indent) {
        String cmd = stmt.params.get("CMD");
        String job = stmt.params.get("JOB");

        if (cmd != null) {
            // Parse CALL PGM(x) inside CMD
            Matcher callMatch = Pattern.compile("CALL\\s+PGM\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE).matcher(cmd);
            if (callMatch.find()) {
                String pgm = cleanValue(callMatch.group(1));
                sb.append(indent).append("executorService.submit(() -> programRunner.call(\"")
                        .append(pgm).append("\"));").append(NL);
            } else {
                sb.append(indent).append("// SBMJOB CMD: ").append(escapeJava(cmd)).append(NL);
            }
        } else if (job != null) {
            sb.append(indent).append("executorService.submit(() -> programRunner.call(\"")
                    .append(cleanValue(job)).append("\"));").append(NL);
        } else {
            sb.append(indent).append("// SBMJOB: ").append(stmt.rawText).append(NL);
        }
    }

    private void generateSbmjobJs(StringBuilder sb, ClStatement stmt, String indent) {
        String job = stmt.params.get("JOB");
        if (job != null) {
            sb.append(indent).append("executorService.submit(() -> programRunner.call(")
                    .append(toJavaExpr(job)).append("));").append(NL);
        } else {
            sb.append(indent).append("// SBMJOBJS: ").append(stmt.rawText).append(NL);
        }
    }

    private void generateOvrdbf(StringBuilder sb, ClStatement stmt, String indent) {
        sb.append(indent).append("// OVRDBF FILE(")
                .append(escapeJava(stmt.params.getOrDefault("FILE", "")))
                .append(") TOFILE(")
                .append(escapeJava(stmt.params.getOrDefault("TOFILE", "")))
                .append(")").append(NL);
    }

    private void generateMonmsg(StringBuilder sb, ClStatement stmt, String indent,
                                 List<ClStatement> stmts, int index) {
        String msgId = stmt.params.getOrDefault("MSGID", "CPF0000");
        String exec = stmt.params.get("EXEC");

        // Wrap preceding statement in try-catch
        sb.append(indent).append("// MONMSG ").append(msgId);
        if (exec != null) {
            sb.append(NL);
            sb.append(indent).append("// on error: ").append(exec);
        }
        sb.append(NL);
    }

    private void generateSndpgmmsg(StringBuilder sb, ClStatement stmt, String indent) {
        // CL SNDPGMMSG → route through the runtime message-queue SPI so a host
        // (Spring Boot wrapper, test harness, future AS/400 bridge) can observe
        // / deliver the message instead of it being silently dropped to stdout.
        //
        // Form:  SNDPGMMSG MSGID(MSG00003) MSGF(APPMSG) TOPGMQ(*PRV (&PGM))
        //                  ^^^^^^^^^^^^^^ ^^^^^^^^^^^^
        //                  message id     message file (for AS/400 msg catalogs)
        //
        // If MSGID/MSGF are absent the CL used MSG('literal') — preserve that
        // as a first argument and pass an empty msgFile. The handler is
        // responsible for the dispatch; the default NOOP is safe for batch.
        String msgId = stmt.params.get("MSGID");
        String msgFile = stmt.params.get("MSGF");
        String msgLiteral = stmt.params.get("MSG");

        String idExpr;
        if (msgId != null) {
            idExpr = "\"" + escapeJava(cleanQuotedString(msgId)) + "\"";
        } else if (msgLiteral != null) {
            idExpr = "\"" + escapeJava(cleanQuotedString(msgLiteral)) + "\"";
        } else {
            idExpr = "\"\"";
        }
        String fileExpr = msgFile != null
                ? "\"" + escapeJava(cleanQuotedString(msgFile)) + "\""
                : "\"\"";

        sb.append(indent)
                .append("io.proleap.cobol.runtime.msg.MessageQueueService.sendProgramMessage(")
                .append(idExpr).append(", ").append(fileExpr).append(");").append(NL);
    }

    private void generateChgvar(StringBuilder sb, ClStatement stmt, String indent) {
        String var = stmt.params.get("VAR");
        String value = stmt.params.get("VALUE");
        if (var != null && value != null) {
            String fieldName = toFieldName(var.replace("&", ""));
            sb.append(indent).append(fieldName).append(" = ").append(toJavaExpr(value)).append(";").append(NL);
        }
    }

    private int generateIf(StringBuilder sb, ClStatement stmt, List<ClStatement> stmts,
                            int index, ClProgram program, int indentLevel) {
        String indent = "    ".repeat(indentLevel);
        String cond = stmt.params.get("COND");
        String then = stmt.params.get("THEN");

        String javaCond = convertCondition(cond != null ? cond : "");
        sb.append(indent).append("if (").append(javaCond).append(") {").append(NL);

        if (then != null) {
            String thenTrimmed = then.trim();
            if (thenTrimmed.equalsIgnoreCase("DO")) {
                // Collect statements until ENDDO
                List<ClStatement> block = new ArrayList<>();
                int j = index + 1;
                int depth = 1;
                while (j < stmts.size()) {
                    ClStatement s = stmts.get(j);
                    if (s.command.equalsIgnoreCase("DO") ||
                            (s.command.equalsIgnoreCase("IF") && "DO".equalsIgnoreCase(s.params.get("THEN")))) {
                        depth++;
                    }
                    if (s.command.equalsIgnoreCase("ENDDO")) {
                        depth--;
                        if (depth == 0) break;
                    }
                    block.add(s);
                    j++;
                }
                generateBody(sb, block, program, indentLevel + 1);
                sb.append(indent).append("}").append(NL);

                // Check for ELSE after ENDDO
                if (j + 1 < stmts.size() && stmts.get(j + 1).command.equalsIgnoreCase("ELSE")) {
                    ClStatement elseStmt = stmts.get(j + 1);
                    String elseAction = elseStmt.rawText.trim();
                    if (!elseAction.isEmpty()) {
                        sb.append(indent).append("else {").append(NL);
                        // Parse the else action as a single statement
                        ClStatement elseCmd = new ClStatement();
                        Matcher cmdMatch = Pattern.compile("^([A-Za-z][A-Za-z0-9]*)\\b(.*)$").matcher(elseAction);
                        if (cmdMatch.matches()) {
                            elseCmd.command = cmdMatch.group(1).toUpperCase();
                            elseCmd.rawText = cmdMatch.group(2).trim();
                            parseParameters(elseCmd);
                        } else {
                            elseCmd.command = elseAction;
                            elseCmd.rawText = "";
                        }
                        List<ClStatement> elseBlock = new ArrayList<>();
                        elseBlock.add(elseCmd);
                        generateBody(sb, elseBlock, program, indentLevel + 1);
                        sb.append(indent).append("}").append(NL);
                    }
                    return j + 1; // skip past ELSE
                }
                return j; // skip past ENDDO
            } else {
                // Single statement THEN
                generateInlineCommand(sb, thenTrimmed, "    ".repeat(indentLevel + 1));
                sb.append(indent).append("}").append(NL);
            }
        } else {
            sb.append(indent).append("}").append(NL);
        }
        return index;
    }

    private void generateElseStandalone(StringBuilder sb, ClStatement stmt, String indent) {
        // Standalone ELSE (not caught by IF handler)
        String action = stmt.rawText.trim();
        if (!action.isEmpty()) {
            sb.append(indent).append("else {").append(NL);
            generateInlineCommand(sb, action, indent + "    ");
            sb.append(indent).append("}").append(NL);
        }
    }

    private void generateInlineCommand(StringBuilder sb, String cmdText, String indent) {
        Matcher m = Pattern.compile("^([A-Za-z][A-Za-z0-9]*)\\b(.*)$").matcher(cmdText.trim());
        if (m.matches()) {
            ClStatement inline = new ClStatement();
            inline.command = m.group(1).toUpperCase();
            inline.rawText = m.group(2).trim();
            parseParameters(inline);

            List<ClStatement> list = new ArrayList<>();
            list.add(inline);
            generateBody(sb, list, null, indent.length() / 4);
        }
    }

    private void generateGoto(StringBuilder sb, ClStatement stmt, String indent) {
        String label = stmt.params.get("CMDLBL");
        if (label == null) {
            // GOTO FIM format
            label = stmt.rawText.trim();
            if (label.startsWith("CMDLBL(")) {
                label = label.substring(7, label.length() - 1);
            }
        }
        sb.append(indent).append("// GOTO ").append(label != null ? label : "").append(NL);
    }

    private void generateChkobj(StringBuilder sb, ClStatement stmt, String indent) {
        String objType = stmt.params.getOrDefault("OBJTYPE", "");
        sb.append(indent).append("// CHKOBJ OBJ(")
                .append(escapeJava(stmt.params.getOrDefault("OBJ", "")))
                .append(") OBJTYPE(").append(escapeJava(objType)).append(")").append(NL);
    }

    private void generateDelete(StringBuilder sb, ClStatement stmt, String indent) {
        sb.append(indent).append("// ").append(stmt.command).append(": ").append(escapeJava(stmt.rawText)).append(NL);
    }

    private void generateCrtdupobj(StringBuilder sb, ClStatement stmt, String indent) {
        sb.append(indent).append("// CRTDUPOBJ: ").append(escapeJava(stmt.rawText)).append(NL);
    }

    private void generateCreate(StringBuilder sb, ClStatement stmt, String indent) {
        sb.append(indent).append("// ").append(stmt.command).append(": ").append(escapeJava(stmt.rawText)).append(NL);
    }

    private void generateChglibl(StringBuilder sb, ClStatement stmt, String indent) {
        sb.append(indent).append("// CHGLIBL: ").append(escapeJava(stmt.rawText)).append(NL);
    }

    private void generateRtvjoba(StringBuilder sb, ClStatement stmt, String indent) {
        // RTVJOBA returns runtime job attributes — populate from JVM/system info.
        // Each KEY(&VAR) pair maps an AS/400 job-attribute key to a CL variable.
        for (Map.Entry<String, String> entry : stmt.params.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue().replace("&", "");
            String fieldName = toFieldName(val);
            String javaExpr = jobAttributeExpression(key);
            sb.append(indent).append("this.").append(fieldName).append(" = ").append(javaExpr).append(";").append(NL);
        }
    }

    private void generateRtvsysval(StringBuilder sb, ClStatement stmt, String indent) {
        String sysval = cleanValue(stmt.params.getOrDefault("SYSVAL", ""));
        String rtnVar = stmt.params.getOrDefault("RTNVAR", "").replace("&", "");
        String fieldName = toFieldName(rtnVar);
        String javaExpr = systemValueExpression(sysval);
        sb.append(indent).append("this.").append(fieldName).append(" = ").append(javaExpr).append(";").append(NL);
    }

    private void generateClrpfm(StringBuilder sb, ClStatement stmt, String indent) {
        sb.append(indent).append("// CLRPFM FILE(")
                .append(escapeJava(stmt.params.getOrDefault("FILE", ""))).append(")").append(NL);
    }

    private void generateRcvf(StringBuilder sb, ClStatement stmt, String indent) {
        sb.append(indent).append("// RCVF (no-op in Java port)").append(NL);
    }

    private void generateRunSql(StringBuilder sb, ClStatement stmt, String indent) {
        sb.append(indent).append("// RUNSQLSTM SRCFILE(")
                .append(escapeJava(stmt.params.getOrDefault("SRCFILE", "")))
                .append(") SRCMBR(")
                .append(escapeJava(stmt.params.getOrDefault("SRCMBR", "")))
                .append(")").append(NL);
    }

    private void generateRtvdtaara(StringBuilder sb, ClStatement stmt, String indent) {
        String dtaara = cleanValue(stmt.params.getOrDefault("DTAARA", ""));
        String rtnVar = stmt.params.getOrDefault("RTNVAR", "").replace("&", "");
        String fieldName = toFieldName(rtnVar);
        // No data-area runtime in Java port; preserve current value.
        sb.append(indent).append("// RTVDTAARA DTAARA(").append(escapeJava(dtaara))
                .append(") RTNVAR(&").append(rtnVar).append(") -- field '")
                .append(fieldName).append("' left unchanged").append(NL);
    }

    private void generateAddEnvVar(StringBuilder sb, ClStatement stmt, String indent) {
        String envvar = cleanValue(stmt.params.getOrDefault("ENVVAR", ""));
        String value = cleanValue(stmt.params.getOrDefault("VALUE", ""));
        sb.append(indent).append("System.setProperty(\"").append(escapeJava(envvar))
                .append("\", \"").append(escapeJava(value)).append("\");").append(NL);
    }

    private void generateDspfd(StringBuilder sb, ClStatement stmt, String indent) {
        sb.append(indent).append("// DSPFD FILE(")
                .append(escapeJava(stmt.params.getOrDefault("FILE", "")))
                .append(") OUTFILE(")
                .append(escapeJava(stmt.params.getOrDefault("OUTFILE", "")))
                .append(")").append(NL);
    }

    private void generateQsh(StringBuilder sb, ClStatement stmt, String indent) {
        String cmd = stmt.params.getOrDefault("CMD", "");
        sb.append(indent).append("// QSH CMD(").append(escapeJava(cleanValue(cmd))).append(")").append(NL);
    }

    private void generateChgdtaara(StringBuilder sb, ClStatement stmt, String indent) {
        String dtaara = cleanValue(stmt.params.getOrDefault("DTAARA", ""));
        sb.append(indent).append("// CHGDTAARA DTAARA(").append(escapeJava(dtaara)).append(")").append(NL);
    }

    /**
     * Maps RTVJOBA attribute keys to a Java expression returning a sensible value.
     * Mirrors the AS/400 runtime so callers (COBOL) get non-null IDs back.
     */
    private String jobAttributeExpression(String key) {
        if (key == null) return "\"\"";
        switch (key.toUpperCase()) {
            case "USER":
                // User profile (10 chars). AS/400 returns uppercased name.
                return "padRight(System.getProperty(\"user.name\", \"JAVAUSER\").toUpperCase(), 10)";
            case "JOB":
                return "padRight(System.getProperty(\"user.name\", \"JAVAUSER\").toUpperCase(), 10)";
            case "NBR":
                // Job number (6 digits). Use PID modulo 1_000_000 for a stable value.
                return "String.format(\"%06d\", (int)(ProcessHandle.current().pid() % 1000000L))";
            case "JOBQ":
                return "padRight(\"QBATCH\", 10)";
            case "TYPE":
                return "\"B\"";
            case "SBSD":
                return "padRight(\"QBATCH\", 10)";
            case "DATE":
                return "new java.text.SimpleDateFormat(\"MMddyy\").format(new java.util.Date())";
            case "CURLIB":
                return "padRight(\"QGPL\", 10)";
            default:
                // Unknown attribute: return blanks.
                return "\"\"";
        }
    }

    /**
     * Maps RTVSYSVAL system values to a Java expression returning real runtime data.
     */
    private String systemValueExpression(String sysval) {
        if (sysval == null) return "\"\"";
        switch (sysval.toUpperCase()) {
            case "QDATE":
                // 6-char date YYMMDD (job date format on AS/400 default)
                return "new java.text.SimpleDateFormat(\"yyMMdd\").format(new java.util.Date())";
            case "QDATETIME":
                return "new java.text.SimpleDateFormat(\"yyyyMMddHHmmssSSS\").format(new java.util.Date())";
            case "QTIME":
                // 6-char time HHMMSS or 8-char HHMMSSCC depending on consumer
                return "new java.text.SimpleDateFormat(\"HHmmssSS\").format(new java.util.Date())";
            case "QCENTURY":
                // '0' for 1900s, '1' for 2000s
                return "(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) >= 2000 ? \"1\" : \"0\")";
            case "QYEAR":
                return "String.valueOf(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) % 100)";
            case "QMONTH":
                return "String.format(\"%02d\", java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1)";
            case "QDAY":
                return "String.format(\"%02d\", java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH))";
            case "QDATSEP":
                return "\"/\"";
            case "QTIMSEP":
                return "\":\"";
            case "QDECFMT":
                return "\".\"";
            case "QDATFMT":
                return "\"YMD\"";
            default:
                return "\"\"";
        }
    }

    // ── Condition Converter ─────────────────────────────────────────────

    private String convertCondition(String cond) {
        if (cond.isEmpty()) return "true";

        String result = cond;

        // Replace CL operators
        result = result.replaceAll("\\*EQ\\b", "==");
        result = result.replaceAll("\\*NE\\b", "!=");
        result = result.replaceAll("\\*GT\\b", ">");
        result = result.replaceAll("\\*LT\\b", "<");
        result = result.replaceAll("\\*GE\\b", ">=");
        result = result.replaceAll("\\*LE\\b", "<=");
        result = result.replaceAll("\\*AND\\b", "&&");
        result = result.replaceAll("\\*OR\\b", "||");
        result = result.replaceAll("\\*NOT\\b", "!");

        // Replace &VAR with fieldName
        Matcher varMatch = Pattern.compile("&([A-Za-z_][A-Za-z0-9_]*)").matcher(result);
        StringBuilder varResult = new StringBuilder();
        while (varMatch.find()) {
            varMatch.appendReplacement(varResult, Matcher.quoteReplacement(toFieldName(varMatch.group(1))));
        }
        varMatch.appendTail(varResult);
        result = varResult.toString();

        // Replace 'string' with "string"
        result = result.replaceAll("'([^']*)'", "\"$1\"");

        // Replace %SST(&var pos len) with var.substring(pos-1, pos-1+len)
        Matcher sstMatch = Pattern.compile("%SST\\(([^)]+)\\)").matcher(result);
        while (sstMatch.find()) {
            String[] parts = sstMatch.group(1).trim().split("\\s+");
            if (parts.length >= 3) {
                String var = parts[0].replace("&", "");
                try {
                    int pos = Integer.parseInt(parts[1]);
                    int len = Integer.parseInt(parts[2]);
                    String replacement = toFieldName(var) + ".substring(" + (pos - 1) + ", " + (pos - 1 + len) + ")";
                    result = result.replace(sstMatch.group(0), replacement);
                } catch (NumberFormatException e) {
                    // leave as-is
                }
            }
        }

        // String comparisons: x == "y" → x.equals("y")
        result = result.replaceAll("(\\w+)\\s*==\\s*(\"[^\"]*\")", "$1.equals($2)");

        return result;
    }

    // ── Utility Methods ─────────────────────────────────────────────────

    private String toJavaExpr(String clExpr) {
        if (clExpr == null || clExpr.isEmpty()) return "\"\"";

        String trimmed = clExpr.trim();

        // %SST(&var pos len) → var.substring(pos-1, pos-1+len)
        Matcher sstMatch = Pattern.compile("%SST\\(([^)]+)\\)").matcher(trimmed);
        if (sstMatch.find()) {
            String[] parts = sstMatch.group(1).trim().split("\\s+");
            if (parts.length >= 3) {
                String var = parts[0].replace("&", "");
                try {
                    int pos = Integer.parseInt(parts[1]);
                    int len = Integer.parseInt(parts[2]);
                    return toFieldName(var) + ".substring(" + (pos - 1) + ", " + (pos - 1 + len) + ")";
                } catch (NumberFormatException e) {
                    // fall through
                }
            }
        }

        // *CAT / *TCAT / *BCAT concatenation (check before quoted string since expressions like 'X' *CAT &Y start/end with quotes)
        if (trimmed.contains("*CAT") || trimmed.contains("*TCAT") || trimmed.contains("*BCAT")) {
            return convertConcatenation(trimmed);
        }

        // Quoted string
        if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() >= 2) {
            return "\"" + escapeJava(trimmed.substring(1, trimmed.length() - 1)) + "\"";
        }

        // Variable reference
        if (trimmed.startsWith("&")) {
            return toFieldName(trimmed.replace("&", ""));
        }

        // Numeric
        if (trimmed.matches("-?\\d+(\\.\\d+)?")) {
            return trimmed;
        }

        // Special values
        if (trimmed.startsWith("*")) {
            return "\"" + trimmed + "\"";
        }

        return "\"" + escapeJava(trimmed) + "\"";
    }

    private String convertConcatenation(String expr) {
        // Split on *CAT, *TCAT, *BCAT
        String[] parts = expr.split("\\s*\\*(T?B?CAT)\\s*");
        StringBuilder sb = new StringBuilder();
        String[] operators = new String[parts.length - 1];
        Matcher m = Pattern.compile("\\*(T?B?CAT)").matcher(expr);
        int idx = 0;
        while (m.find() && idx < operators.length) {
            operators[idx++] = m.group(1);
        }

        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                // BCAT adds space, TCAT trims trailing, CAT is plain concat
                if (idx > i - 1 && "BCAT".equals(operators[i - 1])) {
                    sb.append(" + \" \" + ");
                } else {
                    sb.append(" + ");
                }
            }
            sb.append(toJavaExpr(parts[i].trim()));
        }
        return sb.toString();
    }

    private String getJavaTypeForVar(ClProgram program, String name) {
        ClVariable v = program.variables.get(name);
        if (v != null) {
            return mapClTypeToJava(v.type);
        }
        return "String";
    }

    static String mapClTypeToJava(String clType) {
        if (clType == null) return "String";
        switch (clType.toUpperCase()) {
            case "*CHAR":
                return "String";
            case "*DEC":
                return "java.math.BigDecimal";
            case "*INT":
            case "*UINT":
                return "int";
            case "*LGL":
                return "boolean";
            default:
                return "String";
        }
    }

    private String defaultValue(String javaType) {
        switch (javaType) {
            case "int": return "0";
            case "boolean": return "false";
            case "java.math.BigDecimal": return "java.math.BigDecimal.ZERO";
            default: return "\"\"";
        }
    }

    private String formatJavaValue(String value, String javaType) {
        if (value == null) return defaultValue(javaType);
        String v = value.trim();
        if (v.startsWith("'") && v.endsWith("'")) {
            return "\"" + escapeJava(v.substring(1, v.length() - 1)) + "\"";
        }
        return toJavaExpr(v);
    }

    private String cleanValue(String val) {
        if (val == null) return "";
        String result = val.trim();
        return result.replace("&", "").replace("'", "");
    }

    /**
     * Converts a CL value that may contain &amp;VAR references into a Java string expression.
     * Returns a complete Java expression (may be string concatenation if variables are present).
     */
    private String toStringArg(String val) {
        if (val == null || val.trim().isEmpty()) return "\"\"";
        String trimmed = val.trim();

        // If it contains &VAR references, build concatenation
        if (trimmed.contains("&")) {
            StringBuilder sb = new StringBuilder();
            Matcher m = Pattern.compile("&([A-Za-z_][A-Za-z0-9_]*)").matcher(trimmed);
            int last = 0;
            boolean first = true;
            while (m.find()) {
                String before = trimmed.substring(last, m.start());
                if (!before.isEmpty()) {
                    if (!first) sb.append(" + ");
                    sb.append("\"").append(escapeJava(before)).append("\"");
                    first = false;
                }
                if (!first) sb.append(" + ");
                sb.append(toFieldName(m.group(1)));
                first = false;
                last = m.end();
            }
            String after = trimmed.substring(last);
            if (!after.isEmpty()) {
                if (!first) sb.append(" + ");
                sb.append("\"").append(escapeJava(after)).append("\"");
            }
            return sb.toString();
        }

        // Plain string
        return "\"" + escapeJava(trimmed.replace("'", "")) + "\"";
    }

    private String cleanQuotedString(String val) {
        if (val == null) return "";
        String result = val.trim();
        if (result.startsWith("'") && result.endsWith("'")) {
            result = result.substring(1, result.length() - 1);
        }
        return result;
    }

    private String extractFirstArg(String text) {
        Matcher m = Pattern.compile("\\(([^)]+)\\)").matcher(text);
        if (m.find()) return m.group(1);
        return text.split("\\s+")[0];
    }

    private String[] splitParams(String parm) {
        List<String> params = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : parm.toCharArray()) {
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (c == ' ' && depth == 0) {
                if (current.length() > 0) {
                    params.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) params.add(current.toString());
        return params.toArray(new String[0]);
    }

    static String toClassName(String fileName) {
        StringBuilder sb = new StringBuilder();
        boolean capitalize = true;
        for (char c : fileName.toCharArray()) {
            if (c == '_' || c == '-') {
                capitalize = true;
            } else if (capitalize) {
                sb.append(Character.toUpperCase(c));
                capitalize = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String toFieldName(String name) {
        if (name == null || name.isEmpty()) return "unknown";
        String clean = name.replace("&", "").trim();
        StringBuilder sb = new StringBuilder();
        boolean capitalize = false;
        boolean first = true;
        for (char c : clean.toCharArray()) {
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
        // Avoid Java reserved words
        if (isReserved(result)) return result + "Var";
        return result;
    }

    private static boolean isReserved(String word) {
        switch (word) {
            case "class": case "int": case "long": case "short": case "byte":
            case "float": case "double": case "boolean": case "char": case "void":
            case "if": case "else": case "for": case "while": case "do":
            case "switch": case "case": case "break": case "continue": case "return":
            case "new": case "this": case "super": case "null": case "true": case "false":
            case "try": case "catch": case "finally": case "throw": case "throws":
            case "import": case "package": case "public": case "private": case "protected":
            case "static": case "final": case "abstract": case "native":
                return true;
            default:
                return false;
        }
    }

    private static String escapeJava(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ── Model Classes ───────────────────────────────────────────────────

    static class ClProgram {
        String name;
        List<String> parameters = new ArrayList<>();
        Map<String, ClVariable> variables = new LinkedHashMap<>();
        List<String> declaredFiles = new ArrayList<>();
        List<ClStatement> body = new ArrayList<>();
    }

    static class ClVariable {
        String name;
        String type;
        int length;
        String initialValue;
    }

    static class ClStatement {
        String label;
        String command = "";
        String rawText = "";
        Map<String, String> params = new LinkedHashMap<>();
    }
}
