package io.proleap.cobol.transform.java.rules.lang.procedure.goback;

import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.GobackStatementContext;
import io.proleap.cobol.asg.metamodel.procedure.goback.GobackStatement;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class GobackStatementRule extends CobolTransformRule<GobackStatementContext, GobackStatement> {

	@Override
	public void apply(final GobackStatementContext ctx, final GobackStatement gobackStatement, final RuleContext rc) {
		rc.p("throw new CobolStopRunException();");
		rc.pNl(gobackStatement);
	}

	@Override
	public Class<GobackStatementContext> from() {
		return GobackStatementContext.class;
	}
}
