package io.proleap.cobol.transform.java.rules.lang.procedure.call;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.CallStatementContext;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.call.Call.CallType;
import io.proleap.cobol.asg.metamodel.call.DataDescriptionEntryCall;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.data.datadescription.PictureClause;
import io.proleap.cobol.asg.metamodel.procedure.NotOnExceptionClause;
import io.proleap.cobol.asg.metamodel.procedure.OnExceptionClause;
import io.proleap.cobol.asg.metamodel.procedure.Statement;
import io.proleap.cobol.asg.metamodel.procedure.call.ByContent;
import io.proleap.cobol.asg.metamodel.procedure.call.ByContentPhrase;
import io.proleap.cobol.asg.metamodel.procedure.call.ByReference;
import io.proleap.cobol.asg.metamodel.procedure.call.ByReferencePhrase;
import io.proleap.cobol.asg.metamodel.procedure.call.ByValue;
import io.proleap.cobol.asg.metamodel.procedure.call.ByValuePhrase;
import io.proleap.cobol.asg.metamodel.procedure.call.CallStatement;
import io.proleap.cobol.asg.metamodel.procedure.call.GivingPhrase;
import io.proleap.cobol.asg.metamodel.procedure.call.UsingParameter;
import io.proleap.cobol.asg.metamodel.procedure.call.UsingPhrase;
import io.proleap.cobol.asg.metamodel.valuestmt.CallValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.ValueStmt;
import io.proleap.cobol.commons.datadescription.CobolPictureLengthService;
import io.proleap.cobol.transform.java.expression.JavaExpressionService;
import io.proleap.cobol.transform.java.type.JavaTypeService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class CallStatementRule extends CobolTransformRule<CallStatementContext, CallStatement> {

	@Inject
	private JavaExpressionService javaExpressionService;

	@Inject
	private JavaTypeService javaTypeService;

	@Inject
	private CobolPictureLengthService cobolPictureLengthService;

	/**
	 * Tracks a BY REFERENCE parameter for copy-back code generation.
	 */
	private static class ByRefParam {
		final int argIndex;       // positional index in the CALL argument list
		final String expression;  // Java expression for the target field (e.g., "r_ident", "wk_status")
		final String javaType;    // Java type for casting (e.g., "R_identType", "BigDecimal"), or null
		final int fieldLength;    // PIC field length for the caller's field (-1 if unknown)
		final boolean isGetterExpr; // true when expression is a getter call (elementary REDEFINES),
		                            // requiring copy-back via the corresponding setter.

		ByRefParam(final int argIndex, final String expression, final String javaType, final int fieldLength) {
			this(argIndex, expression, javaType, fieldLength, false);
		}

		ByRefParam(final int argIndex, final String expression, final String javaType, final int fieldLength,
				final boolean isGetterExpr) {
			this.argIndex = argIndex;
			this.expression = expression;
			this.javaType = javaType;
			this.fieldLength = fieldLength;
			this.isGetterExpr = isGetterExpr;
		}
	}

	@Override
	public void apply(final CallStatementContext ctx, final CallStatement callStatement, final RuleContext rc) {
		final OnExceptionClause onExceptionClause = callStatement.getOnExceptionClause();
		final NotOnExceptionClause notOnExceptionClause = callStatement.getNotOnExceptionClause();

		final ValueStmt programValueStmt = callStatement.getProgramValueStmt();

		rc.p("try {");
		rc.pNl();
		rc.getPrinter().indent();

		if (programValueStmt != null && programValueStmt.getCtx() != null) {
			final GivingPhrase givingPhrase = callStatement.getGivingPhrase();
			String givingCast = null;
			if (givingPhrase != null && givingPhrase.getGivingCall() != null) {
				rc.p(javaExpressionService.mapToCall(givingPhrase.getGivingCall()));
				rc.p(" = ");

				// Determine the Java type of the GIVING/RETURNING target for casting
				givingCast = resolveGivingCast(givingPhrase.getGivingCall());
			}

			if (givingCast != null) {
				rc.p("(%s) ", givingCast);
			}
			rc.p("programRunner.call(");
			rc.visit(programValueStmt.getCtx());

			// Collect all argument expressions from USING parameters
			final List<String> argExprs = collectUsingArgs(callStatement);
			for (final String argExpr : argExprs) {
				rc.p(", ");
				rc.p(argExpr);
			}

			rc.p(");");
			rc.pNl();
		}

		if (notOnExceptionClause != null) {
			for (final Statement statement : notOnExceptionClause.getStatements()) {
				rc.visit(statement.getCtx());
			}
		}

		rc.getPrinter().unindent();
		rc.p("} catch (CobolStopRunException e) {");
		rc.pNl();
		rc.getPrinter().indent();
		rc.p("throw e;");
		rc.pNl();
		rc.getPrinter().unindent();
		rc.p("} catch (Exception e) {");
		rc.pNl();
		rc.getPrinter().indent();

		if (onExceptionClause != null) {
			for (final Statement statement : onExceptionClause.getStatements()) {
				rc.visit(statement.getCtx());
			}
		} else {
			// Log the exception instead of silently swallowing it.
			// Silent catch blocks cause cascading failures that are hard to debug
			// (see DebugFlags.STRICT_CALL_ERRORS).
			//
			// Default behaviour (backwards compatible): log a single-line
			// [CALL ERROR] summary to stderr and continue, matching how COBOL
			// programs that rely on CALL ... ON EXCEPTION ... END-CALL semantics
			// expect transient call failures to be absorbed.
			//
			// When -Dcobol.call.error.stacktrace=true (default) the full stack
			// trace is also dumped to stderr so harness stderr captures can
			// identify the actual cause instead of only the class+message.
			//
			// When -Dcobol.strict.call.errors=true or STRICT_CALL_ERRORS=1 is
			// set, the exception is re-thrown wrapped in a RuntimeException so
			// the silent-swallow path becomes a hard failure. This is intended
			// for debugging sessions; NOT enabled by default because many
			// production programs depend on the tolerate-and-continue path.
			final String programName = (programValueStmt != null && programValueStmt.getCtx() != null)
					? programValueStmt.getCtx().getText().replace("\"", "").replace("'", "")
					: "UNKNOWN";
			rc.p("System.err.println(\"[CALL ERROR] %s: \" + e.getClass().getSimpleName() + \": \" + e.getMessage());",
					programName);
			rc.pNl();
			rc.p("if (io.proleap.cobol.runtime.DebugFlags.CALL_ERROR_STACKTRACE) { e.printStackTrace(System.err); }");
			rc.pNl();
			rc.p("if (io.proleap.cobol.runtime.DebugFlags.STRICT_CALL_ERRORS) { throw new RuntimeException(\"[CALL ERROR] %s\", e); }",
					programName);
			rc.pNl();
		}

		rc.getPrinter().unindent();
		rc.p("}");
		rc.pNl();

		// Generate BY REFERENCE copy-back code
		final List<ByRefParam> byRefParams = collectByRefParams(callStatement);
		if (!byRefParams.isEmpty()) {
			rc.p("// BY REFERENCE copy-back from called program's LINKAGE");
			rc.pNl();
			rc.p("{");
			rc.pNl();
			rc.getPrinter().indent();
			rc.p("Object[] _lr = programRunner.getLastCallLinkageResult();");
			rc.pNl();
			rc.p("if (_lr != null) {");
			rc.pNl();
			rc.getPrinter().indent();

			for (final ByRefParam brp : byRefParams) {
				// Elementary REDEFINES fields have get/set pairs instead of direct access.
				// Route copy-back through the setter so the underlying (REDEFINED) storage
				// is updated and the caller sees the callee's mutations after the CALL.
				// Supports both String and BigDecimal setters, matching the callee's return type.
				if (brp.isGetterExpr) {
					final String setterExpr = buildSetterExpression(brp.expression);
					if (setterExpr != null) {
						final boolean isNumeric = "BigDecimal".equals(brp.javaType)
								|| "java.math.BigDecimal".equals(brp.javaType);
						final boolean isBoolean = "boolean".equals(brp.javaType)
								|| "Boolean".equals(brp.javaType);
						if (isNumeric) {
							// Setter takes BigDecimal; coerce callee value if it came back as String.
							rc.p("try { if (_lr.length > %d && _lr[%d] != null) %s(_lr[%d] instanceof java.math.BigDecimal ? (java.math.BigDecimal) _lr[%d] : new java.math.BigDecimal(String.valueOf(_lr[%d]).trim())); } catch (Exception _cce) { /* redefines copy-back */ }",
									brp.argIndex, brp.argIndex, setterExpr, brp.argIndex, brp.argIndex, brp.argIndex);
						} else if (isBoolean) {
							// PIC 1 REDEFINES: setter takes boolean. Callee may return Boolean,
							// a String ("1"/"0"), or "\u0001"/"\u0000" — normalize to boolean.
							rc.p("try { if (_lr.length > %d && _lr[%d] != null) %s(_lr[%d] instanceof Boolean ? (Boolean) _lr[%d] : \"1\".equals(String.valueOf(_lr[%d]).trim()) || \"\\u0001\".equals(String.valueOf(_lr[%d]))); } catch (Exception _cce) { /* redefines copy-back */ }",
									brp.argIndex, brp.argIndex, setterExpr, brp.argIndex, brp.argIndex, brp.argIndex, brp.argIndex);
						} else {
							// Default: setter takes String. Coerce BigDecimal back to zero-padded
							// alphanumeric when a PIC length is known so that "000" round-trips correctly.
							if (brp.fieldLength > 0) {
								rc.p("try { if (_lr.length > %d && _lr[%d] != null) %s(_lr[%d] instanceof String ? (String) _lr[%d] : CobolMove.moveNumericToAlphanumeric((java.math.BigDecimal) _lr[%d], %d, %d)); } catch (Exception _cce) { /* redefines copy-back */ }",
										brp.argIndex, brp.argIndex, setterExpr, brp.argIndex, brp.argIndex, brp.argIndex, brp.fieldLength, brp.fieldLength);
							} else {
								rc.p("try { if (_lr.length > %d && _lr[%d] != null) %s(_lr[%d] instanceof String ? (String) _lr[%d] : String.valueOf(_lr[%d])); } catch (Exception _cce) { /* redefines copy-back */ }",
										brp.argIndex, brp.argIndex, setterExpr, brp.argIndex, brp.argIndex, brp.argIndex);
							}
						}
						rc.pNl();
						continue;
					}
				}
				// For object types (inner classes ending in "Type"):
				// ALWAYS do flat byte copy-back. Even when caller and callee have
				// the SAME Java type, CANCEL creates new program instances (fix #47),
				// so setLinkageFieldsViaReflection copies data to a NEW object.
				// Mutations in the callee don't propagate back without explicit copy.
				if (brp.javaType != null && brp.javaType.endsWith("Type")) {
					// Generate flat-string copy-back with shared-memory semantics.
					// In AS/400, CALL BY REFERENCE shares the same memory block.
					// When callee has a smaller group than caller, the callee's bytes
					// overlay the FIRST N bytes of the caller's group (partial overlay).
				// ALWAYS do copy-back for group types — even when same class,
				// because CANCEL creates new instances so by-ref doesn't work.
					rc.p("try { if (_lr.length > %d && _lr[%d] != null) { String _flat = CobolMove.groupToString(_lr[%d]); int _tgtSz = CobolMove.getGroupSize(%s); if (_flat.length() == _tgtSz) { CobolMove.moveStringToGroup(_flat, %s); } else { String _callerFlat = CobolMove.groupToString(%s); String _merged; if (_flat.length() < _tgtSz) { _merged = _flat + _callerFlat.substring(_flat.length()); } else { _merged = _flat.substring(0, _tgtSz); } CobolMove.moveStringToGroup(_merged, %s); } } } catch (Exception _cbe) { /* copy-back group */ }",
							brp.argIndex, brp.argIndex, brp.argIndex, brp.expression, brp.expression, brp.expression, brp.expression);
					rc.pNl();
					continue;
				}
				// Generate type-safe copy-back with conversion between String↔BigDecimal
				// COBOL treats PIC X and PIC 9 as the same byte buffer; Java needs explicit conversion.
				// When the callee has a PIC 9 field (BigDecimal) and the caller has PIC X (String),
				// we must use moveNumericToAlphanumeric to preserve leading zeros (e.g., BigDecimal(0) → "000").
				if ("String".equals(brp.javaType)) {
					if (brp.fieldLength > 0) {
						// Use 3-arg moveNumericToAlphanumeric(source, sourceIntegerDigits, targetLength)
						// so that BigDecimal(0) with fieldLength=3 produces "000" instead of "0  ".
						// In COBOL CALL BY REFERENCE, caller and callee share the same byte buffer,
						// so the callee's PIC 9(n) has the same digit count as the caller's PIC X(n).
						rc.p("try { if (_lr.length > %d && _lr[%d] != null) %s = _lr[%d] instanceof String ? (String) _lr[%d] : CobolMove.moveNumericToAlphanumeric((java.math.BigDecimal) _lr[%d], %d, %d); } catch (Exception _cce) { /* type mismatch caller/callee */ }",
								brp.argIndex, brp.argIndex, brp.expression, brp.argIndex, brp.argIndex, brp.argIndex, brp.fieldLength, brp.fieldLength);
					} else {
						rc.p("try { if (_lr.length > %d && _lr[%d] != null) %s = _lr[%d] instanceof String ? (String) _lr[%d] : String.valueOf(_lr[%d]); } catch (Exception _cce) { /* type mismatch caller/callee */ }",
								brp.argIndex, brp.argIndex, brp.expression, brp.argIndex, brp.argIndex, brp.argIndex);
					}
				} else if ("BigDecimal".equals(brp.javaType) || "java.math.BigDecimal".equals(brp.javaType)) {
					rc.p("try { if (_lr.length > %d && _lr[%d] != null) %s = _lr[%d] instanceof java.math.BigDecimal ? (java.math.BigDecimal) _lr[%d] : new java.math.BigDecimal(String.valueOf(_lr[%d]).trim()); } catch (Exception _cce) { /* type mismatch caller/callee */ }",
							brp.argIndex, brp.argIndex, brp.expression, brp.argIndex, brp.argIndex, brp.argIndex);
				} else if ("boolean".equals(brp.javaType)) {
					// PIC 1 fields: convert Object to boolean via Boolean wrapper.
					// The callee may return a Boolean, a String ("1"/"0"), or "\u0001"/"\u0000".
					rc.p("try { if (_lr.length > %d && _lr[%d] != null) %s = _lr[%d] instanceof Boolean ? (Boolean) _lr[%d] : \"1\".equals(String.valueOf(_lr[%d]).trim()) || \"\\u0001\".equals(String.valueOf(_lr[%d])); } catch (Exception _cce) { /* type mismatch caller/callee */ }",
							brp.argIndex, brp.argIndex, brp.expression, brp.argIndex, brp.argIndex, brp.argIndex, brp.argIndex);
				} else {
					if (brp.javaType != null && !"Object".equals(brp.javaType)) {
						// Known non-primitive type — cast directly
						rc.p("try { if (_lr.length > %d && _lr[%d] != null) %s = (%s) _lr[%d]; } catch (ClassCastException _cce) { /* type mismatch caller/callee */ }",
								brp.argIndex, brp.argIndex, brp.expression, brp.javaType, brp.argIndex);
					} else {
						// Unknown type — default to String-safe cast since most COBOL fields are alphanumeric.
						// Use instanceof check to handle both String and BigDecimal returns from the callee.
						rc.p("try { if (_lr.length > %d && _lr[%d] != null) %s = _lr[%d] instanceof String ? (String) _lr[%d] : String.valueOf(_lr[%d]); } catch (Exception _cce) { /* type mismatch caller/callee */ }",
								brp.argIndex, brp.argIndex, brp.expression, brp.argIndex, brp.argIndex, brp.argIndex);
					}
				}
				rc.pNl();
			}

			rc.getPrinter().unindent();
			rc.p("}");
			rc.pNl();
			rc.getPrinter().unindent();
			rc.p("}");
			rc.pNl(callStatement);
		} else {
			// Attach the source comment to the closing brace
			rc.pNl(callStatement);
		}
	}

	@Override
	public Class<CallStatementContext> from() {
		return CallStatementContext.class;
	}

	private List<String> collectUsingArgs(final CallStatement callStatement) {
		final List<String> args = new ArrayList<>();
		final UsingPhrase usingPhrase = callStatement.getUsingPhrase();

		if (usingPhrase == null) {
			return args;
		}

		final List<UsingParameter> params = usingPhrase.getUsingParameters();
		if (params == null) {
			return args;
		}

		for (final UsingParameter param : params) {
			final ByReferencePhrase byRef = param.getByReferencePhrase();
			final ByContentPhrase byContent = param.getByContentPhrase();
			final ByValuePhrase byValue = param.getByValuePhrase();

			if (byRef != null) {
				for (final ByReference ref : byRef.getByReferences()) {
					final ValueStmt vs = ref.getValueStmt();
					if (vs != null) {
						final String expr = javaExpressionService.mapToExpression(vs);
						args.add((expr != null && !expr.isEmpty()) ? expr : "null");
					} else {
						args.add("null");
					}
				}
			} else if (byContent != null) {
				for (final ByContent content : byContent.getByContents()) {
					final ValueStmt vs = content.getValueStmt();
					if (vs != null) {
						final String expr = javaExpressionService.mapToExpression(vs);
						args.add((expr != null && !expr.isEmpty()) ? expr : "null");
					} else {
						args.add("null");
					}
				}
			} else if (byValue != null) {
				for (final ByValue value : byValue.getByValues()) {
					final ValueStmt vs = value.getValueStmt();
					if (vs != null) {
						final String expr = javaExpressionService.mapToExpression(vs);
						args.add((expr != null && !expr.isEmpty()) ? expr : "null");
					} else {
						args.add("null");
					}
				}
			} else {
				args.add("null");
			}
		}

		return args;
	}

	/**
	 * Collects information about BY REFERENCE parameters for copy-back code generation.
	 * Tracks the argument index, Java expression (assignment target), and Java type
	 * for each BY REFERENCE parameter. BY CONTENT and BY VALUE parameters are skipped
	 * (they should NOT be copied back).
	 */
	private List<ByRefParam> collectByRefParams(final CallStatement callStatement) {
		final List<ByRefParam> result = new ArrayList<>();
		final UsingPhrase usingPhrase = callStatement.getUsingPhrase();

		if (usingPhrase == null) {
			return result;
		}

		final List<UsingParameter> params = usingPhrase.getUsingParameters();
		if (params == null) {
			return result;
		}

		int argIndex = 0;

		for (final UsingParameter param : params) {
			final ByReferencePhrase byRef = param.getByReferencePhrase();
			final ByContentPhrase byContent = param.getByContentPhrase();
			final ByValuePhrase byValue = param.getByValuePhrase();

			if (byRef != null) {
				for (final ByReference ref : byRef.getByReferences()) {
					final ValueStmt vs = ref.getValueStmt();
					if (vs != null) {
						final String expr = javaExpressionService.mapToExpression(vs);
						if (expr != null && !expr.isEmpty() && !"null".equals(expr)) {
							final String javaType = resolveValueStmtType(vs);
							final int fieldLen = resolveFieldLength(vs);
							// Elementary REDEFINES fields are generated as get<Name>()/set<Name>()
							// pairs instead of direct fields, so the expression ends with "()".
							// For BY REFERENCE semantics on AS/400, the callee's mutations must
							// propagate back to the caller — we route the copy-back through the
							// setter so the underlying (REDEFINED) storage is updated.
							// Only accept getter-call expressions that look like an elementary
							// REDEFINES access; skip anything else that contains '(' to be safe
							// (e.g., accidental method calls in other contexts).
							final boolean isGetterExpr = isElementaryGetterExpression(expr);
							if (!expr.contains("(") || isGetterExpr) {
								result.add(new ByRefParam(argIndex, expr, javaType, fieldLen, isGetterExpr));
							}
						}
					}
					argIndex++;
				}
			} else if (byContent != null) {
				// BY CONTENT - do NOT copy back, just count the args
				for (@SuppressWarnings("unused") final ByContent content : byContent.getByContents()) {
					argIndex++;
				}
			} else if (byValue != null) {
				// BY VALUE - do NOT copy back, just count the args
				for (@SuppressWarnings("unused") final ByValue value : byValue.getByValues()) {
					argIndex++;
				}
			} else {
				// Unknown/default parameter type - skip
				argIndex++;
			}
		}

		return result;
	}

	/**
	 * Resolves the Java type for a ValueStmt, used for casting in BY REFERENCE copy-back.
	 * Returns the fully qualified Java type name (e.g., "String", "BigDecimal",
	 * "Data_hora_structType.Data_formatadaType") or null if the type cannot be determined.
	 * For inner classes (sub-group DDEs), builds the full qualification chain from the
	 * parent DDE hierarchy.
	 */
	private String resolveValueStmtType(final ValueStmt vs) {
		if (vs instanceof CallValueStmt) {
			final Call call = ((CallValueStmt) vs).getCall();
			if (call != null) {
				final Call unwrapped = call.unwrap();
				if (unwrapped != null && unwrapped.getCallType() == CallType.DATA_DESCRIPTION_ENTRY_CALL) {
					final DataDescriptionEntryCall ddeCall = (DataDescriptionEntryCall) unwrapped;
					final DataDescriptionEntry dde = ddeCall.getDataDescriptionEntry();
					if (dde != null) {
						return buildQualifiedType(dde);
					}
				}
			}
		}
		return null;
	}

	/**
	 * Resolves the PIC field length for a ValueStmt, used for numeric-to-string
	 * conversion in BY REFERENCE copy-back. Returns -1 if the length cannot be determined.
	 */
	private int resolveFieldLength(final ValueStmt vs) {
		if (vs instanceof CallValueStmt) {
			final Call call = ((CallValueStmt) vs).getCall();
			if (call != null) {
				final Call unwrapped = call.unwrap();
				if (unwrapped != null && unwrapped.getCallType() == CallType.DATA_DESCRIPTION_ENTRY_CALL) {
					final DataDescriptionEntryCall ddeCall = (DataDescriptionEntryCall) unwrapped;
					final DataDescriptionEntry dde = ddeCall.getDataDescriptionEntry();
					if (dde != null) {
						try {
							final Integer len = cobolPictureLengthService.getLength(dde);
							return (len != null) ? len : -1;
						} catch (final Exception e) {
							return -1;
						}
					}
				}
			}
		}
		return -1;
	}

	/**
	 * Builds a fully qualified Java type name for a DDE, walking up the parent
	 * hierarchy to include enclosing inner class names.
	 * For a level-01 field, returns just "R_identType".
	 * For a nested field like DATA-FORMATADA OF DATA-HORA-STRUCT, returns
	 * "Data_hora_structType.Data_formatadaType".
	 */
	private String buildQualifiedType(final DataDescriptionEntry dde) {
		final String simpleType = javaTypeService.mapToType(dde);
		if (simpleType == null) {
			return null;
		}

		// Primitive types and wrapper types don't need qualification
		if ("String".equals(simpleType) || "BigDecimal".equals(simpleType)
				|| "boolean".equals(simpleType) || "Boolean".equals(simpleType)
				|| "int".equals(simpleType)
				|| "long".equals(simpleType) || "Object".equals(simpleType)) {
			// PIC 1 fields are declared as primitive boolean, but mapToType returns "Boolean".
			// Return "boolean" to match the actual Java field type.
			if ("Boolean".equals(simpleType)) {
				return "boolean";
			}
			return simpleType;
		}

		// Walk up the parent hierarchy to build qualification chain
		final DataDescriptionEntryGroup parentGroup = dde.getParentDataDescriptionEntryGroup();
		if (parentGroup == null) {
			// Top-level DDE (level 01) - no qualification needed
			return simpleType;
		}

		// Build the chain from parent to child
		final List<String> typeChain = new ArrayList<>();
		typeChain.add(simpleType);

		DataDescriptionEntryGroup current = parentGroup;
		while (current != null) {
			final String parentType = javaTypeService.mapToType(current);
			if (parentType != null && !"Object".equals(parentType)) {
				typeChain.add(0, parentType);
			}
			current = current.getParentDataDescriptionEntryGroup();
		}

		if (typeChain.size() <= 1) {
			return simpleType;
		}

		return String.join(".", typeChain);
	}

	/**
	 * Returns true if the expression looks like an elementary REDEFINES getter
	 * access: either a plain "getXxx()" or a qualified "a.b.getXxx()" chain.
	 * Used to detect when copy-back must go through the matching setter so the
	 * underlying (REDEFINED) storage is actually updated.
	 */
	private boolean isElementaryGetterExpression(final String expr) {
		if (expr == null || !expr.endsWith("()")) {
			return false;
		}
		final int lastDot = expr.lastIndexOf('.');
		final String leaf = (lastDot >= 0) ? expr.substring(lastDot + 1) : expr;
		// leaf must be "getXxx()" with Xxx matching a Java identifier starting with uppercase
		if (!leaf.startsWith("get") || leaf.length() < 6) {
			return false;
		}
		// The prefix chain (if any) must only contain identifiers separated by dots,
		// with no extra '(' that would indicate a different kind of call.
		if (lastDot >= 0) {
			final String prefix = expr.substring(0, lastDot);
			if (prefix.indexOf('(') >= 0) {
				return false;
			}
		}
		final char firstAfterGet = leaf.charAt(3);
		return Character.isUpperCase(firstAfterGet) || firstAfterGet == '_';
	}

	/**
	 * Converts a "...getXxx()" expression into the matching "...setXxx" setter
	 * reference (without parentheses — the caller appends the argument list).
	 * Returns null if the expression does not match the expected shape.
	 */
	private String buildSetterExpression(final String getterExpr) {
		if (!isElementaryGetterExpression(getterExpr)) {
			return null;
		}
		final int lastDot = getterExpr.lastIndexOf('.');
		final String prefix = (lastDot >= 0) ? getterExpr.substring(0, lastDot + 1) : "";
		final String leaf = (lastDot >= 0) ? getterExpr.substring(lastDot + 1) : getterExpr;
		// leaf is "getXxx()" — turn into "setXxx"
		final String name = leaf.substring(3, leaf.length() - 2); // strip "get" prefix and "()" suffix
		return prefix + "set" + name;
	}

	/**
	 * Resolves the Java type cast needed for the GIVING/RETURNING target.
	 * Returns null if no cast is needed (target is Object), or the Java type
	 * name (e.g., "String", "BigDecimal", "ICONVOPENRETURNType") if a cast is required.
	 * For group-level DDEs, uses mapToType(DataDescriptionEntry) to get the
	 * generated inner class name (e.g., ICONVOPENRETURNType).
	 */
	private String resolveGivingCast(final Call givingCall) {
		if (givingCall == null) {
			return null;
		}

		final Call unwrapped = givingCall.unwrap();
		if (unwrapped == null || unwrapped.getCallType() != CallType.DATA_DESCRIPTION_ENTRY_CALL) {
			return null;
		}

		final DataDescriptionEntryCall ddeCall = (DataDescriptionEntryCall) unwrapped;
		final DataDescriptionEntry dde = ddeCall.getDataDescriptionEntry();
		if (dde == null) {
			return null;
		}

		// Use the DDE-aware overload which handles group-level types (inner classes)
		// as well as primitive types (String, BigDecimal, etc.)
		final String javaType = javaTypeService.mapToType(dde);
		if (javaType == null || "Object".equals(javaType)) {
			return null;
		}

		return javaType;
	}
}
