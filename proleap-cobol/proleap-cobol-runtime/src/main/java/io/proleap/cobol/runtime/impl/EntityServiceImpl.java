package io.proleap.cobol.runtime.impl;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

import io.proleap.cobol.runtime.CobolConstants;
import io.proleap.cobol.runtime.EntityService;

/**
 * Default implementation of EntityService.
 * Initializes COBOL group structures by resetting all fields to defaults.
 */
public class EntityServiceImpl implements EntityService {

	@Override
	public int getLength(final Object field) {
		if (field == null) {
			return 0;
		}
		if (field instanceof String) {
			return ((String) field).length();
		}
		return String.valueOf(field).length();
	}

	@Override
	public String getAddress(final Object field) {
		if (field == null) {
			return "";
		}
		if (field instanceof String) {
			return (String) field;
		}
		return String.valueOf(field);
	}

	@Override
	public void initialize(final Object group) {
		// Cycle guard, needed only on some JDKs.
		//
		// Generated COBOL groups are non-static inner classes. Whether javac
		// emits the synthetic this$N back-reference to the enclosing instance
		// depends on the compiler version: JDK 17 emits it even when the inner
		// class never uses the outer instance, JDK 21 optimises it away. With
		// this$N present, a naive reflective walk follows it back up and
		// recurses until the stack overflows.
		//
		// This is therefore not a fix for a defect in generated code — the same
		// sources run fine when compiled with JDK 21. It makes the runtime
		// independent of the compiler version, so INITIALIZE behaves the same
		// either way.
		initialize(group, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
	}

	private void initialize(final Object group, final java.util.Set<Object> visited) {
		if (group == null) {
			return;
		}

		if (!visited.add(group)) {
			// Already initialized during this traversal — cycle, stop here.
			return;
		}

		// Handle List (COBOL OCCURS arrays): initialize each element
		if (group instanceof List<?>) {
			for (final Object element : (List<?>) group) {
				if (element != null) {
					initialize(element, visited);
				}
			}
			return;
		}

		// Handle BigDecimal[] arrays (COBOL indicator arrays)
		if (group instanceof BigDecimal[]) {
			final BigDecimal[] arr = (BigDecimal[]) group;
			java.util.Arrays.fill(arr, BigDecimal.ZERO);
			return;
		}

		// Skip JDK/library classes - only recurse into user-defined COBOL types
		final String className = group.getClass().getName();
		if (className.startsWith("java.") || className.startsWith("javax.")
				|| className.startsWith("jdk.") || className.startsWith("sun.")) {
			return;
		}

		for (final Field field : group.getClass().getDeclaredFields()) {
			// Synthetic fields (this$N for inner classes, JaCoCo probes, ...) are
			// not COBOL data and must never be written to or followed.
			if (field.isSynthetic()) {
				continue;
			}

			field.setAccessible(true);
			try {
				final Class<?> type = field.getType();

				if (type == String.class) {
					final String current = (String) field.get(group);
					final int len = (current != null) ? current.length() : 0;
					field.set(group, len > 0 ? CobolConstants.spaces(len) : "");
				} else if (type == BigDecimal.class) {
					field.set(group, BigDecimal.ZERO);
				} else if (type == boolean.class || type == Boolean.class) {
					field.set(group, false);
				} else if (type == int.class || type == Integer.class) {
					field.set(group, 0);
				} else if (type == long.class || type == Long.class) {
					field.set(group, 0L);
				} else if (!type.isPrimitive()) {
					// Recursively initialize nested groups
					final Object nested = field.get(group);
					if (nested != null) {
						initialize(nested, visited);
					}
				}
			} catch (final Exception e) {
				// Skip fields that can't be accessed
			}
		}
	}
}
