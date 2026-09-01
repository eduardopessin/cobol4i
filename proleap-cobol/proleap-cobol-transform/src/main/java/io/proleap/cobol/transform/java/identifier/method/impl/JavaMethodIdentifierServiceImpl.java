package io.proleap.cobol.transform.java.identifier.method.impl;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.asg.metamodel.procedure.Paragraph;
import io.proleap.cobol.asg.metamodel.procedure.Section;
import io.proleap.cobol.transform.java.identifier.JavaIdentifierService;
import io.proleap.cobol.transform.java.identifier.method.JavaMethodIdentifierService;

@Singleton
public class JavaMethodIdentifierServiceImpl implements JavaMethodIdentifierService {

	@Inject
	private JavaIdentifierService javaIdentifierService;

	/**
	 * Caches the assigned Java method name for each Paragraph/Section object,
	 * so repeated calls for the same object return the same name.
	 */
	private final Map<Object, String> assignedNames = new IdentityHashMap<>();

	/**
	 * Tracks how many times each base method name has been used,
	 * so duplicates get a numeric suffix.
	 */
	private final Map<String, Integer> nameCount = new HashMap<>();

	@Override
	public String mapToIdentifier(final Paragraph paragraph) {
		return assignedNames.computeIfAbsent(paragraph, key -> {
			final String baseIdentifier = javaIdentifierService.mapToIdentifier(paragraph.getName());
			return deduplicateMethodName(baseIdentifier);
		});
	}

	@Override
	public String mapToIdentifier(final Section section) {
		return assignedNames.computeIfAbsent(section, key -> {
			final String baseIdentifier = javaIdentifierService.mapToIdentifier(section.getName());
			return deduplicateMethodName(baseIdentifier);
		});
	}

	private String deduplicateMethodName(final String baseIdentifier) {
		final Integer count = nameCount.get(baseIdentifier);

		if (count == null) {
			nameCount.put(baseIdentifier, 1);
			return baseIdentifier;
		}

		final int nextCount = count + 1;
		nameCount.put(baseIdentifier, nextCount);
		return baseIdentifier + "_" + nextCount;
	}

	@Override
	public void reset() {
		assignedNames.clear();
		nameCount.clear();
	}
}
