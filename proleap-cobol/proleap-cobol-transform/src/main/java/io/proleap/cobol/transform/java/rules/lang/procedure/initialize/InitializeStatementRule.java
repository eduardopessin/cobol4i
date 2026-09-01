package io.proleap.cobol.transform.java.rules.lang.procedure.initialize;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.InitializeStatementContext;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.call.Call.CallType;
import io.proleap.cobol.asg.metamodel.call.DataDescriptionEntryCall;
import io.proleap.cobol.asg.metamodel.call.TableCall;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry.DataDescriptionEntryType;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.procedure.initialize.InitializeStatement;
import io.proleap.cobol.asg.metamodel.Program;
import io.proleap.cobol.commons.datadescription.CobolPictureLengthService;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.transform.java.expression.JavaExpressionService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class InitializeStatementRule extends CobolTransformRule<InitializeStatementContext, InitializeStatement> {

	@Inject
	private JavaExpressionService javaExpressionService;

	@Inject
	private CobolPictureLengthService cobolPictureLengthService;

	@Inject
	private CobolTypeService cobolTypeService;

	@Override
	public void apply(final InitializeStatementContext ctx, final InitializeStatement initializeStatement,
			final RuleContext rc) {
		for (final Call dataItemCall : initializeStatement.getDataItemCalls()) {
			final String callExpr = javaExpressionService.mapToCall(dataItemCall);

			if (dataItemCall.getCallType() == CallType.DATA_DESCRIPTION_ENTRY_CALL
				|| dataItemCall.getCallType() == CallType.TABLE_CALL) {
				final DataDescriptionEntryCall ddeCall = (DataDescriptionEntryCall) dataItemCall.unwrap();
				final DataDescriptionEntry dde = ddeCall.getDataDescriptionEntry();

				// Check if the original DDE is a REDEFINES field.
				// For INITIALIZE, the COBOL semantics say we use the TYPE of the field
				// being initialized (the REDEFINES field), NOT the base field's type.
				// E.g., INITIALIZE lkTOKEN-NUM where lkTOKEN-NUM REDEFINES lkTOKEN PIC 9(12)
				// should set to ZEROS (numeric), not SPACES (alphanumeric).
				final boolean isRedefines = isRedefinesField(dde);

				// For type determination: use the original DDE (the REDEFINES field itself)
				// so that numeric REDEFINES fields get initialized to zeros.
				// For group/variable resolution: resolve to the base field (for non-REDEFINES path).
				final DataDescriptionEntry typeDde = dde;
				final DataDescriptionEntry resolvedDde = isRedefines ? dde : resolveRedefinesForInit(dde);

				// For REDEFINES fields, get the getter expression so we can build a setter
				final String redefinesGetter = isRedefines
						? javaExpressionService.getRedefinesGetterExpression(dataItemCall)
						: null;

				if (isTrueGroup(resolvedDde)) {
					rc.p("entityService.initialize(");
					rc.p(callExpr);
					rc.p(");");
					rc.pNl(initializeStatement);
				} else {
					// Use the original DDE type for REDEFINES, resolved DDE type otherwise
					final CobolTypeEnum type = cobolTypeService.getType(isRedefines ? typeDde : resolvedDde);
					// Check if callExpr is a getter (REDEFINES) — need to use setter instead
					final boolean isGetter = (redefinesGetter != null)
							|| (callExpr.contains("get") && callExpr.endsWith("()"));
					// Use the REDEFINES getter if available, otherwise use callExpr
					final String effectiveExpr = (redefinesGetter != null) ? redefinesGetter : callExpr;
					if (type == CobolTypeEnum.INTEGER || type == CobolTypeEnum.FLOAT) {
						if (isGetter) {
							final String setter = effectiveExpr.replaceFirst("get", "set").replace("()", "(BigDecimal.ZERO)");
							rc.p(setter + ";");
						} else {
							rc.p(callExpr);
							rc.p(" = BigDecimal.ZERO;");
						}
						rc.pNl(initializeStatement);
					} else if (type == CobolTypeEnum.BOOLEAN) {
						if (isGetter) {
							final String setter = effectiveExpr.replaceFirst("get", "set").replace("()", "(false)");
							rc.p(setter + ";");
						} else {
							rc.p(callExpr);
							rc.p(" = false;");
						}
						rc.pNl(initializeStatement);
					} else {
						final Integer picLength = cobolPictureLengthService.getLength(isRedefines ? typeDde : resolvedDde);
						if (isGetter) {
							final String value = (picLength != null && picLength > 0)
								? "CobolConstants.spaces(" + picLength + ")" : "\"\"";
							final String setter = effectiveExpr.replaceFirst("get", "set").replace("()", "(" + value + ")");
							rc.p(setter + ";");
						} else {
							rc.p(callExpr);
							if (picLength != null && picLength > 0) {
								rc.p(" = CobolConstants.spaces(%d);", picLength);
							} else {
								rc.p(" = \"\";");
							}
						}
						rc.pNl(initializeStatement);
					}
				}
			} else if (dataItemCall.getCallType() == CallType.SPECIAL_REGISTER_CALL) {
				// Special registers like SQLCODE, SQLSTATE are scalar fields.
				// SQLCODE is PIC S9(9) BINARY → BigDecimal; SQLSTATE is PIC X(5) → String.
				final String upperName = callExpr.toUpperCase();
				if (upperName.contains("SQLCODE") || upperName.contains("SQLERRD")) {
					rc.p(callExpr);
					rc.p(" = BigDecimal.ZERO;");
					rc.pNl(initializeStatement);
				} else if (upperName.contains("SQLSTATE")) {
					rc.p(callExpr);
					rc.p(" = \"00000\";");
					rc.pNl(initializeStatement);
				} else if (upperName.contains("SQLERRMC")) {
					rc.p(callExpr);
					rc.p(" = \"\";");
					rc.pNl(initializeStatement);
				} else {
					// Unknown special register — default to BigDecimal.ZERO (most are numeric)
					rc.p(callExpr);
					rc.p(" = BigDecimal.ZERO;");
					rc.pNl(initializeStatement);
				}
			} else {
				// Check if the unresolved call is a known SQLCA field name
				// (may not be classified as SPECIAL_REGISTER_CALL by the ASG)
				final String upperCallExpr = callExpr.toUpperCase();
				if (upperCallExpr.equals("SQLCA")) {
					// INITIALIZE SQLCA — reset all SQLCA fields to their initial values
					rc.p("sqlcaid = \"SQLCA   \"; sqlcabc = BigDecimal.ZERO; sqlcode = BigDecimal.ZERO; sqlstate = \"00000\"; sqlerrml = BigDecimal.ZERO; sqlerrmc = \"\"; sqlerrp = \"        \"; sqlerrd = new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO}; sqlwarn0 = \" \"; sqlwarn1 = \" \"; sqlwarn2 = \" \"; sqlwarn3 = \" \"; sqlwarn4 = \" \"; sqlwarn5 = \" \"; sqlwarn6 = \" \"; sqlwarn7 = \" \"; sqlwarn8 = \" \"; sqlwarn9 = \" \"; sqlwarna = \" \"; sqlwarn = \"           \";");
					rc.pNl(initializeStatement);
				} else if (upperCallExpr.equals("SQLCODE") || upperCallExpr.equals("SQLERRD")) {
					rc.p(callExpr);
					rc.p(" = BigDecimal.ZERO;");
					rc.pNl(initializeStatement);
				} else if (upperCallExpr.equals("SQLSTATE")) {
					rc.p(callExpr);
					rc.p(" = \"00000\";");
					rc.pNl(initializeStatement);
				} else if (upperCallExpr.equals("SQLERRMC")) {
					rc.p(callExpr);
					rc.p(" = \"\";");
					rc.pNl(initializeStatement);
				} else {
					rc.p("entityService.initialize(");
					rc.p(callExpr);
					rc.p(");");
					rc.pNl(initializeStatement);
				}
			}
		}
	}

	/**
	 * Checks whether the given DDE is a REDEFINES field (has a REDEFINES clause).
	 */
	private boolean isRedefinesField(final DataDescriptionEntry dde) {
		if (!(dde instanceof DataDescriptionEntryGroup)) {
			return false;
		}
		final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) dde;
		return group.getRedefinesClause() != null && group.getRedefinesClause().getRedefinesCall() != null;
	}

	/**
	 * If the DDE is a REDEFINES field, resolve to the original (redefined) entry.
	 * This ensures the initialization value matches the Java variable type, since
	 * REDEFINES fields map to the same Java variable as the base field.
	 */
	private DataDescriptionEntry resolveRedefinesForInit(final DataDescriptionEntry dde) {
		if (!(dde instanceof DataDescriptionEntryGroup)) {
			return dde;
		}
		final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) dde;
		if (group.getRedefinesClause() == null || group.getRedefinesClause().getRedefinesCall() == null) {
			return dde;
		}
		final String redefinesName = group.getRedefinesClause().getRedefinesCall().getName();
		final DataDescriptionEntryGroup parent = dde.getParentDataDescriptionEntryGroup();
		if (parent != null) {
			for (final DataDescriptionEntry sibling : parent.getDataDescriptionEntries()) {
				if (redefinesName.equalsIgnoreCase(sibling.getName())) {
					return sibling;
				}
			}
		} else {
			// Top-level REDEFINES - search in working-storage and linkage sections
			final Program prog = dde.getProgram();
			if (prog != null) {
				for (final var cu : prog.getCompilationUnits()) {
					for (final var pu : cu.getProgramUnits()) {
						if (pu.getDataDivision() != null) {
							final var ws = pu.getDataDivision().getWorkingStorageSection();
							if (ws != null) {
								final DataDescriptionEntry found = ws.getDataDescriptionEntry(redefinesName);
								if (found != null) { return found; }
							}
							final var ls = pu.getDataDivision().getLinkageSection();
							if (ls != null) {
								final DataDescriptionEntry found = ls.getDataDescriptionEntry(redefinesName);
								if (found != null) { return found; }
							}
						}
					}
				}
			}
		}
		return dde;
	}

	/**
	 * Determines if a DataDescriptionEntry is a true group item (has non-condition
	 * subordinate entries). Level-01 items with a PIC clause but no subordinate
	 * entries are classified as GROUP by the parser, but they are effectively scalar
	 * and must be initialized by direct assignment (BigDecimal is immutable).
	 */
	private boolean isTrueGroup(final DataDescriptionEntry dde) {
		if (!DataDescriptionEntryType.GROUP.equals(dde.getDataDescriptionEntryType())) {
			return false;
		}
		final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) dde;
		final List<DataDescriptionEntry> nonConditionEntries = group.getDataDescriptionEntries().stream()
				.filter(e -> e.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION)
				.collect(Collectors.toList());
		// If the item has subordinate non-condition entries, it is a true group
		// even if it also has a PIC clause (COBOL allows both, subordinates take precedence)
		if (!nonConditionEntries.isEmpty()) {
			return true;
		}
		// If the item has a PIC clause but no subordinates, it is a scalar
		// (the parser may classify level-01 with PIC as GROUP, but it's effectively scalar)
		if (group.getPictureClause() != null) {
			return false;
		}
		return false;
	}

	@Override
	public Class<InitializeStatementContext> from() {
		return InitializeStatementContext.class;
	}
}
