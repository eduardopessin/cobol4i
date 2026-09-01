package io.proleap.cobol.transform.java.type.impl;

import java.io.File;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry.DataDescriptionEntryType;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.data.file.FileDescriptionEntry;
import io.proleap.cobol.asg.util.FilenameUtils;
import io.proleap.cobol.asg.util.StringUtils;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.transform.java.type.JavaTypeEnum;
import io.proleap.cobol.transform.java.type.JavaTypeService;

@Singleton
public class JavaTypeServiceImpl implements JavaTypeService {

	@Inject
	private CobolTypeService cobolTypeService;

	@Override
	public String mapToType(final CobolTypeEnum type) {
		final String result;

		if (type == null) {
			result = "Object";
		} else if (CobolTypeEnum.BOOLEAN.equals(type)) {
			result = JavaTypeEnum.BOOLEAN.getName();
		} else if (CobolTypeEnum.FLOAT.equals(type)) {
			result = JavaTypeEnum.BIGDECIMAL.getName();
		} else if (CobolTypeEnum.INTEGER.equals(type)) {
			result = JavaTypeEnum.BIGDECIMAL.getName();
		} else if (CobolTypeEnum.STRING.equals(type)) {
			result = JavaTypeEnum.STRING.getName();
		} else if (CobolTypeEnum.DATE.equals(type)) {
			// IBM ILE COBOL FORMAT DATE fields are alphanumeric PIC X(10) strings
			// (ISO format YYYY-MM-DD), not java.time.LocalDate objects.
			result = JavaTypeEnum.STRING.getName();
		} else if (CobolTypeEnum.TIME.equals(type)) {
			// IBM ILE COBOL FORMAT TIME fields are alphanumeric strings, not LocalTime.
			result = JavaTypeEnum.STRING.getName();
		} else if (CobolTypeEnum.TIMESTAMP.equals(type)) {
			// IBM ILE COBOL FORMAT TIMESTAMP fields are alphanumeric strings, not LocalDateTime.
			result = JavaTypeEnum.STRING.getName();
		} else {
			result = "Object";
		}

		return result;
	}

	@Override
	public String mapToType(final DataDescriptionEntry dataDescriptionEntry) {
		final String result;

		if (dataDescriptionEntry instanceof DataDescriptionEntryGroup) {
			final DataDescriptionEntryGroup dataDescriptionEntryGroup = (DataDescriptionEntryGroup) dataDescriptionEntry;
			result = mapToType(dataDescriptionEntryGroup);
		} else {
			return mapToType(dataDescriptionEntry.getName()) + "Type";
		}

		return result;
	}

	@Override
	public String mapToType(final DataDescriptionEntryGroup dataDescriptionEntryGroup) {
		final CobolTypeEnum type = cobolTypeService.getType(dataDescriptionEntryGroup);
		final String result;

		if (type == null) {
			// For PIC N (NATIONAL) fields, the type resolver returns null.
			// Treat them as String in Java since Java strings are already Unicode.
			if (dataDescriptionEntryGroup.getPictureClause() != null
					&& dataDescriptionEntryGroup.getPictureClause().getPictureString() != null
					&& dataDescriptionEntryGroup.getPictureClause().getPictureString().toUpperCase().startsWith("N")) {
				result = JavaTypeEnum.STRING.getName();
			} else {
				result = "Object";
			}
		} else {
			switch (type) {
			case DATA_DESCRIPTION_GROUP:
				final Boolean isFiller = dataDescriptionEntryGroup.getFiller();

				if (Boolean.TRUE.equals(isFiller)) {
					result = mapToType("Filler" + dataDescriptionEntryGroup.getFillerNumber()) + "Type";
				} else {
					// When a child group has the same name as its parent (e.g., COPY format
					// inside OCCURS array: 04 FILE211300 OCCURS / 05 FILE211300), append "Fmt"
					// to the child's class name to avoid duplicate inner class names.
					final String suffix = hasSameNameAsParent(dataDescriptionEntryGroup) ? "FmtType" : "Type";
					result = mapToType(dataDescriptionEntryGroup.getName()) + suffix;
				}
				break;
			case BOOLEAN:
			case FLOAT:
			case INTEGER:
			case STRING:
			default:
				// A group with child entries is structurally a group even if the type
				// resolver reports a primitive type (e.g. a GROUP REDEFINES an elementary
				// PIC X field).  Use the COBOL group name for the Java class name to
				// avoid collisions with Java built-in types like String.
				if (hasNonConditionChildren(dataDescriptionEntryGroup)) {
					result = mapToType(dataDescriptionEntryGroup.getName()) + "Type";
				} else {
					result = mapToType(type);
				}
				break;
			}
		}

		return result;
	}

	@Override
	public String mapToType(final File inputFile) {
		return mapToType(FilenameUtils.removeExtension(inputFile.getName()));
	}

	@Override
	public String mapToType(final FileDescriptionEntry fileDescriptionEntry) {
		return mapToType(fileDescriptionEntry.getName()) + "Type";
	}

	@Override
	public String mapToType(final String name) {
		final String result;

		if (name == null || name.isEmpty()) {
			result = name;
		} else if (name.contains("-") || name.contains("_")) {
			result = name.toLowerCase().replace("-", "_");
		} else {
			result = name;
		}

		final String capitalized = StringUtils.capitalize(result);

		if (capitalized != null && !capitalized.isEmpty() && Character.isDigit(capitalized.charAt(0))) {
			return "_" + capitalized;
		}

		return capitalized;
	}

	/**
	 * Returns true if the group's parent group has the same name (case-insensitive).
	 * This happens when a COPY format group (e.g., 05 FILE211300) is nested inside
	 * an OCCURS array group with the same name (e.g., 04 FILE211300 OCCURS 11).
	 */
	private boolean hasSameNameAsParent(final DataDescriptionEntryGroup group) {
		final DataDescriptionEntryGroup parent = group.getParentDataDescriptionEntryGroup();
		if (parent == null || group.getName() == null || parent.getName() == null) {
			return false;
		}
		return group.getName().equalsIgnoreCase(parent.getName());
	}

	/**
	 * Returns true if the group has at least one child entry that is not
	 * an 88-level condition.  Such groups are emitted as inner classes and
	 * their Java class name must be derived from the COBOL group name,
	 * not from the Java primitive type.
	 */
	private boolean hasNonConditionChildren(final DataDescriptionEntryGroup group) {
		final List<DataDescriptionEntry> children = group.getDataDescriptionEntries();
		if (children == null || children.isEmpty()) {
			return false;
		}
		for (final DataDescriptionEntry child : children) {
			if (child.getDataDescriptionEntryType() != DataDescriptionEntryType.CONDITION) {
				return true;
			}
		}
		return false;
	}
}
