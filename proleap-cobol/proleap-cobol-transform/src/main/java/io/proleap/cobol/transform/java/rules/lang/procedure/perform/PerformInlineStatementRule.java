package io.proleap.cobol.transform.java.rules.lang.procedure.perform;

import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.PerformInlineStatementContext;
import io.proleap.cobol.asg.metamodel.procedure.Statement;
import io.proleap.cobol.asg.metamodel.procedure.perform.PerformInlineStatement;
import io.proleap.cobol.asg.metamodel.procedure.perform.PerformType;
import io.proleap.cobol.asg.metamodel.procedure.perform.PerformType.PerformTypeType;
import io.proleap.cobol.asg.metamodel.procedure.perform.TestClause;
import io.proleap.cobol.asg.metamodel.procedure.perform.Times;
import io.proleap.cobol.asg.metamodel.procedure.perform.Until;
import io.proleap.cobol.asg.metamodel.procedure.perform.Varying;
import io.proleap.cobol.asg.metamodel.procedure.perform.VaryingClause;
import io.proleap.cobol.asg.metamodel.procedure.perform.VaryingPhrase;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class PerformInlineStatementRule extends CobolTransformRule<PerformInlineStatementContext, PerformInlineStatement> {

	@Override
	public void apply(final PerformInlineStatementContext ctx, final PerformInlineStatement performInlineStatement,
			final RuleContext rc) {
		final PerformType performType = performInlineStatement.getPerformType();

		if (performType == null) {
			visitStatements(performInlineStatement, rc);
		} else {
			final PerformTypeType type = performType.getPerformTypeType();

			if (type == null) {
			} else {
				switch (type) {
				case TIMES:
					final Times times = performType.getTimes();
					printTimes(performInlineStatement, times, rc);
					break;
				case UNTIL:
					final Until until = performType.getUntil();
					printUntil(performInlineStatement, until, rc);
					break;
				case VARYING:
					final Varying varying = performType.getVarying();
					printVarying(performInlineStatement, varying, rc);
					break;
				default:
					break;
				}
			}
		}
	}

	@Override
	public Class<PerformInlineStatementContext> from() {
		return PerformInlineStatementContext.class;
	}

	protected void printTimes(final PerformInlineStatement performInlineStatement, final Times times,
			final RuleContext rc) {
		rc.pNl();
		rc.p("for(BigDecimal i=BigDecimal.ZERO; i.compareTo(");
		rc.visit(times.getTimesValueStmt().getCtx());
		rc.p(") < 0; i = i.add(BigDecimal.ONE)){");
		rc.pNl(times);

		rc.getPrinter().indent();
		visitStatements(performInlineStatement, rc);
		rc.getPrinter().unindent();

		rc.p("}");
		rc.pNl();
		rc.pNl();
	}

	protected void printUntil(final PerformInlineStatement performInlineStatement, final Until until,
			final RuleContext rc) {
		final TestClause testClause = until.getTestClause();

		// Use source line number for loop guard diagnostics
		final int lineNr = performInlineStatement.getCtx() != null ? performInlineStatement.getCtx().getStart().getLine() : 0;

		rc.pNl();

		if (testClause != null && TestClause.TestClauseType.AFTER.equals(testClause.getTestClauseType())) {
			rc.p("{ int _lg%d = 0;", lineNr);
			rc.pNl();
			rc.p("do {");
			rc.pNl();

			rc.getPrinter().indent();
			visitStatements(performInlineStatement, rc);
			// Loop guard
			rc.p("_lg%d++;", lineNr);
			rc.pNl();
			rc.p("if (_lg%d %% 5000 == 0) System.out.println(\"[LOOP-WARN] line=%d iteration=\" + _lg%d);", lineNr, lineNr, lineNr);
			rc.pNl();
			rc.p("if (_lg%d > 10000) { System.err.println(\"[LOOP-BREAK] line=%d exceeded 10000 iterations\"); break; }", lineNr, lineNr);
			rc.pNl();
			rc.getPrinter().unindent();

			rc.p("} while(!(");
			rc.visit(until.getCondition().getCtx());
			rc.p("));");
			rc.pNl(until);
			rc.p("}");
			rc.pNl();
		} else {
			rc.p("{ int _lg%d = 0;", lineNr);
			rc.pNl();
			rc.p("while(!(");
			rc.visit(until.getCondition().getCtx());
			rc.p(")){");
			rc.pNl(until);

			rc.getPrinter().indent();
			visitStatements(performInlineStatement, rc);
			// Loop guard
			rc.p("_lg%d++;", lineNr);
			rc.pNl();
			rc.p("if (_lg%d %% 5000 == 0) System.out.println(\"[LOOP-WARN] line=%d iteration=\" + _lg%d);", lineNr, lineNr, lineNr);
			rc.pNl();
			rc.p("if (_lg%d > 10000) { System.err.println(\"[LOOP-BREAK] line=%d exceeded 10000 iterations\"); break; }", lineNr, lineNr);
			rc.pNl();
			rc.getPrinter().unindent();

			rc.p("}");
			rc.pNl();
			rc.p("}");
			rc.pNl();
		}
	}

	protected void printVarying(final PerformInlineStatement performInlineStatement, final Varying varying,
			final RuleContext rc) {
		final VaryingClause varyingClause = varying.getVaryingClause();

		if (varyingClause == null) {
			visitStatements(performInlineStatement, rc);
			return;
		}

		final VaryingPhrase varyingPhrase = varyingClause.getVaryingPhrase();

		if (varyingPhrase == null) {
			visitStatements(performInlineStatement, rc);
			return;
		}

		// PERFORM VARYING var FROM x BY y UNTIL condition
		// Java: var = x; while(!(condition)) { ...body...; var = var.add(y); }
		rc.pNl();

		// var = FROM value
		rc.visit(varyingPhrase.getVaryingValueStmt().getCtx());
		rc.p(" = ");
		rc.visit(varyingPhrase.getFrom().getFromValueStmt().getCtx());
		rc.p(";");
		rc.pNl();

		// while(!(until condition))
		final Until until = varyingPhrase.getUntil();
		rc.p("while(!(");
		rc.visit(until.getCondition().getCtx());
		rc.p(")){");
		rc.pNl(until);

		rc.getPrinter().indent();

		// body statements
		visitStatements(performInlineStatement, rc);

		// var = var + BY value
		if (varyingPhrase.getBy() != null) {
			rc.visit(varyingPhrase.getVaryingValueStmt().getCtx());
			rc.p(" = ");
			rc.visit(varyingPhrase.getVaryingValueStmt().getCtx());
			rc.p(".add(");
			rc.visit(varyingPhrase.getBy().getByValueStmt().getCtx());
			rc.p(");");
			rc.pNl();
		}

		rc.getPrinter().unindent();
		rc.p("}");
		rc.pNl();
	}

	private void visitStatements(final PerformInlineStatement performInlineStatement, final RuleContext rc) {
		for (final Statement statement : performInlineStatement.getStatements()) {
			rc.visit(statement.getCtx());
		}
	}
}
