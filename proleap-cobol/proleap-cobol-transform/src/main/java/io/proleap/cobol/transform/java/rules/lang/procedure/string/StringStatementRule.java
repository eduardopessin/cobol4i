package io.proleap.cobol.transform.java.rules.lang.procedure.string;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.StringStatementContext;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.call.Call.CallType;
import io.proleap.cobol.asg.metamodel.call.DataDescriptionEntryCall;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry.DataDescriptionEntryType;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.procedure.string.DelimitedByPhrase;
import io.proleap.cobol.asg.metamodel.procedure.string.Sendings;
import io.proleap.cobol.asg.metamodel.procedure.string.StringStatement;
import io.proleap.cobol.asg.metamodel.procedure.string.WithPointerPhrase;
import io.proleap.cobol.asg.metamodel.valuestmt.CallValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.ValueStmt;
import io.proleap.cobol.commons.datadescription.CobolDataDescriptionEntryService;
import io.proleap.cobol.commons.datadescription.CobolPictureLengthService;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.transform.java.expression.JavaExpressionService;
import io.proleap.cobol.transform.java.identifier.variable.JavaVariableIdentifierService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class StringStatementRule extends CobolTransformRule<StringStatementContext, StringStatement> {

	@Inject
	private JavaExpressionService javaExpressionService;

	@Inject
	private CobolDataDescriptionEntryService dataDescriptionEntryService;

	@Inject
	private CobolPictureLengthService cobolPictureLengthService;

	@Inject
	private CobolTypeService cobolTypeService;

	@Inject
	private JavaVariableIdentifierService javaVariableIdentifierService;

	@Override
	public void apply(final StringStatementContext ctx, final StringStatement stringStatement, final RuleContext rc) {
		final Call intoCall = stringStatement.getIntoPhrase().getIntoCall();
		final String intoExpr = javaExpressionService.mapToCall(intoCall);

		// Resolve target PIC length for truncation/padding
		final Integer targetPicLength = resolveTargetPicLength(intoCall);

		final WithPointerPhrase withPointerPhrase = stringStatement.getWithPointerPhrase();
		final String pointerExpr = withPointerPhrase != null
				? javaExpressionService.mapToCall(withPointerPhrase.getPointerCall())
				: null;

		// open block
		rc.p("{");
		rc.pNl();

		// StringBuilder: empty when no POINTER, from current value when POINTER present
		if (pointerExpr != null) {
			rc.p("StringBuilder _sb = new StringBuilder(");
			rc.p(intoExpr);
			rc.p(" != null ? ");
			rc.p(intoExpr);
			rc.p(" : \"\");");
		} else {
			rc.p("StringBuilder _sb = new StringBuilder();");
		}
		rc.pNl();

		// Pointer handling
		if (pointerExpr != null) {
			rc.p("int _ptr = ");
			rc.p(pointerExpr);
			rc.p(" != null ? ");
			rc.p(pointerExpr);
			rc.p(".intValue() : 1;");
			rc.pNl();

			// Adjust StringBuilder to pointer position
			rc.p("if (_sb.length() > _ptr - 1) _sb.setLength(_ptr - 1);");
			rc.pNl();
			rc.p("else while (_sb.length() < _ptr - 1) _sb.append(' ');");
			rc.pNl();
		}

		// Process each sending group
		for (final Sendings sendings : stringStatement.getSendings()) {
			final List<ValueStmt> sendingValues = sendings.getSendingValueStmts();
			final DelimitedByPhrase delimitedBy = sendings.getDelimitedByPhrase();

			for (final ValueStmt sendingValueStmt : sendingValues) {
				final String sendingExpr = javaExpressionService.mapToExpression(sendingValueStmt);
				final boolean sendingIsGroup = isSendingGroupWithChildren(sendingValueStmt);

				if (delimitedBy != null
						&& delimitedBy.getDelimitedByType() == DelimitedByPhrase.DelimitedByType.CHARACTERS) {
					// DELIMITED BY "x" or DELIMITED BY variable
					final String delimExpr = javaExpressionService.mapToExpression(delimitedBy.getCharactersValueStmt());

					rc.p("{");
					rc.pNl();
					if (sendingIsGroup) {
						rc.p("String _src = CobolMove.groupToString(");
						rc.p(sendingExpr);
						rc.p(");");
					} else {
						rc.p("String _src = String.valueOf(");
						rc.p(sendingExpr);
						rc.p(");");
					}
					rc.pNl();
					rc.p("String _delim = String.valueOf(");
					rc.p(delimExpr);
					rc.p(");");
					rc.pNl();
					rc.p("int _delPos = _src.indexOf(_delim);");
					rc.pNl();
					rc.p("if (_delPos >= 0) _src = _src.substring(0, _delPos);");
					rc.pNl();
					rc.p("_sb.append(_src);");
					rc.pNl();
					rc.p("}");
					rc.pNl();
				} else {
					// DELIMITED BY SIZE (default) - append full value
					if (sendingIsGroup) {
						rc.p("_sb.append(CobolMove.groupToString(");
						rc.p(sendingExpr);
						rc.p("));");
					} else {
						// Check if sending field is numeric — need to zero-pad to PIC width
						final CobolTypeEnum sendingType = cobolTypeService.getType(sendingValueStmt);
						final boolean sendingIsNumeric = (sendingType == CobolTypeEnum.INTEGER || sendingType == CobolTypeEnum.FLOAT);
						if (sendingIsNumeric) {
							// Get PIC width for zero-padding
							final DataDescriptionEntry sendingDde = getSendingDde(sendingValueStmt);
							final Integer picLen = sendingDde != null ? cobolPictureLengthService.getLength(sendingDde) : null;
							if (picLen != null && picLen > 0) {
								rc.p("_sb.append(CobolMove.moveNumericToAlphanumeric(");
								rc.p(sendingExpr);
								rc.p(", %d, %d));", picLen, picLen);
							} else {
								rc.p("_sb.append(");
								rc.p(sendingExpr);
								rc.p(");");
							}
						} else {
							rc.p("_sb.append(");
							rc.p(sendingExpr);
							rc.p(");");
						}
					}
					rc.pNl();
				}
			}
		}

		// Update target variable: type-aware assignment
		final boolean targetIsNumeric = isNumericTarget(intoCall);
		final DataDescriptionEntry intoDde = dataDescriptionEntryService.getDataDescriptionEntry(intoCall);

		// Check if the INTO target is a REDEFINES field — if so, use the setter
		// instead of direct assignment (the base field may have a different type).
		// Check both group-over-elementary and elementary-over-elementary REDEFINES.
		String redefinesSetterPrefix = javaExpressionService.getGroupOverElementarySetterPrefix(intoCall);
		if (redefinesSetterPrefix == null) {
			redefinesSetterPrefix = javaExpressionService.getRedefinesSetterPrefix(intoCall);
		}
		if (redefinesSetterPrefix != null) {
			// Check if the REDEFINES target (base field) is numeric — if so,
			// the setter expects BigDecimal, so use moveAlphanumericToNumeric.
			if (targetIsNumeric) {
				final Integer intDigits = getIntegerDigits(intoDde);
				final Integer decDigits = getDecimalDigits(intoDde);
				rc.p(redefinesSetterPrefix);
				rc.p("(");
				if (intDigits != null && decDigits != null) {
					rc.p("CobolMove.moveAlphanumericToNumeric(_sb.toString(), ");
					rc.p(String.valueOf(intDigits));
					rc.p(", ");
					rc.p(String.valueOf(decDigits));
					rc.p(")");
				} else {
					rc.p("new java.math.BigDecimal(_sb.toString().trim().isEmpty() ? \"0\" : _sb.toString().trim())");
				}
				rc.p(");");
			} else {
				// Use setter: setX(CobolMove.moveAlphanumericToAlphanumeric(_sb.toString(), picLen))
				rc.p(redefinesSetterPrefix);
				rc.p("(");
				if (targetPicLength != null) {
					rc.p("CobolMove.moveAlphanumericToAlphanumeric(_sb.toString(), ");
					rc.p(String.valueOf(targetPicLength));
					rc.p(")");
				} else {
					rc.p("_sb.toString()");
				}
				rc.p(");");
			}
		} else if (targetIsNumeric) {
			// STRING result into a numeric field: use moveAlphanumericToNumeric
			final Integer intDigits = getIntegerDigits(intoDde);
			final Integer decDigits = getDecimalDigits(intoDde);
			if (intDigits != null && decDigits != null) {
				rc.p(intoExpr);
				rc.p(" = CobolMove.moveAlphanumericToNumeric(_sb.toString(), ");
				rc.p(String.valueOf(intDigits));
				rc.p(", ");
				rc.p(String.valueOf(decDigits));
				rc.p(");");
			} else {
				// Fallback: parse as BigDecimal
				rc.p(intoExpr);
				rc.p(" = new java.math.BigDecimal(_sb.toString().trim().isEmpty() ? \"0\" : _sb.toString().trim());");
			}
		} else if (isVarcharGroup(intoDde)) {
			// STRING INTO a VARCHAR group: assign to the DATA subfield and set LENGTH
			final DataDescriptionEntryGroup vcGroup = (DataDescriptionEntryGroup) intoDde;
			final java.util.List<DataDescriptionEntry> vcChildren = new java.util.ArrayList<>();
			for (final DataDescriptionEntry vcChild : vcGroup.getDataDescriptionEntries()) {
				if (vcChild.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION) {
					vcChildren.add(vcChild);
				}
			}
			final String lengthField = javaVariableIdentifierService.mapToIdentifier(vcChildren.get(0));
			final String dataField = javaVariableIdentifierService.mapToIdentifier(vcChildren.get(1));
			final Integer dataLen = cobolPictureLengthService.getLength(vcChildren.get(1));
			rc.p(intoExpr);
			rc.p(".");
			rc.p(dataField);
			if (dataLen != null) {
				rc.p(" = CobolMove.moveAlphanumericToAlphanumeric(_sb.toString(), ");
				rc.p(String.valueOf(dataLen));
				rc.p(");");
			} else {
				rc.p(" = _sb.toString();");
			}
			rc.pNl();
			rc.p(intoExpr);
			rc.p(".");
			rc.p(lengthField);
			rc.p(" = new BigDecimal(_sb.toString().length());");
		} else if (targetPicLength != null) {
			rc.p(intoExpr);
			rc.p(" = CobolMove.moveAlphanumericToAlphanumeric(_sb.toString(), ");
			rc.p(String.valueOf(targetPicLength));
			rc.p(");");
		} else {
			// Check if the target is a group item — use moveStringToGroup
			if (intoDde != null && DataDescriptionEntryType.GROUP.equals(intoDde.getDataDescriptionEntryType())
					&& dataDescriptionEntryService.hasChildren(intoDde)) {
				rc.p("CobolMove.moveStringToGroup(_sb.toString(), ");
				rc.p(intoExpr);
				rc.p(");");
			} else {
				rc.p(intoExpr);
				rc.p(" = _sb.toString();");
			}
		}
		rc.pNl();

		// Update pointer if present
		if (pointerExpr != null) {
			rc.p(pointerExpr);
			rc.p(" = new BigDecimal(_sb.length() + 1);");
			rc.pNl();
		}

		// ON OVERFLOW / NOT ON OVERFLOW
		if (stringStatement.getOnOverflowPhrase() != null
				|| stringStatement.getNotOnOverflowPhrase() != null) {
			// Overflow occurs when concatenated length exceeds the receiving field size
			if (targetPicLength != null) {
				rc.p("boolean _strOverflow = _sb.length() > ");
				rc.p(String.valueOf(targetPicLength));
				rc.p(";");
			} else {
				// No known PIC length — overflow cannot occur
				rc.p("boolean _strOverflow = false;");
			}
			rc.pNl();

			if (stringStatement.getOnOverflowPhrase() != null) {
				rc.p("if (_strOverflow) {");
				rc.pNl();
				rc.visitChildren(stringStatement.getOnOverflowPhrase().getCtx());
				rc.p("}");
				rc.pNl();
			}
			if (stringStatement.getNotOnOverflowPhrase() != null) {
				rc.p("if (!_strOverflow) {");
				rc.pNl();
				rc.visitChildren(stringStatement.getNotOnOverflowPhrase().getCtx());
				rc.p("}");
				rc.pNl();
			}
		}

		// close block
		rc.p("}");
		rc.pNl(stringStatement);
	}

	/**
	 * Resolves the PIC length of the STRING INTO target variable.
	 */
	private Integer resolveTargetPicLength(final Call call) {
		final DataDescriptionEntry dde = dataDescriptionEntryService.getDataDescriptionEntry(call);
		if (dde != null) {
			return cobolPictureLengthService.getLength(dde);
		}
		return null;
	}

	/**
	 * Checks whether a sending ValueStmt refers to a group item with children.
	 * Group items are generated as inner classes in Java and must be serialized
	 * with CobolMove.groupToString() before appending to StringBuilder.
	 */
	private boolean isSendingGroupWithChildren(final ValueStmt valueStmt) {
		if (valueStmt instanceof CallValueStmt) {
			final Call call = ((CallValueStmt) valueStmt).getCall();
			final DataDescriptionEntry dde = dataDescriptionEntryService.getDataDescriptionEntry(call);
			if (dde != null
					&& DataDescriptionEntryType.GROUP.equals(dde.getDataDescriptionEntryType())
					&& dataDescriptionEntryService.hasChildren(dde)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Gets the DataDescriptionEntry for a sending ValueStmt (for STRING numeric zero-padding).
	 */
	private DataDescriptionEntry getSendingDde(final ValueStmt valueStmt) {
		if (valueStmt instanceof io.proleap.cobol.asg.metamodel.valuestmt.CallValueStmt) {
			final Call call = ((io.proleap.cobol.asg.metamodel.valuestmt.CallValueStmt) valueStmt).getCall();
			return dataDescriptionEntryService.getDataDescriptionEntry(call);
		}
		return null;
	}

	/**
	 * Determines whether the INTO target field is numeric (BigDecimal in Java).
	 */
	private boolean isNumericTarget(final Call call) {
		final DataDescriptionEntry dde = dataDescriptionEntryService.getDataDescriptionEntry(call);
		if (dde != null) {
			final CobolTypeEnum type = cobolTypeService.getType(dde);
			return type == CobolTypeEnum.INTEGER || type == CobolTypeEnum.FLOAT;
		}
		return false;
	}

	/**
	 * Gets the integer digit count from a DataDescriptionEntry's PIC clause.
	 */
	private Integer getIntegerDigits(final DataDescriptionEntry entry) {
		if (entry == null) return null;
		final String picString = getPictureString(entry);
		if (picString == null) return null;
		return cobolPictureLengthService.getIntegerPartLength(picString);
	}

	/**
	 * Gets the decimal digit count from a DataDescriptionEntry's PIC clause.
	 */
	private Integer getDecimalDigits(final DataDescriptionEntry entry) {
		if (entry == null) return null;
		final String picString = getPictureString(entry);
		if (picString == null) return null;
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

	/**
	 * Detects a VARCHAR group pattern: a group with exactly 2 non-condition children
	 * whose names end in -LENGTH and -DATA (or -L and -V).
	 */
	private boolean isVarcharGroup(final DataDescriptionEntry entry) {
		if (!(entry instanceof DataDescriptionEntryGroup)) {
			return false;
		}
		final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) entry;
		final java.util.List<DataDescriptionEntry> children = new java.util.ArrayList<>();
		for (final DataDescriptionEntry child : group.getDataDescriptionEntries()) {
			if (child.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION) {
				children.add(child);
			}
		}
		if (children.size() != 2) {
			return false;
		}
		final String n0 = children.get(0).getName();
		final String n1 = children.get(1).getName();
		if (n0 == null || n1 == null) {
			return false;
		}
		final String name0 = n0.toUpperCase();
		final String name1 = n1.toUpperCase();
		return (name0.endsWith("-LENGTH") && name1.endsWith("-DATA"))
				|| (name0.endsWith("-DATA") && name1.endsWith("-LENGTH"))
				|| (name0.endsWith("-L") && name1.endsWith("-V"))
				|| (name0.endsWith("-V") && name1.endsWith("-L"));
	}

	@Override
	public Class<StringStatementContext> from() {
		return StringStatementContext.class;
	}
}
