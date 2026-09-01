package io.proleap.cobol.transform.java.rules.lang.procedure.start;

import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.StartStatementContext;
import io.proleap.cobol.asg.metamodel.procedure.start.StartStatement;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class StartStatementRule extends CobolTransformRule<StartStatementContext, StartStatement> {

	@Override
	public void apply(final StartStatementContext ctx, final StartStatement startStatement, final RuleContext rc) {
		rc.p("fileControlService.start(");

		if (startStatement.getFileCall() != null) {
			rc.visit(startStatement.getFileCall().getCtx());
		}

		if (startStatement.getKey() != null) {
			rc.p(", ");
			rc.visit(startStatement.getKey().getComparisonCall().getCtx());
		}

		rc.p(");");
		rc.pNl(startStatement);
	}

	@Override
	public Class<StartStatementContext> from() {
		return StartStatementContext.class;
	}
}
