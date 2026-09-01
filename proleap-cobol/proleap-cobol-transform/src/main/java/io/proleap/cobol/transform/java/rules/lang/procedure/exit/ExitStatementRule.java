package io.proleap.cobol.transform.java.rules.lang.procedure.exit;

import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.ExitStatementContext;
import io.proleap.cobol.asg.metamodel.procedure.exit.ExitStatement;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class ExitStatementRule extends CobolTransformRule<ExitStatementContext, ExitStatement> {

	@Override
	public void apply(final ExitStatementContext ctx, final ExitStatement exitStatement, final RuleContext rc) {
		if (ctx.PROGRAM() != null) {
			// EXIT PROGRAM → return from the called program
			rc.p("return;");
		} else {
			// EXIT → no-op (common end point for a series of procedures)
			rc.p("// EXIT - no-op");
		}
		rc.pNl(exitStatement);
	}

	@Override
	public Class<ExitStatementContext> from() {
		return ExitStatementContext.class;
	}
}
