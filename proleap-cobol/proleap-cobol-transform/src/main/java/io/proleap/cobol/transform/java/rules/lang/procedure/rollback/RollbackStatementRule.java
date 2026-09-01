package io.proleap.cobol.transform.java.rules.lang.procedure.rollback;

import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.RollbackStatementContext;
import io.proleap.cobol.asg.metamodel.procedure.rollback.RollbackStatement;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class RollbackStatementRule extends CobolTransformRule<RollbackStatementContext, RollbackStatement> {

	@Override
	public void apply(final RollbackStatementContext ctx, final RollbackStatement rollbackStatement,
			final RuleContext rc) {
		rc.p("sqlService.rollback();");
		rc.pNl(rollbackStatement);
	}

	@Override
	public Class<RollbackStatementContext> from() {
		return RollbackStatementContext.class;
	}
}
