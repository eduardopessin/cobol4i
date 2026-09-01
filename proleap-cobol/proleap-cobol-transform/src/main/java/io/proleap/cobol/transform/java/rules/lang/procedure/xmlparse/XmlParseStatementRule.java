package io.proleap.cobol.transform.java.rules.lang.procedure.xmlparse;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.XmlParseStatementContext;
import io.proleap.cobol.CobolParser.XmlParseProcessingPhraseContext;
import io.proleap.cobol.CobolParser.ProcedureNameContext;
import io.proleap.cobol.asg.metamodel.ASGElement;
import io.proleap.cobol.transform.java.identifier.JavaIdentifierService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

/**
 * Transforms IBM ILE COBOL XML PARSE statement into Java.
 *
 * COBOL:
 *   XML PARSE identifier-1
 *     PROCESSING PROCEDURE IS procedure-name-1
 *       [THROUGH procedure-name-2]
 *     [ON EXCEPTION imperative-statement-1]
 *     [NOT ON EXCEPTION imperative-statement-2]
 *   END-XML
 *
 * Java (generated):
 *   xmlParse(identifier1, () -> {
 *       procedureName1();
 *   });
 *
 * The xmlParse() method is defined in CobolProgram (runtime base class) and
 * performs SAX-based XML parsing, setting xml_event/xml_text special registers
 * before each call to the processing procedure.
 *
 * ON EXCEPTION / NOT ON EXCEPTION are handled by checking xml_code after parsing.
 */
@Singleton
public class XmlParseStatementRule extends CobolTransformRule<XmlParseStatementContext, ASGElement> {

	@Inject
	private JavaIdentifierService javaIdentifierService;

	@Override
	public void apply(final XmlParseStatementContext ctx, final ASGElement semanticGraphElement,
			final RuleContext rc) {

		// Get the identifier to parse (the XML content variable).
		// The identifier holds the XML string content.
		// For String fields, pass directly. For group fields, use CobolMove.groupToString().
		rc.p("xmlParse(");
		if (ctx.identifier() != null) {
			rc.visit(ctx.identifier());
		}
		rc.p(", () -> {");
		rc.pNl();
		rc.getPrinter().indent();

		// Generate call(s) to the processing procedure(s)
		final XmlParseProcessingPhraseContext processingPhrase = ctx.xmlParseProcessingPhrase();
		if (processingPhrase != null) {
			final List<ProcedureNameContext> procedureNames = processingPhrase.procedureName();
			if (procedureNames != null && !procedureNames.isEmpty()) {
				// First procedure name (the handler)
				final String procName = procedureNames.get(0).getText();
				final String javaName = javaIdentifierService.mapToIdentifier(procName);
				rc.p("%s();", javaName);
				rc.pNl();

				// If THROUGH/THRU is specified, also call subsequent procedures
				// (In COBOL, THROUGH means all paragraphs from proc-1 through proc-2)
				if (procedureNames.size() > 1) {
					for (int i = 1; i < procedureNames.size(); i++) {
						final String throughName = procedureNames.get(i).getText();
						final String javaThroughName = javaIdentifierService.mapToIdentifier(throughName);
						rc.p("%s();", javaThroughName);
						rc.pNl();
					}
				}
			}
		}

		rc.getPrinter().unindent();
		rc.p("});");
		rc.pNl();

		// Handle ON EXCEPTION clause
		if (ctx.onExceptionClause() != null) {
			rc.p("if (xml_code.intValue() != 0) {");
			rc.pNl();
			rc.getPrinter().indent();
			rc.visit(ctx.onExceptionClause());
			rc.getPrinter().unindent();
			rc.p("}");
			rc.pNl();
		}

		// Handle NOT ON EXCEPTION clause
		if (ctx.notOnExceptionClause() != null) {
			rc.p("if (xml_code.intValue() == 0) {");
			rc.pNl();
			rc.getPrinter().indent();
			rc.visit(ctx.notOnExceptionClause());
			rc.getPrinter().unindent();
			rc.p("}");
			rc.pNl();
		}
	}

	@Override
	public Class<XmlParseStatementContext> from() {
		return XmlParseStatementContext.class;
	}
}
