package io.proleap.cobol.transform.java.rules.lang.procedure.close;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.CloseStatementContext;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.call.FileControlEntryCall;
import io.proleap.cobol.asg.metamodel.environment.inputoutput.filecontrol.FileControlEntry;
import io.proleap.cobol.asg.metamodel.environment.inputoutput.filecontrol.FileStatusClause;
import io.proleap.cobol.asg.metamodel.procedure.close.CloseFile;
import io.proleap.cobol.asg.metamodel.procedure.close.CloseStatement;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.transform.java.identifier.variable.JavaFileControlEntryIdentifierService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class CloseStatementRule extends CobolTransformRule<CloseStatementContext, CloseStatement> {

	@Inject
	private CobolTypeService cobolTypeService;

	@Inject
	private JavaFileControlEntryIdentifierService javaFileControlEntryIdentifierService;

	@Override
	public void apply(final CloseStatementContext ctx, final CloseStatement closeStatement, final RuleContext rc) {
		for (final CloseFile closeFile : closeStatement.getCloseFiles()) {
			printCloseFile(closeFile, rc);
		}
	}

	@Override
	public Class<CloseStatementContext> from() {
		return CloseStatementContext.class;
	}

	protected void printCloseFile(final CloseFile closeFile, final RuleContext rc) {
		rc.p("fileControlService.close(");
		emitFileReference(closeFile.getFileCall(), rc);
		rc.p(");");
		rc.pNl(closeFile);
		emitFileStatusUpdate(closeFile.getFileCall(), rc);
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
