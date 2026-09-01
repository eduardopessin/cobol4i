package io.proleap.cobol.transform.java.rules.lang.procedure.subtract;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.SubtractStatementContext;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.procedure.NotOnSizeErrorPhrase;
import io.proleap.cobol.asg.metamodel.procedure.OnSizeErrorPhrase;
import io.proleap.cobol.asg.metamodel.procedure.Statement;
import io.proleap.cobol.asg.metamodel.procedure.subtract.Giving;
import io.proleap.cobol.asg.metamodel.procedure.subtract.Minuend;
import io.proleap.cobol.asg.metamodel.procedure.subtract.MinuendGiving;
import io.proleap.cobol.asg.metamodel.procedure.subtract.SubtractFromGivingStatement;
import io.proleap.cobol.asg.metamodel.procedure.subtract.SubtractFromStatement;
import io.proleap.cobol.asg.metamodel.procedure.subtract.SubtractStatement;
import io.proleap.cobol.asg.metamodel.procedure.subtract.SubtractStatement.SubtractType;
import io.proleap.cobol.asg.metamodel.procedure.subtract.Subtrahend;
import io.proleap.cobol.asg.metamodel.valuestmt.ValueStmt;
import io.proleap.cobol.commons.datadescription.CobolDataDescriptionEntryService;
import io.proleap.cobol.commons.datadescription.CobolPictureLengthService;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.transform.java.expression.JavaExpressionService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class SubtractStatementRule extends CobolTransformRule<SubtractStatementContext, SubtractStatement> {

	@Inject
	private CobolDataDescriptionEntryService dataDescriptionEntryService;

	@Inject
	private CobolPictureLengthService cobolPictureLengthService;

	@Inject
	private CobolTypeService cobolTypeService;

	@Inject
	private JavaExpressionService javaExpressionService;

	@Override
	public void apply(final SubtractStatementContext ctx, final SubtractStatement subtractStatement,
			final RuleContext rc) {
		final OnSizeErrorPhrase onSizeErrorPhrase = subtractStatement.getOnSizeErrorPhrase();
		final NotOnSizeErrorPhrase notOnSizeErrorPhrase = subtractStatement.getNotOnSizeErrorPhrase();
		final boolean hasOnSizeError = onSizeErrorPhrase != null;

		if (hasOnSizeError) {
			rc.p("try {");
			rc.pNl();
			rc.getPrinter().indent();
		}

		final SubtractType type = subtractStatement.getSubtractType();

		switch (type) {
		case CORRESPONDING:
			printCorresponding(subtractStatement, rc);
			break;
		case FROM:
			printSubtractFrom(subtractStatement, rc);
			break;
		case FROM_GIVING:
			printSubtractFromGiving(subtractStatement, rc);
			break;
		default:
			break;
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
			rc.pNl(subtractStatement);
		}
	}

	@Override
	public Class<SubtractStatementContext> from() {
		return SubtractStatementContext.class;
	}

	protected void printCorresponding(final SubtractStatement subtractStatement, final RuleContext rc) {

	}

	protected void printGiving(final RuleContext rc, final SubtractFromGivingStatement subtractFromGiving,
			final Giving giving) {
		final Call givingCall = giving.getGivingCall();

		// Check if the GIVING target is alphanumeric (String)
		final CobolTypeEnum givingType = givingCall != null ? cobolTypeService.getType(givingCall) : null;
		final boolean givingIsAlphanumeric = CobolTypeEnum.STRING.equals(givingType);

		// Check if the minuend (FROM operand) is alphanumeric
		final MinuendGiving minuendObj = subtractFromGiving.getMinuend();
		final ValueStmt minuendValueStmt = minuendObj != null ? minuendObj.getMinuendValueStmt() : null;
		final CobolTypeEnum minuendType = minuendValueStmt != null ? cobolTypeService.getType(minuendValueStmt) : null;
		final boolean minuendIsAlphanumeric = CobolTypeEnum.STRING.equals(minuendType);

		// Resolve the target's PIC to determine integer/decimal digit counts
		final DataDescriptionEntry givingEntry = givingCall != null
				? dataDescriptionEntryService.getDataDescriptionEntry(givingCall)
				: null;
		final Integer intDigits = getIntegerDigits(givingEntry);
		final Integer decDigits = getDecimalDigits(givingEntry);
		final boolean wrapWithMove = intDigits != null && decDigits != null;
		final boolean rounded = giving.isRounded();

		// Check if the GIVING target is a REDEFINES field requiring setter
		String setterPrefix = givingCall != null
				? javaExpressionService.getGroupOverElementarySetterPrefix(givingCall)
				: null;
		if (setterPrefix == null && givingCall != null) {
			setterPrefix = javaExpressionService.getRedefinesSetterPrefix(givingCall);
		}

		if (setterPrefix != null) {
			rc.p("%s(", setterPrefix);
		} else {
			if (givingCall != null) {
				rc.visit(givingCall.getCtx());
			}
			rc.p(" = ");
		}

		if (givingIsAlphanumeric) {
			// Target is alphanumeric: wrap result with moveNumericToAlphanumeric
			final Integer targetLength = cobolPictureLengthService.getLength(givingEntry);
			final int len = targetLength != null ? targetLength : 18;
			rc.p("CobolMove.moveNumericToAlphanumeric(");

			// Minuend (FROM operand)
			if (minuendIsAlphanumeric) {
				rc.p("CobolMove.moveAlphanumericToNumeric(");
				if (minuendValueStmt != null) {
					rc.visit(minuendValueStmt.getCtx());
				}
				rc.p(", 18, 0)");
			} else if (minuendValueStmt != null) {
				rc.visit(minuendValueStmt.getCtx());
			}

			// Subtrahends
			for (final Subtrahend subtrahend : subtractFromGiving.getSubtrahends()) {
				rc.p(".subtract(");
				final ValueStmt subtrahendValueStmt = subtrahend.getSubtrahendValueStmt();
				final CobolTypeEnum subtrahendType = subtrahendValueStmt != null
						? cobolTypeService.getType(subtrahendValueStmt) : null;
				if (CobolTypeEnum.STRING.equals(subtrahendType)) {
					rc.p("CobolMove.moveAlphanumericToNumeric(");
					if (subtrahendValueStmt != null) {
						rc.visit(subtrahendValueStmt.getCtx());
					}
					rc.p(", 18, 0)");
				} else if (subtrahendValueStmt != null) {
					rc.visit(subtrahendValueStmt.getCtx());
				}
				rc.p(")");
			}

			rc.p(", %d)", len);
		} else {
			if (wrapWithMove) {
				if (rounded) {
					rc.p("CobolMove.moveNumericToNumericRounded(");
				} else {
					rc.p("CobolMove.moveNumericToNumeric(");
				}
			}

			// Minuend (FROM operand)
			if (minuendIsAlphanumeric) {
				rc.p("CobolMove.moveAlphanumericToNumeric(");
				if (minuendValueStmt != null) {
					rc.visit(minuendValueStmt.getCtx());
				}
				rc.p(", 18, 0)");
			} else if (minuendValueStmt != null) {
				rc.visit(minuendValueStmt.getCtx());
			}

			for (final Subtrahend subtrahend : subtractFromGiving.getSubtrahends()) {
				rc.p(".subtract(");
				final ValueStmt subtrahendValueStmt = subtrahend.getSubtrahendValueStmt();
				final CobolTypeEnum subtrahendType = subtrahendValueStmt != null
						? cobolTypeService.getType(subtrahendValueStmt) : null;
				if (CobolTypeEnum.STRING.equals(subtrahendType)) {
					rc.p("CobolMove.moveAlphanumericToNumeric(");
					if (subtrahendValueStmt != null) {
						rc.visit(subtrahendValueStmt.getCtx());
					}
					rc.p(", 18, 0)");
				} else if (subtrahendValueStmt != null) {
					rc.visit(subtrahendValueStmt.getCtx());
				}
				rc.p(")");
			}

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

	protected void printSubtractFrom(final SubtractStatement subtractStatement, final RuleContext rc) {
		final SubtractFromStatement from = subtractStatement.getSubtractFromStatement();

		for (final Minuend minuend : from.getMinuends()) {
			final Call minuendCall = minuend.getMinuendCall();

			// Check if the minuend target is an alphanumeric (String) field used in arithmetic.
			// If so, we must convert to numeric, subtract, and convert back.
			final CobolTypeEnum minuendType = minuendCall != null ? cobolTypeService.getType(minuendCall) : null;
			final boolean targetIsAlphanumeric = CobolTypeEnum.STRING.equals(minuendType);

			if (targetIsAlphanumeric && minuendCall != null) {
				// Alphanumeric target: target = moveNumericToAlphanumeric(moveAlphanumericToNumeric(target).subtract(subtrahend), targetLen)
				final Integer targetLength = minuendCall != null
						? cobolPictureLengthService.getLength(dataDescriptionEntryService.getDataDescriptionEntry(minuendCall))
						: null;
				final int len = targetLength != null ? targetLength : 18;
				rc.visit(minuendCall.getCtx());
				rc.p(" = CobolMove.moveNumericToAlphanumeric(CobolMove.moveAlphanumericToNumeric(");
				rc.visit(minuendCall.getCtx());
				rc.p(", 18, 0)");
				for (final Subtrahend subtrahend : from.getSubtrahends()) {
					rc.p(".subtract(");
					final ValueStmt subtrahendValueStmt = subtrahend.getSubtrahendValueStmt();
					if (subtrahendValueStmt != null) {
						rc.visit(subtrahendValueStmt.getCtx());
					}
					rc.p(")");
				}
				rc.p(", %d);", len);
				rc.pNl(minuend);
				continue;
			}

			// Check if the minuend is a group-over-elementary or elementary REDEFINES.
			// If so, the getter/setter pattern must be used instead of direct field access.
			String setterPrefix = minuendCall != null
					? javaExpressionService.getGroupOverElementarySetterPrefix(minuendCall)
					: null;
			String getterExpr = minuendCall != null
					? javaExpressionService.getGroupOverElementaryGetterExpression(minuendCall)
					: null;
			boolean useRedefinesSetter = setterPrefix != null && getterExpr != null;

			if (!useRedefinesSetter && minuendCall != null) {
				final String elemSetterPrefix = javaExpressionService.getRedefinesSetterPrefix(minuendCall);
				if (elemSetterPrefix != null) {
					setterPrefix = elemSetterPrefix;
					// For elementary REDEFINES, the getter is the same as the setter but with "get" prefix
					final String redefinesGetterExpr = javaExpressionService.getRedefinesGetterExpression(minuendCall);
					getterExpr = redefinesGetterExpr;
					useRedefinesSetter = setterPrefix != null;
				}
			}

			// Resolve PIC for moveNumericToNumeric wrapping
			final DataDescriptionEntry minuendEntry = minuendCall != null
					? dataDescriptionEntryService.getDataDescriptionEntry(minuendCall)
					: null;
			final Integer intDigits = getIntegerDigits(minuendEntry);
			final Integer decDigits = getDecimalDigits(minuendEntry);
			final boolean wrapWithMove = intDigits != null && decDigits != null;

			if (useRedefinesSetter) {
				rc.p(setterPrefix + "(");
				if (wrapWithMove) {
					rc.p("CobolMove.moveNumericToNumeric(");
				}
				rc.p(getterExpr);
			} else {
				if (minuendCall != null) {
					rc.visit(minuendCall.getCtx());
				}

				rc.p(" = ");

				if (wrapWithMove) {
					rc.p("CobolMove.moveNumericToNumeric(");
				}

				if (minuendCall != null) {
					rc.visit(minuendCall.getCtx());
				}
			}

			for (final Subtrahend subtrahend : from.getSubtrahends()) {
				rc.p(".subtract(");

				final ValueStmt subtrahendValueStmt = subtrahend.getSubtrahendValueStmt();

				if (subtrahendValueStmt != null) {
					rc.visit(subtrahendValueStmt.getCtx());
				}

				rc.p(")");
			}

			if (wrapWithMove) {
				rc.p(", %d, %d)", intDigits, decDigits);
			}

			if (useRedefinesSetter) {
				rc.p(")");
			}

			rc.p(";");
			rc.pNl(minuend);
		}
	}

	protected void printSubtractFromGiving(final SubtractStatement subtractStatement, final RuleContext rc) {
		final SubtractFromGivingStatement subtractFromGiving = subtractStatement.getSubtractFromGivingStatement();

		for (final Giving giving : subtractFromGiving.getGivings()) {
			printGiving(rc, subtractFromGiving, giving);
		}
	}
}
