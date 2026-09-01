package io.proleap.cobol.transform.java.rules.lang.procedure.delete;

import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.DeleteStatementContext;
import io.proleap.cobol.asg.metamodel.procedure.delete.DeleteStatement;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class DeleteStatementRule extends CobolTransformRule<DeleteStatementContext, DeleteStatement> {

	@Override
	public void apply(final DeleteStatementContext ctx, final DeleteStatement deleteStatement, final RuleContext rc) {
		rc.p("fileControlService.delete(");

		if (deleteStatement.getFileCall() != null) {
			rc.visit(deleteStatement.getFileCall().getCtx());
		}

		if (deleteStatement.isRecord()) {
			rc.p(", true");
		}

		rc.p(");");
		rc.pNl(deleteStatement);
	}

	@Override
	public Class<DeleteStatementContext> from() {
		return DeleteStatementContext.class;
	}
}
