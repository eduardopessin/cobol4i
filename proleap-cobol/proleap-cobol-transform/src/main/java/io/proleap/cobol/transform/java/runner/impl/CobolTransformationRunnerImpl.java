package io.proleap.cobol.transform.java.runner.impl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.antlr.v4.runtime.ParserRuleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.proleap.cobol.asg.metamodel.CompilationUnit;
import io.proleap.cobol.asg.metamodel.Program;
import io.proleap.cobol.asg.params.CobolParserParams;
import io.proleap.cobol.asg.runner.impl.CobolParserRunnerImpl;
import io.proleap.cobol.transform.java.identifier.method.JavaMethodIdentifierService;
import io.proleap.cobol.transform.java.printer.TypedPrinter;
import io.proleap.cobol.transform.java.printer.impl.TypedPrinterImpl;
import io.proleap.cobol.transform.java.runner.CobolTransformationRunner;
import io.proleap.cobol.transform.java.type.JavaTypeService;
import io.proleap.cobol.transform.printer.Printer;
import io.proleap.cobol.transform.printer.impl.PrinterImpl;
import io.proleap.cobol.transform.rule.CobolTransformRuleMatcher;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class CobolTransformationRunnerImpl implements CobolTransformationRunner {

	private final static Logger LOG = LoggerFactory.getLogger(CobolTransformationRunnerImpl.class);

	@Inject
	protected JavaTypeService javaTypeService;

	@Inject
	protected JavaMethodIdentifierService javaMethodIdentifierService;

	@Inject
	protected CobolTransformRuleMatcher ruleMatcher;

	/**
	 * Holds XML PARSE block metadata extracted from the original COBOL source
	 * before preprocessing comments out the blocks. The post-processor uses this
	 * to inject xmlParse() calls into the generated Java.
	 */
	private static class XmlParseInfo {
		final String containingParagraph; // e.g. "PARSE-XML"
		final String identifier;          // e.g. "R-FR0400NAV"
		final String handler;             // e.g. "XMLEVENT-HANDLER"

		XmlParseInfo(final String containingParagraph, final String identifier, final String handler) {
			this.containingParagraph = containingParagraph;
			this.identifier = identifier;
			this.handler = handler;
		}
	}

	/** XML PARSE blocks found in the current COBOL source — populated by transformFile, consumed by post-processor. */
	private final List<XmlParseInfo> xmlParseInfoList = new ArrayList<>();

	protected List<File> transform(final Program program, final String packageName) throws IOException {
		final List<File> result = new ArrayList<>();

		for (final CompilationUnit compilationUnit : program.getCompilationUnits()) {
			final String compilationUnitName = compilationUnit.getName();

			LOG.info("Transforming compilation unit {}.", compilationUnitName);

			// reset method name deduplication state for each compilation unit
			javaMethodIdentifierService.reset();

			// open output file
			final File outputFile = Files.createTempFile(javaTypeService.mapToType(compilationUnitName), "java")
					.toFile();

			// rule context
			final RuleContext ruleContext = new RuleContext();

			// set output file on printer
			final Printer printer = new PrinterImpl(outputFile);

			// typed printer
			final TypedPrinter typedPrinter = new TypedPrinterImpl(ruleContext);

			// init rule context
			ruleContext.setProgram(program);
			ruleContext.setPackageName(packageName);
			ruleContext.setRuleMatcher(ruleMatcher);
			ruleContext.setPrinter(printer);
			ruleContext.setTypedPrinter(typedPrinter);

			// transform
			final ParserRuleContext ctx = compilationUnit.getCtx();
			final Object semanticGraphElement = program.getASGElementRegistry().getASGElement(ctx);
			ruleMatcher.apply(ctx, semanticGraphElement, ruleContext);

			// close printer
			printer.flush();
			printer.close();

			// Post-process: extract embedded paragraphs as separate methods
			postProcessEmbeddedParagraphs(outputFile);

			result.add(outputFile);
		}

		return result;
	}

	/**
	 * Post-processes the generated Java file to extract embedded paragraphs.
	 * The COBOL parser sometimes merges consecutive paragraphs into a single method
	 * (e.g., PROCESS-SUBF content gets embedded into LOAD-SUBFILE-EXIT).
	 * This method detects method calls that have no corresponding method declaration
	 * and attempts to extract the embedded content as a separate method.
	 */
	protected void postProcessEmbeddedParagraphs(final File javaFile) {
		try {
				String content = new String(Files.readAllBytes(javaFile.toPath()));

			// Find all method calls: methodName();
			final java.util.Set<String> calledMethods = new java.util.LinkedHashSet<>();
			final java.util.regex.Pattern callPattern = java.util.regex.Pattern.compile("\\b([a-z][a-z0-9_]*)\\(\\);");
			final java.util.regex.Matcher callMatcher = callPattern.matcher(content);
			while (callMatcher.find()) {
				calledMethods.add(callMatcher.group(1));
			}

			// Find all method declarations: public void methodName() throws Exception {
			final java.util.Set<String> declaredMethods = new java.util.LinkedHashSet<>();
			final java.util.regex.Pattern declPattern = java.util.regex.Pattern.compile("public void ([a-z][a-z0-9_]*)\\(\\) throws Exception");
			final java.util.regex.Matcher declMatcher = declPattern.matcher(content);
			while (declMatcher.find()) {
				declaredMethods.add(declMatcher.group(1));
			}

			// Find missing methods
			final java.util.Set<String> missingMethods = new java.util.LinkedHashSet<>(calledMethods);
			missingMethods.removeAll(declaredMethods);
			// Remove known runtime/framework/Java methods that should not be stubbed
			missingMethods.removeIf(m -> m.startsWith("get") || m.startsWith("set") || m.startsWith("is")
					|| m.equals("main") || m.equals("toString") || m.equals("equals") || m.equals("hashCode")
					|| m.equals("size") || m.equals("add") || m.equals("remove") || m.equals("clear")
					|| m.equals("next") || m.equals("close") || m.equals("trim") || m.equals("length")
					|| m.equals("substring") || m.equals("replace") || m.equals("contains") || m.equals("valueOf")
					|| m.equals("intValue") || m.equals("compareTo") || m.equals("abs") || m.equals("negate")
					|| m.equals("multiply") || m.equals("divide") || m.equals("subtract")
					|| m.equals("format") || m.equals("println") || m.equals("print")
					|| m.equals("write") || m.equals("read") || m.equals("open") || m.equals("flush")
					|| m.equals("execute") || m.equals("executeQuery") || m.equals("executeUpdate")
					|| m.equals("prepareStatement") || m.equals("absolute") || m.equals("wasNull")
					|| m.equals("now") || m.equals("of") || m.equals("parse") || m.equals("run")
					|| m.equals("start") || m.equals("stop") || m.equals("wait") || m.equals("notify")
					|| m.equals("append") || m.equals("insert") || m.equals("delete") || m.equals("update")
					|| m.equals("split") || m.equals("join") || m.equals("sort") || m.equals("fill")
					|| m.length() <= 2);

			// Fix getter used as assignment target: getXxx() = value → setXxx(value)
			// This happens when REDEFINES accessors are used in SQL FETCH INTO or MOVE targets.
			{
				java.util.regex.Pattern getAssign = java.util.regex.Pattern.compile(
					"(get[A-Za-z0-9_]+)\\(\\)\\s*=\\s*([^;]+);");
				java.util.regex.Matcher gam = getAssign.matcher(content);
				StringBuffer gasb = new StringBuffer();
				boolean gaChanged = false;
				while (gam.find()) {
					String getterName = gam.group(1);
					String value = gam.group(2);
					String setterName = getterName.replaceFirst("get", "set");
					gam.appendReplacement(gasb,
						java.util.regex.Matcher.quoteReplacement(setterName + "(" + value + ");"));
					gaChanged = true;
				}
				if (gaChanged) {
					gam.appendTail(gasb);
					content = gasb.toString();
				}
			}

			// Fix unreachable statements: when "return;" is followed by assignment statements
			// (not a closing brace), comment out the return to avoid compilation errors.
			// This happens when GOBACK/STOP RUN is in a fall-through paragraph that has
			// embedded code from subsequent paragraphs.
			content = content.replaceAll("        return;\\n        ([a-z_])", "        // return; // commented: unreachable code follows\n        $1");

			// Fix unreachable statements after if/else blocks where all branches return.
			// Pattern: "return;\n        }\n        \n        method_call();\n    }" - comment out the method call
			{
				String[] unreachLines = content.split("\n");
				for (int uri = 0; uri < unreachLines.length; uri++) {
					String uline = unreachLines[uri].trim();
					if (uline.equals("return;") && uri + 1 < unreachLines.length) {
						// Check if next non-blank lines are "}" followed by a method call
						int nextIdx = uri + 1;
						// Skip closing braces and blank lines
						while (nextIdx < unreachLines.length && (unreachLines[nextIdx].trim().equals("}") || unreachLines[nextIdx].trim().isEmpty())) {
							nextIdx++;
						}
						// If the next code line is a method call followed by "}", it's unreachable
						if (nextIdx < unreachLines.length && nextIdx + 1 < unreachLines.length) {
							String nextCode = unreachLines[nextIdx].trim();
							String afterCode = unreachLines[nextIdx + 1].trim();
							if (nextCode.matches("[a-z_][a-z0-9_]*\\(\\);") && afterCode.equals("}")) {
								unreachLines[nextIdx] = unreachLines[nextIdx].replace(nextCode, "// " + nextCode + " // commented: unreachable after return");
							}
						}
					}
				}
				content = String.join("\n", unreachLines);
			}

			// Fix PIC 1 fields generated as boolean but used as numeric counters.
			// When a boolean field is used with .add(), .compareTo(), etc., convert to BigDecimal.
				{
				// Find boolean fields that are used with .add( or .compareTo(
				// Match both dotted paths (a.b.add() and simple names (b.add()
				java.util.regex.Pattern boolUsedNumeric = java.util.regex.Pattern.compile(
					"\\b((?:[a-z][a-z0-9_]*\\.)*[a-z][a-z0-9_]*)\\.(?:add|compareTo|subtract|multiply|divide)\\(");
				java.util.regex.Matcher bum = boolUsedNumeric.matcher(content);
				java.util.Set<String> boolToFix = new java.util.LinkedHashSet<>();
				while (bum.find()) {
					String varPath = bum.group(1);
					String leafName = varPath.substring(varPath.lastIndexOf('.') + 1);
					// Check if this is declared as boolean
					if (content.contains("protected boolean " + leafName + " =")) {
						// Only convert if the field is NOT also declared as BigDecimal/String in another class.
						// When the same leaf name exists as both boolean (PIC 1) and non-boolean (PIC 9),
						// the numeric usage might be from the non-boolean declaration.
						boolean alsoNonBoolean = content.contains("protected BigDecimal " + leafName + " =")
							|| content.contains("protected String " + leafName + " =");
						if (!alsoNonBoolean) {
							boolToFix.add(leafName);
						}
					}
				}
				// boolToFix detected: processed below
				for (String field : boolToFix) {
					// Change declaration from boolean to BigDecimal
					content = content.replace(
						"protected boolean " + field + " = false;",
						"protected BigDecimal " + field + " = BigDecimal.ZERO;");
					content = content.replace(
						"protected boolean " + field + " = true;",
						"protected BigDecimal " + field + " = BigDecimal.ONE;");
					// Fix SET assignments: field = true → field = BigDecimal.ONE (qualified paths)
					content = content.replaceAll(
						"(\\b\\w+\\." + field + ") = true;",
						"$1 = BigDecimal.ONE;");
					content = content.replaceAll(
						"(\\b\\w+\\." + field + ") = false;",
						"$1 = BigDecimal.ZERO;");
					// Fix SET assignments: simple unqualified field = true/false
					content = content.replaceAll(
						"(^|\\s)" + field + " = true;",
						"$1" + field + " = BigDecimal.ONE;");
					content = content.replaceAll(
						"(^|\\s)" + field + " = false;",
						"$1" + field + " = BigDecimal.ZERO;");
					// Fix IF conditions: if (field) → if (field.compareTo(BigDecimal.ONE) == 0)
					// and if (!field) → if (field.compareTo(BigDecimal.ZERO) == 0)
					content = content.replaceAll(
						"if \\(!" + field + "\\)",
						"if (" + field + ".compareTo(BigDecimal.ZERO) == 0)");
					content = content.replaceAll(
						"if \\(" + field + "\\)(?!\\s*\\.)",
						"if (" + field + ".compareTo(BigDecimal.ONE) == 0)");
					// Fix while/until conditions
					content = content.replaceAll(
						"\\|\\| !" + field + " \\|\\|",
						"|| " + field + ".compareTo(BigDecimal.ZERO) == 0 ||");
					content = content.replaceAll(
						"\\|\\| " + field + " \\|\\|",
						"|| " + field + ".compareTo(BigDecimal.ONE) == 0 ||");
					// Fix MOVE to field: replace moveNumericToAlphanumeric with ternary on this field
					// Use regex to handle any path prefix (ambiente.field, w_xxx.field, etc.)
					// and variable argument counts. The emitter at MoveToPhraseRule.java ~764
					// may emit 2, 3, or 4 arguments before the final ternary length argument:
					//   moveNumericToAlphanumeric(src, <target!=null?target.length():0>)                 (2 args)
					//   moveNumericToAlphanumeric(src, srcIntDigits, <target!=null?target.length():0>)   (3 args, commit 44d7b44)
					//   moveNumericToAlphanumeric(src, srcIntDigits, srcDecDigits, <target!=null?...>)   (4 args, V9 case)
					// Additionally, priority-7 post-processing may have already rewritten
					// <target>.length() to <target>.toPlainString().length() when the target
					// was reclassified to BigDecimal. We must tolerate both forms.
					content = content.replaceAll(
						"CobolMove\\.moveNumericToAlphanumeric\\(([^,]+)(?:,\\s*\\d+)?(?:,\\s*\\d+)?,\\s*[\\w.]*" + field + " != null \\? [\\w.]*" + field + "(?:\\.toPlainString\\(\\))?\\.length\\(\\) : 0\\)",
						"CobolMove.moveNumericToNumeric($1, 1, 0)");
					// Fallback: direct string replacement for common patterns (covers both the
					// legacy 2-arg shape and the 3-arg shape with srcIntDigits=1 for bare literals).
					content = content.replace(
						"CobolMove.moveNumericToAlphanumeric(BigDecimal.ZERO, ambiente." + field + " != null ? ambiente." + field + ".length() : 0)",
						"CobolMove.moveNumericToNumeric(BigDecimal.ZERO, 1, 0)");
					content = content.replace(
						"CobolMove.moveNumericToAlphanumeric(BigDecimal.ZERO, 1, ambiente." + field + " != null ? ambiente." + field + ".length() : 0)",
						"CobolMove.moveNumericToNumeric(BigDecimal.ZERO, 1, 0)");
					content = content.replace(
						"CobolMove.moveNumericToAlphanumeric(BigDecimal.ZERO, 1, ambiente." + field + " != null ? ambiente." + field + ".toPlainString().length() : 0)",
						"CobolMove.moveNumericToNumeric(BigDecimal.ZERO, 1, 0)");
				}
			}

			// Fix OCCURS substring access: substring().fieldname is invalid on String.
			// Pattern: xxx.substring((expr), (expr)).fieldname
			// The nested parentheses in substring args make regex hard — use line-by-line scan.
			{
				String[] sfLines = content.split("\n");
				for (int sfi = 0; sfi < sfLines.length; sfi++) {
					String sfl = sfLines[sfi];
					int subIdx = sfl.indexOf(".substring(");
					if (subIdx < 0) continue;
					// Find the matching closing paren for substring(
					int openParen = sfl.indexOf("(", subIdx);
					int depth = 0;
					int closeParen = -1;
					for (int ci = openParen; ci < sfl.length(); ci++) {
						if (sfl.charAt(ci) == '(') depth++;
						else if (sfl.charAt(ci) == ')') {
							depth--;
							if (depth == 0) { closeParen = ci; break; }
						}
					}
					if (closeParen < 0) continue;
					// Check if followed by .fieldname
					if (closeParen + 1 < sfl.length() && sfl.charAt(closeParen + 1) == '.') {
						// Extract the method/field name after the dot
						String afterSubstr = sfl.substring(closeParen + 2);
						int parenOrEnd = afterSubstr.indexOf('(');
						String followingName = parenOrEnd >= 0 ? afterSubstr.substring(0, parenOrEnd) : afterSubstr;
						// Do NOT strip .substring() when followed by a standard String method —
						// those are legitimate operations on the substring result (e.g., REDEFINES getters
						// that use codoper.substring(1, 11).trim()).
						if (followingName.equals("trim") || followingName.equals("length")
								|| followingName.equals("substring") || followingName.equals("replace")
								|| followingName.equals("contains") || followingName.equals("charAt")
								|| followingName.equals("equals") || followingName.equals("indexOf")
								|| followingName.equals("isEmpty") || followingName.equals("startsWith")
								|| followingName.equals("endsWith") || followingName.equals("toUpperCase")
								|| followingName.equals("toLowerCase") || followingName.equals("compareTo")) {
							continue;
						}
						// Get base variable name before .substring
						int dotPos = sfl.lastIndexOf('.', subIdx - 1);
						int baseStart = dotPos + 1;
						if (baseStart <= 0) continue;
						// Find previous whitespace or operator
						for (int ci = subIdx - 1; ci >= 0; ci--) {
							char c = sfl.charAt(ci);
							if (c == ' ' || c == '(' || c == '=') { baseStart = ci + 1; break; }
						}
						String baseVar = sfl.substring(baseStart, subIdx);
						// Check if base is a String variable (OCCURS REDEFINES)
						if (content.contains("protected String " + baseVar + " =")) {
							// Remove the .substring(...) part, keep the .fieldname
							String before = sfl.substring(0, baseStart);
							String after = sfl.substring(closeParen + 1);
							sfLines[sfi] = before + baseVar + after;
						}
					}
				}
				content = String.join("\n", sfLines);
			}

			// Fix FUNCTION SUM(array(subscript, ALL)) — transformer generates undefined variable refs.
			// Replace undefined function calls like sum(fieldName(varName)) with sum(BigDecimal.ZERO)
			content = content.replaceAll(
				"CobolIntrinsic\\.sum\\([a-z][a-z0-9_]*\\([a-z][a-z0-9_]*\\)\\)",
				"CobolIntrinsic.sum(BigDecimal.ZERO)");

			// Fix Num/Tmp group at wrong level — when 01 Num-DATA is separate from 01 Num group,
			// references to num.num_data become invalid. Replace with the standalone variable.
			content = content.replaceAll("\\bnum\\.num_data\\b", "num_data");
			content = content.replaceAll("\\btmp\\.tmp_data\\b", "tmp_data");

			// Fix moveAlphanumericToNumeric when the post-fix tag indicates wrong conversion.
			// The post-fix: String→BigDecimal comment shows the post-processor applied this.
			// If the source field is actually BigDecimal, wrap with String.valueOf().
			content = content.replaceAll(
				"CobolMove\\.moveAlphanumericToNumeric\\(([^,]+), (\\d+), (\\d+)\\); // post-fix: String→BigDecimal",
				"CobolMove.moveAlphanumericToNumeric(String.valueOf($1), $2, $3); // post-fix: String→BigDecimal (safe)");

			// Fix moveAlphanumericToNumeric with SQLCA numeric fields (sqlcabc, sqlerrml, sqlerrd[n]).
			// These are BigDecimal in generated Java but the move selector treats them as alphanumeric.
			content = content.replaceAll(
				"CobolMove\\.moveAlphanumericToNumeric\\((sqlcabc|sqlerrml|sqlerrd\\[\\d+\\]),",
				"CobolMove.moveNumericToNumeric($1,");
			// Fix moveAlphanumericToAlphanumeric with SQLCA string fields that are output as String.valueOf()
			content = content.replaceAll(
				"CobolMove\\.moveAlphanumericToAlphanumeric\\(String\\.valueOf\\((sqlcaid|sqlerrp)\\),",
				"CobolMove.moveAlphanumericToAlphanumeric($1,");
			// Fix String.valueOf(sqlwarn) group reference — sqlwarn is already a String
			content = content.replaceAll(
				"String\\.valueOf\\(sqlwarn\\)",
				"sqlwarn");

			// Fix String-to-BigDecimal direct assignments.
			// When COBOL MOVEs a display file field (PIC X → String) to a numeric field
			// (PIC S9 → BigDecimal), the ASG may resolve the source type incorrectly
			// (e.g., when the same field name exists in both alphanumeric and numeric records).
			// This post-processor detects BigDecimal fields assigned directly from String fields
			// and wraps them with CobolMove.moveAlphanumericToNumeric().
			content = postProcessStringToBigDecimalAssignments(content);

			// Re-apply: Fix moveAlphanumericToNumeric when the source field is actually BigDecimal.
			// The postProcessStringToBigDecimalAssignments may have added tags after the earlier fix.
			content = content.replaceAll(
				"CobolMove\\.moveAlphanumericToNumeric\\(([^,]+), (\\d+), (\\d+)\\); // post-fix: String\u2192BigDecimal",
				"CobolMove.moveAlphanumericToNumeric(String.valueOf($1), $2, $3); // post-fix: String\u2192BigDecimal (safe)");

			// NOTE: Removed incorrect post-processor that replaced compareAlphanumeric(field, "0")
			// with isZeros(field). The literal "0" is NOT the ZEROS figurative constant.
			// compareAlphanumeric correctly handles space-padding per COBOL rules.
			// The figurative constant ZEROS is handled by COMPARISON_BETWEEN_STRING_AND_ZEROS
			// in the expression classifier, which directly emits isZeros().

			// Fix empty .add() / .subtract() / .multiply() with no arguments
			// Generated from COMPUTE expressions where an operand was dropped
			content = content.replace(".add()", "")
				.replace(".subtract()", "")
				.replace(".multiply()", "");

			// Fix COBOL USAGE clause keywords leaking into Java value initializers.
			// When PIC S9(09) VALUE +0 BINARY is parsed, the parser may include BINARY
			// as part of the value clause context, producing BigDecimal.ZERO"BINARY";
			// Strip any USAGE keyword string appended to a value expression.
			// Guard: only match when immediately preceded by a word char or closing paren
			// (i.e., a Java expression token), not by whitespace/= which would indicate
			// a legitimate string value like = "BINARY".
			content = content.replaceAll(
				"([\\w)])(\"(?:BINARY|COMP|COMP-3|COMP-4|COMP-5|PACKED-DECIMAL|DISPLAY|DISPLAY-1)\")(\\s*;)",
				"$1$3");

			// Fix embedded double quotes in string literals from COBOL VALUE clauses.
			// Strategy: find "protected String xxx = " lines, extract value, escape UNESCAPED internal quotes.
			// Must skip quotes that are already escaped (preceded by \).
			{
				String[] qLines = content.split("\n");
				for (int qi = 0; qi < qLines.length; qi++) {
					String ql = qLines[qi];
					if (!ql.contains("protected String ") || !ql.contains(" = \"")) continue;
					int eqIdx = ql.indexOf(" = \"");
					if (eqIdx < 0) continue;
					int openQuote = eqIdx + 3; // position of opening "
					// Find the comment start
					int commentIdx = ql.indexOf("//", openQuote);
					if (commentIdx < 0) commentIdx = ql.length();
					// Find the closing quote: scan from openQuote+1, skip escaped quotes (\")
					int closeQuote = -1;
					for (int ci = openQuote + 1; ci < commentIdx; ci++) {
						char ch = ql.charAt(ci);
						if (ch == '\\') {
							ci++; // skip escaped character
							continue;
						}
						if (ch == '"') {
							// Check if this is followed by ';' (allowing whitespace) — that's the real closing quote
							int peek = ci + 1;
							while (peek < commentIdx && ql.charAt(peek) == ' ') peek++;
							if (peek >= commentIdx || ql.charAt(peek) == ';' || ql.charAt(peek) == '/') {
								closeQuote = ci;
								break;
							}
							// Otherwise this is an unescaped internal quote — leave it for now
						}
					}
					if (closeQuote < 0 || closeQuote <= openQuote + 1) continue;
					// Extract value between open and close quotes
					String val = ql.substring(openQuote + 1, closeQuote);
					// Check for unescaped quotes (quotes NOT preceded by \)
					boolean hasUnescaped = false;
					for (int vi = 0; vi < val.length(); vi++) {
						if (val.charAt(vi) == '"' && (vi == 0 || val.charAt(vi - 1) != '\\')) {
							hasUnescaped = true;
							break;
						}
					}
					if (hasUnescaped) {
						// Escape only UNESCAPED quotes (those not preceded by \)
						StringBuilder sb = new StringBuilder();
						for (int vi = 0; vi < val.length(); vi++) {
							char ch = val.charAt(vi);
							if (ch == '\\' && vi + 1 < val.length()) {
								sb.append(ch);
								sb.append(val.charAt(vi + 1));
								vi++; // skip the escaped char
							} else if (ch == '"') {
								sb.append("\\\"");
							} else {
								sb.append(ch);
							}
						}
						qLines[qi] = ql.substring(0, openQuote + 1) + sb.toString() + ql.substring(closeQuote);
					}
				}
				content = String.join("\n", qLines);
			}

			// Fix missing HIGH-VALUE/LOW-VALUE argument in compareAlphanumeric.
			// The transformer sometimes generates empty second argument for HIGH-VALUE/LOW-VALUE comparisons.
			// Use a loop to find "compareAlphanumeric(..., )" with balanced parens for the first arg.
			{
				String marker = "compareAlphanumeric(";
				int searchStart = 0;
				while (true) {
					int idx = content.indexOf(marker, searchStart);
					if (idx < 0) break;
					int argStart = idx + marker.length();
					// Find the matching comma after the first argument (respecting nested parens)
					int depth = 0;
					int commaIdx = -1;
					for (int ci = argStart; ci < content.length(); ci++) {
						char ch = content.charAt(ci);
						if (ch == '(') depth++;
						else if (ch == ')') {
							if (depth == 0) break; // closing paren of compareAlphanumeric itself
							depth--;
						} else if (ch == ',' && depth == 0) {
							commaIdx = ci;
							break;
						}
					}
					if (commaIdx > 0) {
						// Check if the second argument is empty (just whitespace before closing paren)
						int afterComma = commaIdx + 1;
						while (afterComma < content.length() && content.charAt(afterComma) == ' ') afterComma++;
						if (afterComma < content.length() && content.charAt(afterComma) == ')') {
							// Empty second argument — fill with CobolConstants.lowValues(1)
							String firstArg = content.substring(argStart, commaIdx).trim();
							String replacement = marker + firstArg + ", CobolConstants.lowValues(1))";
							content = content.substring(0, idx) + replacement + content.substring(afterComma + 1);
							searchStart = idx + replacement.length();
							continue;
						}
					}
					searchStart = idx + marker.length();
				}
			}
			content = content.replaceAll("compareTo\\(\\s*\\)", "compareTo(BigDecimal.ZERO)");

			// Fix INSPECT REPLACING on BigDecimal fields.
			// The transformer generates e.g.:
			//   { String _inspResult = numField.replace(FROM, TO); numField = _inspResult; }
			// but numField is BigDecimal, so .replace() doesn't exist.
			// Fix: convert to numField.toPlainString().replace(...) and assignment to
			//   numField = CobolMove.moveAlphanumericToNumeric(_inspResult, width, scale);
			{
				java.util.regex.Pattern inspPattern = java.util.regex.Pattern.compile(
					"\\{\\s*String\\s+_inspResult\\s*=\\s+([a-zA-Z_][a-zA-Z0-9_.]*)\\.replace\\(");
				java.util.regex.Matcher inspMatcher = inspPattern.matcher(content);
				StringBuffer inspSb = new StringBuffer();
				while (inspMatcher.find()) {
					String varName = inspMatcher.group(1);
					// Check if the variable is declared as BigDecimal
					if (content.contains("BigDecimal " + varName + " =")
							|| content.contains("BigDecimal " + varName + ";")
							|| content.contains("BigDecimal " + varName + "\n")) {
						// Also check dot-qualified names like reg_100.aciwei
						inspMatcher.appendReplacement(inspSb,
							java.util.regex.Matcher.quoteReplacement(
								"{ String _inspResult = " + varName + ".toPlainString().replace("));
					} else if (varName.contains(".")) {
						// For qualified names, check the last component.
						// But only apply the BigDecimal fix if the field is NOT also declared as String
						// (ambiguity: different inner classes may have same field name with different types).
						String lastPart = varName.substring(varName.lastIndexOf('.') + 1);
						boolean isBigDecimal = content.contains("BigDecimal " + lastPart + " =")
								|| content.contains("BigDecimal " + lastPart + ";");
						boolean isString = content.contains("String " + lastPart + " =")
								|| content.contains("String " + lastPart + ";");
						if (isBigDecimal && !isString) {
							inspMatcher.appendReplacement(inspSb,
								java.util.regex.Matcher.quoteReplacement(
									"{ String _inspResult = " + varName + ".toPlainString().replace("));
						}
					}
				}
				inspMatcher.appendTail(inspSb);
				content = inspSb.toString();
			}
			// Fix the assignment back: VAR = _inspResult where VAR is BigDecimal
			// Pattern: "varName = _inspResult;" where varName is BigDecimal
			{
				java.util.regex.Pattern assignPattern = java.util.regex.Pattern.compile(
					"([a-zA-Z_][a-zA-Z0-9_.]*) = _inspResult;\\s*\\}");
				java.util.regex.Matcher assignMatcher = assignPattern.matcher(content);
				StringBuffer assignSb = new StringBuffer();
				while (assignMatcher.find()) {
					String varName = assignMatcher.group(1);
					String lastPart = varName.contains(".") ? varName.substring(varName.lastIndexOf('.') + 1) : varName;
					boolean isBigDecimal = content.contains("BigDecimal " + lastPart + " =")
							|| content.contains("BigDecimal " + lastPart + ";")
							|| content.contains("BigDecimal " + varName + " =")
							|| content.contains("BigDecimal " + varName + ";");
					boolean isString = content.contains("String " + lastPart + " =")
							|| content.contains("String " + lastPart + ";")
							|| content.contains("String " + varName + " =")
							|| content.contains("String " + varName + ";");
					// Only convert to BigDecimal assignment if the field is unambiguously BigDecimal
					if (isBigDecimal && !isString) {
						assignMatcher.appendReplacement(assignSb,
							java.util.regex.Matcher.quoteReplacement(
								varName + " = new BigDecimal(_inspResult.trim()); }"));
					}
				}
				assignMatcher.appendTail(assignSb);
				content = assignSb.toString();
			}

			// Fix unary ! on BigDecimal: when an 88-level condition on a PIC 9/99/999
			// field generates !variableName, replace with variableName.compareTo(BigDecimal.ZERO) == 0
			{
				java.util.regex.Pattern bangPattern = java.util.regex.Pattern.compile(
					"!([a-z][a-z0-9_]*)\\)");
				java.util.regex.Matcher bangMatcher = bangPattern.matcher(content);
				StringBuffer bangSb = new StringBuffer();
				boolean bangChanged = false;
				while (bangMatcher.find()) {
					String varName = bangMatcher.group(1);
					// Only fix if the variable is declared as BigDecimal (not boolean)
					// Skip if there's also a boolean declaration (name collision between inner/outer classes)
					boolean hasBigDecimal = content.contains("BigDecimal " + varName + " =") || content.contains("BigDecimal " + varName + ";");
					boolean hasBoolean = content.contains("boolean " + varName + " =");
					if (hasBigDecimal && !hasBoolean) {
						bangMatcher.appendReplacement(bangSb,
							java.util.regex.Matcher.quoteReplacement(varName + ".compareTo(BigDecimal.ZERO) == 0)"));
						bangChanged = true;
					}
				}
				if (bangChanged) {
					bangMatcher.appendTail(bangSb);
					content = bangSb.toString();
				}
			}

			// Fix DECIMAL-POINT IS COMMA: when a numeric literal like 1,17 is used
			// in a COMPUTE division, the transformer may generate .divide(, ...) with
			// empty first argument. Extract the decimal value from the COBOL comment.
			{
				java.util.regex.Pattern dpPattern = java.util.regex.Pattern.compile(
					"\\.divide\\(,\\s*(\\d+),\\s*java\\.math\\.RoundingMode\\.(\\w+)\\)(.*//.* (\\d+,\\d+))");
				java.util.regex.Matcher dpMatcher = dpPattern.matcher(content);
				StringBuffer dpSb = new StringBuffer();
				boolean dpChanged = false;
				while (dpMatcher.find()) {
					String scale = dpMatcher.group(1);
					String rounding = dpMatcher.group(2);
					String cobolVal = dpMatcher.group(4); // e.g., "1,17"
					String javaVal = cobolVal.replace(",", "."); // → "1.17"
					String replacement = ".divide(new BigDecimal(\"" + javaVal + "\"), " + scale
						+ ", java.math.RoundingMode." + rounding + ")" + dpMatcher.group(3);
					dpMatcher.appendReplacement(dpSb, java.util.regex.Matcher.quoteReplacement(replacement));
					dpChanged = true;
				}
				if (dpChanged) {
					dpMatcher.appendTail(dpSb);
					content = dpSb.toString();
				}
			}

			// Removed: post-processing regex that replaced CobolConstants.spaces() with
			// entityService.initialize() for w_ variables. This was a workaround for
			// group-with-PIC items but incorrectly caught elementary alphanumeric fields
			// (e.g., 01 W-Score PIC X(10)), making INITIALIZE a NO-OP for Strings.
			// InitializeStatementRule now correctly handles both cases via isTrueGroup().

			// Fix compareAlphanumeric with numeric arguments.
			// When a PIC 9 field (BigDecimal) is compared using compareAlphanumeric,
			// wrap the BigDecimal argument with String.valueOf() to fix the type mismatch.
			// Pattern: compareAlphanumeric(numericVar, ...) where numericVar has no dots
			// This handles group-with-PIC numeric fields used in alphanumeric comparisons.
			{
				java.util.regex.Pattern p = java.util.regex.Pattern.compile(
					"CobolComparison\\.compareAlphanumeric\\((\\w+),");
				java.util.regex.Matcher m = p.matcher(content);
				StringBuffer sb = new StringBuffer();
				while (m.find()) {
					String varName = m.group(1);
					// Check if this variable is declared as BigDecimal (not String)
					if (content.contains("BigDecimal " + varName + " =") ||
						content.contains("BigDecimal " + varName + ";")) {
						m.appendReplacement(sb, "CobolComparison.compareAlphanumeric(String.valueOf(" + varName + "),");
					}
				}
				m.appendTail(sb);
				content = sb.toString();
			}
			// Fix moveNumericToAlphanumeric(CobolReference.referenceModification(...), intDigits, size)
			// referenceModification returns String, not BigDecimal.
			// Replace with moveAlphanumericToAlphanumeric(refMod(...), size) — drop the extra intDigits arg.
			// Use line-by-line scan to handle nested parentheses.
			{
				String[] refModLines = content.split("\n");
				for (int rmi = 0; rmi < refModLines.length; rmi++) {
					String rml = refModLines[rmi];
					String marker = "CobolMove.moveNumericToAlphanumeric(CobolReference.referenceModification(";
					int markerIdx = rml.indexOf(marker);
					if (markerIdx < 0) continue;
					// Find the matching closing paren for referenceModification(
					int refModStart = markerIdx + "CobolMove.moveNumericToAlphanumeric(".length();
					int refModOpenParen = rml.indexOf("(", refModStart);
					int depth = 0;
					int refModCloseParen = -1;
					for (int ci = refModOpenParen; ci < rml.length(); ci++) {
						if (rml.charAt(ci) == '(') depth++;
						else if (rml.charAt(ci) == ')') {
							depth--;
							if (depth == 0) { refModCloseParen = ci; break; }
						}
					}
					if (refModCloseParen < 0) continue;
					String refModCall = rml.substring(refModStart, refModCloseParen + 1);
					// After refModCall, expect ", intDigits, size)"
					String afterRefMod = rml.substring(refModCloseParen + 1);
					java.util.regex.Matcher tailMat = java.util.regex.Pattern.compile(
						"^\\s*,\\s*\\d+\\s*,\\s*(\\d+)\\)").matcher(afterRefMod);
					if (tailMat.find()) {
						String size = tailMat.group(1);
						String before = rml.substring(0, markerIdx);
						String after = rml.substring(refModCloseParen + 1 + tailMat.end());
						refModLines[rmi] = before + "CobolMove.moveAlphanumericToAlphanumeric(" + refModCall + ", " + size + ")" + after;
					}
				}
				content = String.join("\n", refModLines);
			}

			// Fix compareAlphanumeric with BigDecimal.valueOf(N) as second argument
			// This happens in abbreviated OR conditions like "ORIGEM = 2 OR 3"
			content = content.replaceAll(
				"compareAlphanumeric\\(([^,]+),\\s*(BigDecimal\\.valueOf\\([^)]+\\))\\)",
				"compareAlphanumeric($1, String.valueOf($2))");

			// Also fix the reverse: compareAlphanumeric(..., numericVar)
			{
				java.util.regex.Pattern p = java.util.regex.Pattern.compile(
					",\\s*(\\w+)\\)\\s*(!=|==|<|>|<=|>=)");
				java.util.regex.Matcher m = p.matcher(content);
				StringBuffer sb = new StringBuffer();
				while (m.find()) {
					String varName = m.group(1);
					String op = m.group(2);
					if ((content.contains("BigDecimal " + varName + " =") ||
						content.contains("BigDecimal " + varName + ";")) &&
						!varName.startsWith("BigDecimal")) {
						m.appendReplacement(sb, ", String.valueOf(" + varName + ")) " + op);
					}
				}
				m.appendTail(sb);
				content = sb.toString();
			}

			// Fix SET condition-name TO TRUE/FALSE misresolution.
			// When an 88-level condition name collides with a database record field,
			// the ASG may resolve SET ERRO TO TRUE as r_vf000700.vf000700.erro = String.valueOf(true).
			// String.valueOf(true) = "true" is never a valid COBOL assignment.
			// Detect and fix by finding the matching boolean program-level field.
			content = postProcessSetConditionMisresolution(content);

			// Fix OCCURS write-access via substring.
			// When COBOL writes to an OCCURS element (e.g., MOVE 29 TO ITEM-DIAS(02)),
			// the transformer may generate fieldPath.substring(start, end) = value which is invalid Java.
			// Convert to overlayString() call.
			{
				java.util.regex.Pattern substrWritePattern = java.util.regex.Pattern.compile(
					"((?:[a-z][a-zA-Z0-9_]*\\.)*[a-z][a-zA-Z0-9_]*)\\.substring\\((\\d+),\\s*(\\d+)\\)\\s*=\\s*CobolMove\\.moveNumericToNumeric\\(([^;]+)\\);");
				java.util.regex.Matcher substrWriteMatcher = substrWritePattern.matcher(content);
				StringBuffer sbOccurs = new StringBuffer();
				boolean occursChanged = false;
				while (substrWriteMatcher.find()) {
					String fieldPath = substrWriteMatcher.group(1);
					int start = Integer.parseInt(substrWriteMatcher.group(2));
					int end = Integer.parseInt(substrWriteMatcher.group(3));
					String valueExpr = substrWriteMatcher.group(4);
					int size = end - start;
					String replacement = fieldPath + " = CobolMove.overlayString(" + fieldPath
						+ ", CobolMove.moveNumericToAlphanumeric(CobolMove.moveNumericToNumeric("
						+ valueExpr + "), " + size + ", " + size + "), " + start + ", " + end + ");";
					substrWriteMatcher.appendReplacement(sbOccurs,
						java.util.regex.Matcher.quoteReplacement(replacement));
					occursChanged = true;
				}
				if (occursChanged) {
					substrWriteMatcher.appendTail(sbOccurs);
					content = sbOccurs.toString();
				}
			}

			// Fix abbreviated OR with OCCURS subscript split.
			// When COBOL has "OR > ITEM-DIAS(WK-MES)", the parser may split this into
			// two conditions: "OR > ITEM-DIAS" and "AND < WK-MES". The post-processor
			// detects this pattern and merges them using the indexed getter.
			// Pattern: .compareTo(GROUP.getOCCURS()) OP 0) && (SUBJECT.compareTo((SUBSCRIPT())) OP2 0)
			// Detect by checking if getOCCURS(int) method exists in the generated code.
			{
				// Pattern: SUBJECT.compareTo(GROUP.getOCCURS()) OP 0) && (SUBJECT.compareTo((SUBSCRIPT)) OP2 0)
				// SUBJECT can be a method call like getXxx() or a field path
				java.util.regex.Pattern occursPattern = java.util.regex.Pattern.compile(
					"((?:[a-zA-Z_][a-zA-Z0-9_.]*\\(\\)|[a-zA-Z_][a-zA-Z0-9_.]*)\\.compareTo\\()([a-zA-Z_][a-zA-Z0-9_.]*\\.get[A-Za-z0-9_]+)\\(\\)\\)\\s*(>|<|>=|<=|==|!=)\\s*0\\)\\s*&&\\s*\\(\\1\\(([a-zA-Z_][a-zA-Z0-9_.]*(?:\\(\\))?)\\)\\)\\s*(>|<|>=|<=|==|!=)\\s*0\\)");
				java.util.regex.Matcher occursM = occursPattern.matcher(content);
				StringBuffer sbOcc = new StringBuffer();
				boolean occChanged = false;
				while (occursM.find()) {
					String subjectCompareTo = occursM.group(1);
					String occursGetter = occursM.group(2);
					String op1 = occursM.group(3);
					String subscriptExpr = occursM.group(4);
					// Check if indexed getter exists
					String getterName = occursGetter.substring(occursGetter.lastIndexOf('.') + 1);
					if (content.contains("public BigDecimal " + getterName + "(int ") ||
						content.contains("public String " + getterName + "(int ")) {
						String intExpr = subscriptExpr.endsWith("()") ? subscriptExpr + ".intValue()" : subscriptExpr + ".intValue()";
						String replacement = subjectCompareTo + occursGetter + "(" + intExpr + ")) " + op1 + " 0)";
						occursM.appendReplacement(sbOcc, java.util.regex.Matcher.quoteReplacement(replacement));
						occChanged = true;
					}
				}
				if (occChanged) {
					occursM.appendTail(sbOcc);
					content = sbOcc.toString();
				}
			}

			// Fix duplicate inner class definitions.
			// When COBOL source has duplicate 01-level declarations (e.g., two 01 R-JRNL210101),
			// the transformer generates duplicate inner classes. Remove subsequent duplicates.
			content = postProcessRemoveDuplicateClasses(content);

			// Fix empty DDS output-format classes (-O suffix).
			// When COPY DDSR-xxx-O generates an empty class but DDSR-xxx-I has fields,
			// copy the field declarations from -I to -O.
			{
				String[] lines = content.split("\n", -1);
				boolean emptyFixed = false;
				for (int li = 0; li < lines.length - 1; li++) {
					String line = lines[li];
					// Detect inner classes with _oType that have NO field declarations (only comments/blanks)
					if (line.contains("public class ") && line.contains("_oType {")) {
						// Find matching closing brace, checking if class body has real fields
						int closeLine = -1;
						boolean hasFields = false;
						int braceDepth = 0;
						for (int k = li; k < Math.min(li + 200, lines.length); k++) {
							for (char c : lines[k].toCharArray()) {
								if (c == '{') braceDepth++;
								else if (c == '}') {
									braceDepth--;
									if (braceDepth == 0) { closeLine = k; break; }
								}
							}
							// Check for actual field declarations (not just comments)
							if (k > li && closeLine < 0) {
								String trimmed = lines[k].trim();
								if (trimmed.startsWith("protected ") && !trimmed.contains("Type ") && !trimmed.contains("= new ")) {
									hasFields = true;
								}
							}
							if (closeLine >= 0) break;
						}
						if (closeLine < 0 || hasFields) continue;
						// Extract class name
						int classStart = line.indexOf("public class ") + "public class ".length();
						int classEnd = line.indexOf(" {", classStart);
						if (classEnd < 0) continue;
						String oClassName = line.substring(classStart, classEnd); // e.g., "Img1_oType"
						if (!oClassName.endsWith("_oType")) continue;
						String iClassName = oClassName.replace("_oType", "_iType");
						// Find the -I class in the lines
						String iClassHeader = "public class " + iClassName + " {";
						int iLine = -1;
						for (int j = 0; j < lines.length; j++) {
							if (lines[j].contains(iClassHeader)) { iLine = j; break; }
						}
						if (iLine < 0) continue;
						// Find -I class body (between { and matching })
						int braceCount = 0;
						int iBodyEnd = -1;
						for (int j = iLine; j < lines.length; j++) {
							for (char c : lines[j].toCharArray()) {
								if (c == '{') braceCount++;
								else if (c == '}') {
									braceCount--;
									if (braceCount == 0) { iBodyEnd = j; break; }
								}
							}
							if (iBodyEnd >= 0) break;
						}
						if (iBodyEnd < 0 || iBodyEnd <= iLine + 1) continue;
						// Replace empty -O class body with -I body
						StringBuilder oBody = new StringBuilder();
						oBody.append(lines[li]).append("\n"); // Keep the class header
						for (int j = iLine + 1; j < iBodyEnd; j++) {
							oBody.append(lines[j]).append("\n");
						}
						oBody.append(lines[closeLine]); // Keep the closing brace
						lines[li] = oBody.toString();
						// Blank out the old lines between li+1 and closeLine
						for (int k = li + 1; k <= closeLine; k++) {
							lines[k] = "";
						}
						emptyFixed = true;
					}
				}
				if (emptyFixed) {
					content = String.join("\n", lines);
					System.err.println("POST-PROCESS: fixed " + emptyFixed + " empty -O DDS classes");
				}
			}

			// Fix duplicate field declarations (e.g., two 77 CHAVE PIC 1 in COBOL source).
			// Only removes duplicates at the TOP-LEVEL class (4-space indent).
			// Inner class fields (8+ space indent) are NEVER deduplicated.
			{
				java.util.Set<String> seenFields = new java.util.HashSet<>();
				String[] lines = content.split("\n");
				StringBuilder sbFields = new StringBuilder();
				java.util.regex.Pattern fieldPattern = java.util.regex.Pattern.compile(
					"^    protected (boolean|BigDecimal|String) (\\w+)\\s*=");
				boolean fieldChanged = false;
				for (String line : lines) {
					java.util.regex.Matcher fm = fieldPattern.matcher(line);
					if (fm.find()) {
						String key = fm.group(1) + " " + fm.group(2);
						if (!seenFields.add(key)) {
							fieldChanged = true;
							continue; // Skip duplicate
						}
					}
					sbFields.append(line).append("\n");
				}
				if (fieldChanged) {
					content = sbFields.toString();
				}
			}

			// Fix duplicate getter/setter methods from REDEFINES with same-named fields.
			// When a REDEFINES group has fields with the same name at different positions
			// (e.g., two WK-FIL01 fields), duplicate methods are generated.
			content = postProcessRemoveDuplicateMethods(content);

			// Final pass: fix empty DDS -O format classes by copying fields from corresponding -I class.
			// At this point, all other post-processors have run, so empty -O classes are truly empty.
			{
				String[] flines = content.split("\n", -1);
				boolean efixed = false;
				for (int fi = 0; fi < flines.length - 1; fi++) {
					if (flines[fi].contains("public class ") && flines[fi].contains("_oType {")
							&& flines[fi + 1].trim().equals("}")) {
						int cs = flines[fi].indexOf("public class ") + "public class ".length();
						int ce = flines[fi].indexOf(" {", cs);
						if (ce < 0) continue;
						String oCls = flines[fi].substring(cs, ce);
						if (!oCls.endsWith("_oType")) continue;
						String iCls = oCls.replace("_oType", "_iType");
						String iHdr = "public class " + iCls + " {";
						int iLn = -1;
						for (int j = 0; j < flines.length; j++) {
							if (flines[j].contains(iHdr)) { iLn = j; break; }
						}
						if (iLn < 0) continue;
						int bc = 0; int iEnd = -1;
						for (int j = iLn; j < flines.length; j++) {
							for (char c : flines[j].toCharArray()) {
								if (c == '{') bc++;
								else if (c == '}') { bc--; if (bc == 0) { iEnd = j; break; } }
							}
							if (iEnd >= 0) break;
						}
						if (iEnd < 0 || iEnd <= iLn + 1) continue;
						StringBuilder ob = new StringBuilder();
						ob.append(flines[fi]).append("\n");
						for (int j = iLn + 1; j < iEnd; j++) ob.append(flines[j]).append("\n");
						ob.append(flines[fi + 1]);
						flines[fi] = ob.toString();
						flines[fi + 1] = "";
						efixed = true;
					}
				}
				if (efixed) {
					content = String.join("\n", flines);
				}
			}

			// Post-processor: convert DDS Object declarations to proper typed inner classes.
			// When COPY DDSR-xxx resolves but produces no fields, the transformer generates
			// "protected Object r_xxx;" instead of a proper typed inner class.
			// This detects those Object declarations, infers fields from code usage, and generates
			// proper inner classes + typed variable declarations.
			content = postProcessObjectToTypedClass(content);

			// Post-processor: fix unresolved copybook qualifier variables (CpyTR/CpyTS/CpyTA pattern).
			// MUST run BEFORE postProcessAddMissingFields so the corrected paths are visible
			// to the missing field detector.
			content = postProcessFixCopybookQualifierVariables(content);

			// Post-processor: add missing field declarations to inner classes.
			// When DDS COPY statements resolve to incomplete stub copybooks, fields referenced
			// in the PROCEDURE DIVISION may not exist in the inner type classes.
			// This post-processor scans for field accesses like "outerVar.innerVar.fieldName"
			// and adds "protected String fieldName = "";" if the field is not declared in the inner class.
			// Post-processor: fix undeclared DDS format variables that are sub-records of declared enclosing records.
			// e.g., when code uses "re_ecran1a_o.field" standalone but only "r_hf4_ecran1a_o" is declared
			// and r_hf4_ecran1a_o IS the R-HF4-ECRAN1A-O record format: replace standalone refs.
			// MUST run BEFORE postProcessAddMissingFields so corrected paths are visible.
			content = postProcessFixUndeclaredFormatVariables(content);

			// Post-processor: fix undeclared copybook/LINKAGE variables.
			// When COBOL COPY includes copybook fields and the transformer doesn't generate
			// declarations for them (e.g., h_vr750501, vr501400_aux, w_tannmvt1), create
			// alias declarations by finding a similarly-named declared variable.
			content = postProcessFixUndeclaredCopybookVariables(content);

			// Post-processor: create declarations for undeclared LINKAGE/SQL INCLUDE variables.
			// When COBOL uses FILE501400-AUX, H-FILE750501, BASE000100, etc. as standalone variables
			// but they're only defined as inner classes of other types, create top-level declarations
			// using the inner class type with enclosing instance.
			// MUST run BEFORE postProcessAddMissingFields so the new variables are visible.
			content = postProcessFixUndeclaredTypedVariables(content);

			// Post-processor: fix CALL BY REFERENCE copy-back that assigns String to group variable.
			// When postProcessFixUndeclaredTypedVariables creates a typed declaration for a variable
			// that the CALL copy-back code treats as String, replace the String assignment with
			// group copy-back (CobolMove.moveStringToGroup).
			content = postProcessFixCallCopyBackForGroupVars(content);

			content = postProcessAddMissingFields(content);

			// Post-processor: add missing DDS indicator variables (indof*_i).
			// In COBOL, "IND OF SFL-I" references the DDS subfile record number indicator.
			// The transformer concatenates this into a single variable name like "indofhmlnlogsfl_i"
			// but never declares it because IND comes from the DDS format, not the COBOL data division.
			// Detect these undeclared variables and add BigDecimal declarations at the class level.
			content = postProcessAddMissingIndOfVariables(content);

			// Post-processor: fix String field used with subfield access (e.g., strout.strout_data).
			// When a DDS record format type declares a field as "protected String strout" but code
			// accesses subfields like ".strout.strout_data", the String needs to be used directly.
			// Replace ".strout.strout_data" with ".strout" (the String IS the data) and remove
			// ".strout_length" assignments since they're redundant for String fields.
			content = postProcessFixStringSubfieldAccess(content);

			// Post-processor: fix MOVE to group-level DDS record that generates an assignment
			// to an undeclared alias with .length() on a group type.
			// Pattern: xxx_o = CobolMove.moveAlphanumericToAlphanumeric(source, xxx_o != null ? r_xxx_o.length() : 0)
			// Fix:     CobolMove.moveStringToGroup(source, r_xxx_o)
			content = postProcessFixGroupMoveToRecord(content);

			// Post-processor: fix SQL FETCH assigning to group fields as if they were Strings.
			// When SQL FETCH INTO assigns to a field that is actually a typed inner class (e.g., STROUTType),
			// the generated code does "group = CobolMove.moveAlphanumericToAlphanumeric(_vc, group.length())"
			// which should be "group.fieldName_data = CobolMove.moveAlphanumericToAlphanumeric(_vc, group.fieldName_data.length())".
			content = postProcessFixSqlFetchGroupFields(content);

			// Post-processor: fix compareAlphanumeric called with BigDecimal argument.
			// Pattern: compareAlphanumeric(EXPR, EXPR) where one argument is a BigDecimal
			// expression (contains .subtract(, .add(, moveAlphanumericToNumeric, etc.).
			// Fix: replace with compareNumeric() call.
			content = postProcessFixCompareAlphanumericWithBigDecimal(content);

			// Post-processor: fix String.compareTo(BigDecimal) -- should wrap the String
			// field in CobolMove.moveAlphanumericToNumeric() first.
			content = postProcessFixStringCompareToBigDecimal(content);

			// Post-processor: fix BigDecimal assigned to String field and vice versa.
			content = postProcessFixTypeMismatchAssignments(content);

			// Post-processor: fix `new BigDecimal("NON_NUMERIC")` — when the COBOL source
			// compares an alphanumeric field to a string literal like "Y", "N", "TOPDACT", etc.,
			// the transformer mistakenly generates `new BigDecimal("Y")` which would throw
			// NumberFormatException at runtime. Fix: convert to string comparison.
			content = postProcessFixNonNumericBigDecimal(content);

			// Post-processor: fix BigDecimal.compareTo(StringField) type mismatch.
			// When a BigDecimal field is compared to a String field via .compareTo(),
			// convert to CobolComparison.compareAlphanumeric() which has overloads for
			// both (BigDecimal, String) and (String, BigDecimal).
			content = postProcessFixBigDecimalCompareToString(content);

			// Post-processor: fix OCCURS/REDEFINES substring patterns where the
			// substring result is used in a numeric context (BigDecimal operations).
			// Pattern 1: VAR.substring(START, END) = VALUE  (assignment to substring)
			//   Fix: VAR = CobolMove.overlayString(VAR, String.valueOf(VALUE), START, END-START)
			// Pattern 2: EXPR.subtract(VAR.substring(START, END))  (subtract String from BigDecimal)
			//   Fix: EXPR.subtract(new BigDecimal(VAR.substring(START, END).trim()))
			// Pattern 3: moveNumericToAlphanumeric(VAR.substring(START, END), N, M)
			//   Fix: moveNumericToAlphanumeric(new BigDecimal(VAR.substring(START, END).trim()), N, M)
			// Pattern 4: EXPR.add(VAR.substring(START, END))
			//   Fix: EXPR.add(new BigDecimal(VAR.substring(START, END).trim()))
			content = postProcessFixOccursRedefinesSubstring(content);

			// Post-processor: fix FUNCTION TRIM/TRIM-R/TRIM-L
			// The transformer generates local methods functiontrim(), functiontrimr(), functiontriml()
			// but doesn't define them. Replace with Java String methods.
			content = content.replace("functiontrim(", "io.proleap.cobol.runtime.CobolStringOps.functionTrim(");
			content = content.replace("functiontrimr(", "io.proleap.cobol.runtime.CobolStringOps.functionTrimR(");
			content = content.replace("functiontriml(", "io.proleap.cobol.runtime.CobolStringOps.functionTrimL(");

			// Post-processor: fix LENGTH OF in reference modification.
			// When LENGTH OF appears inside a reference modification (1:LENGTH OF FIELD),
			// the parser generates it as a variable name like "lengthofXXX" instead of
			// recognizing it as the LENGTH OF special register.
			// Fix: replace "lengthofXXX.intValue()" with "CobolMove.getGroupSize(xxx)"
			// and "lengthofXXX" (standalone) with "BigDecimal.valueOf(CobolMove.getGroupSize(xxx))"
			content = postProcessFixLengthOfVariable(content);

			// Fix misplaced CobolComparison calls: obj.CobolComparison.method(field, ...)
			// should be CobolComparison.method(obj.field, ...)
			// This must run AFTER all other post-processing as abbreviated OR condition expansion
			// can create these patterns during earlier post-processing steps.
			{
				String[] markers = { ".CobolComparison.compareAlphanumeric(", ".CobolComparison.compareNumeric(" };
				for (String mk : markers) {
					String methodName = mk.substring(".CobolComparison.".length(), mk.length() - 1);
					int searchFrom = 0;
					while (true) {
						int idx = content.indexOf(mk, searchFrom);
						if (idx < 0) break;
						int objEnd = idx;
						int objStart = idx - 1;
						int parenDepth = 0;
						while (objStart >= 0) {
							char ch = content.charAt(objStart);
							if (ch == ')') { parenDepth++; objStart--; continue; }
							if (ch == '(') {
								parenDepth--;
								if (parenDepth < 0) break;
								objStart--;
								continue;
							}
							if (parenDepth > 0) { objStart--; continue; }
							if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '.') { objStart--; continue; }
							break;
						}
						objStart++;
						if (objStart >= objEnd) { searchFrom = idx + 1; continue; }
						String objPath = content.substring(objStart, objEnd);
						int fieldStart = idx + mk.length();
						int fieldEnd = fieldStart;
						while (fieldEnd < content.length() && (Character.isLetterOrDigit(content.charAt(fieldEnd)) || content.charAt(fieldEnd) == '_')) {
							fieldEnd++;
						}
						if (fieldEnd == fieldStart || fieldEnd >= content.length() || content.charAt(fieldEnd) != ',') { searchFrom = idx + 1; continue; }
						String fieldName = content.substring(fieldStart, fieldEnd);
						String oldStr = objPath + mk + fieldName + ",";
						String newStr = "CobolComparison." + methodName + "(" + objPath + "." + fieldName + ",";
						content = content.substring(0, objStart) + newStr + content.substring(objStart + oldStr.length());
						searchFrom = objStart + newStr.length();
					}
				}
			}

			// Post-processor: fix String.compareTo(new BigDecimal(stringExpr)).
			// When a String field is compared with new BigDecimal(anotherStringField),
			// the comparison is invalid in Java. Replace with CobolComparison.compareAlphanumeric.
			// Simple approach: replace .compareTo(new BigDecimal(ARG)) with
			// .compareTo(ARG) — since both are Strings, String.compareTo(String) works.
			// But actually, we need CobolComparison for proper COBOL semantics.
			// Strategy: find the subject, wrap in CobolComparison.compareAlphanumeric.
			{
				String marker = ".compareTo(new BigDecimal(";
				int searchFrom = 0;
				while (true) {
					int idx = content.indexOf(marker, searchFrom);
					if (idx < 0) break;

					// Find the BigDecimal argument
					int argStart = idx + marker.length();
					int bdDepth = 1;
					int argEnd = -1;
					for (int ci = argStart; ci < content.length(); ci++) {
						if (content.charAt(ci) == '(') bdDepth++;
						else if (content.charAt(ci) == ')') {
							bdDepth--;
							if (bdDepth == 0) { argEnd = ci; break; }
						}
					}
					if (argEnd < 0) { searchFrom = idx + 1; continue; }
					String argExpr = content.substring(argStart, argEnd);

					// Expect )) after the argument — one for BigDecimal, one for compareTo
					int afterBd = argEnd + 1;
					if (afterBd >= content.length() || content.charAt(afterBd) != ')') {
						searchFrom = idx + 1; continue;
					}
					afterBd++; // past compareTo closing )

					// Find the subject: scan backwards from idx to find where it starts
					// We need to handle safeGet(...) with nested parens
					int subjEnd = idx;
					int subjStart = subjEnd - 1;
					int pDepth = 0;
					while (subjStart >= 0) {
						char c = content.charAt(subjStart);
						if (c == ')') pDepth++;
						else if (c == '(') {
							if (pDepth == 0) { subjStart++; break; }
							pDepth--;
						} else if (pDepth == 0 && (c == ' ' || c == ',' || c == '!' || c == '&' || c == '|')) {
							subjStart++;
							break;
						}
						subjStart--;
					}
					if (subjStart < 0) subjStart = 0;
					String subject = content.substring(subjStart, subjEnd);

					// Replace: SUBJECT.compareTo(new BigDecimal(ARG)) with
					// CobolComparison.compareAlphanumeric(SUBJECT, ARG)
					String oldFragment = subject + marker + argExpr + "))";
					String newFragment = "CobolComparison.compareAlphanumeric(" + subject + ", " + argExpr + ")";
					content = content.substring(0, subjStart) + newFragment + content.substring(subjStart + oldFragment.length());
					searchFrom = subjStart + newFragment.length();
				}
			}

			// Post-processor: fix moveAlphanumericToNumeric with BigDecimal _int fields.
			// When a field ending with _int (BigDecimal) is passed to moveAlphanumericToNumeric
			// (which expects String), replace with moveNumericToNumeric.
			{
				String manMarker = "CobolMove.moveAlphanumericToNumeric(";
				int manSearch = 0;
				while (true) {
					int manIdx = content.indexOf(manMarker, manSearch);
					if (manIdx < 0) break;
					int argStart = manIdx + manMarker.length();
					// Find the first comma at depth 0 (separating first arg from second)
					int manDepth = 0;
					int commaIdx = -1;
					for (int ci = argStart; ci < content.length(); ci++) {
						if (content.charAt(ci) == '(') manDepth++;
						else if (content.charAt(ci) == ')') {
							if (manDepth == 0) break;
							manDepth--;
						} else if (content.charAt(ci) == ',' && manDepth == 0) {
							commaIdx = ci;
							break;
						}
					}
					if (commaIdx < 0) { manSearch = manIdx + 1; continue; }
					String firstArg = content.substring(argStart, commaIdx).trim();
					// Check if the first argument ends with _int (BigDecimal field)
					if (firstArg.endsWith("_int")) {
						content = content.substring(0, manIdx) +
							"CobolMove.moveNumericToNumeric(" +
							content.substring(argStart);
						manSearch = manIdx + "CobolMove.moveNumericToNumeric(".length();
					} else {
						manSearch = manIdx + 1;
					}
				}
			}

			// Post-processor: fix PIC 1 REDEFINES with 88-level SET patterns.
			// When COBOL does "SET ConditionName OF Field(idx) TO TRUE", and the field is
			// a PIC 1 REDEFINES (boolean), ProLeap generates field.conditionName = true
			// but the field is a boolean getter, not an object. Fix to setCanbedelb(true/false).
			content = postProcessFixPic1Set88(content);

			// Post-processor: fix OCCURS initializer using parent variable name.
			// When a group contains an OCCURS child, the generated initializer block
			// sometimes uses the parent's instance variable name instead of the OCCURS
			// List field name. E.g.:
			//   class XType {
			//     class YType { ... }
			//     protected List<YType> y = new ArrayList<YType>();
			//     { x.add(new YType()); }   // BUG: should be y.add(new YType())
			//   }
			//   protected XType x = new XType();
			content = postProcessFixOccursInitializer(content);

			// Post-processor: fix VARCHAR group assignments.
			// DDS stubs define VARCHAR fields as groups with LENGTH and DATA sub-fields.
			// When the generated code assigns a String to a typed field, redirect to _data.
			// IMPORTANT: Only fix assignments where the SPECIFIC path resolves to a typed field,
			// NOT where the same field name is a plain String in a different structure.
			content = postProcessFixVarcharGroupAssignment(content);

			// Post-processor: fix VARCHAR _data/_length references on plain String fields.
			// When COBOL source references sub-fields like ALIASEXTID-DATA on a VARCHAR that
			// was flattened to a plain String (from a flat copybook), the generated code has
			// xxx.yyy_data and xxx.yyy_length but yyy is just a String. Fix:
			//   xxx.yyy_data -> xxx.yyy (the String IS the data)
			//   xxx.yyy_length -> String.valueOf(xxx.yyy.length())
			content = postProcessFixVarcharDataOnString(content);

			// Post-processor: fix doubled variable prefixes like r_r_, wk_wk_, estr_estr_.
			// The code generator sometimes doubles the prefix when qualifying variables,
			// producing r_r_xxx instead of r_xxx. Fix: collapse doubled prefixes.
			content = postProcessFixDoubledPrefixes(content);

			// Post-processor: fix safeGet called on a group type instead of its List child.
			// When COBOL has OCCURS inside a group, the generated code sometimes calls
			// safeGet(groupVar, idx) instead of safeGet(groupVar.listField, idx).
			content = postProcessFixSafeGetOnGroupType(content);

			// Post-processor: fix BigDecimal-to-String type mismatch in setter methods.
			// When a REDEFINES alphanumeric field has a setter, the generated code
			// sometimes uses moveAlphanumericToNumeric (returns BigDecimal) instead of
			// moveAlphanumericToAlphanumeric (returns String). Also handles cases where
			// a BigDecimal getter value is passed to moveAlphanumericToNumeric expecting String.
			content = postProcessFixBigDecimalToStringMismatch(content);

			// Post-processor: fix moveNumericToNumeric called with a String source.
			// When a VARCHAR group is flattened to a plain String by DDS copybook resolution,
			// the transformer cannot resolve sub-field references like PRENOM-LENGTH OF PRENOM
			// and falls back to the parent field (a String). This produces:
			//   target.xxx_length = CobolMove.moveNumericToNumeric(source.flatStringField, N, M);
			// which fails because moveNumericToNumeric expects BigDecimal, not String.
			// Fix: replace moveNumericToNumeric with moveAlphanumericToNumeric for String sources.
			content = postProcessFixMoveNumericFromString(content);

			// Priority 9: Remove unreachable "throw new CobolStopRunException()" after "return;"
			// Use regex to handle inline comments between return and throw.
			content = java.util.regex.Pattern.compile(
				"return;[^\\n]*\\n(\\s*)throw new CobolStopRunException\\(\\);[^\\n]*\\n")
				.matcher(content).replaceAll("return;\n");

			// Priority 10: Fix BigDecimal used as boolean in logical expressions
			// Pattern: || bigDecimalVar) -> || (bigDecimalVar.compareTo(BigDecimal.ZERO) != 0))
			{
				// Build set of all BigDecimal field names from declarations (exclude ambiguous ones also declared as String)
				java.util.Set<String> bdFieldsForBool = new java.util.HashSet<>();
				java.util.Set<String> strFieldsForBool = new java.util.HashSet<>();
				java.util.regex.Matcher bdDeclForBool = java.util.regex.Pattern.compile(
					"protected\\s+(?:java\\.math\\.)?BigDecimal\\s+([a-z][a-z0-9_]*)\\s*=").matcher(content);
				while (bdDeclForBool.find()) {
					bdFieldsForBool.add(bdDeclForBool.group(1));
				}
				java.util.regex.Matcher strDeclForBool = java.util.regex.Pattern.compile(
					"protected\\s+String\\s+([a-z][a-z0-9_]*)\\s*=").matcher(content);
				while (strDeclForBool.find()) {
					strFieldsForBool.add(strDeclForBool.group(1));
				}
				bdFieldsForBool.removeAll(strFieldsForBool); // remove ambiguous

				java.util.regex.Pattern boolBDPattern = java.util.regex.Pattern.compile(
					"(\\|\\||&&)\\s+([a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)*)\\s*\\)");
				java.util.regex.Matcher boolBDMatcher = boolBDPattern.matcher(content);
				StringBuffer boolBDSb = new StringBuffer();
				boolean boolBDChanged = false;
				while (boolBDMatcher.find()) {
					String varPath = boolBDMatcher.group(2);
					String lastPart = varPath.contains(".") ? varPath.substring(varPath.lastIndexOf('.') + 1) : varPath;
					// Check if this variable is a BigDecimal field (by suffix or unambiguous declaration)
					if (lastPart.endsWith("_int") || lastPart.endsWith("_sig") || bdFieldsForBool.contains(lastPart)) {
						String op = boolBDMatcher.group(1);
						boolBDMatcher.appendReplacement(boolBDSb,
							op + " (" + java.util.regex.Matcher.quoteReplacement(varPath) + ".compareTo(BigDecimal.ZERO) != 0))");
						boolBDChanged = true;
					}
				}
				if (boolBDChanged) {
					boolBDMatcher.appendTail(boolBDSb);
					content = boolBDSb.toString();
				}
			}

			// Priority 7: Fix SQL FETCH .length() called on BigDecimal variables
			// Pattern: bigDecimalVar.length() -> bigDecimalVar.toPlainString().length()
			{
				// Build set of all field names declared as BigDecimal (including inside inner classes)
				java.util.Set<String> bigDecimalFieldNames = new java.util.HashSet<>();
				java.util.Set<String> stringFieldNames = new java.util.HashSet<>();
				java.util.regex.Matcher bdDeclMatcher = java.util.regex.Pattern.compile(
					"protected\\s+(?:java\\.math\\.)?BigDecimal\\s+([a-z][a-z0-9_]*)\\s*=").matcher(content);
				while (bdDeclMatcher.find()) {
					bigDecimalFieldNames.add(bdDeclMatcher.group(1));
				}
				java.util.regex.Matcher strDeclMatcher = java.util.regex.Pattern.compile(
					"protected\\s+String\\s+([a-z][a-z0-9_]*)\\s*=").matcher(content);
				while (strDeclMatcher.find()) {
					stringFieldNames.add(strDeclMatcher.group(1));
				}
				// Remove ambiguous names (declared as BOTH String and BigDecimal in different contexts)
				java.util.Set<String> unambiguousBDFields = new java.util.HashSet<>(bigDecimalFieldNames);
				unambiguousBDFields.removeAll(stringFieldNames);

				java.util.regex.Pattern lenBDPattern = java.util.regex.Pattern.compile(
					"\\b([a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)*)(\\.length\\(\\))");
				java.util.regex.Matcher lenBDMatcher = lenBDPattern.matcher(content);
				StringBuffer lenBDSb = new StringBuffer();
				boolean lenBDChanged = false;
				while (lenBDMatcher.find()) {
					String varPath = lenBDMatcher.group(1);
					String lastPart = varPath.contains(".") ? varPath.substring(varPath.lastIndexOf('.') + 1) : varPath;
					// Fix if: (a) suffix is _int/_sig, or (b) the field name is ONLY declared as BigDecimal (not also String)
					if (lastPart.endsWith("_int") || lastPart.endsWith("_sig") || unambiguousBDFields.contains(lastPart)) {
						lenBDMatcher.appendReplacement(lenBDSb,
							java.util.regex.Matcher.quoteReplacement(varPath) + ".toPlainString().length()");
						lenBDChanged = true;
					}
				}
				if (lenBDChanged) {
					lenBDMatcher.appendTail(lenBDSb);
					content = lenBDSb.toString();
				}
			}

			// Post-processor: fix VARCHAR group used where String expected.
			// When a VARCHAR group variable (has _data/_length subfields via inner class) is used
			// in a context expecting String (e.g., String.valueOf(xxx), CobolIntrinsic.reverse(xxx),
			// CobolStringOps methods), append .xxx_data to use the String data field.
			// Also fix assignment of String to VARCHAR group: xxx = stringExpr -> xxx.xxx_data = stringExpr
			content = postProcessFixVarcharGroupInStringContext(content);

			// Post-processor: fix ambiguous VARCHAR fields that are both String and Type
			// in different classes. Uses container-aware disambiguation to only fix
			// references through typed containers where the field is declared as varchar.
			content = postProcessFixAmbiguousVarcharFields(content);

			// Post-processor: fix self-referencing paths in OCCURS SEARCH ALL.
			// When SEARCH ALL generates size checks, it sometimes uses the parent variable name
			// instead of the OCCURS child List field name: parent.parent.size() -> parent.listField.size()
			content = postProcessFixSelfReferencingOccursPath(content);

			// Fix parent-child class name collisions (must run BEFORE
			// postProcessFixOccursElementQualifier so class names are unique).
			content = postProcessFixDuplicateParentChildClasses(content);

			// Fix OCCURS element paths where the qualifier after .get() references a
			// top-level variable name instead of the element type's inner field.
			// Must run AFTER postProcessFixDuplicateParentChildClasses so each class
			// has a unique name and field lookup is unambiguous.
			content = postProcessFixOccursElementQualifier(content);

			// Post-processor: inject xmlParse() calls for XML PARSE blocks that were
			// commented out by the preprocessor. Uses metadata extracted before preprocessing.
			content = postProcessXmlParse(content);

			// Post-processor: fix SET ADDRESS OF type mismatches.
			// When COBOL SET ADDRESS OF GROUP TO POINTER assigns an Object/String to
			// a typed inner class variable, replace with CobolMove.moveStringToGroup()
			// to populate the group fields from the flat data.
			content = postProcessFixSetAddressOfTypeMismatch(content);

			if (missingMethods.isEmpty()) {
				Files.write(javaFile.toPath(), content.getBytes());
				return;
			}

			LOG.info("Post-processing: found {} missing method(s): {}", missingMethods.size(), missingMethods);

			// Generate stub methods for each missing method
			final StringBuilder stubs = new StringBuilder();
			for (final String methodName : missingMethods) {
				stubs.append("\n    public void ").append(methodName).append("() throws Exception {\n");
				stubs.append("        // Auto-generated stub for embedded paragraph\n");
				stubs.append("    }\n");
			}

			// Insert stubs before the last closing brace of the class
			final int lastBrace = content.lastIndexOf('}');
			if (lastBrace > 0) {
				String newContent = content.substring(0, lastBrace) + stubs.toString() + "\n" + content.substring(lastBrace);
				newContent = postProcessFixDuplicateParentChildClasses(newContent);
				newContent = postProcessFixOccursElementQualifier(newContent);
				Files.write(javaFile.toPath(), newContent.getBytes());
				LOG.info("Post-processing: added {} stub method(s)", missingMethods.size());
			}
		} catch (final IOException e) {
			LOG.warn("Post-processing failed: {}", e.getMessage());
		}
	}

	/**
	 * Fixes SET condition-name TO TRUE/FALSE that was misresolved to a database record field.
	 * Pattern: "r_xxx.xxx.fieldname = String.valueOf(true/false);" is always wrong —
	 * String.valueOf(true) = "true" which is never a valid COBOL value.
	 * Search for a matching boolean field and replace the assignment.
	 */
	protected String postProcessSetConditionMisresolution(String content) {
		// Build map of boolean fields: fieldName → fieldName
		final java.util.Map<String, String> booleanFields = new java.util.HashMap<>();
		final java.util.regex.Pattern boolFieldPattern = java.util.regex.Pattern.compile(
				"protected boolean ([a-z][a-z0-9_]*)\\b");
		final java.util.regex.Matcher boolMatcher = boolFieldPattern.matcher(content);
		while (boolMatcher.find()) {
			booleanFields.put(boolMatcher.group(1), boolMatcher.group(1));
		}

		// Find misresolved SET TO TRUE/FALSE: dotted.path.field = String.valueOf(true/false);
		final java.util.regex.Pattern misPattern = java.util.regex.Pattern.compile(
				"([ \\t]+)([a-z][a-zA-Z0-9_]*(?:\\.[a-z][a-zA-Z0-9_]*)+) = String\\.valueOf\\((true|false)\\);");
		final StringBuilder sb = new StringBuilder();
		int lastEnd = 0;
		final java.util.regex.Matcher misMatcher = misPattern.matcher(content);
		boolean changed = false;
		while (misMatcher.find()) {
			final String dottedPath = misMatcher.group(2);
			final String boolValue = misMatcher.group(3);
			// Extract the last segment of the dotted path as the field name
			final String[] segments = dottedPath.split("\\.");
			final String fieldName = segments[segments.length - 1];
			// Check if there's a matching boolean field (possibly with 's' suffix for pluralization)
			String matchingBool = booleanFields.get(fieldName);
			if (matchingBool == null) {
				matchingBool = booleanFields.get(fieldName + "s");
			}
			if (matchingBool == null) {
				matchingBool = booleanFields.get(fieldName.replaceAll("_$", ""));
			}
			if (matchingBool != null) {
				sb.append(content, lastEnd, misMatcher.start());
				sb.append(misMatcher.group(1));
				sb.append(matchingBool);
				sb.append(" = ");
				sb.append(boolValue);
				sb.append("; // post-fix: SET condition misresolution");
				lastEnd = misMatcher.end();
				changed = true;
			}
		}
		if (changed) {
			sb.append(content, lastEnd, content.length());
			return sb.toString();
		}
		return content;
	}

	/**
	 * Detects BigDecimal fields being directly assigned from String fields and wraps
	 * with CobolMove.moveAlphanumericToNumeric(). This happens when the ASG resolves
	 * the source type incorrectly for fields that exist in both alphanumeric (DDS display)
	 * and numeric (database) record structures.
	 */
	protected String postProcessStringToBigDecimalAssignments(String content) {
		// Build a set of field paths declared as String (e.g., "r_img11a_i.img11a_i.mont_devol")
		// and a set declared as BigDecimal.
		final java.util.Set<String> stringFields = new java.util.HashSet<>();
		final java.util.Set<String> bigDecimalFields = new java.util.HashSet<>();

		// Match field declarations: "protected String fieldName" or "protected BigDecimal fieldName"
		final java.util.regex.Pattern fieldDeclPattern = java.util.regex.Pattern.compile(
				"protected (String|BigDecimal) ([a-z][a-zA-Z0-9_]*)\\b");
		final java.util.regex.Matcher fieldMatcher = fieldDeclPattern.matcher(content);
		while (fieldMatcher.find()) {
			final String type = fieldMatcher.group(1);
			final String name = fieldMatcher.group(2);
			if ("String".equals(type)) {
				stringFields.add(name);
			} else {
				bigDecimalFields.add(name);
			}
		}

		// Now scan for assignments: "target = source;" where target is BigDecimal and source is String
		// Pattern: "fieldPath = fieldPath;" (both are dotted paths ending in a field name)
		final java.util.regex.Pattern assignPattern = java.util.regex.Pattern.compile(
				"([ \\t]+)((?:[a-z][a-zA-Z0-9_]*\\.)*([a-z][a-zA-Z0-9_]*)) = ((?:[a-z][a-zA-Z0-9_]*\\.)*([a-z][a-zA-Z0-9_]*));([^\n]*)");
		final StringBuilder sb = new StringBuilder();
		int lastEnd = 0;
		final java.util.regex.Matcher assignMatcher = assignPattern.matcher(content);
		boolean changed = false;
		while (assignMatcher.find()) {
			final String targetField = assignMatcher.group(3);
			final String sourceField = assignMatcher.group(5);
			final String sourceExpr = assignMatcher.group(4);

			if (bigDecimalFields.contains(targetField) && stringFields.contains(sourceField)) {
				sb.append(content, lastEnd, assignMatcher.start());
				sb.append(assignMatcher.group(1));
				sb.append(assignMatcher.group(2));
				sb.append(" = CobolMove.moveAlphanumericToNumeric(");
				sb.append(sourceExpr);
				sb.append(", 10, 2);");
				sb.append(" // post-fix: String→BigDecimal");
				lastEnd = assignMatcher.end();
				changed = true;
			}
		}
		if (changed) {
			sb.append(content, lastEnd, content.length());
			return sb.toString();
		}
		return content;
	}

	/**
	 * Fix compareAlphanumeric() called with a BigDecimal first argument.
	 * This happens when the transformer generates:
	 *   compareAlphanumeric(numericExpr.subtract(...), stringExpr)
	 * The fix changes compareAlphanumeric to compareNumeric and wraps the second
	 * argument with moveAlphanumericToNumeric if it's a String.
	 */
	protected String postProcessFixCompareAlphanumericWithBigDecimal(String content) {
		// Build sets of String and BigDecimal field names from declarations
		final java.util.Set<String> stringFieldNames = new java.util.HashSet<>();
		final java.util.Set<String> bigDecimalFieldNames = new java.util.HashSet<>();
		{
			java.util.regex.Matcher fm = java.util.regex.Pattern.compile(
				"protected\\s+(String|BigDecimal)\\s+(\\w+)\\s*=").matcher(content);
			while (fm.find()) {
				if ("String".equals(fm.group(1))) {
					stringFieldNames.add(fm.group(2));
				} else {
					bigDecimalFieldNames.add(fm.group(2));
				}
			}
		}

		// Pattern: compareAlphanumeric(EXPR, EXPR) where EXPR contains numeric-only operations
		// We detect this by looking for moveAlphanumericToNumeric or .subtract( or .add( before the comma
		boolean changed = false;
		StringBuilder sb = new StringBuilder();
		String[] lines = content.split("\n", -1);
		for (String line : lines) {
			String newLine = line;
			// Fix: compareAlphanumeric(BigDecimalExpr, StringExpr) -> compareNumeric(BigDecimalExpr, BigDecimalExpr)
			if (newLine.contains("compareAlphanumeric(") && (newLine.contains(".subtract(") || newLine.contains(".add(")
					|| newLine.contains("moveAlphanumericToNumeric"))) {
				// Only apply if the line does NOT contain moveNumericToAlphanumeric (which returns String)
				// to avoid creating compareNumeric(BigDecimal, String) mismatches
				if (!newLine.contains("moveNumericToAlphanumeric")) {
					// Replace compareAlphanumeric with compareNumeric
					newLine = newLine.replace("CobolComparison.compareAlphanumeric(", "CobolComparison.compareNumeric(");
					// Replace String.valueOf(BigDecimal.valueOf(N)) with BigDecimal.valueOf(N)
					// This regex matches: String.valueOf(BigDecimal.valueOf(DIGITS))
					newLine = newLine.replaceAll("String\\.valueOf\\(BigDecimal\\.valueOf\\(([^)]+)\\)\\)", "BigDecimal.valueOf($1)");
				}
				if (!newLine.equals(line)) {
					changed = true;
				}
			}
			// Fix: stringField.compareTo(BigDecimal.valueOf(N)) -> moveAlphanumericToNumeric(stringField, ...).compareTo(BigDecimal.valueOf(N))
			// Only wrap when the terminal field is confirmed as a String field.
			// Skip wrapping for BigDecimal fields (fixes safeGet().field.compareTo() for numeric OCCURS elements).
			{
				java.util.regex.Pattern p = java.util.regex.Pattern.compile(
					"((?:[a-z][a-zA-Z0-9_]*\\.)*([a-z][a-zA-Z0-9_]*))(\\.compareTo\\(BigDecimal\\.valueOf\\()");
				java.util.regex.Matcher m = p.matcher(newLine);
				StringBuffer sbLine = new StringBuffer();
				boolean lineChanged = false;
				while (m.find()) {
					String fieldPath = m.group(1);
					String terminalField = m.group(2);
					// Only wrap if the terminal field is known to be String.
					// If it's BigDecimal or unknown, leave it as-is.
					if (stringFieldNames.contains(terminalField) && !bigDecimalFieldNames.contains(terminalField)) {
						m.appendReplacement(sbLine, java.util.regex.Matcher.quoteReplacement(
							"CobolMove.moveAlphanumericToNumeric(" + fieldPath + ", 18, 0)" + m.group(3)));
						lineChanged = true;
					}
					// else: leave unchanged — the field is BigDecimal (or ambiguous), .compareTo() is valid
				}
				if (lineChanged) {
					m.appendTail(sbLine);
					newLine = sbLine.toString();
					changed = true;
				}
			}
			sb.append(newLine).append("\n");
		}
		if (changed) {
			return sb.toString();
		}
		return content;
	}

	/**
	 * Fix moveAlphanumericToNumeric called with BigDecimal arguments.
	 * Pattern 1: moveAlphanumericToNumeric((moveAlphanumericToNumeric(...).add/subtract(...)), N, M)
	 *   - The outer call is redundant since the inner expression is already BigDecimal.
	 * Pattern 2: moveAlphanumericToNumeric(bigDecimalVar, N, M)
	 *   - When the argument is a BigDecimal variable, just use it directly.
	 */
	protected String postProcessFixStringCompareToBigDecimal(String content) {
		// Build map: className -> map of fieldName -> type (String/BigDecimal)
		// This allows resolving ambiguous field names by checking the specific class
		final java.util.Map<String, java.util.Map<String, String>> classFieldTypes = new java.util.LinkedHashMap<>();
		{
			java.util.Deque<String> cStack = new java.util.ArrayDeque<>();
			java.util.Deque<Integer> bStack = new java.util.ArrayDeque<>();
			int bd = 0;
			String[] cLines = content.split("\n", -1);
			for (String cLine : cLines) {
				java.util.regex.Matcher cm = java.util.regex.Pattern.compile(
					"^\\s+public class (\\w+Type)\\s+\\{").matcher(cLine);
				if (cm.find()) {
					cStack.push(cm.group(1));
					bStack.push(bd);
					classFieldTypes.putIfAbsent(cm.group(1), new java.util.LinkedHashMap<>());
				}
				boolean inS = false, inC = false;
				for (int ci = 0; ci < cLine.length(); ci++) {
					char c = cLine.charAt(ci);
					if (inC) break;
					if (c == '"' && !inS) inS = true;
					else if (c == '"' && inS) inS = false;
					else if (c == '/' && ci + 1 < cLine.length() && cLine.charAt(ci + 1) == '/' && !inS) inC = true;
					else if (!inS) {
						if (c == '{') bd++;
						else if (c == '}') { bd--; if (!bStack.isEmpty() && bd == bStack.peek()) { cStack.pop(); bStack.pop(); } }
					}
				}
				if (!cStack.isEmpty()) {
					java.util.regex.Matcher fm = java.util.regex.Pattern.compile(
						"^\\s+protected\\s+(String|BigDecimal)\\s+(\\w+)\\s*=").matcher(cLine);
					if (fm.find()) {
						classFieldTypes.get(cStack.peek()).put(fm.group(2), fm.group(1));
					}
				}
			}
		}

		// Also build set of unambiguous BigDecimal top-level vars
		final java.util.Set<String> bigDecimalVars = new java.util.HashSet<>();
		java.util.regex.Matcher bdMatcher = java.util.regex.Pattern.compile(
			"^    protected\\s+BigDecimal\\s+(\\w+)\\s*=", java.util.regex.Pattern.MULTILINE).matcher(content);
		while (bdMatcher.find()) {
			bigDecimalVars.add(bdMatcher.group(1));
		}

		// Build varToType map for resolving dotted paths
		final java.util.Map<String, String> varToTypeLocal = new java.util.HashMap<>();
		java.util.regex.Matcher vtMatcher = java.util.regex.Pattern.compile(
			"protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)").matcher(content);
		while (vtMatcher.find()) {
			varToTypeLocal.put(vtMatcher.group(2), vtMatcher.group(1));
		}

		// Fix: moveAlphanumericToNumeric(bigDecimalExpr, N, M) -> bigDecimalExpr
		// Matches dotted paths like a.b.fieldName, resolving fieldName type from the class of b
		{
			java.util.regex.Pattern p = java.util.regex.Pattern.compile(
				"CobolMove\\.moveAlphanumericToNumeric\\(((?:[a-z][a-zA-Z0-9_]*\\.)*([a-z][a-zA-Z0-9_]*)),\\s*\\d+,\\s*\\d+\\)");
			java.util.regex.Matcher m = p.matcher(content);
			StringBuffer sb = new StringBuffer();
			boolean changed = false;
			while (m.find()) {
				String fullPath = m.group(1);
				String lastField = m.group(2);
				boolean isBigDecimal = false;

				// Try to resolve via dotted path
				String[] parts = fullPath.split("\\.");
				if (parts.length >= 2) {
					String parentVar = parts[parts.length - 2];
					String parentType = varToTypeLocal.get(parentVar);
					if (parentType != null && classFieldTypes.containsKey(parentType)) {
						String fieldType = classFieldTypes.get(parentType).get(lastField);
						isBigDecimal = "BigDecimal".equals(fieldType);
					}
				} else {
					// Simple variable at top level
					isBigDecimal = bigDecimalVars.contains(lastField);
				}

				if (isBigDecimal) {
					m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(fullPath));
					changed = true;
				}
			}
			if (changed) {
				m.appendTail(sb);
				content = sb.toString();
			}
		}

		// Fix: moveAlphanumericToNumeric(moveAlphanumericToNumeric(...).add/subtract(...), N, M)
		// The outer call is redundant -- the inner expression is already BigDecimal.
		// Remove the outer wrapper.
		boolean changed = false;
		String[] lines = content.split("\n", -1);
		StringBuilder sb = new StringBuilder();
		for (String line : lines) {
			String newLine = line;
			// Detect: CobolMove.moveAlphanumericToNumeric((CobolMove.moveAlphanumericToNumeric
			// Replace with just the inner expression (remove outer moveAlphanumericToNumeric wrapper)
			while (newLine.contains("CobolMove.moveAlphanumericToNumeric((CobolMove.moveAlphanumericToNumeric(")) {
				// Find the outer call and remove it, keeping the inner expression
				int outerStart = newLine.indexOf("CobolMove.moveAlphanumericToNumeric((CobolMove.moveAlphanumericToNumeric(");
				if (outerStart < 0) break;
				// Find the matching closing paren for the outer call
				int depth = 0;
				int innerExprStart = outerStart + "CobolMove.moveAlphanumericToNumeric((".length();
				// We need to find the last ", N, M)" that closes the outer call
				// Strategy: count parens from outerStart
				int searchFrom = outerStart + "CobolMove.moveAlphanumericToNumeric".length();
				int outerEnd = -1;
				for (int i = searchFrom; i < newLine.length(); i++) {
					char c = newLine.charAt(i);
					if (c == '(') depth++;
					else if (c == ')') {
						depth--;
						if (depth == 0) {
							outerEnd = i;
							break;
						}
					}
				}
				if (outerEnd < 0) break;

				// Extract the inner expression (between outer open paren and the ", N, M)" suffix)
				String outerCall = newLine.substring(outerStart, outerEnd + 1);
				// The outer call is: moveAlphanumericToNumeric(INNER_EXPR, N, M)
				// Find the last ", N, M)" pattern to separate inner expr from arguments
				java.util.regex.Matcher tailMatcher = java.util.regex.Pattern.compile(
					",\\s*\\d+\\s*,\\s*\\d+\\s*\\)$").matcher(outerCall);
				if (tailMatcher.find()) {
					String innerExpr = outerCall.substring("CobolMove.moveAlphanumericToNumeric(".length(),
						tailMatcher.start());
					// Remove leading/trailing parens if present
					if (innerExpr.startsWith("(") && innerExpr.endsWith(")")) {
						innerExpr = innerExpr.substring(1, innerExpr.length() - 1);
					}
					newLine = newLine.substring(0, outerStart) + innerExpr + newLine.substring(outerEnd + 1);
					changed = true;
				} else {
					break;
				}
			}
			sb.append(newLine).append("\n");
		}
		if (changed) {
			return sb.toString();
		}
		return content;
	}

	/**
	 * Fix type mismatch assignments:
	 * 1. field = CobolMove.moveAlphanumericToNumeric(X, N, M) where field is String
	 *    -> field = CobolMove.moveNumericToAlphanumeric(CobolMove.moveAlphanumericToNumeric(X, N, M), N, N)
	 *    Simplified: field = X (keep as alphanumeric)
	 * 2. field = CobolMove.moveNumericToNumeric(X, N, M) where field is String
	 *    -> field = String.valueOf(CobolMove.moveNumericToNumeric(X, N, M))
	 * 3. field = expr.divide/add/subtract/multiply where field is String
	 *    -> field = String.valueOf(expr)
	 */
	/**
	 * Fixes compareTo(new BigDecimal("...")) where either:
	 * 1. The literal is not a valid number (e.g., "TOPDACT", "Y", "N")
	 * 2. The field is declared as String (compareTo expects String, not BigDecimal)
	 *
	 * Converts: FIELD.compareTo(new BigDecimal("X")) CMP 0
	 *       to: CobolComparison.compareAlphanumeric(FIELD, "X") CMP 0
	 */
	protected String postProcessFixNonNumericBigDecimal(String content) {
		// Build set of String field names from declarations
		final java.util.Set<String> stringFieldNames = new java.util.HashSet<>();
		final java.util.regex.Matcher sf = java.util.regex.Pattern.compile(
			"protected\\s+String\\s+(\\w+)\\s*=").matcher(content);
		while (sf.find()) {
			stringFieldNames.add(sf.group(1));
		}

		// Match: EXPR.compareTo(new BigDecimal("LITERAL")) CMP 0
		final java.util.regex.Pattern p = java.util.regex.Pattern.compile(
			"([a-zA-Z_][a-zA-Z0-9_.]*)\\.compareTo\\(new BigDecimal\\(\"([^\"]*)\"\\)\\)\\s*(==|!=|<|>|<=|>=)\\s*0");
		final java.util.regex.Matcher m = p.matcher(content);
		final StringBuffer sb = new StringBuffer();
		while (m.find()) {
			final String fieldExpr = m.group(1);
			final String literal = m.group(2);
			final String cmp = m.group(3);

			// Check if literal is non-numeric (contains a letter)
			boolean nonNumericLiteral = literal.matches(".*[a-zA-Z].*");

			// Check if the field is a String variable
			boolean isStringField = false;
			if (fieldExpr.contains(".")) {
				String lastPart = fieldExpr.substring(fieldExpr.lastIndexOf('.') + 1);
				isStringField = stringFieldNames.contains(lastPart);
			} else {
				isStringField = stringFieldNames.contains(fieldExpr);
			}

			if (nonNumericLiteral || isStringField) {
				final String replacement = "CobolComparison.compareAlphanumeric(" + fieldExpr + ", \"" + literal + "\") " + cmp + " 0";
				m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
			}
		}
		m.appendTail(sb);
		return sb.toString();
	}

	/**
	 * Fix BigDecimal.compareTo(StringField) type mismatch.
	 * Pattern: (bigDecimalExpr.compareTo(stringExpr) CMP 0)
	 * Fix:     (CobolComparison.compareAlphanumeric(bigDecimalExpr, stringExpr) CMP 0)
	 *
	 * This catches cases where the classifier fails to detect a numeric-vs-string
	 * comparison (e.g., when one side has an unresolved type from a copybook).
	 */
	protected String postProcessFixBigDecimalCompareToString(String content) {
		// Build sets of String and BigDecimal field names from declarations
		final java.util.Set<String> stringFieldNames = new java.util.HashSet<>();
		final java.util.Set<String> bigDecimalFieldNames = new java.util.HashSet<>();
		{
			java.util.regex.Matcher fm = java.util.regex.Pattern.compile(
				"protected\\s+(String|BigDecimal)\\s+(\\w+)\\s*=").matcher(content);
			while (fm.find()) {
				if ("String".equals(fm.group(1))) {
					stringFieldNames.add(fm.group(2));
				} else {
					bigDecimalFieldNames.add(fm.group(2));
				}
			}
		}

		// Build varToType map for resolving dotted paths (e.g., wk_parm_apoio -> Wk_parm_apoioType)
		final java.util.Map<String, String> varToType = new java.util.HashMap<>();
		{
			java.util.regex.Matcher vtm = java.util.regex.Pattern.compile(
				"protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)").matcher(content);
			while (vtm.find()) {
				varToType.put(vtm.group(2), vtm.group(1));
			}
		}

		// Build classFieldTypes map
		final java.util.Map<String, java.util.Map<String, String>> classFieldTypes = new java.util.LinkedHashMap<>();
		{
			java.util.Deque<String> cStack = new java.util.ArrayDeque<>();
			java.util.Deque<Integer> bStack = new java.util.ArrayDeque<>();
			int bd = 0;
			String[] cLines = content.split("\n", -1);
			for (String cLine : cLines) {
				java.util.regex.Matcher cm = java.util.regex.Pattern.compile(
					"^\\s+public class (\\w+Type)\\s+\\{").matcher(cLine);
				if (cm.find()) {
					cStack.push(cm.group(1));
					bStack.push(bd);
					classFieldTypes.putIfAbsent(cm.group(1), new java.util.LinkedHashMap<>());
				}
				boolean inS = false, inC = false;
				for (int ci = 0; ci < cLine.length(); ci++) {
					char c = cLine.charAt(ci);
					if (inC) break;
					if (c == '"' && !inS) inS = true;
					else if (c == '"' && inS) inS = false;
					else if (c == '/' && ci + 1 < cLine.length() && cLine.charAt(ci + 1) == '/' && !inS) inC = true;
					else if (!inS) {
						if (c == '{') bd++;
						else if (c == '}') { bd--; if (!bStack.isEmpty() && bd == bStack.peek()) { cStack.pop(); bStack.pop(); } }
					}
				}
				if (!cStack.isEmpty()) {
					java.util.regex.Matcher fm2 = java.util.regex.Pattern.compile(
						"^\\s+protected\\s+(String|BigDecimal)\\s+(\\w+)\\s*=").matcher(cLine);
					if (fm2.find()) {
						classFieldTypes.get(cStack.peek()).put(fm2.group(2), fm2.group(1));
					}
				}
			}
		}

		// Helper to resolve type of a dotted path (e.g., wk_parm_apoio.ref -> "String")
		java.util.function.Function<String, String> resolveFieldType = (String path) -> {
			String[] parts = path.split("\\.");
			if (parts.length == 1) {
				if (bigDecimalFieldNames.contains(parts[0])) return "BigDecimal";
				if (stringFieldNames.contains(parts[0])) return "String";
				return null;
			}
			// Resolve through the type chain
			String currentType = varToType.get(parts[0]);
			for (int i = 1; i < parts.length; i++) {
				if (currentType == null) break;
				java.util.Map<String, String> fields = classFieldTypes.get(currentType);
				if (fields == null) break;
				if (i == parts.length - 1) {
					// Terminal field - return its type
					return fields.get(parts[i]);
				}
				// Intermediate field - look up its type
				currentType = varToType.get(parts[i]);
				if (currentType == null) {
					// Try classFieldTypes to find the Type class
					for (java.util.Map.Entry<String, java.util.Map<String, String>> e : classFieldTypes.entrySet()) {
						if (e.getValue().containsKey(parts[i])) {
							// Found the field; look for a Type association
							break;
						}
					}
				}
			}
			return null;
		};

		// Match: SUBJECT.compareTo(ARG) CMP 0
		// where SUBJECT is a dotted field path and ARG is a dotted field path
		final java.util.regex.Pattern p = java.util.regex.Pattern.compile(
			"\\(([a-zA-Z_][a-zA-Z0-9_.]*)\\.compareTo\\(([a-zA-Z_][a-zA-Z0-9_.]*)\\)\\s*(==|!=|<|>|<=|>=)\\s*0\\)");
		final java.util.regex.Matcher m = p.matcher(content);
		final StringBuffer sb = new StringBuffer();
		boolean changed = false;
		while (m.find()) {
			String subject = m.group(1);
			String arg = m.group(2);
			String cmp = m.group(3);

			// Get terminal field names
			String subjectTerminal = subject.contains(".") ? subject.substring(subject.lastIndexOf('.') + 1) : subject;
			String argTerminal = arg.contains(".") ? arg.substring(arg.lastIndexOf('.') + 1) : arg;

			boolean subjectIsBigDecimal = bigDecimalFieldNames.contains(subjectTerminal);
			boolean argIsString = stringFieldNames.contains(argTerminal);

			// If subject is BigDecimal and arg is String -> type mismatch
			if (subjectIsBigDecimal && argIsString) {
				String replacement = "(CobolComparison.compareAlphanumeric(" + subject + ", " + arg + ") " + cmp + " 0)";
				m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
				changed = true;
			}
		}
		if (changed) {
			m.appendTail(sb);
			return sb.toString();
		}
		return content;
	}

	/**
	 * Fix OCCURS over REDEFINES substring patterns that produce String where
	 * BigDecimal is expected, or attempt to assign to a substring (invalid Java).
	 */
	protected String postProcessFixOccursRedefinesSubstring(String content) {
		boolean changed = false;
		String[] lines = content.split("\n", -1);
		StringBuilder sb = new StringBuilder();
		for (String line : lines) {
			String newLine = line;
			String trimmed = newLine.trim();

			// Fix 1: VAR.substring(ARGS) = RHS;
			// -> VAR = CobolMove.overlayString(VAR, VALUE, START, LEN);
			// IMPORTANT: Only match actual assignments, not equality checks (==, !=).
			// The pattern is: the line starts with IDENTIFIER.substring(...)  = VALUE;
			// where "=" is preceded by a space and NOT by !, <, >, or =.
			// Skip lines that are method declarations or getter/setter methods.
			if (trimmed.contains(".substring(") && !trimmed.startsWith("public ") && !trimmed.startsWith("private ") && !trimmed.startsWith("protected ") && !trimmed.startsWith("if ") && !trimmed.startsWith("if(") && !trimmed.startsWith("} else") && !trimmed.startsWith("return ")) {
				// Check if the line starts with a simple identifier followed by .substring(
				java.util.regex.Matcher varSubMatcher = java.util.regex.Pattern.compile(
					"^([\\w.]+)\\.substring\\(").matcher(trimmed);
				if (varSubMatcher.find()) {
					String varName = varSubMatcher.group(1);
					int openParen = varSubMatcher.end() - 1;
					int closeParen = findMatchingParen(trimmed, openParen);
					if (closeParen > 0) {
						// Check that right after the close paren is " = " (assignment, not ==)
						String afterParen = trimmed.substring(closeParen + 1);
						if (afterParen.startsWith(" = ") && !afterParen.startsWith(" == ")) {
							// This is an assignment to a substring
							String indent = newLine.substring(0, newLine.length() - trimmed.length());
							String substringArgs = trimmed.substring(openParen + 1, closeParen);
							String afterEq = afterParen.substring(3); // Skip " = "
							int semiIdx = afterEq.lastIndexOf(';');
							String rhs = semiIdx >= 0 ? afterEq.substring(0, semiIdx).trim() : afterEq.trim();
							String comment = semiIdx >= 0 ? afterEq.substring(semiIdx + 1).trim() : "";
							if (!comment.isEmpty()) comment = " " + comment;

							String[] subArgs = splitTopLevelComma(substringArgs);
							if (subArgs != null && subArgs.length == 2) {
								String startExpr = subArgs[0].trim();
								String endExpr = subArgs[1].trim();
								String lenExpr = "(" + endExpr + ") - (" + startExpr + ")";
								String rhsStr;
								if (rhs.startsWith("CobolMove.moveNumericToNumeric(")) {
									rhsStr = "CobolMove.moveNumericToAlphanumeric(" + rhs + ", " + lenExpr + ", " + lenExpr + ")";
								} else {
									rhsStr = "String.valueOf(" + rhs + ")";
								}
								newLine = indent + varName + " = CobolMove.overlayString(" + varName + ", " + rhsStr + ", " + startExpr + ", " + lenExpr + ");" + comment;
								changed = true;
							}
						}
					}
				}
			}

			// Fix 2: .subtract(VAR.substring(...)) or .add(VAR.substring(...))
			// -> .subtract(new BigDecimal(VAR.substring(...).trim()))
			for (String op : new String[]{".subtract(", ".add("}) {
				int searchStart = 0;
				while (true) {
					int opIdx = newLine.indexOf(op, searchStart);
					if (opIdx < 0) break;
					String afterOp = newLine.substring(opIdx + op.length());
					// Check if the argument contains VAR.substring(
					int subIdx2 = afterOp.indexOf(".substring(");
					if (subIdx2 < 0 || subIdx2 > 60) { searchStart = opIdx + 1; break; }
					String varPart = afterOp.substring(0, subIdx2);
					if (!varPart.matches("[\\w.]+")) { searchStart = opIdx + 1; break; }
					int substringOpen = subIdx2 + ".substring(".length() - 1;
					int substringClose = findMatchingParen(afterOp, substringOpen);
					if (substringClose < 0) { searchStart = opIdx + 1; break; }
					// The closing paren of .subtract() should be right after the substring close
					int outerClose = substringClose + 1;
					if (outerClose >= afterOp.length() || afterOp.charAt(outerClose) != ')') { searchStart = opIdx + 1; break; }
					// Replace: .subtract(VAR.substring(ARGS)) -> .subtract(new BigDecimal(VAR.substring(ARGS).trim()))
					String substringCall = afterOp.substring(0, substringClose + 1);
					newLine = newLine.substring(0, opIdx) + op + "new BigDecimal(" + substringCall + ".trim()))" + afterOp.substring(outerClose + 1);
					changed = true;
					break; // One replacement per pass to avoid index issues
				}
			}

			// Fix 3: moveNumericToAlphanumeric(VAR.substring(...), N, M)
			// -> moveNumericToAlphanumeric(new BigDecimal(VAR.substring(...).trim()), N, M)
			for (String moveMethod : new String[]{"moveNumericToAlphanumeric(", "moveNumericToNumeric(", "moveAlphanumericToNumeric("}) {
				String searchToken = moveMethod;
				int pos = 0;
				while ((pos = newLine.indexOf(searchToken, pos)) >= 0) {
					int argStart = pos + searchToken.length();
					// Check if the first arg is VAR.substring(...)
					String afterMethod = newLine.substring(argStart);
					int subIdx3 = afterMethod.indexOf(".substring(");
					if (subIdx3 >= 0 && subIdx3 < 60) {
						String varPart3 = afterMethod.substring(0, subIdx3);
						if (varPart3.matches("[\\w.]+")) {
							int substringOpen3 = subIdx3 + ".substring(".length() - 1;
							int substringClose3 = findMatchingParen(afterMethod, substringOpen3);
							if (substringClose3 > 0) {
								String substringCall3 = afterMethod.substring(0, substringClose3 + 1);
								// Check that the next char after the substring call is a comma (it's an arg)
								if (substringClose3 + 1 < afterMethod.length() && afterMethod.charAt(substringClose3 + 1) == ',') {
									String wrappedArg = "new BigDecimal(" + substringCall3 + ".trim())";
									newLine = newLine.substring(0, argStart) + wrappedArg + afterMethod.substring(substringClose3 + 1);
									changed = true;
								}
							}
						}
					}
					pos = argStart + 1;
				}
			}

			sb.append(newLine).append("\n");
		}
		if (changed) {
			return sb.toString();
		}
		return content;
	}

	/**
	 * Find the matching closing parenthesis for the opening paren at the given position.
	 */
	private int findMatchingParen(String s, int openPos) {
		int depth = 0;
		for (int i = openPos; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '(') depth++;
			else if (c == ')') {
				depth--;
				if (depth == 0) return i;
			}
		}
		return -1;
	}

	/**
	 * Split a string on the top-level comma (not inside parentheses).
	 */
	private String[] splitTopLevelComma(String s) {
		int depth = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '(') depth++;
			else if (c == ')') depth--;
			else if (c == ',' && depth == 0) {
				return new String[]{s.substring(0, i), s.substring(i + 1)};
			}
		}
		return null;
	}

	protected String postProcessFixTypeMismatchAssignments(String content) {
		// Build set of String fields and BigDecimal fields from declarations
		final java.util.Set<String> stringFields = new java.util.HashSet<>();
		final java.util.Set<String> bigDecimalFields = new java.util.HashSet<>();

		// Map inner class field types
		final java.util.Map<String, java.util.Map<String, String>> classFieldTypes = new java.util.LinkedHashMap<>();
		java.util.Deque<String> cStack = new java.util.ArrayDeque<>();
		java.util.Deque<Integer> bStack = new java.util.ArrayDeque<>();
		int bd = 0;
		String[] scanLines = content.split("\n", -1);
		for (String scanLine : scanLines) {
			java.util.regex.Matcher cm = java.util.regex.Pattern.compile(
				"^\\s+public class (\\w+Type)\\s+\\{").matcher(scanLine);
			if (cm.find()) {
				cStack.push(cm.group(1));
				bStack.push(bd);
				classFieldTypes.putIfAbsent(cm.group(1), new java.util.LinkedHashMap<>());
			}
			boolean inS = false, inC = false;
			for (int ci = 0; ci < scanLine.length(); ci++) {
				char c = scanLine.charAt(ci);
				if (inC) break;
				if (c == '"' && !inS) inS = true;
				else if (c == '"' && inS) inS = false;
				else if (c == '/' && ci + 1 < scanLine.length() && scanLine.charAt(ci + 1) == '/' && !inS) inC = true;
				else if (!inS) {
					if (c == '{') bd++;
					else if (c == '}') { bd--; if (!bStack.isEmpty() && bd == bStack.peek()) { cStack.pop(); bStack.pop(); } }
				}
			}
			if (!cStack.isEmpty()) {
				java.util.regex.Matcher fm = java.util.regex.Pattern.compile(
					"^\\s+protected\\s+(String|BigDecimal)\\s+(\\w+)\\s*=").matcher(scanLine);
				if (fm.find()) {
					classFieldTypes.get(cStack.peek()).put(fm.group(2), fm.group(1));
					if ("String".equals(fm.group(1))) stringFields.add(fm.group(2));
					else bigDecimalFields.add(fm.group(2));
				}
			}
		}

		// Also top-level fields
		java.util.regex.Matcher topFieldMatcher = java.util.regex.Pattern.compile(
			"^    protected\\s+(String|BigDecimal)\\s+(\\w+)\\s*=", java.util.regex.Pattern.MULTILINE).matcher(content);
		while (topFieldMatcher.find()) {
			if ("String".equals(topFieldMatcher.group(1))) stringFields.add(topFieldMatcher.group(2));
			else bigDecimalFields.add(topFieldMatcher.group(2));
		}

		// Variable to type mapping — includes both constructor and alias declarations
		final java.util.Map<String, String> varToType = new java.util.HashMap<>();
		java.util.regex.Matcher vtMatcher = java.util.regex.Pattern.compile(
			"protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)").matcher(content);
		while (vtMatcher.find()) {
			varToType.put(vtMatcher.group(2), vtMatcher.group(1));
		}
		// Also capture alias declarations: protected SomeType alias = otherVar;
		java.util.regex.Matcher aliasMatch = java.util.regex.Pattern.compile(
			"protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*(\\w+)\\s*;").matcher(content);
		while (aliasMatch.find()) {
			varToType.put(aliasMatch.group(2), aliasMatch.group(1));
		}

		boolean changed = false;
		StringBuilder sb = new StringBuilder();
		String[] lines = content.split("\n", -1);
		for (String line : lines) {
			String newLine = line;

			// Strip trailing comment for pattern matching
			String codePart = newLine;
			int commentIdx = codePart.indexOf("// (");
			if (commentIdx < 0) commentIdx = codePart.indexOf("// post-fix");
			if (commentIdx > 0) codePart = codePart.substring(0, commentIdx);

			// Pattern: field = CobolMove.moveAlphanumericToNumeric(X, N, M); where field is String
			// Fix: use moveAlphanumericToAlphanumeric instead (keep String)
			if (codePart.contains("= CobolMove.moveAlphanumericToNumeric(")) {
				java.util.regex.Matcher m = java.util.regex.Pattern.compile(
					"((?:[a-zA-Z_][a-zA-Z0-9_]*\\.)*([a-zA-Z_][a-zA-Z0-9_]*)) = CobolMove\\.moveAlphanumericToNumeric\\((.+),\\s*(\\d+),\\s*(\\d+)\\);").matcher(newLine);
				if (m.find()) {
					String targetField = m.group(2);
					// Resolve field type via dotted path
					String targetType = resolveFieldType(m.group(1), targetField, varToType, classFieldTypes, stringFields, bigDecimalFields);
					if ("String".equals(targetType)) {
						// Replace moveAlphanumericToNumeric with moveAlphanumericToAlphanumeric
						int totalLen = Integer.parseInt(m.group(4)) + Integer.parseInt(m.group(5));
						newLine = newLine.replace(
							"CobolMove.moveAlphanumericToNumeric(" + m.group(3) + ", " + m.group(4) + ", " + m.group(5) + ")",
							"CobolMove.moveAlphanumericToAlphanumeric(" + m.group(3) + ", " + totalLen + ")");
						changed = true;
					}
				}
			}

			// Pattern: field = CobolMove.moveNumericToNumeric(X, N, M); where field is String
			// Fix: wrap with String.valueOf()
			if (codePart.contains("= CobolMove.moveNumericToNumeric(")) {
				java.util.regex.Matcher m = java.util.regex.Pattern.compile(
					"((?:[a-zA-Z_][a-zA-Z0-9_]*\\.)*([a-zA-Z_][a-zA-Z0-9_]*)) = (CobolMove\\.moveNumericToNumeric\\([^;]+\\));").matcher(newLine);
				if (m.find()) {
					String targetField = m.group(2);
					String targetType = resolveFieldType(m.group(1), targetField, varToType, classFieldTypes, stringFields, bigDecimalFields);
					if ("String".equals(targetType)) {
						newLine = newLine.replace(
							m.group(1) + " = " + m.group(3) + ";",
							m.group(1) + " = String.valueOf(" + m.group(3) + ");");
						changed = true;
					}
				}
			}

			// Pattern: field = String.valueOf(CobolMove.moveNumericToNumeric(X, N, M)); where field is BigDecimal
			// Fix: remove String.valueOf() wrapper — moveNumericToNumeric already returns BigDecimal
			if (codePart.contains("= String.valueOf(CobolMove.moveNumericToNumeric(")) {
				java.util.regex.Matcher m = java.util.regex.Pattern.compile(
					"((?:[a-zA-Z_][a-zA-Z0-9_]*\\.)*([a-zA-Z_][a-zA-Z0-9_]*)) = String\\.valueOf\\((CobolMove\\.moveNumericToNumeric\\([^)]+\\))\\);").matcher(newLine);
				if (m.find()) {
					String targetField = m.group(2);
					String targetType = resolveFieldType(m.group(1), targetField, varToType, classFieldTypes, stringFields, bigDecimalFields);
					if ("BigDecimal".equals(targetType)) {
						newLine = newLine.replace(
							m.group(1) + " = String.valueOf(" + m.group(3) + ");",
							m.group(1) + " = " + m.group(3) + ";");
						changed = true;
					}
				}
			}

			// Pattern: CobolMove.moveNumericToAlphanumeric(stringVar, N, D) where stringVar is String
			// Fix: CobolMove.moveAlphanumericToAlphanumeric(stringVar, N+D)
			// This occurs when a REDEFINES numeric view is used but the variable is actually alphanumeric.
			if (codePart.contains("CobolMove.moveNumericToAlphanumeric(")) {
				java.util.regex.Matcher m = java.util.regex.Pattern.compile(
					"CobolMove\\.moveNumericToAlphanumeric\\(((?:[a-zA-Z_][a-zA-Z0-9_]*\\.)*([a-zA-Z_][a-zA-Z0-9_]*)),\\s*(\\d+),\\s*(\\d+)\\)").matcher(newLine);
				if (m.find()) {
					String argField = m.group(2);
					String argType = resolveFieldType(m.group(1), argField, varToType, classFieldTypes, stringFields, bigDecimalFields);
					if ("String".equals(argType)) {
						int totalLen = Integer.parseInt(m.group(3)) + Integer.parseInt(m.group(4));
						newLine = newLine.replace(m.group(0),
							"CobolMove.moveAlphanumericToAlphanumeric(" + m.group(1) + ", " + totalLen + ")");
						changed = true;
					}
				}
			}

			// Pattern: field = expr.subtract/add/divide/multiply where field is String
			// Fix: wrap with String.valueOf()
			if (codePart.contains(".subtract(") || codePart.contains(".divide(")) {
				java.util.regex.Matcher m = java.util.regex.Pattern.compile(
					"((?:[a-zA-Z_][a-zA-Z0-9_]*\\.)*([a-zA-Z_][a-zA-Z0-9_]*)) = ([^;]+\\.(subtract|divide|multiply)\\([^;]+);").matcher(newLine);
				if (m.find()) {
					String targetField = m.group(2);
					String targetType = resolveFieldType(m.group(1), targetField, varToType, classFieldTypes, stringFields, bigDecimalFields);
					if ("String".equals(targetType)) {
						newLine = newLine.replace(
							m.group(1) + " = " + m.group(3) + ";",
							m.group(1) + " = String.valueOf(" + m.group(3) + ");");
						changed = true;
					}
				}
			}

			// Pattern: field = CobolMove.moveAlphanumericToAlphanumeric(X, N); where field is BigDecimal
			// Fix: use moveAlphanumericToNumeric instead
			if (codePart.contains("= CobolMove.moveAlphanumericToAlphanumeric(")) {
				java.util.regex.Matcher m = java.util.regex.Pattern.compile(
					"((?:[a-zA-Z_][a-zA-Z0-9_]*\\.)*([a-zA-Z_][a-zA-Z0-9_]*)) = CobolMove\\.moveAlphanumericToAlphanumeric\\((.+),\\s*(\\d+)\\);").matcher(newLine);
				if (m.find()) {
					String targetField = m.group(2);
					String targetType = resolveFieldType(m.group(1), targetField, varToType, classFieldTypes, stringFields, bigDecimalFields);
					if ("BigDecimal".equals(targetType)) {
						newLine = newLine.replace(
							"CobolMove.moveAlphanumericToAlphanumeric(" + m.group(3) + ", " + m.group(4) + ")",
							"CobolMove.moveAlphanumericToNumeric(" + m.group(3) + ", " + m.group(4) + ", 0)");
						changed = true;
					}
				}
			}

			// Pattern: field = CobolMove.moveZerosToAlphanumeric(N); where field is BigDecimal
			// Fix: replace with BigDecimal.ZERO — MOVE ZERO TO numeric field is just zero
			// This occurs when an undeclared variable (e.g., BASE000100) is aliased to a top-level
			// typed variable with BigDecimal fields, but the code generator emitted alphanumeric
			// MOVE ZERO because it resolved the field as String during ASG construction.
			if (codePart.contains("= CobolMove.moveZerosToAlphanumeric(")) {
				java.util.regex.Matcher m = java.util.regex.Pattern.compile(
					"((?:[a-zA-Z_][a-zA-Z0-9_]*\\.)*([a-zA-Z_][a-zA-Z0-9_]*)) = CobolMove\\.moveZerosToAlphanumeric\\(\\d+\\);").matcher(newLine);
				if (m.find()) {
					String targetField = m.group(2);
					String targetType = resolveFieldType(m.group(1), targetField, varToType, classFieldTypes, stringFields, bigDecimalFields);
					if ("BigDecimal".equals(targetType)) {
						newLine = newLine.replace(m.group(0),
							m.group(1) + " = BigDecimal.ZERO;");
						changed = true;
					}
				}
			}

			// Pattern: bigDecimalField = CobolMove.moveNumericToNumericEdited(X, "FMT", N);
			// where field is BigDecimal. The edited form returns String — use moveNumericToNumeric instead.
			if (codePart.contains("= CobolMove.moveNumericToNumericEdited(")) {
				java.util.regex.Matcher m = java.util.regex.Pattern.compile(
					"((?:[a-zA-Z_][a-zA-Z0-9_]*\\.)*([a-zA-Z_][a-zA-Z0-9_]*)) = CobolMove\\.moveNumericToNumericEdited\\(([^,]+),\\s*\"[^\"]+\",\\s*(\\d+)\\);").matcher(newLine);
				if (m.find()) {
					String targetField = m.group(2);
					String targetType = resolveFieldType(m.group(1), targetField, varToType, classFieldTypes, stringFields, bigDecimalFields);
					if ("BigDecimal".equals(targetType)) {
						// BigDecimal target: use moveNumericToNumeric instead of edited
						newLine = newLine.replace(
							m.group(0).substring(m.group(0).indexOf("= ") + 2),
							"CobolMove.moveNumericToNumeric(" + m.group(3) + ", " + m.group(4) + ", 0);");
						changed = true;
					}
				}
			}

			// Pattern: typedVar = ""; or typedVar = CobolConstants.spaces(N);
			// where typedVar is a typed inner class (e.g., Img2_oType), not String.
			// Fix: replace with entityService.initialize(typedVar);
			// This happens when INITIALIZE generates scalar assignment for a variable
			// that was later promoted to a typed class by the DDS post-processor.
			{
				java.util.regex.Matcher m = java.util.regex.Pattern.compile(
					"^(\\s+)(([a-z][a-zA-Z0-9_]*(?:\\.[a-z][a-zA-Z0-9_]*)*)) = (?:\"\"|CobolConstants\\.spaces\\(\\d+\\));(.*)$").matcher(newLine);
				if (m.find()) {
					String varPath = m.group(2);
					// Get the root variable name
					String rootVar = varPath.contains(".") ? varPath.substring(0, varPath.indexOf('.')) : varPath;
					String varType = varToType.get(rootVar);
					if (varType != null && !varPath.contains(".")) {
						// The root variable itself is a typed class — use entityService.initialize()
						newLine = m.group(1) + "entityService.initialize(" + varPath + ");" + m.group(4);
						changed = true;
					}
				}
			}

			// Pattern: typedVar = CobolMove.moveAlphanumericToAlphanumeric(expr, typedVar != null ? anyVar.length() : 0);
			// where typedVar is a typed inner class. Group-level MOVE should use moveStringToGroup.
			// Note: the .length() call may reference a different variable (e.g., r_xxx alias) than the LHS.
			if (codePart.contains("= CobolMove.moveAlphanumericToAlphanumeric(")) {
				java.util.regex.Matcher m = java.util.regex.Pattern.compile(
					"^(\\s+)([a-z][a-zA-Z0-9_]*) = CobolMove\\.moveAlphanumericToAlphanumeric\\((.+), \\2 != null \\? [a-zA-Z0-9_]+\\.length\\(\\) : 0\\);(.*)$").matcher(newLine);
				if (m.find()) {
					String varName = m.group(2);
					String varType = varToType.get(varName);
					if (varType != null) {
						// This is a group: use moveStringToGroup instead
						newLine = m.group(1) + "CobolMove.moveStringToGroup(" + m.group(3) + ", " + varName + ");" + m.group(4);
						changed = true;
					}
				}
			}

			// Pattern: stringField = stringField.add(expr)  (ADD to alphanumeric field)
			// Fix: stringField = String.valueOf(CobolMove.toBigDecimal(stringField).add(expr))
			if (codePart.contains(".add(")) {
				// Find the pattern: VAR = VAR.add(EXPR);
				// Use string scanning to handle nested parentheses in EXPR
				java.util.regex.Matcher m = java.util.regex.Pattern.compile(
					"((?:[a-zA-Z_][a-zA-Z0-9_]*\\.)*([a-zA-Z_][a-zA-Z0-9_]*)) = \\1\\.add\\(").matcher(newLine);
				if (m.find()) {
					String targetField = m.group(2);
					String targetType = resolveFieldType(m.group(1), targetField, varToType, classFieldTypes, stringFields, bigDecimalFields);
					if ("String".equals(targetType)) {
						// Find the matching closing paren for .add(
						int addStart = m.end(); // position after .add(
						int depth = 1;
						int addEnd = addStart;
						while (addEnd < newLine.length() && depth > 0) {
							char c = newLine.charAt(addEnd);
							if (c == '(') depth++;
							else if (c == ')') depth--;
							addEnd++;
						}
						if (depth == 0) {
							String addExpr = newLine.substring(addStart, addEnd - 1); // content inside .add(...)
							String oldCode = m.group(1) + " = " + m.group(1) + ".add(" + addExpr + ")";
							String newCode = m.group(1) + " = String.valueOf(CobolMove.toBigDecimal(" + m.group(1) + ").add(" + addExpr + "))";
							newLine = newLine.replace(oldCode, newCode);
							changed = true;
						}
					}
				}
			}

			sb.append(newLine).append("\n");
		}
		if (changed) {
			return sb.toString();
		}
		return content;
	}

	/**
	 * Fixes LENGTH OF in reference modification.
	 * When the parser can't recognize LENGTH OF inside a reference modification,
	 * it generates a variable name like "lengthofXXX" (concatenating "lengthof" prefix
	 * with the field name). This method detects those patterns and replaces them
	 * with proper CobolMove.getGroupSize() calls.
	 */
	protected String postProcessFixLengthOfVariable(String content) {
		// Find all "lengthof" prefixed identifiers in the content
		java.util.regex.Pattern pat = java.util.regex.Pattern.compile("\\blengthof([a-z][a-z0-9_]*)\\b");
		java.util.regex.Matcher finder = pat.matcher(content);
		java.util.Set<String> lengthOfVars = new java.util.LinkedHashSet<>();
		while (finder.find()) {
			lengthOfVars.add(finder.group(0)); // e.g., "lengthofcab_fb900s3"
		}
		if (lengthOfVars.isEmpty()) return content;

		// Check that these are not actually declared variables
		for (String lv : new java.util.ArrayList<>(lengthOfVars)) {
			java.util.regex.Pattern declPat = java.util.regex.Pattern.compile(
				"protected\\s+\\w+\\s+" + java.util.regex.Pattern.quote(lv) + "\\s*=");
			if (declPat.matcher(content).find()) {
				// Actually declared as a field — skip
				lengthOfVars.remove(lv);
			}
		}
		if (lengthOfVars.isEmpty()) return content;

		// For each lengthof variable, find the actual qualified path of the field
		// by searching for "parentVar.fieldName" in the content
		boolean changed = false;
		for (String lengthOfVar : lengthOfVars) {
			String fieldName = lengthOfVar.substring("lengthof".length());
			// Try to find the qualified path by looking for ".fieldName" declarations
			// Pattern: "protected ... fieldName = ..." inside a class
			java.util.regex.Pattern fieldPat = java.util.regex.Pattern.compile(
				"protected\\s+(?:String|BigDecimal)\\s+" + java.util.regex.Pattern.quote(fieldName) + "\\s*=");
			java.util.regex.Matcher fieldMatcher = fieldPat.matcher(content);

			// Find the field's PIC length from the declaration
			String qualifiedPath = fieldName; // default to bare name
			java.util.regex.Pattern picPat = java.util.regex.Pattern.compile(
				"protected\\s+String\\s+" + java.util.regex.Pattern.quote(fieldName) + "\\s*=\\s*\"([^\"]*)\";");
			java.util.regex.Matcher picMatcher = picPat.matcher(content);
			int fieldLength = -1;
			if (picMatcher.find()) {
				fieldLength = picMatcher.group(1).length();
			}

			// Replace ".intValue()" usage with the literal length if known
			String intValuePattern = lengthOfVar + ".intValue()";
			if (content.contains(intValuePattern)) {
				if (fieldLength > 0) {
					content = content.replace(intValuePattern, String.valueOf(fieldLength));
				} else {
					content = content.replace(intValuePattern, "40"); // safe default
				}
				changed = true;
			}
			// Replace standalone BigDecimal usage
			String bigDecimalPattern = "\\b" + java.util.regex.Pattern.quote(lengthOfVar) + "\\b";
			String replacement;
			if (fieldLength > 0) {
				replacement = "BigDecimal.valueOf(" + fieldLength + ")";
			} else {
				replacement = "BigDecimal.valueOf(40)";
			}
			String newContent = content.replaceAll(bigDecimalPattern, replacement);
			if (!newContent.equals(content)) {
				content = newContent;
				changed = true;
			}
		}
		if (changed) {
			System.err.println("POST-PROCESS: fixed " + lengthOfVars.size() + " LENGTH OF variable(s): " + lengthOfVars);
		}
		return content;
	}

	private String resolveFieldType(String fullPath, String fieldName,
			java.util.Map<String, String> varToType,
			java.util.Map<String, java.util.Map<String, String>> classFieldTypes,
			java.util.Set<String> stringFields, java.util.Set<String> bigDecimalFields) {
		// Try resolving via dotted path - walk the full chain
		String[] parts = fullPath.split("\\.");
		if (parts.length >= 2) {
			// Walk from the outermost to the innermost parent to find the type class
			String currentType = null;
			for (int i = 0; i < parts.length - 1; i++) {
				String part = parts[i];
				// Remove array subscript patterns like safeGet() wrapper
				String varName = part;
				if (currentType != null && classFieldTypes.containsKey(currentType)) {
					// Look up the variable's type class in the parent class
					// (it would be declared as "protected XxxType varName = new XxxType()")
					String nextType = varToType.get(varName);
					if (nextType != null) {
						currentType = nextType;
					}
				} else {
					currentType = varToType.get(varName);
				}
			}
			// Now check if the field exists in the resolved class
			if (currentType != null && classFieldTypes.containsKey(currentType)) {
				String fieldType = classFieldTypes.get(currentType).get(fieldName);
				if (fieldType != null) return fieldType;
			}
			// Also try just the immediate parent
			String parentVar = parts[parts.length - 2];
			String parentType = varToType.get(parentVar);
			if (parentType != null && classFieldTypes.containsKey(parentType)) {
				String fieldType = classFieldTypes.get(parentType).get(fieldName);
				if (fieldType != null) return fieldType;
			}
		}
		// Only use top-level resolution if the path is simple (no dots except the field)
		if (parts.length == 1) {
			if (stringFields.contains(fieldName) && !bigDecimalFields.contains(fieldName)) return "String";
			if (bigDecimalFields.contains(fieldName) && !stringFields.contains(fieldName)) return "BigDecimal";
		}
		return null;
	}

	/**
	 * Adds missing field declarations to inner classes.
	 * When DDS COPY statements resolve to incomplete stub copybooks, fields referenced
	 * in the PROCEDURE DIVISION may not exist in the generated inner type classes.
	 * This scans for field accesses like "outerVar.innerVar.fieldName" and adds
	 * "protected String fieldName = "";" (or BigDecimal) if the field is not declared.
	 */
	protected String postProcessAddMissingFields(String content) {
		// Step 1: Build a map of inner class names -> set of declared field names
		// Inner classes are at 4-space or 8-space indent: "    public class XxxType {"
		// Fields are declared as: "        protected String/BigDecimal/boolean fieldName = ..."
		final java.util.Map<String, java.util.Set<String>> classFields = new java.util.LinkedHashMap<>();
		final java.util.Map<String, Integer> classClosingBrace = new java.util.LinkedHashMap<>();

		String[] lines = content.split("\n", -1);

		// Parse class definitions and their fields
		// Track nesting via brace counting
		java.util.Deque<String> classStack = new java.util.ArrayDeque<>();
		java.util.Deque<Integer> braceStack = new java.util.ArrayDeque<>();
		int currentBraceDepth = 0;

		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			// Detect class definitions
			java.util.regex.Matcher classMatcher = java.util.regex.Pattern.compile(
				"^(\\s+)public class (\\w+Type)\\s+\\{").matcher(line);
			if (classMatcher.find()) {
				String className = classMatcher.group(2);
				classStack.push(className);
				braceStack.push(currentBraceDepth);
				if (!classFields.containsKey(className)) {
					classFields.put(className, new java.util.LinkedHashSet<>());
				}
			}

			// Count braces (skip string literals and comments)
			boolean inString = false;
			boolean inComment = false;
			for (int ci = 0; ci < line.length(); ci++) {
				char c = line.charAt(ci);
				if (inComment) break;
				if (c == '"' && !inString) inString = true;
				else if (c == '"' && inString) inString = false;
				else if (c == '/' && ci + 1 < line.length() && line.charAt(ci + 1) == '/' && !inString) inComment = true;
				else if (!inString) {
					if (c == '{') currentBraceDepth++;
					else if (c == '}') {
						currentBraceDepth--;
						if (!braceStack.isEmpty() && currentBraceDepth == braceStack.peek()) {
							String closedClass = classStack.pop();
							braceStack.pop();
							classClosingBrace.put(closedClass, i);
						}
					}
				}
			}

			// Detect ALL field/variable declarations inside the current class.
			// Captures: protected String/BigDecimal/boolean/List<T>/Type varName = ...
			// Also: protected type varName; (without initializer)
			if (!classStack.isEmpty()) {
				java.util.regex.Matcher anyFieldMatcher = java.util.regex.Pattern.compile(
					"^\\s+protected\\s+\\S+(?:<\\S+>)?\\s+(\\w+)\\s*[=;]").matcher(line);
				if (anyFieldMatcher.find()) {
					classFields.get(classStack.peek()).add(anyFieldMatcher.group(1));
				}
			}
		}

		// Step 2: Build a map from instance variable name -> its type class name
		// Pattern: "protected XxxType varName = new XxxType();"
		final java.util.Map<String, String> varToType = new java.util.HashMap<>();
		java.util.regex.Matcher varMatcher = java.util.regex.Pattern.compile(
			"protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)").matcher(content);
		while (varMatcher.find()) {
			varToType.put(varMatcher.group(2), varMatcher.group(1));
		}
		// Also map top-level fields
		java.util.regex.Matcher topVarMatcher = java.util.regex.Pattern.compile(
			"^    protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)", java.util.regex.Pattern.MULTILINE).matcher(content);
		while (topVarMatcher.find()) {
			varToType.put(topVarMatcher.group(2), topVarMatcher.group(1));
		}
		// Also map qualified type declarations from postProcessFixUndeclaredTypedVariables:
		// "protected EncType.InnerType varName = encVar.new InnerType();"
		// or "protected EncType.MidType.InnerType varName = encVar.new MidType().new InnerType();"
		java.util.regex.Matcher qualifiedVarMatcher = java.util.regex.Pattern.compile(
			"^    protected\\s+(?:\\w+Type\\.)+?(\\w+Type)\\s+(\\w+)\\s*=", java.util.regex.Pattern.MULTILINE).matcher(content);
		while (qualifiedVarMatcher.find()) {
			varToType.put(qualifiedVarMatcher.group(2), qualifiedVarMatcher.group(1));
		}

		// Step 3: Scan code for field accesses: "varA.varB.fieldName"
		// and check if fieldName exists in the type of varB
		final java.util.Map<String, java.util.Map<String, String>> fieldsToAdd = new java.util.LinkedHashMap<>();
		// Map: className -> { fieldName -> type }

		java.util.regex.Pattern accessPattern = java.util.regex.Pattern.compile(
			"([a-z][a-z0-9_]*)\\s*\\.\\s*([a-z][a-z0-9_]*)\\s*\\.\\s*([a-z][a-z0-9_]*)");
		java.util.regex.Matcher accessMatcher = accessPattern.matcher(content);
		while (accessMatcher.find()) {
			String outerVar = accessMatcher.group(1);
			String innerVar = accessMatcher.group(2);
			String fieldName = accessMatcher.group(3);

			// Skip known non-field access patterns
			if ("java".equals(outerVar) || "io".equals(outerVar) || "System".equals(outerVar)) continue;
			if (fieldName.equals("length") || fieldName.equals("class") || fieldName.equals("intValue")
				|| fieldName.equals("trim") || fieldName.equals("replace") || fieldName.equals("equals")
				|| fieldName.equals("compareTo") || fieldName.equals("substring")) continue;

			// Skip method calls: if the match is followed by uppercase char or '(' then
			// the matched "fieldName" is actually a method name prefix (e.g., "get" from
			// "getWkSomeField()"). The regex stops at uppercase chars, so "getXxx" matches
			// only "get". Check if the next char after the match continues the identifier.
			int matchEnd = accessMatcher.end();
			if (matchEnd < content.length()) {
				char nextChar = content.charAt(matchEnd);
				if (nextChar == '(' || Character.isUpperCase(nextChar)) continue;
			}
			// Also skip if the fieldName starts with "get" or "set" (method prefix fragments)
			if (fieldName.startsWith("get") || fieldName.startsWith("set")) continue;

			// Resolve the type of innerVar
			String innerType = varToType.get(innerVar);
			if (innerType == null) {
				// innerVar is not a typed variable. Check if it's an undeclared field in the outer type.
				// Pattern: outerVar.innerVar.innerVar_data → innerVar is a group that should be a String field
				String outerType = varToType.get(outerVar);
				if (outerType != null && fieldName.startsWith(innerVar + "_")) {
					java.util.Set<String> outerFields = classFields.get(outerType);
					if (outerFields != null && !outerFields.contains(innerVar)) {
						if (!fieldsToAdd.containsKey(outerType)) {
							fieldsToAdd.put(outerType, new java.util.LinkedHashMap<>());
						}
						java.util.Map<String, String> outerTypeFields = fieldsToAdd.get(outerType);
						if (!outerTypeFields.containsKey(innerVar)) {
							outerTypeFields.put(innerVar, "String");
						}
					}
				}
				continue;
			}

			// Check if fieldName exists in that inner type
			java.util.Set<String> existingFields = classFields.get(innerType);
			if (existingFields == null) continue;

			// Verify that innerVar is actually a field of the outer type.
			// varToType resolves globally, so it may find STROUTType from a DIFFERENT class.
			// If innerVar is NOT a field of the outer type, add it as a String field.
			String outerType = varToType.get(outerVar);
			if (outerType != null) {
				java.util.Set<String> outerFields = classFields.get(outerType);
				if (outerFields != null && !outerFields.contains(innerVar)) {
					if (!fieldsToAdd.containsKey(outerType)) {
						fieldsToAdd.put(outerType, new java.util.LinkedHashMap<>());
					}
					java.util.Map<String, String> otf = fieldsToAdd.get(outerType);
					if (!otf.containsKey(innerVar)) {
						otf.put(innerVar, "String");
					}
				}
			}

			if (existingFields.contains(fieldName)) continue;

			// Field is missing! Determine the type from usage context.
			// Look at the surrounding line to infer type.
			int lineStart = content.lastIndexOf('\n', accessMatcher.start()) + 1;
			int lineEnd = content.indexOf('\n', accessMatcher.end());
			if (lineEnd < 0) lineEnd = content.length();
			String lineStr = content.substring(lineStart, lineEnd);

			String fieldType = "String";
			// Fields ending with _int or _sig are always numeric REDEFINES fields
			if (fieldName.endsWith("_int") || fieldName.endsWith("_sig")) {
				fieldType = "BigDecimal";
			}
			// Determine the type by checking if the field is used in numeric context.
			// moveNumericToNumeric(field, ...) -> field is BigDecimal (as source arg)
			// moveNumericToAlphanumeric(field, ...) -> field is BigDecimal (as source arg, but result is String)
			// field = CobolMove.moveNumericToNumeric(...) -> field is BigDecimal (receives numeric)
			// field = CobolMove.moveAlphanumericToNumeric(...) -> field is BigDecimal (receives numeric)
			// field = CobolMove.moveNumericToAlphanumeric(...) -> field is String (receives alphanumeric)
			String fullFieldPath = outerVar + "." + innerVar + "." + fieldName;
			if (lineStr.contains("moveNumericToNumeric(" + fullFieldPath)
				|| lineStr.contains("moveNumericToAlphanumeric(" + fullFieldPath)
				|| lineStr.contains(fullFieldPath + " = CobolMove.moveNumericToNumeric(")
				|| lineStr.contains(fullFieldPath + " = CobolMove.moveAlphanumericToNumeric(")
				|| lineStr.contains(fullFieldPath + ".intValue()")
				|| lineStr.contains(fullFieldPath + ".add(")
				|| lineStr.contains(fullFieldPath + ".subtract(")) {
				fieldType = "BigDecimal";
			}
			// .toPlainString() or .intValue() -> field is BigDecimal
			if (lineStr.contains(fieldName + ".toPlainString()") || lineStr.contains(fieldName + ".intValue()")) {
				fieldType = "BigDecimal";
			}

			if (!fieldsToAdd.containsKey(innerType)) {
				fieldsToAdd.put(innerType, new java.util.LinkedHashMap<>());
			}
			java.util.Map<String, String> typeFields = fieldsToAdd.get(innerType);
			if (!typeFields.containsKey(fieldName)) {
				typeFields.put(fieldName, fieldType);
			}
			// If any usage says BigDecimal, upgrade
			if ("BigDecimal".equals(fieldType)) {
				typeFields.put(fieldName, "BigDecimal");
			}
		}

		// Step 3a2: Detect 2-level field accesses on top-level typed variables.
		// Pattern: topLevelVar.fieldName where topLevelVar is a declared typed variable
		// and fieldName doesn't exist in the type class.
		java.util.regex.Pattern access2Pattern = java.util.regex.Pattern.compile(
			"(?:^\\s+|[(,] ?)([a-z][a-z0-9_]*)\\s*\\.\\s*([a-z][a-z0-9_]*)\\s*[^.a-z0-9_]", java.util.regex.Pattern.MULTILINE);
		java.util.regex.Matcher access2Matcher = access2Pattern.matcher(content);
		while (access2Matcher.find()) {
			String varName = access2Matcher.group(1);
			String fieldName = access2Matcher.group(2);
			// Skip builtins
			if ("java".equals(varName) || "io".equals(varName)) continue;
			if (fieldName.equals("length") || fieldName.equals("class") || fieldName.equals("intValue")
				|| fieldName.equals("trim") || fieldName.equals("replace") || fieldName.equals("equals")
				|| fieldName.equals("compareTo") || fieldName.equals("substring") || fieldName.equals("add")
				|| fieldName.equals("subtract") || fieldName.equals("multiply") || fieldName.equals("divide")
				|| fieldName.equals("toPlainString") || fieldName.equals("toString") || fieldName.equals("size")
				|| fieldName.equals("get") || fieldName.equals("charAt") || fieldName.equals("isEmpty")
				|| fieldName.equals("indexOf") || fieldName.equals("contains") || fieldName.equals("negate")
				|| fieldName.startsWith("get") || fieldName.startsWith("set")) continue;

			// Check if varName is a typed variable with a class that exists
			String varType = varToType.get(varName);
			if (varType == null) continue;
			java.util.Set<String> typeFields = classFields.get(varType);
			if (typeFields == null) continue;
			if (typeFields.contains(fieldName)) continue;

			// Check if fieldName is a sub-variable of THIS specific type (not just any global variable).
			// Previous check "varToType.containsKey(fieldName)" was too broad — it rejected
			// field additions when a global variable with the same name existed, even though
			// the current type didn't have that field. E.g., "ambiente" as a top-level working-storage
			// variable would prevent adding "ambiente" as a DDS field to R_hapilogsfl_oType.
			// Now we only skip if the field is an inner typed variable WITHIN this class.
			boolean isSubVarOfThisClass = false;
			String classHeader = "public class " + varType + " {";
			int classHeaderIdx = content.indexOf(classHeader);
			if (classHeaderIdx >= 0) {
				// Scan forward from class header to find inner typed variable declarations
				int scanEnd = Math.min(classHeaderIdx + 5000, content.length());
				String classBody = content.substring(classHeaderIdx, scanEnd);
				// Check if fieldName is declared as a typed inner variable: "protected XxxType fieldName = new XxxType();"
				if (classBody.contains("protected") && classBody.contains(fieldName + " = new ")) {
					isSubVarOfThisClass = true;
				}
			}
			if (isSubVarOfThisClass) continue;

			// Field is missing - determine type from context
			int lineStart2 = content.lastIndexOf('\n', access2Matcher.start()) + 1;
			int lineEnd2 = content.indexOf('\n', access2Matcher.end());
			if (lineEnd2 < 0) lineEnd2 = content.length();
			String lineStr2 = content.substring(lineStart2, lineEnd2);

			String fieldType2 = "String";
			if (fieldName.endsWith("_int") || fieldName.endsWith("_sig")) {
				fieldType2 = "BigDecimal";
			}
			// Determine type from how the field is used on this line:
			// - field = CobolMove.moveNumericToNumeric(...) → BigDecimal (returns BigDecimal)
			// - field = CobolMove.moveAlphanumericToNumeric(...) → BigDecimal (returns BigDecimal)
			// - field.intValue() / field.add() / field.subtract() → BigDecimal (numeric methods)
			// - moveNumericToNumeric(field, ...) → BigDecimal (field as numeric source)
			// NOTE: moveNumericToAlphanumeric returns String, so "field = moveNumericToAlphanumeric(...)"
			// means field is String, NOT BigDecimal. Only infer BigDecimal if field is the SOURCE arg.
			boolean isAssignTarget = lineStr2.contains("." + fieldName + " = CobolMove.");
			if (lineStr2.contains(fieldName + " = CobolMove.moveNumericToNumeric(")
				|| lineStr2.contains(fieldName + " = CobolMove.moveAlphanumericToNumeric(")
				|| lineStr2.contains(fieldName + ".intValue()") || lineStr2.contains(fieldName + ".add(")
				|| lineStr2.contains(fieldName + ".subtract(") || lineStr2.contains(fieldName + ".toPlainString()")) {
				fieldType2 = "BigDecimal";
			}
			// moveNumericToNumeric(field, ...) means field is used as numeric source → BigDecimal
			if (lineStr2.contains("moveNumericToNumeric(" + varName + "." + fieldName)) {
				fieldType2 = "BigDecimal";
			}
			// moveNumericToAlphanumeric(field, ...) means field is used as numeric source → BigDecimal
			// BUT field = moveNumericToAlphanumeric(...) means field receives String → String
			if (!isAssignTarget && lineStr2.contains("moveNumericToAlphanumeric(" + varName + "." + fieldName)) {
				fieldType2 = "BigDecimal";
			}

			if (!fieldsToAdd.containsKey(varType)) {
				fieldsToAdd.put(varType, new java.util.LinkedHashMap<>());
			}
			java.util.Map<String, String> tf2 = fieldsToAdd.get(varType);
			if (!tf2.containsKey(fieldName)) {
				tf2.put(fieldName, fieldType2);
			}
			if ("BigDecimal".equals(fieldType2)) {
				tf2.put(fieldName, "BigDecimal");
			}
		}

		// Step 3b: Also detect field accesses via safeGet(path, idx).fieldName
		// Map List variable names to their element types
		final java.util.Map<String, String> listVarToType = new java.util.HashMap<>();
		java.util.regex.Matcher listMatcher = java.util.regex.Pattern.compile(
			"protected\\s+List<(\\w+Type)>\\s+(\\w+)\\s*=").matcher(content);
		while (listMatcher.find()) {
			listVarToType.put(listMatcher.group(2), listMatcher.group(1));
		}

		// Scan for safeGet(path.listVar, idx).fieldName.fieldName2... patterns
		// Use line-by-line scan to handle nested parentheses in safeGet arguments
		// Enhanced: follow multi-level field chains after safeGet
		{
		String[] sgLines = content.split("\n");
		for (String sgLine : sgLines) {
			int sgIdx = 0;
			while (true) {
				int safeGetPos = sgLine.indexOf("safeGet(", sgIdx);
				if (safeGetPos < 0) break;
				int openParen = safeGetPos + 7; // position of '('
				// Find matching closing paren
				int sgDepth = 0;
				int closeParen = -1;
				for (int ci = openParen; ci < sgLine.length(); ci++) {
					if (sgLine.charAt(ci) == '(') sgDepth++;
					else if (sgLine.charAt(ci) == ')') {
						sgDepth--;
						if (sgDepth == 0) { closeParen = ci; break; }
					}
				}
				if (closeParen < 0) { sgIdx = safeGetPos + 1; continue; }

				// Extract the first argument (before the comma)
				String argsStr = sgLine.substring(openParen + 1, closeParen);
				int commaPos = -1;
				int cDepth = 0;
				for (int ci = 0; ci < argsStr.length(); ci++) {
					if (argsStr.charAt(ci) == '(') cDepth++;
					else if (argsStr.charAt(ci) == ')') cDepth--;
					else if (argsStr.charAt(ci) == ',' && cDepth == 0) { commaPos = ci; break; }
				}
				if (commaPos < 0) { sgIdx = safeGetPos + 1; continue; }

				String firstArg = argsStr.substring(0, commaPos).trim();
				// Extract listVar: last dotted segment of firstArg
				int lastDot = firstArg.lastIndexOf('.');
				String listVar = lastDot >= 0 ? firstArg.substring(lastDot + 1) : firstArg;

				// Extract the full field chain after safeGet: .field1.field2.field3...
				java.util.List<String> fieldChain = new java.util.ArrayList<>();
				int chainPos = closeParen + 1;
				while (chainPos < sgLine.length() && sgLine.charAt(chainPos) == '.') {
					int fStart = chainPos + 1;
					int fEnd = fStart;
					while (fEnd < sgLine.length() && (Character.isLetterOrDigit(sgLine.charAt(fEnd)) || sgLine.charAt(fEnd) == '_')) {
						fEnd++;
					}
					if (fEnd > fStart) {
						String fn = sgLine.substring(fStart, fEnd);
						// Stop at known method-like accesses
						if (fn.startsWith("get") || fn.startsWith("set")
							|| fn.equals("intValue") || fn.equals("length")
							|| fn.equals("trim") || fn.equals("substring")
							|| fn.equals("compareTo") || fn.equals("equals")
							|| fn.equals("toPlainString") || fn.equals("add")
							|| fn.equals("subtract") || fn.equals("multiply")
							|| fn.equals("divide") || fn.equals("abs")
							|| fn.equals("negate") || fn.equals("toString")) {
							break;
						}
						fieldChain.add(fn);
						chainPos = fEnd;
					} else {
						break;
					}
				}

				if (fieldChain.isEmpty()) { sgIdx = safeGetPos + 1; continue; }

				// Walk the field chain: start from the element type of the list
				String currentType = listVarToType.get(listVar);
				if (currentType == null) { sgIdx = safeGetPos + 1; continue; }

				for (int fi = 0; fi < fieldChain.size(); fi++) {
					String fieldName = fieldChain.get(fi);

					java.util.Set<String> sgExistingFields = classFields.get(currentType);
					if (sgExistingFields == null) break;

					if (!sgExistingFields.contains(fieldName)) {
						// Field is missing — add it
						String sgFieldType = "String";
						if (fieldName.endsWith("_int") || fieldName.endsWith("_sig")) {
							sgFieldType = "BigDecimal";
						}
						if (sgLine.contains("moveNumericToNumeric(") && sgLine.contains("." + fieldName + ",")) {
							sgFieldType = "BigDecimal";
						}
						if (sgLine.contains("moveNumericToAlphanumeric(") && sgLine.contains("." + fieldName + ",")) {
							sgFieldType = "BigDecimal";
						}
						if (sgLine.contains("." + fieldName + ".intValue()") || sgLine.contains("." + fieldName + ".add(")
							|| sgLine.contains("." + fieldName + ".subtract(") || sgLine.contains("." + fieldName + ".compareTo(")) {
							sgFieldType = "BigDecimal";
						}
						if (sgLine.contains(".add(" + fieldName + ")") || sgLine.contains(".add(") && sgLine.contains("." + fieldName + ")")) {
							sgFieldType = "BigDecimal";
						}
						if (sgLine.contains("moveNumericToNumericEdited(") && sgLine.contains("." + fieldName + ")")) {
							if (fieldName.endsWith("_int") || fieldName.endsWith("s")) {
								sgFieldType = "BigDecimal";
							}
						}
						if (sgLine.contains("moveStringToGroup(") && sgLine.contains("." + fieldName + ")")) {
							sgFieldType = "String";
						}

						if (!fieldsToAdd.containsKey(currentType)) {
							fieldsToAdd.put(currentType, new java.util.LinkedHashMap<>());
						}
						java.util.Map<String, String> sgTypeFields = fieldsToAdd.get(currentType);
						if (!sgTypeFields.containsKey(fieldName)) {
							sgTypeFields.put(fieldName, sgFieldType);
						}
						if ("BigDecimal".equals(sgFieldType)) {
							sgTypeFields.put(fieldName, "BigDecimal");
						}
						break; // Can't follow the chain further since the field is missing
					}

					// Field exists. Follow to the next level: resolve its type.
					String nextType = varToType.get(fieldName);
					if (nextType == null) {
						// Try to find the type from protected XxxType fieldName = new XxxType() declarations
						// within the current type's class body
						java.util.regex.Pattern fieldTypePattern = java.util.regex.Pattern.compile(
							"protected\\s+(\\w+Type)\\s+" + java.util.regex.Pattern.quote(fieldName) + "\\s*=");
						java.util.regex.Matcher fieldTypeMatcher = fieldTypePattern.matcher(content);
						while (fieldTypeMatcher.find()) {
							nextType = fieldTypeMatcher.group(1);
						}
					}
					if (nextType == null) break; // Can't resolve deeper type
					currentType = nextType;
				}

				sgIdx = closeParen + 1;
			}
		}
		} // end safeGet scan block

		if (fieldsToAdd.isEmpty()) {
			return content;
		}

		// Step 4: Insert the missing field declarations into EVERY occurrence of each inner class.
		// When the same inner class name (e.g., Rm00010im1_oType) appears inside multiple
		// parent classes (e.g., inside both R_rm00010im1_oType.Rm00010im1_oType for _out and _inp),
		// the previous code only tracked the LAST occurrence via classLastFieldLine map.
		// Fix: collect ALL closing brace positions for each class name, then inject into all.
		lines = content.split("\n", -1);

		// Map: className -> list of closing brace line indices (one per occurrence)
		java.util.Map<String, java.util.List<Integer>> allClassClosingLines = new java.util.LinkedHashMap<>();
		java.util.Map<String, String> classIndent = new java.util.LinkedHashMap<>();

		classStack.clear();
		braceStack.clear();
		currentBraceDepth = 0;

		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			java.util.regex.Matcher cm = java.util.regex.Pattern.compile(
				"^(\\s+)public class (\\w+Type)\\s+\\{").matcher(line);
			if (cm.find()) {
				classStack.push(cm.group(2));
				braceStack.push(currentBraceDepth);
				classIndent.put(cm.group(2), cm.group(1));
			}

			boolean inStr = false;
			boolean inCmt = false;
			for (int ci = 0; ci < line.length(); ci++) {
				char c = line.charAt(ci);
				if (inCmt) break;
				if (c == '"' && !inStr) inStr = true;
				else if (c == '"' && inStr) inStr = false;
				else if (c == '/' && ci + 1 < line.length() && line.charAt(ci + 1) == '/' && !inStr) inCmt = true;
				else if (!inStr) {
					if (c == '{') currentBraceDepth++;
					else if (c == '}') {
						currentBraceDepth--;
						if (!braceStack.isEmpty() && currentBraceDepth == braceStack.peek()) {
							String cls = classStack.pop();
							braceStack.pop();
							if (fieldsToAdd.containsKey(cls)) {
								allClassClosingLines.computeIfAbsent(cls, k -> new java.util.ArrayList<>()).add(i);
							}
						}
					}
				}
			}
		}

		// Collect ALL insertion points, sorted in reverse order for safe insertion
		java.util.List<int[]> insertions = new java.util.ArrayList<>(); // {lineIndex, classNameIndex}
		java.util.List<String> classNameList = new java.util.ArrayList<>();
		for (java.util.Map.Entry<String, java.util.List<Integer>> entry : allClassClosingLines.entrySet()) {
			String className = entry.getKey();
			int classIdx = classNameList.size();
			classNameList.add(className);
			for (int lineIdx : entry.getValue()) {
				insertions.add(new int[]{lineIdx, classIdx});
			}
		}
		// Sort in reverse order by line index so insertions don't shift later indices
		insertions.sort((a, b) -> Integer.compare(b[0], a[0]));

		int totalAdded = 0;
		java.util.List<String> linesList = new java.util.ArrayList<>(java.util.Arrays.asList(lines));

		for (int[] ins : insertions) {
			int closingLine = ins[0];
			String className = classNameList.get(ins[1]);

			java.util.Map<String, String> missingFields = fieldsToAdd.get(className);
			String indent = classIndent.getOrDefault(className, "        ");
			String fieldIndent = indent + "    ";

			// Check which fields are already declared in THIS specific class body.
			// Scan backwards from the closing brace to find the class opening, then check fields.
			java.util.Set<String> existingInThisCopy = new java.util.LinkedHashSet<>();
			int braceCount = 1;
			for (int li = closingLine - 1; li >= 0 && braceCount > 0; li--) {
				String scanLine = linesList.get(li);
				for (int ci = scanLine.length() - 1; ci >= 0; ci--) {
					char c = scanLine.charAt(ci);
					if (c == '}') braceCount++;
					else if (c == '{') braceCount--;
				}
				java.util.regex.Matcher afm = java.util.regex.Pattern.compile(
					"^\\s+protected\\s+\\S+(?:<\\S+>)?\\s+(\\w+)\\s*[=;]").matcher(scanLine);
				if (afm.find()) {
					existingInThisCopy.add(afm.group(1));
				}
			}

			StringBuilder newFields = new StringBuilder();
			for (java.util.Map.Entry<String, String> entry : missingFields.entrySet()) {
				String fName = entry.getKey();
				String fType = entry.getValue();
				if (existingInThisCopy.contains(fName)) continue; // Already in this copy
				if ("BigDecimal".equals(fType)) {
					newFields.append(fieldIndent).append("protected BigDecimal ").append(fName)
						.append(" = BigDecimal.ZERO; // post-fix: added missing DDS field\n");
				} else {
					newFields.append(fieldIndent).append("protected String ").append(fName)
						.append(" = \"\"; // post-fix: added missing DDS field\n");
				}
				totalAdded++;
			}

			if (newFields.length() > 0) {
				linesList.add(closingLine, newFields.toString().stripTrailing());
			}
		}

		if (totalAdded > 0) {
			System.err.println("POST-PROCESS: added " + totalAdded + " missing field declaration(s) to " + insertions.size() + " inner class occurrence(s)");
			return String.join("\n", linesList);
		}

		return content;
	}

	/**
	 * Fixes parent-child class name collisions.
	 * When COBOL has structures like 01 ARR-R-REC containing 02 ARR-REC OCCURS,
	 * both names map to the same Java class name (e.g., Arr_ta0015Type) because the
	 * name transformation strips the R- prefix. Similarly, DDS structures can have
	 * 01 LIN-CP containing 05 LIN_CP where hyphen/underscore produce the same name.
	 * Java does not allow an inner class to have the same name as its enclosing class.
	 * This method renames the inner class by appending "Fmt" suffix (following existing
	 * ProLeap convention) and updates all references within the parent class scope.
	 */
	protected String postProcessFixDuplicateParentChildClasses(String content) {
		final String[] lines = content.split("\n", -1);
		final java.util.regex.Pattern classPattern = java.util.regex.Pattern.compile(
				"^(\\s+)public class (\\w+Type) \\{");

		// Step 1: Find all class declarations with their line index and indent
		final java.util.List<int[]> classDecls = new java.util.ArrayList<>(); // [lineIndex, indent]
		final java.util.List<String> classNamesList = new java.util.ArrayList<>();
		for (int i = 0; i < lines.length; i++) {
			final java.util.regex.Matcher m = classPattern.matcher(lines[i]);
			if (m.find()) {
				classDecls.add(new int[]{i, m.group(1).length()});
				classNamesList.add(m.group(2));
			}
		}

		// Step 2: Find parent-child collisions (inner class at indent N+4 with same name as outer at N)
		// Collisions: [outerLineIndex, innerLineIndex, indent of outer]
		final java.util.List<int[]> collisions = new java.util.ArrayList<>();
		for (int i = 0; i < classDecls.size(); i++) {
			final int outerLine = classDecls.get(i)[0];
			final int outerIndent = classDecls.get(i)[1];
			final String outerName = classNamesList.get(i);
			// Look for the next class declaration that is a direct child (indent + 4)
			for (int j = i + 1; j < classDecls.size(); j++) {
				final int innerIndent = classDecls.get(j)[1];
				if (innerIndent <= outerIndent) break; // Left the scope of the outer class
				if (innerIndent == outerIndent + 4 && classNamesList.get(j).equals(outerName)) {
					collisions.add(new int[]{outerLine, classDecls.get(j)[0], outerIndent});
					break;
				}
			}
		}

		if (collisions.isEmpty()) {
			return content;
		}

		// Step 3: For each collision, find the scope of the outer class and rename within it
		// Process in reverse order so line indices remain valid
		final StringBuilder sb = new StringBuilder(content);
		for (int ci = collisions.size() - 1; ci >= 0; ci--) {
			final int outerLineIdx = collisions.get(ci)[0];
			final int outerIndent = collisions.get(ci)[2];

			// Find the class name from the outer class line
			final java.util.regex.Matcher m = classPattern.matcher(lines[outerLineIdx]);
			if (!m.find()) continue;
			final String className = m.group(2);
			final String newClassName = className.replace("Type", "FmtType");

			// Find the character position of the outer class opening brace
			int outerStart = 0;
			for (int i = 0; i < outerLineIdx; i++) {
				outerStart += lines[i].length() + 1; // +1 for \n
			}
			// Find the opening brace on the outer class line
			int bracePos = sb.indexOf("{", outerStart);
			if (bracePos < 0) continue;

			// Find the matching closing brace for the outer class
			int braceCount = 0;
			int outerEnd = -1;
			for (int i = bracePos; i < sb.length(); i++) {
				final char c = sb.charAt(i);
				if (c == '{') braceCount++;
				else if (c == '}') {
					braceCount--;
					if (braceCount == 0) {
						outerEnd = i;
						break;
					}
				}
			}
			if (outerEnd < 0) continue;

			// Step 4: Within the outer class body (bracePos+1 to outerEnd),
			// replace all word-boundary occurrences of className with newClassName
			final String bodyContent = sb.substring(bracePos + 1, outerEnd);
			final String newBody = bodyContent.replaceAll("\\b" + java.util.regex.Pattern.quote(className) + "\\b", newClassName);
			if (!newBody.equals(bodyContent)) {
				sb.replace(bracePos + 1, outerEnd, newBody);
			}
		}

		return sb.toString();
	}

	/**
	 * Removes duplicate inner class definitions from generated Java.
	 * When COBOL source has duplicate 01-level declarations (e.g., two 01 R-JRNL210101),
	 * the transformer generates duplicate inner classes. This method removes subsequent
	 * duplicates, keeping only the first occurrence.
	 * Also removes duplicate variable declarations for the same class type.
	 */
	protected String postProcessRemoveDuplicateClasses(String content) {
		// Find all inner class definitions at the TOP-LEVEL only (exactly 4 spaces indent).
		// Inner classes nested deeper (8+, 12+ spaces) can legitimately have the same name
		// when they appear inside different parent classes.
		final java.util.regex.Pattern classPattern = java.util.regex.Pattern.compile(
				"(?m)^    public class (\\w+Type) \\{");
		final java.util.regex.Matcher classMatcher = classPattern.matcher(content);
		final java.util.Set<String> seenClasses = new java.util.HashSet<>();
		final java.util.Set<String> duplicateClasses = new java.util.HashSet<>();
		while (classMatcher.find()) {
			final String className = classMatcher.group(1);
			if (!seenClasses.add(className)) {
				duplicateClasses.add(className);
			}
		}
		if (duplicateClasses.isEmpty()) {
			return content;
		}
		// For each duplicate, remove the second occurrence (class definition + variable declaration)
		for (final String className : duplicateClasses) {
			// Find occurrences that are at exactly 4-space indent (top-level inner classes)
			final String marker = "\n    public class " + className + " {";
			int firstIdx = content.indexOf(marker);
			if (firstIdx < 0) continue;
			firstIdx++; // skip the \n
			int secondIdx = content.indexOf(marker, firstIdx + 1);
			if (secondIdx < 0) continue;
			secondIdx++; // skip the \n
			// Find the matching closing brace for the second class definition
			int braceCount = 0;
			int classEnd = -1;
			for (int i = secondIdx; i < content.length(); i++) {
				char c = content.charAt(i);
				if (c == '{') braceCount++;
				else if (c == '}') {
					braceCount--;
					if (braceCount == 0) {
						classEnd = i + 1;
						break;
					}
				}
			}
			if (classEnd < 0) continue;
			// Find the variable declaration after the class: "protected ClassType varName = new ClassType();"
			// Look in the next 200 chars after classEnd
			int searchEnd = Math.min(classEnd + 200, content.length());
			String afterClass = content.substring(classEnd, searchEnd);
			int varDeclEnd = classEnd;
			java.util.regex.Matcher varMatcher = java.util.regex.Pattern.compile(
					"\\s*protected " + className + " \\w+ = new " + className + "\\(\\);[^\n]*\n").matcher(afterClass);
			if (varMatcher.find() && varMatcher.start() < 50) {
				varDeclEnd = classEnd + varMatcher.end();
			}
			// Remove from start of second class line to end of variable declaration
			int lineStart = content.lastIndexOf('\n', secondIdx);
			if (lineStart < 0) lineStart = 0; else lineStart++;
			content = content.substring(0, lineStart) + content.substring(varDeclEnd);
		}
		return content;
	}

	/**
	 * Removes duplicate getter/setter method definitions.
	 * When a REDEFINES group has multiple fields with the same name at different positions
	 * (e.g., two WK-FIL01 fields), duplicate getter/setter methods are generated.
	 * This method keeps only the last occurrence (the one at the correct position).
	 */
	protected String postProcessRemoveDuplicateMethods(String content) {
		// Find all getter methods: "public Type getXxx_Yyy("
		final java.util.regex.Pattern methodPattern = java.util.regex.Pattern.compile(
				"(?m)^    public (?:String|BigDecimal|void) ((?:get|set)\\w+)\\(");
		final java.util.regex.Matcher methodMatcher = methodPattern.matcher(content);
		final java.util.Map<String, Integer> methodCount = new java.util.LinkedHashMap<>();
		while (methodMatcher.find()) {
			final String methodName = methodMatcher.group(1);
			methodCount.merge(methodName, 1, Integer::sum);
		}
		// Remove duplicates — keep the FIRST occurrence, remove subsequent ones
		for (final java.util.Map.Entry<String, Integer> entry : methodCount.entrySet()) {
			if (entry.getValue() <= 1) continue;
			final String methodName = entry.getKey();
			// Find first occurrence
			int firstIdx = content.indexOf(" " + methodName + "(");
			if (firstIdx < 0) continue;
			// Find and remove subsequent occurrences
			int searchFrom = firstIdx + 1;
			while (true) {
				int nextIdx = content.indexOf(" " + methodName + "(", searchFrom);
				if (nextIdx < 0) break;
				// Find the start of the line
				int lineStart = content.lastIndexOf('\n', nextIdx);
				if (lineStart < 0) lineStart = 0; else lineStart++;
				// Find the end of the line
				int lineEnd = content.indexOf('\n', nextIdx);
				if (lineEnd < 0) lineEnd = content.length(); else lineEnd++;
				content = content.substring(0, lineStart) + content.substring(lineEnd);
				// Don't advance searchFrom — the content shifted
			}
		}
		return content;
	}

	@Override
	public List<File> transformCode(final String cobolCode, final String compilationUnitName, final String packageName,
			final CobolParserParams params) throws IOException {
		final Program program = new CobolParserRunnerImpl().analyzeCode(cobolCode, compilationUnitName, params);
		return transform(program, packageName);
	}

	@Override
	public List<File> transformFile(final File cobolFile, final String packageName, final CobolParserParams params)
			throws IOException {
		// Extract XML PARSE block metadata from the original COBOL source BEFORE
		// preprocessing comments them out. The post-processor will use this info
		// to inject xmlParse() calls into the generated Java.
		xmlParseInfoList.clear();
		extractXmlParseInfo(cobolFile);

		// Preprocess: convert COBOL debugging lines (D/d in column 7) to comment lines.
		// AS/400 programs may have debug lines that contain arbitrary text (e.g., Portuguese
		// comments with non-ASCII characters). The parser treats DEBUG lines as normal code,
		// causing parse errors. Since we never migrate debug-only code, converting them to
		// comments is safe and prevents the parser from trying to parse their content.
		File processedFile = preprocessDebugLines(cobolFile);
		// Preprocess: expand SQL TYPE IS CLOB/BLOB/DBCLOB into group with -DATA/-LENGTH subfields
		final File processedFile0b = preprocessSqlTypeLob(processedFile);
		if (processedFile0b != processedFile && processedFile != cobolFile) {
			processedFile.delete();
		}
		processedFile = processedFile0b;
		// Preprocess: strip LDTAARA FOR/LIBRARY clauses that the parser can't handle
		final File processedFile1b = preprocessLdtaaraStatements(processedFile);
		if (processedFile1b != processedFile && processedFile != cobolFile) {
			processedFile.delete();
		}
		processedFile = processedFile1b;
		// Preprocess: add missing periods on group-level data items (AS/400 leniency)
		final File processedFile2 = preprocessMissingGroupPeriods(processedFile);
		if (processedFile2 != processedFile && processedFile != cobolFile) {
			processedFile.delete();
		}
		processedFile = processedFile2;
		// Preprocess: inject missing period after FD entries with no clauses and no period,
		// when followed by COPY or 01 level (IBM ILE COBOL implicit FD termination)
		final File processedFile2b = preprocessMissingFdPeriod(processedFile);
		if (processedFile2b != processedFile && processedFile != cobolFile) {
			processedFile.delete();
		}
		processedFile = processedFile2b;
		// Preprocess: expand IBM ILE COBOL TYPE/TYPEDEF clauses that the parser can't handle
		final File processedFile3 = preprocessTypeDefExpansion(processedFile, params);
		if (processedFile3 != processedFile && processedFile != cobolFile) {
			processedFile.delete();
		}
		processedFile = processedFile3;
		// Preprocess: add missing END-PERFORM for inline PERFORM UNTIL inside IF blocks
		final File processedFile3a = preprocessMissingEndPerform(processedFile);
		if (processedFile3a != processedFile && processedFile != cobolFile) {
			processedFile.delete();
		}
		processedFile = processedFile3a;
		// Preprocess: merge string literal continuations where prev line closes the literal
		final File processedFile3b = preprocessStringContinuationMerge(processedFile);
		if (processedFile3b != processedFile && processedFile != cobolFile) {
			processedFile.delete();
		}
		processedFile = processedFile3b;
		// Preprocess: replace non-ASCII characters that the ANTLR lexer can't handle
		final File processedFile4 = preprocessNonAsciiCharacters(processedFile);
		if (processedFile4 != processedFile && processedFile != cobolFile) {
			processedFile.delete();
		}
		processedFile = processedFile4;
		// Preprocess: rename LABEL and ADDRESS when used as data field names.
		// These are COBOL reserved words in the ANTLR grammar but are legitimately used
		// as field names in DDS copybooks and SQL columns on AS/400.
		final File processedFile5 = preprocessReservedWordFieldNames(processedFile);
		if (processedFile5 != processedFile && processedFile != cobolFile) {
			processedFile.delete();
		}
		processedFile = processedFile5;
		// Preprocess: fix USING BY without REFERENCE/VALUE/CONTENT.
		// IBM ILE COBOL allows "CALL pgm USING BY var" where BY implies REFERENCE.
		// The grammar requires explicit REFERENCE/VALUE/CONTENT after BY.
		final File processedFile6 = preprocessUsingByReference(processedFile);
		if (processedFile6 != processedFile && processedFile != cobolFile) {
			processedFile.delete();
		}
		processedFile = processedFile6;
		// Preprocess: remove duplicate/stray END-EXEC outside EXEC SQL blocks.
		// AS/400 tolerates a stray END-EXEC (e.g., END-EXEC. after an already-closed
		// EXEC SQL ... END-EXEC block), but the ProLeap preprocessor parser rejects it.
		final File processedFile7 = preprocessDuplicateEndExec(processedFile);
		if (processedFile7 != processedFile && processedFile != cobolFile) {
			processedFile.delete();
		}
		processedFile = processedFile7;
		// Preprocess: fix PROCEDURE DIVISION condition continuation lines that start with
		// a number followed by OR/AND/THEN. The parser's injectImplicitPeriodBeforeParagraphs
		// misidentifies these as data level entries and injects a period on the previous line,
		// breaking the condition. Fix: move trailing OR/AND from the previous line to the
		// start of the continuation line so it no longer begins with a bare number.
		final File processedFile8 = preprocessConditionContinuationLines(processedFile);
		if (processedFile8 != processedFile && processedFile != cobolFile) {
			processedFile.delete();
		}
		processedFile = processedFile8;
		// Preprocess: move COBOL scope terminators (END-IF, END-PERFORM, etc.) from Area A
		// to Area B in the PROCEDURE DIVISION. The parser's injectImplicitPeriodBeforeParagraphs
		// heuristic treats any word followed by a period in Area A as a paragraph name and
		// injects a period on the previous line. When END-IF. is in Area A (column 8), it gets
		// misidentified as a paragraph, causing a spurious period that terminates the enclosing
		// IF block prematurely, leaving END-IF orphaned. Fix: indent these keywords to Area B.
		final File processedFile9 = preprocessScopeTerminatorsInAreaA(processedFile);
		if (processedFile9 != processedFile && processedFile != cobolFile) {
			processedFile.delete();
		}
		processedFile = processedFile9;
		// Preprocess: convert bare PERFORM statements (PERFORM with no procedure name,
		// no UNTIL/TIMES/VARYING, and no matching END-PERFORM) to CONTINUE.
		// IBM ILE COBOL treats a bare PERFORM as a no-op, but the ProLeap parser
		// interprets it as the start of an inline PERFORM block and consumes subsequent
		// statements until it expects END-PERFORM, causing parse errors when it encounters
		// END-IF or other scope terminators first.
		final File processedFile10 = preprocessBarePerform(processedFile);
		if (processedFile10 != processedFile && processedFile != cobolFile) {
			processedFile.delete();
		}
		processedFile = processedFile10;
		// Preprocess: insert missing TO keyword in MOVE statements.
		// IBM ILE COBOL on AS/400 tolerates "MOVE ZERO LK-STATUS" (without TO),
		// but the ProLeap ANTLR grammar requires "MOVE ZERO TO LK-STATUS".
		final File processedFile10b = preprocessMissingMoveTo(processedFile);
		if (processedFile10b != processedFile && processedFile != cobolFile) {
			processedFile.delete();
		}
		processedFile = processedFile10b;
		// Preprocess: comment out XML PARSE...END-XML blocks.
		// The parser JAR cannot handle XML PARSE despite having the grammar rule defined —
		// it fails with "no viable alternative at input 'XML PARSE'".
		// The post-processor (postProcessXmlParse) will inject the correct xmlParse() calls
		// using metadata extracted by extractXmlParseInfo() before preprocessing.
		final File processedFile_xml = preprocessXmlParse(processedFile);
		if (processedFile_xml != processedFile && processedFile != cobolFile) {
			processedFile.delete();
		}
		processedFile = processedFile_xml;
		// Preprocess: comment out COPY statements for copybooks that cannot be found
		// in any configured directory. The parser throws CobolPreprocessorException for
		// missing copybooks, crashing the entire generation. Instead, we replace the
		// COPY line with a COBOL comment so the program can still be parsed and
		// generated (it will likely fail at compilation with undeclared fields, which
		// is more informative than a preprocessor crash).
		final File processedFile11 = preprocessMissingCopyBooks(processedFile, params);
		if (processedFile11 != processedFile && processedFile != cobolFile) {
			processedFile.delete();
		}
		processedFile = processedFile11;
		final Program program = new CobolParserRunnerImpl().analyzeFile(processedFile, params);
		// Clean up temp file if one was created
		if (processedFile != cobolFile) {
			processedFile.delete();
		}
		return transform(program, packageName);
	}

	/**
	 * Preprocesses COBOL source to add missing periods on group-level data description
	 * entries. IBM ILE COBOL on AS/400 is lenient about periods on group items, but the
	 * ProLeap ANTLR grammar requires DOT_FS to end each entry. Without the period, the
	 * parser merges the group entry with its first child, losing the child field.
	 *
	 * Pattern detected: a line with only a level number (01-49) and a data name, no PIC
	 * clause, no period, followed by a line with a subordinate level number.
	 */
	private File preprocessMissingGroupPeriods(final File cobolFile) throws IOException {
		final List<String> lines = Files.readAllLines(cobolFile.toPath());
		boolean modified = false;

		for (int i = 0; i < lines.size() - 1; i++) {
			final String line = lines.get(i);
			final String trimmed = line.trim();

			// Skip comment lines (column 7 = *)
			if (line.length() >= 7 && line.charAt(6) == '*') {
				continue;
			}

			// Stop processing at PROCEDURE DIVISION — group-level data items
			// only appear in DATA DIVISION. Without this guard, PROCEDURE DIVISION
			// condition continuations like "2 OR" / "3 OR" get misidentified as
			// level-number data-name entries and have spurious periods appended.
			final String contentForDivCheck = line.length() > 7 ? line.substring(7).trim().toUpperCase()
					: line.trim().toUpperCase();
			if (contentForDivCheck.startsWith("PROCEDURE DIVISION")) {
				break;
			}

			// Match: level-number data-name (no period, no PIC, no other clauses)
			// e.g. "       01  WK-RECORD" or "           05  MY-GROUP"
			if (!trimmed.endsWith(".") && trimmed.matches("\\d{1,2}\\s+[A-Z][A-Z0-9-]*\\s*")) {
				// Check next non-comment, non-blank line has a higher level number
				for (int j = i + 1; j < lines.size(); j++) {
					final String nextLine = lines.get(j);
					final String nextTrimmed = nextLine.trim();
					if (nextTrimmed.isEmpty()) continue;
					if (nextLine.length() >= 7 && nextLine.charAt(6) == '*') continue;

					// Check if next line starts with a level number
					final java.util.regex.Matcher m = java.util.regex.Pattern
							.compile("^(\\d{1,2})\\s+").matcher(nextTrimmed);
					if (m.find()) {
						final int currentLevel = Integer.parseInt(trimmed.split("\\s+")[0]);
						final int nextLevel = Integer.parseInt(m.group(1));
						if (nextLevel > currentLevel && nextLevel <= 49) {
							// Add period at end of this group-level line
							lines.set(i, line + ".");
							modified = true;
						}
					}
					break;
				}
			}
		}

		if (!modified) {
			return cobolFile;
		}

		final File tempDir = Files.createTempDirectory("proleap_pp").toFile();
		final File tempFile = new File(tempDir, cobolFile.getName());
		Files.write(tempFile.toPath(), lines);
		return tempFile;
	}

	/**
	 * Preprocesses COBOL source to inject a missing period after FD (file description)
	 * entries that have no clauses and no period, when followed by a COPY statement or
	 * a level-01 record description.
	 *
	 * In IBM ILE COBOL, an FD statement is implicitly terminated when a record description
	 * (01-level entry) is encountered. But the ProLeap ANTLR grammar requires an explicit
	 * period (DOT_FS) to terminate the FD entry before the record description.
	 *
	 * Pattern detected:
	 *   FD filename          (no period, no FD clauses like BLOCK/RECORD/LABEL)
	 *      COPY copyname ... (expands to 01 record-name. ...)
	 *
	 * Fix: append a period to the FD line so the parser sees:
	 *   FD filename.
	 *      COPY copyname ...
	 *
	 * This also handles the case where FD is directly followed by 01 without COPY.
	 */
	private File preprocessMissingFdPeriod(final File cobolFile) throws IOException {
		final List<String> lines = Files.readAllLines(cobolFile.toPath());
		boolean modified = false;

		for (int i = 0; i < lines.size() - 1; i++) {
			final String line = lines.get(i);

			// Skip comment lines
			if (line.length() >= 7 && line.charAt(6) == '*') {
				continue;
			}

			final String trimmed = line.trim();

			// Match: FD <filename> with no period and no FD clauses on same line
			// FD must be at the start (after spaces), followed by just a filename
			if (!trimmed.matches("(?i)FD\\s+[A-Z][A-Z0-9-]*\\s*")) {
				continue;
			}

			// Already has a period — skip
			if (trimmed.endsWith(".")) {
				continue;
			}

			// Look at the next non-blank, non-comment line
			for (int j = i + 1; j < lines.size(); j++) {
				final String nextLine = lines.get(j);
				final String nextTrimmed = nextLine.trim();
				if (nextTrimmed.isEmpty()) continue;
				if (nextLine.length() >= 7 && nextLine.charAt(6) == '*') continue;

				// Check if the next meaningful line is a COPY statement or 01 level entry
				if (nextTrimmed.matches("(?i)COPY\\s+.*") || nextTrimmed.matches("01\\s+.*")) {
					lines.set(i, line + ".");
					modified = true;
					LOG.info("Preprocessor: injected implicit period after FD at line {} before {} at line {}",
							i + 1, nextTrimmed.split("\\s+")[0], j + 1);
				}
				// If it's an FD clause (BLOCK, RECORD, LABEL, etc.), don't inject period
				break;
			}
		}

		if (!modified) {
			return cobolFile;
		}

		LOG.info("Preprocessor: fixed missing FD period(s) in {}", cobolFile.getName());
		final File tempDir = Files.createTempDirectory("proleap_pp").toFile();
		final File tempFile = new File(tempDir, cobolFile.getName());
		Files.write(tempFile.toPath(), lines);
		return tempFile;
	}

	/**
	 * Preprocesses COBOL source to handle IBM ILE ACCEPT/DISPLAY LDTAARA statements.
	 * Strips the unsupported FOR "name" LIBRARY "lib" clause, converting:
	 *   ACCEPT field FROM LDTAARA FOR "name" LIBRARY "lib"
	 * to:
	 *   ACCEPT field FROM ENVIRONMENT-VALUE
	 * and:
	 *   DISPLAY field UPON LDTAARA FOR "name" LIBRARY "lib"
	 * to:
	 *   DISPLAY field
	 * Returns the original file if no LDTAARA patterns found.
	 */
	/**
	 * Preprocesses COBOL source to expand IBM ILE COBOL TYPE/TYPEDEF clauses.
	 * The ProLeap ANTLR grammar's dataTypeClause only handles time types (SHORT-DATE,
	 * LONG-DATE, etc.) and LOB types, not user-defined TYPE references.
	 *
	 * This preprocessor:
	 * 1. Finds COPY statements that introduce TYPEDEFs (via REPLACING ... BY ... IS TYPEDEF)
	 * 2. Resolves the copybook from the configured copybook directories
	 * 3. Applies REPLACING to get the TYPEDEF definition
	 * 4. Collects the subordinate field definitions from the TYPEDEF
	 * 5. Expands "TYPE typename" references inline with the TYPEDEF's fields
	 *
	 * Example input:
	 *   COPY QTQICONV OF QSYSINC-QCBLLESRC REPLACING
	 *     ==01 QTQCODE== BY ==01 QTQCODE IS TYPEDEF==.
	 *   01  FROM-CODE.
	 *       05 FROM-ENVIRONMENT           TYPE QTQCODE.
	 *
	 * Example output (after expansion):
	 *   COPY QTQICONV OF QSYSINC-QCBLLESRC REPLACING
	 *     ==01 QTQCODE== BY ==01 QTQCODE IS TYPEDEF==.
	 *   01  FROM-CODE.
	 *       05 FROM-ENVIRONMENT.
	 *           10 CCSID              PIC 9(9) COMP-4 VALUE 0.
	 *           10 CVTALT             PIC 9(9) COMP-4 VALUE 0.
	 *           ...
	 */
	private File preprocessTypeDefExpansion(final File cobolFile, final CobolParserParams params) throws IOException {
		final List<String> lines = Files.readAllLines(cobolFile.toPath());
		final String content = new String(Files.readAllBytes(cobolFile.toPath()));

		// Quick check: does this file contain TYPE references (not TYPE IS ... time types)?
		if (!content.contains(" TYPE ")) {
			return cobolFile;
		}

		// Step 1: Find COPY statements that introduce TYPEDEFs
		// Pattern: COPY copyname [OF libname] REPLACING ==...== BY ==... IS TYPEDEF==.
		// This may span multiple lines
		final java.util.regex.Pattern copyTypedefPattern = java.util.regex.Pattern.compile(
				"(?i)COPY\\s+(\\S+)(?:\\s+OF\\s+\\S+)?\\s+REPLACING\\s*\\n?" +
				"\\s*==(.*?)==\\s+BY\\s+==(.*?IS\\s+TYPEDEF)==\\s*\\.",
				java.util.regex.Pattern.DOTALL);

		// Also handle single-line pattern
		final java.util.regex.Pattern copyTypedefPatternSingle = java.util.regex.Pattern.compile(
				"(?i)COPY\\s+(\\S+)(?:\\s+OF\\s+\\S+)?\\s+REPLACING\\s+==(.*?)==\\s+BY\\s+==(.*?IS\\s+TYPEDEF)==\\s*\\.");

		// Collect TYPEDEF structures from copybooks
		// Map: typeName -> list of subordinate field lines
		final java.util.Map<String, List<String>> typedefFields = new java.util.LinkedHashMap<>();
		final java.util.Map<String, Integer> typedefBaseLevels = new java.util.LinkedHashMap<>();

		// Search for COPY ... REPLACING ... TYPEDEF patterns in the source
		java.util.regex.Matcher cm = copyTypedefPattern.matcher(content);
		while (cm.find()) {
			final String copyName = cm.group(1).trim();
			final String fromPattern = cm.group(2).trim();
			final String toPattern = cm.group(3).trim();
			collectTypedefFromCopybook(copyName, fromPattern, toPattern, params, typedefFields, typedefBaseLevels);
		}
		if (typedefFields.isEmpty()) {
			cm = copyTypedefPatternSingle.matcher(content);
			while (cm.find()) {
				final String copyName = cm.group(1).trim();
				final String fromPattern = cm.group(2).trim();
				final String toPattern = cm.group(3).trim();
				collectTypedefFromCopybook(copyName, fromPattern, toPattern, params, typedefFields, typedefBaseLevels);
			}
		}

		if (typedefFields.isEmpty()) {
			return cobolFile;
		}

		// Step 2: Find and expand TYPE references
		// Pattern: NN fieldname TYPE typename.
		// where NN is a level number and typename matches a known TYPEDEF
		boolean modified = false;
		final List<String> outputLines = new ArrayList<>();

		for (int i = 0; i < lines.size(); i++) {
			final String line = lines.get(i);
			final String trimmed = line.trim();

			// Skip comment lines
			if (line.length() >= 7 && line.charAt(6) == '*') {
				outputLines.add(line);
				continue;
			}

			// Match: level-number fieldname TYPE typename.
			final java.util.regex.Matcher typeMatcher = java.util.regex.Pattern.compile(
					"(?i)^\\s*(\\d{1,2})\\s+(\\S+)\\s+TYPE\\s+(\\S+?)\\s*\\.\\s*$").matcher(trimmed);

			if (typeMatcher.matches()) {
				final int fieldLevel = Integer.parseInt(typeMatcher.group(1));
				final String fieldName = typeMatcher.group(2);
				final String typeName = typeMatcher.group(3).toUpperCase();

				if (typedefFields.containsKey(typeName)) {
					// Expand the TYPE reference
					final List<String> tdFields = typedefFields.get(typeName);
					final int tdBaseLevel = typedefBaseLevels.get(typeName);

					// Calculate the indentation from the original line
					final int indent = line.indexOf(trimmed.charAt(0));
					final String baseIndent = indent > 0 ? line.substring(0, indent) : "           ";

					// Emit the field as a group (without TYPE clause)
					outputLines.add(baseIndent + String.format("%02d %s.", fieldLevel, fieldName));

					// Emit subordinate fields with adjusted level numbers
					// The TYPEDEF's 05-level fields become subordinate to fieldLevel
					for (final String tdField : tdFields) {
						final String tdTrimmed = tdField.trim();
						final java.util.regex.Matcher levelMatcher = java.util.regex.Pattern.compile(
								"^(\\d{1,2})\\s+(.*)").matcher(tdTrimmed);
						if (levelMatcher.matches()) {
							final int origLevel = Integer.parseInt(levelMatcher.group(1));
							final String restOfLine = levelMatcher.group(2);

							// Calculate new level: subordinate fields should be at
							// fieldLevel + (origLevel - tdBaseLevel)
							// e.g., if fieldLevel=05 and TYPEDEF has 05-level fields,
							// they become 10-level fields
							int newLevel = fieldLevel + (origLevel - tdBaseLevel) + 5;
							if (newLevel > 49) newLevel = 49;

							// Use deeper indentation for subordinate fields
							final String subIndent = baseIndent + "    ";
							outputLines.add(subIndent + String.format("%02d %s", newLevel, restOfLine));
						} else {
							// Non-level line (shouldn't happen in well-formed COBOL)
							outputLines.add("      " + tdField);
						}
					}

					modified = true;
					LOG.info("Expanded TYPE {} for field {} at level {}", typeName, fieldName, fieldLevel);
					continue;
				}
			}

			outputLines.add(line);
		}

		if (!modified) {
			return cobolFile;
		}

		final File tempDir = Files.createTempDirectory("proleap_pp").toFile();
		final File tempFile = new File(tempDir, cobolFile.getName());
		Files.write(tempFile.toPath(), outputLines);
		return tempFile;
	}

	/**
	 * Resolves a copybook from the configured directories and extracts TYPEDEF field definitions.
	 */
	private void collectTypedefFromCopybook(final String copyName, final String fromPattern,
			final String toPattern, final CobolParserParams params,
			final java.util.Map<String, List<String>> typedefFields,
			final java.util.Map<String, Integer> typedefBaseLevels) {

		// Find the copybook file
		File copybookFile = null;
		for (final File dir : params.getCopyBookDirectories()) {
			// Try various file extensions
			for (final String ext : new String[]{".cbl", ".CBL", ".cpy", ".CPY", ""}) {
				final File candidate = new File(dir, copyName + ext);
				if (candidate.exists()) {
					copybookFile = candidate;
					break;
				}
			}
			if (copybookFile != null) break;
		}

		if (copybookFile == null) {
			LOG.warn("Could not find copybook {} for TYPEDEF expansion", copyName);
			return;
		}

		try {
			final List<String> copybookLines = Files.readAllLines(copybookFile.toPath());

			// Apply the REPLACING: fromPattern -> toPattern
			// The fromPattern is typically "01 QTQCODE" and toPattern is "01 QTQCODE IS TYPEDEF"
			// We need to find the 01-level entry in the copybook and extract its subordinate fields
			final String cleanFrom = fromPattern.replaceAll("\\s+", " ").trim();
			final String cleanTo = toPattern.replaceAll("\\s+", " ").trim();

			// Extract the type name from the toPattern (e.g., "01 QTQCODE IS TYPEDEF" -> "QTQCODE")
			final java.util.regex.Matcher typeNameMatcher = java.util.regex.Pattern.compile(
					"(?i)(\\d{1,2})\\s+(\\S+)\\s+IS\\s+TYPEDEF").matcher(cleanTo);
			if (!typeNameMatcher.matches()) {
				LOG.warn("Could not parse TYPEDEF name from REPLACING pattern: {}", cleanTo);
				return;
			}
			final String typeName = typeNameMatcher.group(2).toUpperCase();
			final int typedefLevel = Integer.parseInt(typeNameMatcher.group(1));

			// Find the matching entry in the copybook and collect subordinate fields
			boolean inTypedef = false;
			int baseLevel = -1;
			final List<String> fields = new ArrayList<>();

			for (final String cbLine : copybookLines) {
				final String cbTrimmed = cbLine.trim();

				// Skip comment lines
				if (cbLine.length() >= 7 && cbLine.charAt(6) == '*') {
					continue;
				}
				if (cbTrimmed.isEmpty() || cbTrimmed.startsWith("*")) {
					continue;
				}

				// Match the group header (the fromPattern entry)
				if (!inTypedef) {
					// Check if this line matches the copybook's group entry
					// fromPattern like "01 QTQCODE" — match level + name
					final java.util.regex.Matcher headerMatcher = java.util.regex.Pattern.compile(
							"(?i)^\\s*(\\d{1,2})\\s+" + java.util.regex.Pattern.quote(typeName) + "\\b").matcher(cbTrimmed);
					if (headerMatcher.find()) {
						inTypedef = true;
						baseLevel = Integer.parseInt(headerMatcher.group(1));
						continue;
					}
				} else {
					// Check if we've left the typedef (same or lower level number)
					final java.util.regex.Matcher levelMatcher = java.util.regex.Pattern.compile(
							"^\\s*(\\d{1,2})\\s+").matcher(cbTrimmed);
					if (levelMatcher.find()) {
						final int currentLevel = Integer.parseInt(levelMatcher.group(1));
						if (currentLevel <= baseLevel) {
							// End of typedef
							break;
						}
						// Strip trailing period if present and re-add it
						String fieldLine = cbTrimmed;
						if (fieldLine.endsWith(".")) {
							fieldLine = fieldLine.substring(0, fieldLine.length() - 1).trim();
						}
						fields.add(fieldLine + ".");
					}
				}
			}

			if (!fields.isEmpty()) {
				// Determine the base level of subordinate fields (typically 05)
				final java.util.regex.Matcher firstFieldLevel = java.util.regex.Pattern.compile(
						"^(\\d{1,2})\\s+").matcher(fields.get(0).trim());
				int subBaseLevel = 5; // default
				if (firstFieldLevel.find()) {
					subBaseLevel = Integer.parseInt(firstFieldLevel.group(1));
				}

				typedefFields.put(typeName, fields);
				typedefBaseLevels.put(typeName, subBaseLevel);
				LOG.info("Collected TYPEDEF {} with {} fields from copybook", typeName, fields.size());
			} else {
				LOG.warn("Found no subordinate fields for TYPEDEF {} in copybook {}", typeName, copybookFile);
			}
		} catch (final IOException e) {
			LOG.warn("Error reading copybook {} for TYPEDEF expansion: {}", copybookFile, e.getMessage());
		}
	}

	/**
	 * Preprocesses COBOL source to expand IBM ILE COBOL SQL TYPE IS CLOB/BLOB/DBCLOB
	 * declarations into a group structure with -LENGTH and -DATA subfields.
	 *
	 * In IBM ILE COBOL, {@code 01 MY-XML SQL TYPE IS CLOB(200000).} expands to:
	 * <pre>
	 *   01 MY-XML.
	 *     49 MY-XML-LENGTH PIC 9(09) COMP-5.
	 *     49 MY-XML-DATA PIC X(200000).
	 * </pre>
	 *
	 * Without this expansion, the parser sees the entry as having no PIC clause,
	 * which causes it to be typed as Object, and references to -DATA/-LENGTH fail.
	 */
	private static final Pattern SQL_TYPE_LOB_PATTERN = Pattern.compile(
			"(?i)(\\s*)(\\d{1,2})\\s+(\\S+)\\s+SQL\\s+TYPE\\s+IS\\s+(CLOB|BLOB|DBCLOB)\\s*\\(\\s*(\\d+)\\s*\\)\\s*\\.\\s*");

	private File preprocessSqlTypeLob(final File cobolFile) throws IOException {
		final List<String> lines = Files.readAllLines(cobolFile.toPath());
		boolean modified = false;
		final List<String> result = new ArrayList<>();

		for (final String line : lines) {
			final String trimmed = line.trim();
			// Skip comment lines
			if (line.length() >= 7 && line.charAt(6) == '*') {
				result.add(line);
				continue;
			}
			// Skip lines starting with *> (free-form comments)
			if (trimmed.startsWith("*>")) {
				result.add(line);
				continue;
			}
			final Matcher m = SQL_TYPE_LOB_PATTERN.matcher(line);
			if (m.matches()) {
				final String indent = m.group(1);
				final String level = m.group(2);
				final String name = m.group(3);
				final String lobType = m.group(4).toUpperCase();
				final String size = m.group(5);

				// Emit group header: same level, same name, no PIC
				result.add(indent + level + " " + name + ".");
				// Emit -LENGTH subfield at level 49 (IBM convention)
				result.add(indent + "  49 " + name + "-LENGTH PIC 9(09) COMP-5.");
				// Emit -DATA subfield at level 49
				if ("DBCLOB".equals(lobType)) {
					result.add(indent + "  49 " + name + "-DATA PIC G(" + size + ") USAGE DISPLAY-1.");
				} else {
					result.add(indent + "  49 " + name + "-DATA PIC X(" + size + ").");
				}
				modified = true;
			} else {
				result.add(line);
			}
		}

		if (!modified) {
			return cobolFile;
		}

		final File tempDir = Files.createTempDirectory("proleap_pp").toFile();
		final File tempFile = new File(tempDir, cobolFile.getName());
		Files.write(tempFile.toPath(), result);
		return tempFile;
	}

	private File preprocessLdtaaraStatements(final File cobolFile) throws IOException {
		String content = new String(Files.readAllBytes(cobolFile.toPath()));
		if (!content.contains("LDTAARA")) {
			return cobolFile;
		}
		// Replace ACCEPT ... FROM LDTAARA FOR "..." LIBRARY "..." with CONTINUE (data areas not available in Java)
		content = content.replaceAll("(?i)ACCEPT\\s+\\S+\\s+FROM\\s+LDTAARA[^.]*\\.",
				"CONTINUE.");
		// Replace DISPLAY ... UPON LDTAARA FOR "..." LIBRARY "..." with CONTINUE
		content = content.replaceAll("(?i)DISPLAY\\s+\\S+\\s+UPON\\s+LDTAARA[^.]*\\.",
				"CONTINUE.");
		// Use a temp directory but keep the original filename (class name derives from it)
		final File tempDir = Files.createTempDirectory("proleap_pp").toFile();
		final File tempFile = new File(tempDir, cobolFile.getName());
		Files.write(tempFile.toPath(), content.getBytes());
		return tempFile;
	}

	/**
	 * Preprocesses COBOL source to add missing END-PERFORM.
	 * In IBM ILE COBOL, a PERFORM UNTIL inside an IF block can be implicitly terminated
	 * by the END-IF. The ProLeap parser requires explicit END-PERFORM.
	 * This detects PERFORM UNTIL blocks without matching END-PERFORM and inserts them
	 * before the enclosing END-IF.
	 * Only applies when the total count of PERFORM UNTIL exceeds the count of END-PERFORM.
	 */
	private File preprocessMissingEndPerform(final File cobolFile) throws IOException {
		final List<String> lines = Files.readAllLines(cobolFile.toPath());

		// First: count inline PERFORM UNTIL and END-PERFORM globally
		int performUntilCount = 0;
		int endPerformCount = 0;
		for (String line : lines) {
			if (line.length() >= 7 && line.charAt(6) == '*') continue;
			// Strip sequence area (columns 1-6) to get the actual COBOL program text.
			// Lines may have alphanumeric sequence markers (e.g. "BR02") in columns 1-6
			// which are not stripped by trim() and would prevent regex matching.
			String programText = line.length() > 7 ? line.substring(7) : line;
			String trimmed = programText.trim();
			String upper = trimmed.toUpperCase();
			if (upper.matches("PERFORM\\s+UNTIL\\s+.*")) performUntilCount++;
			String noPeriod = upper.endsWith(".") ? upper.substring(0, upper.length() - 1).trim() : upper;
			if (noPeriod.equals("END-PERFORM")) endPerformCount++;
		}

		if (performUntilCount <= endPerformCount) {
			return cobolFile; // All balanced, nothing to do
		}

		int missing = performUntilCount - endPerformCount;
		LOG.info("Preprocessor: detected {} PERFORM UNTIL without END-PERFORM in {}", missing, cobolFile.getName());

		// Strategy: walk through tracking IF/PERFORM nesting.
		// When we hit an END-IF and the top of the block stack is PERFORM,
		// insert END-PERFORM before the END-IF.
		boolean modified = false;
		java.util.Deque<String> blockStack = new java.util.ArrayDeque<>();

		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.length() >= 7 && line.charAt(6) == '*') continue;

			// Strip sequence area (columns 1-6) before matching
			String programText = line.length() > 7 ? line.substring(7) : line;
			String trimmed = programText.trim();
			String upper = trimmed.toUpperCase();
			String noPeriod = upper.endsWith(".") ? upper.substring(0, upper.length() - 1).trim() : upper;

			// Detect IF ... THEN
			if (upper.matches("IF\\s+.*THEN\\s*\\.?") || upper.matches("IF\\s+.*THEN")) {
				blockStack.push("IF");
			}
			// Detect EVALUATE
			else if (upper.startsWith("EVALUATE ")) {
				blockStack.push("EVALUATE");
			}
			// Detect inline PERFORM UNTIL
			else if (upper.matches("PERFORM\\s+UNTIL\\s+.*")) {
				blockStack.push("PERFORM");
			}
			// Detect END-EVALUATE
			else if (noPeriod.equals("END-EVALUATE")) {
				while (!blockStack.isEmpty() && !"EVALUATE".equals(blockStack.peek())) {
					blockStack.pop();
				}
				if (!blockStack.isEmpty()) blockStack.pop();
			}
			// Detect END-PERFORM
			else if (noPeriod.equals("END-PERFORM")) {
				while (!blockStack.isEmpty() && !"PERFORM".equals(blockStack.peek())) {
					blockStack.pop();
				}
				if (!blockStack.isEmpty()) blockStack.pop();
			}
			// Detect END-IF
			else if (noPeriod.equals("END-IF")) {
				// Check if there's an unmatched PERFORM before this END-IF
				if (!blockStack.isEmpty() && "PERFORM".equals(blockStack.peek())) {
					// Insert END-PERFORM before this END-IF
					String indent = "";
					for (int ci = 0; ci < line.length(); ci++) {
						if (line.charAt(ci) != ' ') { indent = line.substring(0, ci); break; }
					}
					String endPerform = indent + "   END-PERFORM";
					lines.add(i, endPerform);
					blockStack.pop();
					modified = true;
					missing--;
					i++; // Skip the just-inserted line
					if (missing <= 0) break;
				}
				// Pop the IF
				while (!blockStack.isEmpty() && !"IF".equals(blockStack.peek())) {
					blockStack.pop();
				}
				if (!blockStack.isEmpty()) blockStack.pop();
			}
			// Period-terminated statements reset the block stack partially
			// (paragraphs end with period)
			else if (upper.endsWith(".") && !upper.startsWith("PERFORM") && !upper.startsWith("IF")
					&& !upper.startsWith("EVALUATE") && !upper.startsWith("END-")) {
				// A period at the paragraph level implicitly closes all open blocks
				// But only if we're at a "low" nesting level. Be conservative.
			}
		}

		if (!modified) return cobolFile;

		LOG.info("Preprocessor: added missing END-PERFORM in {}", cobolFile.getName());
		final File tempDir = Files.createTempDirectory("proleap_pp").toFile();
		final File tempFile = new File(tempDir, cobolFile.getName());
		Files.write(tempFile.toPath(), lines);
		return tempFile;
	}

	/**
	 * Preprocesses COBOL source to fix string literal continuations that the parser can't handle.
	 * Pattern: A line with a complete string literal (closing ") followed by a continuation line
	 * (column 7 = '-') that starts a new string literal. This creates two adjacent literals.
	 * Fix: Remove the closing quote from the previous line so the continuation becomes a
	 * standard literal continuation (which the parser handles natively).
	 * e.g.: MOVE "some text"
	 *       -      "more"     TO FIELD
	 * Becomes: MOVE "some text
	 *       -      "more"     TO FIELD
	 * The COBOL preprocessor then merges this into: "some textmore"
	 */
	private File preprocessStringContinuationMerge(final File cobolFile) throws IOException {
		final List<String> lines = Files.readAllLines(cobolFile.toPath());
		boolean modified = false;

		for (int i = 1; i < lines.size(); i++) {
			String line = lines.get(i);
			// Check if this is a continuation line (column 7 = '-')
			if (line.length() < 7 || line.charAt(6) != '-') continue;

			String prevLine = lines.get(i - 1);
			// Skip comment lines
			if (prevLine.length() >= 7 && prevLine.charAt(6) == '*') continue;

			// Check if previous line has a closed string literal (even quote count)
			// Only look at columns 8-72 (Area A + Area B)
			String prevContent = prevLine.length() > 72 ? prevLine.substring(0, 72) : prevLine;
			int prevLastQuote = prevContent.lastIndexOf('"');
			if (prevLastQuote < 7) continue;

			// Count quotes in prevContent to see if the string is closed
			int quoteCount = 0;
			for (int ci = 7; ci < prevContent.length(); ci++) {
				if (prevContent.charAt(ci) == '"') quoteCount++;
			}
			if (quoteCount % 2 != 0) continue; // String NOT closed — standard continuation, parser handles it

			// String IS closed on previous line. Now check if continuation starts a new string.
			String contArea = line.length() > 7 ? line.substring(7) : "";
			String contTrimmed = contArea.trim();
			if (!contTrimmed.startsWith("\"")) continue;

			// Fix: Remove the closing quote from previous line.
			// This makes the literal "open" so the continuation line's opening quote
			// continues the literal naturally (standard COBOL continuation).
			String comment = prevLine.length() > 72 ? prevLine.substring(72) : "";
			String newPrevLine = prevContent.substring(0, prevLastQuote);
			// Pad to column 72 to ensure fixed-format compliance
			while (newPrevLine.length() < 72) {
				newPrevLine += " ";
			}
			if (!comment.isEmpty()) {
				newPrevLine = newPrevLine.substring(0, 72) + comment;
			}

			lines.set(i - 1, newPrevLine);
			modified = true;
		}

		if (!modified) return cobolFile;

		LOG.info("Preprocessor: fixed {} string literal continuation(s) in {}", lines.size(), cobolFile.getName());
		final File tempDir = Files.createTempDirectory("proleap_pp").toFile();
		final File tempFile = new File(tempDir, cobolFile.getName());
		Files.write(tempFile.toPath(), lines);
		return tempFile;
	}

	/**
	 * Preprocesses COBOL source to replace non-ASCII characters with ASCII equivalents.
	 * The ANTLR COBOL lexer only handles ASCII, so characters like 'é', 'ã', 'ç' etc.
	 * in string literals or comments cause "token recognition error".
	 * This replaces non-ASCII characters with their closest ASCII equivalents.
	 */
	private File preprocessNonAsciiCharacters(final File cobolFile) throws IOException {
		byte[] rawBytes = Files.readAllBytes(cobolFile.toPath());
		String content = new String(rawBytes);
		boolean hasNonAscii = false;
		for (int i = 0; i < content.length(); i++) {
			if (content.charAt(i) > 127) {
				hasNonAscii = true;
				break;
			}
		}
		if (!hasNonAscii) return cobolFile;

		StringBuilder sb = new StringBuilder(content.length());
		for (int i = 0; i < content.length(); i++) {
			char c = content.charAt(i);
			if (c <= 127) {
				sb.append(c);
			} else {
				// Replace with closest ASCII equivalent
				switch (c) {
					case '\u00e0': case '\u00e1': case '\u00e2': case '\u00e3': case '\u00e4': case '\u00e5': sb.append('a'); break;
					case '\u00c0': case '\u00c1': case '\u00c2': case '\u00c3': case '\u00c4': case '\u00c5': sb.append('A'); break;
					case '\u00e8': case '\u00e9': case '\u00ea': case '\u00eb': sb.append('e'); break;
					case '\u00c8': case '\u00c9': case '\u00ca': case '\u00cb': sb.append('E'); break;
					case '\u00ec': case '\u00ed': case '\u00ee': case '\u00ef': sb.append('i'); break;
					case '\u00cc': case '\u00cd': case '\u00ce': case '\u00cf': sb.append('I'); break;
					case '\u00f2': case '\u00f3': case '\u00f4': case '\u00f5': case '\u00f6': sb.append('o'); break;
					case '\u00d2': case '\u00d3': case '\u00d4': case '\u00d5': case '\u00d6': sb.append('O'); break;
					case '\u00f9': case '\u00fa': case '\u00fb': case '\u00fc': sb.append('u'); break;
					case '\u00d9': case '\u00da': case '\u00db': case '\u00dc': sb.append('U'); break;
					case '\u00e7': sb.append('c'); break;
					case '\u00c7': sb.append('C'); break;
					case '\u00f1': sb.append('n'); break;
					case '\u00d1': sb.append('N'); break;
					case '\u00df': sb.append("ss"); break;
					case '\u00ba': sb.append('o'); break;
					case '\u00aa': sb.append('a'); break;
					default: sb.append('?'); break;
				}
			}
		}
		LOG.info("Preprocessor: replaced non-ASCII characters in {}", cobolFile.getName());
		final File tempDir = Files.createTempDirectory("proleap_pp").toFile();
		final File tempFile = new File(tempDir, cobolFile.getName());
		Files.write(tempFile.toPath(), sb.toString().getBytes());
		return tempFile;
	}

	/**
	 * Preprocesses COBOL source to rename reserved words used as field names.
	 * LABEL and ADDRESS are COBOL reserved words in the ANTLR grammar but are
	 * legitimately used as field names in DDS copybooks and SQL columns on AS/400.
	 * This renames them to LABEL-FLD and ADDRESS-FLD to avoid parse errors,
	 * and the generated Java will use label_fld / address_fld instead.
	 */
	private File preprocessReservedWordFieldNames(final File cobolFile) throws IOException {
		final List<String> lines = Files.readAllLines(cobolFile.toPath());
		boolean modified = false;

		// Reserved words that need renaming when used as field names in data division.
		// IMPORTANT: Only match standalone LABEL/ADDRESS, not part of hyphenated names
		// like LABEL-DDS, W-LABEL, VATLABEL, etc. In COBOL, identifiers can contain hyphens.
		// Use negative lookahead/lookbehind to avoid matching inside larger identifiers.
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			// Skip comment lines (column 7 = *)
			if (line.length() >= 7 && line.charAt(6) == '*') continue;

			String original = line;

			// Pattern: level number followed by LABEL as standalone field name in data division.
			// Must NOT be followed by a hyphen (to exclude LABEL-DDS).
			// NOTE: ADDRESS is handled by renaming in copybook files only, not in the preprocessor,
			// because ADDRESS is also a COBOL special register (SET PTR TO ADDRESS OF xxx).
			line = line.replaceAll("(\\d{2}\\s+)LABEL(\\s+PIC|\\s*\\.)", "$1LABELFLD$2");

			// Pattern: standalone LABEL as a field reference with OF qualifier
			// Not part of hyphenated name
			line = line.replaceAll("(?<![A-Za-z0-9-])LABEL(?![A-Za-z0-9-])(\\s+OF\\s+)", "LABELFLD$1");

			// Only in SQL context: SELECT ... LABEL, or :LABEL in FETCH INTO
			// These are column names in SQL and variable names in host variable references.
			// We do NOT touch these since they're inside EXEC SQL where reserved words are fine.

			if (!line.equals(original)) {
				lines.set(i, line);
				modified = true;
			}
		}

		if (!modified) return cobolFile;

		LOG.info("Preprocessor: renamed LABEL/ADDRESS field names in {}", cobolFile.getName());
		final File tempDir = Files.createTempDirectory("proleap_pp").toFile();
		final File tempFile = new File(tempDir, cobolFile.getName());
		Files.write(tempFile.toPath(), String.join("\n", lines).getBytes());
		return tempFile;
	}

	/**
	 * Preprocesses COBOL source to fix USING BY without REFERENCE/VALUE/CONTENT.
	 * IBM ILE COBOL allows "CALL pgm USING BY var" or "PROCEDURE DIVISION USING BY var"
	 * where BY implies REFERENCE.
	 * The ANTLR grammar requires explicit REFERENCE/VALUE/CONTENT after BY.
	 * Strategy: only fix "USING BY" when it's in a USING context (preceded by USING on the same
	 * or previous lines), not MULTIPLY BY, SORT BY, etc.
	 */
	private File preprocessUsingByReference(final File cobolFile) throws IOException {
		final List<String> lines = Files.readAllLines(cobolFile.toPath());
		boolean modified = false;

		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.length() >= 7 && line.charAt(6) == '*') continue;

			String upper = line.toUpperCase();

			// Pattern 1: "USING BY varname" on the same line
			if (upper.contains("USING") && upper.contains("BY")) {
				String original = line;
				// Replace: USING BY xxx (not followed by REFERENCE/VALUE/CONTENT) -> USING BY REFERENCE xxx
				line = line.replaceAll("(USING\\s+)BY\\s+(?!REFERENCE|VALUE|CONTENT)([A-Z])", "$1BY REFERENCE $2");
				if (!line.equals(original)) {
					lines.set(i, line);
					modified = true;
				}
			}

			// Pattern 2: "BY varname" on a continuation line after USING
			// Look back to see if we're in a USING context
			if (!upper.contains("USING") && upper.contains("BY ")) {
				boolean isUsingContext = false;
				for (int j = i - 1; j >= Math.max(0, i - 10); j--) {
					String prevUpper = lines.get(j).toUpperCase();
					if (prevUpper.contains("USING")) {
						isUsingContext = true;
						break;
					}
					// Stop scanning if we hit a statement boundary
					if (prevUpper.trim().endsWith(".") || prevUpper.contains("END-CALL") ||
						prevUpper.contains("PERFORM ") || prevUpper.contains("EVALUATE ") ||
						prevUpper.contains("IF ") || prevUpper.contains("MOVE ") ||
						prevUpper.contains("MULTIPLY ") || prevUpper.contains("DIVIDE ") ||
						prevUpper.contains("SORT ")) {
						break;
					}
				}
				if (isUsingContext) {
					String original = line;
					// Only replace BY at the beginning of the continuation (after spaces)
					line = line.replaceAll("^(\\s+)BY\\s+(?!REFERENCE|VALUE|CONTENT)([A-Z])", "$1BY REFERENCE $2");
					if (!line.equals(original)) {
						lines.set(i, line);
						modified = true;
					}
				}
			}
		}

		if (!modified) return cobolFile;

		LOG.info("Preprocessor: fixed USING BY without REFERENCE in {}", cobolFile.getName());
		final File tempDir = Files.createTempDirectory("proleap_pp").toFile();
		final File tempFile = new File(tempDir, cobolFile.getName());
		Files.write(tempFile.toPath(), String.join("\n", lines).getBytes());
		return tempFile;
	}

	/**
	 * Converts DDS Object declarations to proper typed inner classes.
	 * When COPY DDSR-xxx resolves but produces no field definitions, the transformer generates:
	 *   protected Object r_xxx;
	 * But the code uses:
	 *   xxx.fieldName = ... or ... xxx.fieldName ...
	 * This post-processor:
	 * 1. Finds all "protected Object r_xxx;" declarations
	 * 2. Scans the code for field accesses on the corresponding variable (xxx.fieldName)
	 * 3. Generates inner classes with detected fields
	 * 4. Replaces Object declarations with properly typed declarations
	 */
	protected String postProcessObjectToTypedClass(String content) {
		// Step 1: Find all "protected Object r_xxx;" declarations at class level (4-space indent)
		final java.util.regex.Pattern objDeclPattern = java.util.regex.Pattern.compile(
			"^    protected Object ((?:r_|w_)[a-z0-9_]+);\\s*(//.*)?$", java.util.regex.Pattern.MULTILINE);
		java.util.regex.Matcher objMatcher = objDeclPattern.matcher(content);

		// Map: variable name (without prefix) -> full declaration line
		// Also map alternative names: w_ prefix stripped, _out -> _o suffix variant
		final java.util.Map<String, String> objectVars = new java.util.LinkedHashMap<>();
		while (objMatcher.find()) {
			String fullVarName = objMatcher.group(1); // e.g., "r_ha00014ct2_i" or "w_rb00003im3_i"
			// The variable used in code may drop the r_ or w_ prefix
			String varName = fullVarName;
			if (varName.startsWith("r_")) {
				varName = varName.substring(2);
			} else if (varName.startsWith("w_")) {
				varName = varName.substring(2);
			}
			objectVars.put(varName, fullVarName);
			// Also add _out -> _o suffix variant mapping
			if (varName.endsWith("_out")) {
				String altName = varName.substring(0, varName.length() - 4) + "_o";
				objectVars.put(altName, fullVarName);
			}
		}

		// Also match standalone Object variables without r_/w_ prefix
		// but ONLY those that have _out suffix (for _out -> _o mapping)
		java.util.regex.Pattern objDeclPattern2 = java.util.regex.Pattern.compile(
			"^    protected Object ([a-z][a-z0-9_]*_out);\\s*(//.*)?$", java.util.regex.Pattern.MULTILINE);
		java.util.regex.Matcher objMatcher2 = objDeclPattern2.matcher(content);
		while (objMatcher2.find()) {
			String fullVarName = objMatcher2.group(1);
			String varName = fullVarName;
			objectVars.put(varName, fullVarName);
			// Add _out -> _o variant
			String altName = varName.substring(0, varName.length() - 4) + "_o";
			objectVars.put(altName, fullVarName);
		}

		if (objectVars.isEmpty()) {
			return content;
		}

		// Step 2: For each Object variable, collect all field accesses
		// Pattern: varName.fieldName (where fieldName is a simple identifier)
		final java.util.Map<String, java.util.Map<String, String>> varFields = new java.util.LinkedHashMap<>();

		for (String varName : objectVars.keySet()) {
			java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
			String fullVarName = objectVars.get(varName);

			// Search for field accesses using BOTH bare name and r_ prefixed name
			// e.g., both "ha00014ct2_i.codori" and "r_ha00014ct2_i.codori"
			String[] searchNames = new String[] { varName, fullVarName };
			for (String searchName : searchNames) {
				String escapedVar = java.util.regex.Pattern.quote(searchName);
				java.util.regex.Pattern fieldAccessPattern = java.util.regex.Pattern.compile(
					"\\b" + escapedVar + "\\.([a-z][a-z0-9_]*)\\b");
				java.util.regex.Matcher fieldMatcher = fieldAccessPattern.matcher(content);
				while (fieldMatcher.find()) {
					String fieldName = fieldMatcher.group(1);
					// Skip method calls and known non-field accesses
					if ("compareTo".equals(fieldName) || "equals".equals(fieldName)
						|| "toString".equals(fieldName) || "intValue".equals(fieldName)
						|| "length".equals(fieldName) || "trim".equals(fieldName)
						|| "substring".equals(fieldName) || "replace".equals(fieldName)
						|| "size".equals(fieldName) || "get".equals(fieldName)
						|| "set".equals(fieldName) || "add".equals(fieldName)) {
						continue;
					}

					if (!fields.containsKey(fieldName)) {
						// Infer type from usage context
						int pos = fieldMatcher.start();
						int lineStart = content.lastIndexOf('\n', pos) + 1;
						int lineEnd = content.indexOf('\n', pos);
						if (lineEnd < 0) lineEnd = content.length();
						String line = content.substring(lineStart, lineEnd);

						String fieldType = inferFieldType(fieldName, searchName, line, content);
						fields.put(fieldName, fieldType);
					}
				}
			}

			if (!fields.isEmpty()) {
				varFields.put(varName, fields);
			}
		}

		// Step 2b: For Object vars with NO field accesses, check if a corresponding _i/_o class exists.
		// If ha00014ct1_o has no field accesses but ha00014ct1_i does, use the _i fields for _o.
		// Also: if a var is used with moveCorresponding(xxx_i, xxx_o), xxx_o gets xxx_i's fields.
		for (String varName : objectVars.keySet()) {
			if (varFields.containsKey(varName)) continue; // Already has fields

			String counterpart = null;
			if (varName.endsWith("_o")) {
				counterpart = varName.substring(0, varName.length() - 2) + "_i";
			} else if (varName.endsWith("_i")) {
				counterpart = varName.substring(0, varName.length() - 2) + "_o";
			}

			if (counterpart != null && varFields.containsKey(counterpart)) {
				// Copy fields from counterpart
				varFields.put(varName, new java.util.LinkedHashMap<>(varFields.get(counterpart)));
			} else {
				// No counterpart with fields. Check if the corresponding _i Type class exists
				// in the already-generated content (from the transformer itself, not from our post-proc).
				if (counterpart != null) {
					String counterpartTypeName = toTypeName(counterpart);
					if (content.contains("public class " + counterpartTypeName + " {")) {
						// Extract fields from the existing counterpart type
						java.util.Map<String, String> counterFields = extractFieldsFromClass(content, counterpartTypeName);
						if (!counterFields.isEmpty()) {
							varFields.put(varName, counterFields);
						}
					}
				}

				// If still no fields, create a minimal String-based wrapper so it's not Object
				if (!varFields.containsKey(varName)) {
					// Check if the var is used at all in the code (beyond declaration)
					String escapedVar2 = java.util.regex.Pattern.quote(varName);
					java.util.regex.Pattern usagePattern = java.util.regex.Pattern.compile(
						"\\b" + escapedVar2 + "\\b");
					java.util.regex.Matcher usageMatcher = usagePattern.matcher(content);
					int usageCount = 0;
					while (usageMatcher.find()) usageCount++;
					if (usageCount > 1) { // More than just the declaration
						// Create empty class - will be populated by postProcessAddMissingFields later
						varFields.put(varName, new java.util.LinkedHashMap<>());
					}
				}
			}
		}

		// Step 2c: Handle combined I-O DDS COPY pattern.
		// When COBOL has "01 R-HR00004SF5. COPY DDSR-HR00004SF5-I-O" (combined -I-O suffix),
		// the Object var is r_hr00004sf5 (no _i/_o suffix), but the code references
		// hr00004sf5_o and hr00004sf5_i as separate sub-group variables.
		// Detect this pattern: Object var without _i/_o suffix whose base name + _o/_i
		// appears as undeclared field-access variables in the code.
		java.util.Map<String, String> ioSubGroupVars = new java.util.LinkedHashMap<>(); // subVarName -> parentFullVarName
		for (java.util.Map.Entry<String, String> ov : objectVars.entrySet()) {
			String varName = ov.getKey();    // e.g., "hr00004sf5"
			String fullVarName = ov.getValue(); // e.g., "r_hr00004sf5"
			// Skip vars that already have _i/_o suffix (they are normal DDS vars)
			if (varName.endsWith("_i") || varName.endsWith("_o")) continue;
			// Check if varName_o or varName_i are used with field access in the code
			for (String suffix : new String[]{"_o", "_i"}) {
				String subVarName = varName + suffix;
				// Skip if already in objectVars (already handled)
				if (objectVars.containsKey(subVarName)) continue;
				// Check if subVarName is used with field access
				java.util.regex.Pattern subAccessPattern = java.util.regex.Pattern.compile(
					"\\b" + java.util.regex.Pattern.quote(subVarName) + "\\.([a-z][a-z0-9_]*)\\b");
				java.util.regex.Matcher subMatcher = subAccessPattern.matcher(content);
				boolean hasFieldAccess = false;
				java.util.Map<String, String> subFields = new java.util.LinkedHashMap<>();
				while (subMatcher.find()) {
					String fieldName = subMatcher.group(1);
					if ("compareTo".equals(fieldName) || "equals".equals(fieldName)
						|| "toString".equals(fieldName) || "intValue".equals(fieldName)
						|| "length".equals(fieldName) || "trim".equals(fieldName)
						|| "substring".equals(fieldName) || "replace".equals(fieldName)
						|| "size".equals(fieldName) || "get".equals(fieldName)
						|| "set".equals(fieldName) || "add".equals(fieldName)) {
						continue;
					}
					hasFieldAccess = true;
					if (!subFields.containsKey(fieldName)) {
						int pos = subMatcher.start();
						int lineStart = content.lastIndexOf('\n', pos) + 1;
						int lineEnd = content.indexOf('\n', pos);
						if (lineEnd < 0) lineEnd = content.length();
						String line = content.substring(lineStart, lineEnd);
						String fieldType = inferFieldType(fieldName, subVarName, line, content);
						subFields.put(fieldName, fieldType);
					}
				}
				if (hasFieldAccess) {
					ioSubGroupVars.put(subVarName, fullVarName);
					varFields.put(subVarName, subFields);
					LOG.info("Post-processing: detected combined I-O DDS sub-group '{}' under parent '{}'",
						subVarName, fullVarName);
				} else {
					// Also check standalone usage (as function argument)
					java.util.regex.Pattern standalonePattern = java.util.regex.Pattern.compile(
						"[,(]\\s*" + java.util.regex.Pattern.quote(subVarName) + "\\s*[),]");
					if (standalonePattern.matcher(content).find()) {
						ioSubGroupVars.put(subVarName, fullVarName);
						// If _o has no direct field accesses but _i does, copy _i fields
						String counterSuffix = "_o".equals(suffix) ? "_i" : "_o";
						String counterVarName = varName + counterSuffix;
						if (varFields.containsKey(counterVarName)) {
							varFields.put(subVarName, new java.util.LinkedHashMap<>(varFields.get(counterVarName)));
						} else {
							varFields.put(subVarName, new java.util.LinkedHashMap<>());
						}
						LOG.info("Post-processing: detected combined I-O DDS sub-group '{}' (standalone usage) under parent '{}'",
							subVarName, fullVarName);
					}
				}
			}
		}
		// For I-O sub-groups where _o has fields but _i doesn't (or vice versa), copy fields from counterpart
		for (String subVarName : ioSubGroupVars.keySet()) {
			java.util.Map<String, String> fields = varFields.get(subVarName);
			if (fields != null && fields.isEmpty()) {
				String counterpart;
				if (subVarName.endsWith("_o")) {
					counterpart = subVarName.substring(0, subVarName.length() - 2) + "_i";
				} else {
					counterpart = subVarName.substring(0, subVarName.length() - 2) + "_o";
				}
				if (varFields.containsKey(counterpart) && !varFields.get(counterpart).isEmpty()) {
					varFields.put(subVarName, new java.util.LinkedHashMap<>(varFields.get(counterpart)));
				}
			}
		}

		if (varFields.isEmpty()) {
			return content;
		}

		// Step 3: Generate inner classes and replace Object declarations
		StringBuilder newClasses = new StringBuilder();
		String result = content;
		int generated = 0;

		// Collect new standalone declarations for I-O sub-group variables
		StringBuilder ioSubGroupDecls = new StringBuilder();

		for (java.util.Map.Entry<String, java.util.Map<String, String>> entry : varFields.entrySet()) {
			String varName = entry.getKey();
			java.util.Map<String, String> fields = entry.getValue();
			String fullVarName = objectVars.get(varName);

			// Generate class name: capitalize first letter of each segment
			String className = toTypeName(varName);

			// Check if this class already exists in content (avoid duplicates)
			if (result.contains("public class " + className + " {")) continue;

			// Build the inner class
			StringBuilder classBuilder = new StringBuilder();
			classBuilder.append("    public class ").append(className).append(" {\n");
			for (java.util.Map.Entry<String, String> f : fields.entrySet()) {
				String fName = f.getKey();
				String fType = f.getValue();
				if ("BigDecimal".equals(fType)) {
					classBuilder.append("        protected BigDecimal ").append(fName).append(" = BigDecimal.ZERO;\n");
				} else if ("boolean".equals(fType)) {
					classBuilder.append("        protected boolean ").append(fName).append(" = false;\n");
				} else {
					classBuilder.append("        protected String ").append(fName).append(" = \"\";\n");
				}
			}
			classBuilder.append("    }\n");

			newClasses.append(classBuilder);

			if (fullVarName != null) {
				// Normal case: replace the existing Object declaration
				String origDeclRegex = "    protected Object " + java.util.regex.Pattern.quote(fullVarName) + ";(\\s*//.*)?";
				java.util.regex.Pattern origPattern = java.util.regex.Pattern.compile(origDeclRegex);
				java.util.regex.Matcher origMatcher = origPattern.matcher(result);
				if (origMatcher.find()) {
					String comment = origMatcher.group(1) != null ? origMatcher.group(1) : "";
					String replacement = "    protected " + className + " " + fullVarName + " = new " + className + "();" + comment + "\n"
						+ "    protected " + className + " " + varName + " = " + fullVarName + ";";
					result = origMatcher.replaceFirst(java.util.regex.Matcher.quoteReplacement(replacement));
					generated++;
				}
			} else if (ioSubGroupVars.containsKey(varName)) {
				// I-O sub-group case: create a brand new standalone declaration
				// This variable has no Object declaration — it's a sub-group of a combined I-O COPY
				ioSubGroupDecls.append("    protected ").append(className).append(" ").append(varName)
					.append(" = new ").append(className).append("(); // generated from combined I-O DDS COPY\n");
				generated++;
				LOG.info("Post-processing: created standalone declaration for I-O sub-group '{}'", varName);
			}
		}

		// Insert the generated inner classes before the first "protected" field declaration at class level
		// Find a good insertion point: after the last "public class XxxType {" block at 4-space indent
		// or before the first "protected" at 4-space indent
		if (generated > 0) {
			// Find first top-level protected field declaration (not inside an inner class)
			java.util.regex.Pattern firstProtectedPattern = java.util.regex.Pattern.compile(
				"(?m)^    protected\\s+(?:String|BigDecimal|boolean|int|long)\\s+\\w+\\s*=");
			java.util.regex.Matcher firstProtected = firstProtectedPattern.matcher(result);
			if (firstProtected.find()) {
				int insertPos = firstProtected.start();
				// Insert inner classes + any standalone I-O sub-group declarations
				String insertContent = newClasses.toString();
				if (ioSubGroupDecls.length() > 0) {
					insertContent += ioSubGroupDecls.toString();
				}
				result = result.substring(0, insertPos) + insertContent + "\n" + result.substring(insertPos);
			}
			System.err.println("POST-PROCESS: generated " + generated + " inner classes from Object declarations");
		}

		return result;
	}

	/**
	 * Converts a variable name like "ha00014ct2_i" to a type name like "Ha00014ct2_iType".
	 */
	private String toTypeName(String varName) {
		if (varName.isEmpty()) return "UnknownType";
		return Character.toUpperCase(varName.charAt(0)) + varName.substring(1) + "Type";
	}

	/**
	 * Infers the Java type of a field from its usage context.
	 * Returns "String", "BigDecimal", or "boolean".
	 */
	private String inferFieldType(String fieldName, String varName, String line, String content) {
		// If the field is used with moveNumericToNumeric, moveAlphanumericToNumeric, or compareTo(BigDecimal)
		// → BigDecimal
		if (line.contains("moveNumericToNumeric(" + varName + "." + fieldName)
			|| line.contains("moveAlphanumericToNumeric(" + varName + "." + fieldName)
			|| line.contains(varName + "." + fieldName + ".compareTo(BigDecimal")
			|| line.contains(varName + "." + fieldName + ".intValue()")
			|| line.contains(varName + "." + fieldName + ".add(")
			|| line.contains(varName + "." + fieldName + ".subtract(")
			|| line.contains(varName + "." + fieldName + ".multiply(")
			|| line.contains("moveNumericToAlphanumeric(" + varName + "." + fieldName + ",")) {
			return "BigDecimal";
		}

		// Check if it's a boolean (SET ... TO TRUE/FALSE)
		if (line.contains(varName + "." + fieldName + " = true")
			|| line.contains(varName + "." + fieldName + " = false")
			|| line.contains(varName + "." + fieldName + " &&")
			|| line.contains(varName + "." + fieldName + " ||")
			|| line.contains("!" + varName + "." + fieldName)) {
			return "boolean";
		}

		// Check if BigDecimal.ZERO or BigDecimal.valueOf is assigned
		if (line.contains(varName + "." + fieldName + " = BigDecimal")
			|| line.contains(varName + "." + fieldName + " = CobolMove.moveNumericToNumeric")) {
			return "BigDecimal";
		}

		// Check if nrdig or other numeric-sounding names
		if (fieldName.matches(".*\\b(nrdig|qtd|num|mt|val|cnt|idx|ind|nr)\\b.*")
			&& line.contains("moveNumericToNumeric")) {
			return "BigDecimal";
		}

		// Default to String (most DDS fields are alphanumeric)
		return "String";
	}

	/**
	 * Extracts field declarations from an existing inner class in the content.
	 * Returns a map of fieldName -> type ("String", "BigDecimal", "boolean").
	 */
	private java.util.Map<String, String> extractFieldsFromClass(String content, String className) {
		java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
		String header = "public class " + className + " {";
		int classStart = content.indexOf(header);
		if (classStart < 0) return fields;

		// Find the matching closing brace
		int braceDepth = 0;
		int classEnd = -1;
		for (int i = classStart; i < content.length(); i++) {
			char c = content.charAt(i);
			if (c == '{') braceDepth++;
			else if (c == '}') {
				braceDepth--;
				if (braceDepth == 0) { classEnd = i; break; }
			}
		}
		if (classEnd < 0) return fields;

		String classBody = content.substring(classStart, classEnd);
		java.util.regex.Pattern fieldPattern = java.util.regex.Pattern.compile(
			"protected\\s+(String|BigDecimal|boolean)\\s+(\\w+)\\s*=");
		java.util.regex.Matcher fieldMatcher = fieldPattern.matcher(classBody);
		while (fieldMatcher.find()) {
			fields.put(fieldMatcher.group(2), fieldMatcher.group(1));
		}
		return fields;
	}

	/**
	 * Post-processor: fix unresolved copybook qualifier variables.
	 *
	 * When COBOL uses "FIELD OF CpyTR5001(idx)", ProLeap sometimes generates
	 * safeGet(cpytr5001, idx).field where cpytr5001 is a non-existent standalone variable.
	 * The correct code should use the OCCURS list path, e.g., safeGet(wkorderheaderarr.wkorderheader, idx).field
	 *
	 * This post-processor:
	 * 1. Scans for correctly-resolved safeGet calls that have COBOL comments mentioning "OF CpyXXXX("
	 * 2. Builds a mapping: cpyXXXX -> correct OCCURS list path
	 * 3. Replaces broken safeGet(cpyXXXX, ...) with safeGet(correctPath, ...)
	 * 4. Also fixes Pattern B: safeGet(parent.list.cpyXXXX, idx) -> safeGet(parent.list, idx).cpyXXXX
	 */
	protected String postProcessFixCopybookQualifierVariables(String content) {
		// Copybook qualifier pattern: cpytr, cpyts, cpyta followed by digits, optionally "out"
		java.util.regex.Pattern cpyQualPattern = java.util.regex.Pattern.compile(
			"\\b(cpy(?:tr|ts|ta)\\d+(?:out)?)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

		// Step 1: Build mapping from COBOL comments on correctly-resolved lines.
		// Look for lines with safeGet(CORRECT_PATH, ...) and comment mentioning "OF CpyXXXX("
		// A correctly-resolved path contains a dot (e.g., wkorderheaderarr.wkorderheader)
		// and does NOT start with cpy.
		java.util.Map<String, String> cpyToPath = new java.util.LinkedHashMap<>();

		// Pattern 1: safeGet(CORRECT_PATH, ...) with subscripted comment OF CpyXXXX(idx)
		java.util.regex.Pattern correctPattern = java.util.regex.Pattern.compile(
			"safeGet\\(([a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+),\\s*[^)]+\\).*//.*\\bOF\\s+(Cpy(?:TR|TS|TA)\\d+(?:out)?)\\s*\\(",
			java.util.regex.Pattern.CASE_INSENSITIVE);
		java.util.regex.Matcher cm = correctPattern.matcher(content);
		while (cm.find()) {
			String correctPath = cm.group(1);
			String cpyName = cm.group(2).toLowerCase();
			// Only use paths that don't contain a cpy qualifier in them (truly resolved)
			if (!cpyQualPattern.matcher(correctPath).find()) {
				cpyToPath.putIfAbsent(cpyName, correctPath);
				// Also map the "out" variant to same path if base is mapped
				String base = cpyName.endsWith("out") ? cpyName.substring(0, cpyName.length() - 3) : cpyName;
				String out = base + "out";
				cpyToPath.putIfAbsent(base, correctPath);
				cpyToPath.putIfAbsent(out, correctPath);
			}
		}

		// Pattern 2: path.field // ... OF CpyXXXX (without subscript)
		// For non-OCCURS (single element) structures
		java.util.regex.Pattern correctPattern2 = java.util.regex.Pattern.compile(
			"([a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+)\\.[a-z][a-z0-9_]*.*//.*\\bOF\\s+(Cpy(?:TR|TS|TA)\\d+(?:out)?)\\s+(?:TO|OF|NOT|AND|OR|$)",
			java.util.regex.Pattern.CASE_INSENSITIVE);
		java.util.regex.Matcher cm2 = correctPattern2.matcher(content);
		while (cm2.find()) {
			String correctPath = cm2.group(1);
			String cpyName = cm2.group(2).toLowerCase();
			if (!cpyQualPattern.matcher(correctPath).find()) {
				cpyToPath.putIfAbsent(cpyName, correctPath);
				String base = cpyName.endsWith("out") ? cpyName.substring(0, cpyName.length() - 3) : cpyName;
				String out = base + "out";
				cpyToPath.putIfAbsent(base, correctPath);
				cpyToPath.putIfAbsent(out, correctPath);
			}
		}

		if (!cpyToPath.isEmpty()) {
			LOG.info("Post-processing CpyTR/TS/TA qualifier mappings: {}", cpyToPath);
		}

		// Step 2: Fix Pattern A — safeGet(cpyXXXX, ...) standalone
		// Replace with safeGet(correctPath, ...)
		// Also fix standalone cpyXXXX.field references (non-OCCURS single-element access)
		for (java.util.Map.Entry<String, String> entry : cpyToPath.entrySet()) {
			String cpyVar = entry.getKey();
			String correctPath = entry.getValue();
			// Replace safeGet(cpyVar, with safeGet(correctPath,
			content = content.replace("safeGet(" + cpyVar + ",", "safeGet(" + correctPath + ",");
			content = content.replace("safeGet(" + cpyVar + " ,", "safeGet(" + correctPath + ",");
			// Replace standalone cpyVar.field with correctPath.field
			// CRITICAL: Only replace when cpyVar is the START of an expression, NOT when
			// it's part of a dotted path (preceded by dot).
			// Match when preceded by space, (, =, comma, !, | — NOT by dot or alphanumeric
			content = content.replaceAll("(?<![a-z0-9_.])" + java.util.regex.Pattern.quote(cpyVar) + "\\.", correctPath + ".");
			// Fix Pattern A2 — cpyVar[expr] standalone array access (undeclared variable
			// used with Java array indexing). Replace with safeGet(correctPath, expr).
			// e.g., cpytr2001[wk_i.intValue() - 1] -> safeGet(wkorderheaderarr.orderheader, wk_i.intValue() - 1)
			{
				String searchToken = cpyVar + "[";
				int pos = 0;
				while ((pos = content.indexOf(searchToken, pos)) >= 0) {
					// Verify the char before is not alphanumeric/dot/underscore (word boundary)
					if (pos > 0) {
						char before = content.charAt(pos - 1);
						if (Character.isLetterOrDigit(before) || before == '_' || before == '.') {
							pos++;
							continue;
						}
					}
					int bracketStart = pos + cpyVar.length(); // position of '['
					// Find matching ']'
					int depth = 0;
					int bracketEnd = -1;
					for (int ci = bracketStart; ci < content.length(); ci++) {
						if (content.charAt(ci) == '[') depth++;
						else if (content.charAt(ci) == ']') {
							depth--;
							if (depth == 0) { bracketEnd = ci; break; }
						}
					}
					if (bracketEnd < 0) { pos++; continue; }
					String indexExpr = content.substring(bracketStart + 1, bracketEnd);
					String replacement = "io.proleap.cobol.runtime.CobolConstants.safeGet(" + correctPath + ", " + indexExpr + ")";
					content = content.substring(0, pos) + replacement + content.substring(bracketEnd + 1);
					LOG.info("Post-processing: replaced {}[{}] with safeGet({}, {})", cpyVar, indexExpr, correctPath, indexExpr);
					pos += replacement.length();
				}
			}
		}

		// Step 3: Fix Pattern B — safeGet(parent.list.cpyXXXX, idx)
		// The copybook group is placed inside safeGet instead of outside.
		// Fix: safeGet(parent.list.cpyXXXX, idx).field -> safeGet(parent.list, idx).cpyXXXX.field
		{
			java.util.regex.Pattern patternB = java.util.regex.Pattern.compile(
				"safeGet\\(([a-z][a-z0-9_.]*)\\.(" +
				"cpy(?:tr|ts|ta)\\d+(?:out)?" +
				"),\\s*");
			java.util.regex.Matcher mb = patternB.matcher(content);
			StringBuffer sb = new StringBuffer();
			boolean changed = false;
			while (mb.find()) {
				String parentPath = mb.group(1);
				String cpyGroup = mb.group(2);
				mb.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
					"safeGet(" + parentPath + ", "));
				// We need to add .cpyGroup after the closing paren of safeGet
				// This is complex with appendReplacement, so we'll do a second pass
				changed = true;
			}
			if (changed) {
				mb.appendTail(sb);
				content = sb.toString();

				// Second pass: find safeGet calls that now have the cpyGroup stripped,
				// and add it back after the closing paren.
				// Actually, let's do a different approach: direct string replacement
			}

			// Simpler approach for Pattern B: direct string replacement
			// Find: safeGet(path.cpyXXXX, args).field -> safeGet(path, args).cpyXXXX.field
			// We need the args to be preserved, so use a line-by-line scan
			String[] lines = content.split("\n");
			for (int li = 0; li < lines.length; li++) {
				String line = lines[li];
				java.util.regex.Matcher mb2 = patternB.matcher(line);
				if (!mb2.find()) continue;

				String parentPath = mb2.group(1);
				String cpyGroup = mb2.group(2);
				int safeGetStart = mb2.start();

				// Find the matching closing paren for this safeGet(
				int openParen = line.indexOf("(", safeGetStart);
				int depth = 0;
				int closeParen = -1;
				for (int ci = openParen; ci < line.length(); ci++) {
					if (line.charAt(ci) == '(') depth++;
					else if (line.charAt(ci) == ')') {
						depth--;
						if (depth == 0) { closeParen = ci; break; }
					}
				}
				if (closeParen < 0) continue;

				// Reconstruct: safeGet(parentPath, args).cpyGroup
				String args = line.substring(openParen + 1, closeParen);
				// Remove the cpyGroup from args — it's at the start as parentPath.cpyGroup, argsrest
				String newArgs = args.replaceFirst(
					java.util.regex.Pattern.quote(parentPath + "." + cpyGroup),
					parentPath);

				String before = line.substring(0, safeGetStart);
				String after = line.substring(closeParen + 1);
				lines[li] = before + "safeGet(" + newArgs + ")." + cpyGroup + after;
			}
			content = String.join("\n", lines);
		}

		// Step 4: Fix Pattern C — parent.cpyXXXX.fieldName where cpyXXXX is a virtual group
		// In some programs, the COBOL uses "FIELD OF CpyTR5002 OF ParentStructure(idx)"
		// and ProLeap generates parent.cpytr5002.field, but cpytr5002 is a virtual group
		// that doesn't exist as a real inner class.
		// Fix: strip the .cpyXXXX qualifier so the field is accessed directly on the parent.
		{
			// Find cpyXXXX names used as intermediate qualifiers (parent.cpyXXXX.field)
			// Only strip if there is NO real Type class for this cpyXXXX name
			java.util.Set<String> virtualGroups = new java.util.LinkedHashSet<>();
			java.util.regex.Pattern vgUsagePattern = java.util.regex.Pattern.compile(
				"\\.(cpy(?:tr|ts|ta)\\d+(?:out)?)\\.([a-z])", java.util.regex.Pattern.CASE_INSENSITIVE);
			java.util.regex.Matcher vgm = vgUsagePattern.matcher(content);

			// Also check if there's a field declared as a Type (not String/BigDecimal)
			// e.g., "protected CpyTS0040Type cpyts0040 = new CpyTS0040Type();"
			while (vgm.find()) {
				String name = vgm.group(1).toLowerCase();
				// Case-insensitive search: check if any "public class XXXType" exists
				// where XXX matches the cpyXXXX name (case-insensitive)
				boolean hasRealType = java.util.regex.Pattern.compile(
					"public class " + java.util.regex.Pattern.quote(name) + "Type\\s+\\{",
					java.util.regex.Pattern.CASE_INSENSITIVE).matcher(content).find();
				// Also check if there's a field of this type:
				// "protected XxxType cpyXXXX = new XxxType();"
				boolean hasTypedField = java.util.regex.Pattern.compile(
					"protected\\s+\\w+Type\\s+" + java.util.regex.Pattern.quote(name) + "\\s*=",
					java.util.regex.Pattern.CASE_INSENSITIVE).matcher(content).find();
				if (!hasRealType && !hasTypedField) {
					virtualGroups.add(name);
				}
			}

			if (!virtualGroups.isEmpty()) {
				LOG.info("Post-processing: stripping virtual group qualifiers: {}", virtualGroups);
				for (String vg : virtualGroups) {
					// Replace .cpyXXXX. with just . (strip the virtual group from paths)
					content = content.replaceAll(
						"\\." + java.util.regex.Pattern.quote(vg) + "\\.([a-z])",
						".$1");
				}
			}
		}

		return content;
	}

	/**
	 * Post-processor: fix PIC 1 REDEFINES with 88-level SET patterns.
	 *
	 * When COBOL does "SET ConditionName OF PIC1Field(idx) TO TRUE", and the field is
	 * a PIC 1 REDEFINES (boolean), ProLeap generates:
	 *   field.conditionName = true
	 * But field is accessed via getter (returns boolean), not an object with sub-fields.
	 *
	 * Detects: fieldRef.condition88Name = true;
	 * Where fieldRef ends with a known PIC 1 boolean getter name (canbedelb, isdeletedb, etc.)
	 * and condition88Name is an 88-level condition declared on that field.
	 *
	 * Fix: Replace with the correct setter call based on the 88-level VALUE.
	 */
	/**
	 * Adds missing DDS indicator variables (indof*_i, indof*_o) as BigDecimal fields.
	 * In COBOL, "IND OF SFL-I" references the DDS subfile record number indicator.
	 * The transformer concatenates this into a single variable name like "indofhmlnlogsfl_i"
	 * but never declares it because IND comes from the DDS format, not the COBOL data division.
	 */
	protected String postProcessAddMissingIndOfVariables(String content) {
		// Find all indof*_i / indof*_o variables used in the code
		java.util.Set<String> usedIndOfVars = new java.util.LinkedHashSet<>();
		java.util.regex.Matcher indMatcher = java.util.regex.Pattern.compile(
			"\\b(indof[a-z0-9_]+)\\b").matcher(content);
		while (indMatcher.find()) {
			usedIndOfVars.add(indMatcher.group(1));
		}
		if (usedIndOfVars.isEmpty()) return content;

		// Check which ones are NOT declared
		java.util.Set<String> undeclared = new java.util.LinkedHashSet<>();
		for (String varName : usedIndOfVars) {
			// Check for field declarations: "protected BigDecimal varName" or "protected String varName"
			if (!content.contains("protected BigDecimal " + varName) &&
				!content.contains("protected String " + varName) &&
				!content.contains("BigDecimal " + varName + " =") &&
				!content.contains("int " + varName + " =")) {
				undeclared.add(varName);
			}
		}
		if (undeclared.isEmpty()) return content;

		LOG.info("Post-processing: adding {} missing IND OF variable(s): {}", undeclared.size(), undeclared);

		// Insert declarations at the class level — find the first "protected" field declaration
		// at 4-space indent (class-level field)
		java.util.regex.Matcher firstFieldMatcher = java.util.regex.Pattern.compile(
			"^    protected\\s+", java.util.regex.Pattern.MULTILINE).matcher(content);
		if (!firstFieldMatcher.find()) return content;

		int insertPoint = firstFieldMatcher.start();
		StringBuilder declarations = new StringBuilder();
		for (String varName : undeclared) {
			declarations.append("    protected BigDecimal ").append(varName)
				.append(" = BigDecimal.ONE; // post-fix: DDS IND OF variable\n");
		}

		return content.substring(0, insertPoint) + declarations.toString() + content.substring(insertPoint);
	}

	/**
	 * Fixes String field used with subfield access (e.g., strout.strout_data, strout.strout_length).
	 * When a DDS record format type declares a field as "protected String strout" but code
	 * accesses subfields like ".strout.strout_data", the String IS the data.
	 * Also handles the case where code assigns to ".strout.strout_length".
	 */
	/**
	 * Fixes MOVE to group-level DDS record that generates an assignment to an undeclared
	 * alias variable with .length() on a group type.
	 *
	 * COBOL: MOVE ECRA-DADOS TO HAPILOGMNU-O
	 *
	 * Wrong Java: hapilogmnu_o = CobolMove.moveAlphanumericToAlphanumeric(ecra_dados,
	 *                hapilogmnu_o != null ? r_hapilogmnu_o.length() : 0);
	 *
	 * Correct Java: CobolMove.moveStringToGroup(ecra_dados, r_hapilogmnu_o);
	 *
	 * The pattern occurs when the transformer generates a move-to-group using an undeclared
	 * alias (xxx_o) while the declared variable is r_xxx_o of a group type (XxxType).
	 * The .length() call on the group type is invalid — the whole expression should be
	 * a moveStringToGroup call.
	 */
	protected String postProcessFixGroupMoveToRecord(String content) {
		// Build set of declared typed variables (group types, not String/BigDecimal)
		java.util.Map<String, String> declaredTypedVars = new java.util.LinkedHashMap<>();
		java.util.regex.Matcher declMatcher = java.util.regex.Pattern.compile(
			"^    protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)", java.util.regex.Pattern.MULTILINE).matcher(content);
		while (declMatcher.find()) {
			declaredTypedVars.put(declMatcher.group(2), declMatcher.group(1));
		}

		// Pattern: {indent}XXX = CobolMove.moveAlphanumericToAlphanumeric(SOURCE, XXX != null ? r_XXX.length() : 0);
		// where XXX is not declared but r_XXX is a group type variable.
		// Also matches: {indent}XXX = CobolMove.moveAlphanumericToAlphanumeric(SOURCE, SIZE);
		// and the COBOL comment indicates MOVE ... TO XXX (a group record).
		java.util.regex.Pattern moveToGroupPattern = java.util.regex.Pattern.compile(
			"^(\\s+)(\\w+)\\s*=\\s*CobolMove\\.moveAlphanumericToAlphanumeric\\((.+?),\\s*\\2\\s*!=\\s*null\\s*\\?\\s*(r_\\2)\\.length\\(\\)\\s*:\\s*0\\);(\\s*//.*)$",
			java.util.regex.Pattern.MULTILINE);

		boolean changed = false;
		java.util.regex.Matcher m = moveToGroupPattern.matcher(content);
		StringBuffer sb = new StringBuffer();
		while (m.find()) {
			String indent = m.group(1);
			String aliasVar = m.group(2);
			String source = m.group(3);
			String declaredVar = m.group(4);
			String comment = m.group(5);

			// Verify the declared variable is a group type (not String/BigDecimal)
			if (declaredTypedVars.containsKey(declaredVar)) {
				String replacement = indent + "CobolMove.moveStringToGroup(" + source + ", " + declaredVar + ");" + comment;
				m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
				LOG.info("Post-processing: fixed group MOVE to DDS record '{}' -> moveStringToGroup with '{}'", aliasVar, declaredVar);
				changed = true;
			}
		}
		m.appendTail(sb);
		if (changed) {
			content = sb.toString();
		}
		return content;
	}

	protected String postProcessFixStringSubfieldAccess(String content) {
		// Find String fields that are accessed with subfield notation
		// Pattern: varName.fieldName.subfieldName where fieldName is a String but subfieldName is used
		// Build map of fields declared as String in inner classes
		java.util.Map<String, java.util.Set<String>> classStringFields = new java.util.LinkedHashMap<>();
		java.util.Deque<String> classStack = new java.util.ArrayDeque<>();
		java.util.Deque<Integer> braceStack = new java.util.ArrayDeque<>();
		int currentBraceDepth = 0;

		String[] lines = content.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			java.util.regex.Matcher classMatcher = java.util.regex.Pattern.compile(
				"^(\\s+)public class (\\w+Type)\\s+\\{").matcher(line);
			if (classMatcher.find()) {
				classStack.push(classMatcher.group(2));
				braceStack.push(currentBraceDepth);
				if (!classStringFields.containsKey(classMatcher.group(2))) {
					classStringFields.put(classMatcher.group(2), new java.util.LinkedHashSet<>());
				}
			}

			boolean inString = false, inComment = false;
			for (int ci = 0; ci < line.length(); ci++) {
				char c = line.charAt(ci);
				if (inComment) break;
				if (c == '"' && !inString) inString = true;
				else if (c == '"' && inString) inString = false;
				else if (c == '/' && ci + 1 < line.length() && line.charAt(ci + 1) == '/' && !inString) inComment = true;
				else if (!inString) {
					if (c == '{') currentBraceDepth++;
					else if (c == '}') {
						currentBraceDepth--;
						if (!braceStack.isEmpty() && currentBraceDepth == braceStack.peek()) {
							classStack.pop();
							braceStack.pop();
						}
					}
				}
			}

			if (!classStack.isEmpty()) {
				java.util.regex.Matcher fieldMatcher = java.util.regex.Pattern.compile(
					"^\\s+protected\\s+String\\s+(\\w+)\\s*=").matcher(line);
				if (fieldMatcher.find()) {
					classStringFields.get(classStack.peek()).add(fieldMatcher.group(1));
				}
			}
		}

		// Build map of instance variables to their types
		java.util.Map<String, String> varToTypeLocal = new java.util.HashMap<>();
		java.util.regex.Matcher vtMatcher = java.util.regex.Pattern.compile(
			"protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)").matcher(content);
		while (vtMatcher.find()) {
			varToTypeLocal.put(vtMatcher.group(2), vtMatcher.group(1));
		}

		boolean changed = false;

		// Fix pattern: .stringField.stringField_data -> .stringField
		// e.g., .strout.strout_data -> .strout  (the String IS the data)
		// Scan for patterns where a String field has _data/_length subfields accessed
		for (java.util.Map.Entry<String, java.util.Set<String>> entry : classStringFields.entrySet()) {
			for (String stringFieldName : entry.getValue()) {

				// Check if there's code accessing stringFieldName.stringFieldName_data
				String dataAccess = "." + stringFieldName + "." + stringFieldName + "_data";
				if (content.contains(dataAccess)) {
					content = content.replace(dataAccess, "." + stringFieldName);
					changed = true;
				}

				// For .stringFieldName.stringFieldName_length = VALUE;
				// This assigns the length — remove the whole assignment as String length is implicit
				String lengthAssign = "." + stringFieldName + "." + stringFieldName + "_length";
				if (content.contains(lengthAssign)) {
					// Replace lines like: var.strout.strout_length = BigDecimal.valueOf(N);
					// with nothing (remove the line)
					String[] contentLines = content.split("\n");
					StringBuilder sb = new StringBuilder();
					for (String cl : contentLines) {
						if (cl.contains(lengthAssign) && cl.contains("=") && cl.trim().endsWith(";")) {
							// Skip this line — it's a redundant length assignment
							changed = true;
						} else {
							sb.append(cl).append("\n");
						}
					}
					content = sb.toString();
					// Remove trailing newline if added
					if (content.endsWith("\n") && !content.endsWith("\n\n")) {
						content = content.substring(0, content.length() - 1);
					}
				}
			}
		}

		if (changed) {
			LOG.info("Post-processing: fixed String subfield access patterns (strout.strout_data -> strout)");
		}
		return content;
	}

	/**
	 * Fixes undeclared DDS format variables by mapping them to their enclosing record format instance.
	 * When code uses "re_ecran1a_o.field" standalone but only "r_hf4_ecran1a_o" is declared,
	 * and the enclosing record is the parent of the sub-record:
	 * - Replace standalone "re_ecran1a_o.field" with "r_hf4_ecran1a_o.field" (the format IS the parent)
	 * - Replace qualified "r_hf4_ecran1a_o.re_ecran1a_o" with "r_hf4_ecran1a_o" (self-reference)
	 */
	protected String postProcessFixUndeclaredFormatVariables(String content) {
		// Find all top-level declared typed variables (at 4-space indent)
		java.util.Map<String, String> declaredVars = new java.util.LinkedHashMap<>(); // varName -> typeName
		java.util.regex.Matcher declMatcher = java.util.regex.Pattern.compile(
			"^    protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)", java.util.regex.Pattern.MULTILINE).matcher(content);
		while (declMatcher.find()) {
			declaredVars.put(declMatcher.group(2), declMatcher.group(1));
		}
		// Also include Object/String declarations (DDS records that didn't resolve to typed classes)
		java.util.regex.Matcher objDeclMatcher = java.util.regex.Pattern.compile(
			"^    protected\\s+(Object|String)\\s+(\\w+)\\s*;", java.util.regex.Pattern.MULTILINE).matcher(content);
		while (objDeclMatcher.find()) {
			declaredVars.put(objDeclMatcher.group(2), objDeclMatcher.group(1));
		}

		// Collect field names declared inside inner classes (List fields, typed fields, etc.).
		// These are NOT top-level variables but fields accessed within class bodies (e.g.,
		// "sociedade.add()" inside Tabela_sociedadesType initializer block).
		// Without this, they get flagged as "undeclared" and incorrectly remapped to
		// a top-level variable whose name happens to end with the field name.
		java.util.Set<String> innerClassFieldNames = new java.util.LinkedHashSet<>();
		for (java.util.Map.Entry<String, String> dve : declaredVars.entrySet()) {
			String typeName = dve.getValue();
			if (!typeName.endsWith("Type")) continue;
			String classStart = "public class " + typeName + " {";
			int ci = content.indexOf(classStart);
			if (ci < 0) continue;
			int bodyEnd = Math.min(ci + 10000, content.length());
			String classBody = content.substring(ci, bodyEnd);
			// Match List fields: "protected List<XxxType> fieldName = new ArrayList"
			java.util.regex.Matcher icfm = java.util.regex.Pattern.compile(
				"protected\\s+List<\\w+>\\s+(\\w+)\\s*=").matcher(classBody);
			while (icfm.find()) {
				innerClassFieldNames.add(icfm.group(1));
			}
			// Match typed instance fields: "protected XxxType fieldName = new XxxType();"
			java.util.regex.Matcher ictm = java.util.regex.Pattern.compile(
				"protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)").matcher(classBody);
			while (ictm.find()) {
				innerClassFieldNames.add(ictm.group(2));
			}
		}

		// Find variables used with field access that are NOT declared
		java.util.Set<String> usedVars = new java.util.LinkedHashSet<>();
		java.util.regex.Matcher usageMatcher = java.util.regex.Pattern.compile(
			"^\\s+([a-z][a-z0-9_]*)\\.", java.util.regex.Pattern.MULTILINE).matcher(content);
		while (usageMatcher.find()) {
			String varName = usageMatcher.group(1);
			if (!declaredVars.containsKey(varName) && !varName.startsWith("io") && !varName.startsWith("java")
				&& !innerClassFieldNames.contains(varName)) {
				usedVars.add(varName);
			}
		}

		boolean changed = false;
		for (String undeclaredVar : usedVars) {
			// Strategy: Check if the undeclared var name is a suffix or core part of a declared name
			// e.g., re_ecran1a_o -> extract "ecran1a_o" -> match against r_hf4_ecran1a_o
			// Must be an underscore-delimited suffix match to avoid false positives
			String matchedDeclared = null;
			for (java.util.Map.Entry<String, String> entry : declaredVars.entrySet()) {
				String declaredName = entry.getKey();
				// Exact underscore-delimited suffix match: declaredName must end with _undeclaredVar
				if (declaredName.endsWith("_" + undeclaredVar)) {
					matchedDeclared = declaredName;
					break;
				}
				// Extract core name from undeclared: strip common DDS prefixes (r_, re_, w_)
				String undeclaredCore = undeclaredVar;
				if (undeclaredCore.startsWith("re_")) undeclaredCore = undeclaredCore.substring(3);
				else if (undeclaredCore.startsWith("r_")) undeclaredCore = undeclaredCore.substring(2);
				else if (undeclaredCore.startsWith("w_")) undeclaredCore = undeclaredCore.substring(2);
				if (undeclaredCore.length() > 5 && !undeclaredCore.equals(undeclaredVar) && declaredName.endsWith(undeclaredCore)) {
					matchedDeclared = declaredName;
					break;
				}
			}

			// Combined I-O DDS format: xxx_o or xxx_i -> r_xxx
			// When COPY DDSR uses combined I-O suffix, the declared variable is r_xxx without
			// the _o/_i suffix. e.g., hr00004sf5_o -> r_hr00004sf5
			if (matchedDeclared == null && (undeclaredVar.endsWith("_o") || undeclaredVar.endsWith("_i"))) {
				String baseName = undeclaredVar.substring(0, undeclaredVar.length() - 2);
				for (java.util.Map.Entry<String, String> entry : declaredVars.entrySet()) {
					String declaredName = entry.getKey();
					if (declaredName.endsWith("_" + baseName)) {
						matchedDeclared = declaredName;
						break;
					}
				}
				// Also try direct r_ prefix: r_baseName
				if (matchedDeclared == null) {
					String candidate = "r_" + baseName;
					if (declaredVars.containsKey(candidate)) {
						matchedDeclared = candidate;
					}
				}
			}

			// AS/400 DDS implicit record names: xxx_record -> r_xxx.xxx
			// When a variable like vr210100_record is used with field access (vr210100_record.codsoc),
			// map it to r_vr210100.vr210100 so the field access becomes r_vr210100.vr210100.codsoc.
			if (matchedDeclared == null && undeclaredVar.endsWith("_record")) {
				String baseName = undeclaredVar.substring(0, undeclaredVar.length() - "_record".length());
				String recordVar = "r_" + baseName;
				if (declaredVars.containsKey(recordVar)) {
					if (content.contains(undeclaredVar + ".") || content.contains("." + undeclaredVar + ")")) {
						String qualifiedPath = recordVar + "." + baseName;
						LOG.info("Post-processing: mapping DDS implicit record field access '{}' to '{}'",
							undeclaredVar, qualifiedPath);
						content = content.replaceAll(
							"(?<!\\.)" + java.util.regex.Pattern.quote(undeclaredVar) + "\\.",
							java.util.regex.Matcher.quoteReplacement(qualifiedPath) + ".");
						changed = true;
					}
				}
			} else if (matchedDeclared != null) {
				// Verify the undeclared variable is actually used in code (not just in comments)
				if (content.contains(undeclaredVar + ".") || content.contains("." + undeclaredVar + ")")) {
					LOG.info("Post-processing: mapping undeclared '{}' to declared '{}'", undeclaredVar, matchedDeclared);
					// Replace standalone undeclared variable access with declared variable.
					// Use regex with negative lookbehind for '.' AND for identifier chars
					// ([a-z0-9_]) to avoid replacing occurrences that are already qualified
					// (e.g., parent.sociedade.size() must NOT become parent.registo_sociedade.size())
					// AND to avoid replacing a suffix of a longer variable name
					// (e.g., w_nbsemlivac.compareTo must NOT become w_nbwk_semlivac.compareTo
					// when undeclaredVar="semlivac" and matchedDeclared="wk_semlivac").
					content = content.replaceAll(
						"(?<![.a-z0-9_])" + java.util.regex.Pattern.quote(undeclaredVar) + "\\.",
						java.util.regex.Matcher.quoteReplacement(matchedDeclared) + ".");
					// Replace qualified self-reference: declaredName.undeclaredVar -> declaredName
					content = content.replace(matchedDeclared + "." + undeclaredVar + ")", matchedDeclared + ")");
					content = content.replace(matchedDeclared + "." + undeclaredVar + ",", matchedDeclared + ",");
					content = content.replace(matchedDeclared + "." + undeclaredVar + ";", matchedDeclared + ";");
					// Also replace standalone function argument usage (without field access).
					// When a DDS format variable is used both with field access (var.field)
					// and as a standalone argument (func(var)), Phase 1 handles the dotted
					// paths above, but the standalone references remain unresolved.
					// Phase 2 skips variables already in usedVars, so we must fix them here.
					content = content.replace("(" + undeclaredVar + ")", "(" + matchedDeclared + ")");
					content = content.replace("(" + undeclaredVar + ",", "(" + matchedDeclared + ",");
					content = content.replace(", " + undeclaredVar + ")", ", " + matchedDeclared + ")");
					content = content.replace(", " + undeclaredVar + ",", ", " + matchedDeclared + ",");
					changed = true;
				}
			}
		}

		// Phase 2: Detect standalone undeclared variables used as function arguments or in assignments.
		// These are DDS format record names used without field access, e.g.:
		//   entityService.initialize(img1a_o)  -> entityService.initialize(r_img1a_o)
		// Scan for variable names inside parentheses that are not declared.
		// First, build a set of ALL declared variables (not just typed ones) to avoid false positives.
		java.util.Set<String> allDeclaredVarNames = new java.util.LinkedHashSet<>(declaredVars.keySet());
		// Also add FileControlEntry declarations and other non-typed declarations
		java.util.regex.Matcher allDeclMatcher = java.util.regex.Pattern.compile(
			"^    (?:protected\\s+)?(?:FileControlEntry|String|BigDecimal|boolean|int|long|Object)\\s+(\\w+)\\s*=",
			java.util.regex.Pattern.MULTILINE).matcher(content);
		while (allDeclMatcher.find()) {
			allDeclaredVarNames.add(allDeclMatcher.group(1));
		}
		// Also add List declarations
		java.util.regex.Matcher listDeclMatcher = java.util.regex.Pattern.compile(
			"^    protected\\s+List<\\w+>\\s+(\\w+)\\s*=",
			java.util.regex.Pattern.MULTILINE).matcher(content);
		while (listDeclMatcher.find()) {
			allDeclaredVarNames.add(listDeclMatcher.group(1));
		}
		// Also add inner class field declarations (at any indent level).
		// Fields like "protected String[] factor = new String[10];" inside inner classes
		// must NOT be treated as undeclared standalone variables.
		java.util.regex.Matcher innerFieldDeclMatcher = java.util.regex.Pattern.compile(
			"^\\s+protected\\s+(?:String|BigDecimal|boolean|int|long)(?:\\[\\])?\\s+(\\w+)\\s*=",
			java.util.regex.Pattern.MULTILINE).matcher(content);
		while (innerFieldDeclMatcher.find()) {
			allDeclaredVarNames.add(innerFieldDeclMatcher.group(1));
		}

		// Use lookbehind to avoid consuming the delimiter, so consecutive arguments can be matched
		java.util.regex.Pattern argPattern = java.util.regex.Pattern.compile(
			"(?<=[(,])\\s*([a-z][a-z0-9_]*)\\s*(?=[),])");
		java.util.Set<String> standaloneUndeclared = new java.util.LinkedHashSet<>();
		java.util.regex.Matcher argMatcher = argPattern.matcher(content);
		while (argMatcher.find()) {
			String varName = argMatcher.group(1);
			if (!allDeclaredVarNames.contains(varName)
				&& !varName.startsWith("io") && !varName.startsWith("java")
				&& !varName.equals("true") && !varName.equals("false") && !varName.equals("null")
				&& !varName.equals("this") && !varName.equals("super")
				&& varName.length() > 3) {
				standaloneUndeclared.add(varName);
			}
		}

		for (String undeclaredVar : standaloneUndeclared) {
			if (usedVars.contains(undeclaredVar)) continue; // Already handled in Phase 1
			// Try to find a matching declared variable with common prefixes
			String matchedCandidate = null;
			for (String prefix : new String[]{"r_", "w_", "wk_", "re_"}) {
				String candidate = prefix + undeclaredVar;
				if (declaredVars.containsKey(candidate)) {
					matchedCandidate = candidate;
					break;
				}
			}
			// Also try suffix match: check if any declared var ends with "_" + undeclaredVar
			if (matchedCandidate == null) {
				for (String declName : declaredVars.keySet()) {
					if (declName.endsWith("_" + undeclaredVar) && declName.length() > undeclaredVar.length() + 1) {
						matchedCandidate = declName;
						break;
					}
				}
			}
			// Combined I-O DDS format: xxx_o or xxx_i -> r_xxx
			// When COPY DDSR uses combined I-O suffix, the declared variable is r_xxx without
			// the _o/_i suffix. e.g., hr00004sf5_o -> r_hr00004sf5
			if (matchedCandidate == null && (undeclaredVar.endsWith("_o") || undeclaredVar.endsWith("_i"))) {
				String baseName = undeclaredVar.substring(0, undeclaredVar.length() - 2);
				for (String prefix : new String[]{"r_", "w_", "wk_", "re_"}) {
					String candidate = prefix + baseName;
					if (declaredVars.containsKey(candidate)) {
						matchedCandidate = candidate;
						break;
					}
				}
				if (matchedCandidate == null) {
					for (String declName : declaredVars.keySet()) {
						if (declName.endsWith("_" + baseName) && declName.length() > baseName.length() + 1) {
							matchedCandidate = declName;
							break;
						}
					}
				}
			}
			// AS/400 DDS implicit record names: when COPY DDSR-ALL-FORMATS generates
			// a record format, AS/400 implicitly creates a <filename>-RECORD entry.
			// The stub copybooks don't include this, so the parser doesn't resolve it.
			// Map xxx_record -> r_xxx.xxx (the format field inside the record group).
			String ddsRecordQualified = null;
			if (matchedCandidate == null && undeclaredVar.endsWith("_record")) {
				String baseName = undeclaredVar.substring(0, undeclaredVar.length() - "_record".length());
				String recordVar = "r_" + baseName;
				if (declaredVars.containsKey(recordVar)) {
					// Verify the declared variable's type has an inner field named baseName
					String typeName = declaredVars.get(recordVar);
					if (typeName != null && typeName.endsWith("Type")) {
						String classStart = "public class " + typeName + " {";
						if (content.contains(classStart)) {
							// Check if baseName is declared as a field inside that class
							String fieldPattern = " " + baseName + " = new ";
							int classIdx = content.indexOf(classStart);
							if (classIdx >= 0) {
								int searchEnd = Math.min(classIdx + 20000, content.length());
								String classBody = content.substring(classIdx, searchEnd);
								if (classBody.contains(fieldPattern)) {
									ddsRecordQualified = recordVar + "." + baseName;
									LOG.info("Post-processing: mapping DDS implicit record '{}' to '{}'",
										undeclaredVar, ddsRecordQualified);
								}
							}
						}
					}
				}
			}
			if (ddsRecordQualified != null) {
				content = content.replace("(" + undeclaredVar + ")", "(" + ddsRecordQualified + ")");
				content = content.replace("(" + undeclaredVar + ",", "(" + ddsRecordQualified + ",");
				content = content.replace(", " + undeclaredVar + ")", ", " + ddsRecordQualified + ")");
				content = content.replace(", " + undeclaredVar + ",", ", " + ddsRecordQualified + ",");
				changed = true;
			} else if (matchedCandidate != null) {
				LOG.info("Post-processing: mapping standalone undeclared '{}' to declared '{}'", undeclaredVar, matchedCandidate);
				// Replace dotted-access patterns (mid-line field access like comparison args)
				// e.g., hr00004sf5_i.opc5 -> r_hr00004sf5.opc5
				// Use negative lookbehind for identifier chars to avoid replacing a suffix
				// of a longer variable name (e.g., w_nbsemlivac.compareTo must NOT match
				// when undeclaredVar="semlivac").
				if (content.contains(undeclaredVar + ".")) {
					content = content.replaceAll(
						"(?<![.a-z0-9_])" + java.util.regex.Pattern.quote(undeclaredVar) + "\\.",
						java.util.regex.Matcher.quoteReplacement(matchedCandidate) + ".");
				}
				// Use word-boundary-aware replacement to avoid false positives
				content = content.replace("(" + undeclaredVar + ")", "(" + matchedCandidate + ")");
				content = content.replace("(" + undeclaredVar + ",", "(" + matchedCandidate + ",");
				content = content.replace(", " + undeclaredVar + ")", ", " + matchedCandidate + ")");
				content = content.replace(", " + undeclaredVar + ",", ", " + matchedCandidate + ",");
				changed = true;
			}
		}

		return content;
	}

	/**
	 * Fixes undeclared copybook/LINKAGE variables by creating alias declarations.
	 * When COBOL COPY includes fields like H-FILE750501 and the transformer generates
	 * a reference to h_vr750501 but only declares r_vr750501 (with a prefix), this
	 * post-processor replaces references to the undeclared name with the declared one.
	 *
	 * IMPORTANT: Only map variables that appear as TOP-LEVEL references (i.e., as the
	 * first segment of a dotted path on a new statement line), NOT as subfield names
	 * inside a group (e.g., vh000301.nr_rvc_dis must NOT be affected).
	 */
	protected String postProcessFixUndeclaredCopybookVariables(String content) {
		// Collect all top-level declared typed variables (at 4-space indent)
		java.util.Map<String, String> declaredTypedVars = new java.util.LinkedHashMap<>();
		java.util.regex.Matcher dm = java.util.regex.Pattern.compile(
			"^    protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)", java.util.regex.Pattern.MULTILINE).matcher(content);
		while (dm.find()) {
			declaredTypedVars.put(dm.group(2), dm.group(1));
		}
		// Also collect non-Type top-level variables
		java.util.Map<String, String> declaredAllVars = new java.util.LinkedHashMap<>();
		java.util.regex.Matcher dm2 = java.util.regex.Pattern.compile(
			"^    protected\\s+(\\w+)\\s+(\\w+)\\s*=", java.util.regex.Pattern.MULTILINE).matcher(content);
		while (dm2.find()) {
			declaredAllVars.put(dm2.group(2), dm2.group(1));
		}

		// Find variables used as TOP-LEVEL path starts (first segment before a dot)
		// ONLY on statement lines (lines starting with whitespace then the variable name)
		// Pattern: at line start after indentation, the variable is the first identifier followed by .
		java.util.Set<String> topLevelRefs = new java.util.LinkedHashSet<>();
		java.util.regex.Matcher topMatcher = java.util.regex.Pattern.compile(
			"^\\s+([a-z][a-z0-9_]+)\\.", java.util.regex.Pattern.MULTILINE).matcher(content);
		while (topMatcher.find()) {
			String varName = topMatcher.group(1);
			if (!declaredAllVars.containsKey(varName) && !declaredTypedVars.containsKey(varName)
				&& !varName.startsWith("io") && !varName.startsWith("java")
				&& !varName.startsWith("entityservice") && !varName.startsWith("this")) {
				topLevelRefs.add(varName);
			}
		}

		boolean changed = false;
		for (String undeclVar : topLevelRefs) {
			// Only handle variables with known copybook/linkage prefixes
			String core = null;
			if (undeclVar.startsWith("h_") && undeclVar.length() > 3) core = undeclVar.substring(2);
			else if (undeclVar.startsWith("w_") && undeclVar.length() > 3) core = undeclVar.substring(2);
			else if (undeclVar.startsWith("estr_") && undeclVar.length() > 6) core = undeclVar.substring(5);

			if (core == null) continue; // Skip non-prefixed names to avoid false positives

			String matchedDeclared = null;
			// Try r_ prefix
			if (declaredTypedVars.containsKey("r_" + core)) {
				matchedDeclared = "r_" + core;
			}
			// Try exact core match
			if (matchedDeclared == null && declaredTypedVars.containsKey(core)) {
				matchedDeclared = core;
			}

			if (matchedDeclared != null) {
				LOG.info("Post-processing copybook alias: mapping '{}' to '{}'", undeclVar, matchedDeclared);
				// ONLY replace at the beginning of a dotted path, not as a subfield
				// Use line-by-line replacement to avoid matching subfield names
				String[] lines = content.split("\n", -1);
				for (int i = 0; i < lines.length; i++) {
					String line = lines[i];
					// Replace only when the undeclared var starts a dotted path at line-start indent
					if (line.contains(undeclVar + ".")) {
						// Only replace if undeclVar is the FIRST identifier on the line (top-level)
						String trimmedLine = line.trim();
						if (trimmedLine.startsWith(undeclVar + ".")) {
							lines[i] = line.replace(undeclVar + ".", matchedDeclared + ".");
							changed = true;
						}
					}
				}
				if (changed) {
					content = String.join("\n", lines);
				}
			}
		}

		return content;
	}

	/**
	 * Fixes SQL FETCH code that assigns to group fields as if they were Strings.
	 * Pattern: "path.groupField = CobolMove.moveAlphanumericToAlphanumeric(_vc, path.groupField.length())"
	 * where groupField is a typed inner class (XxxType) not a String.
	 * Fix: replace with "path.groupField.groupField_data = ... path.groupField.groupField_data.length()"
	 */
	protected String postProcessFixSqlFetchGroupFields(String content) {
		// Find typed inner class fields: "protected XxxType fieldName = new XxxType();"
		java.util.Map<String, String> typedFields = new java.util.HashMap<>();
		java.util.regex.Matcher tm = java.util.regex.Pattern.compile(
			"protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)").matcher(content);
		while (tm.find()) {
			typedFields.put(tm.group(2), tm.group(1));
		}
		if (typedFields.isEmpty()) return content;

		boolean changed = false;
		String[] lines = content.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			// Look for SQL FETCH pattern: .fieldName = CobolMove.moveAlphanumericToAlphanumeric(
			// or .fieldName.length()
			for (java.util.Map.Entry<String, String> entry : typedFields.entrySet()) {
				String fieldName = entry.getKey();
				// Pattern 1: .fieldName = CobolMove.move... (assignment to group as String)
				String assignPattern = "." + fieldName + " = CobolMove.move";
				if (line.contains(assignPattern)) {
					// Also check if this line has .fieldName.length() — classic SQL FETCH pattern
					String lengthPattern = "." + fieldName + ".length()";
					if (line.contains(lengthPattern)) {
						// Replace assignment target and length call with _data subfield
						line = line.replace(assignPattern, "." + fieldName + "." + fieldName + "_data = CobolMove.move");
						line = line.replace(lengthPattern, "." + fieldName + "." + fieldName + "_data.length()");
						lines[i] = line;
						changed = true;
					}
				}
			}
		}

		if (changed) {
			LOG.info("Post-processing: fixed SQL FETCH group field assignments");
			return String.join("\n", lines);
		}
		return content;
	}

	protected String postProcessFixPic1Set88(String content) {
		// Find PIC 1 REDEFINES fields: declared as "public boolean getXxxb()"
		// and their 88-level conditions from COBOL comments: "88 CondName VALUE B"1"" or "VALUE B"0""
		java.util.regex.Pattern pic1Pattern = java.util.regex.Pattern.compile(
			"public boolean (get[A-Za-z0-9_]+b)\\(\\).*//.*PIC 1");
		java.util.regex.Matcher pic1Matcher = pic1Pattern.matcher(content);

		// Map: getterName -> { conditionName -> value (true/false) }
		// true means B"1" (set the boolean to true), false means B"0" (set to false)
		java.util.Map<String, java.util.Map<String, Boolean>> pic1Fields = new java.util.LinkedHashMap<>();

		while (pic1Matcher.find()) {
			String getterName = pic1Matcher.group(1);
			// Derive field name: strip "get" prefix and lower-case first letter
			String fieldName = getterName.substring(3);
			fieldName = fieldName.substring(0, 1).toLowerCase() + fieldName.substring(1);

			// Look for 88-level conditions in subsequent lines
			int pos = pic1Matcher.end();
			// Scan next few lines for 88 declarations
			java.util.regex.Pattern cond88 = java.util.regex.Pattern.compile(
				"//.*88\\s+([A-Za-z][A-Za-z0-9_-]*)\\s+VALUE\\s+B\"([01])\"");
			// Search in the next 500 chars (should cover a few lines)
			int searchEnd = Math.min(pos + 500, content.length());
			String searchRegion = content.substring(pos, searchEnd);
			java.util.regex.Matcher condMatcher = cond88.matcher(searchRegion);

			java.util.Map<String, Boolean> conditions = new java.util.LinkedHashMap<>();
			while (condMatcher.find()) {
				String condName = condMatcher.group(1).toLowerCase();
				boolean val = "1".equals(condMatcher.group(2));
				conditions.put(condName, val);
			}

			if (!conditions.isEmpty()) {
				pic1Fields.put(fieldName, conditions);
			}
		}

		if (pic1Fields.isEmpty()) return content;

		LOG.info("Post-processing PIC 1 / 88-level SET patterns: {}", pic1Fields.keySet());

		// Now fix: parent.fieldName.conditionName = true;
		// Replace with: parent.setFieldName(boolValue);
		// Use string-based replacement to handle nested parentheses in safeGet
		for (java.util.Map.Entry<String, java.util.Map<String, Boolean>> entry : pic1Fields.entrySet()) {
			String fieldName = entry.getKey();
			java.util.Map<String, Boolean> conditions = entry.getValue();
			String setterName = "set" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);

			for (java.util.Map.Entry<String, Boolean> condEntry : conditions.entrySet()) {
				String condName = condEntry.getKey();
				boolean boolValue = condEntry.getValue();

				// Search for .fieldName.condName = true; and replace
				String searchStr = "." + fieldName + "." + condName + " = true;";
				String replaceStr = "." + setterName + "(" + boolValue + ");";
				content = content.replace(searchStr, replaceStr);
			}
		}

		return content;
	}

	/**
	 * Fix OCCURS initializer blocks that use the parent variable name instead
	 * of the List field name.
	 *
	 * Pattern detected (individual add):
	 *   protected List<YType> y = new ArrayList<YType>();
	 *   {
	 *       x.add(new YType());   // x is the parent's instance variable
	 *   }
	 *
	 * Pattern detected (for-loop):
	 *   protected List<YType> y = new ArrayList<YType>();
	 *   {
	 *       for (int _i = 0; _i < N; _i++) { x.add(new YType()); }
	 *   }
	 *
	 * Fixed to use y.add(...) instead of x.add(...).
	 */
	private String postProcessFixOccursInitializer(String content) {
		// Strategy: find each "protected List<XType> listVar = new ArrayList<XType>();"
		// then look at the INITIALIZER BLOCK immediately following it (starts with "{")
		// Only replace .add() calls within that block, NOT in subsequent code.

		final java.util.regex.Pattern listFieldPattern = java.util.regex.Pattern.compile(
			"protected List<([A-Z][A-Za-z0-9_]*)> ([a-z][a-z0-9_]*) = new ArrayList<\\1>\\(\\);");

		java.util.regex.Matcher m = listFieldPattern.matcher(content);
		StringBuilder result = new StringBuilder(content);
		int offset = 0;

		while (m.find()) {
			String typeName = m.group(1);      // e.g., Tb0021_lkType
			String listFieldName = m.group(2); // e.g., tb0021_lk

			int searchStart = m.end() + offset;

			// Find the next initializer block: skip whitespace/newlines, expect "{"
			int pos = searchStart;
			while (pos < result.length() && (result.charAt(pos) == ' ' || result.charAt(pos) == '\n' || result.charAt(pos) == '\r' || result.charAt(pos) == '\t')) {
				pos++;
			}
			if (pos >= result.length() || result.charAt(pos) != '{') {
				continue; // No initializer block found
			}

			// Find the matching closing brace for this initializer block
			int braceDepth = 0;
			int blockStart = pos;
			int blockEnd = -1;
			for (int i = pos; i < result.length(); i++) {
				char c = result.charAt(i);
				if (c == '{') braceDepth++;
				else if (c == '}') {
					braceDepth--;
					if (braceDepth == 0) {
						blockEnd = i + 1;
						break;
					}
				}
			}
			if (blockEnd < 0) continue;

			// Extract the initializer block content
			String block = result.substring(blockStart, blockEnd);

			// Replace wrongVar.add(new TypeName()) with listFieldName.add(new TypeName())
			// Only within this block
			String addTarget = ".add(new " + typeName + "())";
			String fixed = block;
			// Find all xxx.add(new TypeName()) patterns in the block
			java.util.regex.Pattern addP = java.util.regex.Pattern.compile(
				"([a-z][a-z0-9_]*)\\.add\\(new " + java.util.regex.Pattern.quote(typeName) + "\\(\\)\\)");
			java.util.regex.Matcher addM = addP.matcher(block);
			StringBuffer blockSb = new StringBuffer();
			boolean blockChanged = false;
			while (addM.find()) {
				String usedVar = addM.group(1);
				if (!usedVar.equals(listFieldName)) {
					addM.appendReplacement(blockSb, listFieldName + addTarget);
					blockChanged = true;
				}
			}
			if (blockChanged) {
				addM.appendTail(blockSb);
				fixed = blockSb.toString();
				result.replace(blockStart, blockEnd, fixed);
				offset += fixed.length() - block.length();
			}
		}

		return result.toString();
	}

	/**
	 * Fix VARCHAR group assignments where moveAlphanumericToAlphanumeric returns
	 * String but the target is a VARCHAR group type (has _length + _data sub-fields).
	 *
	 * Pattern: path.field = CobolMove.moveAlphanumericToAlphanumeric(source, len);
	 * Fix: path.field.field_data = CobolMove.moveAlphanumericToAlphanumeric(source, len);
	 *
	 * Strategy: collect all VARCHAR group field names (fields where XxxType has
	 * xxx_data and xxx_length sub-fields). For each assignment line, check if the
	 * last component of the LHS path is a VARCHAR field, AND that the immediately
	 * preceding component is declared as a type that CONTAINS the VARCHAR field.
	 */
	private String postProcessFixVarcharGroupAssignment(String content) {
		// Step 1: Find all VARCHAR group field names by looking at their type classes.
		// A VARCHAR group is: class XxxType { protected xxx_length; protected xxx_data; }
		// declared as: protected XxxType xxx = new XxxType();
		// Build: Map<fieldName, Set<parentTypeName>> so we know which parent types have
		// this field as a VARCHAR group vs a plain String.
		final java.util.Map<String, java.util.Set<String>> varcharFieldParentTypes = new java.util.LinkedHashMap<>();

		java.util.regex.Pattern typedFieldPattern = java.util.regex.Pattern.compile(
			"protected ([A-Z][A-Za-z0-9_]*Type) ([a-z][a-z0-9_]*) = new \\1\\(\\);");
		java.util.regex.Matcher fm = typedFieldPattern.matcher(content);
		while (fm.find()) {
			String typeName = fm.group(1);
			String fieldName = fm.group(2);

			// Check if this type has _data and _length sub-fields
			String classDecl = "class " + typeName + " {";
			int classIdx = content.indexOf(classDecl);
			if (classIdx < 0) continue;

			int searchEnd = Math.min(classIdx + 500, content.length());
			String classBody = content.substring(classIdx, searchEnd);
			if (!classBody.contains(fieldName + "_data") || !classBody.contains(fieldName + "_length")) {
				continue;
			}

			// Find the enclosing parent type by tracking brace depth.
			// We need the class that is OPEN (unclosed) at the field declaration position.
			String before = content.substring(0, fm.start());
			java.util.List<String> classStack = new java.util.ArrayList<>();
			java.util.regex.Pattern classOpenPattern = java.util.regex.Pattern.compile(
				"class ([A-Z][A-Za-z0-9_]*(?:Type)?) \\{");
			java.util.regex.Matcher classM = classOpenPattern.matcher(before);
			int searchPos = 0;
			while (classM.find()) {
				// Count braces between searchPos and this match to track depth
				int matchEnd = classM.end();
				String segment = before.substring(searchPos, classM.start());
				for (int ci = 0; ci < segment.length(); ci++) {
					if (segment.charAt(ci) == '}') {
						if (!classStack.isEmpty()) classStack.remove(classStack.size() - 1);
					}
				}
				classStack.add(classM.group(1));
				searchPos = matchEnd;
			}
			// Count remaining closing braces
			String remaining = before.substring(searchPos);
			for (int ci = 0; ci < remaining.length(); ci++) {
				if (remaining.charAt(ci) == '}') {
					if (!classStack.isEmpty()) classStack.remove(classStack.size() - 1);
				}
			}
			// The last open class in the stack is the enclosing parent
			if (!classStack.isEmpty()) {
				String parentType = classStack.get(classStack.size() - 1);
				// Ensure it ends with "Type" to match our pattern
				if (parentType.endsWith("Type")) {
					varcharFieldParentTypes.computeIfAbsent(fieldName, k -> new java.util.LinkedHashSet<>()).add(parentType);
				}
			}
		}

		if (varcharFieldParentTypes.isEmpty()) {
			return content;
		}

		// Step 2: Build a map of parentTypeName -> parentVarName
		// e.g., VB000500Type -> vb000500
		final java.util.Map<String, String> typeToVar = new java.util.LinkedHashMap<>();
		java.util.regex.Matcher fm2 = typedFieldPattern.matcher(content);
		while (fm2.find()) {
			typeToVar.put(fm2.group(1), fm2.group(2));
		}

		// Step 3: For each VARCHAR field, find assignments where the path ends with
		// ...parentVar.fieldName = ... and the parentVar is of a type that has the VARCHAR field.
		// The full path may have additional prefixes (e.g., r_tb0005.vb000500.aliasextid).
		for (java.util.Map.Entry<String, java.util.Set<String>> entry : varcharFieldParentTypes.entrySet()) {
			String fieldName = entry.getKey();
			java.util.Set<String> parentTypes = entry.getValue();

			for (String parentType : parentTypes) {
				String parentVar = typeToVar.get(parentType);
				if (parentVar == null) continue;

				// Fix: ...parentVar.fieldName = CobolMove.moveAlphanumericToAlphanumeric(...)
				// -> ...parentVar.fieldName.fieldName_data = CobolMove.moveAlphanumericToAlphanumeric(...)
				// String.replace does substring matching, so this handles any prefix path.
				String search = parentVar + "." + fieldName + " = CobolMove.moveAlphanumericToAlphanumeric(";
				String replace = parentVar + "." + fieldName + "." + fieldName + "_data = CobolMove.moveAlphanumericToAlphanumeric(";
				content = content.replace(search, replace);

				// Fix: SQL FETCH patterns where the group is assigned via _vc (VARCHAR cursor variable)
				// Pattern: parentVar.fieldName = _vc...  (any assignment from _vc)
				// Also: parentVar.fieldName = CobolMove.moveAlphanumericToNumeric(
				String searchMoveAN = parentVar + "." + fieldName + " = CobolMove.moveAlphanumericToNumeric(";
				String replaceMoveAN = parentVar + "." + fieldName + "." + fieldName + "_data = CobolMove.moveAlphanumericToNumeric(";
				content = content.replace(searchMoveAN, replaceMoveAN);

				// Fix: compareAlphanumeric(parentVar.fieldName, ...) -> compareAlphanumeric(parentVar.fieldName.fieldName_data, ...)
				String searchCompare = "compareAlphanumeric(" + parentVar + "." + fieldName + ",";
				String replaceCompare = "compareAlphanumeric(" + parentVar + "." + fieldName + "." + fieldName + "_data,";
				content = content.replace(searchCompare, replaceCompare);
				String searchCompare2 = "compareAlphanumeric(" + parentVar + "." + fieldName + ")";
				String replaceCompare2 = "compareAlphanumeric(" + parentVar + "." + fieldName + "." + fieldName + "_data)";
				content = content.replace(searchCompare2, replaceCompare2);

				// Fix: CobolIntrinsic.reverse(parentVar.fieldName) -> CobolIntrinsic.reverse(parentVar.fieldName.fieldName_data)
				String searchReverse = "reverse(" + parentVar + "." + fieldName + ")";
				String replaceReverse = "reverse(" + parentVar + "." + fieldName + "." + fieldName + "_data)";
				content = content.replace(searchReverse, replaceReverse);

				// Fix length references in the same context
				String lenSearch = parentVar + "." + fieldName + ".length()";
				String lenReplace = parentVar + "." + fieldName + "." + fieldName + "_data.length()";
				content = content.replace(lenSearch, lenReplace);

				// Fix: any remaining assignment where group is used as String
				// Pattern: ...parentVar.fieldName, (as argument to a method expecting String)
				// This is a more aggressive fix, so only apply to known safe patterns
				String searchArg = ", " + parentVar + "." + fieldName + ")";
				String replaceArg = ", " + parentVar + "." + fieldName + "." + fieldName + "_data)";
				content = content.replace(searchArg, replaceArg);
				String searchArg2 = ", " + parentVar + "." + fieldName + ",";
				String replaceArg2 = ", " + parentVar + "." + fieldName + "." + fieldName + "_data,";
				content = content.replace(searchArg2, replaceArg2);
				String searchArg3 = "(" + parentVar + "." + fieldName + ",";
				String replaceArg3 = "(" + parentVar + "." + fieldName + "." + fieldName + "_data,";
				content = content.replace(searchArg3, replaceArg3);
			}
		}

		return content;
	}

	/**
	 * Fix VARCHAR _data/_length references on plain String fields.
	 * When a VARCHAR field was flattened to a plain String (from a flat copybook),
	 * the generated code references xxx_data and xxx_length as sub-fields but they
	 * don't exist on String.
	 *
	 * Strategy: for each inner class (type), build a map of which fields are String vs Typed.
	 * Then find container variables of that type, and fix references to .field.field_data
	 * where field is a String in that specific type.
	 */
	private String postProcessFixVarcharDataOnString(String content) {
		// Step 1: Parse each class to get its String fields and typed fields.
		// Map: className -> Set<String field names declared as plain String>
		final java.util.Map<String, java.util.Set<String>> classStringFields = new java.util.LinkedHashMap<>();
		// Map: className -> Set<String field names declared as typed>
		final java.util.Map<String, java.util.Set<String>> classTypedFields = new java.util.LinkedHashMap<>();

		// Find all class declarations and their fields
		final java.util.regex.Pattern classPattern = java.util.regex.Pattern.compile(
			"class ([A-Z][A-Za-z0-9_]*(?:Type)?) \\{");
		java.util.regex.Matcher cm = classPattern.matcher(content);

		while (cm.find()) {
			String className = cm.group(1);
			int classStart = cm.end();

			// Find the end of this class by tracking braces
			int braceDepth = 1;
			int classEnd = classStart;
			for (int i = classStart; i < content.length() && braceDepth > 0; i++) {
				char c = content.charAt(i);
				if (c == '{') braceDepth++;
				else if (c == '}') {
					braceDepth--;
					if (braceDepth == 0) {
						classEnd = i;
					}
				}
			}

			// Only look at direct fields (not nested class fields)
			// For simplicity, scan only the "top-level" lines (not inside nested classes)
			String classBody = content.substring(classStart, classEnd);

			// Remove nested class bodies to avoid picking up their fields
			StringBuilder flatBody = new StringBuilder();
			int depth = 0;
			for (int i = 0; i < classBody.length(); i++) {
				char c = classBody.charAt(i);
				if (c == '{') depth++;
				else if (c == '}') depth--;
				if (depth <= 0) {
					flatBody.append(c);
					depth = 0; // reset
				}
			}
			String flatContent = flatBody.toString();

			java.util.Set<String> strFields = new java.util.LinkedHashSet<>();
			java.util.Set<String> typFields = new java.util.LinkedHashSet<>();

			java.util.regex.Pattern sfp = java.util.regex.Pattern.compile(
				"protected String ([a-z][a-z0-9_]*)\\s*=");
			java.util.regex.Matcher sfm = sfp.matcher(flatContent);
			while (sfm.find()) {
				strFields.add(sfm.group(1));
			}

			java.util.regex.Pattern tfp = java.util.regex.Pattern.compile(
				"protected ([A-Z][A-Za-z0-9_]*Type) ([a-z][a-z0-9_]*) = new \\1\\(\\);");
			java.util.regex.Matcher tfm = tfp.matcher(flatContent);
			while (tfm.find()) {
				typFields.add(tfm.group(2));
			}

			if (!strFields.isEmpty()) {
				classStringFields.put(className, strFields);
			}
			if (!typFields.isEmpty()) {
				classTypedFields.put(className, typFields);
			}
		}

		// Step 2: Build map of containerVar -> containerTypeName
		final java.util.Map<String, String> varToType = new java.util.LinkedHashMap<>();
		final java.util.regex.Pattern typedVarPattern = java.util.regex.Pattern.compile(
			"protected ([A-Z][A-Za-z0-9_]*(?:Type)?) ([a-z][a-z0-9_]*) = new \\1\\(\\);");
		java.util.regex.Matcher tvm = typedVarPattern.matcher(content);
		while (tvm.find()) {
			varToType.put(tvm.group(2), tvm.group(1));
		}

		// Step 3: For each class that has String fields which are NOT typed in that same class,
		// find container variables of that class type and fix .field.field_data references.
		boolean changed = false;
		for (java.util.Map.Entry<String, java.util.Set<String>> entry : classStringFields.entrySet()) {
			String className = entry.getKey();
			java.util.Set<String> strFields = entry.getValue();
			java.util.Set<String> typFields = classTypedFields.getOrDefault(className, java.util.Collections.emptySet());

			for (String field : strFields) {
				if (typFields.contains(field)) {
					continue; // This field is ALSO typed in this class, skip
				}

				// Find all container vars of this class type
				for (java.util.Map.Entry<String, String> varEntry : varToType.entrySet()) {
					String varName = varEntry.getKey();
					String varType = varEntry.getValue();
					if (!varType.equals(className)) continue;

					// Fix: varName.field.field_data -> varName.field
					String dataRef = varName + "." + field + "." + field + "_data";
					String dataFix = varName + "." + field;
					if (content.contains(dataRef)) {
						content = content.replace(dataRef, dataFix);
						changed = true;
						LOG.info("Post-processing: fixed VARCHAR _data on String: {}.{}.{}_data -> {}.{}",
							varName, field, field, varName, field);
					}

					// Fix: varName.field.field_length -> new BigDecimal(varName.field.length())
					String lengthRef = varName + "." + field + "." + field + "_length";
					if (content.contains(lengthRef)) {
						String lengthFix = "new BigDecimal(" + varName + "." + field + ".length())";
						content = content.replace(lengthRef, lengthFix);
						changed = true;
						LOG.info("Post-processing: fixed VARCHAR _length on String: {}.{}.{}_length",
							varName, field, field);
					}
				}
			}
		}

		return content;
	}

	/**
	 * Fix doubled variable prefixes like r_r_, wk_wk_, estr_estr_.
	 * The code generator sometimes doubles the prefix when qualifying variables,
	 * producing r_r_xxx instead of r_xxx.
	 *
	 * Strategy: find all variable declarations, then detect references with doubled prefixes.
	 */
	private String postProcessFixDoubledPrefixes(String content) {
		// Collect all declared field names (instance variables).
		final java.util.Set<String> declaredFields = new java.util.LinkedHashSet<>();
		final java.util.regex.Pattern fieldDeclPattern = java.util.regex.Pattern.compile(
			"protected (?:List<)?[A-Za-z_][A-Za-z0-9_<>]*(?:>)? ([a-z_][a-z0-9_]*)\\s*[=;]");
		java.util.regex.Matcher fdm = fieldDeclPattern.matcher(content);
		while (fdm.find()) {
			declaredFields.add(fdm.group(1));
		}

		if (declaredFields.isEmpty()) {
			return content;
		}

		// Find references to variables with doubled prefixes.
		// Pattern: a word boundary followed by prefix_prefix_rest where prefix_rest is a declared field.
		// E.g., r_r_tr2103_lk where r_tr2103_lk is declared.
		// Also: wk_wk_array_pesos where wk_array_pesos is declared.
		// Also: estr_estr_origens where estr_origens is declared.

		// Build a set of doubled-prefix -> correct mapping.
		final java.util.Map<String, String> replacements = new java.util.LinkedHashMap<>();
		for (String field : declaredFields) {
			// Try to detect the prefix: everything up to and including the first underscore
			int firstUnderscore = field.indexOf('_');
			if (firstUnderscore < 0 || firstUnderscore >= field.length() - 1) {
				continue;
			}
			String prefix = field.substring(0, firstUnderscore + 1); // e.g., "r_", "wk_", "estr_"
			String doubled = prefix + field; // e.g., "r_r_tr2103_lk", "wk_wk_array_pesos"

			if (content.contains(doubled) && !declaredFields.contains(doubled)) {
				replacements.put(doubled, field);
			}
		}

		for (java.util.Map.Entry<String, String> entry : replacements.entrySet()) {
			// Safety check: if the shortened name is already declared as a variable,
			// do NOT replace — just remove the doubled-prefix declaration to avoid collision.
			String doubledName = entry.getKey();
			String shortName = entry.getValue();
			// Check if the doubled name has a declaration (it was added by an earlier post-processor)
			String doubledDeclPattern = "protected\\s+\\S+\\s+" + java.util.regex.Pattern.quote(doubledName) + "\\s*=";
			java.util.regex.Matcher ddm = java.util.regex.Pattern.compile(doubledDeclPattern).matcher(content);
			if (ddm.find()) {
				// The doubled name has its own declaration — check if shortName also has one
				String shortDeclPattern = "protected\\s+\\S+\\s+" + java.util.regex.Pattern.quote(shortName) + "\\s*=";
				java.util.regex.Matcher sdm = java.util.regex.Pattern.compile(shortDeclPattern).matcher(content);
				int shortDeclCount = 0;
				while (sdm.find()) shortDeclCount++;
				if (shortDeclCount >= 1) {
					// Both exist — remove the doubled declaration line and replace references only
					String[] contentLines = content.split("\n", -1);
					StringBuilder sb = new StringBuilder();
					for (String line : contentLines) {
						if (java.util.regex.Pattern.compile(doubledDeclPattern).matcher(line).find()) {
							// Skip this declaration line
							LOG.info("Post-processing: removed duplicate declaration for {} (conflicts with {})", doubledName, shortName);
						} else {
							sb.append(line.replace(doubledName, shortName)).append("\n");
						}
					}
					content = sb.toString();
					if (content.endsWith("\n")) content = content.substring(0, content.length() - 1);
					LOG.info("Post-processing: fixed doubled prefix {} -> {} (removed duplicate decl)", doubledName, shortName);
					continue;
				}
			}
			content = content.replace(entry.getKey(), entry.getValue());
			LOG.info("Post-processing: fixed doubled prefix {} -> {}", entry.getKey(), entry.getValue());
		}

		return content;
	}

	/**
	 * Fix safeGet called on a group type instead of its List child field.
	 * When COBOL has OCCURS inside a group (01 level), the generated code sometimes calls
	 * safeGet(groupVar, idx) instead of safeGet(groupVar.listField, idx).
	 *
	 * Strategy: find safeGet calls where the first arg is a variable declared as a
	 * CustomType (not a List). Then find the List field inside that CustomType
	 * and fix the reference.
	 */
	private String postProcessFixSafeGetOnGroupType(String content) {
		// Step 1: Build map of typedVar -> typeName for all typed fields.
		final java.util.Map<String, String> varToType = new java.util.LinkedHashMap<>();
		final java.util.regex.Pattern typedFieldPattern = java.util.regex.Pattern.compile(
			"protected ([A-Z][A-Za-z0-9_]*(?:Type)?) ([a-z_][a-z0-9_]*) = new \\1\\(\\);");
		java.util.regex.Matcher tfm = typedFieldPattern.matcher(content);
		while (tfm.find()) {
			varToType.put(tfm.group(2), tfm.group(1));
		}

		// Exclude variables that are also declared as List fields anywhere in the file.
		// These should NOT be redirected since they're already Lists.
		final java.util.Set<String> listFieldNames = new java.util.LinkedHashSet<>();
		java.util.regex.Matcher lfmExclude = java.util.regex.Pattern.compile(
			"protected List<[A-Z][A-Za-z0-9_]*(?:Type)?> ([a-z_][a-z0-9_]*)\\s*=").matcher(content);
		while (lfmExclude.find()) {
			listFieldNames.add(lfmExclude.group(1));
		}
		varToType.keySet().removeAll(listFieldNames);

		// Step 2: Build map of typeName -> listFieldName for types that contain a List field.
		// Use class-by-class parsing instead of a single regex across the whole file.
		final java.util.Map<String, String> typeToListField = new java.util.LinkedHashMap<>();
		final java.util.regex.Pattern classOpenPattern = java.util.regex.Pattern.compile(
			"class ([A-Z][A-Za-z0-9_]*(?:Type)?) \\{");
		java.util.regex.Matcher com = classOpenPattern.matcher(content);
		while (com.find()) {
			String className = com.group(1);
			int classBodyStart = com.end();

			// Find the end of this class by tracking braces
			int braceDepth = 1;
			int classEnd = classBodyStart;
			for (int i = classBodyStart; i < content.length() && braceDepth > 0; i++) {
				char c = content.charAt(i);
				if (c == '{') braceDepth++;
				else if (c == '}') {
					braceDepth--;
					if (braceDepth == 0) {
						classEnd = i;
					}
				}
			}

			String classBody = content.substring(classBodyStart, classEnd);

			// Find List fields in this class body (at the top level, i.e., not inside nested classes)
			// Remove nested class/block bodies to find only direct fields
			StringBuilder flatBody = new StringBuilder();
			int depth = 0;
			for (int i = 0; i < classBody.length(); i++) {
				char c = classBody.charAt(i);
				if (c == '{') depth++;
				else if (c == '}') depth--;
				if (depth <= 0) {
					flatBody.append(c);
					depth = 0;
				}
			}

			java.util.regex.Pattern listFieldPattern = java.util.regex.Pattern.compile(
				"protected List<([A-Z][A-Za-z0-9_]*(?:Type)?)> ([a-z_][a-z0-9_]*)\\s*=");
			java.util.regex.Matcher lfm = listFieldPattern.matcher(flatBody.toString());
			if (lfm.find()) {
				typeToListField.put(className, lfm.group(2));
			}
		}

		// Step 3: Find safeGet calls with typed (non-List) variables - bare form.
		// E.g., safeGet(varName, idx) where varName is a typed field with a List child.
		boolean changed = false;
		for (java.util.Map.Entry<String, String> entry : varToType.entrySet()) {
			String varName = entry.getKey();
			String typeName = entry.getValue();

			String listField = typeToListField.get(typeName);
			if (listField == null) continue;

			String safeGetPattern = "safeGet(" + varName + ", ";
			if (content.contains(safeGetPattern)) {
				String safeGetFix = "safeGet(" + varName + "." + listField + ", ";
				content = content.replace(safeGetPattern, safeGetFix);
				changed = true;
				LOG.info("Post-processing: fixed safeGet({}) -> safeGet({}.{})", varName, varName, listField);
			}
		}

		// Step 4: Handle container.varName patterns in safeGet.
		// E.g., safeGet(container.varName, idx) where varName's type has a List child.
		for (java.util.Map.Entry<String, String> entry : varToType.entrySet()) {
			String varName = entry.getKey();
			String typeName = entry.getValue();
			String listField = typeToListField.get(typeName);
			if (listField == null) continue;

			// Pattern: safeGet(xxx.varName,
			java.util.regex.Pattern containerSafeGet = java.util.regex.Pattern.compile(
				"safeGet\\(([a-z_][a-z0-9_]*(?:\\.[a-z_][a-z0-9_]*)*)\\." + java.util.regex.Pattern.quote(varName) + ",\\s");
			java.util.regex.Matcher csm = containerSafeGet.matcher(content);
			StringBuffer sb = new StringBuffer();
			boolean found = false;
			while (csm.find()) {
				String containerPath = csm.group(1);
				csm.appendReplacement(sb, "safeGet(" + containerPath + "." + varName + "." + listField + ", ");
				found = true;
			}
			if (found) {
				csm.appendTail(sb);
				content = sb.toString();
				changed = true;
				LOG.info("Post-processing: fixed safeGet(xxx.{}) -> safeGet(xxx.{}.{})", varName, varName, listField);
			}
		}

		// Step 5: Handle direct .get() calls on group types.
		// Only fix the specific pattern from doubled-prefix residue:
		// varName.varName.get( -> varName.listField.get(
		// This is when the intermediate field name equals the variable name itself.
		for (java.util.Map.Entry<String, String> entry : varToType.entrySet()) {
			String varName = entry.getKey();
			String typeName = entry.getValue();
			String listField = typeToListField.get(typeName);
			if (listField == null) continue;

			// Pattern 5a: varName.varName.get( — doubled-prefix residue
			// After doubled prefix fix, r_r_tr2103_lk.r_r_tr2103_lk.get(...)
			// becomes r_tr2103_lk.r_tr2103_lk.get(...). Fix the intermediate to listField.
			String doubledGetPattern = varName + "." + varName + ".get(";
			if (content.contains(doubledGetPattern)) {
				String fixedDoubledGet = varName + "." + listField + ".get(";
				content = content.replace(doubledGetPattern, fixedDoubledGet);
				LOG.info("Post-processing: fixed {}.{}.get() -> {}.{}.get()", varName, varName, varName, listField);
			}
		}

		return content;
	}

	/**
	 * Fix BigDecimal-to-String type mismatch in setter methods and assignments.
	 *
	 * Pattern 1 (setter): public void set_xxxm(String val) { _xxx = CobolMove.moveAlphanumericToNumeric(val, N, 0); }
	 * The field _xxx is String but moveAlphanumericToNumeric returns BigDecimal.
	 * Fix: replace moveAlphanumericToNumeric(val, N, 0) with moveAlphanumericToAlphanumeric(val, N)
	 *
	 * Pattern 2 (assignment): field = CobolMove.moveAlphanumericToNumeric(getBigDecimalMethod(), N, 0)
	 * The getter returns BigDecimal but moveAlphanumericToNumeric expects String.
	 * Fix: wrap with .toPlainString()
	 */
	private String postProcessFixBigDecimalToStringMismatch(String content) {
		// Pattern 1: Fix setter methods where moveAlphanumericToNumeric is used but field is String.
		// Detect: "public void set_xxxm(String val) { _yyy = CobolMove.moveAlphanumericToNumeric(val,"
		// The _yyy field must be a String field for this to be a bug.

		// Collect all String field names
		final java.util.Set<String> stringFields = new java.util.LinkedHashSet<>();
		final java.util.regex.Pattern stringFieldPattern = java.util.regex.Pattern.compile(
			"protected String ([a-z_][a-z0-9_]*)\\s*=");
		java.util.regex.Matcher sfm = stringFieldPattern.matcher(content);
		while (sfm.find()) {
			stringFields.add(sfm.group(1));
		}

		// Collect BigDecimal fields to detect ambiguous names
		final java.util.Set<String> numericFieldNames = new java.util.LinkedHashSet<>();
		final java.util.regex.Pattern bdFieldPatternAmbig = java.util.regex.Pattern.compile(
			"protected BigDecimal ([a-z_][a-z0-9_]*)\\s*=");
		java.util.regex.Matcher bfmAmbig = bdFieldPatternAmbig.matcher(content);
		while (bfmAmbig.find()) {
			numericFieldNames.add(bfmAmbig.group(1));
		}

		// Remove ambiguous fields (declared as BOTH String AND BigDecimal in different classes)
		stringFields.removeAll(numericFieldNames);

		// Fix Pattern 1: setter body assigns BigDecimal (moveAlphanumericToNumeric) to String field
		// Only match within setter method declarations:
		// "public void setXxx(String val) { fieldName = CobolMove.moveAlphanumericToNumeric(val, N, 0); }"
		// This ensures we don't accidentally change assignments on BigDecimal fields
		// that happen to share a name with a String field in a different class.
		final java.util.regex.Pattern setterBugPattern = java.util.regex.Pattern.compile(
			"(public void set[A-Za-z0-9_]*\\(String val\\) \\{ )([a-z_][a-z0-9_]*) = CobolMove\\.moveAlphanumericToNumeric\\(val, (\\d+), 0\\);( \\})");
		java.util.regex.Matcher sbm = setterBugPattern.matcher(content);
		StringBuffer sb1 = new StringBuffer();
		boolean changed1 = false;
		while (sbm.find()) {
			String prefix = sbm.group(1);
			String fieldName = sbm.group(2);
			String digits = sbm.group(3);
			String suffix = sbm.group(4);
			if (stringFields.contains(fieldName)) {
				sbm.appendReplacement(sb1, java.util.regex.Matcher.quoteReplacement(
					prefix + fieldName + " = CobolMove.moveAlphanumericToAlphanumeric(val, " + digits + ");" + suffix));
				changed1 = true;
			}
		}
		if (changed1) {
			sbm.appendTail(sb1);
			content = sb1.toString();
			LOG.info("Post-processing: fixed moveAlphanumericToNumeric -> moveAlphanumericToAlphanumeric for String setters");
		}

		// Fix Pattern 2: moveAlphanumericToNumeric called with BigDecimal getter as first arg.
		// The first arg should be a String but a getter returns BigDecimal.
		// Pattern: "= CobolMove.moveAlphanumericToNumeric(xxx.getYyy(), N, 0)"
		// where getYyy() returns BigDecimal. We can't easily know the return type,
		// so instead detect the compilation error pattern: the field being assigned
		// is BigDecimal (valid assignment) but the getter returns BigDecimal (invalid arg to
		// moveAlphanumericToNumeric which expects String).
		// Fix: replace moveAlphanumericToNumeric(expr, N, 0) with moveNumericToNumeric(expr, N, 0)
		// when the expression is a BigDecimal getter call.
		// Actually, the safest fix is: if the LHS is a BigDecimal field and the source expr
		// is from a getter that returns BigDecimal, use moveNumericToNumeric directly.

		// Collect BigDecimal fields
		final java.util.Set<String> bigDecimalFields = new java.util.LinkedHashSet<>();
		final java.util.regex.Pattern bdFieldPattern = java.util.regex.Pattern.compile(
			"protected BigDecimal ([a-z_][a-z0-9_]*)\\s*=");
		java.util.regex.Matcher bfm = bdFieldPattern.matcher(content);
		while (bfm.find()) {
			bigDecimalFields.add(bfm.group(1));
		}

		// Collect BigDecimal getter methods
		final java.util.Set<String> bigDecimalGetters = new java.util.LinkedHashSet<>();
		final java.util.regex.Pattern bdGetterPattern = java.util.regex.Pattern.compile(
			"public BigDecimal (get[A-Za-z0-9_]*)\\(\\)");
		java.util.regex.Matcher bgm = bdGetterPattern.matcher(content);
		while (bgm.find()) {
			bigDecimalGetters.add(bgm.group(1));
		}

		// Collect String getter methods and remove ambiguous ones
		// (same getter name exists returning both BigDecimal and String in different classes)
		final java.util.Set<String> stringGetters = new java.util.LinkedHashSet<>();
		final java.util.regex.Pattern strGetterPattern = java.util.regex.Pattern.compile(
			"public String (get[A-Za-z0-9_]*)\\(\\)");
		java.util.regex.Matcher sgm = strGetterPattern.matcher(content);
		while (sgm.find()) {
			stringGetters.add(sgm.group(1));
		}
		bigDecimalGetters.removeAll(stringGetters);

		// Fix: field = CobolMove.moveAlphanumericToNumeric(xxx.getYyy(), N, 0)
		// where field is BigDecimal and getYyy returns BigDecimal
		// -> field = CobolMove.moveNumericToNumeric(xxx.getYyy(), N, 0)
		if (!bigDecimalGetters.isEmpty()) {
			for (String getter : bigDecimalGetters) {
				String bugPattern = "CobolMove.moveAlphanumericToNumeric(" ;
				// This is tricky because we need to match the specific getter in context.
				// Let's use a more targeted regex.
				String getterCall = "\\." + java.util.regex.Pattern.quote(getter) + "\\(\\)";
				java.util.regex.Pattern p2 = java.util.regex.Pattern.compile(
					"CobolMove\\.moveAlphanumericToNumeric\\(([^,]*" + getterCall + "),\\s*(\\d+),\\s*(\\d+)\\)");
				java.util.regex.Matcher m2 = p2.matcher(content);
				StringBuffer sb2 = new StringBuffer();
				boolean changed2 = false;
				while (m2.find()) {
					String fullExpr = m2.group(1);
					String intDigits = m2.group(2);
					String decDigits = m2.group(3);
					m2.appendReplacement(sb2, "CobolMove.moveNumericToNumeric(" + fullExpr + ", " + intDigits + ", " + decDigits + ")");
					changed2 = true;
				}
				if (changed2) {
					m2.appendTail(sb2);
					content = sb2.toString();
					LOG.info("Post-processing: fixed moveAlphanumericToNumeric with BigDecimal getter {} -> moveNumericToNumeric", getter);
				}
			}
		}

		return content;
	}

	/**
	 * Fixes moveNumericToNumeric calls where the source argument is a String field
	 * (from a flattened VARCHAR group). When a DDS copybook flattens a VARCHAR group
	 * like PRENOM (with sub-fields PRENOM-LENGTH and PRENOM-DATA) into a plain PIC X(34),
	 * the transformer cannot resolve sub-field references and falls back to the parent
	 * String field. This produces:
	 *   CobolMove.moveNumericToNumeric(source.stringField, N, M)
	 * which fails because moveNumericToNumeric expects BigDecimal.
	 * Fix: replace with moveAlphanumericToNumeric which accepts String.
	 */
	private String postProcessFixMoveNumericFromString(String content) {
		// Step 1: Collect all field names declared as String (across all classes)
		final java.util.Set<String> stringFieldNames = new java.util.LinkedHashSet<>();
		final java.util.regex.Pattern strFieldPat = java.util.regex.Pattern.compile(
			"protected\\s+String\\s+([a-z][a-z0-9_]*)\\s*=");
		java.util.regex.Matcher sfm = strFieldPat.matcher(content);
		while (sfm.find()) {
			stringFieldNames.add(sfm.group(1));
		}

		// Step 2: Collect all field names declared as BigDecimal
		final java.util.Set<String> bdFieldNames = new java.util.LinkedHashSet<>();
		final java.util.regex.Pattern bdFieldPat = java.util.regex.Pattern.compile(
			"protected\\s+(?:java\\.math\\.)?BigDecimal\\s+([a-z][a-z0-9_]*)\\s*=");
		java.util.regex.Matcher bfm = bdFieldPat.matcher(content);
		while (bfm.find()) {
			bdFieldNames.add(bfm.group(1));
		}

		// Only consider fields that are ONLY String (not also BigDecimal in another class)
		final java.util.Set<String> onlyStringFields = new java.util.LinkedHashSet<>(stringFieldNames);
		onlyStringFields.removeAll(bdFieldNames);

		if (onlyStringFields.isEmpty()) {
			return content;
		}

		// Step 3: Find moveNumericToNumeric(path.stringField, N, M) and fix
		// Pattern: CobolMove.moveNumericToNumeric(dotted.path.field, digits, decimals)
		final java.util.regex.Pattern movePattern = java.util.regex.Pattern.compile(
			"CobolMove\\.moveNumericToNumeric\\(([a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)*),\\s*(\\d+),\\s*(\\d+)\\)");
		java.util.regex.Matcher mm = movePattern.matcher(content);
		StringBuffer sb = new StringBuffer();
		boolean changed = false;
		while (mm.find()) {
			String varPath = mm.group(1);
			String intDigits = mm.group(2);
			String decDigits = mm.group(3);
			// Extract the last segment of the path (the actual field name)
			String lastSegment = varPath.contains(".")
				? varPath.substring(varPath.lastIndexOf('.') + 1)
				: varPath;
			if (onlyStringFields.contains(lastSegment)) {
				mm.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
					"CobolMove.moveAlphanumericToNumeric(" + varPath + ", " + intDigits + ", " + decDigits + ")"));
				changed = true;
				LOG.info("Post-processing: fixed moveNumericToNumeric with String source '{}' -> moveAlphanumericToNumeric", varPath);
			}
		}
		if (changed) {
			mm.appendTail(sb);
			content = sb.toString();
		}

		// Step 4: Fix BigDecimal.add/subtract/multiply/divide(path.stringField)
		// When a String field is passed as argument to a BigDecimal arithmetic method,
		// wrap it with CobolMove.toBigDecimal() so it compiles.
		// Pattern: .add(path.stringField) -> .add(CobolMove.toBigDecimal(path.stringField))
		final java.util.regex.Pattern arithPattern = java.util.regex.Pattern.compile(
			"\\.(add|subtract|multiply|divide)\\(([a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)*)\\)");
		java.util.regex.Matcher am = arithPattern.matcher(content);
		StringBuffer sb2 = new StringBuffer();
		boolean changed2 = false;
		while (am.find()) {
			String method = am.group(1);
			String varPath = am.group(2);
			String lastSegment = varPath.contains(".")
				? varPath.substring(varPath.lastIndexOf('.') + 1)
				: varPath;
			if (onlyStringFields.contains(lastSegment)) {
				am.appendReplacement(sb2, java.util.regex.Matcher.quoteReplacement(
					"." + method + "(CobolMove.toBigDecimal(" + varPath + "))"));
				changed2 = true;
				LOG.info("Post-processing: fixed BigDecimal.{}() with String source '{}' -> wrapped with CobolMove.toBigDecimal()", method, varPath);
			}
		}
		if (changed2) {
			am.appendTail(sb2);
			content = sb2.toString();
		}

		return content;
	}

	/**
	 * Fix VARCHAR group variables used in String contexts.
	 * When a VARCHAR group (inner class with xxx_data and xxx_length fields) is used where
	 * a String is expected (e.g., String.valueOf(), CobolIntrinsic.reverse(), assignment from String),
	 * replace with .xxx_data access.
	 * Also fix assignment of String value to VARCHAR group: xxx = stringExpr -> xxx.xxx_data = stringExpr
	 */
	private String postProcessFixVarcharGroupInStringContext(String content) {
		// Build map of VARCHAR group fields: path.fieldName -> fieldName (for _data access)
		// Find inner classes that have both xxx_length and xxx_data fields
		java.util.Set<String> varcharGroupFieldNames = new java.util.LinkedHashSet<>();
		java.util.regex.Matcher vcClassMatcher = java.util.regex.Pattern.compile(
			"public class (\\w+)Type \\{[^}]*?protected[^}]*?(\\w+)_length[^}]*?\\2_data[^}]*?\\}",
			java.util.regex.Pattern.DOTALL).matcher(content);
		while (vcClassMatcher.find()) {
			String className = vcClassMatcher.group(1);
			String fieldBase = vcClassMatcher.group(2);
			// The instance variable name is the lowercased class name without "Type"
			String instanceName = className.substring(0, 1).toLowerCase() + className.substring(1);
			// Remove "Type" suffix if present in the mapping
			varcharGroupFieldNames.add(instanceName.toLowerCase());
		}

		// Also find VARCHAR group field instances by looking for typed field declarations
		// Pattern: protected XxxType fieldName = new XxxType();
		// where XxxType has xxx_length and xxx_data
		java.util.Map<String, String> typedFieldToDataField = new java.util.LinkedHashMap<>();
		// Also collect field names that are plain String in some classes
		java.util.Set<String> stringFieldNames = new java.util.LinkedHashSet<>();
		java.util.regex.Matcher strFm = java.util.regex.Pattern.compile(
			"protected\\s+String\\s+(\\w+)\\s*=").matcher(content);
		while (strFm.find()) {
			stringFieldNames.add(strFm.group(1));
		}
		java.util.regex.Matcher tfm = java.util.regex.Pattern.compile(
			"protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)").matcher(content);
		while (tfm.find()) {
			String typeName = tfm.group(1);
			String fieldName = tfm.group(2);
			// Check if this type has _data and _length subfields
			String dataFieldName = fieldName + "_data";
			String lengthFieldName = fieldName + "_length";
			if (content.contains("protected") && content.contains(dataFieldName) && content.contains(lengthFieldName)) {
				// SKIP if the same field name is also declared as plain String in some class
				// (ambiguous - global replacement would break the String version)
				if (!stringFieldNames.contains(fieldName)) {
					typedFieldToDataField.put(fieldName, dataFieldName);
				} else {
					LOG.info("Post-processing: skipping ambiguous VARCHAR field '{}' (also declared as String)", fieldName);
				}
			}
		}

		if (typedFieldToDataField.isEmpty()) return content;

		boolean changed = false;
		// Fix 1: VARCHAR group passed to String.valueOf(), CobolIntrinsic.reverse(), etc.
		// Pattern: String.valueOf(path.varcharField) or CobolIntrinsic.reverse(path.varcharField)
		// where varcharField is a typed VARCHAR group
		for (java.util.Map.Entry<String, String> entry : typedFieldToDataField.entrySet()) {
			String fieldName = entry.getKey();
			String dataField = entry.getValue();

			// Fix: functionCall(path.fieldName) where fieldName is VARCHAR group
			// -> functionCall(path.fieldName.fieldName_data)
			// Only when the expression ends with .fieldName) and there's no further .xxx after it
			String[] markers = {
				"." + fieldName + "))",  // end of nested function call
				"." + fieldName + "),",  // arg in function call
				"." + fieldName + ");",  // end of statement
			};
			for (String marker : markers) {
				String replacement = "." + fieldName + "." + dataField + marker.substring(marker.length() - 2);
				// Only replace in code contexts (lines containing function calls)
				if (content.contains(marker)) {
					// Check that preceding context is a function arg, not a field declaration
					String[] lines = content.split("\n", -1);
					for (int i = 0; i < lines.length; i++) {
						String line = lines[i];
						if (line.contains(marker) && !line.trim().startsWith("protected ") && !line.trim().startsWith("public ")
							&& !line.trim().startsWith("//")) {
							// Make sure we're in a method call context
							if (line.contains("String.valueOf(") || line.contains("CobolIntrinsic.reverse(")
								|| line.contains("CobolStringOps.") || line.contains("moveAlphanumericToAlphanumeric(")
								|| line.contains("CobolMove.") || line.contains("CobolComparison.")) {
								lines[i] = line.replace(marker, replacement);
								changed = true;
							}
						}
					}
					if (changed) {
						content = String.join("\n", lines);
					}
				}
			}

			// Fix 2: Assignment of String to VARCHAR group
			// Pattern: path.fieldName = CobolMove.moveAlphanumericToAlphanumeric(...)
			// -> path.fieldName.fieldName_data = CobolMove.moveAlphanumericToAlphanumeric(...)
			String assignPattern = "." + fieldName + " = CobolMove.moveAlphanumericToAlphanumeric(";
			if (content.contains(assignPattern)) {
				// Also check for STRING result assignment
				String replacement2 = "." + fieldName + "." + dataField + " = CobolMove.moveAlphanumericToAlphanumeric(";
				content = content.replace(assignPattern, replacement2);
				changed = true;
			}

			// Fix 3: Assignment from StringBuilder.toString() to VARCHAR group (STRING statement)
			String sbAssignPattern = "." + fieldName + " = CobolMove.moveAlphanumericToAlphanumeric(_sb.toString()";
			if (content.contains(sbAssignPattern)) {
				content = content.replace(sbAssignPattern,
					"." + fieldName + "." + dataField + " = CobolMove.moveAlphanumericToAlphanumeric(_sb.toString()");
				changed = true;
			}
		}

		if (changed) {
			LOG.info("Post-processing: fixed VARCHAR group in String context");
		}
		return content;
	}

	/**
	 * Fix self-referencing paths in OCCURS SEARCH ALL.
	 * When SEARCH ALL generates a size check, it sometimes uses the parent variable name
	 * as the field name: parent.parent.size() should be parent.listField.size().
	 * Also handles: parent.parent = ... (assignment to self)
	 *
	 * Detection: Find patterns where var.var appears and var is a declared typed variable
	 * whose type has exactly one List field.
	 */
	private String postProcessFixSelfReferencingOccursPath(String content) {
		// Find all typed variable declarations: "protected XxxType varName = new XxxType();"
		java.util.Map<String, String> typedVars = new java.util.LinkedHashMap<>(); // varName -> typeName
		java.util.regex.Matcher vm = java.util.regex.Pattern.compile(
			"^    protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)",
			java.util.regex.Pattern.MULTILINE).matcher(content);
		while (vm.find()) {
			typedVars.put(vm.group(2), vm.group(1));
		}

		boolean changed = false;
		for (java.util.Map.Entry<String, String> entry : typedVars.entrySet()) {
			String varName = entry.getKey();
			String typeName = entry.getValue();

			// Check if code has self-referencing pattern: varName.varName.
			String selfRef = varName + "." + varName + ".";
			String selfRefSize = varName + "." + varName + ".size()";
			if (!content.contains(selfRef)) continue;

			// Find the List field inside this type's class
			// Pattern: "protected List<XxxType> fieldName = new ArrayList"
			// The type class starts with "public class TypeName {" and we need to find List fields within it
			String classStart = "public class " + typeName + " {";
			int classIdx = content.indexOf(classStart);
			if (classIdx < 0) continue;

			// Find the List field within this class (search next ~5000 chars)
			int searchEnd = Math.min(classIdx + 5000, content.length());
			String classRegion = content.substring(classIdx, searchEnd);
			java.util.regex.Matcher listMatcher = java.util.regex.Pattern.compile(
				"protected\\s+List<(\\w+)>\\s+(\\w+)\\s*=\\s*new\\s+ArrayList").matcher(classRegion);

			// Find ALL List fields and pick the best match
			String listFieldName = null;
			String firstListField = null;
			while (listMatcher.find()) {
				String candidate = listMatcher.group(2);
				if (firstListField == null) firstListField = candidate;
				// Prefer a List field whose name is a substring of the parent variable name
				// e.g., w_table_parametres contains w_parametres
				if (varName.contains(candidate) || candidate.contains(varName)
					|| varName.endsWith(candidate) || candidate.endsWith(varName.substring(varName.indexOf('_') + 1))) {
					listFieldName = candidate;
					break;
				}
			}
			if (listFieldName == null) listFieldName = firstListField;

			if (listFieldName != null) {
				LOG.info("Post-processing: fixing self-referencing OCCURS path {}.{} -> {}.{}",
					varName, varName, varName, listFieldName);
				content = content.replace(selfRef, varName + "." + listFieldName + ".");
				changed = true;
			}
		}

		return content;
	}

	/**
	 * Fix OCCURS element paths that use the top-level variable name instead of the inner field name.
	 * Pattern: r_xxx.listField.get(idx).r_xxx.field -> r_xxx.listField.get(idx).innerField.field
	 *
	 * This happens when the 01-level variable name and the 05-level format name collide
	 * in qualified references through OCCURS structures.
	 * Example: COBOL structure
	 *   01 R-FILE211300.
	 *     04 FILE211300 OCCURS 11.
	 *       05 FILE211300. (from COPY DDSR)
	 *         10 CODSOC PIC X.
	 * generates r_vr211300.vr211300.get(idx).r_vr211300.codsoc but should be
	 *           r_vr211300.vr211300.get(idx).vr211300.codsoc
	 * The qualifier r_vr211300 is a top-level variable, not a field on FILE211300Type.
	 */
	private String postProcessFixOccursElementQualifier(String content) {
		// Collect all field names declared within each inner class type.
		// This lets us verify whether a qualifier after .get() is actually a valid
		// field of the element type or an incorrectly scoped reference.
		java.util.Map<String, java.util.Set<String>> classFields = new java.util.LinkedHashMap<>();
		java.util.regex.Pattern classPattern = java.util.regex.Pattern.compile(
			"public class (\\w+Type) \\{");
		java.util.regex.Matcher cm = classPattern.matcher(content);
		while (cm.find()) {
			String className = cm.group(1);
			int braceStart = content.indexOf("{", cm.start());
			if (braceStart < 0) continue;
			// Find matching closing brace
			int depth = 0;
			int braceEnd = -1;
			for (int i = braceStart; i < content.length(); i++) {
				char c = content.charAt(i);
				if (c == '{') depth++;
				else if (c == '}') {
					depth--;
					if (depth == 0) { braceEnd = i; break; }
				}
			}
			if (braceEnd < 0) continue;
			String body = content.substring(braceStart + 1, braceEnd);
			java.util.Set<String> fields = new java.util.LinkedHashSet<>();
			// Match typed instance fields: "protected XxxType fieldName = new XxxType();"
			java.util.regex.Matcher fm = java.util.regex.Pattern.compile(
				"protected\\s+\\w+\\s+(\\w+)\\s*=").matcher(body);
			while (fm.find()) {
				fields.add(fm.group(1));
			}
			// Match List fields: "protected List<XxxType> fieldName = new ArrayList"
			java.util.regex.Matcher lm = java.util.regex.Pattern.compile(
				"protected\\s+List<\\w+>\\s+(\\w+)\\s*=").matcher(body);
			while (lm.find()) {
				fields.add(lm.group(1));
			}
			// Match array fields: "protected BigDecimal[] fieldName = new BigDecimal"
			java.util.regex.Matcher am = java.util.regex.Pattern.compile(
				"protected\\s+\\w+\\[\\]\\s+(\\w+)\\s*=").matcher(body);
			while (am.find()) {
				fields.add(am.group(1));
			}
			classFields.put(className, fields);
		}

		// Find all List<XxxType> declarations
		java.util.regex.Matcher listMatcher = java.util.regex.Pattern.compile(
			"protected\\s+List<(\\w+Type)>\\s+(\\w+)\\s*=\\s*new\\s+ArrayList").matcher(content);

		boolean changed = false;
		while (listMatcher.find()) {
			String elementTypeName = listMatcher.group(1);
			String listFieldName = listMatcher.group(2);

			java.util.Set<String> elementFields = classFields.get(elementTypeName);
			if (elementFields == null || elementFields.isEmpty()) continue;

			// Find instance fields of typed inner classes within this element type
			String classStart = "public class " + elementTypeName + " {";
			int classIdx = content.indexOf(classStart);
			if (classIdx < 0) continue;

			int searchEnd = Math.min(classIdx + 10000, content.length());
			String classRegion = content.substring(classIdx, searchEnd);
			java.util.regex.Matcher fieldMatcher = java.util.regex.Pattern.compile(
				"protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)").matcher(classRegion);

			while (fieldMatcher.find()) {
				String innerFieldName = fieldMatcher.group(2);

				// Look for pattern: listField.get(EXPR).qualifier.field
				// where qualifier is NOT a valid field of the element type
				java.util.regex.Pattern getPattern = java.util.regex.Pattern.compile(
					java.util.regex.Pattern.quote(listFieldName) + "\\.get\\([^)]+\\)\\.([a-z][a-z0-9_]*)\\.([a-z][a-z0-9_]*)");
				java.util.regex.Matcher getMatcher = getPattern.matcher(content);

				while (getMatcher.find()) {
					String qualifier = getMatcher.group(1);
					if (qualifier.equals(innerFieldName)) continue; // Already correct

					// KEY FIX: check if the qualifier is an actual field of the element type.
					// If it IS a valid field, the reference is correct — skip it.
					// If it is NOT a valid field, it must be an incorrectly scoped reference
					// to a top-level variable, and we should replace it with innerFieldName.
					if (elementFields.contains(qualifier)) continue;

					// Additional safety: only fix when the qualifier name is clearly related
					// to innerFieldName (e.g., r_vr211300 vs vr211300)
					if (innerFieldName.endsWith(qualifier) || qualifier.endsWith(innerFieldName)
						|| qualifier.startsWith("r_") && innerFieldName.equals(qualifier.substring(2))
						|| qualifier.startsWith("r_") && innerFieldName.startsWith(qualifier.substring(2))) {
						String badQualifier = ")." + qualifier + ".";
						String goodQualifier = ")." + innerFieldName + ".";
						String searchPattern = listFieldName + ".get(";
						int searchFrom = 0;
						while (true) {
							int idx = content.indexOf(searchPattern, searchFrom);
							if (idx < 0) break;
							int closeParenIdx = content.indexOf(")", idx + searchPattern.length());
							if (closeParenIdx < 0) break;
							String afterParen = content.substring(closeParenIdx);
							if (afterParen.startsWith(badQualifier)) {
								content = content.substring(0, closeParenIdx) + goodQualifier +
									content.substring(closeParenIdx + badQualifier.length());
								changed = true;
								LOG.info("Post-processing: fixed OCCURS element qualifier {}.get().{} -> {}.get().{}",
									listFieldName, qualifier, listFieldName, innerFieldName);
							}
							searchFrom = closeParenIdx + 1;
						}
						break; // Only fix first matching pattern per list
					}
				}
			}
		}

		return content;
	}

	/**
	 * Create declarations for undeclared variables that match inner class types.
	 * Uses field-access analysis to find the correct matching type:
	 * 1. Collect fields accessed on each undeclared variable
	 * 2. Find which declared typed variable has a sub-variable with matching fields
	 * 3. Declare the undeclared variable using that sub-variable's type
	 */
	/**
	 * Count net brace depth in text[from..to), skipping braces inside // comments and "..." strings.
	 * This prevents COBOL annotation comments like "*>EXECSQL END-EXEC }." from
	 * corrupting the brace depth calculation.
	 */
	private int countBraceDepth(String text, int from, int to) {
		int depth = 0;
		for (int i = from; i < to; i++) {
			char c = text.charAt(i);
			// Skip // line comments
			if (c == '/' && i + 1 < to && text.charAt(i + 1) == '/') {
				int eol = text.indexOf('\n', i);
				if (eol < 0 || eol >= to) break;
				i = eol; // loop i++ steps past newline
				continue;
			}
			// Skip string literals
			if (c == '"') {
				i++;
				while (i < to && text.charAt(i) != '"') {
					if (text.charAt(i) == '\\') i++; // skip escaped char
					i++;
				}
				continue;
			}
			if (c == '{') depth++;
			else if (c == '}') depth--;
		}
		return depth;
	}

	/**
	 * Find the position after the matching closing brace, starting from openBrace+1,
	 * skipping braces inside // comments and "..." strings.
	 */
	private int findMatchingCloseBrace(String text, int openBrace) {
		int depth = 1;
		int pos = openBrace + 1;
		while (pos < text.length() && depth > 0) {
			char c = text.charAt(pos);
			if (c == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '/') {
				int eol = text.indexOf('\n', pos);
				if (eol < 0) break;
				pos = eol + 1;
				continue;
			}
			if (c == '"') {
				pos++;
				while (pos < text.length() && text.charAt(pos) != '"') {
					if (text.charAt(pos) == '\\') pos++;
					pos++;
				}
				pos++;
				continue;
			}
			if (c == '{') depth++;
			else if (c == '}') depth--;
			pos++;
		}
		return pos;
	}

	private String postProcessFixUndeclaredTypedVariables(String content) {
		// Step 1: Collect ALL declared top-level typed variables
		java.util.Map<String, String> declaredVars = new java.util.LinkedHashMap<>(); // varName -> typeName
		java.util.regex.Matcher declMatcher = java.util.regex.Pattern.compile(
			"^    protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)",
			java.util.regex.Pattern.MULTILINE).matcher(content);
		while (declMatcher.find()) {
			declaredVars.put(declMatcher.group(2), declMatcher.group(1));
		}

		// Also collect non-typed variables
		java.util.Set<String> allDeclaredNames = new java.util.LinkedHashSet<>(declaredVars.keySet());
		java.util.regex.Matcher allDm = java.util.regex.Pattern.compile(
			"^    protected\\s+\\S+\\s+(\\w+)\\s*=",
			java.util.regex.Pattern.MULTILINE).matcher(content);
		while (allDm.find()) {
			allDeclaredNames.add(allDm.group(1));
		}
		// Also collect FileControlEntry declarations (no "protected" keyword)
		java.util.regex.Matcher fceDm = java.util.regex.Pattern.compile(
			"^    FileControlEntry\\s+(\\w+)\\s*=",
			java.util.regex.Pattern.MULTILINE).matcher(content);
		while (fceDm.find()) {
			allDeclaredNames.add(fceDm.group(1));
		}
		// Also collect public declarations (e.g., public FA1000SFRType xxxContent)
		java.util.regex.Matcher pubDm = java.util.regex.Pattern.compile(
			"^    public\\s+\\S+\\s+(\\w+)\\s*=",
			java.util.regex.Pattern.MULTILINE).matcher(content);
		while (pubDm.find()) {
			allDeclaredNames.add(pubDm.group(1));
		}

		// Step 2: Find undeclared variables used with field access and collect their accessed fields
		java.util.Map<String, java.util.Set<String>> undeclaredVarFields = new java.util.LinkedHashMap<>();
		java.util.regex.Matcher useMatcher = java.util.regex.Pattern.compile(
			"(?:^\\s+|[(,] ?)([a-z][a-z0-9_]*)\\.([a-z][a-z0-9_]*)", java.util.regex.Pattern.MULTILINE).matcher(content);
		while (useMatcher.find()) {
			String varName = useMatcher.group(1);
			String fieldName = useMatcher.group(2);
			if (!allDeclaredNames.contains(varName)
				&& !varName.startsWith("io") && !varName.startsWith("java")
				&& !varName.startsWith("entityservice") && !varName.startsWith("this")
				&& !varName.equals("rs") && !varName.equals("super")
				&& varName.length() > 3) {
				undeclaredVarFields.computeIfAbsent(varName, k -> new java.util.LinkedHashSet<>()).add(fieldName);
			}
		}
		// Also check for undeclared vars used as standalone function arguments
		java.util.Set<String> undeclaredStandalone = new java.util.LinkedHashSet<>();
		java.util.regex.Matcher argMatcher = java.util.regex.Pattern.compile(
			"[(,]\\s*([a-z][a-z0-9_]*)\\s*[),]").matcher(content);
		while (argMatcher.find()) {
			String varName = argMatcher.group(1);
			if (!allDeclaredNames.contains(varName)
				&& !varName.startsWith("io") && !varName.startsWith("java")
				&& !varName.equals("true") && !varName.equals("false") && !varName.equals("null")
				&& !varName.equals("this") && !varName.equals("super") && !varName.equals("rs")
				&& varName.length() > 3) {
				undeclaredStandalone.add(varName);
			}
		}
		// Merge standalone vars into the fields map
		for (String var : undeclaredStandalone) {
			undeclaredVarFields.computeIfAbsent(var, k -> new java.util.LinkedHashSet<>());
		}

		if (undeclaredVarFields.isEmpty()) return content;

		// Step 3: Build a map of inner class types -> their field names -> enclosing info
		// For each "public class XxxType { ... }" parse the fields declared inside
		// Also map: typeName@position -> { fields, enclosingVarName, enclosingTypeName }
		// Entry format: [typeName, encVarName, encTypeName, innerFieldName, fieldsStr, qualifiedTypePath, initializerPath]
		// qualifiedTypePath: full type path for multi-level nesting (e.g., "R_tr5005Type.R_vr500500Type.FILE501400Type")
		// initializerPath: full initializer for multi-level nesting (e.g., "r_tr5005.new R_vr500500Type().new FILE501400Type()")
		java.util.List<String[]> innerClassInfoList = new java.util.ArrayList<>();

		for (java.util.Map.Entry<String, String> dv : declaredVars.entrySet()) {
			String encVarName = dv.getKey();
			String encTypeName = dv.getValue();
			String encClassDef = "public class " + encTypeName + " {";
			int encClassPos = content.indexOf(encClassDef);
			if (encClassPos < 0) continue;

			// Find all "protected XxxType fieldName = new XxxType()" within this class
			String varDeclStr = "    protected " + encTypeName + " " + encVarName + " = new " + encTypeName + "()";
			int varDeclPos = content.indexOf(varDeclStr);
			if (varDeclPos < 0) continue;

			// The class body is between encClassPos and varDeclPos
			String classBody = content.substring(encClassPos, varDeclPos);

			// Helper: extract the body of a specific inner class and its brace-matched extent.
			// We use brace counting to properly delimit inner class bodies.
			// Build a list of DIRECT inner classes of the enclosing type.
			// A direct inner class is one whose "public class XxxType {" appears at the right depth.
			// We identify them by finding class definitions and tracking brace depth.
			java.util.List<int[]> directInnerClassRanges = new java.util.ArrayList<>(); // [startOfClassDef, endOfClassBody]
			java.util.List<String> directInnerClassNames = new java.util.ArrayList<>();

			int searchFrom = 0;
			while (searchFrom < classBody.length()) {
				int classDefIdx = classBody.indexOf("public class ", searchFrom);
				if (classDefIdx < 0) break;

				// Extract type name
				int typeStart = classDefIdx + "public class ".length();
				int typeEnd = classBody.indexOf(' ', typeStart);
				if (typeEnd < 0) { searchFrom = classDefIdx + 1; continue; }
				String typeName = classBody.substring(typeStart, typeEnd).trim();

				// Check: is this class definition at the DIRECT level of the enclosing type?
				// Count braces from the start of classBody (which starts with "public class EncType {")
				// to classDefIdx. If brace depth is 1, it's a direct child.
				int firstBrace = classBody.indexOf('{');
				if (firstBrace < 0) break;
				int braceCount = countBraceDepth(classBody, firstBrace, classDefIdx);

				// braceCount == 1 means we're directly inside the enclosing class
				if (braceCount == 1) {
					// Find the opening brace of this inner class
					int innerBraceStart = classBody.indexOf('{', classDefIdx);
					if (innerBraceStart < 0) { searchFrom = classDefIdx + 1; continue; }

					// Find the matching closing brace (skip braces in comments/strings)
					int pos = findMatchingCloseBrace(classBody, innerBraceStart);

					directInnerClassRanges.add(new int[]{classDefIdx, pos});
					directInnerClassNames.add(typeName);
					searchFrom = pos;
				} else {
					searchFrom = classDefIdx + 1;
				}
			}

			// For each DIRECT inner class, extract its body and scan for typed fields
			for (int ci = 0; ci < directInnerClassNames.size(); ci++) {
				String innerTypeName = directInnerClassNames.get(ci);
				int[] range = directInnerClassRanges.get(ci);
				String innerClassFullText = classBody.substring(range[0], range[1]);

				// Find "protected InnerType fieldName = new InnerType()" INSIDE this class's body
				// (i.e., the field declaration that instantiates it in the enclosing type)
				String innerFieldName = null;
				java.util.regex.Matcher ifm = java.util.regex.Pattern.compile(
					"protected\\s+" + java.util.regex.Pattern.quote(innerTypeName) + "\\s+(\\w+)\\s*=").matcher(classBody);
				if (ifm.find()) {
					innerFieldName = ifm.group(1);
				}
				if (innerFieldName == null) innerFieldName = innerTypeName.toLowerCase();

				// Extract fields of this inner class (strip its nested classes first)
				String innerClassBody = innerClassFullText;
				String directBody = innerClassBody;
				int nestedStart;
				while ((nestedStart = directBody.indexOf("public class ", directBody.indexOf('{') + 1)) >= 0) {
					int bs = directBody.indexOf('{', nestedStart);
					if (bs < 0) break;
					int ps = findMatchingCloseBrace(directBody, bs);
					directBody = directBody.substring(0, nestedStart) + directBody.substring(ps);
				}

				java.util.Set<String> innerFields = new java.util.LinkedHashSet<>();
				java.util.regex.Matcher fieldExtractor = java.util.regex.Pattern.compile(
					"protected\\s+(?:String|BigDecimal|boolean|int|long)\\s+(\\w+)\\s*=").matcher(directBody);
				while (fieldExtractor.find()) {
					innerFields.add(fieldExtractor.group(1));
				}
				// Also add typed sub-fields (direct children only - they're in directBody)
				java.util.regex.Matcher typedFieldExtractor = java.util.regex.Pattern.compile(
					"protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)").matcher(directBody);
				while (typedFieldExtractor.find()) {
					innerFields.add(typedFieldExtractor.group(2));
				}

				if (!innerFields.isEmpty()) {
					String fieldsStr = String.join(",", innerFields);
					// Single-level nesting: qualified path = encTypeName.innerTypeName
					String qualifiedTypePath = encTypeName + "." + innerTypeName;
					String initializerPath = encVarName + ".new " + innerTypeName + "()";
					innerClassInfoList.add(new String[]{innerTypeName, encVarName, encTypeName, innerFieldName, fieldsStr, qualifiedTypePath, initializerPath});
				}

				// Depth 2: scan for inner classes nested INSIDE this inner class.
				// Example: R_tr5005Type -> R_vr500500Type -> FILE501400Type
				// Find DIRECT children of this inner class using brace counting.
				int innerBodyStart = innerClassFullText.indexOf('{') + 1;
				if (innerBodyStart <= 0) continue;

				int d2SearchFrom = innerBodyStart;
				while (d2SearchFrom < innerClassFullText.length()) {
					int d2ClassDefIdx = innerClassFullText.indexOf("public class ", d2SearchFrom);
					if (d2ClassDefIdx < 0) break;

					// Check depth: count braces from innerBodyStart to d2ClassDefIdx
					int d2BraceCount = countBraceDepth(innerClassFullText, innerBodyStart, d2ClassDefIdx);

					if (d2BraceCount == 0) { // depth 0 inside the inner class = direct child
						int d2TypeStart = d2ClassDefIdx + "public class ".length();
						int d2TypeEnd = innerClassFullText.indexOf(' ', d2TypeStart);
						if (d2TypeEnd < 0) { d2SearchFrom = d2ClassDefIdx + 1; continue; }
						String depth2TypeName = innerClassFullText.substring(d2TypeStart, d2TypeEnd).trim();

						// Find body of this depth-2 class
						int d2BraceStart = innerClassFullText.indexOf('{', d2ClassDefIdx);
						if (d2BraceStart < 0) { d2SearchFrom = d2ClassDefIdx + 1; continue; }
						int ps = findMatchingCloseBrace(innerClassFullText, d2BraceStart);
						String depth2ClassBody = innerClassFullText.substring(d2BraceStart + 1, Math.max(d2BraceStart + 1, ps - 1));

						// Find field name for this depth-2 type
						String depth2FieldName = null;
						java.util.regex.Matcher d2fm = java.util.regex.Pattern.compile(
							"protected\\s+" + java.util.regex.Pattern.quote(depth2TypeName) + "\\s+(\\w+)\\s*=").matcher(innerClassFullText);
						if (d2fm.find()) {
							depth2FieldName = d2fm.group(1);
						}
						if (depth2FieldName == null) depth2FieldName = depth2TypeName.toLowerCase();

						// Extract fields of this depth-2 class (strip its nested classes)
						String d2DirectBody = depth2ClassBody;
						while ((nestedStart = d2DirectBody.indexOf("public class ")) >= 0) {
							int bs = d2DirectBody.indexOf('{', nestedStart);
							if (bs < 0) break;
							int psx = findMatchingCloseBrace(d2DirectBody, bs);
							d2DirectBody = d2DirectBody.substring(0, nestedStart) + d2DirectBody.substring(psx);
						}

						java.util.Set<String> depth2Fields = new java.util.LinkedHashSet<>();
						java.util.regex.Matcher d2FieldExtractor = java.util.regex.Pattern.compile(
							"protected\\s+(?:String|BigDecimal|boolean|int|long)\\s+(\\w+)\\s*=").matcher(d2DirectBody);
						while (d2FieldExtractor.find()) {
							depth2Fields.add(d2FieldExtractor.group(1));
						}
						java.util.regex.Matcher d2TypedFieldExtractor = java.util.regex.Pattern.compile(
							"protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)").matcher(d2DirectBody);
						while (d2TypedFieldExtractor.find()) {
							depth2Fields.add(d2TypedFieldExtractor.group(2));
						}

						if (!depth2Fields.isEmpty()) {
							String d2FieldsStr = String.join(",", depth2Fields);
							// Multi-level nesting: qualified path = encTypeName.innerTypeName.depth2TypeName
							String qualifiedTypePath = encTypeName + "." + innerTypeName + "." + depth2TypeName;
							String initializerPath = encVarName + ".new " + innerTypeName + "().new " + depth2TypeName + "()";
							innerClassInfoList.add(new String[]{depth2TypeName, encVarName, encTypeName, depth2FieldName, d2FieldsStr, qualifiedTypePath, initializerPath});
							LOG.info("Post-processing: found depth-2 inner class {} inside {}.{}", depth2TypeName, encTypeName, innerTypeName);
						}

						// Depth 3: scan for inner classes nested INSIDE this depth-2 class.
						// Example: WkFilterFactCabType -> WkCodOriFilterArrType -> WkCodOriFType -> FILE501400Type
						String depth2FullText = innerClassFullText.substring(d2BraceStart, ps);
						int d2BodyStartIdx = 1; // skip the opening brace
						int d3SearchFrom = d2BodyStartIdx;
						while (d3SearchFrom < depth2FullText.length()) {
							int d3ClassDefIdx = depth2FullText.indexOf("public class ", d3SearchFrom);
							if (d3ClassDefIdx < 0) break;

							// Check depth: count braces from d2BodyStartIdx to d3ClassDefIdx
							int d3BraceCount = countBraceDepth(depth2FullText, d2BodyStartIdx, d3ClassDefIdx);

							if (d3BraceCount == 0) { // depth 0 inside the depth-2 class = direct child
								int d3TypeStart = d3ClassDefIdx + "public class ".length();
								int d3TypeEnd = depth2FullText.indexOf(' ', d3TypeStart);
								if (d3TypeEnd < 0) { d3SearchFrom = d3ClassDefIdx + 1; continue; }
								String depth3TypeName = depth2FullText.substring(d3TypeStart, d3TypeEnd).trim();

								// Find body of this depth-3 class
								int d3BraceStart = depth2FullText.indexOf('{', d3ClassDefIdx);
								if (d3BraceStart < 0) { d3SearchFrom = d3ClassDefIdx + 1; continue; }
								int d3ps = findMatchingCloseBrace(depth2FullText, d3BraceStart);
								String depth3ClassBody = depth2FullText.substring(d3BraceStart + 1, Math.max(d3BraceStart + 1, d3ps - 1));

								// Find field name for this depth-3 type
								String depth3FieldName = null;
								java.util.regex.Matcher d3fm = java.util.regex.Pattern.compile(
									"protected\\s+" + java.util.regex.Pattern.quote(depth3TypeName) + "\\s+(\\w+)\\s*=").matcher(depth2FullText);
								if (d3fm.find()) {
									depth3FieldName = d3fm.group(1);
								}
								if (depth3FieldName == null) depth3FieldName = depth3TypeName.toLowerCase();

								// Extract fields of this depth-3 class (strip its nested classes)
								String d3DirectBody = depth3ClassBody;
								int d3NestedStart;
								while ((d3NestedStart = d3DirectBody.indexOf("public class ")) >= 0) {
									int bs3 = d3DirectBody.indexOf('{', d3NestedStart);
									if (bs3 < 0) break;
									int psx3 = findMatchingCloseBrace(d3DirectBody, bs3);
									d3DirectBody = d3DirectBody.substring(0, d3NestedStart) + d3DirectBody.substring(psx3);
								}

								java.util.Set<String> depth3Fields = new java.util.LinkedHashSet<>();
								java.util.regex.Matcher d3FieldExtractor = java.util.regex.Pattern.compile(
									"protected\\s+(?:String|BigDecimal|boolean|int|long)\\s+(\\w+)\\s*=").matcher(d3DirectBody);
								while (d3FieldExtractor.find()) {
									depth3Fields.add(d3FieldExtractor.group(1));
								}
								java.util.regex.Matcher d3TypedFieldExtractor = java.util.regex.Pattern.compile(
									"protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)").matcher(d3DirectBody);
								while (d3TypedFieldExtractor.find()) {
									depth3Fields.add(d3TypedFieldExtractor.group(2));
								}

								if (!depth3Fields.isEmpty()) {
									String d3FieldsStr = String.join(",", depth3Fields);
									// Multi-level nesting: encTypeName.innerTypeName.depth2TypeName.depth3TypeName
									String d3QualifiedTypePath = encTypeName + "." + innerTypeName + "." + depth2TypeName + "." + depth3TypeName;
									String d3InitializerPath = encVarName + ".new " + innerTypeName + "().new " + depth2TypeName + "().new " + depth3TypeName + "()";
									innerClassInfoList.add(new String[]{depth3TypeName, encVarName, encTypeName, depth3FieldName, d3FieldsStr, d3QualifiedTypePath, d3InitializerPath});
									LOG.info("Post-processing: found depth-3 inner class {} inside {}.{}.{}", depth3TypeName, encTypeName, innerTypeName, depth2TypeName);
								}

								d3SearchFrom = d3ps;
							} else {
								d3SearchFrom = d3ClassDefIdx + 1;
							}
						}

						d2SearchFrom = ps;
					} else {
						d2SearchFrom = d2ClassDefIdx + 1;
					}
				}
			}
		}

		// Step 3b: Also extract fields from top-level typed variables (not nested in enclosing types).
		// These are standalone "public class XxxType { ... }" classes with their own field declarations.
		// Map: topLevelVarName -> { typeName, fieldsSet }
		java.util.Map<String, String[]> topLevelTypeInfo = new java.util.LinkedHashMap<>(); // varName -> [typeName, fieldsStr]
		for (java.util.Map.Entry<String, String> dv : declaredVars.entrySet()) {
			String tlVarName = dv.getKey();
			String tlTypeName = dv.getValue();
			String tlClassDef = "public class " + tlTypeName + " {";
			int tlClassPos = content.indexOf(tlClassDef);
			if (tlClassPos < 0) continue;

			// Find the class body: from class definition to the matching closing brace
			// Use a simple approach: scan forward to find "protected XxxType varName = new XxxType()"
			String tlVarDeclStr = "    protected " + tlTypeName + " " + tlVarName + " = new " + tlTypeName + "()";
			int tlVarDeclPos = content.indexOf(tlVarDeclStr);
			if (tlVarDeclPos < 0) continue;

			String tlClassBody = content.substring(tlClassPos + tlClassDef.length(), tlVarDeclPos);

			// Remove nested class bodies to only extract DIRECT fields of this top-level class.
			// Nested classes (public class InnerType { ... }) contain their own fields that
			// should NOT be counted as fields of the enclosing type.
			String tlDirectBody = tlClassBody;
			int nestedStart;
			while ((nestedStart = tlDirectBody.indexOf("public class ")) >= 0) {
				int braceStart = tlDirectBody.indexOf('{', nestedStart);
				if (braceStart < 0) break;
				int pos = findMatchingCloseBrace(tlDirectBody, braceStart);
				tlDirectBody = tlDirectBody.substring(0, nestedStart) + tlDirectBody.substring(pos);
			}

			// Extract fields: look for "protected (String|BigDecimal|boolean|int|long) fieldName ="
			java.util.Set<String> tlFields = new java.util.LinkedHashSet<>();
			java.util.regex.Matcher tlFieldExtractor = java.util.regex.Pattern.compile(
				"protected\\s+(?:String|BigDecimal|boolean|int|long)\\s+(\\w+)\\s*=").matcher(tlDirectBody);
			while (tlFieldExtractor.find()) {
				tlFields.add(tlFieldExtractor.group(1));
			}
			// Also add typed sub-fields
			java.util.regex.Matcher tlTypedFieldExtractor = java.util.regex.Pattern.compile(
				"protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)").matcher(tlDirectBody);
			while (tlTypedFieldExtractor.find()) {
				tlFields.add(tlTypedFieldExtractor.group(2));
			}

			if (!tlFields.isEmpty()) {
				topLevelTypeInfo.put(tlVarName, new String[]{tlTypeName, String.join(",", tlFields)});
			}
		}

		// Step 4: Match undeclared variables to inner class types OR top-level types by field overlap
		boolean changed = false;
		StringBuilder newDeclarations = new StringBuilder();
		for (java.util.Map.Entry<String, java.util.Set<String>> entry : undeclaredVarFields.entrySet()) {
			String undeclVar = entry.getKey();
			java.util.Set<String> accessedFields = entry.getValue();
			if (allDeclaredNames.contains(undeclVar)) continue;

			// Try to find the best matching inner class type
			String bestTypeName = null;
			String bestEncVarName = null;
			String bestEncTypeName = null;
			int bestMatchCount = 0;
			boolean bestIsTopLevel = false;
			String bestTopLevelVarName = null;
			String bestQualifiedTypePath = null;
			String bestInitializerPath = null;

			for (String[] info : innerClassInfoList) {
				String typeName = info[0];
				String encVarName = info[1];
				String encTypeName = info[2];
				String innerFieldName = info[3];
				String fieldsStr = info[4];
				String qualifiedTypePath = info.length > 5 ? info[5] : null;
				String initializerPath = info.length > 6 ? info[6] : null;
				java.util.Set<String> typeFields = new java.util.LinkedHashSet<>(java.util.Arrays.asList(fieldsStr.split(",")));

				// Count how many accessed fields match
				int matchCount = 0;
				for (String af : accessedFields) {
					if (typeFields.contains(af)) matchCount++;
				}

				// Also check if the type name matches the variable name
				boolean nameMatch = false;
				String[] candidateTypeNames = deriveCandidateTypeNames(undeclVar);
				for (String candidate : candidateTypeNames) {
					if (candidate.equalsIgnoreCase(typeName)) { nameMatch = true; break; }
				}

				// Prefer: name match + field match > field match only > name match only
				int score = matchCount * 10 + (nameMatch ? 100 : 0);
				if (score > bestMatchCount && (matchCount > 0 || nameMatch)) {
					bestMatchCount = score;
					bestTypeName = typeName;
					bestEncVarName = encVarName;
					bestEncTypeName = encTypeName;
					bestIsTopLevel = false;
					bestTopLevelVarName = null;
					bestQualifiedTypePath = qualifiedTypePath;
					bestInitializerPath = initializerPath;
				}
			}

			// Also search top-level typed variables for field overlap.
			// Top-level variables preserve correct field types (e.g., BigDecimal for numeric COBOL PIC)
			// while DDS inner class types may have String fields for the same COBOL field names.
			// Prefer a top-level match when it has equal or better field coverage.
			for (java.util.Map.Entry<String, String[]> tlEntry : topLevelTypeInfo.entrySet()) {
				String tlVarName = tlEntry.getKey();
				String tlTypeName = tlEntry.getValue()[0];
				String tlFieldsStr = tlEntry.getValue()[1];
				java.util.Set<String> tlFields = new java.util.LinkedHashSet<>(java.util.Arrays.asList(tlFieldsStr.split(",")));

				// Count how many accessed fields match
				int matchCount = 0;
				for (String af : accessedFields) {
					if (tlFields.contains(af)) matchCount++;
				}

				// Also check if the type name matches the variable name
				boolean nameMatch = false;
				String[] candidateTypeNames = deriveCandidateTypeNames(undeclVar);
				for (String candidate : candidateTypeNames) {
					if (candidate.equalsIgnoreCase(tlTypeName)) { nameMatch = true; break; }
				}

				// Top-level types get a bonus because they preserve correct COBOL data types.
				// DDS inner class types often have String fields for numeric COBOL fields,
				// which causes type mismatches (e.g., .subtract() on String).
				int score = matchCount * 10 + (nameMatch ? 100 : 0) + (matchCount > 0 ? 5 : 0);
				if (score > bestMatchCount && (matchCount > 0 || nameMatch)) {
					bestMatchCount = score;
					bestTypeName = tlTypeName;
					bestEncVarName = null;
					bestEncTypeName = null;
					bestIsTopLevel = true;
					bestTopLevelVarName = tlVarName;
				}
			}

			if (bestTypeName != null) {
				// Safety: verify the variable is truly undeclared in the content
				// Check multiple patterns for existing declarations
				boolean alreadyDeclared = false;
				// Pattern 1: typed declaration
				java.util.regex.Matcher dupeCheck1 = java.util.regex.Pattern.compile(
					"protected\\s+\\S+\\s+" + java.util.regex.Pattern.quote(undeclVar) + "\\s*=").matcher(content);
				if (dupeCheck1.find()) alreadyDeclared = true;
				// Pattern 2: FileControlEntry declaration
				if (content.contains("FileControlEntry " + undeclVar + " =")) alreadyDeclared = true;
				// Pattern 3: public typed declaration (e.g., "public SomeType varName = ...")
				java.util.regex.Matcher dupeCheck3 = java.util.regex.Pattern.compile(
					"public\\s+\\w+\\s+" + java.util.regex.Pattern.quote(undeclVar) + "\\s*=").matcher(content);
				if (dupeCheck3.find()) alreadyDeclared = true;
				if (alreadyDeclared) {
					LOG.info("Post-processing: skipping '{}' - already declared", undeclVar);
					continue;
				}
				String decl;
				if (bestIsTopLevel && bestTopLevelVarName != null) {
					// Create alias pointing to the existing top-level variable instance.
					// This ensures both variables share the same data area, matching
					// AS/400 COBOL behavior where renamed record structures share storage.
					decl = "    protected " + bestTypeName + " " + undeclVar + " = " + bestTopLevelVarName + ";";
					LOG.info("Post-processing: declared undeclared variable '{}' as {} alias to top-level '{}'",
						undeclVar, bestTypeName, bestTopLevelVarName);
				} else {
					// Use the full qualified type path if available (supports multi-level nesting)
					String qualifiedType;
					String initializer;
					if (bestQualifiedTypePath != null) {
						qualifiedType = bestQualifiedTypePath;
						initializer = bestInitializerPath;
					} else {
						qualifiedType = bestEncTypeName + "." + bestTypeName;
						initializer = bestEncVarName + ".new " + bestTypeName + "()";
					}
					decl = "    protected " + qualifiedType + " " + undeclVar + " = " + initializer + ";";
					LOG.info("Post-processing: declared undeclared variable '{}' as {} = {}",
						undeclVar, qualifiedType, initializer);
				}
				newDeclarations.append(decl).append("\n");
				allDeclaredNames.add(undeclVar);
				changed = true;
			}
		}

		if (changed) {
			// Insert declarations after the last existing top-level variable declaration
			java.util.regex.Pattern lastDeclPat = java.util.regex.Pattern.compile(
				"^    protected\\s+\\w+\\s+\\w+\\s*=.*?;\\s*$", java.util.regex.Pattern.MULTILINE);
			java.util.regex.Matcher lastDeclMatcher = lastDeclPat.matcher(content);
			int lastDeclEnd = -1;
			while (lastDeclMatcher.find()) {
				lastDeclEnd = lastDeclMatcher.end();
			}
			if (lastDeclEnd > 0) {
				content = content.substring(0, lastDeclEnd) + "\n" + newDeclarations.toString() + content.substring(lastDeclEnd);
			}
		}

		return content;
	}

	/**
	 * Fix CALL BY REFERENCE copy-back that assigns String to a group-typed variable.
	 * When postProcessFixUndeclaredTypedVariables creates a typed declaration for a variable
	 * (e.g., "protected R_tr5005Type.R_vr500500Type.FILE501400Type vr501400_aux = ..."),
	 * but the CALL copy-back code treats it as String:
	 *   "vr501400_aux = _lr[3] instanceof String ? (String) _lr[3] : String.valueOf(_lr[3])"
	 * This must be replaced with group copy-back:
	 *   "{ String _flat = ...; CobolMove.moveStringToGroup(_flat, vr501400_aux); }"
	 */
	private String postProcessFixCallCopyBackForGroupVars(String content) {
		// Collect all typed variable declarations (including qualified multi-level types)
		java.util.Set<String> groupTypedVars = new java.util.LinkedHashSet<>();
		java.util.regex.Matcher typedVarMatcher = java.util.regex.Pattern.compile(
			"^    protected\\s+(?:\\w+\\.)*\\w+Type\\s+(\\w+)\\s*=",
			java.util.regex.Pattern.MULTILINE).matcher(content);
		while (typedVarMatcher.find()) {
			groupTypedVars.add(typedVarMatcher.group(1));
		}

		if (groupTypedVars.isEmpty()) return content;

		// Find CALL copy-back lines that assign String to group-typed variables
		// Pattern: "try { if (_lr.length > N && _lr[N] != null) VARNAME = _lr[N] instanceof String ? (String) _lr[N] : String.valueOf(_lr[N]); }"
		boolean changed = false;
		for (String varName : groupTypedVars) {
			String stringCopyBack = java.util.regex.Pattern.quote(varName) + " = _lr\\[(\\d+)\\] instanceof String \\? \\(String\\) _lr\\[\\1\\] : String\\.valueOf\\(_lr\\[\\1\\]\\);";
			java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
				"try \\{ if \\(_lr\\.length > (\\d+) && _lr\\[\\1\\] != null\\) " + stringCopyBack + " \\} catch \\(Exception _cce\\) \\{ /\\* type mismatch caller/callee \\*/ \\}");
			java.util.regex.Matcher matcher = pattern.matcher(content);
			if (matcher.find()) {
				String argIdx = matcher.group(1);
				String replacement = String.format(
					"try { if (_lr.length > %s && _lr[%s] != null) { String _flat = CobolMove.groupToString(_lr[%s]); int _tgtSz = CobolMove.getGroupSize(%s); if (_flat.length() == _tgtSz) { CobolMove.moveStringToGroup(_flat, %s); } else { String _callerFlat = CobolMove.groupToString(%s); String _merged; if (_flat.length() < _tgtSz) { _merged = _flat + _callerFlat.substring(_flat.length()); } else { _merged = _flat.substring(0, _tgtSz); } CobolMove.moveStringToGroup(_merged, %s); } } } catch (Exception _cbe) { /* copy-back group */ }",
					argIdx, argIdx, argIdx, varName, varName, varName, varName);
				content = content.substring(0, matcher.start()) + replacement + content.substring(matcher.end());
				LOG.info("Post-processing: fixed CALL copy-back for group variable '{}' (argIndex={})", varName, argIdx);
				changed = true;
			}
		}

		return content;
	}

	/**
	 * Derive candidate type names from an undeclared variable name.
	 */
	private String[] deriveCandidateTypeNames(String varName) {
		java.util.List<String> candidates = new java.util.ArrayList<>();

		// Direct: capitalize first letter + "Type"
		String direct = varName.substring(0, 1).toUpperCase() + varName.substring(1) + "Type";
		candidates.add(direct);

		// Uppercase: all uppercase + "Type"
		candidates.add(varName.toUpperCase() + "Type");

		// Strip _aux suffix
		if (varName.endsWith("_aux")) {
			String base = varName.substring(0, varName.length() - 4);
			candidates.add(base.toUpperCase() + "Type");
			candidates.add(base.substring(0, 1).toUpperCase() + base.substring(1) + "Type");
		}

		// Strip h_ prefix (SQL INCLUDE host variable)
		if (varName.startsWith("h_")) {
			String base = varName.substring(2);
			candidates.add(base.toUpperCase() + "Type");
			candidates.add(base.substring(0, 1).toUpperCase() + base.substring(1) + "Type");
		}

		// Strip r_ prefix
		if (varName.startsWith("r_")) {
			String base = varName.substring(2);
			candidates.add(base.toUpperCase() + "Type");
			candidates.add(base.substring(0, 1).toUpperCase() + base.substring(1) + "Type");
		}

		// Strip w_ prefix
		if (varName.startsWith("w_")) {
			String base = varName.substring(2);
			candidates.add(base.toUpperCase() + "Type");
			candidates.add(base.substring(0, 1).toUpperCase() + base.substring(1) + "Type");
		}

		// Strip lk prefix
		if (varName.startsWith("lk")) {
			String base = varName.substring(2);
			candidates.add(base.toUpperCase() + "Type");
			if (base.startsWith("_")) {
				base = base.substring(1);
				candidates.add(base.toUpperCase() + "Type");
			}
		}

		return candidates.toArray(new String[0]);
	}

	/**
	 * Fix VARCHAR fields that are AMBIGUOUS: the same field name is declared as both a
	 * varchar type (XxxType with xxx_data/xxx_length) and as plain String in different classes.
	 *
	 * The existing postProcessFixVarcharGroupInStringContext skips these fields to avoid
	 * breaking String references. This method handles them safely by checking the CONTAINER
	 * type to determine whether a specific reference is to a varchar or String field.
	 *
	 * Example: "aliasextid" is varchar (ALIASEXTIDType) in VB000500Type but String in another class.
	 * This method fixes references like CobolIntrinsic.reverse(xxx.vb000500.aliasextid) by checking
	 * that vb000500 is of type VB000500Type which declares aliasextid as ALIASEXTIDType.
	 */
	private String postProcessFixAmbiguousVarcharFields(String content) {
		// Step 1: Find all varchar type classes (have both _length and _data subfields)
		// Map: lowercase field base name -> true (it has a varchar type somewhere)
		java.util.Set<String> varcharFieldNames = new java.util.LinkedHashSet<>();
		java.util.regex.Matcher vcMatcher = java.util.regex.Pattern.compile(
			"public class (\\w+)Type \\{[^}]*?(\\w+)_length[^}]*?\\2_data[^}]*?\\}",
			java.util.regex.Pattern.DOTALL).matcher(content);
		while (vcMatcher.find()) {
			String fieldBase = vcMatcher.group(2);
			varcharFieldNames.add(fieldBase);
		}
		if (varcharFieldNames.isEmpty()) return content;

		// Step 2: Find all plain String field names
		java.util.Set<String> stringFieldNames = new java.util.LinkedHashSet<>();
		java.util.regex.Matcher strFm = java.util.regex.Pattern.compile(
			"protected\\s+String\\s+(\\w+)\\s*=").matcher(content);
		while (strFm.find()) {
			stringFieldNames.add(strFm.group(1));
		}

		// Step 3: Find ambiguous fields (both varchar AND String)
		java.util.Set<String> ambiguousFields = new java.util.LinkedHashSet<>();
		for (String fn : varcharFieldNames) {
			if (stringFieldNames.contains(fn)) {
				ambiguousFields.add(fn);
			}
		}
		if (ambiguousFields.isEmpty()) return content;

		// Step 4: Build per-class field info — which classes have which fields as varchar
		// Parse class structures to find: className -> Set<fieldName declared as Type>
		java.util.Map<String, java.util.Set<String>> classVarcharFields = new java.util.LinkedHashMap<>();
		// Pattern: "class XxxType {" followed by field declarations
		java.util.regex.Pattern classP = java.util.regex.Pattern.compile(
			"(?:public|protected|private)?\\s*class\\s+(\\w+(?:Type)?)\\s*\\{");
		java.util.regex.Matcher classMatcher = classP.matcher(content);
		while (classMatcher.find()) {
			String className = classMatcher.group(1);
			int classBodyStart = classMatcher.end();
			// Find the end of this class by tracking braces
			int depth = 1;
			int classBodyEnd = classBodyStart;
			for (int ci = classBodyStart; ci < content.length() && depth > 0; ci++) {
				char ch = content.charAt(ci);
				if (ch == '{') depth++;
				else if (ch == '}') {
					depth--;
					if (depth == 0) classBodyEnd = ci;
				}
			}
			// Get flat body (excluding nested class bodies)
			String classBody = content.substring(classBodyStart, classBodyEnd);
			StringBuilder flatBody = new StringBuilder();
			int d = 0;
			for (int ci = 0; ci < classBody.length(); ci++) {
				char ch = classBody.charAt(ci);
				if (ch == '{') d++;
				else if (ch == '}') d--;
				if (d <= 0) { flatBody.append(ch); d = 0; }
			}
			// Find typed field declarations in flat body
			java.util.Set<String> vcFields = new java.util.LinkedHashSet<>();
			java.util.regex.Matcher tfm = java.util.regex.Pattern.compile(
				"protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)").matcher(flatBody);
			while (tfm.find()) {
				String fieldName = tfm.group(2);
				if (ambiguousFields.contains(fieldName)) {
					vcFields.add(fieldName);
				}
			}
			if (!vcFields.isEmpty()) {
				classVarcharFields.put(className, vcFields);
			}
		}
		if (classVarcharFields.isEmpty()) return content;

		// Step 5: Build instance-to-type map
		java.util.Map<String, String> instanceToType = new java.util.LinkedHashMap<>();
		java.util.regex.Matcher itm = java.util.regex.Pattern.compile(
			"protected\\s+(\\w+(?:Type)?)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)").matcher(content);
		while (itm.find()) {
			instanceToType.put(itm.group(2), itm.group(1));
		}

		// Step 5b: Build list-variable-to-element-type map for safeGet() type resolution
		java.util.Map<String, String> listVarToElementType = new java.util.LinkedHashMap<>();
		java.util.regex.Matcher listDeclM = java.util.regex.Pattern.compile(
			"List<(\\w+(?:Type)?)>\\s+(\\w+)\\s*=").matcher(content);
		while (listDeclM.find()) {
			listVarToElementType.put(listDeclM.group(2), listDeclM.group(1));
		}

		// Step 6: Process lines — fix ambiguous varchar references using container checks
		boolean changed = false;
		String[] lines = content.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			// Skip declarations and comments
			String trimmed = line.trim();
			if (trimmed.startsWith("protected ") || trimmed.startsWith("public ") || trimmed.startsWith("//")) continue;

			for (String fieldName : ambiguousFields) {
				String dataField = fieldName + "_data";
				// Skip if this line doesn't reference the field at all
				if (!line.contains("." + fieldName)) continue;
				// Skip if already qualified with _data
				if (line.contains("." + fieldName + "." + dataField)) continue;

				// Check if line is in a String-expecting context
				boolean isStringContext = line.contains("String.valueOf(") || line.contains("CobolIntrinsic.reverse(")
					|| line.contains("CobolStringOps.") || line.contains("moveAlphanumericToAlphanumeric(")
					|| line.contains("CobolMove.") || line.contains("CobolComparison.")
					|| line.contains("CobolIntrinsic.length(");

				if (!isStringContext) continue;

				// Find all occurrences of container.fieldName in this line
				// Pattern: word.fieldName followed by ) , ; or = (not followed by . which would mean already qualified)
				java.util.regex.Pattern refP = java.util.regex.Pattern.compile(
					"(\\w+)\\." + java.util.regex.Pattern.quote(fieldName) + "(?=[);,\\s=])(?!\\." + java.util.regex.Pattern.quote(dataField) + ")");
				java.util.regex.Matcher refM = refP.matcher(line);
				StringBuilder newLine = new StringBuilder();
				int lastEnd = 0;
				boolean lineChanged = false;

				while (refM.find()) {
					String container = refM.group(1);
					// Check if container is a typed instance whose class has this field as varchar
					String containerType = instanceToType.get(container);
					boolean isVarchar = false;
					if (containerType != null) {
						java.util.Set<String> vcFields = classVarcharFields.get(containerType);
						if (vcFields != null && vcFields.contains(fieldName)) {
							isVarchar = true;
						}
					}
					if (isVarchar) {
						// Replace container.fieldName with container.fieldName.fieldName_data
						newLine.append(line, lastEnd, refM.end());
						newLine.append(".").append(dataField);
						lastEnd = refM.end();
						lineChanged = true;
					}
				}

				if (lineChanged) {
					newLine.append(line.substring(lastEnd));
					line = newLine.toString();
					lines[i] = line;
					changed = true;
				}

				// Also handle function-return contexts: ).fieldName in various positions.
				// This occurs with safeGet(list, idx).fieldName used as assignment
				// target OR as argument to a function call.
				// Resolve the list element type before adding _data — only fix if the
				// element type declares this field as varchar, not String.
				String funcReturnToken = ")." + fieldName;
				if (line.contains(funcReturnToken) && !line.contains(")." + fieldName + "." + dataField)) {
					// Find ALL occurrences of ).fieldName in this line and fix each one
					// where the safeGet/get list element type declares field as varchar.
					int searchFrom = 0;
					while (true) {
						int tokenPos = line.indexOf(funcReturnToken, searchFrom);
						if (tokenPos < 0) break;
						// Verify the token is followed by a boundary char (not another identifier segment)
						int afterToken = tokenPos + funcReturnToken.length();
						if (afterToken < line.length()) {
							char nextChar = line.charAt(afterToken);
							// If next char is '.', the code already accesses a sub-field
							// (e.g., ).nome.nome_length) — do NOT insert _data
							if (nextChar == '.') {
								searchFrom = afterToken;
								continue;
							}
							// If next char is a word char or underscore, this is part of a longer identifier — skip
							if (Character.isLetterOrDigit(nextChar) || nextChar == '_') {
								searchFrom = afterToken;
								continue;
							}
						}
						String leftSide = line.substring(0, tokenPos);
						boolean shouldFix = false;
						// Check safeGet(a.b.c.listVar, ...) pattern — support multi-segment paths
						java.util.regex.Matcher sgm = java.util.regex.Pattern.compile(
							"safeGet\\((?:\\w+\\.)*?(\\w+),").matcher(leftSide);
						if (sgm.find()) {
							String listVar = sgm.group(1);
							String elementType = listVarToElementType.get(listVar);
							if (elementType != null) {
								java.util.Set<String> vcFields = classVarcharFields.get(elementType);
								if (vcFields != null && vcFields.contains(fieldName)) {
									shouldFix = true;
								}
							}
						}
						// Also check direct .get(idx) pattern: listVar.get(idx).fieldName
						if (!shouldFix) {
							java.util.regex.Matcher gm = java.util.regex.Pattern.compile(
								"(\\w+)\\.get\\(").matcher(leftSide);
							if (gm.find()) {
								String listVar = gm.group(1);
								String elementType = listVarToElementType.get(listVar);
								if (elementType != null) {
									java.util.Set<String> vcFields = classVarcharFields.get(elementType);
									if (vcFields != null && vcFields.contains(fieldName)) {
										shouldFix = true;
									}
								}
							}
						}
						if (shouldFix) {
							// Insert .fieldName_data after ).fieldName
							line = line.substring(0, afterToken) + "." + dataField + line.substring(afterToken);
							lines[i] = line;
							changed = true;
							searchFrom = afterToken + 1 + dataField.length();
						} else {
							searchFrom = afterToken;
						}
					}
				}
			}
		}

		if (changed) {
			LOG.info("Post-processing: fixed ambiguous VARCHAR fields using container-aware disambiguation");
			return String.join("\n", lines);
		}
		return content;
	}

	/**
	 * Preprocesses COBOL source to remove duplicate/stray END-EXEC keywords.
	 * In some AS/400 programs, a paragraph like:
	 *
	 *     IF NOT-ERRO THEN
	 *        EXEC SQL
	 *           CLOSE cursor-name
	 *        END-EXEC
	 *     END-EXEC.
	 *
	 * has a stray END-EXEC after the EXEC SQL block is already closed. AS/400's COBOL
	 * compiler tolerates this (the period terminates the IF regardless), but ProLeap's
	 * preprocessor parser rejects the unexpected END-EXEC token.
	 *
	 * This preprocessor tracks EXEC SQL / END-EXEC pairs and removes any END-EXEC
	 * that appears when no EXEC block is open, preserving the trailing period if present.
	 */
	private File preprocessDuplicateEndExec(final File cobolFile) throws IOException {
		final List<String> lines = Files.readAllLines(cobolFile.toPath());
		boolean modified = false;
		boolean inExecBlock = false;

		for (int i = 0; i < lines.size(); i++) {
			final String line = lines.get(i);

			// Skip comment lines (column 7 = * or /)
			if (line.length() >= 7 && (line.charAt(6) == '*' || line.charAt(6) == '/')) {
				continue;
			}

			// In fixed-format COBOL, columns 1-6 are the sequence area (may contain
			// change tags like "GJ", "GJ>" etc.), column 7 is the indicator, and
			// columns 8+ are the content area. We must extract content from column 8+
			// to detect EXEC/END-EXEC, NOT use line.trim() which includes the sequence area.
			final String contentArea;
			if (line.length() > 7) {
				contentArea = line.substring(7).trim().toUpperCase();
			} else {
				contentArea = line.trim().toUpperCase();
			}

			// Detect EXEC SQL (or EXEC SQLIMS, EXEC CICS, etc.)
			if (contentArea.startsWith("EXEC ")) {
				inExecBlock = true;
			}

			// Detect END-EXEC
			if (contentArea.startsWith("END-EXEC")) {
				if (inExecBlock) {
					// This END-EXEC correctly closes the EXEC block
					inExecBlock = false;
				} else {
					// Stray END-EXEC outside of any EXEC block — remove it
					// Preserve the period if present (it terminates the enclosing statement)
					if (contentArea.endsWith(".")) {
						// Replace the line content with just a period in column 12
						// (standard area B position for COBOL fixed format)
						final String replacement = line.substring(0, Math.min(11, line.length()))
								+ ".";
						lines.set(i, replacement);
					} else {
						// No period — comment out the entire line
						if (line.length() >= 7) {
							lines.set(i, line.substring(0, 6) + "*" + line.substring(7));
						} else {
							lines.set(i, "      *" + line);
						}
					}
					modified = true;
					LOG.info("Preprocessor: removed stray END-EXEC on line {} (outside EXEC block)", i + 1);
				}
			}
		}

		if (!modified) {
			return cobolFile;
		}

		final File tempDir = Files.createTempDirectory("proleap_pp").toFile();
		final File tempFile = new File(tempDir, cobolFile.getName());
		Files.write(tempFile.toPath(), lines);
		return tempFile;
	}

	/**
	 * Preprocesses COBOL source to fix condition continuation lines in the PROCEDURE DIVISION
	 * that start with a bare number (e.g., "42 OR 48 OR ...").
	 *
	 * The external parser's injectImplicitPeriodBeforeParagraphs heuristic detects lines
	 * matching ^\s+\d{1,2}\s+[A-Za-z] as data level entries and injects a period on the
	 * preceding line. In the PROCEDURE DIVISION, a continuation of an IF condition like:
	 *
	 *     IF CD-PAG OF AP1 = 5 OR 8 OR 18 OR 23 OR 28 OR 38 OR
	 *                               42 OR 48 OR 58 OR 68 OR 78 OR 80 OR
	 *                               83 THEN
	 *
	 * causes "42 OR" to be misidentified as level-42 data entry "OR", and a period is
	 * injected after "OR" on the previous line, breaking the condition.
	 *
	 * Fix: when a PROCEDURE DIVISION line ends with OR or AND, and the next non-blank
	 * line's content area starts with a 1-2 digit number followed by OR/AND/THEN,
	 * move the trailing OR/AND from the current line to the beginning of the next line.
	 * This makes the next line start with a keyword instead of a bare number.
	 */
	private File preprocessConditionContinuationLines(final File cobolFile) throws IOException {
		final List<String> lines = Files.readAllLines(cobolFile.toPath());
		boolean modified = false;
		boolean inProcedureDivision = false;

		for (int i = 0; i < lines.size(); i++) {
			final String line = lines.get(i);

			// Skip comment lines
			if (line.length() >= 7 && (line.charAt(6) == '*' || line.charAt(6) == '/')) {
				continue;
			}

			// Detect PROCEDURE DIVISION
			final String contentUpper;
			if (line.length() > 7) {
				contentUpper = line.substring(7).trim().toUpperCase();
			} else {
				contentUpper = line.trim().toUpperCase();
			}

			if (contentUpper.startsWith("PROCEDURE DIVISION")) {
				inProcedureDivision = true;
				continue;
			}

			if (!inProcedureDivision) {
				continue;
			}

			// Get the content area (columns 8-72) of the current line
			final String effectiveLine = line.length() > 72 ? line.substring(0, 72) : line;
			final String contentArea = effectiveLine.length() > 7 ? effectiveLine.substring(7) : "";
			final String trimmedContent = contentArea.trim().toUpperCase();

			// Check if the line ends with OR or AND
			if (!trimmedContent.endsWith(" OR") && !trimmedContent.endsWith(" AND")
					&& !trimmedContent.equals("OR") && !trimmedContent.equals("AND")) {
				continue;
			}

			// Determine the trailing keyword
			final String trailingKeyword;
			if (trimmedContent.endsWith("OR")) {
				trailingKeyword = "OR";
			} else {
				trailingKeyword = "AND";
			}

			// Find the next non-blank, non-comment line
			int nextIdx = i + 1;
			while (nextIdx < lines.size()) {
				final String nl = lines.get(nextIdx);
				if (nl.length() >= 7 && (nl.charAt(6) == '*' || nl.charAt(6) == '/')) {
					nextIdx++;
					continue;
				}
				final String nc = nl.length() > 7 ? nl.substring(7) : "";
				if (nc.trim().isEmpty()) {
					nextIdx++;
					continue;
				}
				break;
			}

			if (nextIdx >= lines.size()) {
				continue;
			}

			final String nextLine = lines.get(nextIdx);
			final String nextContentArea = nextLine.length() > 7 ? nextLine.substring(7) : "";
			final String nextTrimmed = nextContentArea.trim().toUpperCase();

			// Check if the next line starts with a 1-2 digit number followed by
			// OR, AND, THEN, or end-of-content (which would look like a data level entry)
			if (!nextTrimmed.matches("^\\d{1,2}(\\s+(OR|AND|THEN)\\b.*|\\s*$)")) {
				continue;
			}

			// This is a condition continuation that will be misidentified as a data level entry.
			// Fix: remove trailing keyword from current line and prepend it to the next line.

			// Remove trailing keyword from current line
			final int kwPos = effectiveLine.toUpperCase().lastIndexOf(trailingKeyword);
			if (kwPos < 0) {
				continue;
			}
			String newCurrentLine = effectiveLine.substring(0, kwPos).stripTrailing();
			// Preserve sequence/comment area beyond column 72
			final String commentArea = line.length() > 72 ? line.substring(72) : "";
			if (!commentArea.isEmpty()) {
				while (newCurrentLine.length() < 72) {
					newCurrentLine += " ";
				}
				newCurrentLine += commentArea;
			}

			// Prepend keyword to the next line
			final String nextPrefix = nextLine.length() >= 7 ? nextLine.substring(0, 7) : nextLine;
			final String nextRest = nextLine.length() > 7 ? nextLine.substring(7) : "";

			// Find leading spaces before the number
			int spaceCount = 0;
			for (int ci = 0; ci < nextRest.length(); ci++) {
				if (nextRest.charAt(ci) == ' ') {
					spaceCount++;
				} else {
					break;
				}
			}

			// Insert "OR " (or "AND ") before the number, using some leading spaces
			final String kwPrefix = trailingKeyword + " ";
			final int kwLen = kwPrefix.length();
			final int newSpaceCount = Math.max(0, spaceCount - kwLen);
			final StringBuilder sbNext = new StringBuilder();
			sbNext.append(nextPrefix);
			for (int s = 0; s < newSpaceCount; s++) {
				sbNext.append(' ');
			}
			sbNext.append(kwPrefix);
			sbNext.append(nextRest.substring(spaceCount));

			lines.set(i, newCurrentLine);
			lines.set(nextIdx, sbNext.toString());
			modified = true;

			LOG.info("Preprocessor: moved trailing {} from line {} to line {} to prevent implicit period injection",
					trailingKeyword, i + 1, nextIdx + 1);
		}

		if (!modified) {
			return cobolFile;
		}

		LOG.info("Preprocessor: fixed condition continuation lines in {}", cobolFile.getName());
		final File tempDir = Files.createTempDirectory("proleap_pp").toFile();
		final File tempFile = new File(tempDir, cobolFile.getName());
		Files.write(tempFile.toPath(), lines);
		return tempFile;
	}

	/**
	 * Preprocesses COBOL source to move scope terminator keywords (END-IF, END-PERFORM,
	 * END-EVALUATE, END-STRING, END-CALL, END-READ, END-WRITE, etc.) from Area A (column 8)
	 * to Area B (column 12) in the PROCEDURE DIVISION.
	 *
	 * The parser's injectImplicitPeriodBeforeParagraphs heuristic detects words followed
	 * by a period in Area A as paragraph names. When a scope terminator like END-IF. is
	 * coded in Area A, it gets misidentified as a paragraph, causing a spurious period
	 * to be injected on the previous line. This prematurely terminates the enclosing
	 * IF block, leaving the END-IF orphaned and causing a parse error.
	 *
	 * Fix: indent these scope terminators from Area A to Area B so the parser's paragraph
	 * detection pattern (which requires content at column 8) does not match them.
	 */
	private File preprocessScopeTerminatorsInAreaA(final File cobolFile) throws IOException {
		final List<String> lines = Files.readAllLines(cobolFile.toPath());
		boolean modified = false;
		boolean inProcedureDivision = false;

		// Pattern matching the parser's paragraph detection: exactly 6 spaces + 1 space indicator
		// + word starting at column 8 (Area A). We look for END-xxx keywords.
		final Pattern scopeTerminatorInAreaA = Pattern.compile(
			"^(\\s{6} )(END-(?:IF|PERFORM|EVALUATE|STRING|UNSTRING|CALL|READ|WRITE|COMPUTE|SEARCH|START|RETURN|DELETE|REWRITE|ACCEPT|ADD|SUBTRACT|MULTIPLY|DIVIDE|DISPLAY|EXEC|INVOKE|XML|JSON|RECEIVE|SEND))\\s*\\.(.*)$",
			Pattern.CASE_INSENSITIVE);

		for (int i = 0; i < lines.size(); i++) {
			final String line = lines.get(i);

			// Skip comment lines
			if (line.length() >= 7 && (line.charAt(6) == '*' || line.charAt(6) == '/')) {
				continue;
			}

			// Detect PROCEDURE DIVISION
			final String contentUpper;
			if (line.length() > 7) {
				contentUpper = line.substring(7).trim().toUpperCase();
			} else {
				contentUpper = line.trim().toUpperCase();
			}

			if (contentUpper.startsWith("PROCEDURE DIVISION")) {
				inProcedureDivision = true;
				continue;
			}

			if (!inProcedureDivision) {
				continue;
			}

			final java.util.regex.Matcher m = scopeTerminatorInAreaA.matcher(line);
			if (m.matches()) {
				// Move the keyword from Area A (column 8) to Area B (column 12)
				// by replacing the prefix with 6 spaces + indicator + 4 spaces
				final String prefix = m.group(1); // "      " (6 spaces + indicator)
				final String keyword = m.group(2); // e.g., "END-IF"
				final String rest = m.group(3);    // anything after the period
				final String newLine = prefix + "    " + keyword + "." + rest;
				lines.set(i, newLine);
				modified = true;
				LOG.info("Preprocessor: moved scope terminator {} from Area A to Area B on line {}",
						keyword, i + 1);
			}
		}

		if (!modified) {
			return cobolFile;
		}

		final File tempDir = Files.createTempDirectory("proleap_pp").toFile();
		final File tempFile = new File(tempDir, cobolFile.getName());
		Files.write(tempFile.toPath(), lines);
		return tempFile;
	}

	/**
	 * Converts bare PERFORM statements (PERFORM with no procedure name, no UNTIL/TIMES/VARYING,
	 * and no matching END-PERFORM) to CONTINUE.
	 *
	 * In IBM ILE COBOL, a bare PERFORM (Format 1 with no procedure name) is treated as a no-op.
	 * The ProLeap ANTLR grammar, however, sees PERFORM followed by imperative statements and
	 * interprets it as an inline PERFORM (expecting END-PERFORM). When the body of the
	 * enclosing IF/EVALUATE/etc. ends before END-PERFORM, the parser reports a syntax error.
	 *
	 * This preprocessor detects a line whose content (after the sequence/indicator area) is
	 * exactly "PERFORM" (with optional trailing whitespace), where the next non-blank, non-comment
	 * line starts with a COBOL verb (not a PERFORM continuation keyword like UNTIL/VARYING/TIMES/
	 * WITH/THROUGH/THRU and not a paragraph/section name). It replaces the bare PERFORM with
	 * CONTINUE, which is semantically equivalent (both are no-ops).
	 */
	private File preprocessBarePerform(final File cobolFile) throws IOException {
		final List<String> lines = Files.readAllLines(cobolFile.toPath());
		boolean modified = false;
		boolean inProcedureDivision = false;

		// COBOL verbs that indicate the PERFORM is bare (next statement, not a continuation)
		final java.util.Set<String> cobolVerbs = new java.util.HashSet<>(java.util.Arrays.asList(
			"ACCEPT", "ACQUIRE", "ADD", "ALTER", "CALL", "CANCEL", "CLOSE", "COMMIT",
			"COMPUTE", "CONTINUE", "DELETE", "DISABLE", "DISPLAY", "DIVIDE", "DROP",
			"ENABLE", "ENTRY", "EVALUATE", "EXEC", "EXIT", "GENERATE", "GO", "GOBACK",
			"IF", "INITIALIZE", "INITIATE", "INSPECT", "MERGE", "MOVE", "MULTIPLY",
			"NEXT", "OPEN", "PERFORM", "PURGE", "READ", "RECEIVE", "RELEASE",
			"RETURN", "REWRITE", "ROLLBACK", "SEARCH", "SEND", "SET", "SORT",
			"START", "STOP", "STRING", "SUBTRACT", "TERMINATE", "UNSTRING",
			"WRITE", "XML", "END-IF", "END-PERFORM", "END-EVALUATE", "END-CALL",
			"END-READ", "END-WRITE", "END-COMPUTE", "END-SEARCH", "END-STRING",
			"END-UNSTRING", "END-START", "END-RETURN", "END-DELETE", "END-REWRITE",
			"END-ACCEPT", "END-ADD", "END-SUBTRACT", "END-MULTIPLY", "END-DIVIDE",
			"END-DISPLAY", "END-EXEC", "END-INVOKE", "END-XML", "END-JSON",
			"END-RECEIVE", "END-SEND", "ELSE", "WHEN"
		));

		// Keywords that continue a PERFORM statement (not bare)
		final java.util.Set<String> performContinuationKeywords = new java.util.HashSet<>(java.util.Arrays.asList(
			"UNTIL", "VARYING", "TIMES", "WITH", "THROUGH", "THRU", "TEST"
		));

		for (int i = 0; i < lines.size(); i++) {
			final String line = lines.get(i);

			// Skip comment lines
			if (line.length() >= 7 && (line.charAt(6) == '*' || line.charAt(6) == '/')) {
				continue;
			}

			// Detect PROCEDURE DIVISION
			final String contentUpper;
			if (line.length() > 7) {
				contentUpper = line.substring(7).trim().toUpperCase();
			} else {
				contentUpper = line.trim().toUpperCase();
			}

			if (contentUpper.startsWith("PROCEDURE DIVISION")) {
				inProcedureDivision = true;
				continue;
			}

			if (!inProcedureDivision) {
				continue;
			}

			// Check if this line's content is exactly "PERFORM" (bare PERFORM)
			if (!contentUpper.equals("PERFORM")) {
				continue;
			}

			// Look at the next non-blank, non-comment line
			for (int j = i + 1; j < lines.size(); j++) {
				final String nextLine = lines.get(j);

				// Skip blank lines
				if (nextLine.trim().isEmpty()) {
					continue;
				}

				// Skip comment lines
				if (nextLine.length() >= 7 && (nextLine.charAt(6) == '*' || nextLine.charAt(6) == '/')) {
					continue;
				}

				// Get the first word of the next line
				final String nextContent;
				if (nextLine.length() > 7) {
					nextContent = nextLine.substring(7).trim().toUpperCase();
				} else {
					nextContent = nextLine.trim().toUpperCase();
				}

				if (nextContent.isEmpty()) {
					continue;
				}

				final String firstWord = nextContent.split("\\s+")[0].replaceAll("\\.", "");

				// If next line starts with a PERFORM continuation keyword, it's a multi-line PERFORM
				if (performContinuationKeywords.contains(firstWord)) {
					break; // not a bare PERFORM, leave it alone
				}

				// If next line starts with a COBOL verb, this is a bare PERFORM
				if (cobolVerbs.contains(firstWord)) {
					// Replace the word PERFORM with CONTINUE, preserving surrounding whitespace
					final int perfIdx = line.toUpperCase().indexOf("PERFORM", 7);
					if (perfIdx >= 0) {
						final String newLine = line.substring(0, perfIdx) + "CONTINUE" + line.substring(perfIdx + 7);
						lines.set(i, newLine);
						modified = true;
						LOG.info("Preprocessor: replaced bare PERFORM with CONTINUE on line {} (next line starts with {})",
								i + 1, firstWord);
					}
				}
				// else: next line starts with a paragraph/section name, it's an out-of-line PERFORM
				break;
			}
		}

		if (!modified) {
			return cobolFile;
		}

		final File tempDir = Files.createTempDirectory("proleap_pp").toFile();
		final File tempFile = new File(tempDir, cobolFile.getName());
		Files.write(tempFile.toPath(), lines);
		return tempFile;
	}

	/**
	 * Converts COBOL debugging lines (indicator 'D' or 'd' in column 7) to comment
	 * lines (indicator '*'). In COBOL fixed format, column 7 = 'D' marks a debugging
	 * line that is only compiled when WITH DEBUGGING MODE is active. The ProLeap
	 * parser recognizes these as DEBUG line types but then processes their content as
	 * normal code, which fails when the content is not valid COBOL (e.g., Portuguese
	 * comment text that happens to start with 'D' at column 7).
	 *
	 * Since we never need debug-only code in migration, converting to comments is safe.
	 */
	private File preprocessDebugLines(final File cobolFile) throws IOException {
		final List<String> lines = Files.readAllLines(cobolFile.toPath());
		boolean modified = false;

		for (int i = 0; i < lines.size(); i++) {
			final String line = lines.get(i);

			// In fixed format, column 7 (0-indexed position 6) is the indicator area
			if (line.length() >= 7) {
				final char indicator = line.charAt(6);
				if (indicator == 'D' || indicator == 'd') {
					// Replace column 7 with '*' to make it a comment line
					final String newLine = line.substring(0, 6) + '*' + line.substring(7);
					lines.set(i, newLine);
					modified = true;
					LOG.info("Preprocessor: converted debug line to comment on line {}: {}",
							i + 1, line.trim());
				}
			}
		}

		if (!modified) {
			return cobolFile;
		}

		final File tempDir = Files.createTempDirectory("proleap_pp").toFile();
		final File tempFile = new File(tempDir, cobolFile.getName());
		Files.write(tempFile.toPath(), lines);
		return tempFile;
	}

	/**
	 * Scans the original COBOL source for XML PARSE blocks and extracts metadata:
	 * the containing paragraph name, the identifier being parsed, and the
	 * PROCESSING PROCEDURE handler name. This must be called BEFORE preprocessXmlParse
	 * comments out the blocks.
	 */
	private void extractXmlParseInfo(final File cobolFile) throws IOException {
		final List<String> lines = Files.readAllLines(cobolFile.toPath());
		String lastParagraph = null;
		boolean inProcedureDivision = false;

		for (int i = 0; i < lines.size(); i++) {
			final String line = lines.get(i);

			if (line.length() < 8) {
				continue;
			}

			// Skip comment lines
			final char indicator = line.charAt(6);
			if (indicator == '*' || indicator == '/') {
				continue;
			}

			final int endCol = Math.min(line.length(), 72);
			final String codeArea = line.substring(7, endCol).trim();
			final String codeAreaUpper = codeArea.toUpperCase();

			// Detect PROCEDURE DIVISION
			if (codeAreaUpper.startsWith("PROCEDURE DIVISION")) {
				inProcedureDivision = true;
				continue;
			}

			if (!inProcedureDivision) {
				continue;
			}

			// Detect paragraph names: a word starting in Area A (columns 8-11, 0-indexed 7-10)
			// followed by a period, on a line by itself or with the period.
			// Area A is columns 8-11 (0-indexed 7-10). A paragraph name starts in Area A.
			if (line.length() >= 8) {
				final char col8 = line.charAt(7);
				if (col8 != ' ' && Character.isLetter(col8)) {
					// This line starts in Area A — could be a paragraph name
					final String trimmed = codeArea.replaceAll("\\s+", "");
					if (trimmed.endsWith(".") && !trimmed.contains(" ")) {
						// Single word followed by period = paragraph name
						lastParagraph = trimmed.substring(0, trimmed.length() - 1);
					} else {
						// Could be "PARA-NAME." with spaces before the period
						final String[] parts = codeArea.split("\\s+");
						if (parts.length >= 1 && codeArea.trim().endsWith(".")
								&& parts[0].matches("[A-Za-z][A-Za-z0-9-]*")) {
							// Check if the rest is just a period
							final String withoutFirst = codeArea.substring(parts[0].length()).trim();
							if (withoutFirst.equals(".")) {
								lastParagraph = parts[0];
							}
						}
					}
				}
			}

			// Detect XML PARSE <identifier>
			if (codeAreaUpper.startsWith("XML PARSE ")) {
				final String afterXmlParse = codeArea.substring("XML PARSE ".length()).trim();
				// The identifier is the first word after XML PARSE
				final String[] parts = afterXmlParse.split("\\s+");
				final String identifier = parts.length > 0 ? parts[0].toUpperCase() : null;

				if (identifier != null && lastParagraph != null) {
					// Now scan forward for PROCESSING PROCEDURE <handler>
					String handler = null;
					for (int j = i; j < lines.size() && j < i + 10; j++) {
						final String scanLine = lines.get(j);
						if (scanLine.length() < 8) continue;
						if (scanLine.charAt(6) == '*' || scanLine.charAt(6) == '/') continue;
						final int scanEnd = Math.min(scanLine.length(), 72);
						final String scanCode = scanLine.substring(7, scanEnd).trim().toUpperCase();
						if (scanCode.startsWith("PROCESSING PROCEDURE")) {
							final String afterPP = scanCode.substring("PROCESSING PROCEDURE".length()).trim();
							final String[] ppParts = afterPP.split("\\s+");
							if (ppParts.length > 0 && !ppParts[0].isEmpty()) {
								handler = ppParts[0];
							}
							break;
						}
						if (scanCode.startsWith("END-XML")) {
							break;
						}
					}

					if (handler != null) {
						xmlParseInfoList.add(new XmlParseInfo(lastParagraph, identifier, handler));
						LOG.info("Extracted XML PARSE info: paragraph={}, identifier={}, handler={}",
								lastParagraph, identifier, handler);
					} else {
						LOG.warn("XML PARSE at line {} in paragraph {} has no PROCESSING PROCEDURE",
								i + 1, lastParagraph);
					}
				}
			}
		}
	}

	/**
	 * Post-processes generated Java to inject xmlParse() calls for XML PARSE blocks
	 * that were commented out by the preprocessor. Uses metadata previously extracted
	 * by extractXmlParseInfo().
	 *
	 * For each XML PARSE block, finds the generated Java method corresponding to
	 * the containing COBOL paragraph and inserts:
	 *   xmlParse(identifier, () -> { handler(); });
	 */
	private String postProcessXmlParse(String content) {
		if (xmlParseInfoList.isEmpty()) {
			return content;
		}

		for (final XmlParseInfo info : xmlParseInfoList) {
			// Convert COBOL names to Java identifiers using the same rules as the transformer:
			// lowercase, hyphens to underscores
			final String methodName = info.containingParagraph.toLowerCase().replace("-", "_").replace("#", "$");
			final String identifierName = info.identifier.toLowerCase().replace("-", "_").replace("#", "$");
			final String handlerName = info.handler.toLowerCase().replace("-", "_").replace("#", "$");

			// Resolve qualified name: the identifier may be a field inside an inner class
			// (e.g., FD record fields like R-FR0400NAV are generated as fr0400navContent.r_fr0400nav).
			// The generated Java pattern for FD records is:
			//   public class XxxType {
			//       protected String r_xxx = ...;
			//   }
			//   public XxxType xxxContent = new XxxType();
			// We need to find the content variable that gives access to the field.
			String qualifiedIdentifier = identifierName;
			// Search for the field declaration inside an inner class
			final Pattern fieldDeclPattern = Pattern.compile(
					"public\\s+class\\s+(\\w+Type)\\s*\\{[^}]*\\b" + Pattern.quote(identifierName) + "\\b",
					Pattern.DOTALL);
			final Matcher fieldDeclMatcher = fieldDeclPattern.matcher(content);
			if (fieldDeclMatcher.find()) {
				final String typeName = fieldDeclMatcher.group(1);
				// Now find the content variable: public XxxType xxxContent = new XxxType();
				final Pattern contentVarPattern = Pattern.compile(
						"\\b" + Pattern.quote(typeName) + "\\s+(\\w+)\\s*=\\s*new\\s+" + Pattern.quote(typeName) + "\\(\\)");
				final Matcher contentVarMatcher = contentVarPattern.matcher(content);
				if (contentVarMatcher.find()) {
					qualifiedIdentifier = contentVarMatcher.group(1) + "." + identifierName;
					LOG.info("postProcessXmlParse: resolved {} to qualified name {} (via inner class {})",
							identifierName, qualifiedIdentifier, typeName);
				}
			}

			// Find the method declaration: public void methodName() throws Exception {
			final String methodDecl = "public void " + methodName + "() throws Exception {";
			final int methodStart = content.indexOf(methodDecl);
			if (methodStart < 0) {
				LOG.warn("postProcessXmlParse: method {}() not found for XML PARSE in paragraph {}",
						methodName, info.containingParagraph);
				continue;
			}

			// Find the opening brace of this method
			final int bracePos = content.indexOf('{', methodStart);
			if (bracePos < 0) {
				continue;
			}

			// Insert the xmlParse call right after the opening brace
			final String xmlParseCall = "\n        xmlParse(" + qualifiedIdentifier + ", () -> { " + handlerName + "(); });";
			content = content.substring(0, bracePos + 1) + xmlParseCall + content.substring(bracePos + 1);

			LOG.info("postProcessXmlParse: injected xmlParse({}, () -> {{ {}(); }}) into {}()",
					qualifiedIdentifier, handlerName, methodName);
		}

		return content;
	}

	/**
	 * Preprocesses COBOL source to comment out XML PARSE ... END-XML blocks.
	 * IBM ILE COBOL supports the XML PARSE statement for XML processing, but the
	 * ProLeap ANTLR grammar does not include this statement. Rather than extending
	 * the grammar for only 3 programs, we comment out the entire block (from the
	 * line containing XML PARSE to the line containing END-XML, inclusive).
	 *
	 * The pattern in fixed format is:
	 *     columns 7+: XML PARSE <identifier>
	 *       ... intermediate lines (PROCESSING PROCEDURE, ON EXCEPTION, etc.)
	 *     columns 7+: END-XML
	 */
	private File preprocessXmlParse(final File cobolFile) throws IOException {
		final List<String> lines = Files.readAllLines(cobolFile.toPath());
		boolean modified = false;
		boolean insideXmlParse = false;
		int blockStartLine = -1;

		for (int i = 0; i < lines.size(); i++) {
			final String line = lines.get(i);

			// Skip lines that are too short to have code content
			if (line.length() < 12) {
				continue;
			}

			// In fixed format, column 7 (0-indexed pos 6) is the indicator area.
			// Skip lines that are already comments.
			final char indicator = line.charAt(6);
			if (indicator == '*' || indicator == '/') {
				continue;
			}

			// Extract the code area (columns 8-72, 0-indexed 7-71)
			final int endCol = Math.min(line.length(), 72);
			final String codeArea = line.substring(7, endCol).trim().toUpperCase();

			if (!insideXmlParse && codeArea.startsWith("XML PARSE")) {
				insideXmlParse = true;
				blockStartLine = i + 1; // 1-based for logging
				// Comment out this line
				final String newLine = line.substring(0, 6) + '*' + line.substring(7);
				lines.set(i, newLine);
				modified = true;
			} else if (insideXmlParse) {
				// Comment out every line inside the block
				final String newLine = line.substring(0, 6) + '*' + line.substring(7);
				lines.set(i, newLine);

				if (codeArea.startsWith("END-XML")) {
					LOG.warn("Preprocessor: commented out XML PARSE...END-XML block (lines {}-{})",
							blockStartLine, i + 1);
					insideXmlParse = false;
				}
			}
		}

		if (insideXmlParse) {
			LOG.warn("Preprocessor: unclosed XML PARSE block starting at line {} — commented out to end of file",
					blockStartLine);
		}

		if (!modified) {
			return cobolFile;
		}

		final File tempDir = Files.createTempDirectory("proleap_pp").toFile();
		final File tempFile = new File(tempDir, cobolFile.getName());
		Files.write(tempFile.toPath(), lines);
		return tempFile;
	}

	/**
	 * Preprocesses COBOL source to comment out COPY statements whose copybooks
	 * cannot be found in any configured directory. This prevents the preprocessor
	 * from throwing CobolPreprocessorException and crashing the entire generation.
	 * The missing copybook is replaced with a COBOL comment line, allowing the
	 * program to proceed to parsing/compilation where the missing fields will
	 * produce more informative error messages.
	 *
	 * DDS copy statements (COPY DDSR-xxx, COPY DDS-xxx, COPY DDR-xxx, COPY DD-xxx)
	 * are NOT affected — they are resolved via DDS schema files, not copybook files.
	 */
	private File preprocessMissingCopyBooks(final File cobolFile, final CobolParserParams params) throws IOException {
		final List<File> copyBookDirs = params.getCopyBookDirectories();
		if (copyBookDirs == null || copyBookDirs.isEmpty()) {
			return cobolFile;
		}

		final List<String> lines = Files.readAllLines(cobolFile.toPath());
		boolean modified = false;

		// Pattern to match COPY statements in COBOL fixed format.
		// Captures: group(1) = copybook name, group(2) = optional OF/IN library
		// Examples:
		//   COPY MYCOPY OF MYLIB.
		//   COPY MYBOOK.
		//   COPY 'MYBOOK'.
		final Pattern copyPattern = Pattern.compile(
			"^.{6}\\s+COPY\\s+(\\S+?)(?:\\s+(?:OF|IN)\\s+(\\S+?))?\\s*\\.\\s*$",
			Pattern.CASE_INSENSITIVE);

		for (int i = 0; i < lines.size(); i++) {
			final String line = lines.get(i);

			// Skip comment lines
			if (line.length() >= 7 && (line.charAt(6) == '*' || line.charAt(6) == '/')) {
				continue;
			}

			final Matcher m = copyPattern.matcher(line);
			if (!m.matches()) {
				continue;
			}

			String copyBookName = m.group(1);

			// Strip quotes if present
			copyBookName = copyBookName.replace("'", "").replace("\"", "");

			// Skip DDS copy statements — they are resolved differently
			final String upper = copyBookName.toUpperCase();
			if (upper.startsWith("DDSR-") || upper.startsWith("DDS-")
					|| upper.startsWith("DDR-") || upper.startsWith("DD-")) {
				continue;
			}

			// Check if the copybook can be found in any configured directory
			if (!canFindCopyBook(copyBookName, copyBookDirs)) {
				LOG.warn("COPY book not found, commenting out: {} (line {})", copyBookName, i + 1);
				// Replace with COBOL comment line (asterisk in column 7)
				final String commented = "      * COPY book not found: " + copyBookName;
				lines.set(i, commented);
				modified = true;
			}
		}

		if (!modified) {
			return cobolFile;
		}

		final File tempDir = Files.createTempDirectory("proleap_pp").toFile();
		final File tempFile = new File(tempDir, cobolFile.getName());
		Files.write(tempFile.toPath(), lines);
		return tempFile;
	}

	/**
	 * Checks if a copybook file can be found in any of the configured directories.
	 * Mirrors the logic of CobolWordCopyBookFinderImpl: scans each directory for a
	 * file whose base name (case-insensitive) matches the copybook name.
	 */
	private boolean canFindCopyBook(final String copyBookName, final List<File> copyBookDirs) {
		for (final File dir : copyBookDirs) {
			if (!dir.isDirectory()) {
				continue;
			}
			final File[] files = dir.listFiles();
			if (files == null) {
				continue;
			}
			for (final File f : files) {
				final String fileName = f.getName();
				// Match with extension stripped
				final int dot = fileName.lastIndexOf('.');
				final String baseName = (dot > 0) ? fileName.substring(0, dot) : fileName;
				if (baseName.equalsIgnoreCase(copyBookName)) {
					return true;
				}
				// Also match the full file name (when copybook name includes extension)
				if (fileName.equalsIgnoreCase(copyBookName)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Fixes SET ADDRESS OF type mismatches.
	 *
	 * In COBOL, SET ADDRESS OF GROUP TO POINTER overlays a group structure onto a
	 * memory region pointed to by a pointer variable. The transformer generates a
	 * simple assignment (e.g., {@code groupVar = pointerVar;}) which fails to compile
	 * when the left side is a typed inner class and the right side is Object or String.
	 *
	 * This post-processor detects lines with SET ADDRESS OF in the COBOL comment and
	 * transforms them:
	 * - {@code typedVar = expr;} → {@code CobolMove.moveStringToGroup(String.valueOf(expr), typedVar);}
	 *
	 * Only applies when the left-hand variable is declared as a typed inner class (XxxType).
	 */
	private String postProcessFixSetAddressOfTypeMismatch(String content) {
		// Build map of typed inner class variables: varName -> TypeName
		final java.util.Map<String, String> typedVars = new java.util.HashMap<>();
		java.util.regex.Matcher vtMatcher = java.util.regex.Pattern.compile(
			"protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\(\\)").matcher(content);
		while (vtMatcher.find()) {
			typedVars.put(vtMatcher.group(2), vtMatcher.group(1));
		}
		// Also capture alias declarations: protected SomeType alias = otherVar;
		java.util.regex.Matcher aliasMatcher = java.util.regex.Pattern.compile(
			"protected\\s+(\\w+Type)\\s+(\\w+)\\s*=\\s*\\w+\\s*;").matcher(content);
		while (aliasMatcher.find()) {
			typedVars.put(aliasMatcher.group(2), aliasMatcher.group(1));
		}

		if (typedVars.isEmpty()) {
			return content;
		}

		boolean changed = false;
		StringBuilder sb = new StringBuilder();
		String[] lines = content.split("\n", -1);
		for (String line : lines) {
			// Only process lines with SET ADDRESS OF in the COBOL comment
			if (line.contains("SET ADDRESS OF")) {
				// Pattern: varName = expr;  // ... SET ADDRESS OF ...
				java.util.regex.Matcher m = java.util.regex.Pattern.compile(
					"^(\\s+)(\\w+)\\s*=\\s*(.+);(\\s*//.*SET ADDRESS OF.*)$").matcher(line);
				if (m.find()) {
					String indent = m.group(1);
					String varName = m.group(2);
					String expr = m.group(3);
					String comment = m.group(4);

					if (typedVars.containsKey(varName)) {
						// Replace assignment with moveStringToGroup
						String newLine = indent + "CobolMove.moveStringToGroup(String.valueOf(" + expr.trim() + "), " + varName + ");" + comment;
						sb.append(newLine).append("\n");
						changed = true;
						continue;
					}
				}
			}
			sb.append(line).append("\n");
		}

		if (!changed) {
			return content;
		}

		// Remove trailing newline added by split
		String result = sb.toString();
		if (result.endsWith("\n") && !content.endsWith("\n")) {
			result = result.substring(0, result.length() - 1);
		}
		return result;
	}

	/**
	 * Preprocesses COBOL source to insert the missing TO keyword in MOVE statements.
	 * IBM ILE COBOL on AS/400 tolerates "MOVE ZERO LK-STATUS" (without TO), treating
	 * the identifier after the source operand as the destination. The ProLeap ANTLR
	 * grammar requires "MOVE source TO dest1 dest2 ...".
	 *
	 * Pattern detected (in PROCEDURE DIVISION only):
	 *   MOVE <figurative-constant> <identifier>
	 * where <figurative-constant> is ZERO, ZEROS, ZEROES, SPACE, SPACES,
	 * HIGH-VALUE, HIGH-VALUES, LOW-VALUE, LOW-VALUES, or a numeric literal,
	 * and no TO keyword follows the source operand on the same logical statement.
	 */
	private File preprocessMissingMoveTo(final File cobolFile) throws IOException {
		final List<String> lines = Files.readAllLines(cobolFile.toPath());
		boolean modified = false;
		boolean inProcedureDivision = false;

		// Pattern matches: MOVE <figurative-constant-or-literal> <identifier>
		// where there is no TO between the source and the identifier.
		// Group 1: everything up to and including the source operand + trailing whitespace
		// Group 2: the destination identifier(s)
		// Figurative constants: ZERO(S/ES), SPACE(S), HIGH-VALUE(S), LOW-VALUE(S), NULL(S), QUOTE(S)
		// Also matches numeric literals like 0, +0, 002, etc.
		final Pattern moveNoToPattern = Pattern.compile(
			"(\\bMOVE\\s+(?:ZEROS?|ZEROES|SPACES?|HIGH-VALUES?|LOW-VALUES?|NULLS?|QUOTES?|[+-]?\\d+)\\s+)"
			+ "((?!TO\\b)[A-Z][A-Z0-9-]+.*)",
			Pattern.CASE_INSENSITIVE
		);

		for (int i = 0; i < lines.size(); i++) {
			final String line = lines.get(i);

			// Skip comment lines
			if (line.length() >= 7 && (line.charAt(6) == '*' || line.charAt(6) == '/')) {
				continue;
			}

			final String contentUpper;
			if (line.length() > 7) {
				contentUpper = line.substring(7).trim().toUpperCase();
			} else {
				contentUpper = line.trim().toUpperCase();
			}

			if (contentUpper.startsWith("PROCEDURE DIVISION")) {
				inProcedureDivision = true;
				continue;
			}

			if (!inProcedureDivision) {
				continue;
			}

			// Quick checks before regex
			if (!contentUpper.contains("MOVE")) {
				continue;
			}
			// If it already has TO after source, skip
			if (contentUpper.matches(".*\\bMOVE\\s+\\S+\\s+TO\\b.*")) {
				continue;
			}
			// If it is MOVE CORRESPONDING, skip
			if (contentUpper.matches(".*\\bMOVE\\s+CORR(ESPONDING)?\\b.*")) {
				continue;
			}

			final Matcher m = moveNoToPattern.matcher(line);
			if (m.find()) {
				// Insert TO between source and destination
				final String before = line.substring(0, m.start(2));
				final String after = line.substring(m.start(2));
				final String newLine = before + "TO " + after;
				lines.set(i, newLine);
				modified = true;
				LOG.info("preprocessMissingMoveTo: line {} — inserted TO: {}", i + 1, newLine.trim());
			}
		}

		if (!modified) {
			return cobolFile;
		}

		final File tempDir = Files.createTempDirectory("proleap_pp").toFile();
		final File tempFile = new File(tempDir, cobolFile.getName());
		Files.write(tempFile.toPath(), lines);
		return tempFile;
	}
}
