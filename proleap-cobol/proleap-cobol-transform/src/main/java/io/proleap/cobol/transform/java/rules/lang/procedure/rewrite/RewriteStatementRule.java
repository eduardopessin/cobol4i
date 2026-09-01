package io.proleap.cobol.transform.java.rules.lang.procedure.rewrite;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.RewriteStatementContext;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.file.FileDescriptionEntry;
import io.proleap.cobol.asg.metamodel.environment.inputoutput.filecontrol.FileControlEntry;
import io.proleap.cobol.asg.metamodel.procedure.InvalidKeyPhrase;
import io.proleap.cobol.asg.metamodel.procedure.NotInvalidKeyPhrase;
import io.proleap.cobol.asg.metamodel.procedure.Statement;
import io.proleap.cobol.asg.metamodel.procedure.rewrite.RewriteStatement;
import io.proleap.cobol.asg.metamodel.valuestmt.ValueStmt;
import io.proleap.cobol.asg.util.ANTLRUtils;
import io.proleap.cobol.commons.datadescription.CobolDataDescriptionEntryService;
import io.proleap.cobol.transform.java.identifier.variable.JavaFileControlEntryIdentifierService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class RewriteStatementRule extends CobolTransformRule<RewriteStatementContext, RewriteStatement> {

	@Inject
	private CobolDataDescriptionEntryService cobolDataDescriptionEntryService;

	@Inject
	private JavaFileControlEntryIdentifierService javaFileControlEntryIdentifierService;

	@Override
	public void apply(final RewriteStatementContext ctx, final RewriteStatement rewriteStatement,
			final RuleContext rc) {
		final boolean isSubfile = rewriteStatement.isSubfile();
		final ValueStmt formatPhrase = rewriteStatement.getFormatPhrase();
		final Call indicatorsPhrase = rewriteStatement.getIndicatorsPhrase();

		if (isSubfile) {
			applySubfile(rewriteStatement, formatPhrase, indicatorsPhrase, rc);
		} else if (formatPhrase != null || indicatorsPhrase != null) {
			applyWithFormat(rewriteStatement, formatPhrase, indicatorsPhrase, rc);
		} else {
			applyStandard(rewriteStatement, rc);
		}
	}

	private void applyStandard(final RewriteStatement rewriteStatement, final RuleContext rc) {
		rc.p("fileControlService.rewrite(");
		printFileIdentifier(rewriteStatement, rc);
		rc.p(");");
		rc.pNl(rewriteStatement);

		// INVALID KEY / NOT INVALID KEY handling
		emitInvalidKeyHandling(rewriteStatement, rc);
	}

	private void applySubfile(final RewriteStatement rewriteStatement, final ValueStmt formatPhrase,
			final Call indicatorsPhrase, final RuleContext rc) {
		rc.p("fileControlService.rewriteSubfile(");
		printFileIdentifier(rewriteStatement, rc);
		rc.p(", ");
		visitRecordCall(rewriteStatement, rc);
		rc.p(", ");
		printValueStmtOrNull(formatPhrase, rc);
		rc.p(", ");
		printCallOrNull(indicatorsPhrase, rc);
		rc.p(");");
		rc.pNl(rewriteStatement);

		// INVALID KEY / NOT INVALID KEY handling
		emitInvalidKeyHandling(rewriteStatement, rc);
	}

	private void applyWithFormat(final RewriteStatement rewriteStatement, final ValueStmt formatPhrase,
			final Call indicatorsPhrase, final RuleContext rc) {
		rc.p("fileControlService.rewrite(");
		printFileIdentifier(rewriteStatement, rc);
		rc.p(", ");
		printValueStmtOrNull(formatPhrase, rc);
		rc.p(", ");
		printCallOrNull(indicatorsPhrase, rc);
		rc.p(");");
		rc.pNl(rewriteStatement);

		// INVALID KEY / NOT INVALID KEY handling
		emitInvalidKeyHandling(rewriteStatement, rc);
	}

	private void printFileIdentifier(final RewriteStatement rewriteStatement, final RuleContext rc) {
		final Call recordCall = rewriteStatement.getRecordCall();
		final DataDescriptionEntry dataDescriptionEntry = cobolDataDescriptionEntryService
				.getDataDescriptionEntry(recordCall);

		if (dataDescriptionEntry == null) {
			visitRecordCall(rewriteStatement, rc);
		} else {
			final FileDescriptionEntry fileDescriptionEntry = (FileDescriptionEntry) ANTLRUtils.findParent(
					FileDescriptionEntry.class, dataDescriptionEntry.getCtx(),
					rewriteStatement.getProgram().getASGElementRegistry());

			if (fileDescriptionEntry != null && fileDescriptionEntry.getFileControlEntry() != null) {
				final FileControlEntry fileControlEntry = fileDescriptionEntry.getFileControlEntry();
				final String identifier = javaFileControlEntryIdentifierService.mapToIdentifier(fileControlEntry);
				rc.p(identifier);
			} else {
				visitRecordCall(rewriteStatement, rc);
			}
		}
	}

	private void visitRecordCall(final RewriteStatement rewriteStatement, final RuleContext rc) {
		rc.visit(rewriteStatement.getRecordCall().getCtx());
	}

	@Override
	public Class<RewriteStatementContext> from() {
		return RewriteStatementContext.class;
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
	 */
	private void emitInvalidKeyHandling(final RewriteStatement rewriteStatement, final RuleContext rc) {
		final InvalidKeyPhrase invalidKeyPhrase = rewriteStatement.getInvalidKeyPhrase();
		final NotInvalidKeyPhrase notInvalidKeyPhrase = rewriteStatement.getNotInvalidKeyPhrase();

		if (invalidKeyPhrase == null && notInvalidKeyPhrase == null) {
			return;
		}

		rc.p("if (fileControlService.isInvalidKey(");
		printFileIdentifier(rewriteStatement, rc);
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
