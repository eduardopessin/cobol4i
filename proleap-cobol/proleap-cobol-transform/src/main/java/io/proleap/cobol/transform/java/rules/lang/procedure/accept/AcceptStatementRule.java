package io.proleap.cobol.transform.java.rules.lang.procedure.accept;

import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.AcceptStatementContext;
import io.proleap.cobol.asg.metamodel.procedure.NotOnExceptionClause;
import io.proleap.cobol.asg.metamodel.procedure.OnExceptionClause;
import io.proleap.cobol.asg.metamodel.procedure.Statement;
import io.proleap.cobol.asg.metamodel.procedure.accept.AcceptFromDateStatement;
import io.proleap.cobol.asg.metamodel.procedure.accept.AcceptFromDateStatement.DateType;
import io.proleap.cobol.asg.metamodel.procedure.accept.AcceptStatement;
import io.proleap.cobol.asg.metamodel.procedure.accept.AcceptStatement.AcceptType;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class AcceptStatementRule extends CobolTransformRule<AcceptStatementContext, AcceptStatement> {

	@Override
	public void apply(final AcceptStatementContext ctx, final AcceptStatement acceptStatement, final RuleContext rc) {
		final AcceptType acceptType = acceptStatement.getAcceptType();

		if (acceptType == AcceptType.DATE) {
			final AcceptFromDateStatement acceptFromDateStatement = acceptStatement.getAcceptFromDateStatement();

			if (acceptFromDateStatement != null) {
				final DateType dateType = acceptFromDateStatement.getDateType();
				emitDateAccept(dateType, acceptStatement, rc);
				return;
			}
		}

		// Fallback: original try/catch pattern for non-date ACCEPT variants
		final OnExceptionClause onExceptionClause = acceptStatement.getOnExceptionClause();
		final NotOnExceptionClause notOnExceptionClause = acceptStatement.getNotOnExceptionClause();

		rc.p("try {");
		rc.pNl();

		if (notOnExceptionClause != null) {
			visitStatements(notOnExceptionClause, rc);
		}

		rc.p("} catch (Exception e) {");
		rc.pNl();
		rc.getPrinter().indent();

		if (onExceptionClause != null) {
			visitStatements(onExceptionClause, rc);
		}

		rc.getPrinter().unindent();
		rc.p("}");
		rc.pNl(acceptStatement);
	}

	private void emitDateAccept(final DateType dateType, final AcceptStatement acceptStatement, final RuleContext rc) {
		// Generate a block that computes the date string and moves it into the target variable
		rc.p("{ java.time.LocalDate _today = java.time.LocalDate.now();");
		rc.pNl();

		switch (dateType) {
		case DATE:
			// ACCEPT var FROM DATE → YYMMDD (6 digits)
			rc.p("  String _dateStr = String.format(\"%02d%02d%02d\", _today.getYear() % 100, _today.getMonthValue(), _today.getDayOfMonth());");
			rc.pNl();
			break;
		case DATE_YYYYMMDD:
			// ACCEPT var FROM DATE YYYYMMDD → YYYYMMDD (8 digits)
			rc.p("  String _dateStr = String.format(\"%04d%02d%02d\", _today.getYear(), _today.getMonthValue(), _today.getDayOfMonth());");
			rc.pNl();
			break;
		case DAY:
			// ACCEPT var FROM DAY → YYDDD (5 digits)
			rc.p("  String _dateStr = String.format(\"%02d%03d\", _today.getYear() % 100, _today.getDayOfYear());");
			rc.pNl();
			break;
		case DAY_YYYYMMDD:
			// ACCEPT var FROM DAY YYYYDDD → YYYYDDD (7 digits)
			// Note: DAY_YYYYMMDD in the enum actually represents DAY with YYYYDDD qualifier
			rc.p("  String _dateStr = String.format(\"%04d%03d\", _today.getYear(), _today.getDayOfYear());");
			rc.pNl();
			break;
		case YYYYDDD:
			// ACCEPT var FROM DAY YYYYDDD (alternate mapping) → YYYYDDD (7 digits)
			rc.p("  String _dateStr = String.format(\"%04d%03d\", _today.getYear(), _today.getDayOfYear());");
			rc.pNl();
			break;
		case TIME:
			// ACCEPT var FROM TIME → HHMMSSCC (8 digits, CC=centiseconds)
			rc.p("  java.time.LocalTime _time = java.time.LocalTime.now();");
			rc.pNl();
			rc.p("  String _dateStr = String.format(\"%02d%02d%02d%02d\", _time.getHour(), _time.getMinute(), _time.getSecond(), _time.getNano() / 10_000_000);");
			rc.pNl();
			break;
		case YEAR:
			// ACCEPT var FROM YEAR → YYYY (4 digits)
			rc.p("  String _dateStr = String.format(\"%04d\", _today.getYear());");
			rc.pNl();
			break;
		case YYYYMMDD:
			// ACCEPT var FROM YYYYMMDD → YYYYMMDD (8 digits)
			rc.p("  String _dateStr = String.format(\"%04d%02d%02d\", _today.getYear(), _today.getMonthValue(), _today.getDayOfMonth());");
			rc.pNl();
			break;
		case MMDDYYYY:
			// ACCEPT var FROM MMDDYYYY → MMDDYYYY (8 digits)
			rc.p("  String _dateStr = String.format(\"%02d%02d%04d\", _today.getMonthValue(), _today.getDayOfMonth(), _today.getYear());");
			rc.pNl();
			break;
		case TODAYS_DATE:
			// ACCEPT var FROM TODAYS-DATE → YYMMDD (6 digits)
			rc.p("  String _dateStr = String.format(\"%02d%02d%02d\", _today.getYear() % 100, _today.getMonthValue(), _today.getDayOfMonth());");
			rc.pNl();
			break;
		case TODAYS_DATE_MMDDYYYY:
			// ACCEPT var FROM TODAYS-DATE MMDDYYYY → MMDDYYYY (8 digits)
			rc.p("  String _dateStr = String.format(\"%02d%02d%04d\", _today.getMonthValue(), _today.getDayOfMonth(), _today.getYear());");
			rc.pNl();
			break;
		default:
			// For unhandled types (TIMER, TODAYS_NAME), emit a TODO comment
			rc.p("  String _dateStr = \"\"; /* TODO: unhandled ACCEPT FROM date type: " + dateType + " */");
			rc.pNl();
			break;
		}

		rc.p("  CobolMove.moveStringToGroup(_dateStr, ");
		rc.visit(acceptStatement.getAcceptCall().getCtx());
		rc.p(");");
		rc.pNl();
		rc.p("}");
		rc.pNl(acceptStatement);
	}

	@Override
	public Class<AcceptStatementContext> from() {
		return AcceptStatementContext.class;
	}

	private void visitStatements(final NotOnExceptionClause notOnExceptionClause, final RuleContext rc) {
		for (final Statement statement : notOnExceptionClause.getStatements()) {
			rc.visit(statement.getCtx());
		}
	}

	private void visitStatements(final OnExceptionClause onExceptionClause, final RuleContext rc) {
		for (final Statement statement : onExceptionClause.getStatements()) {
			rc.visit(statement.getCtx());
		}
	}
}
