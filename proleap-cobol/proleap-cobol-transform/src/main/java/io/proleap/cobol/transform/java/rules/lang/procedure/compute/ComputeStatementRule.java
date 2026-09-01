package io.proleap.cobol.transform.java.rules.lang.procedure.compute;

import java.math.BigDecimal;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.ComputeStatementContext;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.call.Call.CallType;
import io.proleap.cobol.asg.metamodel.call.DataDescriptionEntryCall;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry.DataDescriptionEntryType;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.procedure.NotOnSizeErrorPhrase;
import io.proleap.cobol.asg.metamodel.procedure.OnSizeErrorPhrase;
import io.proleap.cobol.asg.metamodel.procedure.Statement;
import io.proleap.cobol.asg.metamodel.procedure.compute.ComputeStatement;
import io.proleap.cobol.asg.metamodel.procedure.compute.Store;
import io.proleap.cobol.asg.metamodel.valuestmt.ArithmeticValueStmt;
import io.proleap.cobol.commons.datadescription.CobolDataDescriptionEntryService;
import io.proleap.cobol.commons.datadescription.CobolPictureLengthService;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.commons.value.CobolValueService;
import io.proleap.cobol.commons.value.CobolValueStmtService;
import io.proleap.cobol.commons.value.domain.CobolValue;
import io.proleap.cobol.transform.java.expression.JavaExpressionService;
import io.proleap.cobol.transform.java.util.JavaLiteralUtils;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class ComputeStatementRule extends CobolTransformRule<ComputeStatementContext, ComputeStatement> {

	@Inject
	private CobolValueService valueService;

	@Inject
	private CobolValueStmtService valueStmtService;

	@Inject
	private CobolDataDescriptionEntryService dataDescriptionEntryService;

	@Inject
	private CobolPictureLengthService cobolPictureLengthService;

	@Inject
	private CobolTypeService cobolTypeService;

	@Inject
	private JavaExpressionService javaExpressionService;

	@Override
	public void apply(final ComputeStatementContext ctx, final ComputeStatement computeStatement,
			final RuleContext rc) {
		final OnSizeErrorPhrase onSizeErrorPhrase = computeStatement.getOnSizeErrorPhrase();
		final NotOnSizeErrorPhrase notOnSizeErrorPhrase = computeStatement.getNotOnSizeErrorPhrase();
		final boolean hasOnSizeError = onSizeErrorPhrase != null;

		if (hasOnSizeError) {
			rc.p("try {");
			rc.pNl();
			rc.getPrinter().indent();
		}

		final ArithmeticValueStmt arithmeticExpression = computeStatement.getArithmeticExpression();

		final CobolValue value = valueStmtService.getValue(arithmeticExpression, null);
		final BigDecimal decimalValue = valueService.getDecimal(value);

		for (final Store store : computeStatement.getStores()) {
			// Resolve the target's PIC to determine integer/decimal digit counts
			final Call storeCall = store.getStoreCall();
			final DataDescriptionEntry storeEntry = dataDescriptionEntryService.getDataDescriptionEntry(storeCall);
			final Integer intDigits = getIntegerDigits(storeEntry);
			final Integer decDigits = getDecimalDigits(storeEntry);
			final boolean wrapWithMove = intDigits != null && decDigits != null;
			final boolean rounded = store.isRounded();

			// Check if the target is a child of a group-over-elementary REDEFINES.
			// If so, the getter returns a value (not an lvalue), so we must use setter(value).
			String getterExpr = javaExpressionService.getGroupOverElementaryGetterExpression(storeCall);
			String setterPrefix = javaExpressionService.getGroupOverElementarySetterPrefix(storeCall);
			boolean useRedefinesSetter = getterExpr != null && setterPrefix != null;

			// If not a group-over-elementary REDEFINES, check for elementary-over-elementary
			// REDEFINES (e.g., LKMTNTOTN REDEFINES LKMTNTOT PIC 9(09)).
			// But skip if the call has subscripts (TABLE_CALL) — the setter doesn't handle
			// subscript resolution, so we fall through to the alphanumeric conversion path.
			if (!useRedefinesSetter) {
				final Call unwrappedStore = storeCall != null ? storeCall.unwrap() : null;
				final boolean isTableCall = unwrappedStore != null
						&& unwrappedStore.getCallType() == CallType.TABLE_CALL;
				if (!isTableCall) {
					final String elemSetterPrefix = javaExpressionService.getRedefinesSetterPrefix(storeCall);
					if (elemSetterPrefix != null) {
						setterPrefix = elemSetterPrefix;
						useRedefinesSetter = true;
					}
				}
			}

			// Check if the target is an alphanumeric (String) field receiving a numeric
			// COMPUTE result. This happens with DDS output fields and non-REDEFINES PIC X
			// targets. In this case, use moveNumericToAlphanumeric instead.
			final boolean targetIsAlphanumeric = isTargetAlphanumeric(storeEntry);

			// Check if the source arithmetic expression resolves to an alphanumeric (String)
			// type. When COMPUTE <numeric-target> = <alphanumeric-source>, the alphanumeric
			// source must be converted to numeric before applying moveNumericToNumeric.
			// However, if the expression has arithmetic operations (+, -, *, /), the result
			// will already be BigDecimal because the expression generator wraps String operands.
			final CobolTypeEnum sourceType = cobolTypeService.getType(arithmeticExpression);
			final boolean hasArithmeticOps = (arithmeticExpression.getPlusMinus() != null && !arithmeticExpression.getPlusMinus().isEmpty())
					|| (arithmeticExpression.getMultDivs() != null && arithmeticExpression.getMultDivs().getMultDivs() != null && !arithmeticExpression.getMultDivs().getMultDivs().isEmpty());
			final boolean sourceIsAlphanumeric = CobolTypeEnum.STRING.equals(sourceType) && !hasArithmeticOps;

			if (useRedefinesSetter) {
				rc.p(setterPrefix + "(");
			} else {
				rc.visit(store.getCtx());
				rc.p(" = ");
			}

			if (wrapWithMove) {
				if (targetIsAlphanumeric && !useRedefinesSetter) {
					// Target is String but expression is numeric — use moveNumericToAlphanumeric.
					// For rounded, first use moveNumericToNumericRounded then convert to alpha.
					final int totalLen = (intDigits != null ? intDigits : 0) + (decDigits != null ? decDigits : 0);
					rc.p("CobolMove.moveNumericToAlphanumeric(");
					if (rounded) {
						rc.p("CobolMove.moveNumericToNumericRounded(");
					}

					if (sourceIsAlphanumeric) {
						rc.p("CobolMove.moveAlphanumericToNumeric(");
					}
					if (decimalValue != null) {
						rc.p(JavaLiteralUtils.mapToLiteral(decimalValue));
					} else {
						rc.visit(arithmeticExpression.getCtx());
					}
					if (sourceIsAlphanumeric) {
						rc.p(", %d, %d)", intDigits, decDigits);
					}

					if (rounded) {
						rc.p(", %d, %d)", intDigits, decDigits);
					}
					rc.p(", %d, %d)", totalLen, totalLen);
				} else if (sourceIsAlphanumeric && !targetIsAlphanumeric) {
					// Source is alphanumeric (String) but target is numeric.
					// Use moveAlphanumericToNumeric to convert String to BigDecimal.
					rc.p("CobolMove.moveAlphanumericToNumeric(");
					if (decimalValue != null) {
						rc.p(JavaLiteralUtils.mapToLiteral(decimalValue));
					} else {
						rc.visit(arithmeticExpression.getCtx());
					}
					rc.p(", %d, %d)", intDigits, decDigits);
				} else {
					if (rounded) {
						rc.p("CobolMove.moveNumericToNumericRounded(");
					} else {
						rc.p("CobolMove.moveNumericToNumeric(");
					}

					if (decimalValue != null) {
						rc.p(JavaLiteralUtils.mapToLiteral(decimalValue));
					} else {
						rc.visit(arithmeticExpression.getCtx());
					}

					rc.p(", %d, %d)", intDigits, decDigits);
				}
			} else if (targetIsAlphanumeric && !useRedefinesSetter) {
				// No PIC integer digits but target is String and expression is numeric.
				// Use moveNumericToAlphanumeric to format the value into the target field.
				final Integer targetLen = storeEntry != null ? cobolPictureLengthService.getLength(storeEntry) : null;
				if (targetLen != null) {
					rc.p("CobolMove.moveNumericToAlphanumeric(");
					if (sourceIsAlphanumeric) {
						// Source is also alphanumeric (String) — convert to BigDecimal first
						rc.p("CobolMove.moveAlphanumericToNumeric(");
					}
					if (decimalValue != null) {
						rc.p(JavaLiteralUtils.mapToLiteral(decimalValue));
					} else {
						rc.visit(arithmeticExpression.getCtx());
					}
					if (sourceIsAlphanumeric) {
						rc.p(", 18, 0)");
					}
					rc.p(", %d)", targetLen);
				} else {
					// Fallback: use toPlainString() to avoid compile error
					if (sourceIsAlphanumeric) {
						// Source is alphanumeric — wrap with moveAlphanumericToNumeric then toPlainString
						rc.p("CobolMove.moveAlphanumericToNumeric(");
						if (decimalValue != null) {
							rc.p(JavaLiteralUtils.mapToLiteral(decimalValue));
						} else {
							rc.visit(arithmeticExpression.getCtx());
						}
						rc.p(", 18, 0).toPlainString()");
					} else if (decimalValue != null) {
						rc.p("%s.toPlainString()", JavaLiteralUtils.mapToLiteral(decimalValue));
					} else {
						rc.p("(");
						rc.visit(arithmeticExpression.getCtx());
						rc.p(").toPlainString()");
					}
				}
			} else {
				if (decimalValue != null) {
					rc.p(JavaLiteralUtils.mapToLiteral(decimalValue));
				} else {
					rc.visit(arithmeticExpression.getCtx());
				}
			}

			if (useRedefinesSetter) {
				rc.p(")");
			}

			rc.p(";");
			rc.pNl(store);
		}

		if (notOnSizeErrorPhrase != null) {
			for (final Statement statement : notOnSizeErrorPhrase.getStatements()) {
				rc.visit(statement.getCtx());
			}
		}

		if (hasOnSizeError) {
			rc.getPrinter().unindent();
			rc.p("} catch (ArithmeticException e) {");
			rc.pNl();
			rc.getPrinter().indent();

			for (final Statement statement : onSizeErrorPhrase.getStatements()) {
				rc.visit(statement.getCtx());
			}

			rc.getPrinter().unindent();
			rc.p("}");
			rc.pNl(computeStatement);
		}
	}

	/**
	 * Checks if the target DataDescriptionEntry resolves to a String field in Java.
	 * This is true when:
	 * 1. The entry itself is alphanumeric (PIC X), or
	 * 2. The entry is a numeric REDEFINES of an alphanumeric base field (the Java
	 *    field assignment target is the base String field).
	 */
	private boolean isTargetAlphanumeric(final DataDescriptionEntry entry) {
		if (entry == null) {
			return false;
		}
		final CobolTypeEnum type = cobolTypeService.getType(entry);
		if (CobolTypeEnum.STRING.equals(type)) {
			return true;
		}
		// Check if this is a numeric REDEFINES of an alphanumeric base
		if (entry instanceof DataDescriptionEntryGroup) {
			final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) entry;
			if (group.getRedefinesClause() != null && group.getRedefinesClause().getRedefinesCall() != null) {
				final String baseName = group.getRedefinesClause().getRedefinesCall().getName();
				final DataDescriptionEntry baseDde = findBaseDde(entry, baseName);
				if (baseDde != null) {
					final CobolTypeEnum baseType = cobolTypeService.getType(baseDde);
					return CobolTypeEnum.STRING.equals(baseType);
				}
			}
		}
		return false;
	}

	/**
	 * Finds the base (redefined) DataDescriptionEntry by name among siblings.
	 */
	private DataDescriptionEntry findBaseDde(final DataDescriptionEntry redefinesDde, final String origName) {
		final DataDescriptionEntryGroup parent = redefinesDde.getParentDataDescriptionEntryGroup();
		if (parent != null) {
			for (final DataDescriptionEntry sibling : parent.getDataDescriptionEntries()) {
				if (origName.equalsIgnoreCase(sibling.getName())) {
					return sibling;
				}
			}
		}
		return null;
	}

	/**
	 * Gets the integer digit count from a DataDescriptionEntry's PIC clause.
	 */
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

	/**
	 * Gets the decimal digit count from a DataDescriptionEntry's PIC clause.
	 */
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

	/**
	 * Extracts the PIC string from a DataDescriptionEntry.
	 */
	private String getPictureString(final DataDescriptionEntry entry) {
		if (entry instanceof DataDescriptionEntryGroup) {
			final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) entry;
			if (group.getPictureClause() != null) {
				return group.getPictureClause().getPictureString();
			}
		}
		return null;
	}

	@Override
	public Class<ComputeStatementContext> from() {
		return ComputeStatementContext.class;
	}
}
