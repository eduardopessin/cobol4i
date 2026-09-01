package io.proleap.cobol.transform.java.dds;

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

import io.proleap.cobol.transform.java.validation.TransformerPaths;

/**
 * Generates Vaadin @Route view classes from IBM i DDS DSPF (Display File) definitions.
 * Maps DDS screen formats to Vaadin UI components:
 * <ul>
 *   <li>Records → FormLayout sections</li>
 *   <li>SFL records → Grid</li>
 *   <li>SFLCTL records → Grid container with pagination</li>
 *   <li>WINDOW → Dialog</li>
 *   <li>CA/CF keys → Button toolbar</li>
 *   <li>Fields → TextField, NumberField, etc.</li>
 *   <li>Indicators → visibility/enabled bindings</li>
 * </ul>
 */
public class DdsToVaadinGenerator {

	private static final String PACKAGE_NAME = "io.proleap.cobol.generated.views";
	private static final String NL = System.lineSeparator();

	private final Path outputDir;

	public DdsToVaadinGenerator(final Path outputDir) {
		this.outputDir = outputDir;
	}

	public static void main(final String[] args) throws IOException {
		final Path outputDir = args.length > 0 ? Path.of(args[0]) : Path.of("generated/views");
		// DDS display files (.dspf). Defaults to <base>/dds under the configured
		// extraction root; override with argv[1] or -Dcobol.base.dir.
		final String ddsDir = args.length > 1
				? args[1]
				: TransformerPaths.baseDir().resolve("dds").toString();

		final DdsToVaadinGenerator generator = new DdsToVaadinGenerator(outputDir);

		final File[] dspfFiles = new File(ddsDir).listFiles((dir, name) ->
				name.toLowerCase().endsWith(".dspf"));

		if (dspfFiles == null || dspfFiles.length == 0) {
			System.err.println("No DSPF files found in " + ddsDir);
			System.exit(1);
		}

		int totalGenerated = 0;
		for (final File dspfFile : dspfFiles) {
			final DspfDefinition dspf = generator.parseDspf(dspfFile.toPath());
			generator.generateView(dspf);
			totalGenerated++;
			System.out.println("Generated view for " + dspfFile.getName()
					+ " (" + dspf.records.size() + " records, "
					+ dspf.subfiles.size() + " subfiles)");
		}
		System.out.println("Total views generated: " + totalGenerated);
	}

	// ---- DDS DSPF Parser ----

	DspfDefinition parseDspf(final Path dspfFile) throws IOException {
		final List<String> lines = Files.readAllLines(dspfFile);
		final DspfDefinition dspf = new DspfDefinition();
		dspf.fileName = dspfFile.getFileName().toString().replaceAll("\\.[^.]+$", "");

		RecordDef currentRecord = null;

		for (final String rawLine : lines) {
			if (rawLine.length() < 6) {
				continue;
			}

			// DDS fixed format: column 6 (index 5) = form type 'A'
			if (rawLine.charAt(5) != 'A') {
				continue;
			}

			// Column 7 (index 6) = comment indicator
			if (rawLine.length() > 6 && rawLine.charAt(6) == '*') {
				continue; // comment
			}

			// Everything from column 7 onward is DDS content
			final String ddsLine = rawLine.length() > 6 ? rawLine.substring(6).stripTrailing() : "";

			// File-level keywords (before any record)
			if (currentRecord == null) {
				parseFileKeywords(dspf, ddsLine);
			}

			// Record definition: R <name>
			final Matcher recordMatcher = Pattern.compile("^\\s+R\\s+(\\w+)").matcher(ddsLine);
			if (recordMatcher.find()) {
				final String recordName = recordMatcher.group(1);

				// Check for SFL
				if (ddsLine.contains(" SFL") && !ddsLine.contains("SFLCTL")) {
					final SubfileDef sfl = new SubfileDef();
					sfl.name = recordName;
					dspf.subfiles.put(recordName, sfl);
					currentRecord = sfl;
				} else if (ddsLine.contains("SFLCTL(")) {
					final Matcher sflCtlMatcher = Pattern.compile("SFLCTL\\((\\w+)\\)").matcher(ddsLine);
					if (sflCtlMatcher.find()) {
						final String sflName = sflCtlMatcher.group(1);
						final SubfileCtlDef ctl = new SubfileCtlDef();
						ctl.name = recordName;
						ctl.subfileName = sflName;
						dspf.subfileControls.put(recordName, ctl);
						currentRecord = ctl;
					}
				} else {
					currentRecord = new RecordDef();
					currentRecord.name = recordName;
					dspf.records.put(recordName, currentRecord);
				}
				continue;
			}

			if (currentRecord == null) {
				parseFileKeywords(dspf, ddsLine);
				continue;
			}

			// Parse record-level keywords
			parseRecordKeywords(currentRecord, dspf, ddsLine);

			// Parse field definitions
			parseField(currentRecord, ddsLine);

			// Parse literals (static text)
			parseLiteral(currentRecord, ddsLine);
		}

		return dspf;
	}

	private void parseFileKeywords(final DspfDefinition dspf, final String ddsLine) {
		// DSPSIZ
		final Matcher dspsizMatcher = Pattern.compile("DSPSIZ\\((\\d+)\\s+(\\d+)").matcher(ddsLine);
		if (dspsizMatcher.find()) {
			dspf.rows = Integer.parseInt(dspsizMatcher.group(1));
			dspf.cols = Integer.parseInt(dspsizMatcher.group(2));
		}

		// INDARA
		if (ddsLine.contains("INDARA")) {
			dspf.indara = true;
		}

		// Function keys: CA/CF — deduplicate by fkey number
		final Matcher fkeyMatcher = Pattern.compile("(CA|CF)(\\d+)\\((\\d+)\\s+'([^']*)'\\)").matcher(ddsLine);
		while (fkeyMatcher.find()) {
			final int fkeyNumber = Integer.parseInt(fkeyMatcher.group(2));

			// Skip if already registered
			boolean exists = false;
			for (final FunctionKeyDef existing : dspf.functionKeys) {
				if (existing.fkeyNumber == fkeyNumber) {
					exists = true;
					break;
				}
			}
			if (exists) {
				continue;
			}

			final FunctionKeyDef fk = new FunctionKeyDef();
			fk.type = fkeyMatcher.group(1);
			fk.fkeyNumber = fkeyNumber;
			fk.responseIndicator = Integer.parseInt(fkeyMatcher.group(3));
			fk.label = fkeyMatcher.group(4);

			// Check for conditioning indicator
			final Matcher indMatcher = Pattern.compile("(\\d{2})\\s+(CA|CF)" + fk.fkeyNumber).matcher(ddsLine);
			if (indMatcher.find()) {
				fk.conditionIndicator = Integer.parseInt(indMatcher.group(1));
			}

			dspf.functionKeys.add(fk);
		}
	}

	private void parseRecordKeywords(final RecordDef record, final DspfDefinition dspf, final String ddsLine) {
		// WINDOW
		final Matcher windowMatcher = Pattern.compile("WINDOW\\((\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\)").matcher(ddsLine);
		if (windowMatcher.find()) {
			record.isWindow = true;
			record.windowRow = Integer.parseInt(windowMatcher.group(1));
			record.windowCol = Integer.parseInt(windowMatcher.group(2));
			record.windowHeight = Integer.parseInt(windowMatcher.group(3));
			record.windowWidth = Integer.parseInt(windowMatcher.group(4));
		}

		// OVERLAY
		if (ddsLine.contains("OVERLAY")) {
			record.overlay = true;
		}

		// Subfile control keywords
		if (record instanceof SubfileCtlDef) {
			final SubfileCtlDef ctl = (SubfileCtlDef) record;
			final Matcher sflsizMatcher = Pattern.compile("SFLSIZ\\((\\d+)\\)").matcher(ddsLine);
			if (sflsizMatcher.find()) {
				ctl.sflSize = Integer.parseInt(sflsizMatcher.group(1));
			}
			final Matcher sflpagMatcher = Pattern.compile("SFLPAG\\((\\d+)\\)").matcher(ddsLine);
			if (sflpagMatcher.find()) {
				ctl.sflPage = Integer.parseInt(sflpagMatcher.group(1));
			}
			if (ddsLine.contains("SFLDSP")) {
				ctl.hasSflDsp = true;
			}
			if (ddsLine.contains("SFLDSPCTL")) {
				ctl.hasSflDspCtl = true;
			}
			if (ddsLine.contains("SFLCLR")) {
				ctl.hasSflClr = true;
			}
			if (ddsLine.contains("SFLEND")) {
				ctl.hasSflEnd = true;
			}
		}
	}

	private void parseField(final RecordDef record, final String ddsLine) {
		// Field: <indicator?> <name> <length><type> <usage> <row> <col> <keywords...>
		// Example: "            NUMFACT        8Y 0B  7 35"
		// Example: "  40                                  DSPATR(PR)"
		final Matcher fieldMatcher = Pattern.compile(
				"^\\s*(?:N?(\\d{2}))?\\s+(\\w+)\\s+(\\d+)([AYXSB]?)\\s*(\\d*)\\s*([BIOHE])\\s+(\\d+)\\s+(\\d+)"
		).matcher(ddsLine);

		if (fieldMatcher.find()) {
			final FieldDef field = new FieldDef();
			field.conditionIndicator = fieldMatcher.group(1) != null ? Integer.parseInt(fieldMatcher.group(1)) : 0;
			field.negateIndicator = ddsLine.trim().startsWith("N");
			field.name = fieldMatcher.group(2);
			field.length = Integer.parseInt(fieldMatcher.group(3));
			field.ddsType = fieldMatcher.group(4).isEmpty() ? "A" : fieldMatcher.group(4);
			field.decimals = fieldMatcher.group(5).isEmpty() ? 0 : Integer.parseInt(fieldMatcher.group(5));
			field.usage = fieldMatcher.group(6).charAt(0);
			field.row = Integer.parseInt(fieldMatcher.group(7));
			field.col = Integer.parseInt(fieldMatcher.group(8));

			// Parse field keywords
			parseFieldKeywords(field, ddsLine);

			record.fields.add(field);
		}
	}

	private void parseFieldKeywords(final FieldDef field, final String ddsLine) {
		// DSPATR
		final Matcher dspatrMatcher = Pattern.compile("DSPATR\\(([^)]+)\\)").matcher(ddsLine);
		while (dspatrMatcher.find()) {
			final String attr = dspatrMatcher.group(1);
			if (attr.contains("PR")) {
				field.protect = true;
			}
			if (attr.contains("UL")) {
				field.underline = true;
			}
			if (attr.contains("RI")) {
				field.reverseImage = true;
			}
			if (attr.contains("PC")) {
				field.positionCursor = true;
			}
		}

		// EDTCDE
		final Matcher edtcdeMatcher = Pattern.compile("EDTCDE\\(([^)]+)\\)").matcher(ddsLine);
		if (edtcdeMatcher.find()) {
			field.editCode = edtcdeMatcher.group(1);
		}

		// COLOR
		final Matcher colorMatcher = Pattern.compile("COLOR\\(([^)]+)\\)").matcher(ddsLine);
		if (colorMatcher.find()) {
			field.color = colorMatcher.group(1);
		}

		// CHECK
		final Matcher checkMatcher = Pattern.compile("CHECK\\(([^)]+)\\)").matcher(ddsLine);
		if (checkMatcher.find()) {
			field.check = checkMatcher.group(1);
		}

		// VALUES
		final Matcher valuesMatcher = Pattern.compile("VALUES\\(([^)]+)\\)").matcher(ddsLine);
		if (valuesMatcher.find()) {
			field.values = valuesMatcher.group(1);
		}
	}

	private void parseLiteral(final RecordDef record, final String ddsLine) {
		// Literal: <row> <col>'<text>'
		final Matcher litMatcher = Pattern.compile("(\\d+)\\s+(\\d+)'([^']*)'").matcher(ddsLine);
		if (litMatcher.find()) {
			final LiteralDef lit = new LiteralDef();
			lit.row = Integer.parseInt(litMatcher.group(1));
			lit.col = Integer.parseInt(litMatcher.group(2));
			lit.text = litMatcher.group(3);

			// Check for conditioning indicator
			final Matcher indMatcher = Pattern.compile("^\\s*(\\d{2})\\s+").matcher(ddsLine);
			if (indMatcher.find()) {
				lit.conditionIndicator = Integer.parseInt(indMatcher.group(1));
			}

			final Matcher colorMatcher = Pattern.compile("COLOR\\(([^)]+)\\)").matcher(ddsLine);
			if (colorMatcher.find()) {
				lit.color = colorMatcher.group(1);
			}

			record.literals.add(lit);
		}
	}

	// ---- Vaadin Code Generator ----

	void generateView(final DspfDefinition dspf) throws IOException {
		final String className = toClassName(dspf.fileName) + "View";
		final StringBuilder sb = new StringBuilder();

		// Package and imports
		sb.append("package ").append(PACKAGE_NAME).append(";").append(NL).append(NL);
		appendImports(sb, dspf);
		sb.append(NL);

		// Class
		sb.append("/**").append(NL);
		sb.append(" * Vaadin view generated from DDS DSPF: ").append(dspf.fileName).append(NL);
		sb.append(" * Records: ").append(dspf.records.size())
				.append(", Subfiles: ").append(dspf.subfiles.size()).append(NL);
		sb.append(" */").append(NL);
		sb.append("@Route(\"").append(dspf.fileName.toLowerCase()).append("\")").append(NL);
		sb.append("public class ").append(className)
				.append(" extends VerticalLayout implements HasDynamicTitle {").append(NL).append(NL);

		// Indicator array
		sb.append("\tprivate final boolean[] indicators = new boolean[100];").append(NL).append(NL);

		// Generate fields for all records
		generateFieldDeclarations(sb, dspf);
		sb.append(NL);

		// Generate subfile grids
		generateGridDeclarations(sb, dspf);
		sb.append(NL);

		// Constructor
		sb.append("\tpublic ").append(className).append("() {").append(NL);
		sb.append("\t\tsetSizeFull();").append(NL);
		sb.append("\t\tsetPadding(true);").append(NL);
		sb.append("\t\tsetSpacing(true);").append(NL);
		sb.append(NL);

		// Build each record as a section
		for (final Map.Entry<String, RecordDef> entry : dspf.records.entrySet()) {
			generateRecordSection(sb, entry.getValue(), dspf);
		}

		// Build subfile controls
		for (final Map.Entry<String, SubfileCtlDef> entry : dspf.subfileControls.entrySet()) {
			final SubfileCtlDef ctl = entry.getValue();
			final SubfileDef sfl = dspf.subfiles.get(ctl.subfileName);
			if (sfl != null) {
				generateSubfileSection(sb, ctl, sfl, dspf);
			}
		}

		// Function key toolbar
		if (!dspf.functionKeys.isEmpty()) {
			generateFunctionKeyToolbar(sb, dspf);
		}

		sb.append("\t}").append(NL).append(NL);

		// getPageTitle
		sb.append("\t@Override").append(NL);
		sb.append("\tpublic String getPageTitle() {").append(NL);
		sb.append("\t\treturn \"").append(dspf.fileName).append("\";").append(NL);
		sb.append("\t}").append(NL).append(NL);

		// Indicator methods
		sb.append("\tpublic void setIndicator(final int index, final boolean value) {").append(NL);
		sb.append("\t\tif (index >= 1 && index <= 99) {").append(NL);
		sb.append("\t\t\tindicators[index] = value;").append(NL);
		sb.append("\t\t}").append(NL);
		sb.append("\t}").append(NL).append(NL);

		sb.append("\tpublic boolean getIndicator(final int index) {").append(NL);
		sb.append("\t\treturn index >= 1 && index <= 99 && indicators[index];").append(NL);
		sb.append("\t}").append(NL).append(NL);

		// updateVisibility method
		generateUpdateVisibility(sb, dspf);

		// Getter/setter stubs for fields
		generateFieldAccessors(sb, dspf);

		sb.append("}").append(NL);

		// Write file
		final Path outputFile = outputDir.resolve(className + ".java");
		Files.createDirectories(outputFile.getParent());
		Files.writeString(outputFile, sb.toString());
	}

	private void appendImports(final StringBuilder sb, final DspfDefinition dspf) {
		sb.append("import com.vaadin.flow.component.button.Button;").append(NL);
		sb.append("import com.vaadin.flow.component.button.ButtonVariant;").append(NL);
		sb.append("import com.vaadin.flow.component.formlayout.FormLayout;").append(NL);
		if (!dspf.subfiles.isEmpty()) {
			sb.append("import com.vaadin.flow.component.grid.Grid;").append(NL);
		}
		sb.append("import com.vaadin.flow.component.html.H3;").append(NL);
		sb.append("import com.vaadin.flow.component.html.Hr;").append(NL);
		sb.append("import com.vaadin.flow.component.html.Span;").append(NL);
		sb.append("import com.vaadin.flow.component.notification.Notification;").append(NL);
		sb.append("import com.vaadin.flow.component.orderedlayout.HorizontalLayout;").append(NL);
		sb.append("import com.vaadin.flow.component.orderedlayout.VerticalLayout;").append(NL);
		sb.append("import com.vaadin.flow.component.textfield.NumberField;").append(NL);
		sb.append("import com.vaadin.flow.component.textfield.TextField;").append(NL);
		sb.append("import com.vaadin.flow.router.HasDynamicTitle;").append(NL);
		sb.append("import com.vaadin.flow.router.Route;").append(NL);

		boolean hasWindow = false;
		for (final RecordDef rec : dspf.records.values()) {
			if (rec.isWindow) {
				hasWindow = true;
				break;
			}
		}
		for (final SubfileCtlDef ctl : dspf.subfileControls.values()) {
			if (ctl.isWindow) {
				hasWindow = true;
				break;
			}
		}
		if (hasWindow) {
			sb.append("import com.vaadin.flow.component.dialog.Dialog;").append(NL);
		}

		if (!dspf.subfiles.isEmpty()) {
			sb.append(NL);
			sb.append("import java.util.ArrayList;").append(NL);
			sb.append("import java.util.List;").append(NL);
		}
	}

	private void generateFieldDeclarations(final StringBuilder sb, final DspfDefinition dspf) {
		for (final RecordDef record : dspf.records.values()) {
			sb.append("\t// ---- Record: ").append(record.name).append(" ----").append(NL);
			for (final FieldDef field : record.fields) {
				final String fieldVar = toFieldVarName(record.name, field.name);
				if (isNumericField(field)) {
					sb.append("\tprivate final NumberField ").append(fieldVar)
							.append(" = new NumberField(\"").append(field.name).append("\");").append(NL);
				} else {
					sb.append("\tprivate final TextField ").append(fieldVar)
							.append(" = new TextField(\"").append(field.name).append("\");").append(NL);
				}
			}
		}
	}

	private void generateGridDeclarations(final StringBuilder sb, final DspfDefinition dspf) {
		for (final SubfileDef sfl : dspf.subfiles.values()) {
			if (sfl.fields.isEmpty()) {
				continue;
			}
			final String gridVar = "grid" + sfl.name;
			// Use String[] as default bean type — real app would use a proper bean
			sb.append("\t// Subfile: ").append(sfl.name).append(NL);
			sb.append("\tprivate final Grid<").append(toClassName(sfl.name)).append("Row> ")
					.append(gridVar).append(" = new Grid<>();").append(NL);
			sb.append("\tprivate final List<").append(toClassName(sfl.name)).append("Row> ")
					.append(gridVar).append("Items = new ArrayList<>();").append(NL);
		}
	}

	private void generateRecordSection(final StringBuilder sb, final RecordDef record,
			final DspfDefinition dspf) {
		sb.append(NL);
		sb.append("\t\t// ---- Record: ").append(record.name).append(" ----").append(NL);

		if (record.isWindow) {
			sb.append("\t\t// Window record → Dialog").append(NL);
			sb.append("\t\t// Dialog ").append(record.name).append(" = new Dialog();").append(NL);
			sb.append("\t\t// ").append(record.name).append(".setWidth(\"")
					.append(record.windowWidth * 10).append("px\");").append(NL);
			sb.append("\t\t// ").append(record.name).append(".setHeight(\"")
					.append(record.windowHeight * 24).append("px\");").append(NL);
		}

		// Title from literals on first rows
		final String title = findTitleLiteral(record);
		if (title != null) {
			sb.append("\t\tadd(new H3(\"").append(escapeJava(title)).append("\"));").append(NL);
		}

		// Form layout for fields
		if (!record.fields.isEmpty()) {
			final String formVar = "form" + record.name;
			sb.append("\t\tfinal FormLayout ").append(formVar).append(" = new FormLayout();").append(NL);
			sb.append("\t\t").append(formVar).append(".setResponsiveSteps(").append(NL);
			sb.append("\t\t\tnew FormLayout.ResponsiveStep(\"0\", 1),").append(NL);
			sb.append("\t\t\tnew FormLayout.ResponsiveStep(\"600px\", 2),").append(NL);
			sb.append("\t\t\tnew FormLayout.ResponsiveStep(\"900px\", 3)").append(NL);
			sb.append("\t\t);").append(NL);

			for (final FieldDef field : record.fields) {
				final String fieldVar = toFieldVarName(record.name, field.name);

				// Set read-only for output-only or protected fields
				if (field.usage == 'O' || field.protect) {
					sb.append("\t\t").append(fieldVar).append(".setReadOnly(true);").append(NL);
				}

				// Set max length for text fields
				if (!isNumericField(field) && field.length > 0) {
					sb.append("\t\t").append(fieldVar).append(".setMaxLength(")
							.append(field.length).append(");").append(NL);
				}

				// Find the label from preceding literals
				final String label = findLabelForField(record, field);
				if (label != null) {
					sb.append("\t\t").append(fieldVar).append(".setLabel(\"")
							.append(escapeJava(label)).append("\");").append(NL);
				}

				sb.append("\t\t").append(formVar).append(".add(").append(fieldVar).append(");").append(NL);
			}

			sb.append("\t\tadd(").append(formVar).append(");").append(NL);
		}

		// Message field (commonly indicator 80)
		for (final FieldDef field : record.fields) {
			if (field.conditionIndicator == 80 || "MSG_ERRO".equals(field.name) || field.name.contains("MSG")) {
				final String fieldVar = toFieldVarName(record.name, field.name);
				sb.append("\t\t").append(fieldVar)
						.append(".addClassName(\"error-message\");").append(NL);
				sb.append("\t\t").append(fieldVar).append(".setVisible(false);").append(NL);
			}
		}

		sb.append("\t\tadd(new Hr());").append(NL);
	}

	private void generateSubfileSection(final StringBuilder sb, final SubfileCtlDef ctl,
			final SubfileDef sfl, final DspfDefinition dspf) {
		sb.append(NL);
		sb.append("\t\t// ---- Subfile: ").append(ctl.name).append(" → ")
				.append(ctl.subfileName).append(" ----").append(NL);

		final String gridVar = "grid" + sfl.name;
		final String rowClass = toClassName(sfl.name) + "Row";

		// Add columns for each field in the SFL
		for (final FieldDef field : sfl.fields) {
			final String fieldName = toJavaFieldName(field.name);
			sb.append("\t\t").append(gridVar).append(".addColumn(")
					.append(rowClass).append("::get")
					.append(Character.toUpperCase(fieldName.charAt(0)))
					.append(fieldName.substring(1))
					.append(").setHeader(\"").append(field.name).append("\")");

			if (field.usage == 'I') {
				// Input columns get editors — noted as comment
				sb.append("; // editable");
			} else {
				sb.append(";");
			}
			sb.append(NL);
		}

		// Page size
		if (ctl.sflPage > 0) {
			sb.append("\t\t").append(gridVar).append(".setPageSize(")
					.append(ctl.sflPage).append(");").append(NL);
		}

		sb.append("\t\t").append(gridVar).append(".setItems(")
				.append(gridVar).append("Items);").append(NL);

		if (ctl.isWindow) {
			sb.append("\t\t// Subfile in window → add to Dialog").append(NL);
		}

		sb.append("\t\tadd(").append(gridVar).append(");").append(NL);

		// Control record labels
		final String ctlTitle = findTitleLiteral(ctl);
		if (ctlTitle != null) {
			sb.append("\t\t// Subfile header: ").append(escapeJava(ctlTitle)).append(NL);
		}
	}

	private void generateFunctionKeyToolbar(final StringBuilder sb, final DspfDefinition dspf) {
		sb.append(NL);
		sb.append("\t\t// ---- Function Keys ----").append(NL);
		sb.append("\t\tfinal HorizontalLayout toolbar = new HorizontalLayout();").append(NL);
		sb.append("\t\ttoolbar.setWidthFull();").append(NL);
		sb.append("\t\ttoolbar.setJustifyContentMode(JustifyContentMode.END);").append(NL);
		sb.append("\t\ttoolbar.setSpacing(true);").append(NL);

		for (final FunctionKeyDef fk : dspf.functionKeys) {
			final String btnVar = "btnF" + fk.fkeyNumber;
			final String label = fk.label.isEmpty()
					? "F" + fk.fkeyNumber
					: "F" + fk.fkeyNumber + " " + fk.label;

			sb.append("\t\tfinal Button ").append(btnVar)
					.append(" = new Button(\"").append(escapeJava(label)).append("\");").append(NL);

			// Style: F3/F12 get tertiary, F10 gets primary
			if (fk.fkeyNumber == 3 || fk.fkeyNumber == 12) {
				sb.append("\t\t").append(btnVar)
						.append(".addThemeVariants(ButtonVariant.LUMO_TERTIARY);").append(NL);
			} else if (fk.fkeyNumber == 10) {
				sb.append("\t\t").append(btnVar)
						.append(".addThemeVariants(ButtonVariant.LUMO_PRIMARY);").append(NL);
			}

			sb.append("\t\t").append(btnVar).append(".addClickListener(e -> {").append(NL);
			sb.append("\t\t\tsetIndicator(").append(fk.responseIndicator).append(", true);").append(NL);
			sb.append("\t\t\tonFunctionKey(").append(fk.fkeyNumber).append(");").append(NL);
			sb.append("\t\t});").append(NL);

			sb.append("\t\ttoolbar.add(").append(btnVar).append(");").append(NL);
		}

		sb.append("\t\tadd(toolbar);").append(NL);
	}

	private void generateUpdateVisibility(final StringBuilder sb, final DspfDefinition dspf) {
		sb.append("\t/**").append(NL);
		sb.append("\t * Updates field visibility and enabled state based on indicator values.").append(NL);
		sb.append("\t * Call after changing indicators to refresh the UI state.").append(NL);
		sb.append("\t */").append(NL);
		sb.append("\tpublic void updateVisibility() {").append(NL);

		for (final RecordDef record : dspf.records.values()) {
			for (final FieldDef field : record.fields) {
				if (field.conditionIndicator > 0) {
					final String fieldVar = toFieldVarName(record.name, field.name);
					if (field.protect) {
						sb.append("\t\t").append(fieldVar)
								.append(".setReadOnly(").append(field.negateIndicator ? "!" : "")
								.append("getIndicator(").append(field.conditionIndicator).append("));")
								.append(NL);
					} else {
						sb.append("\t\t").append(fieldVar)
								.append(".setVisible(").append(field.negateIndicator ? "!" : "")
								.append("getIndicator(").append(field.conditionIndicator).append("));")
								.append(NL);
					}
				}
			}
		}

		sb.append("\t}").append(NL).append(NL);

		// Stub for function key handler
		sb.append("\tprotected void onFunctionKey(final int fkeyNumber) {").append(NL);
		sb.append("\t\tNotification.show(\"Function key F\" + fkeyNumber + \" pressed\");").append(NL);
		sb.append("\t}").append(NL).append(NL);
	}

	private void generateFieldAccessors(final StringBuilder sb, final DspfDefinition dspf) {
		// Generate inner Row classes for subfiles
		for (final SubfileDef sfl : dspf.subfiles.values()) {
			if (sfl.fields.isEmpty()) {
				continue;
			}
			final String rowClass = toClassName(sfl.name) + "Row";
			sb.append("\t/**").append(NL);
			sb.append("\t * Row bean for subfile ").append(sfl.name).append(".").append(NL);
			sb.append("\t */").append(NL);
			sb.append("\tpublic static class ").append(rowClass).append(" {").append(NL);

			for (final FieldDef field : sfl.fields) {
				final String fieldName = toJavaFieldName(field.name);
				final String javaType = isNumericField(field) ? "Double" : "String";
				sb.append("\t\tprivate ").append(javaType).append(" ").append(fieldName).append(";").append(NL);
			}
			sb.append(NL);

			for (final FieldDef field : sfl.fields) {
				final String fieldName = toJavaFieldName(field.name);
				final String javaType = isNumericField(field) ? "Double" : "String";
				final String cap = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);

				sb.append("\t\tpublic ").append(javaType).append(" get").append(cap)
						.append("() { return ").append(fieldName).append("; }").append(NL);
				sb.append("\t\tpublic void set").append(cap).append("(final ")
						.append(javaType).append(" ").append(fieldName)
						.append(") { this.").append(fieldName).append(" = ").append(fieldName)
						.append("; }").append(NL);
			}

			sb.append("\t}").append(NL).append(NL);
		}
	}

	// ---- Utilities ----

	private String findTitleLiteral(final RecordDef record) {
		// Find the first literal in rows 1-5 that looks like a title (centered, white)
		for (final LiteralDef lit : record.literals) {
			if (lit.row <= 5 && lit.text.length() > 3 && !lit.text.startsWith("F")) {
				return lit.text.trim();
			}
		}
		return null;
	}

	private String findLabelForField(final RecordDef record, final FieldDef field) {
		// Find a literal on the same row that precedes the field
		for (final LiteralDef lit : record.literals) {
			if (lit.row == field.row && lit.col < field.col) {
				return lit.text.replaceAll("[.]+:?$", "").trim();
			}
		}
		return null;
	}

	private boolean isNumericField(final FieldDef field) {
		return "Y".equals(field.ddsType) || "S".equals(field.ddsType)
				|| "B".equals(field.ddsType);
	}

	static String toClassName(final String name) {
		final StringBuilder sb = new StringBuilder();
		boolean capitalize = true;
		for (final char c : name.toCharArray()) {
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

	private String toFieldVarName(final String recordName, final String fieldName) {
		return toJavaFieldName(fieldName) + "Of" + recordName;
	}

	static String toJavaFieldName(final String ddsName) {
		final StringBuilder sb = new StringBuilder();
		boolean capitalize = false;
		boolean first = true;
		for (final char c : ddsName.toCharArray()) {
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
		final String result = sb.toString();
		// Avoid Java reserved words
		if ("class".equals(result) || "new".equals(result) || "return".equals(result)) {
			return result + "Field";
		}
		return result;
	}

	private String escapeJava(final String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	// ---- Model Classes ----

	static class DspfDefinition {
		String fileName;
		int rows = 24;
		int cols = 80;
		boolean indara;
		final List<FunctionKeyDef> functionKeys = new ArrayList<>();
		final Map<String, RecordDef> records = new LinkedHashMap<>();
		final Map<String, SubfileDef> subfiles = new LinkedHashMap<>();
		final Map<String, SubfileCtlDef> subfileControls = new LinkedHashMap<>();
	}

	static class RecordDef {
		String name;
		boolean overlay;
		boolean isWindow;
		int windowRow;
		int windowCol;
		int windowHeight;
		int windowWidth;
		final List<FieldDef> fields = new ArrayList<>();
		final List<LiteralDef> literals = new ArrayList<>();
	}

	static class SubfileDef extends RecordDef {
	}

	static class SubfileCtlDef extends RecordDef {
		String subfileName;
		int sflSize;
		int sflPage;
		boolean hasSflDsp;
		boolean hasSflDspCtl;
		boolean hasSflClr;
		boolean hasSflEnd;
	}

	static class FieldDef {
		String name;
		int length;
		String ddsType = "A"; // A=alpha, Y=numeric, X=alphanumeric, S=signed, B=binary
		int decimals;
		char usage; // B=both, O=output, I=input, H=hidden, E=edit
		int row;
		int col;
		int conditionIndicator;
		boolean negateIndicator;
		boolean protect;
		boolean underline;
		boolean reverseImage;
		boolean positionCursor;
		String editCode;
		String color;
		String check;
		String values;
	}

	static class LiteralDef {
		int row;
		int col;
		String text;
		int conditionIndicator;
		String color;
	}

	static class FunctionKeyDef {
		String type; // CA or CF
		int fkeyNumber;
		int responseIndicator;
		String label;
		int conditionIndicator;
	}
}
