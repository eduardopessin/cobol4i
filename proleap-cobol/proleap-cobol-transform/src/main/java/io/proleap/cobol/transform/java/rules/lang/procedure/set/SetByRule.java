package io.proleap.cobol.transform.java.rules.lang.procedure.set;

import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.SetUpDownByStatementContext;
import io.proleap.cobol.asg.metamodel.procedure.set.By;
import io.proleap.cobol.asg.metamodel.procedure.set.SetBy;
import io.proleap.cobol.asg.metamodel.procedure.set.SetBy.SetByType;
import io.proleap.cobol.asg.metamodel.procedure.set.To;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class SetByRule extends CobolTransformRule<SetUpDownByStatementContext, SetBy> {

	@Override
	public void apply(final SetUpDownByStatementContext ctx, final SetBy setBy, final RuleContext rc) {
		final SetByType setByType = setBy.getSetByType();
		final By by = setBy.getBy();

		if (by == null) {
			return;
		}

		final String method = SetByType.DOWN.equals(setByType) ? "subtract" : "add";

		for (final To to : setBy.getTos()) {
			// target = target.add(value) or target = target.subtract(value)
			rc.visit(to.getToCall().getCtx());
			rc.p(" = ");
			rc.visit(to.getToCall().getCtx());
			rc.p("." + method + "(");
			rc.visit(by.getByValueStmt().getCtx());
			rc.p(");");
			rc.pNl(to);
		}
	}

	@Override
	public Class<SetUpDownByStatementContext> from() {
		return SetUpDownByStatementContext.class;
	}
}
