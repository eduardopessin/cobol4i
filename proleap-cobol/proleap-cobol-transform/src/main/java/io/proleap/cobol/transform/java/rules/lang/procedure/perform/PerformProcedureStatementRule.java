package io.proleap.cobol.transform.java.rules.lang.procedure.perform;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.PerformProcedureStatementContext;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.procedure.perform.PerformProcedureStatement;
import io.proleap.cobol.asg.metamodel.procedure.perform.PerformType;
import io.proleap.cobol.asg.metamodel.procedure.perform.PerformType.PerformTypeType;
import io.proleap.cobol.asg.metamodel.procedure.perform.TestClause;
import io.proleap.cobol.asg.metamodel.procedure.perform.TestClause.TestClauseType;
import io.proleap.cobol.asg.metamodel.procedure.perform.Times;
import io.proleap.cobol.asg.metamodel.procedure.perform.Until;
import io.proleap.cobol.asg.metamodel.procedure.perform.Varying;
import io.proleap.cobol.transform.java.identifier.JavaIdentifierService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class PerformProcedureStatementRule
		extends CobolTransformRule<PerformProcedureStatementContext, PerformProcedureStatement> {

	@Inject
	private JavaIdentifierService javaIdentifierService;

	@Override
	public void apply(final PerformProcedureStatementContext ctx,
			final PerformProcedureStatement performProcedureStatement, final RuleContext rc) {
		final PerformType performType = performProcedureStatement.getPerformType();

		if (performType == null) {
			visitCalls(performProcedureStatement, rc);
		} else {
			final PerformTypeType type = performType.getPerformTypeType();

			switch (type) {
			case TIMES:
				final Times times = performType.getTimes();
				printTimes(performProcedureStatement, times, rc);
				break;
			case UNTIL:
				final Until until = performType.getUntil();
				printUntil(performProcedureStatement, until, rc);
				break;
			case VARYING:
				final Varying varying = performType.getVarying();
				printVarying(performProcedureStatement, varying, rc);
				break;
			default:
				break;
			}

			rc.pNl(performProcedureStatement);
		}
	}

	@Override
	public Class<PerformProcedureStatementContext> from() {
		return PerformProcedureStatementContext.class;
	}

	protected void printTimes(final PerformProcedureStatement performProcedureStatement, final Times times,
			final RuleContext rc) {
		rc.p("for(BigDecimal i=BigDecimal.ZERO; i.compareTo(");
		rc.visit(times.getTimesValueStmt().getCtx());
		rc.p(") < 0; i = i.add(BigDecimal.ONE)){");
		rc.pNl();

		rc.getPrinter().indent();
		visitCalls(performProcedureStatement, rc);
		rc.getPrinter().unindent();

		rc.p("}");

		rc.pNl(times);
	}

	protected void printUntil(final PerformProcedureStatement performProcedureStatement, final Until until,
			final RuleContext rc) {
		final TestClause testClause = until.getTestClause();

		// Use source line number for loop guard diagnostics
		final int lineNr = performProcedureStatement.getCtx() != null ? performProcedureStatement.getCtx().getStart().getLine() : 0;

		if (testClause != null && TestClauseType.AFTER.equals(testClause.getTestClauseType())) {
			rc.p("{ int _lg%d = 0;", lineNr);
			rc.pNl();
			rc.p("do {");
			rc.pNl();

			rc.getPrinter().indent();
			visitCalls(performProcedureStatement, rc);
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
			visitCalls(performProcedureStatement, rc);
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

	protected void printVarying(final PerformProcedureStatement performProcedureStatement, final Varying varying,
			final RuleContext rc) {
		final io.proleap.cobol.asg.metamodel.procedure.perform.VaryingClause vc = varying.getVaryingClause();
		if (vc == null || vc.getVaryingPhrase() == null) {
			// Fallback: simple call without loop
			visitCalls(performProcedureStatement, rc);
			return;
		}
		final io.proleap.cobol.asg.metamodel.procedure.perform.VaryingPhrase vp = vc.getVaryingPhrase();

		// Initialize: var = fromExpr
		if (vp.getVaryingValueStmt() != null && vp.getFrom() != null) {
			rc.visit(vp.getVaryingValueStmt().getCtx());
			rc.p(" = ");
			rc.visit(vp.getFrom().getFromValueStmt().getCtx());
			rc.p(";");
			rc.pNl();
		}

		// while(!(untilCondition)) { calls(); var = var.add(byExpr); }
		if (vp.getUntil() != null && vp.getUntil().getCondition() != null) {
			rc.p("while(!(");
			rc.visit(vp.getUntil().getCondition().getCtx());
			rc.p(")){");
			rc.pNl();
			rc.getPrinter().indent();
			visitCalls(performProcedureStatement, rc);
			if (vp.getBy() != null && vp.getBy().getByValueStmt() != null) {
				rc.visit(vp.getVaryingValueStmt().getCtx());
				rc.p(" = ");
				rc.visit(vp.getVaryingValueStmt().getCtx());
				rc.p(".add(");
				rc.visit(vp.getBy().getByValueStmt().getCtx());
				rc.p(");");
				rc.pNl();
			}
			rc.getPrinter().unindent();
			rc.p("}");
		} else {
			// No UNTIL — just call once
			visitCalls(performProcedureStatement, rc);
		}
	}

	protected void visitCalls(final PerformProcedureStatement performProcedureStatement, final RuleContext rc) {
		for (final Call call : performProcedureStatement.getCalls()) {
			rc.p("%s();", javaIdentifierService.mapToIdentifier(call.getName()));
			rc.pNl(performProcedureStatement);
		}
	}
}
