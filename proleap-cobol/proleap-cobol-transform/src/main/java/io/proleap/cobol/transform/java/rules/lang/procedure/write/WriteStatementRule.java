package io.proleap.cobol.transform.java.rules.lang.procedure.write;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.WriteStatementContext;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.file.FileDescriptionEntry;
import io.proleap.cobol.asg.metamodel.environment.inputoutput.filecontrol.FileControlEntry;
import io.proleap.cobol.asg.metamodel.environment.inputoutput.filecontrol.FileStatusClause;
import io.proleap.cobol.asg.metamodel.procedure.InvalidKeyPhrase;
import io.proleap.cobol.asg.metamodel.procedure.NotInvalidKeyPhrase;
import io.proleap.cobol.asg.metamodel.procedure.Statement;
import io.proleap.cobol.asg.metamodel.procedure.write.AdvancingLines;
import io.proleap.cobol.asg.metamodel.procedure.write.AdvancingPhrase;
import io.proleap.cobol.asg.metamodel.procedure.write.From;
import io.proleap.cobol.asg.metamodel.procedure.write.WriteStatement;
import io.proleap.cobol.asg.metamodel.valuestmt.ValueStmt;
import io.proleap.cobol.asg.util.ANTLRUtils;
import io.proleap.cobol.commons.datadescription.CobolDataDescriptionEntryService;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.transform.java.identifier.variable.JavaFileControlEntryIdentifierService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class WriteStatementRule extends CobolTransformRule<WriteStatementContext, WriteStatement> {

	@Inject
	private CobolDataDescriptionEntryService cobolDataDescriptionEntryService;

	@Inject
	private CobolTypeService cobolTypeService;

	@Inject
	private JavaFileControlEntryIdentifierService javaFileControlEntryIdentifierService;

	@Override
	public void apply(final WriteStatementContext ctx, final WriteStatement writeStatement, final RuleContext rc) {
		final boolean isSubfile = writeStatement.isSubfile();
		final ValueStmt formatPhrase = writeStatement.getFormatPhrase();
		final Call indicatorsPhrase = writeStatement.getIndicatorsPhrase();

		if (isSubfile) {
			applySubfile(writeStatement, formatPhrase, indicatorsPhrase, rc);
		} else if (formatPhrase != null || indicatorsPhrase != null) {
			applyWithFormat(writeStatement, formatPhrase, indicatorsPhrase, rc);
		} else {
			applyStandard(writeStatement, rc);
		}
	}

	private void applyStandard(final WriteStatement writeStatement, final RuleContext rc) {
		final From from = writeStatement.getFrom();
		final AdvancingPhrase advancingPhrase = writeStatement.getAdvancingPhrase();

		if (advancingPhrase != null) {
			applyAdvancing(writeStatement, from, advancingPhrase, rc);
		} else if (from != null && from.getFromValueStmt() != null && from.getFromValueStmt().getCtx() != null) {
			// WRITE record FROM data-source
			rc.p("fileControlService.write(");
			printFileIdentifier(writeStatement, rc);
			rc.p(", ");
			rc.visit(from.getFromValueStmt().getCtx());
			rc.p(");");
		} else {
			rc.p("fileControlService.write(");
			printFileIdentifier(writeStatement, rc);
			rc.p(");");
		}
		rc.pNl(writeStatement);
		emitFileStatusUpdate(writeStatement, rc);

		// INVALID KEY / NOT INVALID KEY handling
		emitInvalidKeyHandling(writeStatement, rc);
	}

	private void applyAdvancing(final WriteStatement writeStatement, final From from,
			final AdvancingPhrase advancingPhrase, final RuleContext rc) {
		// Determine advanceType string: "AFTER_PAGE", "AFTER_LINES", "BEFORE_PAGE", "BEFORE_LINES"
		final AdvancingPhrase.PositionType positionType = advancingPhrase.getPositionType();
		final AdvancingPhrase.AdvancingType advancingType = advancingPhrase.getAdvancingType();

		final String posPrefix = (positionType == AdvancingPhrase.PositionType.BEFORE) ? "BEFORE" : "AFTER";
		final String typeSuffix;

		if (advancingType == AdvancingPhrase.AdvancingType.PAGE) {
			typeSuffix = "PAGE";
		} else {
			typeSuffix = "LINES";
		}

		final String advanceTypeStr = posPrefix + "_" + typeSuffix;

		// Determine line count
		int lineCount = 0;
		if (advancingType == AdvancingPhrase.AdvancingType.LINES) {
			final AdvancingLines advancingLines = advancingPhrase.getAdvancingLines();
			if (advancingLines != null && advancingLines.getLinesValueStmt() != null) {
				// Try to get the literal value from the context text
				try {
					final String text = advancingLines.getLinesValueStmt().getCtx().getText().trim();
					lineCount = Integer.parseInt(text);
				} catch (final Exception e) {
					lineCount = 1; // default to 1 if we can't parse
				}
			}
		}

		rc.p("fileControlService.writeAdvancing(");
		printFileIdentifier(writeStatement, rc);
		rc.p(", ");

		// FROM argument
		if (from != null && from.getFromValueStmt() != null && from.getFromValueStmt().getCtx() != null) {
			rc.visit(from.getFromValueStmt().getCtx());
		} else {
			rc.p("null");
		}

		rc.p(", \"" + advanceTypeStr + "\", " + lineCount + ");");
	}

	private void applySubfile(final WriteStatement writeStatement, final ValueStmt formatPhrase,
			final Call indicatorsPhrase, final RuleContext rc) {
		final From from = writeStatement.getFrom();
		rc.p("fileControlService.writeSubfile(");
		printFileIdentifier(writeStatement, rc);
		rc.p(", ");
		// Use FROM data source when present, otherwise use the FD record
		if (from != null && from.getFromValueStmt() != null && from.getFromValueStmt().getCtx() != null) {
			rc.visit(from.getFromValueStmt().getCtx());
		} else {
			visitRecordCall(writeStatement, rc);
		}
		rc.p(", ");
		printValueStmtOrNull(formatPhrase, rc);
		rc.p(", ");
		printCallOrNull(indicatorsPhrase, rc);
		rc.p(");");
		rc.pNl(writeStatement);
		emitFileStatusUpdate(writeStatement, rc);

		// INVALID KEY / NOT INVALID KEY handling
		emitInvalidKeyHandling(writeStatement, rc);
	}

	private void applyWithFormat(final WriteStatement writeStatement, final ValueStmt formatPhrase,
			final Call indicatorsPhrase, final RuleContext rc) {
		final From from = writeStatement.getFrom();

		rc.p("fileControlService.write(");
		printFileIdentifier(writeStatement, rc);

		// Include FROM data if present
		if (from != null && from.getFromValueStmt() != null && from.getFromValueStmt().getCtx() != null) {
			rc.p(", ");
			rc.visit(from.getFromValueStmt().getCtx());
		}

		rc.p(", ");
		printValueStmtOrNull(formatPhrase, rc);
		rc.p(", ");
		printCallOrNull(indicatorsPhrase, rc);
		rc.p(");");
		rc.pNl(writeStatement);
		emitFileStatusUpdate(writeStatement, rc);

		// INVALID KEY / NOT INVALID KEY handling
		emitInvalidKeyHandling(writeStatement, rc);
	}

	@Override
	public Class<WriteStatementContext> from() {
		return WriteStatementContext.class;
	}

	private void printFileIdentifier(final WriteStatement writeStatement, final RuleContext rc) {
		final Call recordCall = writeStatement.getRecordCall();
		final DataDescriptionEntry dataDescriptionEntry = cobolDataDescriptionEntryService
				.getDataDescriptionEntry(recordCall);

		if (dataDescriptionEntry == null) {
			visitRecordCall(writeStatement, rc);
		} else {
			final FileDescriptionEntry fileDescriptionEntry = (FileDescriptionEntry) ANTLRUtils.findParent(
					FileDescriptionEntry.class, dataDescriptionEntry.getCtx(),
					writeStatement.getProgram().getASGElementRegistry());

			if (fileDescriptionEntry == null || fileDescriptionEntry.getFileControlEntry() == null) {
				visitRecordCall(writeStatement, rc);
			} else {
				final FileControlEntry fileControlEntry = fileDescriptionEntry.getFileControlEntry();
				final String identifier = javaFileControlEntryIdentifierService.mapToIdentifier(fileControlEntry);
				rc.p(identifier);
			}
		}
	}

	private void visitRecordCall(final WriteStatement writeStatement, final RuleContext rc) {
		rc.visit(writeStatement.getRecordCall().getCtx());
	}

	private void printValueStmtOrNull(final ValueStmt valueStmt, final RuleContext rc) {
		if (valueStmt != null && valueStmt.getCtx() != null) {
			rc.visit(valueStmt.getCtx());
		} else {
			rc.p("null");
		}
	}

	private void printCallOrNull(final Call call, final RuleContext rc) {
		if (call != null && call.getCtx() != null) {
			rc.visit(call.getCtx());
		} else {
			rc.p("null");
		}
	}

	/**
	 * Emits an assignment to update the FILE STATUS variable after a WRITE operation,
	 * if the file's SELECT clause declared a FILE STATUS IS clause.
	 */
	private void emitFileStatusUpdate(final WriteStatement writeStatement, final RuleContext rc) {
		final Call recordCall = writeStatement.getRecordCall();
		final DataDescriptionEntry dataDescriptionEntry = cobolDataDescriptionEntryService
				.getDataDescriptionEntry(recordCall);

		if (dataDescriptionEntry == null) {
			return;
		}

		final FileDescriptionEntry fileDescriptionEntry = (FileDescriptionEntry) ANTLRUtils.findParent(
				FileDescriptionEntry.class, dataDescriptionEntry.getCtx(),
				writeStatement.getProgram().getASGElementRegistry());

		if (fileDescriptionEntry == null || fileDescriptionEntry.getFileControlEntry() == null) {
			return;
		}

		final FileControlEntry fileControlEntry = fileDescriptionEntry.getFileControlEntry();
		final FileStatusClause fsc = fileControlEntry.getFileStatusClause();

		if (fsc != null && fsc.getDataCall() != null && fsc.getDataCall().getCtx() != null) {
			final CobolTypeEnum statusType = cobolTypeService.getType(fsc.getDataCall());
			final String fileIdentifier = javaFileControlEntryIdentifierService.mapToIdentifier(fileControlEntry);
			rc.visit(fsc.getDataCall().getCtx());
			rc.p(" = ");
			if (statusType == CobolTypeEnum.INTEGER) {
				rc.p("new BigDecimal(" + fileIdentifier + ".getFileStatus())");
			} else {
				rc.p(fileIdentifier + ".getFileStatus()");
			}
			rc.p(";");
			rc.pNl();
		}
	}

	/**
	 * Emits INVALID KEY / NOT INVALID KEY conditional blocks.
	 * Generates: if (fileControlService.isInvalidKey(file)) { ... } else { ... }
	 */
	private void emitInvalidKeyHandling(final WriteStatement writeStatement, final RuleContext rc) {
		final InvalidKeyPhrase invalidKeyPhrase = writeStatement.getInvalidKeyPhrase();
		final NotInvalidKeyPhrase notInvalidKeyPhrase = writeStatement.getNotInvalidKeyPhrase();

		if (invalidKeyPhrase == null && notInvalidKeyPhrase == null) {
			return;
		}

		rc.p("if (fileControlService.isInvalidKey(");
		printFileIdentifier(writeStatement, rc);
		rc.p(")) {");
		rc.pNl();

		if (invalidKeyPhrase != null) {
			rc.getPrinter().indent();
			for (final Statement statement : invalidKeyPhrase.getStatements()) {
				rc.visit(statement.getCtx());
			}
			rc.getPrinter().unindent();
		}

		if (notInvalidKeyPhrase != null) {
			rc.p("} else {");
			rc.pNl();
			rc.getPrinter().indent();
			for (final Statement statement : notInvalidKeyPhrase.getStatements()) {
				rc.visit(statement.getCtx());
			}
			rc.getPrinter().unindent();
		}

		rc.p("}");
		rc.pNl();
	}
}
