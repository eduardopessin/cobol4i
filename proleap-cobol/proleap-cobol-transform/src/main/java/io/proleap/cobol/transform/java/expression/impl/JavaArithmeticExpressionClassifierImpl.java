package io.proleap.cobol.transform.java.expression.impl;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.valuestmt.ArithmeticValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.relation.ArithmeticComparison;
import io.proleap.cobol.asg.metamodel.valuestmt.relation.RelationalOperator;
import io.proleap.cobol.asg.metamodel.valuestmt.relation.RelationalOperator.RelationalOperatorType;
import io.proleap.cobol.commons.datadescription.CobolDataDescriptionEntryService;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.commons.value.CobolValueSpaceService;
import io.proleap.cobol.transform.java.expression.JavaArithmeticExpressionClassifier;

@Singleton
public class JavaArithmeticExpressionClassifierImpl implements JavaArithmeticExpressionClassifier {

	@Inject
	private CobolDataDescriptionEntryService cobolDataDescriptionEntryService;

	@Inject
	private CobolTypeService cobolTypeService;

	@Inject
	private CobolValueSpaceService cobolValueSpaceService;

	@Override
	public JavaArithmeticExpressionTypeEnum classify(final ArithmeticComparison arithmeticComparison) {
		final RelationalOperator operator = arithmeticComparison.getOperator();
		final RelationalOperatorType relationalOperatorType = operator.getRelationalOperatorType();
		final ArithmeticValueStmt arithmeticExpressionLeft = arithmeticComparison.getArithmeticExpressionLeft();
		final ArithmeticValueStmt arithmeticExpressionRight = arithmeticComparison.getArithmeticExpressionRight();

		final JavaArithmeticExpressionTypeEnum result;

		if (isComparisonBetweenGroupAndString(relationalOperatorType, arithmeticExpressionLeft,
				arithmeticExpressionRight)) {
			result = JavaArithmeticExpressionTypeEnum.COMPARISON_BETWEEN_GROUP_AND_STRING;
		} else if (isComparisonBetweenStringAndBlank(relationalOperatorType, arithmeticExpressionLeft,
				arithmeticExpressionRight)) {
			result = JavaArithmeticExpressionTypeEnum.COMPARISON_BETWEEN_STRING_AND_BLANK;
		} else if (isComparisonBetweenStringAndZeros(relationalOperatorType, arithmeticExpressionLeft,
				arithmeticExpressionRight)) {
			result = JavaArithmeticExpressionTypeEnum.COMPARISON_BETWEEN_STRING_AND_ZEROS;
		} else if (isComparisonBetweenStringAndLowValues(relationalOperatorType, arithmeticExpressionLeft,
				arithmeticExpressionRight)) {
			result = JavaArithmeticExpressionTypeEnum.COMPARISON_BETWEEN_STRING_AND_LOW_VALUES;
		} else if (isComparisonBetweenStringAndHighValues(relationalOperatorType, arithmeticExpressionLeft,
				arithmeticExpressionRight)) {
			result = JavaArithmeticExpressionTypeEnum.COMPARISON_BETWEEN_STRING_AND_HIGH_VALUES;
		} else if (isComparisonBetweenNumericAndString(arithmeticExpressionLeft, arithmeticExpressionRight)) {
			result = JavaArithmeticExpressionTypeEnum.COMPARISON_BETWEEN_NUMERIC_AND_STRING;
		} else {
			result = JavaArithmeticExpressionTypeEnum.DEFAULT;
		}

		return result;
	}

	protected boolean isComparisonBetweenGroupAndString(final RelationalOperatorType relationalOperatorType,
			final ArithmeticValueStmt arithmeticExpressionLeft, final ArithmeticValueStmt arithmeticExpressionRight) {
		// Check if LHS is a group
		final CobolTypeEnum leftType = cobolTypeService.getType(arithmeticExpressionLeft);
		if (CobolTypeEnum.DATA_DESCRIPTION_GROUP.equals(leftType)) {
			final DataDescriptionEntry dataDescriptionEntry = cobolDataDescriptionEntryService
					.getDataDescriptionEntry(arithmeticExpressionLeft);
			if (dataDescriptionEntry instanceof DataDescriptionEntryGroup) {
				final DataDescriptionEntryGroup typeGroup = (DataDescriptionEntryGroup) dataDescriptionEntry;
				final boolean hasDataDescriptionEntries = !typeGroup.getDataDescriptionEntries().isEmpty();
				if (hasDataDescriptionEntries) {
					return true;
				}
			}
		}

		// Check if RHS is a group (e.g., IF DATMOV > W-DATA-SOC where W-DATA-SOC is group)
		final CobolTypeEnum rightType = cobolTypeService.getType(arithmeticExpressionRight);
		if (CobolTypeEnum.DATA_DESCRIPTION_GROUP.equals(rightType)) {
			final DataDescriptionEntry dataDescriptionEntry = cobolDataDescriptionEntryService
					.getDataDescriptionEntry(arithmeticExpressionRight);
			if (dataDescriptionEntry instanceof DataDescriptionEntryGroup) {
				final DataDescriptionEntryGroup typeGroup = (DataDescriptionEntryGroup) dataDescriptionEntry;
				final boolean hasDataDescriptionEntries = !typeGroup.getDataDescriptionEntries().isEmpty();
				if (hasDataDescriptionEntries) {
					return true;
				}
			}
		}

		return false;
	}

	protected boolean isComparisonBetweenNumericAndString(
			final ArithmeticValueStmt arithmeticExpressionLeft, final ArithmeticValueStmt arithmeticExpressionRight) {
		final CobolTypeEnum leftType = cobolTypeService.getType(arithmeticExpressionLeft);
		final CobolTypeEnum rightType = cobolTypeService.getType(arithmeticExpressionRight);

		return (CobolTypeEnum.INTEGER.equals(leftType) || CobolTypeEnum.FLOAT.equals(leftType))
				&& CobolTypeEnum.STRING.equals(rightType)
			|| CobolTypeEnum.STRING.equals(leftType)
				&& (CobolTypeEnum.INTEGER.equals(rightType) || CobolTypeEnum.FLOAT.equals(rightType));
	}

	/**
	 * Detects comparison between an alphanumeric field and ZEROS figurative constant.
	 * In COBOL, "field = ZEROS" on PIC X means all characters are '0'.
	 */
	protected boolean isComparisonBetweenStringAndZeros(final RelationalOperatorType relationalOperatorType,
			final ArithmeticValueStmt arithmeticExpressionLeft, final ArithmeticValueStmt arithmeticExpressionRight) {
		if (RelationalOperatorType.EQUAL.equals(relationalOperatorType)
				|| RelationalOperatorType.NOT_EQUAL.equals(relationalOperatorType)) {
			final CobolTypeEnum leftType = cobolTypeService.getType(arithmeticExpressionLeft);
			// Only for alphanumeric or unknown-type (unresolved) fields
			if (CobolTypeEnum.STRING.equals(leftType) || leftType == null) {
				return cobolValueSpaceService.isZero(arithmeticExpressionRight);
			}
		}
		return false;
	}

	protected boolean isComparisonBetweenStringAndBlank(final RelationalOperatorType relationalOperatorType,
			final ArithmeticValueStmt arithmeticExpressionLeft, final ArithmeticValueStmt arithmeticExpressionRight) {
		final boolean result;

		if (RelationalOperatorType.EQUAL.equals(relationalOperatorType)
				|| RelationalOperatorType.NOT_EQUAL.equals(relationalOperatorType)) {
			final CobolTypeEnum leftType = cobolTypeService.getType(arithmeticExpressionLeft);
			// Treat as string-vs-blank when the left side is STRING, or when the type is
			// unknown (null — e.g., unresolved qualified field like DTA-CTT OF PARM000700).
			// An unresolved field compared to SPACES is almost certainly alphanumeric.
			final boolean receivingTypeIsStringOrUnknown = CobolTypeEnum.STRING.equals(leftType)
					|| (leftType == null);

			if (receivingTypeIsStringOrUnknown) {
				result = cobolValueSpaceService.isSpace(arithmeticExpressionRight);
			} else {
				result = false;
			}
		} else {
			result = false;
		}

		return result;
	}

	protected boolean isComparisonBetweenStringAndLowValues(final RelationalOperatorType relationalOperatorType,
			final ArithmeticValueStmt arithmeticExpressionLeft, final ArithmeticValueStmt arithmeticExpressionRight) {
		if (RelationalOperatorType.EQUAL.equals(relationalOperatorType)
				|| RelationalOperatorType.NOT_EQUAL.equals(relationalOperatorType)) {
			final CobolTypeEnum leftType = cobolTypeService.getType(arithmeticExpressionLeft);
			if (CobolTypeEnum.STRING.equals(leftType) || leftType == null) {
				return cobolValueSpaceService.isLowValue(arithmeticExpressionRight);
			}
		}
		return false;
	}

	protected boolean isComparisonBetweenStringAndHighValues(final RelationalOperatorType relationalOperatorType,
			final ArithmeticValueStmt arithmeticExpressionLeft, final ArithmeticValueStmt arithmeticExpressionRight) {
		if (RelationalOperatorType.EQUAL.equals(relationalOperatorType)
				|| RelationalOperatorType.NOT_EQUAL.equals(relationalOperatorType)) {
			final CobolTypeEnum leftType = cobolTypeService.getType(arithmeticExpressionLeft);
			if (CobolTypeEnum.STRING.equals(leftType) || leftType == null) {
				return cobolValueSpaceService.isHighValue(arithmeticExpressionRight);
			}
		}
		return false;
	}
}
