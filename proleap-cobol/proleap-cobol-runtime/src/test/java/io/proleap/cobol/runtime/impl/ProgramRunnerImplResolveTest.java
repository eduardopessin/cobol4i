package io.proleap.cobol.runtime.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the configurable program-package resolver added so that the
 * runtime can load sub-programs generated from non-COBOL dialects (CL, and
 * — in future — RPG, PL/I, …) without the harness having to know about each
 * package.
 *
 * <p>The test fixture {@code io.proleap.cobol.generated.cl.FakeClProgram}
 * lives in the real CL package so that resolution travels exactly the same
 * code path production classes will.</p>
 */
public class ProgramRunnerImplResolveTest {

	@Test
	public void defaultPackagesIncludeDefaultAndCl() {
		final List<String> pkgs = ProgramRunnerImpl.getProgramPackages();
		assertTrue(pkgs.contains(""), "default (unnamed) package must be scanned — generated COBOL classes live here");
		assertTrue(pkgs.contains("io.proleap.cobol.generated.cl"),
				"CL dialect package must be scanned so a CALL resolves to the generated CL class");
	}

	@Test
	public void resolvesClassInClPackage() {
		final Class<?> clazz = ProgramRunnerImpl.resolveProgramClass("FakeClProgram");
		assertNotNull(clazz, "FakeClProgram must be resolvable via the CL package scan");
		assertEquals("io.proleap.cobol.generated.cl.FakeClProgram", clazz.getName());
	}

	@Test
	public void resolveReturnsNullForUnknownProgram() {
		assertNull(ProgramRunnerImpl.resolveProgramClass("NoSuchProgram_" + System.nanoTime()));
	}

	@Test
	public void addProgramPackageIsHonouredAndDedup() {
		final int before = ProgramRunnerImpl.getProgramPackages().size();
		ProgramRunnerImpl.addProgramPackage("io.proleap.cobol.generated.cl"); // already present
		assertEquals(before, ProgramRunnerImpl.getProgramPackages().size(),
				"duplicates must be ignored — registrations are idempotent");

		final String newPkg = "io.proleap.cobol.generated.rpg_test_only";
		ProgramRunnerImpl.addProgramPackage(newPkg);
		assertTrue(ProgramRunnerImpl.getProgramPackages().contains(newPkg),
				"new package must be registered so future dialects can hook in");
	}

	@Test
	public void callLazyResolvesUnregisteredClProgram() {
		final ProgramRunnerImpl runner = new ProgramRunnerImpl();
		// We do NOT call register(...) — the runner must discover the class via
		// the package scan. This mirrors the harness failure mode reported by
		// an interactive sweep: CALL of a CL program with no prior registration.
		final Object rc = runner.call("FakeClProgram");
		assertNotNull(rc, "lazy resolve must locate FakeClProgram and produce a return code");
	}
}
