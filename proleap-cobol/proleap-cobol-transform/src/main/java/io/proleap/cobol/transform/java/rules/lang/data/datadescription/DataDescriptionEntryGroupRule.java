package io.proleap.cobol.transform.java.rules.lang.data.datadescription;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.antlr.v4.runtime.tree.ParseTree;

import io.proleap.cobol.CobolParser.DataDescriptionEntryContext;
import io.proleap.cobol.CobolParser.DataDescriptionEntryFormat1Context;
import io.proleap.cobol.CobolParser.WorkingStorageSectionContext;
import io.proleap.cobol.asg.metamodel.registry.ASGElementRegistry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry.DataDescriptionEntryType;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.data.datadescription.PictureClause;
import io.proleap.cobol.asg.metamodel.data.datadescription.RedefinesClause;
import io.proleap.cobol.asg.metamodel.data.datadescription.ValueClause;
import io.proleap.cobol.asg.metamodel.data.datadescription.ValueInterval;
import io.proleap.cobol.commons.datadescription.CobolPictureLengthService;
import io.proleap.cobol.commons.datadescription.CobolPictureStringService;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.transform.java.identifier.JavaIdentifierService;
import io.proleap.cobol.transform.java.identifier.method.JavaGetterIdentifierService;
import io.proleap.cobol.transform.java.identifier.method.JavaSetterIdentifierService;
import io.proleap.cobol.transform.java.identifier.variable.JavaVariableIdentifierService;
import io.proleap.cobol.transform.java.type.JavaInstanceService;
import io.proleap.cobol.transform.java.type.JavaInterfaceService;
import io.proleap.cobol.transform.java.type.JavaTypeService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class DataDescriptionEntryGroupRule
		extends CobolTransformRule<DataDescriptionEntryFormat1Context, DataDescriptionEntryGroup> {

	public static boolean GENERATE_ACCESSORS = false;

	@Inject
	private CobolPictureLengthService cobolPictureLengthService;

	@Inject
	private CobolTypeService cobolTypeService;

	@Inject
	private JavaIdentifierService javaIdentifierService;

	@Inject
	private JavaGetterIdentifierService javaGetterIdentifierService;

	@Inject
	private JavaInstanceService javaInstanceService;

	@Inject
	private JavaInterfaceService javaInterfaceService;

	@Inject
	private JavaSetterIdentifierService javaSetterIdentifierService;

	@Inject
	private JavaTypeService javaTypeService;

	@Inject
	private JavaVariableIdentifierService javaVariableIdentifierService;

	@Inject
	private CobolPictureStringService pictureStringService;

	@Override
	public void apply(final DataDescriptionEntryFormat1Context ctx,
			final DataDescriptionEntryGroup dataDescriptionEntryGroup, final RuleContext rc) {
		final List<DataDescriptionEntry> cotainedDataDescriptionEntries = dataDescriptionEntryGroup
				.getDataDescriptionEntries();

		// Filter out 88-level condition entries — they don't make a group into a class
		final List<DataDescriptionEntry> nonConditionEntries = cotainedDataDescriptionEntries.stream()
				.filter(e -> e.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION)
				.collect(Collectors.toList());

		if (nonConditionEntries.isEmpty()) {
			// When the ASG dropped the children of a REDEFINES group (a known parser
			// limitation), the entry looks scalar here.  But if the parse tree still
			// has children at a higher level number AND the base is an elementary
			// field, we must route through printGroupOverElementaryRedefines so that
			// correct substring-based getters are emitted for each child field.
			if (isGroupRedefinesElementary(dataDescriptionEntryGroup)
					&& hasParseTreeChildren(dataDescriptionEntryGroup)) {
				printGroupOverElementaryRedefines(dataDescriptionEntryGroup, rc);
			} else {
				printScalarVariable(ctx, dataDescriptionEntryGroup, rc);
			}

			if (GENERATE_ACCESSORS) {
				printGetter(dataDescriptionEntryGroup, rc);
				printSetter(dataDescriptionEntryGroup, rc);
			}
		} else {
			// Check for group-over-group REDEFINES (e.g., DDS -I/-O pattern)
			if (isGroupRedefinesWithSameFields(dataDescriptionEntryGroup)) {
				printRedefinesAlias(dataDescriptionEntryGroup, rc);
			} else if (isGroupRedefinesElementary(dataDescriptionEntryGroup)) {
				printGroupOverElementaryRedefines(dataDescriptionEntryGroup, rc);
			} else {
				printClass(dataDescriptionEntryGroup, rc);
				printInstanceVariable(dataDescriptionEntryGroup, rc);
			}

			if (GENERATE_ACCESSORS) {
				printGetter(dataDescriptionEntryGroup, rc);

				final int numberOfOccurrs = pictureStringService.getMaxOccurs(dataDescriptionEntryGroup);

				if (numberOfOccurrs == 1) {
					printSetter(dataDescriptionEntryGroup, rc);
				}
			}
		}
	}

	@Override
	public Class<DataDescriptionEntryFormat1Context> from() {
		return DataDescriptionEntryFormat1Context.class;
	}

	protected void printAnnotations(final DataDescriptionEntryGroup dataDescriptionEntryGroup, final RuleContext rc) {
		// Annotations removed — no javax.validation dependency needed
	}

	protected void printClass(final DataDescriptionEntryGroup dataDescriptionEntryGroup, final RuleContext rc) {
		rc.p("public class %s {", javaTypeService.mapToType(dataDescriptionEntryGroup));
		rc.pNl(dataDescriptionEntryGroup);
		rc.getPrinter().indent();

		final Set<String> seenNames = new HashSet<>();

		for (final DataDescriptionEntry dataDescriptionEntry : dataDescriptionEntryGroup.getDataDescriptionEntries()) {
			final String name = dataDescriptionEntry.getName();

			// Skip duplicate data description entries with the same name
			if (name != null && !seenNames.add(name)) {
				continue;
			}

			rc.visit(dataDescriptionEntry.getCtx());
		}

		rc.getPrinter().unindent();
		rc.p("}");
		rc.pNl();
		rc.pNl();
	}

	protected void printGetter(final DataDescriptionEntryGroup dataDescriptionEntryGroup, final RuleContext rc) {
		rc.p("public %s %s", javaInterfaceService.mapToInterface(dataDescriptionEntryGroup),
				javaGetterIdentifierService.mapToIdentifier(dataDescriptionEntryGroup));
		rc.p("(){ ");
		rc.p("return %s; ", javaVariableIdentifierService.mapToIdentifier(dataDescriptionEntryGroup));
		rc.p("}");
		rc.pNl(dataDescriptionEntryGroup);
	}

	protected void printInstanceVariable(final DataDescriptionEntryGroup dataDescriptionEntryGroup,
			final RuleContext rc) {
		final String type = javaTypeService.mapToType(dataDescriptionEntryGroup);

		final String interfaceType = javaInterfaceService.mapToInterface(dataDescriptionEntryGroup);
		final String instanceType = javaInstanceService.mapToInstance(dataDescriptionEntryGroup);

		final String identifier = javaVariableIdentifierService.mapToIdentifier(dataDescriptionEntryGroup);
		final int numberOfOccurrs = pictureStringService.getMaxOccurs(dataDescriptionEntryGroup);

		if (numberOfOccurrs > 1) {
			rc.p("protected %s %s = new %s();", interfaceType, identifier, instanceType);
			rc.pNl();
			rc.p("{");
			rc.pNl();
			rc.getPrinter().indent();

			if (numberOfOccurrs > 100) {
				// Use a for-loop to avoid exceeding the JVM 64KB method size limit
				rc.p("for (int _i = 0; _i < %d; _i++) { %s.add(new %s()); }", numberOfOccurrs, identifier, type);
				rc.pNl();
			} else {
				for (int i = 0; i < numberOfOccurrs; i++) {
					rc.p("%s.add(new %s());", identifier, type);
					rc.pNl();
				}
			}

			rc.getPrinter().unindent();
			rc.p("}");
			rc.pNl();
		} else {
			rc.p("protected %s %s = new %s();", instanceType, identifier, instanceType);
			rc.pNl();
		}
	}

	/**
	 * Checks whether this group entry is a REDEFINES of another group with the same field structure.
	 * This is the DDS -I/-O pattern where both groups have identical children.
	 */
	private boolean isGroupRedefinesWithSameFields(final DataDescriptionEntryGroup entry) {
		final RedefinesClause redefinesClause = entry.getRedefinesClause();
		if (redefinesClause == null || redefinesClause.getRedefinesCall() == null) {
			return false;
		}
		final String baseName = redefinesClause.getRedefinesCall().getName();
		final DataDescriptionEntry baseDde = findBaseDde(entry, baseName);
		if (baseDde == null || baseDde.getDataDescriptionEntryType() != DataDescriptionEntryType.GROUP) {
			return false;
		}
		final DataDescriptionEntryGroup baseGroup = (DataDescriptionEntryGroup) baseDde;

		// Both must be true groups (have non-condition children)
		final List<DataDescriptionEntry> baseChildren = baseGroup.getDataDescriptionEntries().stream()
				.filter(e -> e.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION)
				.collect(Collectors.toList());
		final List<DataDescriptionEntry> redefChildren = entry.getDataDescriptionEntries().stream()
				.filter(e -> e.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION)
				.collect(Collectors.toList());

		if (baseChildren.isEmpty() || redefChildren.isEmpty()) {
			return false;
		}
		if (baseChildren.size() != redefChildren.size()) {
			return false;
		}

		// Compare field names — if all match, it's the same-fields pattern
		for (int i = 0; i < baseChildren.size(); i++) {
			final String bn = baseChildren.get(i).getName();
			final String rn = redefChildren.get(i).getName();
			if (bn == null || rn == null || !bn.equalsIgnoreCase(rn)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * For a group REDEFINES with same fields, generate an alias field that points to the base object.
	 * E.g.: protected VA000000Type va000000_i = va000000;
	 */
	private void printRedefinesAlias(final DataDescriptionEntryGroup entry, final RuleContext rc) {
		final RedefinesClause redefinesClause = entry.getRedefinesClause();
		final String baseName = redefinesClause.getRedefinesCall().getName();
		final DataDescriptionEntry baseDde = findBaseDde(entry, baseName);
		final String baseType = javaTypeService.mapToType((DataDescriptionEntryGroup) baseDde);
		final String baseId = javaVariableIdentifierService.mapToIdentifier((DataDescriptionEntryGroup) baseDde);
		final String redefId = javaVariableIdentifierService.mapToIdentifier(entry);

		rc.p("protected %s %s = %s;", baseType, redefId, baseId);
		rc.pNl(entry);
	}

	/**
	 * Checks whether this group entry REDEFINES an elementary (scalar) field.
	 * This is the pattern:
	 *   02 DATMOV PIC X(10).
	 *   02 DATMOVm REDEFINES DATMOV.
	 *     03 DATMOV-yyyy PIC 9(4).
	 *     03 DATMOV-sep1 PIC X.
	 *     ...
	 */
	private boolean isGroupRedefinesElementary(final DataDescriptionEntryGroup entry) {
		final RedefinesClause redefinesClause = entry.getRedefinesClause();
		if (redefinesClause == null || redefinesClause.getRedefinesCall() == null) {
			return false;
		}
		final String baseName = redefinesClause.getRedefinesCall().getName();
		final DataDescriptionEntry baseDde = findBaseDde(entry, baseName);
		if (baseDde == null) {
			return false;
		}
		// The base must be a scalar (elementary) field, not a group with children
		if (baseDde.getDataDescriptionEntryType() == DataDescriptionEntryType.GROUP) {
			final DataDescriptionEntryGroup baseGroup = (DataDescriptionEntryGroup) baseDde;
			final boolean hasNonConditionChildren = baseGroup.getDataDescriptionEntries().stream()
					.anyMatch(e -> e.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION);
			if (hasNonConditionChildren) {
				return false; // base is a true group, not elementary
			}
		}
		// The base must be a string type (PIC X/A) or numeric type (PIC 9/S9)
		final CobolTypeEnum baseType = cobolTypeService.getType(baseDde);
		return CobolTypeEnum.STRING.equals(baseType)
				|| CobolTypeEnum.INTEGER.equals(baseType)
				|| CobolTypeEnum.FLOAT.equals(baseType);
	}

	/**
	 * Recursively computes the total byte length of a group by summing
	 * the lengths of all its leaf (elementary) children.
	 */
	private int computeGroupLength(final DataDescriptionEntryGroup group) {
		int total = 0;
		for (final DataDescriptionEntry child : group.getDataDescriptionEntries()) {
			if (child.getDataDescriptionEntryType() == DataDescriptionEntryType.CONDITION) {
				continue;
			}
			final Integer childLen = cobolPictureLengthService.getLength(child);
			if (childLen != null) {
				total += childLen;
			} else if (child instanceof DataDescriptionEntryGroup) {
				// Child is a group without PIC — recurse into it
				total += computeGroupLength((DataDescriptionEntryGroup) child);
			} else {
				total += 1; // fallback
			}
		}
		return total;
	}

	/**
	 * For a group that REDEFINES an elementary PIC X(n) field, generate getter/setter
	 * methods for each child field that use substring operations on the base field.
	 * This preserves COBOL REDEFINES memory-sharing semantics.
	 *
	 * When a child is itself a group (e.g., 02 LkServerQuestion with sub-fields),
	 * we generate an inner class where each leaf field is a public field, plus a
	 * getter for the group that returns a typed instance. We also generate a
	 * syncToBase() method on the inner class that flushes all field values back
	 * to the correct offsets in the base string, and a syncFromBase() method that
	 * populates the fields from the base string.
	 */
	private void printGroupOverElementaryRedefines(final DataDescriptionEntryGroup entry, final RuleContext rc) {
		final RedefinesClause redefinesClause = entry.getRedefinesClause();
		final String baseName = redefinesClause.getRedefinesCall().getName();
		String baseId = javaIdentifierService.mapToIdentifier(baseName);

		// Determine if the base field is numeric (PIC 9/S9) vs string (PIC X/A)
		final DataDescriptionEntry baseDde = findBaseDde(entry, baseName);
		CobolTypeEnum resolvedBaseType = baseDde != null ? cobolTypeService.getType(baseDde) : null;

		// When the immediate base is itself a REDEFINES (e.g., PIC +9(9) REDEFINES PIC X(10)),
		// it has no Java storage — only getters/setters exist.
		// Follow the REDEFINES chain to the ultimate storage field for both baseId and baseIsNumeric.
		if (baseDde instanceof DataDescriptionEntryGroup) {
			DataDescriptionEntry storageEntry = baseDde;
			while (storageEntry instanceof DataDescriptionEntryGroup) {
				final RedefinesClause rc2 = ((DataDescriptionEntryGroup) storageEntry).getRedefinesClause();
				if (rc2 == null || rc2.getRedefinesCall() == null) break;
				final String redName = rc2.getRedefinesCall().getName();
				final DataDescriptionEntry resolved = findBaseDde(storageEntry, redName);
				if (resolved == null || resolved == storageEntry) break;
				storageEntry = resolved;
			}
			if (storageEntry != baseDde) {
				baseId = javaIdentifierService.mapToIdentifier(storageEntry.getName());
				resolvedBaseType = cobolTypeService.getType(storageEntry);
			}
		}

		final boolean baseIsNumeric = CobolTypeEnum.INTEGER.equals(resolvedBaseType) || CobolTypeEnum.FLOAT.equals(resolvedBaseType);

		// For numeric base: compute the base PIC's integer and decimal digit counts
		int baseIntDigits = 0;
		int baseDecDigits = 0;
		if (baseIsNumeric && baseDde instanceof DataDescriptionEntryGroup) {
			final PictureClause basePic = ((DataDescriptionEntryGroup) baseDde).getPictureClause();
			if (basePic != null) {
				final String basePicStr = basePic.getPictureString();
				final Integer bi = cobolPictureLengthService.getIntegerPartLength(basePicStr);
				final Integer bd = cobolPictureLengthService.getFractionalPartLength(basePicStr);
				baseIntDigits = bi != null ? bi : 0;
				baseDecDigits = bd != null ? bd : 0;
			}
		}

		// Qualify getter/setter names with the REDEFINES group name to prevent
		// collisions when multiple sibling REDEFINES groups have children with the same name
		final String groupId = javaVariableIdentifierService.mapToIdentifier(entry);
		final String capGroupId = Character.toUpperCase(groupId.charAt(0)) + groupId.substring(1);

		// Calculate byte offsets for each child field and emit getters/setters.
		// Group children are flattened: their leaf fields get their own accessors
		// with qualified names (e.g., getWkserviceqst_Lkserverquestion_Lklocale).

		// BUG WORKAROUND: The ProLeap parser ASG sometimes drops children of a REDEFINES
		// group entirely (they never appear in the ASG). Walk the parse tree to find any
		// missing children and emit their getter/setter before processing the known ones.
		// Returns the total byte length of the missing children so offset can be adjusted.
		final int missingChildrenLen = emitMissingRedefinesChildren(entry, baseId, capGroupId,
				baseIsNumeric, baseIntDigits, baseDecDigits, rc);

		int offset = missingChildrenLen;
		for (final DataDescriptionEntry child : entry.getDataDescriptionEntries()) {
			if (child.getDataDescriptionEntryType() == DataDescriptionEntryType.CONDITION) {
				continue; // Skip 88-level conditions
			}

			final String childId = javaVariableIdentifierService.mapToIdentifier(child);
			final String capChildId = capGroupId + "_" + Character.toUpperCase(childId.charAt(0)) + childId.substring(1);
			final CobolTypeEnum childType = cobolTypeService.getType(child);

			// Compute child length: for groups without PIC, recursively sum leaf lengths
			final Integer childLenObj = cobolPictureLengthService.getLength(child);
			final int childLen;
			if (childLenObj != null) {
				childLen = childLenObj;
			} else if (child instanceof DataDescriptionEntryGroup) {
				childLen = computeGroupLength((DataDescriptionEntryGroup) child);
			} else {
				childLen = 1;
			}

			final int start = offset;
			final int end = offset + childLen;

			// Check if this child is a group (has non-condition children)
			final boolean childIsGroup = (child instanceof DataDescriptionEntryGroup)
					&& ((DataDescriptionEntryGroup) child).getDataDescriptionEntries().stream()
							.anyMatch(e -> e.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION);


			if (childIsGroup) {
				// --- GROUP CHILD: recursively flatten all leaf descendants ---
				// Generate getter/setter methods for each leaf field using substring
				// operations on the base string. The accessor name includes the full
				// hierarchy path to prevent collisions.
				final DataDescriptionEntryGroup childGroup = (DataDescriptionEntryGroup) child;
				emitFlattenedLeafAccessors(childGroup, baseId, start, capChildId,
						baseIsNumeric, baseIntDigits, baseDecDigits, rc);

				// Also emit a group-level string getter/setter for this group child
				// (used when the group is referenced as a whole)
				if (!baseIsNumeric) {
					rc.p("public String get%s() {", capChildId);
					rc.p(" if (%s == null || %s.length() < %d) return \"%s\";",
							baseId, baseId, end, " ".repeat(Math.max(1, childLen)));
					rc.p(" return %s.substring(%d, %d); }", baseId, start, end);
					rc.pNl(child);

					rc.p("public void set%s(String val) {", capChildId);
					rc.p(" String s = CobolMove.moveAlphanumericToAlphanumeric(val, %d);", childLen);
					rc.p(" %s = CobolMove.overlayString(%s, s, %d, %d); }", baseId, baseId, start, end);
					rc.pNl(child);
				}
			} else if (baseIsNumeric) {
				// --- NUMERIC BASE: use numericToDigitString/digitStringToNumeric ---
				if (CobolTypeEnum.INTEGER.equals(childType) || CobolTypeEnum.FLOAT.equals(childType)) {
					// Numeric child over numeric base
					rc.p("public BigDecimal get%s() {", capChildId);
					rc.p(" String ds = CobolMove.numericToDigitString(%s, %d, %d);", baseId, baseIntDigits, baseDecDigits);
					rc.p(" try { return new BigDecimal(ds.substring(%d, %d).trim()); }", start, end);
					rc.p(" catch (Exception e) { return BigDecimal.ZERO; } }");
					rc.pNl(child);

					rc.p("public void set%s(BigDecimal val) {", capChildId);
					rc.p(" String ds = CobolMove.numericToDigitString(%s, %d, %d);", baseId, baseIntDigits, baseDecDigits);
					rc.p(" String sv = String.format(\"%%0%dd\", val == null ? 0 : val.toBigInteger().abs());", childLen);
					rc.p(" if (sv.length() > %d) sv = sv.substring(sv.length() - %d);", childLen, childLen);
					rc.p(" ds = ds.substring(0, %d) + sv + ds.substring(%d);", start, end);
					rc.p(" %s = CobolMove.digitStringToNumeric(ds, %d); }", baseId, baseDecDigits);
					rc.pNl(child);
				} else {
					// String child over numeric base (unlikely but handle gracefully)
					rc.p("public String get%s() {", capChildId);
					rc.p(" String ds = CobolMove.numericToDigitString(%s, %d, %d);", baseId, baseIntDigits, baseDecDigits);
					rc.p(" return ds.substring(%d, %d); }", start, end);
					rc.pNl(child);

					rc.p("public void set%s(String val) {", capChildId);
					rc.p(" String ds = CobolMove.numericToDigitString(%s, %d, %d);", baseId, baseIntDigits, baseDecDigits);
					rc.p(" String sv = CobolMove.moveAlphanumericToAlphanumeric(val, %d);", childLen);
					rc.p(" ds = ds.substring(0, %d) + sv + ds.substring(%d);", start, end);
					rc.p(" %s = CobolMove.digitStringToNumeric(ds, %d); }", baseId, baseDecDigits);
					rc.pNl(child);
				}
			} else {
				// --- STRING BASE: existing logic ---
				if (CobolTypeEnum.INTEGER.equals(childType) || CobolTypeEnum.FLOAT.equals(childType)) {
					// Numeric child: getter parses substring to BigDecimal, setter formats back
					final String picStr = (child instanceof DataDescriptionEntryGroup)
							? ((DataDescriptionEntryGroup) child).getPictureClause() != null
								? ((DataDescriptionEntryGroup) child).getPictureClause().getPictureString() : null
							: null;
					final Integer intPart = picStr != null ? cobolPictureLengthService.getIntegerPartLength(picStr) : null;
					final int intDigits = intPart != null ? intPart : childLen;
					final Integer decPart = picStr != null ? cobolPictureLengthService.getFractionalPartLength(picStr) : null;
					final int decDigits = decPart != null ? decPart : 0;

					rc.p("public BigDecimal get%s() {", capChildId);
					rc.p(" if (%s == null || %s.length() < %d) return BigDecimal.ZERO;", baseId, baseId, end);
					if (decDigits > 0) {
						// COBOL REDEFINES numeric with implied decimal (V): divide by 10^decDigits
						// e.g., PIC 9(03)V99 with "00100" => BigDecimal("00100")=100 => movePointLeft(2) => 1.00
						rc.p(" try { return new BigDecimal(%s.substring(%d, %d).trim()).movePointLeft(%d); }", baseId, start, end, decDigits);
					} else {
						rc.p(" try { return new BigDecimal(%s.substring(%d, %d).trim()); }", baseId, start, end);
					}
					rc.p(" catch (Exception e) { return BigDecimal.ZERO; } }");
					rc.pNl(child);

					rc.p("public void set%s(BigDecimal val) {", capChildId);
					if (decDigits > 0) {
						rc.p(" String s = CobolMove.moveNumericToAlphanumeric(val, %d, %d, %d);", intDigits, decDigits, childLen);
					} else {
						rc.p(" String s = CobolMove.moveNumericToAlphanumeric(val, %d, %d);", intDigits, childLen);
					}
					rc.p(" %s = CobolMove.overlayString(%s, s, %d, %d); }", baseId, baseId, start, end);
					rc.pNl(child);

					// For OCCURS children: generate indexed getter/setter (1-based COBOL index)
					if (child instanceof DataDescriptionEntryGroup
							&& ((DataDescriptionEntryGroup) child).getOccursClauses() != null
							&& !((DataDescriptionEntryGroup) child).getOccursClauses().isEmpty()) {
						rc.p("public BigDecimal get%s(int index) {", capChildId);
						rc.p(" int s = (index - 1) * %d; int e = s + %d;", childLen, childLen);
						rc.p(" if (%s == null || %s.length() < e) return BigDecimal.ZERO;", baseId, baseId);
						if (decDigits > 0) {
							rc.p(" try { return new BigDecimal(%s.substring(s, e).trim()).movePointLeft(%d); }", baseId, decDigits);
						} else {
							rc.p(" try { return new BigDecimal(%s.substring(s, e).trim()); }", baseId);
						}
						rc.p(" catch (Exception ex) { return BigDecimal.ZERO; } }");
						rc.pNl(child);

						rc.p("public void set%s(int index, BigDecimal val) {", capChildId);
						rc.p(" int s = (index - 1) * %d; int e = s + %d;", childLen, childLen);
						if (decDigits > 0) {
							rc.p(" String sv = CobolMove.moveNumericToAlphanumeric(val, %d, %d, %d);", intDigits, decDigits, childLen);
						} else {
							rc.p(" String sv = CobolMove.moveNumericToAlphanumeric(val, %d, %d);", intDigits, childLen);
						}
						rc.p(" %s = CobolMove.overlayString(%s, sv, s, e); }", baseId, baseId);
						rc.pNl(child);
					}
				} else {
					// String child: getter returns substring, setter overlays
					rc.p("public String get%s() {", capChildId);
					rc.p(" if (%s == null || %s.length() < %d) return \"%s\";",
							baseId, baseId, end, " ".repeat(Math.max(1, childLen)));
					rc.p(" return %s.substring(%d, %d); }", baseId, start, end);
					rc.pNl(child);

					rc.p("public void set%s(String val) {", capChildId);
					rc.p(" String s = CobolMove.moveAlphanumericToAlphanumeric(val, %d);", childLen);
					rc.p(" %s = CobolMove.overlayString(%s, s, %d, %d); }", baseId, baseId, start, end);
					rc.pNl(child);

					// For OCCURS children: generate indexed getter/setter (1-based COBOL index)
					if (child instanceof DataDescriptionEntryGroup
							&& ((DataDescriptionEntryGroup) child).getOccursClauses() != null
							&& !((DataDescriptionEntryGroup) child).getOccursClauses().isEmpty()) {
						rc.p("public String get%s(int index) {", capChildId);
						rc.p(" int s = (index - 1) * %d; int e = s + %d;", childLen, childLen);
						rc.p(" if (%s == null || %s.length() < e) return \"%s\";", baseId, baseId, " ".repeat(Math.max(1, childLen)));
						rc.p(" return %s.substring(s, e); }", baseId);
						rc.pNl(child);

						rc.p("public void set%s(int index, String val) {", capChildId);
						rc.p(" int s = (index - 1) * %d; int e = s + %d;", childLen, childLen);
						rc.p(" String sv = CobolMove.moveAlphanumericToAlphanumeric(val, %d);", childLen);
						rc.p(" %s = CobolMove.overlayString(%s, sv, s, e); }", baseId, baseId);
						rc.pNl(child);
					}
				}
			}

			offset = end;
		}

		// Generate a group-level getter that returns the entire base field value.
		// When COBOL moves a group REDEFINES to a target, it treats the group as alphanumeric.
		// E.g., MOVE WK-BPBARREN(WK-I) TO LKBPBARREs(WK-I) uses the group as a whole.
		final String groupGetId = Character.toUpperCase(groupId.charAt(0)) + groupId.substring(1);
		if (baseIsNumeric) {
			rc.p("public String get%s() { return CobolMove.numericToDigitString(%s, %d, %d); }",
					groupGetId, baseId, baseIntDigits, baseDecDigits);
		} else {
			rc.p("public String get%s() { return %s; }", groupGetId, baseId);
		}
		rc.pNl(entry);
		// Generate a group-level setter for completeness
		if (baseIsNumeric) {
			rc.p("public void set%s(String val) { %s = CobolMove.moveAlphanumericToNumeric(val, %d, %d); }",
					groupGetId, baseId, baseIntDigits, baseDecDigits);
		} else {
			final Integer totalLen = cobolPictureLengthService.getLength(baseDde);
			final int len = totalLen != null ? totalLen : offset;
			rc.p("public void set%s(String val) { %s = CobolMove.moveAlphanumericToAlphanumeric(val, %d); }",
					groupGetId, baseId, len);
		}
		rc.pNl(entry);
	}

	/**
	 * Recursively emits flattened getter/setter methods for all leaf fields within
	 * a group child of a REDEFINES-over-elementary. Each leaf field gets its own
	 * getter/setter that operates directly on the base string via substring operations.
	 * The accessor names include the full hierarchy path to prevent collisions.
	 *
	 * @param group     the group entry whose descendants to flatten
	 * @param baseId    the Java identifier of the base string field
	 * @param groupOffset the byte offset where this group starts within the base string
	 * @param parentCapId the capitalized qualified accessor name prefix
	 * @param baseIsNumeric whether the base field is numeric
	 * @param baseIntDigits integer digits of the base PIC (if numeric)
	 * @param baseDecDigits decimal digits of the base PIC (if numeric)
	 * @param rc        the rule context for output
	 */
	private void emitFlattenedLeafAccessors(final DataDescriptionEntryGroup group, final String baseId,
			final int groupOffset, final String parentCapId,
			final boolean baseIsNumeric, final int baseIntDigits, final int baseDecDigits,
			final RuleContext rc) {
		int localOffset = 0;
		for (final DataDescriptionEntry child : group.getDataDescriptionEntries()) {
			if (child.getDataDescriptionEntryType() == DataDescriptionEntryType.CONDITION) {
				continue;
			}

			final String childId = javaVariableIdentifierService.mapToIdentifier(child);
			final String capChildId = parentCapId + "_" + Character.toUpperCase(childId.charAt(0)) + childId.substring(1);
			final CobolTypeEnum childType = cobolTypeService.getType(child);
			final Integer childLenObj = cobolPictureLengthService.getLength(child);
			final int childLen;
			if (childLenObj != null) {
				childLen = childLenObj;
			} else if (child instanceof DataDescriptionEntryGroup) {
				childLen = computeGroupLength((DataDescriptionEntryGroup) child);
			} else {
				childLen = 1;
			}
			final int absStart = groupOffset + localOffset;
			final int absEnd = absStart + childLen;

			final boolean childIsGroup = (child instanceof DataDescriptionEntryGroup)
					&& ((DataDescriptionEntryGroup) child).getDataDescriptionEntries().stream()
							.anyMatch(e -> e.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION);

			if (childIsGroup) {
				// Recurse into nested group
				emitFlattenedLeafAccessors((DataDescriptionEntryGroup) child, baseId, absStart, capChildId,
						baseIsNumeric, baseIntDigits, baseDecDigits, rc);

				// Also emit a group-level string getter/setter for this sub-group
				if (!baseIsNumeric) {
					rc.p("public String get%s() {", capChildId);
					rc.p(" if (%s == null || %s.length() < %d) return \"%s\";",
							baseId, baseId, absEnd, " ".repeat(Math.max(1, childLen)));
					rc.p(" return %s.substring(%d, %d); }", baseId, absStart, absEnd);
					rc.pNl(child);

					rc.p("public void set%s(String val) {", capChildId);
					rc.p(" String s = CobolMove.moveAlphanumericToAlphanumeric(val, %d);", childLen);
					rc.p(" %s = CobolMove.overlayString(%s, s, %d, %d); }", baseId, baseId, absStart, absEnd);
					rc.pNl(child);
				}
			} else if (baseIsNumeric) {
				if (CobolTypeEnum.INTEGER.equals(childType) || CobolTypeEnum.FLOAT.equals(childType)) {
					rc.p("public BigDecimal get%s() {", capChildId);
					rc.p(" String ds = CobolMove.numericToDigitString(%s, %d, %d);", baseId, baseIntDigits, baseDecDigits);
					rc.p(" try { return new BigDecimal(ds.substring(%d, %d).trim()); }", absStart, absEnd);
					rc.p(" catch (Exception e) { return BigDecimal.ZERO; } }");
					rc.pNl(child);

					rc.p("public void set%s(BigDecimal val) {", capChildId);
					rc.p(" String ds = CobolMove.numericToDigitString(%s, %d, %d);", baseId, baseIntDigits, baseDecDigits);
					rc.p(" String sv = String.format(\"%%0%dd\", val == null ? 0 : val.toBigInteger().abs());", childLen);
					rc.p(" if (sv.length() > %d) sv = sv.substring(sv.length() - %d);", childLen, childLen);
					rc.p(" ds = ds.substring(0, %d) + sv + ds.substring(%d);", absStart, absEnd);
					rc.p(" %s = CobolMove.digitStringToNumeric(ds, %d); }", baseId, baseDecDigits);
					rc.pNl(child);
				} else {
					rc.p("public String get%s() {", capChildId);
					rc.p(" String ds = CobolMove.numericToDigitString(%s, %d, %d);", baseId, baseIntDigits, baseDecDigits);
					rc.p(" return ds.substring(%d, %d); }", absStart, absEnd);
					rc.pNl(child);

					rc.p("public void set%s(String val) {", capChildId);
					rc.p(" String ds = CobolMove.numericToDigitString(%s, %d, %d);", baseId, baseIntDigits, baseDecDigits);
					rc.p(" String sv = CobolMove.moveAlphanumericToAlphanumeric(val, %d);", childLen);
					rc.p(" ds = ds.substring(0, %d) + sv + ds.substring(%d);", absStart, absEnd);
					rc.p(" %s = CobolMove.digitStringToNumeric(ds, %d); }", baseId, baseDecDigits);
					rc.pNl(child);
				}
			} else {
				// STRING BASE
				if (CobolTypeEnum.INTEGER.equals(childType) || CobolTypeEnum.FLOAT.equals(childType)) {
					final String picStr = (child instanceof DataDescriptionEntryGroup)
							? ((DataDescriptionEntryGroup) child).getPictureClause() != null
								? ((DataDescriptionEntryGroup) child).getPictureClause().getPictureString() : null
							: null;
					final Integer intPart = picStr != null ? cobolPictureLengthService.getIntegerPartLength(picStr) : null;
					final int intDigits = intPart != null ? intPart : childLen;
					final Integer decPart2 = picStr != null ? cobolPictureLengthService.getFractionalPartLength(picStr) : null;
					final int decDigits2 = decPart2 != null ? decPart2 : 0;

					rc.p("public BigDecimal get%s() {", capChildId);
					rc.p(" if (%s == null || %s.length() < %d) return BigDecimal.ZERO;", baseId, baseId, absEnd);
					if (decDigits2 > 0) {
						// COBOL REDEFINES numeric with implied decimal (V): divide by 10^decDigits
						rc.p(" try { return new BigDecimal(%s.substring(%d, %d).trim()).movePointLeft(%d); }", baseId, absStart, absEnd, decDigits2);
					} else {
						rc.p(" try { return new BigDecimal(%s.substring(%d, %d).trim()); }", baseId, absStart, absEnd);
					}
					rc.p(" catch (Exception e) { return BigDecimal.ZERO; } }");
					rc.pNl(child);

					rc.p("public void set%s(BigDecimal val) {", capChildId);
					if (decDigits2 > 0) {
						rc.p(" String s = CobolMove.moveNumericToAlphanumeric(val, %d, %d, %d);", intDigits, decDigits2, childLen);
					} else {
						rc.p(" String s = CobolMove.moveNumericToAlphanumeric(val, %d, %d);", intDigits, childLen);
					}
					rc.p(" %s = CobolMove.overlayString(%s, s, %d, %d); }", baseId, baseId, absStart, absEnd);
					rc.pNl(child);
				} else {
					rc.p("public String get%s() {", capChildId);
					rc.p(" if (%s == null || %s.length() < %d) return \"%s\";",
							baseId, baseId, absEnd, " ".repeat(Math.max(1, childLen)));
					rc.p(" return %s.substring(%d, %d); }", baseId, absStart, absEnd);
					rc.pNl(child);

					rc.p("public void set%s(String val) {", capChildId);
					rc.p(" String s = CobolMove.moveAlphanumericToAlphanumeric(val, %d);", childLen);
					rc.p(" %s = CobolMove.overlayString(%s, s, %d, %d); }", baseId, baseId, absStart, absEnd);
					rc.pNl(child);
				}
			}

			localOffset += childLen;
		}
	}

	/**
	 * Names of SQLCA fields already emitted by DataDivisionRule.
	 * If a COBOL program explicitly declares one of these in WORKING-STORAGE,
	 * we must suppress the duplicate to avoid a Java compile error.
	 */
	private static final Set<String> SQLCA_FIELD_NAMES = Set.of(
		"SQLCAID", "SQLCABC", "SQLCODE", "SQLSTATE", "SQLERRML", "SQLERRMC",
		"SQLERRP", "SQLERRD", "SQLWARN", "SQLWARN0", "SQLWARN1", "SQLWARN2", "SQLWARN3",
		"SQLWARN4", "SQLWARN5", "SQLWARN6", "SQLWARN7", "SQLWARN8",
		"SQLWARN9", "SQLWARNA"
	);

	protected void printScalarVariable(final DataDescriptionEntryFormat1Context ctx,
			final DataDescriptionEntryGroup dataDescriptionEntryGroup, final RuleContext rc) {

		// Skip SQLCA fields that are already emitted by DataDivisionRule
		final String entryName = dataDescriptionEntryGroup.getName();
		if (entryName != null && isInWorkingStorage(ctx) && SQLCA_FIELD_NAMES.contains(entryName.toUpperCase())) {
			rc.pNl(dataDescriptionEntryGroup);
			return;
		}

		printAnnotations(dataDescriptionEntryGroup, rc);

		String javaType = javaTypeService.mapToType(dataDescriptionEntryGroup);
		String identifier = javaVariableIdentifierService.mapToIdentifier(dataDescriptionEntryGroup);

		// Detect name collision: if a sibling root-level group entry has the same
		// Java identifier as this scalar, suffix this scalar to avoid duplicate field.
		if (hasSiblingGroupWithSameName(dataDescriptionEntryGroup)) {
			identifier = identifier + "_ws77";
		}

		// Detect duplicate scalar field within same parent group.
		// In COBOL, two fields with the same name at the same level are valid
		// (disambiguated by position). In Java, this causes a compile error.
		// Suffix the second (and subsequent) occurrence(s) with a counter.
		if (hasPrecedingSiblingWithSameName(dataDescriptionEntryGroup, identifier)) {
			identifier = identifier + "_dup" + getSiblingDupIndex(dataDescriptionEntryGroup, identifier);
		}

		// Use primitive boolean instead of Boolean wrapper to avoid NPE on unboxing
		final boolean isBooleanPrimitive = "Boolean".equals(javaType);
		if (isBooleanPrimitive) {
			javaType = "boolean";
		}

		// REDEFINES: generate getter that reinterprets the redefined field
		final RedefinesClause redefinesClause = dataDescriptionEntryGroup.getRedefinesClause();
		if (redefinesClause != null && redefinesClause.getRedefinesCall() != null) {
			final String redefinesName = redefinesClause.getRedefinesCall().getName();
			final String redefinesId = redefinesName != null ? javaIdentifierService.mapToIdentifier(redefinesName) : "unknown";
			final CobolTypeEnum type = cobolTypeService.getType(dataDescriptionEntryGroup);

			final String capId = Character.toUpperCase(identifier.charAt(0)) + identifier.substring(1);
			final DataDescriptionEntry baseDde = findBaseDde(dataDescriptionEntryGroup, redefinesName);
			final boolean baseIsNumeric = baseDde != null
					&& (CobolTypeEnum.INTEGER.equals(cobolTypeService.getType(baseDde))
							|| CobolTypeEnum.FLOAT.equals(cobolTypeService.getType(baseDde)));
			final boolean baseIsGroup;
			if (baseDde != null) {
				baseIsGroup = baseDde.getDataDescriptionEntryType() == DataDescriptionEntryType.GROUP
						&& !((DataDescriptionEntryGroup) baseDde).getDataDescriptionEntries().stream()
								.filter(e -> e.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION)
								.collect(Collectors.toList()).isEmpty();
			} else {
				// findBaseDde failed — if PIC X redefines an unknown entry, assume group
				// (scalar String redefines typically redefine other scalars which findBaseDde would find)
				baseIsGroup = !baseIsNumeric;
			}
			final Integer picLen = cobolPictureLengthService.getLength(dataDescriptionEntryGroup);
			final int len = picLen != null ? picLen : 1;
			final String picStr = dataDescriptionEntryGroup.getPictureClause() != null
					? dataDescriptionEntryGroup.getPictureClause().getPictureString() : null;
			final Integer intPart = picStr != null ? cobolPictureLengthService.getIntegerPartLength(picStr) : null;
			final Integer decPart = picStr != null ? cobolPictureLengthService.getFractionalPartLength(picStr) : null;
			final int intDigits = intPart != null ? intPart : len;
			final int decDigits = decPart != null ? decPart : 0;

			// For PIC X(n) REDEFINES PIC 9(m)[V9(k)], the getter must zero-pad the
			// numeric base to the base PIC's width (the external DISPLAY representation),
			// mirroring COBOL semantics where the X(n) field sees the EBCDIC bytes of the
			// numeric field (zone = F, digit nibble = each decimal digit, so "000", "007",
			// "994", etc.). Using String.valueOf(BigDecimal) emits "0" instead of "000",
			// breaking downstream compareAlphanumeric(..., "000") checks.
			int baseIntDigits = intDigits;
			int baseDecDigits = decDigits;
			if (baseIsNumeric && baseDde != null) {
				final PictureClause basePicClause = baseDde instanceof DataDescriptionEntryGroup
						? ((DataDescriptionEntryGroup) baseDde).getPictureClause() : null;
				final String basePicStr = basePicClause != null ? basePicClause.getPictureString() : null;
				final Integer baseIntPart = basePicStr != null
						? cobolPictureLengthService.getIntegerPartLength(basePicStr) : null;
				final Integer baseDecPart = basePicStr != null
						? cobolPictureLengthService.getFractionalPartLength(basePicStr) : null;
				if (baseIntPart != null) {
					baseIntDigits = baseIntPart;
				}
				baseDecDigits = baseDecPart != null ? baseDecPart : 0;
			}

			if (CobolTypeEnum.STRING.equals(type)) {
				// String redefines numeric or other: getter returns string of redefined field
				if (baseIsGroup) {
					rc.p("public %s get%s() { return CobolMove.groupToString(%s); }", javaType, capId, redefinesId);
				} else if (baseIsNumeric) {
					// PIC X(n) REDEFINES PIC 9(m)[V9(k)]: emit zero-padded external decimal
					// representation at the base field's width, truncated/padded to n.
					rc.p("public %s get%s() { return CobolMove.moveNumericToAlphanumeric(%s, %d, %d, %d); }",
							javaType, capId, redefinesId, baseIntDigits, baseDecDigits, len);
				} else {
					rc.p("public %s get%s() { return String.valueOf(%s); }", javaType, capId, redefinesId);
				}
				rc.pNl(dataDescriptionEntryGroup);
				// Setter: write string back to base field
				if (baseIsNumeric) {
					rc.p("public void set%s(%s val) { %s = CobolMove.moveAlphanumericToNumeric(val, %d, %d); }",
							capId, javaType, redefinesId, baseIntDigits, baseDecDigits);
				} else if (baseIsGroup) {
					rc.p("public void set%s(%s val) { CobolMove.moveStringToGroup(val, %s); }",
							capId, javaType, redefinesId);
				} else {
					rc.p("public void set%s(%s val) { %s = CobolMove.moveAlphanumericToAlphanumeric(val, %d); }",
							capId, javaType, redefinesId, len);
				}
			} else if (CobolTypeEnum.BOOLEAN.equals(type)) {
				// Boolean (PIC 1) redefines string: getter parses "\u0001"/"\u0000"
				// B"1" = X"01" = \u0001 (not the character "1" which is X"F1" in EBCDIC)
				rc.p("public %s get%s() { return \"\\u0001\".equals(%s); }", javaType, capId, redefinesId);
				rc.pNl(dataDescriptionEntryGroup);
				// Setter: write boolean back as "\u0001"/"\u0000"
				rc.p("public void set%s(%s val) { %s = val ? \"\\u0001\" : \"\\u0000\"; }", capId, javaType, redefinesId);
			} else {
				// Numeric redefines: getter parses to BigDecimal
				final String trimExpr;
				if (baseIsNumeric) {
					trimExpr = redefinesId + ".toPlainString().trim()";
				} else if (baseIsGroup) {
					trimExpr = "CobolMove.groupToString(" + redefinesId + ").trim()";
				} else {
					trimExpr = redefinesId + ".trim()";
				}
				if (decDigits > 0) {
					// COBOL REDEFINES numeric with implied decimal (V): apply movePointLeft
					// e.g., PIC 9(03)V99 with "00100" => BigDecimal("00100")=100 => movePointLeft(2) => 1.00
					rc.p("public %s get%s() { try { return new BigDecimal(%s).movePointLeft(%d); } catch (Exception e) { return BigDecimal.ZERO; } }",
							javaType, capId, trimExpr, decDigits);
				} else {
					rc.p("public %s get%s() { try { return new BigDecimal(%s); } catch (Exception e) { return BigDecimal.ZERO; } }",
							javaType, capId, trimExpr);
				}
				rc.pNl(dataDescriptionEntryGroup);
				// Setter: write numeric back to base field
				if (baseIsNumeric) {
					rc.p("public void set%s(%s val) { %s = CobolMove.moveNumericToNumeric(val, %d, %d); }",
							capId, javaType, redefinesId, intDigits, decDigits);
				} else if (baseIsGroup) {
					if (decDigits > 0) {
						rc.p("public void set%s(%s val) { CobolMove.moveStringToGroup(CobolMove.moveNumericToAlphanumeric(val, %d, %d, %d), %s); }",
								capId, javaType, intDigits, decDigits, len, redefinesId);
					} else {
						rc.p("public void set%s(%s val) { CobolMove.moveStringToGroup(CobolMove.moveNumericToAlphanumeric(val, %d, %d), %s); }",
								capId, javaType, intDigits, len, redefinesId);
					}
				} else {
					if (decDigits > 0) {
						rc.p("public void set%s(%s val) { %s = CobolMove.moveNumericToAlphanumeric(val, %d, %d, %d); }",
								capId, javaType, redefinesId, intDigits, decDigits, len);
					} else {
						rc.p("public void set%s(%s val) { %s = CobolMove.moveNumericToAlphanumeric(val, %d, %d); }",
								capId, javaType, redefinesId, intDigits, len);
					}
				}
			}
			rc.pNl(dataDescriptionEntryGroup);
			return;
		}

		final int numberOfOccurs = pictureStringService.getMaxOccurs(dataDescriptionEntryGroup);

		if (numberOfOccurs > 1) {
			// OCCURS clause: generate array
			rc.p("protected %s[] %s = new %s[%d];", javaType, identifier, javaType, numberOfOccurs);
			rc.pNl(dataDescriptionEntryGroup);

			// WORKING-STORAGE arrays must be initialized (COBOL defaults: numeric=0, alpha=SPACES)
			if (isInWorkingStorage(ctx)) {
				if (isNumericType(dataDescriptionEntryGroup)) {
					rc.p("{ java.util.Arrays.fill(%s, BigDecimal.ZERO); }", identifier);
					rc.pNl();
				} else if ("String".equals(javaType)) {
					final int len = cobolPictureLengthService.getLength(dataDescriptionEntryGroup);
					if (len > 256) {
						rc.p("{ java.util.Arrays.fill(%s, CobolConstants.spaces(%d)); }", identifier, len);
					} else {
						rc.p("{ java.util.Arrays.fill(%s, \"%s\"); }", identifier, " ".repeat(Math.max(1, len)));
					}
					rc.pNl();
				}
			}

			return;
		}

		// Emit @CobolFieldWidth annotation for BigDecimal fields so that
		// groupToString/moveStringToGroup can produce fixed-width serialization
		if ("BigDecimal".equals(javaType)) {
			final Integer picLen = cobolPictureLengthService.getLength(dataDescriptionEntryGroup);
			if (picLen != null && picLen > 0) {
				// Check for implied decimal digits (V9(n)) to emit decimalDigits attribute
				final String picStr = dataDescriptionEntryGroup.getPictureClause() != null
						? dataDescriptionEntryGroup.getPictureClause().getPictureString() : null;
				final Integer decDigits = picStr != null
						? cobolPictureLengthService.getFractionalPartLength(picStr) : null;
				if (decDigits != null && decDigits > 0) {
					rc.p("@CobolFieldWidth(value=%d, decimalDigits=%d)", picLen, decDigits);
				} else {
					rc.p("@CobolFieldWidth(%d)", picLen);
				}
				rc.pNl();
			}
		}
		rc.p("protected %s %s", javaType, identifier);

		if (dataDescriptionEntryGroup.getValueClause() != null) {
			final ValueClause vc = dataDescriptionEntryGroup.getValueClause();

			// Check if this is ZEROS/ZEROES/ZERO on a String field
			if ("String".equals(javaType) && isZerosValue(vc)) {
				final Integer lenObj = cobolPictureLengthService.getLength(dataDescriptionEntryGroup);
				final int len = lenObj != null ? lenObj : 1;
				rc.p(" = \"%s\"", "0".repeat(Math.max(1, len)));
			} else if (isBooleanPrimitive && isZerosValue(vc)) {
				// PIC 1 VALUE ZEROS → false (ZEROS = B"0" = false)
				rc.p(" = false");
			} else if (isBooleanPrimitive) {
				// PIC 1 with literal value: "0"/B"0" → false, "1"/B"1" → true
				final String valStr = getValueString(vc);
				if ("0".equals(valStr) || "B\"0\"".equalsIgnoreCase(valStr)) {
					rc.p(" = false");
				} else {
					rc.p(" = true");
				}
			} else if ("String".equals(javaType) && isSpacesValue(vc)) {
				// VALUE SPACES on a String field — use CobolConstants.spaces(N) for large fields
				final Integer lenObj = cobolPictureLengthService.getLength(dataDescriptionEntryGroup);
				final int len = lenObj != null ? lenObj : 1;
				if (len > 256) {
					rc.p(" = CobolConstants.spaces(%d)", len);
				} else {
					rc.p(" = \"%s\"", " ".repeat(Math.max(1, len)));
				}
			} else {
				rc.p(" = ");
				printValue(vc, rc);
			}
		} else if (isBooleanPrimitive) {
			// Explicit default for boolean primitive (B"0" = false)
			rc.p(" = false");
		} else if (isInWorkingStorage(ctx) && isNumericType(dataDescriptionEntryGroup)) {
			// ILE COBOL: WORKING-STORAGE numeric fields default to ZEROS
			rc.p(" = BigDecimal.ZERO");
		} else if (isInWorkingStorage(ctx) && "String".equals(javaType)) {
			// ILE COBOL: WORKING-STORAGE alphanumeric fields default to SPACES
			final Integer lenObj = cobolPictureLengthService.getLength(dataDescriptionEntryGroup);
			final int len = lenObj != null ? lenObj : 1;
			if (len > 256) {
				rc.p(" = CobolConstants.spaces(%d)", len);
			} else {
				rc.p(" = \"%s\"", " ".repeat(Math.max(1, len)));
			}
		}

		rc.p(";");
		rc.pNl(dataDescriptionEntryGroup);
	}

	protected boolean isZerosValue(final ValueClause vc) {
		if (vc.getValueIntervals().isEmpty()) {
			return false;
		}
		final ValueInterval vi = vc.getValueIntervals().get(0);
		if (vi.getCtx() != null) {
			final String text = vi.getCtx().getText().toUpperCase();
			return "ZERO".equals(text) || "ZEROS".equals(text) || "ZEROES".equals(text);
		}
		return false;
	}

	protected boolean isSpacesValue(final ValueClause vc) {
		if (vc.getValueIntervals().isEmpty()) {
			return false;
		}
		final ValueInterval vi = vc.getValueIntervals().get(0);
		if (vi.getCtx() != null) {
			final String text = vi.getCtx().getText().toUpperCase();
			return "SPACE".equals(text) || "SPACES".equals(text);
		}
		return false;
	}

	/**
	 * Extracts the raw value string from a VALUE clause. For string literals,
	 * strips the surrounding quotes. For numeric literals, returns the number text.
	 * Returns null if the value cannot be extracted.
	 */
	protected String getValueString(final ValueClause vc) {
		if (vc.getValueIntervals().isEmpty()) {
			return null;
		}
		final ValueInterval vi = vc.getValueIntervals().get(0);
		if (vi.getCtx() != null) {
			String text = vi.getCtx().getText();
			// Strip surrounding quotes (single or double)
			if (text.length() >= 2
					&& ((text.startsWith("\"") && text.endsWith("\""))
							|| (text.startsWith("'") && text.endsWith("'")))) {
				return text.substring(1, text.length() - 1);
			}
			return text;
		}
		return null;
	}

	protected void printSetter(final DataDescriptionEntryGroup dataDescriptionEntryGroup, final RuleContext rc) {
		final String variableIdentifier = javaVariableIdentifierService.mapToIdentifier(dataDescriptionEntryGroup);

		rc.p("public void %s(%s %s){ ", javaSetterIdentifierService.mapToIdentifier(dataDescriptionEntryGroup),
				javaInterfaceService.mapToInterface(dataDescriptionEntryGroup), variableIdentifier);
		rc.p("this.%s = %s; ", variableIdentifier, variableIdentifier);
		rc.p("}");
		rc.pNl(dataDescriptionEntryGroup);
	}

	/**
	 * Walks up the ANTLR parse tree to determine if the given context is inside a
	 * WORKING-STORAGE or LINKAGE SECTION. Both need auto-initialization because
	 * Java null fields break groupToString/moveStringToGroup byte-level operations.
	 */
	protected boolean isInWorkingStorage(final DataDescriptionEntryFormat1Context ctx) {
		// Always initialize fields in any DATA DIVISION section (WORKING-STORAGE, LINKAGE, FILE)
		// Java null fields break groupToString/moveStringToGroup byte-level operations.
		return true;
	}

	/**
	 * Returns true if the data description entry maps to a numeric Java type
	 * (BigDecimal for INTEGER or FLOAT).
	 */
	protected boolean isNumericType(final DataDescriptionEntryGroup dataDescriptionEntryGroup) {
		final CobolTypeEnum type = cobolTypeService.getType(dataDescriptionEntryGroup);
		return CobolTypeEnum.INTEGER.equals(type) || CobolTypeEnum.FLOAT.equals(type);
	}

	/**
	 * Checks whether a root-level sibling group entry (with actual children) has the same
	 * Java identifier as this scalar entry, which would cause a duplicate field in Java.
	 */
	private boolean hasSiblingGroupWithSameName(final DataDescriptionEntryGroup entry) {
		if (entry.getParentDataDescriptionEntryGroup() != null) {
			return false; // Only handle root-level collisions
		}
		final String name = entry.getName();
		if (name == null) {
			return false;
		}
		final String javaId = javaVariableIdentifierService.mapToIdentifier(entry);
		final io.proleap.cobol.asg.metamodel.Program prog = entry.getProgram();
		if (prog == null) {
			return false;
		}
		for (final var cu : prog.getCompilationUnits()) {
			for (final var pu : cu.getProgramUnits()) {
				if (pu.getDataDivision() != null) {
					final var ws = pu.getDataDivision().getWorkingStorageSection();
					if (ws != null) {
						for (final DataDescriptionEntry sibling : ws.getRootDataDescriptionEntries()) {
							if (sibling == entry) {
								continue;
							}
							if (sibling.getDataDescriptionEntryType() == DataDescriptionEntryType.GROUP) {
								final DataDescriptionEntryGroup sibGroup = (DataDescriptionEntryGroup) sibling;
								final String sibJavaId = javaVariableIdentifierService.mapToIdentifier(sibGroup);
								if (javaId.equals(sibJavaId)) {
									// Check that the sibling is a true group (has non-condition children)
									final boolean hasChildren = sibGroup.getDataDescriptionEntries().stream()
											.anyMatch(e -> e.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION);
									if (hasChildren) {
										return true;
									}
								}
							}
						}
					}
				}
			}
		}
		return false;
	}

	/**
	 * Checks if there is a preceding sibling scalar entry within the same parent group
	 * that has the same Java identifier. The first occurrence keeps the name; this method
	 * returns true for the second and subsequent occurrences.
	 */
	private boolean hasPrecedingSiblingWithSameName(final DataDescriptionEntryGroup entry, final String javaId) {
		final DataDescriptionEntryGroup parent = entry.getParentDataDescriptionEntryGroup();
		if (parent == null) {
			return false; // Root-level handled by hasSiblingGroupWithSameName
		}
		for (final DataDescriptionEntry sibling : parent.getDataDescriptionEntries()) {
			if (sibling == entry) {
				return false; // We are the first occurrence
			}
			if (sibling.getDataDescriptionEntryType() == DataDescriptionEntryType.CONDITION) {
				continue;
			}
			final String sibId = javaVariableIdentifierService.mapToIdentifier(sibling);
			if (javaId.equals(sibId)) {
				return true; // A preceding sibling has the same name
			}
		}
		return false;
	}

	/**
	 * Gets the duplicate index for a sibling with the same Java identifier.
	 * Returns 2 for the second occurrence, 3 for the third, etc.
	 */
	private int getSiblingDupIndex(final DataDescriptionEntryGroup entry, final String javaId) {
		final DataDescriptionEntryGroup parent = entry.getParentDataDescriptionEntryGroup();
		if (parent == null) {
			return 2;
		}
		int count = 0;
		for (final DataDescriptionEntry sibling : parent.getDataDescriptionEntries()) {
			if (sibling.getDataDescriptionEntryType() == DataDescriptionEntryType.CONDITION) {
				continue;
			}
			final String sibId = javaVariableIdentifierService.mapToIdentifier(sibling);
			if (javaId.equals(sibId)) {
				count++;
			}
			if (sibling == entry) {
				return count;
			}
		}
		return count;
	}

	/**
	 * Finds the base (redefined) DataDescriptionEntry by name among siblings or top-level entries.
	 */
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
								for (final DataDescriptionEntry dde : ws.getDataDescriptionEntries()) {
									if (origName.equalsIgnoreCase(dde.getName())) {
										return dde;
									}
								}
							}
							final var ls = pu.getDataDivision().getLinkageSection();
							if (ls != null) {
								for (final DataDescriptionEntry dde : ls.getDataDescriptionEntries()) {
									if (origName.equalsIgnoreCase(dde.getName())) {
										return dde;
									}
								}
							}
						}
					}
				}
			}
		}
		return null;
	}

	/**
	 * Emit getter/setter methods for REDEFINES children that are missing from the ASG.
	 * The ProLeap parser ASG sometimes drops children of a REDEFINES group entirely.
	 * This method walks the parse tree to find those children and emits their
	 * getter/setter methods directly from parse tree information.
	 *
	 * @return total byte length of the missing children (to adjust offset for known children)
	 */
	private int emitMissingRedefinesChildren(final DataDescriptionEntryGroup entry, final String baseId,
			final String capGroupId, final boolean baseIsNumeric, final int baseIntDigits, final int baseDecDigits,
			final RuleContext rc) {
		final Integer entryLevel = entry.getLevelNumber();
		if (entryLevel == null) return 0;

		// Walk the parse tree to find ALL sibling entries that should be children
		final org.antlr.v4.runtime.ParserRuleContext format1Ctx =
				(org.antlr.v4.runtime.ParserRuleContext) entry.getCtx();
		if (format1Ctx == null || format1Ctx.getParent() == null || format1Ctx.getParent().getParent() == null) {
			return 0;
		}
		final ParseTree entryParent = format1Ctx.getParent(); // DataDescriptionEntryContext
		final ParseTree sectionCtx = entryParent.getParent(); // section context

		// Find our position
		int ourPos = -1;
		for (int i = 0; i < sectionCtx.getChildCount(); i++) {
			if (sectionCtx.getChild(i) == entryParent) {
				ourPos = i;
				break;
			}
		}
		if (ourPos < 0) return 0;

		// Collect names of children that ARE in the ASG
		final Set<String> knownChildNames = new HashSet<>();
		for (final DataDescriptionEntry child : entry.getDataDescriptionEntries()) {
			if (child.getName() != null) {
				knownChildNames.add(child.getName().toUpperCase());
			}
		}

		// Track cumulative byte length and offset of all missing children
		int totalMissingLen = 0;
		int missingOffset = 0;

		// Check for misparsed first child: the ANTLR grammar sometimes parses
		// "04 ANO PIC X(04)." as a DataValueClauseContext + DataPictureClauseContext
		// inside the REDEFINES group's format1 context, instead of as a separate entry.
		final DataDescriptionEntryFormat1Context fmt1Ctx = (DataDescriptionEntryFormat1Context) format1Ctx;
		if (fmt1Ctx.dataValueClause() != null && !fmt1Ctx.dataValueClause().isEmpty()
				&& fmt1Ctx.dataPictureClause() != null && !fmt1Ctx.dataPictureClause().isEmpty()) {
			final var valClause = fmt1Ctx.dataValueClause().get(0);
			final String valText = valClause.getText();
			// Check if VALUE/VALUES keyword is absent — indicates misparse
			if (valClause.VALUE() == null && valClause.VALUES() == null) {
				// Pattern: level number + name, e.g. "04ANO" or "04SOME-NAME"
				final java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)([A-Za-z][A-Za-z0-9-]*)$").matcher(valText);
				if (m.matches()) {
					final String childName = m.group(2);
					final var picClause = fmt1Ctx.dataPictureClause().get(0);
					String picString = null;
					if (picClause.pictureString() != null) {
						picString = picClause.pictureString().getText();
					}
					if (picString != null && !knownChildNames.contains(childName.toUpperCase())) {
						final Integer picLen = cobolPictureLengthService.getLength(picString);
						if (picLen != null && picLen > 0) {
							final boolean isNumeric = picString.toUpperCase().contains("9");
							final Integer intPartInf = cobolPictureLengthService.getIntegerPartLength(picString);
							final Integer decPartInf = cobolPictureLengthService.getFractionalPartLength(picString);
							final int intDigitsInf = intPartInf != null ? intPartInf : picLen;
							final int decDigitsInf = decPartInf != null ? decPartInf : 0;
							final String childId = javaIdentifierService.mapToIdentifier(childName);
							final String capChildId = capGroupId + "_" + Character.toUpperCase(childId.charAt(0)) + childId.substring(1);
							if (!baseIsNumeric) {
								if (isNumeric) {
									rc.p("public BigDecimal get%s() {", capChildId);
									rc.p(" if (%s == null || %s.length() < %d) return BigDecimal.ZERO;", baseId, baseId, picLen);
									if (decDigitsInf > 0) {
										// COBOL REDEFINES numeric with implied decimal (V): divide by 10^decDigits
										rc.p(" try { return new BigDecimal(%s.substring(%d, %d).trim()).movePointLeft(%d); }", baseId, 0, picLen, decDigitsInf);
									} else {
										rc.p(" try { return new BigDecimal(%s.substring(%d, %d).trim()); }", baseId, 0, picLen);
									}
									rc.p(" catch (Exception e) { return BigDecimal.ZERO; } }");
									rc.pNl();
									rc.p("public void set%s(BigDecimal val) {", capChildId);
									if (decDigitsInf > 0) {
										rc.p(" String s = CobolMove.moveNumericToAlphanumeric(val, %d, %d, %d);", intDigitsInf, decDigitsInf, picLen);
									} else {
										rc.p(" String s = CobolMove.moveNumericToAlphanumeric(val, %d, %d);", intDigitsInf, picLen);
									}
									rc.p(" %s = CobolMove.overlayString(%s, s, %d, %d); }", baseId, baseId, 0, picLen);
									rc.pNl();
								} else {
									rc.p("public String get%s() {", capChildId);
									rc.p(" if (%s == null || %s.length() < %d) return \"%s\";",
											baseId, baseId, picLen, " ".repeat(Math.max(1, picLen)));
									rc.p(" return %s.substring(%d, %d); }", baseId, 0, picLen);
									rc.pNl();
									rc.p("public void set%s(String val) {", capChildId);
									rc.p(" String s = CobolMove.moveAlphanumericToAlphanumeric(val, %d);", picLen);
									rc.p(" %s = CobolMove.overlayString(%s, s, %d, %d); }", baseId, baseId, 0, picLen);
									rc.pNl();
								}
							}
							// Do NOT return early — continue to walk parse tree siblings
							// so that subsequent missing children (e.g., CODOPER-V after
							// CODOPER-S) are also emitted with correct offsets.
							totalMissingLen = picLen;
							missingOffset = picLen;
							knownChildNames.add(childName.toUpperCase());
						}
					}
				}
			}
		}

		// Walk subsequent parse tree siblings and find children missing from ASG
		for (int i = ourPos + 1; i < sectionCtx.getChildCount(); i++) {
			final ParseTree sibling = sectionCtx.getChild(i);
			if (!(sibling instanceof DataDescriptionEntryContext)) continue;

			final DataDescriptionEntryContext ddeCtx = (DataDescriptionEntryContext) sibling;
			final DataDescriptionEntryFormat1Context f1 = ddeCtx.dataDescriptionEntryFormat1();
			if (f1 == null || f1.INTEGERLITERAL() == null) continue;

			final int level;
			try {
				level = Integer.parseInt(f1.INTEGERLITERAL().getText());
			} catch (NumberFormatException e) {
				continue;
			}
			if (level <= entryLevel) break; // End of children

			// Extract name from parse tree
			String childName = null;
			if (f1.dataName() != null) {
				childName = f1.dataName().getText();
			}
			if (childName == null) continue;

			// Check if this child is already in the ASG
			if (knownChildNames.contains(childName.toUpperCase())) {
				// Already known — compute length and skip
				// (known children will be processed in the main loop)
				break; // Once we hit a known child, all subsequent are known too
			}

			// This child is missing from the ASG — extract PIC info and emit getter/setter
			String picString = null;
			if (f1.dataPictureClause() != null && !f1.dataPictureClause().isEmpty()) {
				final var picClause = f1.dataPictureClause().get(0);
				if (picClause.pictureString() != null) {
					picString = picClause.pictureString().getText();
				}
			}
			if (picString == null) continue;

			// Compute length from PIC string
			final Integer picLen = cobolPictureLengthService.getLength(picString);
			if (picLen == null || picLen <= 0) continue;

			// Determine type from PIC string
			final boolean isNumeric = picString.toUpperCase().contains("9") || picString.toUpperCase().contains("S9");
			final Integer intPartMissing = cobolPictureLengthService.getIntegerPartLength(picString);
			final Integer decPartMissing = cobolPictureLengthService.getFractionalPartLength(picString);
			final int intDigitsMissing = intPartMissing != null ? intPartMissing : picLen;
			final int decDigitsMissing = decPartMissing != null ? decPartMissing : 0;

			// Generate getter/setter
			final String childId = javaIdentifierService.mapToIdentifier(childName);
			final String capChildId = capGroupId + "_" + Character.toUpperCase(childId.charAt(0)) + childId.substring(1);
			final int start = missingOffset;
			final int end = missingOffset + picLen;

			if (!baseIsNumeric) {
				if (isNumeric) {
					// Numeric child on string base
					rc.p("public BigDecimal get%s() {", capChildId);
					rc.p(" if (%s == null || %s.length() < %d) return BigDecimal.ZERO;", baseId, baseId, end);
					if (decDigitsMissing > 0) {
						// COBOL REDEFINES numeric with implied decimal (V): divide by 10^decDigits
						rc.p(" try { return new BigDecimal(%s.substring(%d, %d).trim()).movePointLeft(%d); }", baseId, start, end, decDigitsMissing);
					} else {
						rc.p(" try { return new BigDecimal(%s.substring(%d, %d).trim()); }", baseId, start, end);
					}
					rc.p(" catch (Exception e) { return BigDecimal.ZERO; } }");
					rc.pNl();

					rc.p("public void set%s(BigDecimal val) {", capChildId);
					if (decDigitsMissing > 0) {
						rc.p(" String s = CobolMove.moveNumericToAlphanumeric(val, %d, %d, %d);", intDigitsMissing, decDigitsMissing, picLen);
					} else {
						rc.p(" String s = CobolMove.moveNumericToAlphanumeric(val, %d, %d);", intDigitsMissing, picLen);
					}
					rc.p(" %s = CobolMove.overlayString(%s, s, %d, %d); }", baseId, baseId, start, end);
					rc.pNl();
				} else {
					// String child on string base
					rc.p("public String get%s() {", capChildId);
					rc.p(" if (%s == null || %s.length() < %d) return \"%s\";",
							baseId, baseId, end, " ".repeat(Math.max(1, picLen)));
					rc.p(" return %s.substring(%d, %d); }", baseId, start, end);
					rc.pNl();

					rc.p("public void set%s(String val) {", capChildId);
					rc.p(" String s = CobolMove.moveAlphanumericToAlphanumeric(val, %d);", picLen);
					rc.p(" %s = CobolMove.overlayString(%s, s, %d, %d); }", baseId, baseId, start, end);
					rc.pNl();
				}
			}

			totalMissingLen += picLen;
			missingOffset += picLen;
		}

		return totalMissingLen;
	}

	/**
	 * Checks whether the parse tree contains child entries (higher level numbers)
	 * immediately following this entry, even though the ASG dropped them.
	 * This detects the case where a REDEFINES group like:
	 *   04 CODOPERN    REDEFINES CODOPER.
	 *      05 CODOPER-S          PIC X.
	 *      05 CODOPER-V          PIC 9(10).
	 * has no children in the ASG but the parse tree siblings at level 05 exist.
	 */
	private boolean hasParseTreeChildren(final DataDescriptionEntryGroup entry) {
		final Integer entryLevel = entry.getLevelNumber();
		if (entryLevel == null) return false;

		final org.antlr.v4.runtime.ParserRuleContext format1Ctx =
				(org.antlr.v4.runtime.ParserRuleContext) entry.getCtx();
		if (format1Ctx == null || format1Ctx.getParent() == null || format1Ctx.getParent().getParent() == null) {
			return false;
		}

		// Also check the misparsed-first-child pattern: the ANTLR grammar sometimes
		// parses the first child's level+name as a DataValueClause inside the parent's
		// format1 context.  If we see a dataValueClause without VALUE/VALUES keyword
		// together with a dataPictureClause, that is a misparsed child.
		final DataDescriptionEntryFormat1Context fmt1Ctx = (DataDescriptionEntryFormat1Context) format1Ctx;
		if (fmt1Ctx.dataValueClause() != null && !fmt1Ctx.dataValueClause().isEmpty()
				&& fmt1Ctx.dataPictureClause() != null && !fmt1Ctx.dataPictureClause().isEmpty()) {
			final var valClause = fmt1Ctx.dataValueClause().get(0);
			if (valClause.VALUE() == null && valClause.VALUES() == null) {
				final String valText = valClause.getText();
				final java.util.regex.Matcher m = java.util.regex.Pattern
						.compile("^(\\d+)([A-Za-z][A-Za-z0-9-]*)$").matcher(valText);
				if (m.matches()) {
					return true;
				}
			}
		}

		final ParseTree entryParent = format1Ctx.getParent(); // DataDescriptionEntryContext
		final ParseTree sectionCtx = entryParent.getParent(); // section context

		// Find our position among siblings
		int ourPos = -1;
		for (int i = 0; i < sectionCtx.getChildCount(); i++) {
			if (sectionCtx.getChild(i) == entryParent) {
				ourPos = i;
				break;
			}
		}
		if (ourPos < 0) return false;

		// Look at subsequent siblings — any entry with level > entryLevel is a child
		for (int i = ourPos + 1; i < sectionCtx.getChildCount(); i++) {
			final ParseTree sibling = sectionCtx.getChild(i);
			if (!(sibling instanceof DataDescriptionEntryContext)) continue;

			final DataDescriptionEntryContext ddeCtx = (DataDescriptionEntryContext) sibling;
			final DataDescriptionEntryFormat1Context f1 = ddeCtx.dataDescriptionEntryFormat1();
			if (f1 == null || f1.INTEGERLITERAL() == null) continue;

			final int level;
			try {
				level = Integer.parseInt(f1.INTEGERLITERAL().getText());
			} catch (NumberFormatException e) {
				continue;
			}
			if (level <= entryLevel) {
				break; // Same or lower level means we've left our children
			}
			// Found a child — must also have a PIC clause to be meaningful
			if (f1.dataPictureClause() != null && !f1.dataPictureClause().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	protected void printValue(final ValueClause valueClause, final RuleContext rc) {
		final List<ValueInterval> valueIntervals = valueClause.getValueIntervals();

		for (final ValueInterval valueInterval : valueIntervals) {
			rc.visit(valueInterval.getCtx());
		}
	}
}
