package io.proleap.cobol.transform.java.rules.lang.procedure;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.ProcedureDivisionContext;
import io.proleap.cobol.asg.metamodel.procedure.Paragraph;
import io.proleap.cobol.asg.metamodel.procedure.ProcedureDivision;
import io.proleap.cobol.asg.metamodel.procedure.Section;
import io.proleap.cobol.asg.metamodel.procedure.Statement;
import io.proleap.cobol.asg.metamodel.procedure.StatementTypeEnum;
import io.proleap.cobol.asg.metamodel.procedure.stop.StopStatement;
import io.proleap.cobol.transform.java.identifier.method.JavaMethodIdentifierService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class ProcedureDivisionRule extends CobolTransformRule<ProcedureDivisionContext, ProcedureDivision> {

	@Inject
	private JavaMethodIdentifierService javaMethodIdentifierService;

	@Override
	public void apply(final ProcedureDivisionContext ctx, final ProcedureDivision procedureDivision,
			final RuleContext rc) {
		final boolean hasStatements = !procedureDivision.getStatements().isEmpty();

		if (hasStatements) {
			rc.p("@Override");
			rc.pNl();
			rc.p("public void procedureDivision() throws Exception{");
			rc.pNl(procedureDivision);
			rc.getPrinter().indent();

			for (final Statement statement : procedureDivision.getStatements()) {
				rc.visit(statement.getCtx());
			}

			// COBOL fall-through: after the inline statements, execution continues
			// sequentially into the first paragraph or section. Without this call,
			// programs with statements before their first paragraph (e.g., DH0001IC
			// which has an INSPECT before the INICIO paragraph) would silently skip
			// all code in the first paragraph.
			// However, if the last statement is GOBACK or STOP RUN, the fall-through
			// call is unreachable and causes a compile error.
			final java.util.List<Statement> stmts = procedureDivision.getStatements();
			final boolean lastStatementTerminates = !stmts.isEmpty()
					&& isTerminatingStatement(stmts.get(stmts.size() - 1));

			if (!lastStatementTerminates) {
				final java.util.List<Section> sections = procedureDivision.getSections();
				final java.util.List<Paragraph> paragraphs = procedureDivision.getRootParagraphs();
				String fallThroughCall = null;
				if (!sections.isEmpty()) {
					fallThroughCall = javaMethodIdentifierService.mapToIdentifier(sections.get(0)) + "()";
				} else if (!paragraphs.isEmpty()) {
					fallThroughCall = javaMethodIdentifierService.mapToIdentifier(paragraphs.get(0)) + "()";
				}
				if (fallThroughCall != null) {
					rc.p("%s;", fallThroughCall);
					rc.pNl();
				}
			}

			rc.getPrinter().unindent();
			rc.p("}");
			rc.pNl();
		}

		for (final Section section : procedureDivision.getSections()) {
			rc.visit(section.getCtx());
		}

		for (final Paragraph paragraph : procedureDivision.getRootParagraphs()) {
			rc.visit(paragraph.getCtx());
		}
	}

	/**
	 * Checks if a statement is a GOBACK or STOP RUN (which generates a throw,
	 * making any subsequent code unreachable).
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
	public Class<ProcedureDivisionContext> from() {
		return ProcedureDivisionContext.class;
	}
}
