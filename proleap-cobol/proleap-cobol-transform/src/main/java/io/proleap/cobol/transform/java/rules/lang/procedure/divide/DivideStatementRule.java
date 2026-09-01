package io.proleap.cobol.transform.java.rules.lang.procedure.divide;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.DivideStatementContext;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.call.Call.CallType;
import io.proleap.cobol.asg.metamodel.call.DataDescriptionEntryCall;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.procedure.NotOnSizeErrorPhrase;
import io.proleap.cobol.asg.metamodel.procedure.OnSizeErrorPhrase;
import io.proleap.cobol.asg.metamodel.procedure.Statement;
import io.proleap.cobol.asg.metamodel.procedure.divide.DivideByGivingStatement;
import io.proleap.cobol.asg.metamodel.procedure.divide.DivideIntoGivingStatement;
import io.proleap.cobol.asg.metamodel.procedure.divide.DivideIntoStatement;
import io.proleap.cobol.asg.metamodel.procedure.divide.DivideStatement;
import io.proleap.cobol.asg.metamodel.procedure.divide.DivideStatement.DivideType;
import io.proleap.cobol.asg.metamodel.procedure.divide.Giving;
import io.proleap.cobol.asg.metamodel.procedure.divide.GivingPhrase;
import io.proleap.cobol.asg.metamodel.procedure.divide.Into;
import io.proleap.cobol.asg.metamodel.procedure.divide.Remainder;
import io.proleap.cobol.asg.metamodel.valuestmt.CallValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.ValueStmt;
import io.proleap.cobol.commons.datadescription.CobolPictureLengthService;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.transform.java.expression.JavaExpressionService;
import io.proleap.cobol.transform.java.identifier.variable.JavaVariableIdentifierService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class DivideStatementRule extends CobolTransformRule<DivideStatementContext, DivideStatement> {

	@Inject
	private JavaExpressionService javaExpressionService;

	@Inject
	private CobolTypeService cobolTypeService;

	@Inject
	private CobolPictureLengthService cobolPictureLengthService;

	@Inject
	private JavaVariableIdentifierService javaVariableIdentifierService;

	@Override
	public void apply(final DivideStatementContext ctx, final DivideStatement divideStatement, final RuleContext rc) {
		final OnSizeErrorPhrase onSizeErrorPhrase = divideStatement.getOnSizeErrorPhrase();
		final NotOnSizeErrorPhrase notOnSizeErrorPhrase = divideStatement.getNotOnSizeErrorPhrase();
		final boolean hasOnSizeError = onSizeErrorPhrase != null;

		if (hasOnSizeError) {
			rc.p("try {");
			rc.pNl();
			rc.getPrinter().indent();
		} else {
			// IBM ILE COBOL: DIVIDE by zero without ON SIZE ERROR does not crash.
			// The result field is set to zero. Wrap in try-catch to match AS/400 behaviour.
			rc.p("try {");
			rc.pNl();
			rc.getPrinter().indent();
		}

		final DivideType type = divideStatement.getDivideType();

		switch (type) {
		case BY_GIVING:
			printByGiving(divideStatement, rc);
			break;
		case INTO:
			printInto(divideStatement, rc);
			break;
		case INTO_GIVING:
			printIntoGiving(divideStatement, rc);
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
			rc.pNl(divideStatement);
		} else {
			// No ON SIZE ERROR: catch ArithmeticException silently (AS/400 behaviour)
			rc.getPrinter().unindent();
			rc.p("} catch (ArithmeticException _divZero) { /* COBOL: division by zero without ON SIZE ERROR */ }");
			rc.pNl(divideStatement);
		}
	}

	@Override
	public Class<DivideStatementContext> from() {
		return DivideStatementContext.class;
	}

	/**
	 * Prints a ValueStmt in read context, using mapToExpression which correctly
	 * resolves REDEFINES getters for numeric REDEFINES of alphanumeric fields.
	 */
	private void printReadExpr(final ValueStmt valueStmt, final RuleContext rc) {
		rc.p(javaExpressionService.mapToExpression(valueStmt));
	}

	/**
	 * Returns a read expression for a Call, using the REDEFINES getter if applicable.
	 */
	private String readExprForCall(final Call call) {
		final RedefinesInfo info = getNumericRedefinesOnCall(call);
		if (info != null) {
			return info.getterExpression;
		}
		return javaExpressionService.mapToCall(call);
	}

	protected void printByGiving(final DivideStatement divideStatement, final RuleContext rc) {
		final DivideByGivingStatement byGiving = divideStatement.getDivideByGivingStatement();
		final GivingPhrase givings = byGiving.getGivingPhrase();
		final Remainder remainder = divideStatement.getRemainder();

		if (remainder != null && givings != null && !givings.getGivings().isEmpty()) {
			// DIVIDE operand BY divisor GIVING target REMAINDER rem
			rc.p("{ BigDecimal[] _divResult = ");
			printReadExpr(divideStatement.getOperandValueStmt(), rc);
			rc.p(".divideAndRemainder(");
			printReadExpr(byGiving.getByValueStmt(), rc);
			rc.p(");");
			rc.pNl();

			for (final Giving giving : givings.getGivings()) {
				rc.visit(giving.getGivingCall().getCtx());
				rc.p(" = _divResult[0];");
				rc.pNl();
			}

			rc.visit(remainder.getRemainderCall().getCtx());
			rc.p(" = _divResult[1]; }");
			rc.pNl(divideStatement);
		} else if (givings == null) {
			// DIVIDE operand BY divisor (no GIVING — result stored back in operand)
			final RedefinesInfo redefInfo = getNumericRedefinesOnValueStmt(divideStatement.getOperandValueStmt());
			final int scale = getScaleForValueStmt(divideStatement.getOperandValueStmt());
			if (redefInfo != null) {
				rc.p("{ BigDecimal _numVal = ");
				printReadExpr(divideStatement.getOperandValueStmt(), rc);
				rc.p("; _numVal = _numVal.divide(");
				printReadExpr(byGiving.getByValueStmt(), rc);
				rc.p(", %d, RoundingMode.DOWN); ", scale);
				rc.visit(divideStatement.getOperandValueStmt().getCtx());
				rc.p(" = CobolMove.moveNumericToAlphanumeric(_numVal, %d); }", redefInfo.baseLength);
			} else {
				rc.visit(divideStatement.getOperandValueStmt().getCtx());
				rc.p(" = ");
				printReadExpr(divideStatement.getOperandValueStmt(), rc);
				rc.p(".divide(");
				printReadExpr(byGiving.getByValueStmt(), rc);
				rc.p(", %d, RoundingMode.DOWN);", scale);
			}

			rc.pNl(divideStatement);
		} else {
			// DIVIDE operand BY divisor GIVING targets (no remainder)
			for (final Giving giving : givings.getGivings()) {
				final String roundingMode = giving.isRounded() ? "RoundingMode.HALF_UP" : "RoundingMode.DOWN";
				final int scale = getScaleForCall(giving.getGivingCall());
				rc.visit(giving.getGivingCall().getCtx());
				rc.p(" = ");
				printReadExpr(divideStatement.getOperandValueStmt(), rc);
				rc.p(".divide(");
				printReadExpr(byGiving.getByValueStmt(), rc);
				rc.p(", %d, %s);", scale, roundingMode);

				rc.pNl(giving);
			}
		}
	}

	protected void printInto(final DivideStatement divideStatement, final RuleContext rc) {
		final DivideIntoStatement intoStatement = divideStatement.getDivideIntoStatement();
		final Remainder remainder = divideStatement.getRemainder();

		if (remainder != null && !intoStatement.getIntos().isEmpty()) {
			// DIVIDE operand INTO target REMAINDER rem
			final Into firstInto = intoStatement.getIntos().get(0);

			rc.p("{ BigDecimal[] _divResult = ");
			rc.p(readExprForCall(firstInto.getGivingCall()));
			rc.p(".divideAndRemainder(");
			printReadExpr(divideStatement.getOperandValueStmt(), rc);
			rc.p(");");
			rc.pNl();

			for (final Into into : intoStatement.getIntos()) {
				final RedefinesInfo intoRedefInfo = getNumericRedefinesOnCall(into.getGivingCall());
				if (intoRedefInfo != null) {
					rc.visit(into.getGivingCall().getCtx());
					rc.p(" = CobolMove.moveNumericToAlphanumeric(_divResult[0], %d);", intoRedefInfo.baseLength);
				} else {
					rc.visit(into.getGivingCall().getCtx());
					rc.p(" = _divResult[0];");
				}
				rc.pNl();
			}

			rc.visit(remainder.getRemainderCall().getCtx());
			rc.p(" = _divResult[1]; }");
			rc.pNl(divideStatement);
		} else {
			for (final Into into : intoStatement.getIntos()) {
				final RedefinesInfo redefInfo = getNumericRedefinesOnCall(into.getGivingCall());
				final String roundingMode = into.isRounded() ? "RoundingMode.HALF_UP" : "RoundingMode.DOWN";
				final int scale = getScaleForCall(into.getGivingCall());

				if (redefInfo != null) {
					// Target is a numeric REDEFINES of an alphanumeric field.
					// Read via getter, divide, format back to String.
					rc.p("{ BigDecimal _numVal = ");
					rc.p(readExprForCall(into.getGivingCall()));
					rc.p("; _numVal = _numVal.divide(");
					printReadExpr(divideStatement.getOperandValueStmt(), rc);
					rc.p(", %d, %s); ", scale, roundingMode);
					rc.visit(into.getGivingCall().getCtx());
					rc.p(" = CobolMove.moveNumericToAlphanumeric(_numVal, %d); }", redefInfo.baseLength);
				} else {
					rc.visit(into.getGivingCall().getCtx());
					rc.p(" = ");
					rc.visit(into.getGivingCall().getCtx());
					rc.p(".divide(");
					printReadExpr(divideStatement.getOperandValueStmt(), rc);
					rc.p(", %d, %s);", scale, roundingMode);
				}

				rc.pNl(into);
			}
		}
	}

	protected void printIntoGiving(final DivideStatement divideStatement, final RuleContext rc) {
		final DivideIntoGivingStatement intoGivingStatement = divideStatement.getDivideIntoGivingStatement();
		final GivingPhrase givings = intoGivingStatement.getGivingPhrase();
		final Remainder remainder = divideStatement.getRemainder();

		if (remainder != null && givings != null && !givings.getGivings().isEmpty()) {
			// DIVIDE operand INTO dividend GIVING target REMAINDER rem
			rc.p("{ BigDecimal[] _divResult = ");
			printReadExpr(intoGivingStatement.getIntoValueStmt(), rc);
			rc.p(".divideAndRemainder(");
			printReadExpr(divideStatement.getOperandValueStmt(), rc);
			rc.p(");");
			rc.pNl();

			for (final Giving giving : givings.getGivings()) {
				rc.visit(giving.getGivingCall().getCtx());
				rc.p(" = _divResult[0];");
				rc.pNl();
			}

			rc.visit(remainder.getRemainderCall().getCtx());
			rc.p(" = _divResult[1]; }");
			rc.pNl(divideStatement);
		} else if (givings == null) {
			// No GIVING — result stored back in the INTO field
			final RedefinesInfo redefInfo = getNumericRedefinesOnValueStmt(intoGivingStatement.getIntoValueStmt());
			final int scale = getScaleForValueStmt(intoGivingStatement.getIntoValueStmt());
			if (redefInfo != null) {
				rc.p("{ BigDecimal _numVal = ");
				printReadExpr(intoGivingStatement.getIntoValueStmt(), rc);
				rc.p("; _numVal = _numVal.divide(");
				printReadExpr(divideStatement.getOperandValueStmt(), rc);
				rc.p(", %d, RoundingMode.DOWN); ", scale);
				rc.visit(intoGivingStatement.getIntoValueStmt().getCtx());
				rc.p(" = CobolMove.moveNumericToAlphanumeric(_numVal, %d); }", redefInfo.baseLength);
			} else {
				rc.visit(intoGivingStatement.getIntoValueStmt().getCtx());
				rc.p(" = ");
				printReadExpr(intoGivingStatement.getIntoValueStmt(), rc);
				rc.p(".divide(");
				printReadExpr(divideStatement.getOperandValueStmt(), rc);
				rc.p(", %d, RoundingMode.DOWN);", scale);
			}

			rc.pNl(divideStatement);
		} else {
			// GIVING targets without remainder
			for (final Giving giving : givings.getGivings()) {
				final String roundingMode = giving.isRounded() ? "RoundingMode.HALF_UP" : "RoundingMode.DOWN";
				final int scale = getScaleForCall(giving.getGivingCall());
				rc.visit(giving.getGivingCall().getCtx());
				rc.p(" = ");
				printReadExpr(intoGivingStatement.getIntoValueStmt(), rc);
				rc.p(".divide(");
				printReadExpr(divideStatement.getOperandValueStmt(), rc);
				rc.p(", %d, %s);", scale, roundingMode);

				rc.pNl(giving);
			}
		}
	}

	// ---- REDEFINES detection helpers ----

	/**
	 * Checks whether a Call targets a numeric REDEFINES field whose base field is alphanumeric.
	 */
	private RedefinesInfo getNumericRedefinesOnCall(final Call call) {
		if (call == null) {
			return null;
		}
		final Call unwrapped = call.unwrap();
		if (unwrapped == null) {
			return null;
		}
		if (unwrapped.getCallType() != CallType.DATA_DESCRIPTION_ENTRY_CALL
				&& unwrapped.getCallType() != CallType.TABLE_CALL) {
			return null;
		}
		final DataDescriptionEntryCall ddec = (DataDescriptionEntryCall) unwrapped;
		return checkRedefines(ddec.getDataDescriptionEntry());
	}

	/**
	 * Checks whether a ValueStmt references a numeric REDEFINES of an alphanumeric base.
	 */
	private RedefinesInfo getNumericRedefinesOnValueStmt(final ValueStmt valueStmt) {
		if (!(valueStmt instanceof CallValueStmt)) {
			return null;
		}
		final Call call = ((CallValueStmt) valueStmt).getCall();
		return getNumericRedefinesOnCall(call);
	}

	private RedefinesInfo checkRedefines(final DataDescriptionEntry dde) {
		if (!(dde instanceof DataDescriptionEntryGroup)) {
			return null;
		}
		final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) dde;
		if (group.getRedefinesClause() == null || group.getRedefinesClause().getRedefinesCall() == null) {
			return null;
		}

		final CobolTypeEnum redefinesType = cobolTypeService.getType(dde);
		if (redefinesType != CobolTypeEnum.INTEGER && redefinesType != CobolTypeEnum.FLOAT) {
			return null;
		}

		final String origName = group.getRedefinesClause().getRedefinesCall().getName();
		final DataDescriptionEntry baseDde = findBaseDde(dde, origName);
		if (baseDde == null) {
			return null;
		}
		final CobolTypeEnum baseType = cobolTypeService.getType(baseDde);
		if (baseType != CobolTypeEnum.STRING) {
			return null;
		}

		final Integer length = cobolPictureLengthService.getLength(baseDde);
		if (length == null) {
			return null;
		}

		// Build getter expression using the REDEFINES field identifier
		final String variableId = javaVariableIdentifierService.mapToIdentifier(dde);
		final String getterName = "get" + Character.toUpperCase(variableId.charAt(0)) + variableId.substring(1);

		final String getterExpr;
		final DataDescriptionEntryGroup parentGroup = dde.getParentDataDescriptionEntryGroup();
		if (parentGroup != null) {
			// Nested REDEFINES: build parent path
			final String parentId = javaVariableIdentifierService.mapToIdentifier(parentGroup);
			getterExpr = parentId + "." + getterName + "()";
		} else {
			getterExpr = getterName + "()";
		}

		return new RedefinesInfo(length, getterExpr);
	}

	private DataDescriptionEntry findBaseDde(final DataDescriptionEntry redefinesDde, final String origName) {
		final DataDescriptionEntryGroup parent = redefinesDde.getParentDataDescriptionEntryGroup();
		if (parent != null) {
			for (final DataDescriptionEntry sibling : parent.getDataDescriptionEntries()) {
				if (origName.equalsIgnoreCase(sibling.getName())) {
					return sibling;
				}
			}
		} else {
			final io.proleap.cobol.asg.metamodel.Program prog = redefinesDde.getProgram();
			if (prog != null) {
				for (final var cu : prog.getCompilationUnits()) {
					for (final var pu : cu.getProgramUnits()) {
						if (pu.getDataDivision() != null) {
							final var ws = pu.getDataDivision().getWorkingStorageSection();
							if (ws != null) {
								final DataDescriptionEntry found = ws.getDataDescriptionEntry(origName);
								if (found != null) return found;
							}
							final var ls = pu.getDataDivision().getLinkageSection();
							if (ls != null) {
								final DataDescriptionEntry found = ls.getDataDescriptionEntry(origName);
								if (found != null) return found;
							}
						}
					}
				}
			}
		}
		return null;
	}

	/**
	 * Returns the scale for a ValueStmt target by extracting its underlying Call.
	 */
	private int getScaleForValueStmt(final ValueStmt valueStmt) {
		if (!(valueStmt instanceof CallValueStmt)) {
			return 0;
		}
		return getScaleForCall(((CallValueStmt) valueStmt).getCall());
	}

	/**
	 * Returns the number of decimal places (scale) for the target of a GIVING call,
	 * derived from its PIC clause's fractional part (V9(n)). Returns 0 if no
	 * fractional part is found or the call cannot be resolved.
	 */
	private int getScaleForCall(final Call call) {
		if (call == null) {
			return 0;
		}
		final Call unwrapped = call.unwrap();
		if (unwrapped == null || (unwrapped.getCallType() != CallType.DATA_DESCRIPTION_ENTRY_CALL
				&& unwrapped.getCallType() != CallType.TABLE_CALL)) {
			return 0;
		}
		final DataDescriptionEntryCall ddec = (DataDescriptionEntryCall) unwrapped;
		final DataDescriptionEntry dde = ddec.getDataDescriptionEntry();
		if (!(dde instanceof DataDescriptionEntryGroup)) {
			return 0;
		}
		final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) dde;
		final io.proleap.cobol.asg.metamodel.data.datadescription.PictureClause pic = group.getPictureClause();
		if (pic == null || pic.getPictureString() == null) {
			return 0;
		}
		final Integer fractional = cobolPictureLengthService.getFractionalPartLength(pic.getPictureString());
		return fractional != null ? fractional : 0;
	}

	private static class RedefinesInfo {
		final int baseLength;
		final String getterExpression;

		RedefinesInfo(final int baseLength, final String getterExpression) {
			this.baseLength = baseLength;
			this.getterExpression = getterExpression;
		}
	}
}
