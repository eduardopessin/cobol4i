package io.proleap.cobol.runtime.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EntityServiceImpl#initialize(Object)}, which implements the
 * COBOL {@code INITIALIZE} statement over a generated group structure.
 *
 * <p>Generated COBOL groups are modelled as <em>non-static</em> inner classes.
 * Whether the compiler emits the synthetic {@code this$N} back-reference to
 * the enclosing instance is version-dependent: JDK 17 emits it even when the
 * inner class never touches the outer instance, JDK 21 optimises it away.
 * Where it is present, a naive reflective walk follows it and recurses until
 * the stack overflows.
 *
 * <p>These tests pin the cycle guard so {@code INITIALIZE} behaves the same
 * regardless of which JDK compiled the generated sources. They are not
 * regression tests for a defect in the generated code.
 */
public class EntityServiceImplInitializeTest {

	private final EntityServiceImpl service = new EntityServiceImpl();

	/** Mirrors the shape the transformer emits: nested non-static inner classes. */
	public static class GeneratedProgram {

		public class GroupType {

			protected BigDecimal amount = BigDecimal.TEN;

			protected String name = "ABC";

			public class NestedType {

				protected BigDecimal counter = BigDecimal.ONE;

				protected String label = "XY";
			}

			protected NestedType nested = new NestedType();
		}

		protected GroupType group = new GroupType();
	}

	@Test
	public void initializeDoesNotRecurseThroughEnclosingInstance() {
		final GeneratedProgram program = new GeneratedProgram();

		// Before the cycle guard this threw StackOverflowError.
		service.initialize(program.group);

		assertEquals(BigDecimal.ZERO, program.group.amount, "numeric field must reset to zero");
		assertEquals("   ", program.group.name, "alphanumeric field must reset to spaces, keeping its width");
	}

	@Test
	public void initializeReachesNestedGroups() {
		final GeneratedProgram program = new GeneratedProgram();

		service.initialize(program.group);

		assertEquals(BigDecimal.ZERO, program.group.nested.counter, "nested numeric field must reset");
		assertEquals("  ", program.group.nested.label, "nested alphanumeric field must reset to spaces");
	}

	@Test
	public void initializePreservesSyntheticOuterReference() {
		final GeneratedProgram program = new GeneratedProgram();
		final GeneratedProgram.GroupType group = program.group;

		service.initialize(group);

		// The synthetic this$N field must be left untouched, otherwise the
		// generated code loses its link to the enclosing program instance.
		assertSame(group, program.group);
		assertSame(group.nested, program.group.nested);
	}

	@Test
	public void initializeHandlesOccursLists() {
		final GeneratedProgram program = new GeneratedProgram();
		final List<GeneratedProgram.GroupType> table = new ArrayList<>();
		table.add(program.group);
		table.add(program.new GroupType());

		service.initialize(table);

		for (final GeneratedProgram.GroupType entry : table) {
			assertEquals(BigDecimal.ZERO, entry.amount, "every OCCURS element must be initialized");
			assertEquals("   ", entry.name);
		}
	}

	@Test
	public void initializeIsNullSafe() {
		service.initialize(null);
	}
}
