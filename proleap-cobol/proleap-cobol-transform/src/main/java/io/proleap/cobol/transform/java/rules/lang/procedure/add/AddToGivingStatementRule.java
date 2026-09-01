package io.proleap.cobol.transform.java.rules.lang.procedure.add;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.AddToGivingStatementContext;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.procedure.add.AddToGivingStatement;
import io.proleap.cobol.asg.metamodel.procedure.add.From;
import io.proleap.cobol.asg.metamodel.procedure.add.Giving;
import io.proleap.cobol.asg.metamodel.procedure.add.ToGiving;
import io.proleap.cobol.commons.datadescription.CobolDataDescriptionEntryService;
import io.proleap.cobol.commons.datadescription.CobolPictureLengthService;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.transform.java.expression.JavaExpressionService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class AddToGivingStatementRule extends CobolTransformRule<AddToGivingStatementContext, AddToGivingStatement> {

	@Inject
	private CobolTypeService cobolTypeService;

	@Inject
	private CobolDataDescriptionEntryService dataDescriptionEntryService;

	@Inject
	private CobolPictureLengthService cobolPictureLengthService;

	@Inject
	private JavaExpressionService javaExpressionService;

	@Override
	public void apply(final AddToGivingStatementContext ctx, final AddToGivingStatement addToGivingStatement,
			final RuleContext rc) {
		final List<From> froms = addToGivingStatement.getFroms();
		final List<ToGiving> tos = addToGivingStatement.getTos();
		final List<Giving> givings = addToGivingStatement.getGivings();

		for (final Giving giving : givings) {
			printGiving(froms, tos, giving, rc);
		}
	}

	@Override
	public Class<AddToGivingStatementContext> from() {
		return AddToGivingStatementContext.class;
	}

	protected void printGiving(final List<From> froms, final List<ToGiving> tos, final Giving giving,
			final RuleContext rc) {
		final Call givingGiving = giving.getGivingCall();

		// Check if the GIVING target is alphanumeric (String)
		final CobolTypeEnum givingType = givingGiving != null ? cobolTypeService.getType(givingGiving) : null;
		final boolean givingIsAlphanumeric = CobolTypeEnum.STRING.equals(givingType);

		// Resolve the target's PIC to determine integer/decimal digit counts
		final DataDescriptionEntry givingEntry = givingGiving != null
				? dataDescriptionEntryService.getDataDescriptionEntry(givingGiving)
				: null;
		final Integer intDigits = getIntegerDigits(givingEntry);
		final Integer decDigits = getDecimalDigits(givingEntry);
		final boolean wrapWithMove = !givingIsAlphanumeric && intDigits != null && decDigits != null;
		final boolean rounded = giving.isRounded();

		// Check if the GIVING target is a child of a group-over-elementary REDEFINES,
		// or an elementary-over-elementary REDEFINES (numeric REDEFINES of alphanumeric).
		// If so, we must use a setter method instead of field assignment, because
		// getters cannot be used as assignment targets in Java.
		String setterPrefix = givingGiving != null
				? javaExpressionService.getGroupOverElementarySetterPrefix(givingGiving)
				: null;

		// If not group-over-elementary, check for elementary REDEFINES setter
		if (setterPrefix == null && givingGiving != null) {
			setterPrefix = javaExpressionService.getRedefinesSetterPrefix(givingGiving);
		}

		if (setterPrefix != null) {
			// Emit: setterPrefix(computedValue);
			rc.p("%s(", setterPrefix);
		} else {
			if (givingGiving != null) {
				rc.visit(givingGiving.getCtx());
			}
			rc.p(" = ");
		}

		if (givingIsAlphanumeric) {
			// Target is alphanumeric: wrap result with moveNumericToAlphanumeric
			final Integer targetLength = cobolPictureLengthService.getLength(givingEntry);
			final int len = targetLength != null ? targetLength : 18;
			rc.p("CobolMove.moveNumericToAlphanumeric(");

			printAddOperands(froms, tos, rc);

			rc.p(", %d)", len);
		} else {
			if (wrapWithMove) {
				if (rounded) {
					rc.p("CobolMove.moveNumericToNumericRounded(");
				} else {
					rc.p("CobolMove.moveNumericToNumeric(");
				}
			}

			printAddOperands(froms, tos, rc);

			if (wrapWithMove) {
				rc.p(", %d, %d)", intDigits, decDigits);
			}
		}

		if (setterPrefix != null) {
			rc.p(")");
		}

		rc.p(";");
		rc.pNl(giving);
	}

	private void printAddOperands(final List<From> froms, final List<ToGiving> tos, final RuleContext rc) {
		final List<String> operandExprs = new ArrayList<>();

		for (final From from : froms) {
			if (from.getFromValueStmt() != null) {
				String expr = javaExpressionService.mapToExpression(from.getFromValueStmt());
				if (expr != null && !expr.isEmpty()) {
					final CobolTypeEnum fromType = cobolTypeService.getType(from.getFromValueStmt());
					if (fromType == CobolTypeEnum.STRING) {
						expr = "CobolMove.toBigDecimal(" + expr + ")";
					}
					operandExprs.add(expr);
				}
			}
		}

		for (final ToGiving to : tos) {
			if (to.getToValueStmt() != null) {
				String expr = javaExpressionService.mapToExpression(to.getToValueStmt());
				if (expr != null && !expr.isEmpty()) {
					final CobolTypeEnum toType = cobolTypeService.getType(to.getToValueStmt());
					if (toType == CobolTypeEnum.STRING) {
						expr = "CobolMove.toBigDecimal(" + expr + ")";
					}
					operandExprs.add(expr);
				}
			}
		}

		if (operandExprs.isEmpty()) {
			rc.p("BigDecimal.ZERO");
		} else {
			rc.p(operandExprs.get(0));
			for (int i = 1; i < operandExprs.size(); i++) {
				rc.p(".add(%s)", operandExprs.get(i));
			}
		}
	}

	private Integer getIntegerDigits(final DataDescriptionEntry entry) {
		if (entry == null) {
			return null;
		}
		final String picString = getPictureString(entry);
		if (picString == null) {
			return null;
		}
		return cobolPictureLengthService.getIntegerPartLength(picString);
	}

	private Integer getDecimalDigits(final DataDescriptionEntry entry) {
		if (entry == null) {
			return null;
		}
		final String picString = getPictureString(entry);
		if (picString == null) {
			return null;
		}
		return cobolPictureLengthService.getFractionalPartLength(picString);
	}

	private String getPictureString(final DataDescriptionEntry entry) {
		if (entry instanceof DataDescriptionEntryGroup) {
			final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) entry;
			if (group.getPictureClause() != null) {
				return group.getPictureClause().getPictureString();
			}
		}
		return null;
	}
}
