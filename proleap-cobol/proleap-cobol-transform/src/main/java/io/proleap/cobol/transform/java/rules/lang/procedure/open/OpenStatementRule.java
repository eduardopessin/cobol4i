package io.proleap.cobol.transform.java.rules.lang.procedure.open;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.OpenStatementContext;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.call.FileControlEntryCall;
import io.proleap.cobol.asg.metamodel.environment.inputoutput.filecontrol.FileControlEntry;
import io.proleap.cobol.asg.metamodel.environment.inputoutput.filecontrol.FileStatusClause;
import io.proleap.cobol.asg.metamodel.procedure.open.ExtendPhrase;
import io.proleap.cobol.asg.metamodel.procedure.open.Input;
import io.proleap.cobol.asg.metamodel.procedure.open.InputOutputPhrase;
import io.proleap.cobol.asg.metamodel.procedure.open.InputPhrase;
import io.proleap.cobol.asg.metamodel.procedure.open.OpenStatement;
import io.proleap.cobol.asg.metamodel.procedure.open.Output;
import io.proleap.cobol.asg.metamodel.procedure.open.OutputPhrase;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.transform.java.identifier.variable.JavaFileControlEntryIdentifierService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class OpenStatementRule extends CobolTransformRule<OpenStatementContext, OpenStatement> {

	@Inject
	private CobolTypeService cobolTypeService;

	@Inject
	private JavaFileControlEntryIdentifierService javaFileControlEntryIdentifierService;

	@Override
	public void apply(final OpenStatementContext ctx, final OpenStatement openStatement, final RuleContext rc) {
		printOpenInputs(openStatement, rc);
		printOpenInputOutputs(openStatement, rc);
		printOpenOutputs(openStatement, rc);
		printOpenExtends(openStatement, rc);
	}

	@Override
	public Class<OpenStatementContext> from() {
		return OpenStatementContext.class;
	}

	protected void printOpenExtends(final OpenStatement openStatement, final RuleContext rc) {
		for (final ExtendPhrase openExtend : openStatement.getExtendPhrases()) {
			for (final Call call : openExtend.getFileCalls()) {
				rc.p("fileControlService.openExtend(");
				emitFileReference(call, rc);
				rc.p(");");
				rc.pNl(call);
				emitFileStatusUpdate(call, rc);
			}
		}
	}

	protected void printOpenInputOutputs(final OpenStatement openStatement, final RuleContext rc) {
		for (final InputOutputPhrase openInputOutput : openStatement.getInputOutputPhrases()) {
			for (final Call call : openInputOutput.getFileCalls()) {
				rc.p("fileControlService.openInputOutput(");
				emitFileReference(call, rc);
				rc.p(");");
				rc.pNl(call);
				emitFileStatusUpdate(call, rc);
			}
		}
	}

	protected void printOpenInputs(final OpenStatement openStatement, final RuleContext rc) {
		for (final InputPhrase openInput : openStatement.getInputPhrases()) {
			for (final Input input : openInput.getInputs()) {
				rc.p("fileControlService.openInput(");
				emitFileReference(input.getFileCall(), rc);
				rc.p(");");
				rc.pNl(input);
				emitFileStatusUpdate(input.getFileCall(), rc);
			}
		}
	}

	protected void printOpenOutputs(final OpenStatement openStatement, final RuleContext rc) {
		for (final OutputPhrase openOutput : openStatement.getOutputPhrases()) {
			for (final Output output : openOutput.getOutputs()) {
				rc.p("fileControlService.openOutput(");
				emitFileReference(output.getFileCall(), rc);
				rc.p(");");
				rc.pNl(output);
				emitFileStatusUpdate(output.getFileCall(), rc);
			}
		}
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
