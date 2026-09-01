package io.proleap.cobol.transform.java.rules.lang.procedure.read;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.ReadStatementContext;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.call.FileControlEntryCall;
import io.proleap.cobol.asg.metamodel.environment.inputoutput.filecontrol.FileControlEntry;
import io.proleap.cobol.asg.metamodel.environment.inputoutput.filecontrol.FileStatusClause;
import io.proleap.cobol.asg.metamodel.procedure.AtEndPhrase;
import io.proleap.cobol.asg.metamodel.procedure.InvalidKeyPhrase;
import io.proleap.cobol.asg.metamodel.procedure.NotAtEndPhrase;
import io.proleap.cobol.asg.metamodel.procedure.NotInvalidKeyPhrase;
import io.proleap.cobol.asg.metamodel.procedure.Statement;
import io.proleap.cobol.asg.metamodel.procedure.read.Into;
import io.proleap.cobol.asg.metamodel.procedure.read.ReadStatement;
import io.proleap.cobol.asg.metamodel.valuestmt.ValueStmt;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.transform.java.identifier.variable.JavaFileControlEntryIdentifierService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class ReadStatementRule extends CobolTransformRule<ReadStatementContext, ReadStatement> {

	@Inject
	private CobolTypeService cobolTypeService;

	@Inject
	private JavaFileControlEntryIdentifierService javaFileControlEntryIdentifierService;

	@Override
	public void apply(final ReadStatementContext ctx, final ReadStatement readStatement, final RuleContext rc) {
		final boolean isSubfile = readStatement.isSubfile();
		final ValueStmt formatPhrase = readStatement.getFormatPhrase();
		final Call indicatorsPhrase = readStatement.getIndicatorsPhrase();

		if (isSubfile) {
			applySubfile(readStatement, formatPhrase, indicatorsPhrase, rc);
		} else if (formatPhrase != null || indicatorsPhrase != null) {
			applyWithFormat(readStatement, formatPhrase, indicatorsPhrase, rc);
		} else {
			applyStandard(readStatement, rc);
		}
	}

	private void applyStandard(final ReadStatement readStatement, final RuleContext rc) {
		final Into into = readStatement.getInto();
		final AtEndPhrase atEndPhrase = readStatement.getAtEnd();
		final NotAtEndPhrase notAtEndPhrase = readStatement.getNotAtEndPhrase();
		final InvalidKeyPhrase invalidKeyPhrase = readStatement.getInvalidKeyPhrase();
		final NotInvalidKeyPhrase notInvalidKeyPhrase = readStatement.getNotInvalidKeyPhrase();

		// Generate the read call
		if (into == null) {
			rc.p("fileControlService.read(");
			emitFileReference(readStatement.getFileCall(), rc);
			rc.p(");");
		} else {
			rc.p("fileControlService.read(");
			emitFileReference(readStatement.getFileCall(), rc);
			rc.p(", ");
			rc.visit(into.getIntoCall().getCtx());
			rc.p(");");
		}
		rc.pNl(readStatement);

		// Update FILE STATUS variable if defined
		emitFileStatusUpdate(readStatement.getFileCall(), rc);

		// INVALID KEY / NOT INVALID KEY handling
		emitInvalidKeyHandling(readStatement, invalidKeyPhrase, notInvalidKeyPhrase, rc);

		// Generate AT END / NOT AT END handling
		if (atEndPhrase != null || notAtEndPhrase != null) {
			rc.p("if (fileControlService.isEndOfFile(");
			emitFileReference(readStatement.getFileCall(), rc);
			rc.p(")) {");
			rc.pNl();

			if (atEndPhrase != null) {
				rc.getPrinter().indent();
				for (final Statement statement : atEndPhrase.getStatements()) {
					rc.visit(statement.getCtx());
				}
				rc.getPrinter().unindent();
			}

			if (notAtEndPhrase != null) {
				rc.p("} else {");
				rc.pNl();
				rc.getPrinter().indent();
				for (final Statement statement : notAtEndPhrase.getStatements()) {
					rc.visit(statement.getCtx());
				}
				rc.getPrinter().unindent();
			}

			rc.p("}");
			rc.pNl();
		}
	}

	private void applySubfile(final ReadStatement readStatement, final ValueStmt formatPhrase,
			final Call indicatorsPhrase, final RuleContext rc) {
		final Into into = readStatement.getInto();
		final AtEndPhrase atEndPhrase = readStatement.getAtEnd();
		final NotAtEndPhrase notAtEndPhrase = readStatement.getNotAtEndPhrase();
		final InvalidKeyPhrase invalidKeyPhrase = readStatement.getInvalidKeyPhrase();
		final NotInvalidKeyPhrase notInvalidKeyPhrase = readStatement.getNotInvalidKeyPhrase();

		rc.p("fileControlService.readSubfile(");
		emitFileReference(readStatement.getFileCall(), rc);
		if (into != null && into.getIntoCall() != null && into.getIntoCall().getCtx() != null) {
			rc.p(", ");
			rc.visit(into.getIntoCall().getCtx());
		}
		rc.p(", ");
		printValueStmtOrNull(formatPhrase, rc);
		rc.p(", ");
		printCallOrNull(indicatorsPhrase, rc);
		rc.p(");");
		rc.pNl(readStatement);

		// INVALID KEY / NOT INVALID KEY handling
		emitInvalidKeyHandling(readStatement, invalidKeyPhrase, notInvalidKeyPhrase, rc);

		if (atEndPhrase != null || notAtEndPhrase != null) {
			rc.p("if (fileControlService.isEndOfFile(");
			emitFileReference(readStatement.getFileCall(), rc);
			rc.p(")) {");
			rc.pNl(readStatement);
			if (atEndPhrase != null) {
				for (final Statement stmt : atEndPhrase.getStatements()) {
					rc.visit(stmt.getCtx());
				}
			}
			if (notAtEndPhrase != null) {
				rc.p("} else {");
				rc.pNl(readStatement);
				for (final Statement stmt : notAtEndPhrase.getStatements()) {
					rc.visit(stmt.getCtx());
				}
			}
			rc.p("}");
			rc.pNl(readStatement);
		}
	}

	private void applyWithFormat(final ReadStatement readStatement, final ValueStmt formatPhrase,
			final Call indicatorsPhrase, final RuleContext rc) {
		final Into into = readStatement.getInto();
		final AtEndPhrase atEndPhrase = readStatement.getAtEnd();
		final NotAtEndPhrase notAtEndPhrase = readStatement.getNotAtEndPhrase();
		final InvalidKeyPhrase invalidKeyPhrase = readStatement.getInvalidKeyPhrase();
		final NotInvalidKeyPhrase notInvalidKeyPhrase = readStatement.getNotInvalidKeyPhrase();

		rc.p("fileControlService.read(");
		emitFileReference(readStatement.getFileCall(), rc);
		if (into != null && into.getIntoCall() != null && into.getIntoCall().getCtx() != null) {
			rc.p(", ");
			rc.visit(into.getIntoCall().getCtx());
		}
		rc.p(", ");
		printValueStmtOrNull(formatPhrase, rc);
		rc.p(", ");
		printCallOrNull(indicatorsPhrase, rc);
		rc.p(");");
		rc.pNl(readStatement);

		// INVALID KEY / NOT INVALID KEY handling
		emitInvalidKeyHandling(readStatement, invalidKeyPhrase, notInvalidKeyPhrase, rc);

		// Generate AT END / NOT AT END handling
		if (atEndPhrase != null || notAtEndPhrase != null) {
			rc.p("if (fileControlService.isEndOfFile(");
			emitFileReference(readStatement.getFileCall(), rc);
			rc.p(")) {");
			rc.pNl();

			if (atEndPhrase != null) {
				rc.getPrinter().indent();
				for (final Statement statement : atEndPhrase.getStatements()) {
					rc.visit(statement.getCtx());
				}
				rc.getPrinter().unindent();
			}

			if (notAtEndPhrase != null) {
				rc.p("} else {");
				rc.pNl();
				rc.getPrinter().indent();
				for (final Statement statement : notAtEndPhrase.getStatements()) {
					rc.visit(statement.getCtx());
				}
				rc.getPrinter().unindent();
			}

			rc.p("}");
			rc.pNl();
		}
	}

	@Override
	public Class<ReadStatementContext> from() {
		return ReadStatementContext.class;
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
	 * Emits INVALID KEY / NOT INVALID KEY conditional blocks.
	 * Generates: if (fileControlService.isInvalidKey(file)) { ... } else { ... }
	 */
	private void emitInvalidKeyHandling(final ReadStatement readStatement,
			final InvalidKeyPhrase invalidKeyPhrase,
			final NotInvalidKeyPhrase notInvalidKeyPhrase, final RuleContext rc) {
		if (invalidKeyPhrase == null && notInvalidKeyPhrase == null) {
			return;
		}

		rc.p("if (fileControlService.isInvalidKey(");
		emitFileReference(readStatement.getFileCall(), rc);
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

	/**
	 * Emits the file reference for a Call. If the call is a FileControlEntryCall,
	 * emits the FileControlEntry identifier directly (avoiding name collisions with
	 * data fields that have the same name). Otherwise falls back to visiting the context.
	 */
	private void emitFileReference(final Call call, final RuleContext rc) {
		if (call instanceof FileControlEntryCall) {
			final FileControlEntry fce = ((FileControlEntryCall) call).getFileControlEntry();
			rc.p(javaFileControlEntryIdentifierService.mapToIdentifier(fce));
		} else {
			rc.visit(call.getCtx());
		}
	}

	/**
	 * Emits an assignment to update the FILE STATUS variable after an I/O operation,
	 * if the file's SELECT clause declared a FILE STATUS IS clause.
	 */
	private void emitFileStatusUpdate(final Call fileCall, final RuleContext rc) {
		if (fileCall instanceof FileControlEntryCall) {
			final FileControlEntry fce = ((FileControlEntryCall) fileCall).getFileControlEntry();
			if (fce != null) {
				final FileStatusClause fsc = fce.getFileStatusClause();
				if (fsc != null && fsc.getDataCall() != null && fsc.getDataCall().getCtx() != null) {
					final CobolTypeEnum statusType = cobolTypeService.getType(fsc.getDataCall());
					rc.visit(fsc.getDataCall().getCtx());
					rc.p(" = ");
					if (statusType == CobolTypeEnum.INTEGER) {
						rc.p("new BigDecimal(");
						emitFileReference(fileCall, rc);
						rc.p(".getFileStatus())");
					} else {
						emitFileReference(fileCall, rc);
						rc.p(".getFileStatus()");
					}
					rc.p(";");
					rc.pNl();
				}
			}
		}
	}
}
