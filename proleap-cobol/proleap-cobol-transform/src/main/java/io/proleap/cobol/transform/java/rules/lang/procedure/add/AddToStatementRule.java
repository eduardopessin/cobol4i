package io.proleap.cobol.transform.java.rules.lang.procedure.add;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.AddToStatementContext;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.call.Call.CallType;
import io.proleap.cobol.asg.metamodel.call.DataDescriptionEntryCall;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.procedure.add.AddToStatement;
import io.proleap.cobol.asg.metamodel.procedure.add.From;
import io.proleap.cobol.asg.metamodel.procedure.add.To;
import io.proleap.cobol.commons.datadescription.CobolPictureLengthService;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.transform.java.expression.JavaExpressionService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class AddToStatementRule extends CobolTransformRule<AddToStatementContext, AddToStatement> {

	@Inject
	private CobolTypeService cobolTypeService;

	@Inject
	private CobolPictureLengthService cobolPictureLengthService;

	@Inject
	private JavaExpressionService javaExpressionService;

	@Override
	public void apply(final AddToStatementContext ctx, final AddToStatement addToStatement, final RuleContext rc) {
		final List<From> froms = addToStatement.getFroms();
		final List<To> tos = addToStatement.getTos();

		for (final From from : froms) {
			for (final To to : tos) {
				printTo(from, to, rc);
			}
		}
	}

	@Override
	public Class<AddToStatementContext> from() {
		return AddToStatementContext.class;
	}

	protected void printTo(final From from, final To to, final RuleContext rc) {
		final RedefinesInfo redefInfo = getNumericRedefinesOnAlphanumeric(to.getToCall());

		// Check if FROM is a REDEFINES field (numeric redefines of alphanumeric) — if so,
		// use the getter expression to get the BigDecimal value instead of the raw String field.
		final String fromRedefinesGetter = getRedefinesGetterForFrom(from);

		// Check if FROM is alphanumeric (String) — if so, we need to wrap it in
		// CobolMove.toBigDecimal() so .add() receives a BigDecimal, not a String.
		final boolean fromIsAlphanumeric = isFromAlphanumeric(from);

		if (redefInfo != null) {
			// Target is a numeric REDEFINES of an alphanumeric field.
			// The expression resolves to the base String field, so we must
			// parse -> add -> format back to String.
			rc.p("{ BigDecimal _numVal = new BigDecimal(");
			rc.visit(to.getToCall().getCtx());
			rc.p(".trim()); _numVal = _numVal.add(");
			if (fromRedefinesGetter != null) {
				rc.p(fromRedefinesGetter);
			} else if (fromIsAlphanumeric) {
				rc.p("CobolMove.toBigDecimal(");
				rc.visit(from.getFromValueStmt().getCtx());
				rc.p(")");
			} else {
				rc.visit(from.getFromValueStmt().getCtx());
			}
			rc.p("); ");
			rc.visit(to.getToCall().getCtx());
			rc.p(" = CobolMove.moveNumericToAlphanumeric(_numVal, %d); }", redefInfo.baseLength);
			rc.pNl(from);
			return;
		}

		// Check if the target is a child of a group-over-elementary REDEFINES.
		// If so, the getter returns a value (not an lvalue), so we must use setter(getter.add(...)).
		final String getterExpr = javaExpressionService.getGroupOverElementaryGetterExpression(to.getToCall());
		final String setterPrefix = javaExpressionService.getGroupOverElementarySetterPrefix(to.getToCall());
		if (getterExpr != null && setterPrefix != null) {
			rc.p("%s(%s.add(", setterPrefix, getterExpr);
			if (fromRedefinesGetter != null) {
				rc.p(fromRedefinesGetter);
			} else if (fromIsAlphanumeric) {
				rc.p("CobolMove.toBigDecimal(");
				rc.visit(from.getFromValueStmt().getCtx());
				rc.p(")");
			} else {
				rc.visit(from.getFromValueStmt().getCtx());
			}
			rc.p("));");
			rc.pNl(from);
			return;
		}

		rc.visit(to.getToCall().getCtx());
		rc.p(" = ");
		rc.visit(to.getToCall().getCtx());
		rc.p(".add(");
		if (fromRedefinesGetter != null) {
			rc.p(fromRedefinesGetter);
		} else if (fromIsAlphanumeric) {
			rc.p("CobolMove.toBigDecimal(");
			rc.visit(from.getFromValueStmt().getCtx());
			rc.p(")");
		} else {
			rc.visit(from.getFromValueStmt().getCtx());
		}
		rc.p(");");
		rc.pNl(from);
	}

	/**
	 * Returns true if the FROM operand is alphanumeric (String in Java).
	 * This is used to wrap the operand in CobolMove.toBigDecimal() when it
	 * participates in arithmetic with a numeric (BigDecimal) target.
	 */
	private boolean isFromAlphanumeric(final From from) {
		if (from.getFromValueStmt() == null) {
			return false;
		}
		final CobolTypeEnum fromType = cobolTypeService.getType(from.getFromValueStmt());
		return fromType == CobolTypeEnum.STRING;
	}

	/**
	 * If the FROM value is a reference to a REDEFINES field, returns the getter
	 * expression (e.g., r_vr210300.lkvr210300.get(0).getRemise()).
	 * Returns null if it's not a REDEFINES reference.
	 */
	private String getRedefinesGetterForFrom(final From from) {
		if (from.getFromValueStmt() instanceof io.proleap.cobol.asg.metamodel.valuestmt.CallValueStmt) {
			final io.proleap.cobol.asg.metamodel.valuestmt.CallValueStmt callVs =
					(io.proleap.cobol.asg.metamodel.valuestmt.CallValueStmt) from.getFromValueStmt();
			return javaExpressionService.getRedefinesGetterExpression(callVs.getCall());
		}
		return null;
	}

	/**
	 * Detects when a Call targets a numeric REDEFINES field whose base (redefined)
	 * field is alphanumeric (String in Java). Returns info about the base field,
	 * or null if not applicable.
	 */
	private RedefinesInfo getNumericRedefinesOnAlphanumeric(final Call call) {
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
		final DataDescriptionEntry dde = ddec.getDataDescriptionEntry();
		if (!(dde instanceof DataDescriptionEntryGroup)) {
			return null;
		}
		final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) dde;
		if (group.getRedefinesClause() == null || group.getRedefinesClause().getRedefinesCall() == null) {
			return null;
		}

		// This DDE is a REDEFINES. Check if the target DDE is numeric.
		final CobolTypeEnum redefinesType = cobolTypeService.getType(dde);
		if (redefinesType != CobolTypeEnum.INTEGER && redefinesType != CobolTypeEnum.FLOAT) {
			return null;
		}

		// Find the base (redefined) field and check if it's alphanumeric (String).
		final String origName = group.getRedefinesClause().getRedefinesCall().getName();
		final DataDescriptionEntry baseDde = findBaseDde(dde, origName);
		if (baseDde == null) {
			return null;
		}
		final CobolTypeEnum baseType = cobolTypeService.getType(baseDde);
		if (baseType != CobolTypeEnum.STRING) {
			return null;
		}

		// Get the length of the base alphanumeric field
		final Integer length = cobolPictureLengthService.getLength(baseDde);
		if (length == null) {
			return null;
		}

		return new RedefinesInfo(length);
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
			// Top-level REDEFINES: search working storage and linkage sections
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

	private static class RedefinesInfo {
		final int baseLength;

		RedefinesInfo(final int baseLength) {
			this.baseLength = baseLength;
		}
	}
}
