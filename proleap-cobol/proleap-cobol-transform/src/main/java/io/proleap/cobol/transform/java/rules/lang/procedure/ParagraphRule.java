package io.proleap.cobol.transform.java.rules.lang.procedure;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.ParagraphContext;
import io.proleap.cobol.asg.metamodel.procedure.Paragraph;
import io.proleap.cobol.asg.metamodel.procedure.Statement;
import io.proleap.cobol.asg.metamodel.procedure.StatementTypeEnum;
import io.proleap.cobol.asg.metamodel.procedure.stop.StopStatement;
import io.proleap.cobol.transform.java.identifier.method.JavaMethodIdentifierService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class ParagraphRule extends CobolTransformRule<ParagraphContext, Paragraph> {

	@Inject
	private JavaMethodIdentifierService javaMethodIdentifierService;

	@Override
	public void apply(final ParagraphContext ctx, final Paragraph paragraph, final RuleContext rc) {
		rc.p("public void %s() throws Exception {", javaMethodIdentifierService.mapToIdentifier(paragraph));
		rc.pNl(paragraph);
		rc.getPrinter().indent();

		for (final Statement statement : paragraph.getStatements()) {
			rc.visit(statement.getCtx());
			// After a GOBACK or STOP RUN (which generates a throw), stop emitting
			// statements to avoid unreachable code compile errors.
			if (isTerminatingStatement(statement)) {
				break;
			}
		}

		rc.getPrinter().unindent();
		rc.p("}");
		rc.pNl();

		rc.pNl();
	}

	/**
	 * Checks if a statement is a GOBACK or STOP RUN.
	 */
	private boolean isTerminatingStatement(final Statement stmt) {
		if (stmt.getStatementType() == StatementTypeEnum.GO_BACK) {
			return true;
		}
		if (StatementTypeEnum.STOP.equals(stmt.getStatementType()) && stmt instanceof StopStatement) {
			final StopStatement stopStmt = (StopStatement) stmt;
			if (StopStatement.StopType.STOP_RUN.equals(stopStmt.getStopType())) {
				return true;
			}
		}
		return false;
	}

	@Override
	public Class<ParagraphContext> from() {
		return ParagraphContext.class;
	}
}
