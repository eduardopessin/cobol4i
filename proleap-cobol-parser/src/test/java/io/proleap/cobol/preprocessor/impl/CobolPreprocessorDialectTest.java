/*
 * Copyright (C) 2017, Ulrich Wolffgang <ulrich.wolffgang@proleap.io>
 * All rights reserved.
 *
 * This software may be modified and distributed under the terms
 * of the MIT license. See the LICENSE file for details.
 */

package io.proleap.cobol.preprocessor.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.proleap.cobol.asg.params.CobolDialect;
import io.proleap.cobol.asg.params.CobolParserParams;
import io.proleap.cobol.asg.params.impl.CobolParserParamsImpl;
import io.proleap.cobol.preprocessor.CobolPreprocessor.CobolSourceFormatEnum;

/**
 * The IBM i source transformations must be opt-in.
 *
 * <p>They rewrite source before parsing — stripping PROCESS/CBL directives,
 * injecting implicit section headers and sentence terminators — which is
 * correct for IBM i source and wrong for anything else. A caller that has not
 * asked for the IBM i dialect must get upstream behaviour.
 *
 * <p>These tests exist because the transformations were once unconditional,
 * which broke 20 upstream parser tests on standard COBOL.
 */
public class CobolPreprocessorDialectTest {

	/** Exposes the protected hook and the internals under test. */
	private static class TestablePreprocessor extends CobolPreprocessorImpl {

		boolean ibmILE(final CobolParserParams params) {
			return isIbmILE(params);
		}

		String strip(final String code) {
			return stripProcessDirectives(code);
		}
	}

	private static CobolParserParams params(final CobolDialect dialect) {
		final CobolParserParams result = new CobolParserParamsImpl();
		result.setFormat(CobolSourceFormatEnum.FIXED);
		if (dialect != null) {
			result.setDialect(dialect);
		}
		return result;
	}

	private final TestablePreprocessor preprocessor = new TestablePreprocessor();

	@Test
	public void ibmIleDialectEnablesTransformations() {
		assertTrue(preprocessor.ibmILE(params(CobolDialect.IBM_ILE)));
	}

	@Test
	public void noDialectMeansStandard() {
		assertFalse("absent dialect must not enable IBM i rewriting",
				preprocessor.ibmILE(params(null)));
	}

	@Test
	public void nullParamsMeanStandard() {
		assertFalse(preprocessor.ibmILE(null));
	}

	@Test
	public void otherDialectsMeanStandard() {
		assertFalse(preprocessor.ibmILE(params(CobolDialect.ANSI85)));
		assertFalse(preprocessor.ibmILE(params(CobolDialect.MF)));
		assertFalse(preprocessor.ibmILE(params(CobolDialect.OSVS)));
	}

	/**
	 * The directive strip itself still works when invoked — the dialect decides
	 * whether it is called, not whether it functions.
	 */
	@Test
	public void stripRemovesDirectiveButKeepsParagraphOfSimilarName() {
		final String code = "      " + " PROCESS OPTIONS.\n"
				+ "      " + " PROCESS-ORDERS.\n"
				+ "      " + "     DISPLAY 'X'.\n";

		final String stripped = preprocessor.strip(code);

		assertFalse("PROCESS directive should be gone",
				stripped.contains("PROCESS OPTIONS"));
		assertTrue("PROCESS-ORDERS is a paragraph, not a directive",
				stripped.contains("PROCESS-ORDERS"));
		assertEquals(2, stripped.split("\n").length);
	}
}
