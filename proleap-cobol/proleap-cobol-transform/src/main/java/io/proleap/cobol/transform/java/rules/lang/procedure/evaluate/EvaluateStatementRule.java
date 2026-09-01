package io.proleap.cobol.transform.java.rules.lang.procedure.evaluate;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.EvaluateStatementContext;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.procedure.Statement;
import io.proleap.cobol.asg.metamodel.procedure.evaluate.AlsoCondition;
import io.proleap.cobol.asg.metamodel.procedure.evaluate.AlsoSelect;
import io.proleap.cobol.asg.metamodel.procedure.evaluate.Condition;
import io.proleap.cobol.asg.metamodel.procedure.evaluate.Condition.ConditionType;
import io.proleap.cobol.asg.metamodel.procedure.evaluate.EvaluateStatement;
import io.proleap.cobol.asg.metamodel.procedure.evaluate.Select;
import io.proleap.cobol.asg.metamodel.procedure.evaluate.Through;
import io.proleap.cobol.asg.metamodel.procedure.evaluate.When;
import io.proleap.cobol.asg.metamodel.procedure.evaluate.WhenOther;
import io.proleap.cobol.asg.metamodel.procedure.evaluate.WhenPhrase;
import io.proleap.cobol.asg.metamodel.valuestmt.ValueStmt;
import io.proleap.cobol.commons.datadescription.CobolDataDescriptionEntryService;
import io.proleap.cobol.commons.datadescription.CobolPictureLengthService;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.transform.java.expression.JavaExpressionService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class EvaluateStatementRule extends CobolTransformRule<EvaluateStatementContext, EvaluateStatement> {

	@Inject
	private CobolDataDescriptionEntryService dataDescriptionEntryService;

	@Inject
	private CobolPictureLengthService cobolPictureLengthService;

	@Inject
	private CobolTypeService cobolTypeService;

	@Inject
	private JavaExpressionService javaExpressionService;

	@Override
	public void apply(final EvaluateStatementContext ctx, final EvaluateStatement evaluateStatement,
			final RuleContext rc) {
		// Build list of all select subjects (primary + ALSO subjects)
		final List<String> selectExprs = new ArrayList<>();
		final List<ValueStmt> selectValueStmts = new ArrayList<>();

		final Select select = evaluateStatement.getSelect();
		final String primarySelectExpr = (select != null && select.getSelectValueStmt() != null)
				? javaExpressionService.mapToExpression(select.getSelectValueStmt())
				: null;
		selectExprs.add(primarySelectExpr);
		selectValueStmts.add(select != null ? select.getSelectValueStmt() : null);

		final List<AlsoSelect> alsoSelects = evaluateStatement.getAlsoSelects();
		if (alsoSelects != null) {
			for (final AlsoSelect alsoSelect : alsoSelects) {
				final Select alsoSel = alsoSelect.getSelect();
				if (alsoSel != null && alsoSel.getSelectValueStmt() != null) {
					selectExprs.add(javaExpressionService.mapToExpression(alsoSel.getSelectValueStmt()));
					selectValueStmts.add(alsoSel.getSelectValueStmt());
				} else {
					selectExprs.add(null);
					selectValueStmts.add(null);
				}
			}
		}

		// Detect EVALUATE TRUE pattern: selectExpr is "true" literal
		final boolean isEvaluateTrue = "true".equals(primarySelectExpr);
		final boolean isEvaluateFalse = "false".equals(primarySelectExpr);

		boolean isFirst = true;

		for (final WhenPhrase whenPhrase : evaluateStatement.getWhenPhrases()) {
			final List<When> whens = whenPhrase.getWhens();

			if (!isFirst) {
				rc.p(" else ");
			}

			rc.p("if (");

			// Multiple WHENs in the same phrase are OR'd together
			boolean firstWhen = true;
			for (final When when : whens) {
				if (!firstWhen) {
					rc.p(" || ");
				}

				// Build condition for primary subject
				final Condition condition = when.getCondition();
				final boolean hasAlso = when.getAlsoConditions() != null && !when.getAlsoConditions().isEmpty();

				if (hasAlso) {
					rc.p("(");
				}

				if (condition != null) {
					printCondition(condition, selectExprs.get(0), selectValueStmts.get(0), isEvaluateTrue, isEvaluateFalse, rc);
				} else {
					rc.p("true");
				}

				// Handle ALSO conditions
				if (hasAlso) {
					int alsoIndex = 1;
					for (final AlsoCondition alsoCondition : when.getAlsoConditions()) {
						rc.p(" && ");
						final Condition alsoCond = alsoCondition.getCondition();
						final String alsoSelectExpr = (alsoIndex < selectExprs.size()) ? selectExprs.get(alsoIndex) : null;
						final ValueStmt alsoSelectVs = (alsoIndex < selectValueStmts.size()) ? selectValueStmts.get(alsoIndex) : null;
						if (alsoCond != null) {
							final boolean alsoIsTrue = "true".equals(alsoSelectExpr);
							final boolean alsoIsFalse = "false".equals(alsoSelectExpr);
							printCondition(alsoCond, alsoSelectExpr, alsoSelectVs, alsoIsTrue, alsoIsFalse, rc);
						} else {
							rc.p("true");
						}
						alsoIndex++;
					}
					rc.p(")");
				}

				firstWhen = false;
			}

			rc.p(") {");
			rc.pNl();
			rc.getPrinter().indent();

			visitStatements(whenPhrase.getStatements(), rc);

			rc.getPrinter().unindent();
			rc.p("}");

			isFirst = false;
		}

		final WhenOther whenOther = evaluateStatement.getWhenOther();

		if (whenOther != null) {
			rc.p(" else {");
			rc.pNl();
			rc.getPrinter().indent();

			visitStatements(whenOther.getStatements(), rc);

			rc.getPrinter().unindent();
			rc.p("}");
			rc.pNl();
		} else {
			rc.pNl();
		}
	}

	protected void printCondition(final Condition condition, final String selectExpr,
			final ValueStmt selectValueStmt,
			final boolean isEvaluateTrue, final boolean isEvaluateFalse, final RuleContext rc) {
		final ConditionType type = condition.getConditionType();

		// Determine if the EVALUATE subject is alphanumeric (String) type
		final CobolTypeEnum selectType = selectValueStmt != null ? cobolTypeService.getType(selectValueStmt) : null;
		final boolean selectIsString = CobolTypeEnum.STRING.equals(selectType);
		final boolean selectIsGroup = CobolTypeEnum.DATA_DESCRIPTION_GROUP.equals(selectType);

		switch (type) {
		case ANY:
			rc.p("true");
			break;
		case BOOLEAN:
			if (condition.getConditionValueStmt() != null) {
				final String expr = javaExpressionService.mapToExpression(condition.getConditionValueStmt());
				if (expr != null && !expr.isEmpty()) {
					if (isEvaluateFalse) {
						rc.p("!(");
						rc.p(expr);
						rc.p(")");
					} else {
						rc.p(expr);
					}
				} else {
					rc.p("true");
				}
			} else {
				rc.p("true");
			}
			break;
		case CONDITION:
			if (condition.getConditionValueStmt() != null) {
				final String expr = javaExpressionService.mapToExpression(condition.getConditionValueStmt());
				if (expr != null && !expr.isEmpty()) {
					rc.p(expr);
				} else {
					rc.p("true");
				}
			} else {
				rc.p("true");
			}
			break;
		case VALUE:
			if (selectExpr != null && condition.getValue() != null && condition.getValue().getValueStmt() != null) {
				final String valueExpr = javaExpressionService
						.mapToExpression(condition.getValue().getValueStmt());

				if (isEvaluateTrue || isEvaluateFalse) {
					// EVALUATE TRUE/FALSE: the WHEN value IS the condition
					if (condition.isNot()) {
						rc.p("!");
					}
					if (isEvaluateFalse) {
						rc.p("!(");
						rc.p(valueExpr);
						rc.p(")");
					} else {
						rc.p(valueExpr);
					}
				} else if ("true".equals(valueExpr)) {
					// WHEN TRUE: the select expression is itself a boolean condition
					if (condition.isNot()) {
						rc.p("!(");
						rc.p(selectExpr);
						rc.p(")");
					} else {
						rc.p("(");
						rc.p(selectExpr);
						rc.p(")");
					}
				} else if ("false".equals(valueExpr)) {
					// WHEN FALSE: negate the select expression
					if (condition.isNot()) {
						rc.p("(");
						rc.p(selectExpr);
						rc.p(")");
					} else {
						rc.p("!(");
						rc.p(selectExpr);
						rc.p(")");
					}
				} else if (selectIsString || selectIsGroup) {
					// Alphanumeric or group subject: use CobolComparison.compareAlphanumeric
					// For groups, wrap with CobolMove.groupToString()
					// When the WHEN value is numeric, convert to String per COBOL rules
					final CobolTypeEnum valueType = cobolTypeService.getType(condition.getValue().getValueStmt());
					final boolean valueIsNumeric = CobolTypeEnum.INTEGER.equals(valueType) || CobolTypeEnum.FLOAT.equals(valueType);
					if (condition.isNot()) {
						rc.p("!");
					}
					rc.p("(CobolComparison.compareAlphanumeric(");
					if (selectIsGroup) {
						rc.p("CobolMove.groupToString(");
						rc.p(selectExpr);
						rc.p(")");
					} else {
						rc.p(selectExpr);
					}
					rc.p(", ");
					if (valueIsNumeric) {
						// Format the numeric value according to its PIC size so that
						// leading zeros are preserved (e.g., PIC 9(03) VALUE 10 -> "010")
						final Integer numIntDigits = getNumericIntegerDigits(condition.getValue().getValueStmt());
						if (numIntDigits != null) {
							rc.p("CobolMove.moveNumericToAlphanumeric(");
							rc.p(valueExpr);
							rc.p(", ");
							rc.p(String.valueOf(numIntDigits));
							rc.p(", ");
							rc.p(String.valueOf(numIntDigits));
							rc.p(")");
						} else {
							rc.p("String.valueOf(");
							rc.p(valueExpr);
							rc.p(")");
						}
					} else {
						rc.p(valueExpr);
					}
					rc.p(") == 0)");
				} else {
					// Numeric subject: check if WHEN value is alphanumeric (String literal)
					// COBOL converts alphanumeric literals to numeric for comparison
					final CobolTypeEnum valueType2 = cobolTypeService.getType(condition.getValue().getValueStmt());
					final boolean valueIsString = CobolTypeEnum.STRING.equals(valueType2);
					if (condition.isNot()) {
						rc.p("!");
					}
					rc.p("(");
					rc.p(selectExpr);
					rc.p(".compareTo(");
					if (valueIsString) {
						rc.p("new BigDecimal(");
						rc.p(valueExpr);
						rc.p(")");
					} else {
						rc.p(valueExpr);
					}
					rc.p(") == 0)");
				}
			} else if (condition.getConditionValueStmt() != null) {
				rc.p(javaExpressionService.mapToExpression(condition.getConditionValueStmt()));
			} else {
				rc.p("true");
			}
			break;
		case VALUE_THROUGH:
			if (selectExpr != null && condition.getValue() != null && condition.getValue().getValueStmt() != null) {
				final String fromExpr = javaExpressionService
						.mapToExpression(condition.getValue().getValueStmt());
				final Through through = condition.getThrough();

				if (through != null && through.getValue() != null && through.getValue().getValueStmt() != null) {
					final String toExpr = javaExpressionService
							.mapToExpression(through.getValue().getValueStmt());
					if (condition.isNot()) {
						rc.p("!(");
					}
					if (selectIsString || selectIsGroup) {
						final String throughSelectExpr = selectIsGroup
								? "CobolMove.groupToString(" + selectExpr + ")"
								: selectExpr;
						rc.p("CobolComparison.compareAlphanumeric(");
						rc.p(throughSelectExpr);
						rc.p(", ");
						rc.p(fromExpr);
						rc.p(") >= 0 && CobolComparison.compareAlphanumeric(");
						rc.p(throughSelectExpr);
						rc.p(", ");
						rc.p(toExpr);
						rc.p(") <= 0");
					} else {
						rc.p(selectExpr);
						rc.p(".compareTo(");
						rc.p(fromExpr);
						rc.p(") >= 0 && ");
						rc.p(selectExpr);
						rc.p(".compareTo(");
						rc.p(toExpr);
						rc.p(") <= 0");
					}
					if (condition.isNot()) {
						rc.p(")");
					}
				} else {
					// Fallback: just compare to the from value
					if (condition.isNot()) {
						rc.p("!");
					}
					if (selectIsString || selectIsGroup) {
						final String fallbackSelectExpr = selectIsGroup
								? "CobolMove.groupToString(" + selectExpr + ")"
								: selectExpr;
						rc.p("(CobolComparison.compareAlphanumeric(");
						rc.p(fallbackSelectExpr);
						rc.p(", ");
						rc.p(fromExpr);
						rc.p(") == 0)");
					} else {
						rc.p("(");
						rc.p(selectExpr);
						rc.p(".compareTo(");
						rc.p(fromExpr);
						rc.p(") == 0)");
					}
				}
			} else {
				rc.p("true");
			}
			break;
		default:
			rc.p("true");
			break;
		}
	}

	/**
	 * Gets the integer digit count from the PIC clause of a numeric field
	 * referenced by the given ValueStmt. Returns null if the field cannot
	 * be resolved or has no PIC clause.
	 */
	private Integer getNumericIntegerDigits(final ValueStmt valueStmt) {
		final DataDescriptionEntry entry = dataDescriptionEntryService.getDataDescriptionEntry(valueStmt);
		if (entry instanceof DataDescriptionEntryGroup) {
			final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) entry;
			if (group.getPictureClause() != null) {
				final String picString = group.getPictureClause().getPictureString();
				if (picString != null) {
					return cobolPictureLengthService.getIntegerPartLength(picString);
				}
			}
		}
		return null;
	}

	@Override
	public Class<EvaluateStatementContext> from() {
		return EvaluateStatementContext.class;
	}

	protected void visitStatements(final List<Statement> statements, final RuleContext rc) {
		for (final Statement statement : statements) {
			rc.visit(statement.getCtx());
		}
	}
}
