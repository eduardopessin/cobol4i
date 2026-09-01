package io.proleap.cobol.transform.java.rules.lang.procedure.search;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.SearchStatementContext;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.call.DataDescriptionEntryCall;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.data.datadescription.OccursClause;
import io.proleap.cobol.asg.metamodel.data.datadescription.OccursIndexed;
import io.proleap.cobol.asg.metamodel.procedure.AtEndPhrase;
import io.proleap.cobol.asg.metamodel.procedure.Statement;
import io.proleap.cobol.asg.metamodel.procedure.search.SearchStatement;
import io.proleap.cobol.asg.metamodel.procedure.search.VaryingPhrase;
import io.proleap.cobol.asg.metamodel.procedure.search.WhenPhrase;
import io.proleap.cobol.transform.java.expression.JavaExpressionService;
import io.proleap.cobol.transform.java.identifier.JavaIdentifierService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

/**
 * Transforms COBOL SEARCH statement into a Java for-loop that iterates
 * through the table, checking WHEN conditions sequentially.
 *
 * COBOL:
 *   SEARCH table-name VARYING index-name
 *     AT END statements
 *     WHEN condition1 statements1
 *     WHEN condition2 statements2
 *   END-SEARCH
 *
 * Java:
 *   {
 *     boolean _searchFound = false;
 *     for (; indexVar.intValue() <= tableName.size(); indexVar = indexVar.add(BigDecimal.ONE)) {
 *       if (condition1) { statements1; _searchFound = true; break; }
 *       if (condition2) { statements2; _searchFound = true; break; }
 *     }
 *     if (!_searchFound) { atEndStatements; }
 *   }
 */
@Singleton
public class SearchStatementRule extends CobolTransformRule<SearchStatementContext, SearchStatement> {

	@Inject
	private JavaExpressionService javaExpressionService;

	@Inject
	private JavaIdentifierService javaIdentifierService;

	@Override
	public void apply(final SearchStatementContext ctx, final SearchStatement searchStatement,
			final RuleContext rc) {
		// Get the varying index variable name
		final VaryingPhrase varyingPhrase = searchStatement.getVaryingPhrase();
		String indexVar = null;
		if (varyingPhrase != null && varyingPhrase.getDataCall() != null
				&& varyingPhrase.getDataCall().getName() != null) {
			indexVar = javaIdentifierService.mapToIdentifier(varyingPhrase.getDataCall().getName());
		}

		// If no VARYING phrase, resolve the table's INDEXED BY variable
		if (indexVar == null) {
			indexVar = resolveTableIndex(searchStatement);
		}

		// Final fallback - should not happen for well-formed COBOL
		if (indexVar == null) {
			indexVar = "_searchIdx";
		}

		// Get the fully qualified table path for the .size() bound
		// Uses javaExpressionService.mapToCall to produce the full dotted path
		// (e.g., w_arr_tr1006.arr_tr1006) rather than just the leaf name (arr_tr1006)
		final Call dataCall = searchStatement.getDataCall();
		String tableRef = "table";
		if (dataCall != null) {
			tableRef = javaExpressionService.mapToCall(dataCall);
		}

		rc.p("{");
		rc.pNl(searchStatement);
		rc.getPrinter().indent();

		rc.p("boolean _searchFound = false;");
		rc.pNl();

		// Generate for-loop: index starts at current value, increments by 1 each iteration
		// The COBOL SEARCH starts from the current index value, so no initialization here.
		// Upper bound: iterate while index <= table size (1-based COBOL indexing).
		rc.p("for (; %s.intValue() <= %s.size(); %s = %s.add(BigDecimal.ONE)) {",
				indexVar, tableRef, indexVar, indexVar);
		rc.pNl();
		rc.getPrinter().indent();

		// Generate WHEN clauses
		final List<WhenPhrase> whenPhrases = searchStatement.getWhenPhrases();
		if (whenPhrases != null) {
			for (final WhenPhrase whenPhrase : whenPhrases) {
				rc.p("if (");
				if (whenPhrase.getCondition() != null) {
					rc.visit(whenPhrase.getCondition().getCtx());
				}
				rc.p(") {");
				rc.pNl();
				rc.getPrinter().indent();

				// Visit WHEN statements (CONTINUE, SET, MOVE, etc.)
				if (whenPhrase.getWhenType() == WhenPhrase.WhenType.STATEMENTS) {
					for (final Statement statement : whenPhrase.getStatements()) {
						rc.visit(statement.getCtx());
					}
				}

				rc.p("_searchFound = true; break;");
				rc.pNl();
				rc.getPrinter().unindent();
				rc.p("}");
				rc.pNl();
			}
		}

		rc.getPrinter().unindent();
		rc.p("}");
		rc.pNl();

		// Generate AT END clause
		final AtEndPhrase atEndPhrase = searchStatement.getAtEndPhrase();
		if (atEndPhrase != null) {
			rc.p("if (!_searchFound) {");
			rc.pNl();
			rc.getPrinter().indent();

			for (final Statement statement : atEndPhrase.getStatements()) {
				rc.visit(statement.getCtx());
			}

			rc.getPrinter().unindent();
			rc.p("}");
			rc.pNl();
		}

		rc.getPrinter().unindent();
		rc.p("}");
		rc.pNl();
	}

	/**
	 * Resolves the INDEXED BY variable name from the table's OCCURS clause.
	 * For SEARCH without VARYING, the table's implicit index is used.
	 */
	private String resolveTableIndex(final SearchStatement searchStatement) {
		final Call dataCall = searchStatement.getDataCall();
		if (dataCall == null) {
			return null;
		}

		final Call unwrapped = dataCall.unwrap();
		if (!(unwrapped instanceof DataDescriptionEntryCall)) {
			return null;
		}

		final DataDescriptionEntry entry = ((DataDescriptionEntryCall) unwrapped).getDataDescriptionEntry();
		if (!(entry instanceof DataDescriptionEntryGroup)) {
			return null;
		}

		final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) entry;
		final List<OccursClause> occursClauses = group.getOccursClauses();
		if (occursClauses == null || occursClauses.isEmpty()) {
			return null;
		}

		final OccursIndexed occursIndexed = occursClauses.get(0).getOccursIndexed();
		if (occursIndexed == null || occursIndexed.getIndices() == null || occursIndexed.getIndices().isEmpty()) {
			return null;
		}

		final String indexName = occursIndexed.getIndices().get(0).getName();
		if (indexName == null) {
			return null;
		}

		return javaIdentifierService.mapToIdentifier(indexName);
	}

	@Override
	public Class<SearchStatementContext> from() {
		return SearchStatementContext.class;
	}
}
