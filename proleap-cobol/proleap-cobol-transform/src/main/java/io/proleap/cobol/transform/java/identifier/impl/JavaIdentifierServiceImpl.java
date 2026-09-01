package io.proleap.cobol.transform.java.identifier.impl;

import java.util.Set;

import jakarta.inject.Singleton;

import io.proleap.cobol.transform.java.identifier.JavaIdentifierService;

@Singleton
public class JavaIdentifierServiceImpl implements JavaIdentifierService {

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
		"transient", "try", "void", "volatile", "while",
		"null", "true", "false"
	);

	@Override
	public String mapToIdentifier(final String identifier) {
		final String result;

		if (identifier == null) {
			result = null;
		} else {
			final String identifierReplaced = identifier.replace('#', '$');
			final String identifierCleaned = identifierReplaced.toLowerCase().replace("-", "_");

			if (identifierCleaned == null || identifierCleaned.isEmpty()) {
				result = "";
			} else {
				String mapped = io.proleap.cobol.asg.util.StringUtils.lowercaseFirstLetter(identifierCleaned);
				if (!mapped.isEmpty() && Character.isDigit(mapped.charAt(0))) {
					mapped = "_" + mapped;
				}
				// Escape Java reserved words to avoid compilation errors
				if (JAVA_RESERVED_WORDS.contains(mapped)) {
					mapped = mapped + "_";
				}
				result = mapped;
			}
		}

		return result;
	}
}
