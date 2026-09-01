package io.proleap.cobol.transform.java.rules.lang.procedure.add;

import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.AddStatementContext;
import io.proleap.cobol.asg.metamodel.procedure.NotOnSizeErrorPhrase;
import io.proleap.cobol.asg.metamodel.procedure.OnSizeErrorPhrase;
import io.proleap.cobol.asg.metamodel.procedure.Statement;
import io.proleap.cobol.asg.metamodel.procedure.add.AddStatement;
import io.proleap.cobol.asg.metamodel.procedure.add.AddStatement.AddType;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class AddStatementRule extends CobolTransformRule<AddStatementContext, AddStatement> {

	@Override
	public void apply(final AddStatementContext ctx, final AddStatement addStatement, final RuleContext rc) {
		final OnSizeErrorPhrase onSizeErrorPhrase = addStatement.getOnSizeErrorPhrase();
		final NotOnSizeErrorPhrase notOnSizeErrorPhrase = addStatement.getNotOnSizeErrorPhrase();
		final boolean hasOnSizeError = onSizeErrorPhrase != null;

		if (hasOnSizeError) {
			rc.p("try {");
			rc.pNl();
			rc.getPrinter().indent();
		}

		final AddType type = addStatement.getAddType();

		switch (type) {
		case CORRESPONDING:
			rc.visit(addStatement.getAddCorrespondingStatement().getCtx());
			break;
		case TO_GIVING:
			rc.visit(addStatement.getAddToGivingStatement().getCtx());
			break;
		case TO:
			rc.visit(addStatement.getAddToStatement().getCtx());
			break;
		default:
			break;
		}

		if (notOnSizeErrorPhrase != null) {
			for (final Statement statement : notOnSizeErrorPhrase.getStatements()) {
				rc.visit(statement.getCtx());
			}
		}

		if (hasOnSizeError) {
			rc.getPrinter().unindent();
			rc.p("} catch (ArithmeticException e) {");
			rc.pNl();
			rc.getPrinter().indent();

			for (final Statement statement : onSizeErrorPhrase.getStatements()) {
				rc.visit(statement.getCtx());
			}

			rc.getPrinter().unindent();
			rc.p("}");
			rc.pNl(addStatement);
		}
	}

	@Override
	public Class<AddStatementContext> from() {
		return AddStatementContext.class;
	}
}
