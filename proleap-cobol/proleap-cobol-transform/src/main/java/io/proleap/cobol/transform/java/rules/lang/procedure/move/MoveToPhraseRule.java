package io.proleap.cobol.transform.java.rules.lang.procedure.move;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.MoveToStatementContext;
import io.proleap.cobol.asg.metamodel.FigurativeConstant;
import io.proleap.cobol.asg.metamodel.FigurativeConstant.FigurativeConstantType;
import io.proleap.cobol.asg.metamodel.Literal;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.call.Call.CallType;
import io.proleap.cobol.asg.metamodel.call.DataDescriptionEntryCall;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry.DataDescriptionEntryType;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.procedure.move.MoveToSendingArea;
import io.proleap.cobol.asg.metamodel.procedure.move.MoveToStatement;
import io.proleap.cobol.asg.metamodel.valuestmt.ArithmeticValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.CallValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.LiteralValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.ValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.arithmetic.Basis;
import io.proleap.cobol.asg.metamodel.valuestmt.arithmetic.Powers;
import io.proleap.cobol.commons.datadescription.CobolDataDescriptionEntryService;
import io.proleap.cobol.commons.datadescription.CobolPictureLengthService;
import io.proleap.cobol.commons.datadescription.CobolPictureStringService;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.transform.java.expression.JavaExpressionService;
import io.proleap.cobol.transform.java.identifier.JavaIdentifierService;
import io.proleap.cobol.transform.java.identifier.variable.JavaVariableIdentifierService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class MoveToPhraseRule extends CobolTransformRule<MoveToStatementContext, MoveToStatement> {

	@Inject
	private JavaExpressionService javaExpressionService;

	@Inject
	private JavaIdentifierService javaIdentifierService;

	@Inject
	private JavaVariableIdentifierService javaVariableIdentifierService;

	@Inject
	private CobolTypeService cobolTypeService;

	@Inject
	private CobolDataDescriptionEntryService dataDescriptionEntryService;

	@Inject
	private CobolPictureLengthService cobolPictureLengthService;

	@Inject
	private CobolPictureStringService pictureStringService;

	@Override
	public void apply(final MoveToStatementContext ctx, final MoveToStatement moveToPhrase, final RuleContext rc) {
		final MoveToSendingArea sendingArea = moveToPhrase.getSendingArea();

		for (final Call call : moveToPhrase.getReceivingAreaCalls()) {
			DataDescriptionEntry dataDescriptionEntry = dataDescriptionEntryService.getDataDescriptionEntry(call);

			// When the ASG can't resolve the call (e.g., NOME-OPE vs NOMEOPE), try
			// hyphen-insensitive lookup to find the target DDE
			if (dataDescriptionEntry == null) {
				dataDescriptionEntry = resolveByHyphenInsensitiveLookup(call);
			}

			if (dataDescriptionEntry == null) {
				printScalarMoveTo(call, sendingArea, null, rc);
			} else {
				final DataDescriptionEntryType dataDescriptionEntryType = dataDescriptionEntry
						.getDataDescriptionEntryType();

				if (!DataDescriptionEntryType.GROUP.equals(dataDescriptionEntryType)
						|| !dataDescriptionEntryService.hasChildren(dataDescriptionEntry)) {
					printScalarMoveTo(call, sendingArea, dataDescriptionEntry, rc);
				} else if (isVarcharGroup(dataDescriptionEntry)) {
					// VARCHAR group (2 children: -LENGTH and -DATA) — MOVE targets the _data subfield
					printVarcharMoveTo(call, sendingArea, (DataDescriptionEntryGroup) dataDescriptionEntry, rc);
				} else {
					printGroupMoveTo(call, sendingArea, rc);
				}
			}
		}
	}

	@Override
	public Class<MoveToStatementContext> from() {
		return MoveToStatementContext.class;
	}

	protected void printGroupMoveTo(final Call call, final MoveToSendingArea sendingArea, final RuleContext rc) {
		// Per IBM ILE COBOL manual: MOVE elementary TO group is a byte-level overlay
		// (left-justified, space-padded), NOT a MOVE CORRESPONDING.
		// Only MOVE CORRESPONDING (handled by MoveCorrespondingPhraseRule) matches by name.
		final FigurativeConstantType figType = getFigurativeConstantType(sendingArea);

		if (figType != null && isZeroFigurativeConstant(figType)) {
			// MOVE ZEROS TO group: fill entire group with character '0'
			rc.p("CobolMove.moveStringToGroup(CobolMove.groupToString(");
			rc.visit(call.getCtx());
			rc.p(").replaceAll(\".\", \"0\"), ");
			rc.visit(call.getCtx());
			rc.p(");");
			rc.pNl(call);
		} else if (figType != null && isSpaceFigurativeConstant(figType)) {
			// MOVE SPACES TO group: fill entire group with spaces
			rc.p("CobolMove.moveStringToGroup(CobolMove.moveSpaces(CobolMove.groupToString(");
			rc.visit(call.getCtx());
			rc.p(").length()), ");
			rc.visit(call.getCtx());
			rc.p(");");
			rc.pNl(call);
		} else {
			// MOVE elementary/group TO group: byte-level overlay via moveStringToGroup
			final boolean sourceIsGroup = isSourceGroupWithChildren(sendingArea);
			rc.p("CobolMove.moveStringToGroup(");
			if (sourceIsGroup) {
				rc.p("CobolMove.groupToString(");
				rc.visit(sendingArea.getSendingAreaValueStmt().getCtx());
				rc.p(")");
			} else {
				CobolTypeEnum sourceType = cobolTypeService.getType(sendingArea.getSendingAreaValueStmt());
				if (sourceType == null) {
					sourceType = resolveSourceTypeFromASG(sendingArea);
				}
				// Reference modification produces a String regardless of the field's original type
				if (sourceHasReferenceModification(sendingArea)) {
					sourceType = CobolTypeEnum.STRING;
				}
				final boolean sourceIsNumeric = (sourceType == CobolTypeEnum.INTEGER || sourceType == CobolTypeEnum.FLOAT);
				if (sourceIsNumeric) {
					rc.p("String.valueOf(");
					rc.visit(sendingArea.getSendingAreaValueStmt().getCtx());
					rc.p(")");
				} else if (sourceType == null) {
					rc.p("String.valueOf(");
					rc.visit(sendingArea.getSendingAreaValueStmt().getCtx());
					rc.p(")");
				} else {
					rc.visit(sendingArea.getSendingAreaValueStmt().getCtx());
				}
			}
			rc.p(", ");
			rc.visit(call.getCtx());
			rc.p(");");
			rc.pNl(call);
		}
	}

	/**
	 * Tries to resolve the target DDE by extracting the data name from the call and
	 * searching the ASG with hyphen-insensitive matching. This handles cases like
	 * NOME-OPE being the COBOL reference for the NOMEOPE group defined in a copybook.
	 */
	private DataDescriptionEntry resolveByHyphenInsensitiveLookup(final Call call) {
		final String callName = call.getName();
		if (callName == null || callName.isEmpty()) {
			return null;
		}
		// Strip hyphens/underscores for comparison
		final String strippedName = callName.toUpperCase().replace("-", "").replace("_", "");
		try {
			final io.proleap.cobol.asg.metamodel.ProgramUnit pu = call.getProgramUnit();
			if (pu == null || pu.getDataDivision() == null) {
				return null;
			}
			final io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryContainer[] sections =
					new io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryContainer[] {
				pu.getDataDivision().getWorkingStorageSection(),
				pu.getDataDivision().getLinkageSection()
			};
			for (final io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryContainer section : sections) {
				if (section == null) {
					continue;
				}
				for (final DataDescriptionEntry entry : section.getDataDescriptionEntries()) {
					if (entry.getName() != null) {
						final String entryStripped = entry.getName().toUpperCase().replace("-", "").replace("_", "");
						if (entryStripped.equals(strippedName)) {
							return entry;
						}
					}
				}
			}
			// Try DDS truncated name matching: 10-char names with 5-digit suffix (e.g., NUM-C00001 → NUM-CODPAYS)
			final String nameUpper = callName.toUpperCase();
			if (nameUpper.length() == 10 && nameUpper.matches("[A-Z0-9_-]{5}\\d{5}")) {
				final String prefix = nameUpper.substring(0, 5).replace("-", "_");
				final int targetSeq = Integer.parseInt(nameUpper.substring(5));
				for (final io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryContainer section : sections) {
					if (section == null) {
						continue;
					}
					// Group matching entries by parent to find the N-th match
					final java.util.Map<DataDescriptionEntryGroup, java.util.List<DataDescriptionEntry>> byParent =
							new java.util.LinkedHashMap<>();
					for (final DataDescriptionEntry entry : section.getDataDescriptionEntries()) {
						if (entry.getName() != null && entry.getName().length() > 10) {
							final String entryNameUpper = entry.getName().toUpperCase();
							final String entryPrefix = entryNameUpper.length() >= 5
									? entryNameUpper.substring(0, 5).replace("-", "_") : "";
							if (entryPrefix.equals(prefix)) {
								final DataDescriptionEntryGroup parent = entry.getParentDataDescriptionEntryGroup();
								byParent.computeIfAbsent(parent, k -> new java.util.ArrayList<>()).add(entry);
							}
						}
					}
					for (final java.util.List<DataDescriptionEntry> entries : byParent.values()) {
						if (targetSeq <= entries.size()) {
							return entries.get(targetSeq - 1);
						}
					}
				}
			}
			// If the name ends in -DATA or -LENGTH, try resolving via the VARCHAR parent group
			final String upperName = callName.toUpperCase();
			if (upperName.endsWith("-DATA") || upperName.endsWith("-LENGTH")) {
				final String parentName = callName.substring(0, callName.length() - (upperName.endsWith("-DATA") ? 5 : 7));
				final String parentStripped = parentName.toUpperCase().replace("-", "").replace("_", "");
				final String suffix = upperName.endsWith("-DATA") ? "-DATA" : "-LENGTH";
				for (final io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryContainer section : sections) {
					if (section == null) {
						continue;
					}
					for (final DataDescriptionEntry entry : section.getDataDescriptionEntries()) {
						if (entry.getName() != null) {
							final String entryStripped = entry.getName().toUpperCase().replace("-", "").replace("_", "");
							if (entryStripped.equals(parentStripped)
									&& entry instanceof DataDescriptionEntryGroup
									&& isVarcharGroup(entry)) {
								// Found the VARCHAR parent — return the matching child
								for (final DataDescriptionEntry child : ((DataDescriptionEntryGroup) entry).getDataDescriptionEntries()) {
									if (child.getName() != null && child.getName().toUpperCase().endsWith(suffix)) {
										return child;
									}
								}
							}
						}
					}
				}
			}
		} catch (final Exception e) {
			// Fall through
		}
		return null;
	}

	/**
	 * When the source type cannot be determined from the Call (e.g., UNDEFINED_CALL for
	 * DDS truncated names like NUM-C00001), try to resolve the source DataDescriptionEntry
	 * by searching the ASG with hyphen-insensitive and DDS truncated name matching,
	 * then return the resolved entry's type.
	 */
	private CobolTypeEnum resolveSourceTypeFromASG(final MoveToSendingArea sendingArea) {
		final ValueStmt valueStmt = sendingArea.getSendingAreaValueStmt();
		if (!(valueStmt instanceof CallValueStmt)) {
			return null;
		}
		final Call sourceCall = ((CallValueStmt) valueStmt).getCall();
		if (sourceCall == null) {
			return null;
		}
		// Try resolving via the same hyphen-insensitive lookup used for targets
		final DataDescriptionEntry resolved = resolveByHyphenInsensitiveLookup(sourceCall);
		if (resolved != null) {
			return cobolTypeService.getType(resolved);
		}
		return null;
	}

	/**
	 * Detects a VARCHAR group pattern: a group with exactly 2 children whose names
	 * end in -LENGTH and -DATA (in any order).
	 */
	private boolean isVarcharGroup(final DataDescriptionEntry entry) {
		if (!(entry instanceof DataDescriptionEntryGroup)) {
			return false;
		}
		final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) entry;
		final java.util.List<DataDescriptionEntry> children = group.getDataDescriptionEntries();
		if (children == null || children.size() != 2) {
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

	/**
	 * Returns the -DATA child from a VARCHAR group.
	 */
	private DataDescriptionEntry getVarcharDataChild(final DataDescriptionEntryGroup group) {
		for (final DataDescriptionEntry child : group.getDataDescriptionEntries()) {
			final String name = child.getName().toUpperCase();
			if (name.endsWith("-DATA") || name.endsWith("-V")) {
				return child;
			}
		}
		return null;
	}

	/**
	 * Handles MOVE to a VARCHAR group target: emits assignment to the _data subfield
	 * instead of the group itself.
	 * MOVE source TO varchar-group  →  path.groupname_data = CobolMove.moveAlphanumericToAlphanumeric(source, dataLen)
	 */
	protected void printVarcharMoveTo(final Call call, final MoveToSendingArea sendingArea,
			final DataDescriptionEntryGroup varcharGroup, final RuleContext rc) {
		final DataDescriptionEntry dataChild = getVarcharDataChild(varcharGroup);
		final DataDescriptionEntry lengthChild = getVarcharLengthChild(varcharGroup);
		final Integer picLength = dataChild != null ? cobolPictureLengthService.getLength(dataChild) : null;

		// Check if the source is also a VARCHAR group — if so, copy field-by-field
		final DataDescriptionEntryGroup sourceVarcharGroup = resolveSourceVarcharGroup(sendingArea);
		if (sourceVarcharGroup != null) {
			final DataDescriptionEntry sourceDataChild = getVarcharDataChild(sourceVarcharGroup);
			final DataDescriptionEntry sourceLengthChild = getVarcharLengthChild(sourceVarcharGroup);

			// Copy DATA: target._data = CobolMove.moveAlphanumericToAlphanumeric(source._data, targetLen)
			rc.visit(call.getCtx());
			rc.p(".");
			rc.p(javaVariableIdentifierService.mapToIdentifier(dataChild));
			rc.p(" = CobolMove.moveAlphanumericToAlphanumeric(");
			rc.visit(sendingArea.getSendingAreaValueStmt().getCtx());
			rc.p(".");
			rc.p(javaVariableIdentifierService.mapToIdentifier(sourceDataChild));
			rc.p(", ");
			if (picLength != null) {
				rc.p("%d)", picLength);
			} else {
				rc.p("0)");
			}
			rc.p(";");
			rc.pNl(call);

			// Copy LENGTH: target._length = CobolMove.moveNumericToNumeric(source._length, 4, 0)
			if (lengthChild != null && sourceLengthChild != null) {
				rc.visit(call.getCtx());
				rc.p(".");
				rc.p(javaVariableIdentifierService.mapToIdentifier(lengthChild));
				rc.p(" = CobolMove.moveNumericToNumeric(");
				rc.visit(sendingArea.getSendingAreaValueStmt().getCtx());
				rc.p(".");
				rc.p(javaVariableIdentifierService.mapToIdentifier(sourceLengthChild));
				rc.p(", 4, 0);");
				rc.pNl();
			}
			return;
		}

		// Non-VARCHAR source: emit assignment to the _data subfield
		rc.visit(call.getCtx());
		rc.p(".");
		rc.p(javaVariableIdentifierService.mapToIdentifier(dataChild));
		// Now use a scalar move with the _DATA child's entry for PIC length
		rc.p(" = ");

		CobolTypeEnum sourceType = cobolTypeService.getType(sendingArea.getSendingAreaValueStmt());
		if (sourceType == null) {
			sourceType = resolveSourceTypeFromASG(sendingArea);
		}
		// When reference modification is applied, do NOT use the REDEFINES getter because
		// ref-mod is a substring operation on the alphanumeric base field.
		final boolean srcHasRefMod = sourceHasReferenceModification(sendingArea);
		final String sourceRedefinesGetter = srcHasRefMod
				? null : getSourceRedefinesGetterExpression(sendingArea);
		if (sourceRedefinesGetter == null) {
			sourceType = resolveRedefinesType(sendingArea.getSendingAreaValueStmt(), sourceType);
		}
		// When reference modification is applied to the source, the result is always a String.
		if (srcHasRefMod) {
			sourceType = CobolTypeEnum.STRING;
		}
		final boolean sourceIsGroup = isSourceGroupWithChildren(sendingArea);
		final boolean sourceIsNumeric = (sourceType == CobolTypeEnum.INTEGER || sourceType == CobolTypeEnum.FLOAT);

		final FigurativeConstantType figType = getFigurativeConstantType(sendingArea);

		if (figType != null && isZeroFigurativeConstant(figType)) {
			rc.p("CobolMove.moveZerosToAlphanumeric(");
			if (picLength != null) {
				rc.p("%d)", picLength);
			} else {
				rc.p("0)");
			}
		} else if (figType != null && isSpaceFigurativeConstant(figType)) {
			rc.p("CobolMove.moveAlphanumericToAlphanumeric(\" \", ");
			if (picLength != null) {
				rc.p("%d)", picLength);
			} else {
				rc.p("0)");
			}
		} else if (sourceIsNumeric) {
			final Integer sourceIntDigits = getSourceIntegerDigits(sendingArea);
			final Integer sourceDecDigitsVC = getSourceDecimalDigits(sendingArea);
			rc.p("CobolMove.moveNumericToAlphanumeric(");
			visitSourceExpr(sendingArea, false, sourceRedefinesGetter, rc);
			rc.p(", ");
			if (sourceIntDigits != null) {
				rc.p("%d, ", sourceIntDigits);
				// When source has implied decimal digits (V9), use 4-arg form
				if (sourceDecDigitsVC != null && sourceDecDigitsVC > 0) {
					rc.p("%d, ", sourceDecDigitsVC);
				}
			}
			if (picLength != null) {
				rc.p("%d)", picLength);
			} else {
				rc.p("0)");
			}
		} else {
			rc.p("CobolMove.moveAlphanumericToAlphanumeric(");
			if (sourceIsGroup) {
				visitSourceExpr(sendingArea, true, sourceRedefinesGetter, rc);
			} else if (sourceType == null) {
				rc.p("String.valueOf(");
				visitSourceExpr(sendingArea, false, sourceRedefinesGetter, rc);
				rc.p(")");
			} else {
				visitSourceExpr(sendingArea, false, sourceRedefinesGetter, rc);
			}
			rc.p(", ");
			if (picLength != null) {
				rc.p("%d)", picLength);
			} else {
				rc.p("0)");
			}
		}

		rc.p(";");
		rc.pNl(call);

		// Also set the VARCHAR _length subfield to the DATA PIC length.
		// In COBOL, MOVEing to a VARCHAR group fills the DATA portion to its full PIC length,
		// so the LENGTH must reflect the DATA capacity for correct SQL parameterization.
		if (lengthChild != null && picLength != null) {
			rc.visit(call.getCtx());
			rc.p(".");
			rc.p(javaVariableIdentifierService.mapToIdentifier(lengthChild));
			rc.p(" = BigDecimal.valueOf(%d);", picLength);
			rc.pNl();
		}
	}

	/**
	 * Returns the -LENGTH child from a VARCHAR group.
	 */
	private DataDescriptionEntry getVarcharLengthChild(final DataDescriptionEntryGroup group) {
		for (final DataDescriptionEntry child : group.getDataDescriptionEntries()) {
			final String name = child.getName().toUpperCase();
			if (name.endsWith("-LENGTH") || name.endsWith("-L")) {
				return child;
			}
		}
		return null;
	}

	/**
	 * Resolves the source of a MOVE sending area to its DataDescriptionEntryGroup,
	 * returning the group only if it is a VARCHAR group.  Returns null otherwise.
	 */
	private DataDescriptionEntryGroup resolveSourceVarcharGroup(final MoveToSendingArea sendingArea) {
		final ValueStmt valueStmt = sendingArea.getSendingAreaValueStmt();
		if (!(valueStmt instanceof CallValueStmt)) {
			return null;
		}
		final Call sourceCall = ((CallValueStmt) valueStmt).getCall();
		DataDescriptionEntry sourceEntry = dataDescriptionEntryService.getDataDescriptionEntry(sourceCall);
		if (sourceEntry == null) {
			sourceEntry = resolveByHyphenInsensitiveLookup(sourceCall);
		}
		if (sourceEntry != null && isVarcharGroup(sourceEntry)) {
			return (DataDescriptionEntryGroup) sourceEntry;
		}
		return null;
	}

	protected void printScalarMoveTo(final Call call, final MoveToSendingArea sendingArea,
			final DataDescriptionEntry targetEntry, final RuleContext rc) {
		// Check if the target has reference modification (e.g., FIELD(pos:len))
		final ReferenceModInfo refModInfo = findReferenceModification(call);
		if (refModInfo != null) {
			printRefModMoveTo(call, sendingArea, refModInfo, rc);
			return;
		}

		// Check if the target is a child of a group-over-elementary REDEFINES.
		// If so, use setter method instead of field assignment.
		final String overlaySetterPrefix = javaExpressionService.getGroupOverElementarySetterPrefix(call);
		if (overlaySetterPrefix != null) {
			printOverlaySetterMoveTo(call, sendingArea, targetEntry, overlaySetterPrefix, rc);
			return;
		}

		CobolTypeEnum sourceType = cobolTypeService.getType(sendingArea.getSendingAreaValueStmt());
		// When source type is null (e.g., DDS truncated name like NUM-C00001), try to resolve
		// the source DDE from the ASG and use its type for correct move conversion
		if (sourceType == null) {
			sourceType = resolveSourceTypeFromASG(sendingArea);
		}
		CobolTypeEnum targetType = cobolTypeService.getType(call);

		// When the call is unresolved (UNDEFINED_CALL) but we have a resolved DDE,
		// use the DDE's type for correct move conversion
		if (targetType == null && targetEntry != null) {
			targetType = cobolTypeService.getType(targetEntry);
		}

		// For REDEFINES source fields, check if there is a getter that reinterprets the value.
		// If so, use the REDEFINES field's own type (not the base field's type) and emit the getter.
		// However, when reference modification is applied (e.g., POIDSGRS(1)(2:10)), do NOT use
		// the REDEFINES getter because reference modification is a substring operation on the
		// alphanumeric base field, and the getter returns the wrong type (e.g., BigDecimal).
		final boolean sourceHasRefMod = sourceHasReferenceModification(sendingArea);
		final String sourceRedefinesGetter = sourceHasRefMod
				? null : getSourceRedefinesGetterExpression(sendingArea);

		if (sourceRedefinesGetter == null) {
			// No REDEFINES getter: the expression redirects to the original field,
			// so use the original field's type for correct conversion
			sourceType = resolveRedefinesType(sendingArea.getSendingAreaValueStmt(), sourceType);
		}
		// For REDEFINES targets, the expression writes to the original field,
		// so use the original field's type for correct conversion
		targetType = resolveRedefinesType(call, targetType);

		// When reference modification is applied to the source (e.g., ESTADO(2:1)),
		// the result is always a String (substring), regardless of the field's original type.
		// CobolReference.referenceModification() returns String, so we must treat source as alphanumeric.
		if (sourceHasRefMod) {
			sourceType = CobolTypeEnum.STRING;
		}

		// Detect if the source is a group item with children (inner class in Java).
		// When MOVEd to an elementary target, the group must be serialized to String first.
		final boolean sourceIsGroup = isSourceGroupWithChildren(sendingArea);

		final boolean sourceIsNumeric = (sourceType == CobolTypeEnum.INTEGER || sourceType == CobolTypeEnum.FLOAT);
		final boolean targetIsNumeric = (targetType == CobolTypeEnum.INTEGER || targetType == CobolTypeEnum.FLOAT);

		// For REDEFINES targets, resolve to the original field's DDE for PIC length lookup,
		// because the generated expression writes to the original field (e.g., pdelay not pdelayn)
		final DataDescriptionEntry effectiveTargetEntry = resolveRedefinesDDE(targetEntry);

		// When the effective target (after REDEFINES resolution) is a group with children,
		// the generated expression refers to a group type variable. Scalar assignments won't work;
		// use CobolMove.moveStringToGroup() instead of target = value.
		final boolean targetIsGroupAfterRedefines = effectiveTargetEntry != null
				&& effectiveTargetEntry != targetEntry
				&& effectiveTargetEntry instanceof DataDescriptionEntryGroup
				&& dataDescriptionEntryService.hasChildren(effectiveTargetEntry);

		if (targetIsGroupAfterRedefines) {
			// MOVE value TO redefines-of-group: byte-level overlay via moveStringToGroup
			rc.p("CobolMove.moveStringToGroup(");
			if (sourceIsGroup) {
				rc.p("CobolMove.groupToString(");
				visitSourceExpr(sendingArea, true, sourceRedefinesGetter, rc);
				rc.p(")");
			} else if (sourceRedefinesGetter != null) {
				rc.p(sourceRedefinesGetter);
			} else {
				rc.p("String.valueOf(");
				rc.visit(sendingArea.getSendingAreaValueStmt().getCtx());
				rc.p(")");
			}
			rc.p(", ");
			rc.visit(call.getCtx());
			rc.p(");");
			rc.pNl(call);
			return;
		}

		rc.visit(call.getCtx());
		rc.p(" = ");

		// Detect figurative constants (ZEROS, SPACES) for special handling
		final FigurativeConstantType figType = getFigurativeConstantType(sendingArea);

		// When target is BOOLEAN (PIC 1) and source is a figurative constant,
		// generate true/false directly. ZEROS/ZERO -> false (B"0"), SPACES -> false.
		if (CobolTypeEnum.BOOLEAN.equals(targetType) && figType != null) {
			if (isZeroFigurativeConstant(figType) || isSpaceFigurativeConstant(figType)) {
				rc.p("false");
			} else {
				rc.p("true");
			}
		} else if (figType != null && isZeroFigurativeConstant(figType) && !targetIsNumeric) {
			// Check if the target (before REDEFINES resolution) is a numeric-edited field
			// like PIC +9(09) or PIC -9(05). MOVE ZEROS to such fields must produce "+000000000" not "0000000000".
			final String targetOrigPic = getNumericEditedPic(targetEntry);
			if (targetOrigPic != null) {
				// MOVE ZEROS TO numeric-edited REDEFINES: format zero with sign and zero-padding
				final Integer targetPicLength = effectiveTargetEntry != null ? cobolPictureLengthService.getLength(effectiveTargetEntry) : null;
				final int len = (targetPicLength != null) ? targetPicLength : 10;
				rc.p("CobolMove.moveNumericToNumericEdited(BigDecimal.ZERO, \"%s\", %d)", targetOrigPic, len);
			} else {
				// MOVE ZEROS/ZEROES/ZERO TO alphanumeric: fill with '0' characters
				final Integer picLength = effectiveTargetEntry != null ? cobolPictureLengthService.getLength(effectiveTargetEntry) : null;
				rc.p("CobolMove.moveZerosToAlphanumeric(");
				if (picLength != null) {
					rc.p("%d)", picLength);
				} else {
					rc.visit(call.getCtx());
					rc.p(" != null ? ");
					rc.visit(call.getCtx());
					rc.p(".length() : 0)");
				}
			}
		} else if (figType != null && isSpaceFigurativeConstant(figType) && !targetIsNumeric) {
			// MOVE SPACES TO alphanumeric: fill with space characters
			final Integer picLength = effectiveTargetEntry != null ? cobolPictureLengthService.getLength(effectiveTargetEntry) : null;
			rc.p("CobolMove.moveAlphanumericToAlphanumeric(\" \", ");
			if (picLength != null) {
				rc.p("%d)", picLength);
			} else {
				rc.visit(call.getCtx());
				rc.p(" != null ? ");
				rc.visit(call.getCtx());
				rc.p(".length() : 0)");
			}
		} else if (CobolTypeEnum.BOOLEAN.equals(targetType) && CobolTypeEnum.BOOLEAN.equals(sourceType)) {
			// BOOLEAN to BOOLEAN: direct assignment
			visitSourceExpr(sendingArea, false, sourceRedefinesGetter, rc);
		} else if (CobolTypeEnum.BOOLEAN.equals(targetType)) {
			// Alphanumeric/numeric to BOOLEAN (PIC 1): convert "1" -> true, else false
			rc.p("\"1\".equals(String.valueOf(");
			if (sourceIsGroup) {
				visitSourceExpr(sendingArea, true, sourceRedefinesGetter, rc);
			} else {
				visitSourceExpr(sendingArea, false, sourceRedefinesGetter, rc);
			}
			rc.p("))");
		} else if (CobolTypeEnum.BOOLEAN.equals(sourceType) && !targetIsNumeric) {
			// BOOLEAN (PIC 1) to alphanumeric: convert boolean to "1"/"0" then move
			final Integer picLength = effectiveTargetEntry != null ? cobolPictureLengthService.getLength(effectiveTargetEntry) : null;
			rc.p("CobolMove.moveAlphanumericToAlphanumeric(");
			rc.p("(");
			visitSourceExpr(sendingArea, false, sourceRedefinesGetter, rc);
			rc.p(" ? \"1\" : \"0\")");
			rc.p(", ");
			if (picLength != null) {
				rc.p("%d)", picLength);
			} else {
				rc.p("1)");
			}
		} else if (!sourceIsNumeric && !targetIsNumeric) {
			// Check if the target (before REDEFINES resolution) is a numeric-edited field
			// like PIC +9(09) or PIC -9(05). If so, use moveAlphanumericToNumericEdited
			// which parses the alphanumeric source as a number and formats it.
			final String targetOrigPicAlpha = getNumericEditedPic(targetEntry);
			if (targetOrigPicAlpha != null) {
				final Integer targetPicLengthNE = effectiveTargetEntry != null ? cobolPictureLengthService.getLength(effectiveTargetEntry) : null;
				final int lenNE = (targetPicLengthNE != null) ? targetPicLengthNE : 10;
				rc.p("CobolMove.moveAlphanumericToNumericEdited(");
				if (sourceIsGroup) {
					visitSourceExpr(sendingArea, sourceIsGroup, sourceRedefinesGetter, rc);
				} else if (sourceType == null) {
					rc.p("String.valueOf(");
					visitSourceExpr(sendingArea, false, sourceRedefinesGetter, rc);
					rc.p(")");
				} else {
					visitSourceExpr(sendingArea, false, sourceRedefinesGetter, rc);
				}
				rc.p(", \"%s\", %d)", targetOrigPicAlpha, lenNE);
			} else {
			// Check if the SOURCE is a numeric-edited field (e.g., PIC +9(8)).
			// When a numeric-edited source is MOVEd to an alphanumeric target, COBOL uses
			// the edited representation as the source string (e.g., "+00000001" for value 1).
			final String sourceNumEditedPic = getSourceNumericEditedPic(sendingArea);
			if (sourceNumEditedPic != null) {
				final Integer targetPicLenNE = effectiveTargetEntry != null ? cobolPictureLengthService.getLength(effectiveTargetEntry) : null;
				final int lenNE2 = (targetPicLenNE != null) ? targetPicLenNE : 10;
				rc.p("CobolMove.moveNumericToNumericEdited(");
				visitSourceExpr(sendingArea, false, sourceRedefinesGetter, rc);
				rc.p(", \"%s\", %d)", sourceNumEditedPic, lenNE2);
			} else {
			// Alphanumeric to alphanumeric: use CobolMove for padding/truncation
			// Use PIC-defined length when available, fall back to runtime length
			final Integer picLength = effectiveTargetEntry != null ? cobolPictureLengthService.getLength(effectiveTargetEntry) : null;

			rc.p("CobolMove.moveAlphanumericToAlphanumeric(");
			// When source is a group item, serialize to String via groupToString.
			// When source type is unknown (null), wrap in String.valueOf() for safety
			// (e.g. SQLCODE is generated as BigDecimal but has no data description entry)
			if (sourceIsGroup) {
				visitSourceExpr(sendingArea, sourceIsGroup, sourceRedefinesGetter, rc);
			} else if (sourceType == null) {
				rc.p("String.valueOf(");
				visitSourceExpr(sendingArea, false, sourceRedefinesGetter, rc);
				rc.p(")");
			} else {
				visitSourceExpr(sendingArea, false, sourceRedefinesGetter, rc);
			}
			rc.p(", ");
			if (picLength != null) {
				rc.p("%d)", picLength);
			} else {
				rc.visit(call.getCtx());
				rc.p(" != null ? ");
				rc.visit(call.getCtx());
				rc.p(".length() : 0)");
			}
			}
			}
		} else if (sourceIsNumeric && !targetIsNumeric) {
			// Check if the target (before REDEFINES resolution) is a numeric-edited field
			// like PIC +9(09) or PIC -9(05). These need special formatting.
			final String targetOrigPic = getNumericEditedPic(targetEntry);
			if (targetOrigPic != null) {
				// Numeric to numeric-edited REDEFINES: format with sign and zero-padding
				final Integer targetPicLength = effectiveTargetEntry != null ? cobolPictureLengthService.getLength(effectiveTargetEntry) : null;
				final int len = (targetPicLength != null) ? targetPicLength : 10;
				rc.p("CobolMove.moveNumericToNumericEdited(");
				visitSourceExpr(sendingArea, false, sourceRedefinesGetter, rc);
				rc.p(", \"%s\", %d)", targetOrigPic, len);
			} else {
				// Check if the SOURCE is a numeric-edited field (e.g., PIC +9(8)).
				// When a numeric-edited source is MOVEd to an alphanumeric target, COBOL uses
				// the edited representation as the source string (e.g., "+00000001" for value 1).
				final String sourceNumEditedPicNtoA = getSourceNumericEditedPic(sendingArea);
				if (sourceNumEditedPicNtoA != null) {
					final Integer targetPicLenSrcNE = effectiveTargetEntry != null ? cobolPictureLengthService.getLength(effectiveTargetEntry) : null;
					final int lenSrcNE = (targetPicLenSrcNE != null) ? targetPicLenSrcNE : 10;
					rc.p("CobolMove.moveNumericToNumericEdited(");
					visitSourceExpr(sendingArea, false, sourceRedefinesGetter, rc);
					rc.p(", \"%s\", %d)", sourceNumEditedPicNtoA, lenSrcNE);
				} else {
				// Numeric to alphanumeric: use CobolMove.moveNumericToAlphanumeric(source, sourceIntDigits, sourceDecDigits, targetLength)
				// Per IBM manual: the sending numeric field's external representation (with leading zeros
				// per its PIC) is moved as alphanumeric. E.g., PIC 9(03) VALUE 5 -> "005" -> PIC X(03) -> "005".
				// For PIC 9(03)V9(02) VALUE 23.00 -> "23000" -> MOVE to PIC X(05) -> "23000".
				final Integer targetPicLength = effectiveTargetEntry != null ? cobolPictureLengthService.getLength(effectiveTargetEntry) : null;
				final Integer sourceIntDigits = getSourceIntegerDigits(sendingArea);
				final Integer sourceDecDigits = getSourceDecimalDigits(sendingArea);

				// COBOL byte-level semantics: when the source is a PIC 9 leaf that REDEFINES
				// (directly or through a group-over-elementary ancestor) an alphanumeric base,
				// MOVE to an alphanumeric target is a raw byte copy of the base bytes at the
				// leaf's offset — NOT a numeric interpretation. This preserves SPACES (as
				// happens when the underlying record is unpopulated) instead of converting
				// them to "0000...0" through BigDecimal round-trip.
				final String rawBaseExpr = getSourceGroupOverElementaryRawBaseExpression(sendingArea);
				if (rawBaseExpr != null) {
					if (targetPicLength != null) {
						rc.p("CobolMove.moveAlphanumericToAlphanumeric(%s, %d)", rawBaseExpr, targetPicLength);
					} else {
						rc.p("%s", rawBaseExpr);
					}
				} else {
				rc.p("CobolMove.moveNumericToAlphanumeric(");
				visitSourceExpr(sendingArea, false, sourceRedefinesGetter, rc);
				rc.p(", ");
				if (sourceIntDigits != null) {
					rc.p("%d, ", sourceIntDigits);
					// When source has implied decimal digits (V9), use 4-arg form
					if (sourceDecDigits != null && sourceDecDigits > 0) {
						rc.p("%d, ", sourceDecDigits);
					}
				}
				if (targetPicLength != null) {
					rc.p("%d)", targetPicLength);
				} else {
					rc.visit(call.getCtx());
					rc.p(" != null ? ");
					rc.visit(call.getCtx());
					rc.p(".length() : 0)");
				}
				}
				}
			}
		} else if (sourceIsNumeric && targetIsNumeric) {
			// Numeric to numeric: use CobolMove.moveNumericToNumeric to enforce PIC constraints
			// (decimal alignment, zero-fill, truncation of excess integer digits)
			final Integer targetIntDigits = getTargetIntegerDigits(effectiveTargetEntry);
			final Integer targetDecDigits = getTargetDecimalDigits(effectiveTargetEntry);

			if (targetIntDigits != null && targetDecDigits != null) {
				rc.p("CobolMove.moveNumericToNumeric(");
				visitSourceExpr(sendingArea, false, sourceRedefinesGetter, rc);
				rc.p(", %d, %d)", targetIntDigits, targetDecDigits);
			} else if (sourceRedefinesGetter != null) {
				// REDEFINES getter returns the correct numeric type; direct assignment is safe
				rc.p(sourceRedefinesGetter);
			} else {
				// No PIC info available, fall back to direct assignment
				rc.getTypedPrinter().printWithAdjustedType(sendingArea.getSendingAreaValueStmt().getCtx(),
						sourceType, targetType);
			}
		} else {
			// Alphanumeric to numeric: use CobolMove.moveAlphanumericToNumeric
			final Integer targetIntDigits = getTargetIntegerDigits(effectiveTargetEntry);
			final Integer targetDecDigits = getTargetDecimalDigits(effectiveTargetEntry);

			if (targetIntDigits != null && targetDecDigits != null) {
				rc.p("CobolMove.moveAlphanumericToNumeric(");
				visitSourceExpr(sendingArea, sourceIsGroup, sourceRedefinesGetter, rc);
				rc.p(", %d, %d)", targetIntDigits, targetDecDigits);
			} else if (sourceRedefinesGetter != null) {
				// REDEFINES getter handles the type conversion; use it directly
				rc.p(sourceRedefinesGetter);
			} else {
				// No PIC info available, fall back to type conversion
				rc.getTypedPrinter().printWithAdjustedType(sendingArea.getSendingAreaValueStmt().getCtx(),
						sourceType, targetType);
			}
		}

		rc.p(";");
		rc.pNl(call);
	}

	/**
	 * Handles MOVE to a target that is a child of a group-over-elementary REDEFINES.
	 * Instead of field assignment, uses the setter method (e.g., setDatmov_yyyy(value)).
	 */
	private void printOverlaySetterMoveTo(final Call call, final MoveToSendingArea sendingArea,
			final DataDescriptionEntry targetEntry, final String setterPrefix, final RuleContext rc) {
		CobolTypeEnum sourceType = cobolTypeService.getType(sendingArea.getSendingAreaValueStmt());
		if (sourceType == null) {
			sourceType = resolveSourceTypeFromASG(sendingArea);
		}
		// When reference modification is applied, do NOT use the REDEFINES getter.
		final boolean srcHasRefMod2 = sourceHasReferenceModification(sendingArea);
		final String sourceRedefinesGetter = srcHasRefMod2
				? null : getSourceRedefinesGetterExpression(sendingArea);
		if (sourceRedefinesGetter == null) {
			sourceType = resolveRedefinesType(sendingArea.getSendingAreaValueStmt(), sourceType);
		}
		if (srcHasRefMod2) {
			sourceType = CobolTypeEnum.STRING;
		}

		// Determine the target type from the DDE (the overlay child field)
		CobolTypeEnum targetType = targetEntry != null ? cobolTypeService.getType(targetEntry) : cobolTypeService.getType(call);
		final boolean sourceIsGroup = isSourceGroupWithChildren(sendingArea);
		final boolean sourceIsNumeric = (sourceType == CobolTypeEnum.INTEGER || sourceType == CobolTypeEnum.FLOAT);
		final boolean targetIsNumeric = (targetType == CobolTypeEnum.INTEGER || targetType == CobolTypeEnum.FLOAT);
		final FigurativeConstantType figType = getFigurativeConstantType(sendingArea);

		// Emit: setterPrefix(value);
		rc.p("%s(", setterPrefix);

		if (figType != null && isZeroFigurativeConstant(figType)) {
			if (targetIsNumeric) {
				rc.p("BigDecimal.ZERO");
			} else {
				final Integer picLength = targetEntry != null ? cobolPictureLengthService.getLength(targetEntry) : null;
				rc.p("CobolMove.moveZerosToAlphanumeric(%d)", picLength != null ? picLength : 1);
			}
		} else if (figType != null && isSpaceFigurativeConstant(figType)) {
			if (targetIsNumeric) {
				rc.p("BigDecimal.ZERO");
			} else {
				final Integer picLength = targetEntry != null ? cobolPictureLengthService.getLength(targetEntry) : null;
				rc.p("CobolMove.moveAlphanumericToAlphanumeric(\" \", %d)", picLength != null ? picLength : 1);
			}
		} else if (sourceIsNumeric && targetIsNumeric) {
			final Integer targetIntDigits = getTargetIntegerDigits(targetEntry);
			final Integer targetDecDigits = getTargetDecimalDigits(targetEntry);
			if (targetIntDigits != null && targetDecDigits != null) {
				rc.p("CobolMove.moveNumericToNumeric(");
				visitSourceExpr(sendingArea, false, sourceRedefinesGetter, rc);
				rc.p(", %d, %d)", targetIntDigits, targetDecDigits);
			} else {
				visitSourceExpr(sendingArea, false, sourceRedefinesGetter, rc);
			}
		} else if (!sourceIsNumeric && targetIsNumeric) {
			final Integer targetIntDigits = getTargetIntegerDigits(targetEntry);
			final Integer targetDecDigits = getTargetDecimalDigits(targetEntry);
			if (targetIntDigits != null && targetDecDigits != null) {
				rc.p("CobolMove.moveAlphanumericToNumeric(");
				visitSourceExpr(sendingArea, sourceIsGroup, sourceRedefinesGetter, rc);
				rc.p(", %d, %d)", targetIntDigits, targetDecDigits);
			} else {
				visitSourceExpr(sendingArea, sourceIsGroup, sourceRedefinesGetter, rc);
			}
		} else if (sourceIsNumeric && !targetIsNumeric) {
			final Integer picLength = targetEntry != null ? cobolPictureLengthService.getLength(targetEntry) : null;
			final Integer sourceIntDigits = getSourceIntegerDigits(sendingArea);
			final Integer sourceDecDigits2 = getSourceDecimalDigits(sendingArea);
			rc.p("CobolMove.moveNumericToAlphanumeric(");
			visitSourceExpr(sendingArea, false, sourceRedefinesGetter, rc);
			if (sourceIntDigits != null) {
				rc.p(", %d", sourceIntDigits);
				// When source has implied decimal digits (V9), use 4-arg form
				if (sourceDecDigits2 != null && sourceDecDigits2 > 0) {
					rc.p(", %d", sourceDecDigits2);
				}
			}
			rc.p(", %d)", picLength != null ? picLength : 1);
		} else {
			// alpha to alpha
			final Integer picLength = targetEntry != null ? cobolPictureLengthService.getLength(targetEntry) : null;
			if (sourceIsGroup) {
				rc.p("CobolMove.moveAlphanumericToAlphanumeric(");
				visitSourceExpr(sendingArea, true, sourceRedefinesGetter, rc);
				rc.p(", %d)", picLength != null ? picLength : 1);
			} else {
				visitSourceExpr(sendingArea, false, sourceRedefinesGetter, rc);
			}
		}

		rc.p(");");
		rc.pNl(call);
	}

	/**
	 * Emits the source expression, wrapping with CobolMove.groupToString() if the source is a group item.
	 * If a REDEFINES getter expression is provided, uses that instead of visiting the parse tree.
	 */
	private void visitSourceExpr(final MoveToSendingArea sendingArea, final boolean sourceIsGroup,
			final String redefinesGetter, final RuleContext rc) {
		if (redefinesGetter != null) {
			rc.p(redefinesGetter);
		} else if (sourceIsGroup) {
			rc.p("CobolMove.groupToString(");
			rc.visit(sendingArea.getSendingAreaValueStmt().getCtx());
			rc.p(")");
		} else {
			rc.visit(sendingArea.getSendingAreaValueStmt().getCtx());
		}
	}

	/**
	 * If the MOVE source references a REDEFINES field, builds the getter expression
	 * (e.g., lk_tl200600.getMaxpctdscs()) that reinterprets the base field's value.
	 * Returns null if the source is not a REDEFINES field.
	 */
	private String getSourceRedefinesGetterExpression(final MoveToSendingArea sendingArea) {
		final ValueStmt valueStmt = sendingArea.getSendingAreaValueStmt();
		if (!(valueStmt instanceof CallValueStmt)) {
			return null;
		}
		final Call call = ((CallValueStmt) valueStmt).getCall();
		return javaExpressionService.getRedefinesGetterExpression(call);
	}

	/**
	 * If the MOVE source is a numeric leaf that is a descendant of a group-over-elementary
	 * REDEFINES over an ALPHANUMERIC base, returns an expression that reads the raw bytes
	 * of the base at the leaf's offset (byte-copy semantics). This is the COBOL-correct
	 * representation when the source is used as alphanumeric (e.g., MOVE NRCOLIS-int TO
	 * WkNRCOLIS where NRCOLIS PIC X(11) is the base and NRCOLIS-int PIC 9(10) is the leaf).
	 * Returns null if not applicable.
	 */
	private String getSourceGroupOverElementaryRawBaseExpression(final MoveToSendingArea sendingArea) {
		final ValueStmt valueStmt = sendingArea.getSendingAreaValueStmt();
		if (!(valueStmt instanceof CallValueStmt)) {
			return null;
		}
		final Call call = ((CallValueStmt) valueStmt).getCall();
		return javaExpressionService.getGroupOverElementaryRawBaseExpression(call);
	}

	/**
	 * Checks whether the MOVE source is a group item with children.
	 * Such items are generated as inner classes in Java and cannot be used
	 * directly where a String or BigDecimal is expected.
	 */
	private boolean isSourceGroupWithChildren(final MoveToSendingArea sendingArea) {
		final ValueStmt valueStmt = sendingArea.getSendingAreaValueStmt();
		if (valueStmt instanceof CallValueStmt) {
			final Call sourceCall = ((CallValueStmt) valueStmt).getCall();
			DataDescriptionEntry sourceEntry = dataDescriptionEntryService.getDataDescriptionEntry(sourceCall);
			// If the initial resolution finds a non-group (e.g., LKTP0006.DESCOPE PIC X(300)
			// instead of PARM000600.DESC-OPE group), try hyphen-insensitive lookup which
			// may find the correct qualified entry in the target parent group.
			if (sourceEntry == null || !DataDescriptionEntryType.GROUP.equals(sourceEntry.getDataDescriptionEntryType())) {
				final DataDescriptionEntry altEntry = resolveByHyphenInsensitiveLookup(sourceCall);
				if (altEntry != null) {
					sourceEntry = altEntry;
				}
			}
			if (sourceEntry != null
					&& DataDescriptionEntryType.GROUP.equals(sourceEntry.getDataDescriptionEntryType())
					&& dataDescriptionEntryService.hasChildren(sourceEntry)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Gets the source field's integer digit count from its PIC clause.
	 * Resolves the sendingArea ValueStmt to its DataDescriptionEntry.
	 * <p>
	 * For numeric literals (IntegerLiteral / NumericLiteral), infers the
	 * source digit count from the literal's original textual form
	 * (e.g. <code>00</code> → 2 integer digits, <code>123</code> → 3).
	 * This mirrors IBM ILE COBOL behaviour where <code>MOVE 00 TO X(2)</code>
	 * produces <code>"00"</code> (not <code>"0 "</code>), because the literal is
	 * treated as PIC 9(2) in DISPLAY form before the alphanumeric MOVE.
	 * <p>
	 * Returns null if the source cannot be resolved.
	 */
	private Integer getSourceIntegerDigits(final MoveToSendingArea sendingArea) {
		final ValueStmt valueStmt = sendingArea.getSendingAreaValueStmt();
		if (valueStmt instanceof CallValueStmt) {
			final Call sourceCall = ((CallValueStmt) valueStmt).getCall();
			final DataDescriptionEntry sourceEntry = dataDescriptionEntryService.getDataDescriptionEntry(sourceCall);
			if (sourceEntry != null) {
				final String picString = getPictureString(sourceEntry);
				if (picString != null) {
					return cobolPictureLengthService.getIntegerPartLength(picString);
				}
			}
		}
		final int[] digits = getLiteralDigitCounts(valueStmt);
		if (digits != null) {
			return digits[0];
		}
		return null;
	}

	private Integer getSourceDecimalDigits(final MoveToSendingArea sendingArea) {
		final ValueStmt valueStmt = sendingArea.getSendingAreaValueStmt();
		if (valueStmt instanceof CallValueStmt) {
			final Call sourceCall = ((CallValueStmt) valueStmt).getCall();
			final DataDescriptionEntry sourceEntry = dataDescriptionEntryService.getDataDescriptionEntry(sourceCall);
			if (sourceEntry != null) {
				final String picString = getPictureString(sourceEntry);
				if (picString != null) {
					return cobolPictureLengthService.getFractionalPartLength(picString);
				}
			}
		}
		final int[] digits = getLiteralDigitCounts(valueStmt);
		if (digits != null) {
			return digits[1];
		}
		return null;
	}

	/**
	 * Returns {integerDigits, decimalDigits} for a numeric/integer literal ValueStmt
	 * based on the literal's original textual form, or null if the ValueStmt is not
	 * a numeric literal.
	 * <p>
	 * Example: literal text "00" → {2, 0}; "1234" → {4, 0}; "123.45" → {3, 2}.
	 * Any leading sign and decimal point are excluded from the digit counts.
	 */
	private int[] getLiteralDigitCounts(final ValueStmt valueStmt) {
		if (valueStmt == null) {
			return null;
		}
		String text = null;
		if (valueStmt instanceof io.proleap.cobol.asg.metamodel.valuestmt.IntegerLiteralValueStmt) {
			final io.proleap.cobol.asg.metamodel.valuestmt.IntegerLiteralValueStmt ilvs =
					(io.proleap.cobol.asg.metamodel.valuestmt.IntegerLiteralValueStmt) valueStmt;
			if (ilvs.getLiteral() != null && ilvs.getLiteral().getCtx() != null) {
				text = ilvs.getLiteral().getCtx().getText();
			}
		} else if (valueStmt instanceof LiteralValueStmt) {
			final Literal lit = ((LiteralValueStmt) valueStmt).getLiteral();
			if (lit != null && lit.getNumericLiteral() != null && lit.getNumericLiteral().getCtx() != null) {
				text = lit.getNumericLiteral().getCtx().getText();
			}
		}
		if (text == null) {
			return null;
		}
		// Strip quotes if any (should not happen for numeric literals, but be defensive)
		text = text.trim();
		if (text.isEmpty()) {
			return null;
		}
		// Strip leading sign
		int start = 0;
		if (text.charAt(0) == '+' || text.charAt(0) == '-') {
			start = 1;
		}
		int intDigits = 0;
		int decDigits = 0;
		boolean sawDecimal = false;
		for (int i = start; i < text.length(); i++) {
			final char c = text.charAt(i);
			if (c == '.' || c == ',') {
				// ILE COBOL permits both '.' and ',' as decimal separator (DECIMAL-POINT IS COMMA).
				if (sawDecimal) {
					return null; // malformed
				}
				sawDecimal = true;
			} else if (c >= '0' && c <= '9') {
				if (sawDecimal) {
					decDigits++;
				} else {
					intDigits++;
				}
			} else {
				// Non-digit char (not a sign/dot): not a plain numeric literal, bail out
				return null;
			}
		}
		if (intDigits + decDigits == 0) {
			return null;
		}
		return new int[] { intDigits, decDigits };
	}

	/**
	 * Returns the numeric-edited PIC string of the MOVE source field, or null if the
	 * source is not a numeric-edited field. This is used when a numeric-edited source
	 * (e.g., PIC +9(8)) is MOVEd to an alphanumeric target: the edited representation
	 * must be used as the source string.
	 */
	private String getSourceNumericEditedPic(final MoveToSendingArea sendingArea) {
		final ValueStmt valueStmt = sendingArea.getSendingAreaValueStmt();
		if (valueStmt instanceof CallValueStmt) {
			final Call sourceCall = ((CallValueStmt) valueStmt).getCall();
			DataDescriptionEntry sourceEntry = dataDescriptionEntryService.getDataDescriptionEntry(sourceCall);
			if (sourceEntry == null) {
				sourceEntry = resolveByHyphenInsensitiveLookup(sourceCall);
			}
			if (sourceEntry != null) {
				return getNumericEditedPic(sourceEntry);
			}
		}
		return null;
	}

	/**
	 * Gets the integer digit count from the target field's PIC clause.
	 * For PIC 9(03), returns 3. For PIC 9(5)V99, returns 5.
	 */
	private Integer getTargetIntegerDigits(final DataDescriptionEntry targetEntry) {
		if (targetEntry == null) {
			return null;
		}
		final String picString = getPictureString(targetEntry);
		if (picString == null) {
			return null;
		}
		return cobolPictureLengthService.getIntegerPartLength(picString);
	}

	/**
	 * Gets the decimal digit count from the target field's PIC clause.
	 * For PIC 9(03), returns 0. For PIC 9(5)V99, returns 2.
	 */
	private Integer getTargetDecimalDigits(final DataDescriptionEntry targetEntry) {
		if (targetEntry == null) {
			return null;
		}
		final String picString = getPictureString(targetEntry);
		if (picString == null) {
			return null;
		}
		return cobolPictureLengthService.getFractionalPartLength(picString);
	}

	/**
	 * Resolves a REDEFINES field's DataDescriptionEntry to the original field's DDE.
	 * This is needed because the generated expression writes to the original field,
	 * so PIC length should come from the original field (e.g., MEXPIRY PIC X(11)
	 * instead of MEXPIRYs PIC +9(10)).
	 */
	private DataDescriptionEntry resolveRedefinesDDE(final DataDescriptionEntry entry) {
		if (entry instanceof DataDescriptionEntryGroup) {
			final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) entry;
			if (group.getRedefinesClause() != null && group.getRedefinesClause().getRedefinesCall() != null) {
				final String origName = group.getRedefinesClause().getRedefinesCall().getName();
				final DataDescriptionEntryGroup parent = entry.getParentDataDescriptionEntryGroup();
				if (parent != null) {
					for (final DataDescriptionEntry sibling : parent.getDataDescriptionEntries()) {
						if (origName.equalsIgnoreCase(sibling.getName())) {
							return sibling;
						}
					}
				} else {
					// Top-level REDEFINES: search working storage and linkage sections
					final io.proleap.cobol.asg.metamodel.Program prog = entry.getProgram();
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
			}
		}
		return entry;
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
	 * Extracts the FigurativeConstantType from the sending area's ValueStmt,
	 * unwrapping the ArithmeticValueStmt -> Basis -> LiteralValueStmt hierarchy
	 * if needed. Returns null if the sending area is not a figurative constant.
	 */
	private FigurativeConstantType getFigurativeConstantType(final MoveToSendingArea sendingArea) {
		ValueStmt valueStmt = sendingArea.getSendingAreaValueStmt();

		// Unwrap ArithmeticValueStmt -> MultDivs -> Powers -> Basis -> inner ValueStmt
		if (valueStmt instanceof ArithmeticValueStmt) {
			final ArithmeticValueStmt arith = (ArithmeticValueStmt) valueStmt;
			if (arith.getMultDivs() != null && arith.getMultDivs().getPowers() != null
					&& arith.getMultDivs().getPowers().getBasis() != null) {
				valueStmt = arith.getMultDivs().getPowers().getBasis().getBasisValueStmt();
			}
		}
		// Unwrap Basis
		if (valueStmt instanceof Basis) {
			valueStmt = ((Basis) valueStmt).getBasisValueStmt();
		}

		if (valueStmt instanceof LiteralValueStmt) {
			final Literal literal = ((LiteralValueStmt) valueStmt).getLiteral();
			if (literal != null && literal.getLiteralType() == Literal.LiteralType.FIGURATIVE_CONSTANT) {
				final FigurativeConstant fc = literal.getFigurativeConstant();
				if (fc != null) {
					return fc.getFigurativeConstantType();
				}
			}
		}
		return null;
	}

	private boolean isZeroFigurativeConstant(final FigurativeConstantType type) {
		return type == FigurativeConstantType.ZERO
				|| type == FigurativeConstantType.ZEROS
				|| type == FigurativeConstantType.ZEROES;
	}

	private boolean isSpaceFigurativeConstant(final FigurativeConstantType type) {
		return type == FigurativeConstantType.SPACE
				|| type == FigurativeConstantType.SPACES;
	}

	/**
	 * For REDEFINES fields, the generated expression uses the original field
	 * (e.g., lknumrow for LkNumRowS), so the effective type is the original's type.
	 */
	private CobolTypeEnum resolveRedefinesType(final Object callOrValueStmt, final CobolTypeEnum declaredType) {
		Call resolvedCall = null;
		if (callOrValueStmt instanceof Call) {
			resolvedCall = (Call) callOrValueStmt;
		} else if (callOrValueStmt instanceof ValueStmt) {
			if (callOrValueStmt instanceof CallValueStmt) {
				resolvedCall = ((CallValueStmt) callOrValueStmt).getCall();
			}
		}

		if (resolvedCall != null) {
			final Call unwrapped = resolvedCall.unwrap();
			if (unwrapped != null && (unwrapped.getCallType() == CallType.DATA_DESCRIPTION_ENTRY_CALL
					|| unwrapped.getCallType() == CallType.TABLE_CALL)) {
				final DataDescriptionEntryCall ddec = (DataDescriptionEntryCall) unwrapped;
				final DataDescriptionEntry dde = ddec.getDataDescriptionEntry();
				if (dde instanceof DataDescriptionEntryGroup) {
					final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) dde;
					if (group.getRedefinesClause() != null && group.getRedefinesClause().getRedefinesCall() != null) {
						// This is a REDEFINES field; the expression uses the original field's type
						final String origName = group.getRedefinesClause().getRedefinesCall().getName();
						final DataDescriptionEntryGroup parent = dde.getParentDataDescriptionEntryGroup();
						if (parent != null) {
							for (final DataDescriptionEntry sibling : parent.getDataDescriptionEntries()) {
								if (origName.equalsIgnoreCase(sibling.getName())) {
									return cobolTypeService.getType(sibling);
								}
							}
						} else {
							// Top-level REDEFINES: search working storage and linkage sections
							final io.proleap.cobol.asg.metamodel.Program prog = dde.getProgram();
							if (prog != null) {
								for (final var cu : prog.getCompilationUnits()) {
									for (final var pu : cu.getProgramUnits()) {
										if (pu.getDataDivision() != null) {
											final var ws = pu.getDataDivision().getWorkingStorageSection();
											if (ws != null) {
												final DataDescriptionEntry found = ws.getDataDescriptionEntry(origName);
												if (found != null) return cobolTypeService.getType(found);
											}
											final var ls = pu.getDataDivision().getLinkageSection();
											if (ls != null) {
												final DataDescriptionEntry found = ls.getDataDescriptionEntry(origName);
												if (found != null) return cobolTypeService.getType(found);
											}
										}
									}
								}
							}
						}
					}
				}
			}
		}
		return declaredType;
	}

	/**
	 * Returns the PIC string if the target entry is a numeric-edited field
	 * (PIC starting with + or -), typically a REDEFINES of an alphanumeric field.
	 * Returns null if the target is not numeric-edited.
	 */
	private String getNumericEditedPic(final DataDescriptionEntry entry) {
		if (entry == null) {
			return null;
		}
		final String picString = getPictureString(entry);
		if (picString == null) {
			return null;
		}
		final String trimmed = picString.trim();
		if (trimmed.startsWith("+") || trimmed.startsWith("-")) {
			// Numeric-edited PIC like +9(09), -9(05), etc.
			return trimmed;
		}
		// Recognize Z, B, *, / editing characters in numeric-edited pictures
		// e.g., ZZZBZZZ, ZZZ,ZZZ.99, **,***.**+, Z(5)9, etc.
		final String upper = trimmed.toUpperCase();
		if (upper.matches("[Z*$B0/,. 9()+-]+") && upper.matches(".*[ZB*$/].*")) {
			return trimmed;
		}
		// Recognize plain numeric PIC (e.g., 9(09), S9(09), 9(5)V9(2)) on REDEFINES entries.
		// These are numeric fields that REDEFINE an alphanumeric base field; after resolveRedefinesType
		// the target type becomes STRING (the base field's type), so we land in the !targetIsNumeric branch.
		// We must format the value numerically (zero-padded) per the COBOL PIC, not treat it as alphanumeric.
		// The regex allows digits 0-9 inside repeat counts, e.g., 9(09) has '0' and '9' inside parens.
		if (isRedefinesEntry(entry) && upper.matches("[S9V0-9()]+") && upper.contains("9")) {
			return trimmed;
		}
		return null;
	}

	/**
	 * Checks whether the given data description entry has a REDEFINES clause.
	 */
	private boolean isRedefinesEntry(final DataDescriptionEntry entry) {
		if (entry instanceof DataDescriptionEntryGroup) {
			final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) entry;
			return group.getRedefinesClause() != null && group.getRedefinesClause().getRedefinesCall() != null;
		}
		return false;
	}

	/**
	 * Holds reference modification info extracted from a target call's context.
	 */
	private static class ReferenceModInfo {
		final String posExpr;
		final String lenExpr; // null if open-ended

		ReferenceModInfo(final String posExpr, final String lenExpr) {
			this.posExpr = posExpr;
			this.lenExpr = lenExpr;
		}
	}

	/**
	 * Checks whether the target call has reference modification (e.g., FIELD(pos:len)).
	 * Walks the call's context chain to find an IdentifierContext with a referenceModifier,
	 * or a TableCallContext with a referenceModifier.
	 */
	private ReferenceModInfo findReferenceModification(final Call call) {
		final io.proleap.cobol.asg.metamodel.Program program = call.getProgram();
		org.antlr.v4.runtime.ParserRuleContext ctx = call.getCtx();
		while (ctx != null) {
			if (ctx instanceof io.proleap.cobol.CobolParser.IdentifierContext) {
				final io.proleap.cobol.CobolParser.IdentifierContext identCtx =
						(io.proleap.cobol.CobolParser.IdentifierContext) ctx;
				if (identCtx.referenceModifier() != null) {
					return extractRefModInfo(identCtx.referenceModifier(), program);
				}
				// Also check tableCall child for referenceModifier
				if (identCtx.tableCall() != null && identCtx.tableCall().referenceModifier() != null) {
					return extractRefModInfo(identCtx.tableCall().referenceModifier(), program);
				}
				// Fallback: when the parser embeds the reference modifier deeper in the tree
				// (e.g., subscripted fields like FIELD OF GROUP(IDX)(pos:len) parsed as
				// qualifiedDataName without a tableCall node), search recursively.
				final io.proleap.cobol.CobolParser.ReferenceModifierContext deepRefMod =
						findReferenceModifierRecursive(identCtx);
				if (deepRefMod != null) {
					return extractRefModInfo(deepRefMod, program);
				}
				break;
			}
			if (ctx instanceof io.proleap.cobol.CobolParser.TableCallContext) {
				final io.proleap.cobol.CobolParser.TableCallContext tableCtx =
						(io.proleap.cobol.CobolParser.TableCallContext) ctx;
				if (tableCtx.referenceModifier() != null) {
					return extractRefModInfo(tableCtx.referenceModifier(), program);
				}
			}
			ctx = ctx.getParent();
		}
		// Also check the unwrapped call's context (e.g., when the outer context is a
		// CallDelegate wrapping a TableCall whose TableCallContext has the ref mod)
		final Call unwrapped = call.unwrap();
		if (unwrapped != call && unwrapped.getCtx() != null) {
			final org.antlr.v4.runtime.ParserRuleContext unwrappedCtx = unwrapped.getCtx();
			if (unwrappedCtx instanceof io.proleap.cobol.CobolParser.TableCallContext) {
				final io.proleap.cobol.CobolParser.TableCallContext tableCtx =
						(io.proleap.cobol.CobolParser.TableCallContext) unwrappedCtx;
				if (tableCtx.referenceModifier() != null) {
					return extractRefModInfo(tableCtx.referenceModifier(), program);
				}
			}
			final io.proleap.cobol.CobolParser.ReferenceModifierContext deepRefMod2 =
					findReferenceModifierRecursive(unwrappedCtx);
			if (deepRefMod2 != null) {
				return extractRefModInfo(deepRefMod2, program);
			}
		}
		return null;
	}

	/**
	 * Recursively searches the parse tree for a ReferenceModifierContext.
	 * Used as a fallback when the parser embeds the reference modifier deeper
	 * than expected (e.g., in subscripted qualified references).
	 */
	private io.proleap.cobol.CobolParser.ReferenceModifierContext findReferenceModifierRecursive(
			final org.antlr.v4.runtime.tree.ParseTree tree) {
		if (tree instanceof io.proleap.cobol.CobolParser.ReferenceModifierContext) {
			return (io.proleap.cobol.CobolParser.ReferenceModifierContext) tree;
		}
		for (int i = 0; i < tree.getChildCount(); i++) {
			final io.proleap.cobol.CobolParser.ReferenceModifierContext found =
					findReferenceModifierRecursive(tree.getChild(i));
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private ReferenceModInfo extractRefModInfo(
			final io.proleap.cobol.CobolParser.ReferenceModifierContext refMod,
			final io.proleap.cobol.asg.metamodel.Program program) {
		final String posExpr = convertArithmeticRefModExpr(
				refMod.characterPosition().arithmeticExpression(), program);
		final String lenExpr = refMod.length() != null
				? convertArithmeticRefModExpr(
						refMod.length().arithmeticExpression(), program) : null;
		return new ReferenceModInfo(posExpr, lenExpr);
	}

	/**
	 * Converts an arithmetic expression from a reference modifier (position or length)
	 * to a Java int expression, properly handling subtraction/addition operators and
	 * resolving identifiers through the ASG for qualified paths.
	 */
	private String convertArithmeticRefModExpr(
			final io.proleap.cobol.CobolParser.ArithmeticExpressionContext arithExpr,
			final io.proleap.cobol.asg.metamodel.Program program) {
		// Delegate to the expression service's tree-walking method which properly
		// handles qualified names (e.g., W-POSICAO OF W-PARSADOR → w_parsador.w_posicao.intValue())
		return javaExpressionService.convertArithExprCtxToJavaInt(arithExpr, program);
	}

	/**
	 * Generates a MOVE to a reference-modified target:
	 * MOVE source TO target(pos:len)  →  target = CobolReference.setReferenceModification(target, pos, len, sourceStr)
	 *
	 * When the target is numeric (BigDecimal), COBOL reference modification treats
	 * the field as a character string regardless of PIC type. So we must:
	 * 1. Convert the numeric target to its string representation
	 * 2. Apply setReferenceModification on the string
	 * 3. Convert the result back to numeric (BigDecimal)
	 */
	private void printRefModMoveTo(final Call call, final MoveToSendingArea sendingArea,
			final ReferenceModInfo refModInfo, final RuleContext rc) {
		// We need to emit the target base expression WITHOUT reference modification.
		// To do this, we get the base field identifier from the call name.
		final String baseField = getBaseFieldExpression(call);

		CobolTypeEnum sourceType = cobolTypeService.getType(sendingArea.getSendingAreaValueStmt());
		if (sourceType == null) {
			sourceType = resolveSourceTypeFromASG(sendingArea);
		}
		sourceType = resolveRedefinesType(sendingArea.getSendingAreaValueStmt(), sourceType);
		final boolean sourceIsGroup = isSourceGroupWithChildren(sendingArea);
		final boolean sourceIsNumeric = (sourceType == CobolTypeEnum.INTEGER || sourceType == CobolTypeEnum.FLOAT);

		// Determine if the target is numeric (BigDecimal) — reference modification
		// on a numeric field requires string conversion round-trip
		final DataDescriptionEntry targetDDE = dataDescriptionEntryService.getDataDescriptionEntry(call);
		final CobolTypeEnum targetType = (targetDDE != null) ? cobolTypeService.getType(targetDDE) : cobolTypeService.getType(call);
		final boolean targetIsNumeric = (targetType == CobolTypeEnum.INTEGER || targetType == CobolTypeEnum.FLOAT);

		// Check if target is a REDEFINES field — use getter/setter instead of direct variable access.
		// First check group-over-elementary, then elementary-over-elementary REDEFINES.
		String redefinesGetterExpr = javaExpressionService.getGroupOverElementaryGetterExpression(call);
		String redefinesSetterPrefix = javaExpressionService.getGroupOverElementarySetterPrefix(call);
		if (redefinesGetterExpr == null || redefinesSetterPrefix == null) {
			redefinesGetterExpr = javaExpressionService.getRedefinesGetterExpression(call);
			redefinesSetterPrefix = javaExpressionService.getRedefinesSetterPrefix(call);
		}
		final boolean useRedefinesAccessors = (redefinesGetterExpr != null && redefinesSetterPrefix != null);

		if (useRedefinesAccessors) {
			// REDEFINES target with reference modification:
			// setter(CobolReference.setReferenceModification(getter(), pos, len, sourceStr))
			rc.p("%s(CobolReference.setReferenceModification(%s, %s, %s, ",
					redefinesSetterPrefix, redefinesGetterExpr, refModInfo.posExpr,
					refModInfo.lenExpr != null ? refModInfo.lenExpr : "0");
		} else if (targetIsNumeric) {
			// Resolve PIC dimensions for the numeric target
			final Integer intDigits = getTargetIntegerDigits(targetDDE);
			final Integer decDigits = getTargetDecimalDigits(targetDDE);
			final int intD = (intDigits != null) ? intDigits : 8;
			final int decD = (decDigits != null) ? decDigits : 0;
			final int totalLen = intD + decD;

			// target = CobolMove.moveAlphanumericToNumeric(
			//   CobolReference.setReferenceModification(
			//     CobolMove.moveNumericToAlphanumeric(target, intDigits, totalLen),
			//     pos, len, sourceStr), intDigits, decDigits)
			rc.p("%s = CobolMove.moveAlphanumericToNumeric(CobolReference.setReferenceModification(CobolMove.moveNumericToAlphanumeric(%s, %d, %d), %s, %s, ",
					baseField, baseField, intD, totalLen, refModInfo.posExpr,
					refModInfo.lenExpr != null ? refModInfo.lenExpr : "0");
		} else {
			rc.p("%s = CobolReference.setReferenceModification(%s, %s, %s, ",
					baseField, baseField, refModInfo.posExpr,
					refModInfo.lenExpr != null ? refModInfo.lenExpr : "0");
		}

		// Emit source expression as String
		final FigurativeConstantType figType = getFigurativeConstantType(sendingArea);
		if (figType != null && isSpaceFigurativeConstant(figType)) {
			rc.p("\" \"");
		} else if (figType != null && isZeroFigurativeConstant(figType)) {
			rc.p("\"0\"");
		} else if (sourceIsGroup) {
			rc.p("CobolMove.groupToString(");
			rc.visit(sendingArea.getSendingAreaValueStmt().getCtx());
			rc.p(")");
		} else if (sourceIsNumeric) {
			final Integer srcIntDigits = getSourceIntegerDigits(sendingArea);
			final Integer srcDecDigits = getSourceDecimalDigits(sendingArea);
			final int srcInt = (srcIntDigits != null) ? srcIntDigits : 8;
			final int srcDec = (srcDecDigits != null) ? srcDecDigits : 0;
			final int srcDisplayLen = srcInt + srcDec;
			rc.p("CobolMove.moveNumericToAlphanumeric(");
			rc.visit(sendingArea.getSendingAreaValueStmt().getCtx());
			if (srcDec > 0) {
				rc.p(", %d, %d, %d)", srcInt, srcDec, srcDisplayLen);
			} else {
				rc.p(", %d, %d)", srcInt, srcDisplayLen);
			}
		} else {
			rc.visit(sendingArea.getSendingAreaValueStmt().getCtx());
		}

		if (useRedefinesAccessors) {
			// Close: setter(CobolReference.setReferenceModification(getter(), pos, len, sourceStr))
			rc.p("));");
		} else if (targetIsNumeric) {
			final Integer intDigits = getTargetIntegerDigits(targetDDE);
			final Integer decDigits = getTargetDecimalDigits(targetDDE);
			final int intD = (intDigits != null) ? intDigits : 8;
			final int decD = (decDigits != null) ? decDigits : 0;
			rc.p("), %d, %d);", intD, decD);
		} else {
			rc.p(");");
		}
		rc.pNl(call);
	}

	/**
	 * Gets the base field expression for a call, WITHOUT reference modification.
	 * Builds the fully qualified path (e.g., det_fb0350cs.nu_fiscal) by walking
	 * the DataDescriptionEntry parent hierarchy, so that qualified references like
	 * NU-FISCAL OF DET-FB0350CS are correctly resolved.
	 *
	 * For TableCall (subscripted references like MSG-MSGTXT(02)), includes the
	 * .get(index) accessor for OCCURS items in the hierarchy.
	 */
	private String getBaseFieldExpression(final Call call) {
		// Use the expression service's mapToCall which correctly handles subscripts
		// from the parse tree context (including cases where the ASG doesn't create
		// a TableCall but subscripts exist in qualifiedInData -> inTable -> tableCall).
		// Then strip any CobolReference.referenceModification(...) wrapper to get the
		// base field expression.
		final String fullExpr = javaExpressionService.mapToCall(call);
		return stripReferenceModificationWrapper(fullExpr);
	}

	/**
	 * Strips the CobolReference.referenceModification(...) wrapper from an expression,
	 * returning just the first argument (the base field).
	 * E.g., "CobolReference.referenceModification(a.b.get(0).c, pos, len)" -> "a.b.get(0).c"
	 */
	private String stripReferenceModificationWrapper(final String expr) {
		final String prefix = "CobolReference.referenceModification(";
		if (!expr.startsWith(prefix)) {
			return expr;
		}
		// Find the matching first argument by tracking parenthesis depth
		final String inner = expr.substring(prefix.length());
		int depth = 0;
		for (int i = 0; i < inner.length(); i++) {
			final char ch = inner.charAt(i);
			if (ch == '(') {
				depth++;
			} else if (ch == ')') {
				if (depth == 0) {
					// This is the closing paren of referenceModification - shouldn't happen before comma
					return inner.substring(0, i);
				}
				depth--;
			} else if (ch == ',' && depth == 0) {
				// Found the comma separating first arg from pos arg
				return inner.substring(0, i).trim();
			}
		}
		// Shouldn't reach here, but return the original expression as fallback
		return expr;
	}

	/**
	 * Checks whether the sending area's value statement contains reference modification
	 * (e.g., ESTADO(2:1)). When reference modification is applied, the result is always
	 * a substring (String), regardless of the original field's type. This is important
	 * because CobolReference.referenceModification() returns String, so the MOVE method
	 * must treat the source as alphanumeric.
	 */
	private boolean sourceHasReferenceModification(final MoveToSendingArea sendingArea) {
		final ValueStmt valueStmt = sendingArea.getSendingAreaValueStmt();
		if (valueStmt == null || valueStmt.getCtx() == null) {
			return false;
		}
		// Walk the context tree looking for a referenceModifier
		return containsReferenceModifier(valueStmt.getCtx());
	}

	/**
	 * Recursively checks whether the given parse tree context contains a referenceModifier.
	 */
	private boolean containsReferenceModifier(final org.antlr.v4.runtime.tree.ParseTree tree) {
		if (tree instanceof io.proleap.cobol.CobolParser.ReferenceModifierContext) {
			return true;
		}
		for (int i = 0; i < tree.getChildCount(); i++) {
			if (containsReferenceModifier(tree.getChild(i))) {
				return true;
			}
		}
		return false;
	}
}
