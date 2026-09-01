package io.proleap.cobol.transform.java.rules.lang.procedure.unstring;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.UnstringStatementContext;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.procedure.unstring.CountIn;
import io.proleap.cobol.asg.metamodel.procedure.unstring.DelimitedByPhrase;
import io.proleap.cobol.asg.metamodel.procedure.unstring.DelimiterIn;
import io.proleap.cobol.asg.metamodel.procedure.unstring.Into;
import io.proleap.cobol.asg.metamodel.procedure.unstring.IntoPhrase;
import io.proleap.cobol.asg.metamodel.procedure.unstring.OrAll;
import io.proleap.cobol.asg.metamodel.procedure.unstring.Sending;
import io.proleap.cobol.asg.metamodel.procedure.unstring.TallyingPhrase;
import io.proleap.cobol.asg.metamodel.procedure.unstring.UnstringStatement;
import io.proleap.cobol.asg.metamodel.procedure.unstring.WithPointerPhrase;
import io.proleap.cobol.commons.datadescription.CobolDataDescriptionEntryService;
import io.proleap.cobol.commons.datadescription.CobolPictureLengthService;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.transform.java.expression.JavaExpressionService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

/**
 * Generates Java code for the COBOL UNSTRING statement.
 *
 * Handles:
 * - UNSTRING source INTO field1 field2 ... (no delimiter = one char at a time)
 * - UNSTRING source DELIMITED BY delim [OR delim2 ...] INTO field1 ...
 * - WITH POINTER (tracks position in source)
 * - DELIMITER IN (stores matched delimiter)
 * - COUNT IN (stores character count)
 * - TALLYING IN (stores number of fields acted upon)
 * - ON OVERFLOW / NOT ON OVERFLOW
 *
 * Type-aware: when INTO target is numeric (BigDecimal), the extracted string
 * is converted via CobolMove.moveAlphanumericToNumeric.
 */
@Singleton
public class UnstringStatementRule extends CobolTransformRule<UnstringStatementContext, UnstringStatement> {

	@Inject
	private JavaExpressionService javaExpressionService;

	@Inject
	private CobolDataDescriptionEntryService dataDescriptionEntryService;

	@Inject
	private CobolPictureLengthService pictureLengthService;

	@Inject
	private CobolTypeService cobolTypeService;

	@Override
	public void apply(final UnstringStatementContext ctx, final UnstringStatement unstringStatement,
			final RuleContext rc) {

		final Sending sending = unstringStatement.getSending();
		final String sourceExpr = javaExpressionService.mapToCall(sending.getSendingCall());

		final IntoPhrase intoPhrase = unstringStatement.getIntoPhrase();
		final List<Into> intos = intoPhrase.getIntos();

		final WithPointerPhrase withPointerPhrase = unstringStatement.getWithPointerPhrase();
		final String pointerExpr = withPointerPhrase != null
				? javaExpressionService.mapToCall(withPointerPhrase.getPointerCall())
				: null;

		final TallyingPhrase tallyingPhrase = unstringStatement.getTallyingPhrase();
		final String tallyExpr = tallyingPhrase != null
				? javaExpressionService.mapToCall(tallyingPhrase.getTallyCountDataItemCall())
				: null;

		final DelimitedByPhrase delimitedByPhrase = sending.getDelimitedByPhrase();
		final List<OrAll> orAlls = sending.getOrAlls();

		// Determine if we have delimiters
		final boolean hasDelimiters = delimitedByPhrase != null;

		// Open block
		rc.p("{");
		rc.pNl();

		// Source string
		rc.p("String _unstSrc = String.valueOf(");
		rc.p(sourceExpr);
		rc.p(");");
		rc.pNl();

		if (!hasDelimiters) {
			// No DELIMITED BY: extract one character per INTO field, advancing pointer
			emitNoDelimiter(rc, intos, pointerExpr, tallyExpr);
		} else {
			// DELIMITED BY: use runtime CobolStringOps.unstring()
			emitWithDelimiters(rc, intos, pointerExpr, tallyExpr, delimitedByPhrase, orAlls);
		}

		// ON OVERFLOW / NOT ON OVERFLOW
		if (unstringStatement.getOnOverflowPhrase() != null
				|| unstringStatement.getNotOnOverflowPhrase() != null) {
			if (unstringStatement.getOnOverflowPhrase() != null) {
				rc.p("if (_unstOverflow) {");
				rc.pNl();
				rc.visitChildren(unstringStatement.getOnOverflowPhrase().getCtx());
				rc.p("}");
				rc.pNl();
			}
			if (unstringStatement.getNotOnOverflowPhrase() != null) {
				rc.p("if (!_unstOverflow) {");
				rc.pNl();
				rc.visitChildren(unstringStatement.getNotOnOverflowPhrase().getCtx());
				rc.p("}");
				rc.pNl();
			}
		}

		// Close block
		rc.p("}");
		rc.pNl(unstringStatement);
	}

	/**
	 * Determines whether an INTO target field is numeric (BigDecimal in Java).
	 */
	private boolean isNumericTarget(final Call call) {
		final DataDescriptionEntry dde = dataDescriptionEntryService.getDataDescriptionEntry(call);
		if (dde != null) {
			final CobolTypeEnum type = cobolTypeService.getType(dde);
			return type == CobolTypeEnum.INTEGER || type == CobolTypeEnum.FLOAT;
		}
		// Also check the call directly
		final CobolTypeEnum callType = cobolTypeService.getType(call);
		return callType == CobolTypeEnum.INTEGER || callType == CobolTypeEnum.FLOAT;
	}

	/**
	 * Emits a type-aware assignment of a String value expression to an INTO target.
	 * For numeric targets: converts string to BigDecimal.
	 * For alphanumeric targets: assigns string directly.
	 * Handles REDEFINES fields by using setter methods instead of direct assignment.
	 */
	private void emitAssignment(final RuleContext rc, final Into into, final String valueExpr) {
		final Call intoCall = into.getIntoCall();
		final String intoExpr = javaExpressionService.mapToCall(intoCall);
		final boolean numeric = isNumericTarget(intoCall);

		// Check if the INTO target is a REDEFINES field that needs setter access
		String setterPrefix = intoCall != null
				? javaExpressionService.getGroupOverElementarySetterPrefix(intoCall)
				: null;
		if (setterPrefix == null && intoCall != null) {
			setterPrefix = javaExpressionService.getRedefinesSetterPrefix(intoCall);
		}

		if (setterPrefix != null) {
			// Use setter: setter(convertedValue)
			rc.p(setterPrefix);
			rc.p("(");
			if (numeric) {
				rc.p("new java.math.BigDecimal(");
				rc.p(valueExpr);
				rc.p(".trim().isEmpty() ? \"0\" : ");
				rc.p(valueExpr);
				rc.p(".trim())");
			} else {
				rc.p(valueExpr);
			}
			rc.p(");");
		} else {
			rc.p(intoExpr);
			if (numeric) {
				rc.p(" = new java.math.BigDecimal(");
				rc.p(valueExpr);
				rc.p(".trim().isEmpty() ? \"0\" : ");
				rc.p(valueExpr);
				rc.p(".trim());");
			} else {
				rc.p(" = ");
				rc.p(valueExpr);
				rc.p(";");
			}
		}
		rc.pNl();
	}

	/**
	 * No DELIMITED BY: characters from the source string (starting at the pointer
	 * position) are distributed across INTO fields based on each field's PIC size.
	 * Per IBM ILE COBOL reference, when no DELIMITED BY is specified, characters
	 * are moved from the sending field to each receiving field in order, with
	 * the number of characters moved determined by the size of the receiving field.
	 */
	private void emitNoDelimiter(final RuleContext rc, final List<Into> intos,
			final String pointerExpr, final String tallyExpr) {

		// Pointer: 1-based position in source
		rc.p("int _unstPtr = ");
		if (pointerExpr != null) {
			rc.p(pointerExpr);
			rc.p(" != null ? ");
			rc.p(pointerExpr);
			rc.p(".intValue() : 1;");
		} else {
			rc.p("1;");
		}
		rc.pNl();

		rc.p("int _unstTally = 0;");
		rc.pNl();
		rc.p("boolean _unstOverflow = false;");
		rc.pNl();

		for (int i = 0; i < intos.size(); i++) {
			final Into into = intos.get(i);
			final String intoExpr = javaExpressionService.mapToCall(into.getIntoCall());

			// Determine the PIC length of the receiving field
			final DataDescriptionEntry dde = dataDescriptionEntryService.getDataDescriptionEntry(into.getIntoCall());
			final Integer picLength = dde != null ? pictureLengthService.getLength(dde) : null;

			rc.p("if (_unstPtr >= 1 && _unstPtr <= _unstSrc.length()) {");
			rc.pNl();

			if (picLength != null) {
				// Known PIC size: extract exactly picLength chars (or remaining, whichever is less)
				rc.p("  int _unstEnd" + i + " = Math.min(_unstPtr - 1 + " + picLength + ", _unstSrc.length());");
				rc.pNl();
				rc.p("  String _unstVal" + i + " = _unstSrc.substring(_unstPtr - 1, _unstEnd" + i + ");");
				rc.pNl();
				rc.p("  int _unstCount" + i + " = _unstEnd" + i + " - (_unstPtr - 1);");
			} else {
				// Unknown PIC size: take remaining string from pointer to end
				rc.p("  String _unstVal" + i + " = _unstSrc.substring(_unstPtr - 1);");
				rc.pNl();
				rc.p("  int _unstCount" + i + " = _unstVal" + i + ".length();");
			}
			rc.pNl();
			rc.p("  ");
			emitAssignment(rc, into, "_unstVal" + i);

			// COUNT IN
			final CountIn countIn = into.getCountIn();
			if (countIn != null) {
				final String countExpr = javaExpressionService.mapToCall(countIn.getCountInCall());
				rc.p("  ");
				rc.p(countExpr);
				rc.p(" = new java.math.BigDecimal(_unstCount" + i + ");");
				rc.pNl();
			}

			// DELIMITER IN (no delimiter when no DELIMITED BY, store empty)
			final DelimiterIn delimiterIn = into.getDelimiterIn();
			if (delimiterIn != null) {
				final String delimInExpr = javaExpressionService.mapToCall(delimiterIn.getDelimiterInCall());
				rc.p("  ");
				rc.p(delimInExpr);
				rc.p(" = \"\";");
				rc.pNl();
			}

			if (picLength != null) {
				rc.p("  _unstPtr += " + picLength + ";");
			} else {
				rc.p("  _unstPtr += _unstCount" + i + ";");
			}
			rc.pNl();
			rc.p("  _unstTally++;");
			rc.pNl();
			rc.p("}");
			rc.pNl();
		}

		// Check overflow: more data remaining
		rc.p("if (_unstPtr <= _unstSrc.length()) { _unstOverflow = true; }");
		rc.pNl();

		// Update pointer variable
		if (pointerExpr != null) {
			rc.p(pointerExpr);
			rc.p(" = new java.math.BigDecimal(_unstPtr);");
			rc.pNl();
		}

		// Update tally variable
		if (tallyExpr != null) {
			rc.p(tallyExpr);
			rc.p(" = ");
			rc.p(tallyExpr);
			rc.p(" != null ? ");
			rc.p(tallyExpr);
			rc.p(".add(new java.math.BigDecimal(_unstTally)) : new java.math.BigDecimal(_unstTally);");
			rc.pNl();
		}
	}

	/**
	 * DELIMITED BY: use runtime CobolStringOps.unstring() for full parsing.
	 */
	private void emitWithDelimiters(final RuleContext rc, final List<Into> intos,
			final String pointerExpr, final String tallyExpr,
			final DelimitedByPhrase delimitedByPhrase,
			final List<OrAll> orAlls) {

		// Build delimiter list
		rc.p("java.util.List<String> _unstDelims = new java.util.ArrayList<>();");
		rc.pNl();
		rc.p("java.util.List<Boolean> _unstAllFlags = new java.util.ArrayList<>();");
		rc.pNl();

		// Primary delimiter — check for ALL keyword in parser context
		final String delimExpr = javaExpressionService.mapToExpression(delimitedByPhrase.getDelimitedByValueStmt());
		rc.p("_unstDelims.add(String.valueOf(");
		rc.p(delimExpr);
		rc.p("));");
		rc.pNl();
		boolean primaryHasAll = false;
		if (delimitedByPhrase instanceof io.proleap.cobol.asg.metamodel.procedure.unstring.impl.DelimitedByPhraseImpl) {
			final org.antlr.v4.runtime.ParserRuleContext delimCtx =
					((io.proleap.cobol.asg.metamodel.procedure.unstring.impl.DelimitedByPhraseImpl) delimitedByPhrase).getCtx();
			if (delimCtx instanceof io.proleap.cobol.CobolParser.UnstringDelimitedByPhraseContext) {
				primaryHasAll = ((io.proleap.cobol.CobolParser.UnstringDelimitedByPhraseContext) delimCtx).ALL() != null;
			}
		}
		rc.p("_unstAllFlags.add(%s);", primaryHasAll ? "true" : "false");
		rc.pNl();

		// OR [ALL] delimiters — only set ALL=true when COBOL source has ALL keyword
		if (orAlls != null) {
			for (final OrAll orAll : orAlls) {
				final String orExpr = javaExpressionService.mapToExpression(orAll.getOrAllValueStmt());
				rc.p("_unstDelims.add(String.valueOf(");
				rc.p(orExpr);
				rc.p("));");
				rc.pNl();
				// Check if ALL keyword is present in the parser context
				boolean hasAll = false;
				if (orAll instanceof io.proleap.cobol.asg.metamodel.procedure.unstring.impl.OrAllImpl) {
					final org.antlr.v4.runtime.ParserRuleContext orCtx =
							((io.proleap.cobol.asg.metamodel.procedure.unstring.impl.OrAllImpl) orAll).getCtx();
					if (orCtx instanceof io.proleap.cobol.CobolParser.UnstringOrAllPhraseContext) {
						hasAll = ((io.proleap.cobol.CobolParser.UnstringOrAllPhraseContext) orCtx).ALL() != null;
					}
				}
				rc.p("_unstAllFlags.add(%s);", hasAll ? "true" : "false");
				rc.pNl();
			}
		}

		// Pointer
		rc.p("int _unstPtr = ");
		if (pointerExpr != null) {
			rc.p(pointerExpr);
			rc.p(" != null ? ");
			rc.p(pointerExpr);
			rc.p(".intValue() : 1;");
		} else {
			rc.p("1;");
		}
		rc.pNl();

		// Call runtime
		rc.p("io.proleap.cobol.runtime.CobolStringOps.UnstringResult _unstResult = ");
		rc.p("io.proleap.cobol.runtime.CobolStringOps.unstring(_unstSrc, _unstDelims, _unstAllFlags, ");
		rc.p(String.valueOf(intos.size()));
		rc.p(", _unstPtr);");
		rc.pNl();

		rc.p("boolean _unstOverflow = _unstResult.isOverflow();");
		rc.pNl();

		// Assign results to INTO fields
		for (int i = 0; i < intos.size(); i++) {
			final Into into = intos.get(i);

			rc.p("if (_unstResult.getFields().size() > " + i + ") {");
			rc.pNl();
			rc.p("  String _unstFld" + i + " = _unstResult.getFields().get(" + i + ").getValue();");
			rc.pNl();
			rc.p("  ");
			emitAssignment(rc, into, "_unstFld" + i);

			// DELIMITER IN
			final DelimiterIn delimiterIn = into.getDelimiterIn();
			if (delimiterIn != null) {
				final String delimInExpr = javaExpressionService.mapToCall(delimiterIn.getDelimiterInCall());
				rc.p("  ");
				rc.p(delimInExpr);
				rc.p(" = _unstResult.getFields().get(" + i + ").getDelimiter();");
				rc.pNl();
			}

			// COUNT IN
			final CountIn countIn = into.getCountIn();
			if (countIn != null) {
				final String countExpr = javaExpressionService.mapToCall(countIn.getCountInCall());
				rc.p("  ");
				rc.p(countExpr);
				rc.p(" = new java.math.BigDecimal(_unstResult.getFields().get(" + i + ").getCount());");
				rc.pNl();
			}

			rc.p("}");
			rc.pNl();
		}

		// Update pointer variable
		if (pointerExpr != null) {
			rc.p(pointerExpr);
			rc.p(" = new java.math.BigDecimal(_unstResult.getPointer());");
			rc.pNl();
		}

		// Update tally variable
		if (tallyExpr != null) {
			rc.p(tallyExpr);
			rc.p(" = ");
			rc.p(tallyExpr);
			rc.p(" != null ? ");
			rc.p(tallyExpr);
			rc.p(".add(new java.math.BigDecimal(_unstResult.getTallyCount())) : new java.math.BigDecimal(_unstResult.getTallyCount());");
			rc.pNl();
		}
	}

	@Override
	public Class<UnstringStatementContext> from() {
		return UnstringStatementContext.class;
	}
}
