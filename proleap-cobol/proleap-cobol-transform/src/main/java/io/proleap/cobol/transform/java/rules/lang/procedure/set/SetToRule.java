package io.proleap.cobol.transform.java.rules.lang.procedure.set;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.math.BigDecimal;

import io.proleap.cobol.CobolParser.SetToStatementContext;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.call.Call.CallType;
import io.proleap.cobol.asg.metamodel.call.DataDescriptionEntryCall;
import io.proleap.cobol.asg.metamodel.call.SpecialRegisterCall;
import io.proleap.cobol.asg.metamodel.call.SpecialRegisterCall.SpecialRegisterType;
import io.proleap.cobol.asg.metamodel.call.TableCall;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry.DataDescriptionEntryType;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryCondition;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.data.datadescription.ValueClause;
import io.proleap.cobol.asg.metamodel.data.datadescription.ValueInterval;
import io.proleap.cobol.asg.metamodel.procedure.set.SetTo;
import io.proleap.cobol.asg.metamodel.procedure.set.To;
import io.proleap.cobol.asg.metamodel.procedure.set.Value;
import io.proleap.cobol.asg.metamodel.valuestmt.CallValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.Subscript;
import io.proleap.cobol.asg.metamodel.valuestmt.ValueStmt;
import io.proleap.cobol.commons.datadescription.CobolDataDescriptionEntryService;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.commons.value.CobolValueService;
import io.proleap.cobol.commons.value.CobolValueStmtService;
import io.proleap.cobol.commons.value.domain.CobolValue;
import io.proleap.cobol.transform.java.expression.JavaExpressionService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class SetToRule extends CobolTransformRule<SetToStatementContext, SetTo> {

	@Inject
	private CobolTypeService cobolTypeService;

	@Inject
	private CobolDataDescriptionEntryService dataDescriptionEntryService;

	@Inject
	private JavaExpressionService javaExpressionService;

	@Inject
	private CobolValueService valueService;

	@Inject
	private CobolValueStmtService valueStmtService;

	@Override
	public void apply(final SetToStatementContext ctx, final SetTo setTo, final RuleContext rc) {
		for (final To to : setTo.getTos()) {
			for (final Value value : setTo.getValues()) {
				// Skip ENTRY values (procedure pointer bindings) - not applicable in Java
				if (Value.ValueType.ENTRY.equals(value.getValueType())) {
					rc.p("// SET TO ENTRY skipped (procedure pointer)");
					rc.pNl();
					continue;
				}

				final Call toCall = to.getToCall();

				// Handle SET ADDRESS OF target TO source
				// In COBOL this sets a pointer; in Java we generate a cast assignment
				// e.g. nbrerro = (BigDecimal) errno_ptr;
				if (toCall.getCallType() == CallType.SPECIAL_REGISTER_CALL) {
					final SpecialRegisterCall srCall = (SpecialRegisterCall) toCall.unwrap();
					if (SpecialRegisterType.ADDRESS_OF.equals(srCall.getSpecialRegisterType())) {
						final Call innerCall = srCall.getIdentifierCall();
						if (innerCall != null) {
							rc.visit(innerCall.getCtx());
						} else {
							rc.visit(toCall.getCtx());
						}
						rc.p(" = ");
						// Resolve the target's Java type for casting (source is POINTER → Object)
						String castType = null;
						if (innerCall != null) {
							final Call unwrappedInner = innerCall.unwrap();
							if (unwrappedInner != null && unwrappedInner.getCallType() == CallType.DATA_DESCRIPTION_ENTRY_CALL) {
								final DataDescriptionEntryCall innerDdeCall = (DataDescriptionEntryCall) unwrappedInner;
								final DataDescriptionEntry innerDde = innerDdeCall.getDataDescriptionEntry();
								if (innerDde != null) {
									final CobolTypeEnum innerType = cobolTypeService.getType(innerDde);
									if (innerType != null) {
										switch (innerType) {
										case INTEGER:
										case FLOAT:
											castType = "BigDecimal";
											break;
										case STRING:
											castType = "String";
											break;
										case BOOLEAN:
											castType = "Boolean";
											break;
										default:
											break;
										}
									}
								}
							}
						}
						if (castType != null) {
							rc.p("(%s) ", castType);
						}
						rc.visit(value.getValueStmt().getCtx());
						rc.p(";");
						rc.pNl(toCall);
						continue;
					}
				}

				// Check if this is an 88-level condition SET TO TRUE/FALSE
				// TABLE_CALL is used when the condition is subscripted: SET IND-OFF(INDEX) TO TRUE
				if (toCall.getCallType() == CallType.DATA_DESCRIPTION_ENTRY_CALL
						|| toCall.getCallType() == CallType.TABLE_CALL) {
					final DataDescriptionEntryCall ddeCall = (DataDescriptionEntryCall) toCall.unwrap();
					final DataDescriptionEntry dde = ddeCall.getDataDescriptionEntry();

					if (DataDescriptionEntryType.CONDITION.equals(dde.getDataDescriptionEntryType())) {
						if (toCall.getCallType() == CallType.TABLE_CALL) {
							// Subscripted 88-level: SET IND-OFF(INDEX) TO TRUE
							// → parent[index - 1] = false
							printSubscriptedConditionSetTo(dde, (TableCall) toCall.unwrap(), rc);
						} else {
							// Check if the source text has subscripts that the parser didn't
							// capture as TABLE_CALL (e.g., SET TOPSEL-OFF OF LKBTOPSEL(WK-I) TO TRUE)
							final java.util.List<String> extractedSubs = extractSubscriptsFromContext(toCall);
							if (!extractedSubs.isEmpty()) {
								printConditionSetToWithSubscripts(dde, extractedSubs, rc);
							} else {
								printConditionSetTo(dde, rc);
							}
						}
						continue;
					}

					// Handle same-name collision: when ASG resolves SET W-EOF TO TRUE
					// to the parent field (77 W-EOF PIC 9) instead of the 88-level
					// condition (88 W-EOF VALUE 1), look for a same-name 88-level child.
					if (dde instanceof DataDescriptionEntryGroup) {
						final DataDescriptionEntryGroup parentGroup = (DataDescriptionEntryGroup) dde;
						final java.util.List<DataDescriptionEntry> children = parentGroup.getDataDescriptionEntries();
						// Look for any 88-level condition child — handles both
						// same-name collision (77 W-EOF / 88 W-EOF) and
						// SET TO TRUE where ASG resolves to the parent field.
						DataDescriptionEntry condChild = null;
						final String ddeNameUpper = dde.getName() != null ? dde.getName().toUpperCase() : null;
						for (final DataDescriptionEntry child : children) {
							if (DataDescriptionEntryType.CONDITION.equals(child.getDataDescriptionEntryType())) {
								// Prefer same-name match
								if (child.getName() != null
										&& child.getName().toUpperCase().equals(ddeNameUpper)) {
									condChild = child;
									break;
								}
								if (condChild == null) {
									condChild = child;
								}
							}
						}
						if (condChild != null) {
							// For SET TO TRUE, use the condition that represents TRUE (value 1)
							// For SET TO FALSE, use the condition that represents FALSE (value 0)
							// When there are multiple 88-levels (e.g., W-EOF/W-NOT-EOF),
							// pick the right one based on the value expression
							if (children.size() > 1) {
								// Check if the SET value expression contains TRUE or FALSE
								final String valText = value.getValueStmt() != null
										&& value.getValueStmt().getCtx() != null
										? value.getValueStmt().getCtx().getText().toUpperCase() : "";
								if (valText.contains("TRUE") || valText.contains("FALSE")) {
									// Find the matching condition by value
									for (final DataDescriptionEntry child : children) {
										if (DataDescriptionEntryType.CONDITION.equals(child.getDataDescriptionEntryType())
												&& child instanceof DataDescriptionEntryCondition) {
											final DataDescriptionEntryCondition condEntry = (DataDescriptionEntryCondition) child;
											final ValueClause vc = condEntry.getValueClause();
											if (vc != null && !vc.getValueIntervals().isEmpty()) {
												final ValueInterval vi = vc.getValueIntervals().get(0);
												final CobolValue cv = valueStmtService.getValue(vi.getFromValueStmt(), null);
												final java.math.BigDecimal dv = valueService.getDecimal(cv);
												if (valText.contains("TRUE") && dv != null && dv.compareTo(java.math.BigDecimal.ONE) == 0) {
													condChild = child;
													break;
												} else if (valText.contains("FALSE") && dv != null && dv.compareTo(java.math.BigDecimal.ZERO) == 0) {
													condChild = child;
													break;
												}
											}
										}
									}
								}
							}
							printConditionSetTo(condChild, rc);
							continue;
						}
					}
				}

				final DataDescriptionEntry dataDescriptionEntry = dataDescriptionEntryService
						.getDataDescriptionEntry(toCall);

				if (dataDescriptionEntry == null) {
					printScalarSetTo(toCall, value, rc);
				} else {
					final DataDescriptionEntryType dataDescriptionEntryType = dataDescriptionEntry
							.getDataDescriptionEntryType();

					if (!DataDescriptionEntryType.GROUP.equals(dataDescriptionEntryType)
							|| !dataDescriptionEntryService.hasChildren(dataDescriptionEntry)) {
						printScalarSetTo(toCall, value, rc);
					} else {
						printGroupSetTo(toCall, value, rc);
					}
				}
			}
		}
	}

	@Override
	public Class<SetToStatementContext> from() {
		return SetToStatementContext.class;
	}

	/**
	 * SET conditionName TO TRUE for 88-level conditions.
	 * In COBOL, SET condition-name TO TRUE sets the parent field to the VALUE
	 * declared in the 88-level condition definition.
	 * e.g., 88 NO-ERRO VALUE "0". → SET NO-ERRO TO TRUE → parent = "0"
	 *       88 ERRO VALUE "1".    → SET ERRO TO TRUE    → parent = "1"
	 * For PIC 1 (boolean) parents with B"1"/B"0", we use true/false.
	 */
	protected void printConditionSetTo(final DataDescriptionEntry conditionEntry, final RuleContext rc) {
		final String parentPath = javaExpressionService.mapConditionToCall(conditionEntry);

		// Determine the parent's Java type
		final DataDescriptionEntry originalParent = conditionEntry.getParentDataDescriptionEntryGroup();
		final CobolTypeEnum parentType = originalParent != null
				? cobolTypeService.getType(originalParent) : null;

		// Extract the actual VALUE from the 88-level condition definition
		String conditionLiteral = null;
		Boolean conditionBoolean = null;
		if (conditionEntry instanceof DataDescriptionEntryCondition) {
			final DataDescriptionEntryCondition condEntry = (DataDescriptionEntryCondition) conditionEntry;
			final ValueClause valueClause = condEntry.getValueClause();
			if (valueClause != null && !valueClause.getValueIntervals().isEmpty()) {
				final ValueInterval interval = valueClause.getValueIntervals().get(0);
				final ValueStmt fromValueStmt = interval.getFromValueStmt();
				if (fromValueStmt != null) {
					final CobolValue value = valueStmtService.getValue(fromValueStmt, null);
					if (value != null) {
						conditionBoolean = valueService.getBoolean(value);
						final String strVal = valueService.getString(value);
						if (strVal != null) {
							conditionLiteral = strVal;
						} else {
							final BigDecimal decVal = valueService.getDecimal(value);
							if (decVal != null) {
								conditionLiteral = decVal.toPlainString();
							}
						}
					}
				}
			}
		}

		// Check if the parent was a REDEFINES PIC 1 (boolean over string)
		final boolean parentIsRedefines = originalParent instanceof DataDescriptionEntryGroup
				&& ((DataDescriptionEntryGroup) originalParent).getRedefinesClause() != null
				&& ((DataDescriptionEntryGroup) originalParent).getRedefinesClause().getRedefinesCall() != null;

		rc.p(parentPath);
		rc.p(" = ");

		if (conditionBoolean != null && !parentIsRedefines
				&& CobolTypeEnum.BOOLEAN.equals(parentType)) {
			// PIC 1 boolean parent with B"1"/B"0" values → use true/false
			rc.p(conditionBoolean.booleanValue() ? "true" : "false");
		} else if (parentIsRedefines && conditionLiteral != null) {
			// REDEFINES PIC 1 over string → use the actual string value
			rc.p("\"" + conditionLiteral + "\"");
		} else if (parentIsRedefines && conditionBoolean != null) {
			// REDEFINES PIC 1 over string, B"1"/B"0" values → use \u0001/\u0000
			// B"1" is X"01" (not the character "1" which is X"F1" in EBCDIC)
			rc.p(conditionBoolean.booleanValue() ? "\"\\u0001\"" : "\"\\u0000\"");
		} else if (parentType != null && (CobolTypeEnum.INTEGER.equals(parentType)
				|| CobolTypeEnum.FLOAT.equals(parentType))) {
			// Numeric parent → use BigDecimal with the actual value
			if (conditionLiteral != null) {
				rc.p("new BigDecimal(\"" + conditionLiteral + "\")");
			} else {
				// Fallback if no literal found
				final boolean isFalseCondition = javaExpressionService.isConditionFalseValue(conditionEntry);
				rc.p(isFalseCondition ? "BigDecimal.ZERO" : "BigDecimal.ONE");
			}
		} else if (parentType != null && CobolTypeEnum.STRING.equals(parentType)) {
			// Alphanumeric parent → use the actual string value from the 88-level
			if (conditionLiteral != null) {
				rc.p("\"" + conditionLiteral + "\"");
			} else if (conditionBoolean != null) {
				// B"1"/B"0" on a STRING parent → use \u0001/\u0000
				// B"1" is X"01" (not the character "1" which is X"F1" in EBCDIC)
				rc.p(conditionBoolean.booleanValue() ? "\"\\u0001\"" : "\"\\u0000\"");
			} else {
				// Fallback if no literal found
				final boolean isFalseCondition = javaExpressionService.isConditionFalseValue(conditionEntry);
				rc.p(isFalseCondition ? "\"0\"" : "\"1\"");
			}
		} else if (conditionBoolean != null) {
			// Boolean parent without REDEFINES
			rc.p(conditionBoolean.booleanValue() ? "true" : "false");
		} else {
			// Fallback
			final boolean isFalseCondition = javaExpressionService.isConditionFalseValue(conditionEntry);
			rc.p(isFalseCondition ? "false" : "true");
		}
		rc.p(";");
		rc.pNl();
	}

	/**
	 * SET IND-OFF(INDEX) TO TRUE for subscripted 88-level conditions under OCCURS arrays.
	 * The parent is an OCCURS array (e.g., TABELA-IND OCCURS 99 PIC 1 INDICATOR 1).
	 * IND-ON VALUE B"1" → assign true; IND-OFF VALUE B"0" → assign false.
	 * For non-boolean parents, use the actual VALUE from the 88-level definition.
	 * Generate: parent[subscript - 1] = value
	 */
	protected void printSubscriptedConditionSetTo(final DataDescriptionEntry conditionEntry,
			final TableCall tableCall, final RuleContext rc) {
		// Determine the parent's Java type, resolving through REDEFINES
		final DataDescriptionEntry originalParent = conditionEntry.getParentDataDescriptionEntryGroup();
		CobolTypeEnum parentType = originalParent != null
				? cobolTypeService.getType(originalParent) : null;

		// When parent is a REDEFINES (e.g., PIC 1 REDEFINES PIC X), the assignment
		// target resolves to the original (redefined) field — use its type.
		if (originalParent instanceof DataDescriptionEntryGroup) {
			final DataDescriptionEntryGroup parentGroup = (DataDescriptionEntryGroup) originalParent;
			if (parentGroup.getRedefinesClause() != null
					&& parentGroup.getRedefinesClause().getRedefinesCall() != null) {
				final String redefinesName = parentGroup.getRedefinesClause().getRedefinesCall().getName();
				final DataDescriptionEntryGroup grandParent = originalParent.getParentDataDescriptionEntryGroup();
				if (grandParent != null) {
					for (final DataDescriptionEntry sibling : grandParent.getDataDescriptionEntries()) {
						if (redefinesName.equalsIgnoreCase(sibling.getName())) {
							parentType = cobolTypeService.getType(sibling);
							break;
						}
					}
				}
			}
		}

		// Extract the actual VALUE from the 88-level condition definition
		Boolean conditionBoolean = null;
		String conditionLiteral = null;
		if (conditionEntry instanceof DataDescriptionEntryCondition) {
			final DataDescriptionEntryCondition condEntry = (DataDescriptionEntryCondition) conditionEntry;
			final ValueClause valueClause = condEntry.getValueClause();
			if (valueClause != null && !valueClause.getValueIntervals().isEmpty()) {
				final ValueInterval interval = valueClause.getValueIntervals().get(0);
				final ValueStmt fromValueStmt = interval.getFromValueStmt();
				if (fromValueStmt != null) {
					final CobolValue value = valueStmtService.getValue(fromValueStmt, null);
					if (value != null) {
						conditionBoolean = valueService.getBoolean(value);
						final String strVal = valueService.getString(value);
						if (strVal != null) {
							conditionLiteral = strVal;
						} else {
							final BigDecimal decVal = valueService.getDecimal(value);
							if (decVal != null) {
								conditionLiteral = decVal.toPlainString();
							}
						}
					}
				}
			}
		}

		// Get subscripts from the TableCall and build subscript expressions
		final java.util.List<Subscript> subscripts = tableCall.getSubscripts();
		final java.util.List<String> subscriptExprs = new java.util.ArrayList<>();
		for (final Subscript subscript : subscripts) {
			final CobolValue subscriptValue = valueStmtService.getValue(subscript.getSubscriptValueStmt(), null);
			BigDecimal decimalSubscriptValue = valueService.getDecimal(subscriptValue);
			if (decimalSubscriptValue != null) {
				subscriptExprs.add(String.format("%d", decimalSubscriptValue.intValue() - 1));
			} else {
				// Fallback: try parsing expression text as a numeric literal
				final String subExprText = javaExpressionService.mapToExpression(subscript.getSubscriptValueStmt());
				try {
					int literalIdx = Integer.parseInt(subExprText.trim());
					subscriptExprs.add(String.format("%d", literalIdx - 1));
				} catch (NumberFormatException e) {
					// Check if subscript resolved to a boolean (wrong ASG resolution).
					// This happens when a COBOL name like IN52 exists both as a DDS indicator
					// (PIC 1 INDIC 52, boolean) and a WORKING-STORAGE numeric constant
					// (PIC 99 VALUE 52, BigDecimal). The ASG may resolve to the boolean DDS
					// field, but the subscript should use the numeric constant.
					final BigDecimal booleanOverrideLiteral = resolveBooleanSubscriptToNumeric(
							subscript.getSubscriptValueStmt(), conditionEntry);
					if (booleanOverrideLiteral != null) {
						subscriptExprs.add(String.format("%d", booleanOverrideLiteral.intValue() - 1));
					} else {
						subscriptExprs.add(subExprText + ".intValue() - 1");
					}
				}
			}
		}

		// Build the parent path with subscripts inserted at OCCURS group levels
		final String parentPath = javaExpressionService.mapConditionToCallWithSubscripts(
				conditionEntry, subscriptExprs);

		rc.p(parentPath);

		rc.p(" = ");
		if (conditionBoolean != null && CobolTypeEnum.BOOLEAN.equals(parentType)) {
			rc.p(conditionBoolean.booleanValue() ? "true" : "false");
		} else if (parentType != null && (CobolTypeEnum.INTEGER.equals(parentType)
				|| CobolTypeEnum.FLOAT.equals(parentType)) && conditionLiteral != null) {
			rc.p("new BigDecimal(\"" + conditionLiteral + "\")");
		} else if (parentType != null && CobolTypeEnum.STRING.equals(parentType)) {
			if (conditionLiteral != null) {
				rc.p("\"" + conditionLiteral + "\"");
			} else if (conditionBoolean != null) {
				rc.p(conditionBoolean.booleanValue() ? "\"1\"" : "\"0\"");
			} else {
				final boolean isFalseCondition = javaExpressionService.isConditionFalseValue(conditionEntry);
				rc.p(isFalseCondition ? "\"0\"" : "\"1\"");
			}
		} else {
			// Fallback for boolean arrays (PIC 1 OCCURS)
			final boolean isFalseCondition = javaExpressionService.isConditionFalseValue(conditionEntry);
			rc.p(isFalseCondition ? "false" : "true");
		}
		rc.p(";");
		rc.pNl();
	}

	protected void printGroupSetTo(final Call call, final Value value, final RuleContext rc) {
		rc.p("entityService.assignTo(");
		rc.visit(call.getCtx());
		rc.p(", ");
		rc.visit(value.getValueStmt().getCtx());
		rc.p(");");
		rc.pNl(call);
	}

	protected void printScalarSetTo(final Call call, final Value value, final RuleContext rc) {
		rc.visit(call.getCtx());
		rc.p(" = ");
		rc.getTypedPrinter().printWithAdjustedType(value.getValueStmt().getCtx(),
				cobolTypeService.getType(value.getValueStmt()), cobolTypeService.getType(call));
		rc.p(";");
		rc.pNl(call);
	}

	/**
	 * Extracts subscript expressions from the source text of a call context.
	 * Used when the parser creates a DATA_DESCRIPTION_ENTRY_CALL but the COBOL
	 * source had subscripts (e.g., SET TOPSEL-OFF OF LKBTOPSEL(WK-I) TO TRUE).
	 */
	private java.util.List<String> extractSubscriptsFromContext(final Call call) {
		final java.util.List<String> result = new java.util.ArrayList<>();
		if (call.getCtx() == null) return result;

		// First, try to find subscript nodes in the parse tree (handles multi-subscript
		// cases like WK-I WK-J where getText() loses the separator whitespace)
		final java.util.List<io.proleap.cobol.CobolParser.SubscriptContext> subscriptNodes =
				findSubscriptContexts(call.getCtx());
		if (!subscriptNodes.isEmpty()) {
			for (final io.proleap.cobol.CobolParser.SubscriptContext subCtx : subscriptNodes) {
				final String subText = subCtx.getText().trim().replace("-", "_").toLowerCase();
				if (!subText.isEmpty()) {
					try {
						int literalIdx = Integer.parseInt(subText);
						result.add(String.format("%d", literalIdx - 1));
					} catch (NumberFormatException e) {
						result.add(subText + ".intValue() - 1");
					}
				}
			}
			return result;
		}

		// Fallback: parse from text (single subscript cases)
		final String text = call.getCtx().getText();
		// Look for parenthesized subscripts: FIELD(SUB1,SUB2)
		final int lparen = text.lastIndexOf('(');
		final int rparen = text.lastIndexOf(')');
		if (lparen >= 0 && rparen > lparen) {
			final String subsText = text.substring(lparen + 1, rparen);
			for (final String sub : subsText.split(",")) {
				final String trimmed = sub.trim().replace("-", "_").toLowerCase();
				if (!trimmed.isEmpty()) {
					// Check if subscript is a numeric literal
					try {
						int literalIdx = Integer.parseInt(trimmed);
						result.add(String.format("%d", literalIdx - 1));
					} catch (NumberFormatException e) {
						result.add(trimmed + ".intValue() - 1");
					}
				}
			}
		}
		return result;
	}

	/**
	 * Recursively finds all SubscriptContext nodes in the parse tree.
	 */
	private java.util.List<io.proleap.cobol.CobolParser.SubscriptContext> findSubscriptContexts(
			final org.antlr.v4.runtime.tree.ParseTree tree) {
		final java.util.List<io.proleap.cobol.CobolParser.SubscriptContext> result = new java.util.ArrayList<>();
		if (tree == null) return result;
		if (tree instanceof io.proleap.cobol.CobolParser.SubscriptContext) {
			result.add((io.proleap.cobol.CobolParser.SubscriptContext) tree);
			return result;
		}
		for (int i = 0; i < tree.getChildCount(); i++) {
			result.addAll(findSubscriptContexts(tree.getChild(i)));
		}
		return result;
	}

	/**
	 * SET conditionName TO TRUE with subscripts extracted from source text.
	 * Delegates to mapConditionToCallWithSubscripts for proper path generation.
	 */
	protected void printConditionSetToWithSubscripts(final DataDescriptionEntry conditionEntry,
			final java.util.List<String> subscriptExprs, final RuleContext rc) {
		final DataDescriptionEntry originalParent = conditionEntry.getParentDataDescriptionEntryGroup();
		CobolTypeEnum parentType = originalParent != null
				? cobolTypeService.getType(originalParent) : null;

		// When parent is a REDEFINES (e.g., PIC 1 REDEFINES PIC X), the assignment
		// target resolves to the original (redefined) field — use its type.
		if (originalParent instanceof DataDescriptionEntryGroup) {
			final DataDescriptionEntryGroup parentGroup = (DataDescriptionEntryGroup) originalParent;
			if (parentGroup.getRedefinesClause() != null
					&& parentGroup.getRedefinesClause().getRedefinesCall() != null) {
				final String redefinesName = parentGroup.getRedefinesClause().getRedefinesCall().getName();
				final DataDescriptionEntryGroup grandParent = originalParent.getParentDataDescriptionEntryGroup();
				if (grandParent != null) {
					for (final DataDescriptionEntry sibling : grandParent.getDataDescriptionEntries()) {
						if (redefinesName.equalsIgnoreCase(sibling.getName())) {
							parentType = cobolTypeService.getType(sibling);
							break;
						}
					}
				}
			}
		}

		// Extract the actual VALUE from the 88-level condition definition
		Boolean conditionBoolean = null;
		String conditionLiteral = null;
		if (conditionEntry instanceof DataDescriptionEntryCondition) {
			final DataDescriptionEntryCondition condEntry = (DataDescriptionEntryCondition) conditionEntry;
			final ValueClause valueClause = condEntry.getValueClause();
			if (valueClause != null && !valueClause.getValueIntervals().isEmpty()) {
				final ValueInterval interval = valueClause.getValueIntervals().get(0);
				final ValueStmt fromValueStmt = interval.getFromValueStmt();
				if (fromValueStmt != null) {
					final CobolValue value = valueStmtService.getValue(fromValueStmt, null);
					if (value != null) {
						conditionBoolean = valueService.getBoolean(value);
						final String strVal = valueService.getString(value);
						if (strVal != null) {
							conditionLiteral = strVal;
						} else {
							final BigDecimal decVal = valueService.getDecimal(value);
							if (decVal != null) {
								conditionLiteral = decVal.toPlainString();
							}
						}
					}
				}
			}
		}

		// Build the parent path with subscripts inserted at OCCURS group levels
		final String parentPath = javaExpressionService.mapConditionToCallWithSubscripts(
				conditionEntry, subscriptExprs);

		rc.p(parentPath);
		rc.p(" = ");
		if (conditionBoolean != null && CobolTypeEnum.BOOLEAN.equals(parentType)) {
			rc.p(conditionBoolean.booleanValue() ? "true" : "false");
		} else if (parentType != null && (CobolTypeEnum.INTEGER.equals(parentType)
				|| CobolTypeEnum.FLOAT.equals(parentType)) && conditionLiteral != null) {
			rc.p("new BigDecimal(\"" + conditionLiteral + "\")");
		} else if (parentType != null && CobolTypeEnum.STRING.equals(parentType)) {
			if (conditionLiteral != null) {
				rc.p("\"" + conditionLiteral + "\"");
			} else if (conditionBoolean != null) {
				rc.p(conditionBoolean.booleanValue() ? "\"1\"" : "\"0\"");
			} else {
				final boolean isFalseCondition = javaExpressionService.isConditionFalseValue(conditionEntry);
				rc.p(isFalseCondition ? "\"0\"" : "\"1\"");
			}
		} else {
			final boolean isFalseCondition = javaExpressionService.isConditionFalseValue(conditionEntry);
			rc.p(isFalseCondition ? "false" : "true");
		}
		rc.p(";");
		rc.pNl();
	}

	/**
	 * When a subscript resolves to a boolean (DDS indicator field), find the
	 * corresponding numeric WORKING-STORAGE field with the same name and return
	 * its VALUE literal.
	 *
	 * In AS/400 COBOL, indicator index constants like IN52 (PIC 99 VALUE 52)
	 * may share names with DDS indicator fields (PIC 1 INDIC 52, boolean).
	 * The ASG may resolve to the boolean DDS field, but SET IND-ON(IN52)
	 * needs the numeric constant (52) as an array index.
	 *
	 * @return the numeric literal value if found, or null if not applicable
	 */
	private BigDecimal resolveBooleanSubscriptToNumeric(final ValueStmt subscriptVS,
			final DataDescriptionEntry contextDde) {
		if (!(subscriptVS instanceof CallValueStmt)) {
			return null;
		}
		final Call subCall = ((CallValueStmt) subscriptVS).getCall();
		if (subCall == null) {
			return null;
		}
		final Call unwrapped = subCall.unwrap();
		if (unwrapped == null || unwrapped.getCallType() != CallType.DATA_DESCRIPTION_ENTRY_CALL) {
			return null;
		}
		final DataDescriptionEntry subDde = ((DataDescriptionEntryCall) unwrapped).getDataDescriptionEntry();
		if (subDde == null) {
			return null;
		}
		// Only proceed if the resolved field is boolean (wrong resolution)
		final CobolTypeEnum subType = cobolTypeService.getType(subDde);
		if (!CobolTypeEnum.BOOLEAN.equals(subType)) {
			return null;
		}
		// Search WORKING-STORAGE for a same-named numeric field
		final String fieldName = subDde.getName();
		if (fieldName == null) {
			return null;
		}
		final io.proleap.cobol.asg.metamodel.ProgramUnit pu = contextDde.getProgramUnit();
		if (pu == null || pu.getDataDivision() == null) {
			return null;
		}
		final io.proleap.cobol.asg.metamodel.data.workingstorage.WorkingStorageSection ws =
				pu.getDataDivision().getWorkingStorageSection();
		if (ws == null) {
			return null;
		}
		// Search all entries (recursively) for a same-named numeric field with a VALUE clause
		return findNumericValueByName(ws.getDataDescriptionEntries(), fieldName);
	}

	/**
	 * Recursively searches data description entries for a numeric field with the given name
	 * that has a VALUE clause, returning the numeric value.
	 */
	private BigDecimal findNumericValueByName(final java.util.List<DataDescriptionEntry> entries,
			final String name) {
		for (final DataDescriptionEntry entry : entries) {
			if (name.equalsIgnoreCase(entry.getName())
					&& entry instanceof DataDescriptionEntryGroup) {
				final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) entry;
				final CobolTypeEnum type = cobolTypeService.getType(entry);
				if (CobolTypeEnum.INTEGER.equals(type) || CobolTypeEnum.FLOAT.equals(type)) {
					final ValueClause vc = group.getValueClause();
					if (vc != null && !vc.getValueIntervals().isEmpty()) {
						final ValueInterval vi = vc.getValueIntervals().get(0);
						final CobolValue cv = valueStmtService.getValue(vi.getFromValueStmt(), null);
						final BigDecimal dv = valueService.getDecimal(cv);
						if (dv != null) {
							return dv;
						}
					}
				}
			}
			// Recurse into children
			if (entry instanceof DataDescriptionEntryGroup) {
				final BigDecimal result = findNumericValueByName(
						((DataDescriptionEntryGroup) entry).getDataDescriptionEntries(), name);
				if (result != null) {
					return result;
				}
			}
		}
		return null;
	}
}
