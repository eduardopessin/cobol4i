package io.proleap.cobol.commons.value.impl;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.asg.metamodel.valuestmt.ValueStmt;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.commons.value.CobolValueService;
import io.proleap.cobol.commons.value.CobolValueSpaceService;
import io.proleap.cobol.commons.value.CobolValueStmtService;
import io.proleap.cobol.commons.value.domain.CobolValue;

@Singleton
public class CobolValueSpaceServiceImpl implements CobolValueSpaceService {

	@Inject
	private CobolTypeService cobolTypeService;

	@Inject
	private CobolValueService valueService;

	@Inject
	private CobolValueStmtService valueStmtService;

	@Override
	public boolean isZero(final ValueStmt valueStmt) {
		final CobolTypeEnum type = cobolTypeService.getType(valueStmt);
		// ZEROS figurative constant is typed as INTEGER by the type service
		if (CobolTypeEnum.INTEGER.equals(type) || CobolTypeEnum.FLOAT.equals(type)) {
			final CobolValue value = valueStmtService.getValue(valueStmt, null);
			if (value != null) {
				// For DECIMAL-type CobolValues (ZEROS), use getDecimal
				final java.math.BigDecimal decimalValue = valueService.getDecimal(value);
				if (decimalValue != null) {
					return decimalValue.compareTo(java.math.BigDecimal.ZERO) == 0;
				}
				// Fallback: check string representation
				final String stringValue = valueService.getString(value);
				if (stringValue != null) {
					try {
						return new java.math.BigDecimal(stringValue).compareTo(java.math.BigDecimal.ZERO) == 0;
					} catch (final NumberFormatException e) {
						return false;
					}
				}
			}
		}
		return false;
	}

	@Override
	public boolean isLowValue(final ValueStmt valueStmt) {
		final CobolValue value = valueStmtService.getValue(valueStmt, null);
		return value != null && value.getType() == CobolValue.CobolValueType.LOW_VALUE;
	}

	@Override
	public boolean isHighValue(final ValueStmt valueStmt) {
		final CobolValue value = valueStmtService.getValue(valueStmt, null);
		return value != null && value.getType() == CobolValue.CobolValueType.HIGH_VALUE;
	}

	@Override
	public boolean isSpace(final ValueStmt valueStmt) {
		final CobolTypeEnum type = cobolTypeService.getType(valueStmt);
		final boolean result;

		if (!CobolTypeEnum.STRING.equals(type)) {
			result = false;
		} else {
			final CobolValue value = valueStmtService.getValue(valueStmt, null);
			final String stringValue = valueService.getString(value);
			result = value != null && (stringValue == null || stringValue.isBlank());
		}

		return result;
	}
}
