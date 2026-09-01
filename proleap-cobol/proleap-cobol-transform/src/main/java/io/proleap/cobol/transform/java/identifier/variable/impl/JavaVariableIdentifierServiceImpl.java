package io.proleap.cobol.transform.java.identifier.variable.impl;

import java.util.Set;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.data.datadescription.RedefinesClause;
import io.proleap.cobol.commons.datadescription.CobolDataDescriptionEntryService;
import io.proleap.cobol.transform.java.identifier.JavaIdentifierService;
import io.proleap.cobol.transform.java.identifier.variable.JavaVariableIdentifierService;

@Singleton
public class JavaVariableIdentifierServiceImpl implements JavaVariableIdentifierService {

	private static final Set<String> JAVA_RESERVED_WORDS = Set.of(
		"abstract", "assert", "boolean", "break", "byte",
		"case", "catch", "char", "class", "const",
		"continue", "default", "do", "double", "else",
		"enum", "extends", "final", "finally", "float",
		"for", "goto", "if", "implements", "import",
		"instanceof", "int", "interface", "long", "native",
		"new", "package", "private", "protected", "public",
		"return", "short", "static", "strictfp", "super",
		"switch", "synchronized", "this", "throw", "throws",
		"transient", "try", "void", "volatile", "while"
	);

	@Inject
	private CobolDataDescriptionEntryService cobolDataDescriptionEntryService;

	@Inject
	private JavaIdentifierService javaIdentifierService;

	@Override
	public String mapToIdentifier(final DataDescriptionEntry dataDescriptionEntry) {
		final String result;

		if (dataDescriptionEntry instanceof DataDescriptionEntryGroup) {
			final DataDescriptionEntryGroup dataDescriptionEntryGroup = (DataDescriptionEntryGroup) dataDescriptionEntry;
			result = mapToIdentifier(dataDescriptionEntryGroup);
		} else {
			result = escapeReservedWord(javaIdentifierService.mapToIdentifier(dataDescriptionEntry.getName()));
		}

		return result;
	}

	@Override
	public String mapToIdentifier(final DataDescriptionEntryGroup dataDescriptionEntryGroup) {
		final Boolean isFiller = dataDescriptionEntryGroup.getFiller();
		final RedefinesClause redefinesClause = dataDescriptionEntryGroup.getRedefinesClause();
		final String result;

		if (Boolean.TRUE.equals(isFiller)) {
			result = escapeReservedWord(javaIdentifierService.mapToIdentifier("filler" + dataDescriptionEntryGroup.getFillerNumber()));
		} else if (redefinesClause != null) {
			cobolDataDescriptionEntryService.getDataDescriptionEntry(redefinesClause.getRedefinesCall());
			result = escapeReservedWord(javaIdentifierService.mapToIdentifier(dataDescriptionEntryGroup.getName()));
		} else {
			result = escapeReservedWord(javaIdentifierService.mapToIdentifier(dataDescriptionEntryGroup.getName()));
		}

		return result;
	}

	private String escapeReservedWord(final String identifier) {
		if (identifier == null) {
			return "unnamed_field";
		}
		if (JAVA_RESERVED_WORDS.contains(identifier)) {
			return identifier + "_";
		}
		return identifier;
	}
}
