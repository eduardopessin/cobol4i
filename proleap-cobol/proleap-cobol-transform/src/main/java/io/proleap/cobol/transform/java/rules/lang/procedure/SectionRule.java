package io.proleap.cobol.transform.java.rules.lang.procedure;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;

import io.proleap.cobol.CobolParser.ProcedureSectionContext;
import io.proleap.cobol.asg.metamodel.procedure.Paragraph;
import io.proleap.cobol.asg.metamodel.procedure.Section;
import io.proleap.cobol.asg.metamodel.procedure.Statement;
import io.proleap.cobol.asg.metamodel.procedure.StatementTypeEnum;
import io.proleap.cobol.asg.metamodel.procedure.ifstmt.IfStatement;
import io.proleap.cobol.asg.metamodel.procedure.stop.StopStatement;
import io.proleap.cobol.asg.metamodel.procedure.stop.StopStatement.StopType;
import io.proleap.cobol.transform.java.identifier.method.JavaMethodIdentifierService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class SectionRule extends CobolTransformRule<ProcedureSectionContext, Section> {

	@Inject
	private JavaMethodIdentifierService javaMethodIdentifierService;

	@Override
	public void apply(final ProcedureSectionContext ctx, final Section section, final RuleContext rc) {
		rc.p("public void %s() throws Exception {", javaMethodIdentifierService.mapToIdentifier(section));
		rc.pNl(section);
		rc.getPrinter().indent();

		final List<Statement> statements = section.getStatements();

		for (final Statement statement : statements) {
			rc.visit(statement.getCtx());
		}

		// In COBOL, a SECTION executes ALL its paragraphs in order (fall-through).
		// Emit a call for each paragraph sequentially.
		// Skip remaining calls once we hit a paragraph that always returns (GOBACK/STOP RUN).
		final boolean sectionStatementsReturn = statementsAlwaysReturn(statements);

		if (!sectionStatementsReturn) {
			for (final Paragraph paragraph : section.getParagraphs()) {
				rc.p("%s();", javaMethodIdentifierService.mapToIdentifier(paragraph));
				rc.pNl();
			}
		}

		rc.getPrinter().unindent();
		rc.p("}");
		rc.pNl();

		rc.pNl();

		for (final Paragraph paragraph : section.getParagraphs()) {
			rc.visit(paragraph.getCtx());
		}
	}

	/**
	 * Checks whether a list of statements always ends with a return (GOBACK/STOP RUN),
	 * including when the return is inside both branches of an IF/ELSE.
	 */
	protected boolean statementsAlwaysReturn(final List<Statement> statements) {
		for (final Statement stmt : statements) {
			if (statementReturns(stmt)) {
				return true;
			}
		}

		// Also check if the last statement is an IF with both branches returning
		if (!statements.isEmpty()) {
			final Statement lastStmt = statements.get(statements.size() - 1);
			if (lastStmt instanceof IfStatement) {
				final IfStatement ifStmt = (IfStatement) lastStmt;
				if (ifBothBranchesReturn(ifStmt)) {
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * Checks if a single statement is a GOBACK or STOP RUN.
	 */
	protected boolean statementReturns(final Statement stmt) {
		if (stmt.getStatementType() == StatementTypeEnum.GO_BACK) {
			return true;
		}
		if (StatementTypeEnum.STOP.equals(stmt.getStatementType()) && stmt instanceof StopStatement) {
			final StopStatement stopStmt = (StopStatement) stmt;
			if (StopType.STOP_RUN.equals(stopStmt.getStopType())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks if an IF statement has both THEN and ELSE branches, and both always return.
	 */
	protected boolean ifBothBranchesReturn(final IfStatement ifStmt) {
		final var thenBranch = ifStmt.getThen();
		final var elseBranch = ifStmt.getElse();

		if (thenBranch == null || elseBranch == null) {
			return false;
		}

		return statementsAlwaysReturn(thenBranch.getStatements())
				&& statementsAlwaysReturn(elseBranch.getStatements());
	}

	@Override
	public Class<ProcedureSectionContext> from() {
		return ProcedureSectionContext.class;
	}
}
