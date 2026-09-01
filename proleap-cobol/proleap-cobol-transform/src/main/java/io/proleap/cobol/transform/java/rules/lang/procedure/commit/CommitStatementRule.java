package io.proleap.cobol.transform.java.rules.lang.procedure.commit;

import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.CommitStatementContext;
import io.proleap.cobol.asg.metamodel.procedure.commit.CommitStatement;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class CommitStatementRule extends CobolTransformRule<CommitStatementContext, CommitStatement> {

	@Override
	public void apply(final CommitStatementContext ctx, final CommitStatement commitStatement, final RuleContext rc) {
		rc.p("sqlService.commit();");
		rc.pNl(commitStatement);
	}

	@Override
	public Class<CommitStatementContext> from() {
		return CommitStatementContext.class;
	}
}
