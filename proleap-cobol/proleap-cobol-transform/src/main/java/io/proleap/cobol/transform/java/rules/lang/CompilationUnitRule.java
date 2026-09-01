package io.proleap.cobol.transform.java.rules.lang;

import java.util.Arrays;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.CompilationUnitContext;
import io.proleap.cobol.asg.metamodel.CompilationUnit;
import io.proleap.cobol.asg.metamodel.ProgramUnit;
import io.proleap.cobol.asg.metamodel.procedure.Paragraph;
import io.proleap.cobol.asg.metamodel.procedure.ProcedureDivision;
import io.proleap.cobol.asg.metamodel.procedure.Section;
import io.proleap.cobol.asg.metamodel.procedure.Statement;
import io.proleap.cobol.transform.java.identifier.JavaIdentifierService;
import io.proleap.cobol.transform.java.identifier.method.JavaMethodIdentifierService;
import io.proleap.cobol.transform.java.type.JavaTypeService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class CompilationUnitRule extends CobolTransformRule<CompilationUnitContext, CompilationUnit> {

	protected final List<String> imports = Arrays.asList("io.proleap.cobol.runtime.*");

	@Inject
	private JavaIdentifierService javaIdentifierService;

	@Inject
	private JavaMethodIdentifierService javaMethodIdentifierService;

	@Inject
	private JavaTypeService javaTypeService;

	@Override
	public void apply(final CompilationUnitContext ctx, final CompilationUnit compilationUnit, final RuleContext rc) {
		if (rc.getPackageName() != null && !rc.getPackageName().isEmpty()) {
			rc.p("package %s;", rc.getPackageName());
			rc.pNl();
			rc.pNl();
		}

		rc.p("import java.math.BigDecimal;");
		rc.pNl();
		rc.p("import java.math.RoundingMode;");
		rc.pNl();
		rc.p("import java.sql.*;");
		rc.pNl();
		rc.p("import java.util.Arrays;");
		rc.pNl();
		rc.p("import java.util.ArrayList;");
		rc.pNl();
		rc.p("import java.util.List;");
		rc.pNl();

		for (final String importEntry : imports) {
			rc.p("import %s;", importEntry);
			rc.pNl();
		}

		rc.pNl();

		rc.p("public class %s extends io.proleap.cobol.runtime.CobolProgram {", javaTypeService.mapToType(compilationUnit.getName()));
		rc.pNl();

		rc.getPrinter().indent();
		rc.visitChildren(ctx);

		printMainMethod(compilationUnit, rc);

		rc.getPrinter().unindent();
		rc.p("}");
		rc.pNl();
	}

	@Override
	public Class<CompilationUnitContext> from() {
		return CompilationUnitContext.class;
	}

	protected void printProcedureDivisionMethod(final CompilationUnit compilationUnit, final RuleContext rc) {
		final ProgramUnit programUnit = compilationUnit.getProgramUnit();
		if (programUnit == null) return;
		final ProcedureDivision procedureDivision = programUnit.getProcedureDivision();
		if (procedureDivision == null) return;

		final List<Statement> statements = procedureDivision.getStatements();
		final List<Paragraph> paragraphs = procedureDivision.getRootParagraphs();
		final List<Section> sections = procedureDivision.getSections();

		// If there are inline statements, ProcedureDivisionRule already generates procedureDivision()
		if (!statements.isEmpty()) {
			return;
		}

		// Find the entry point method name
		String entryCall = null;
		if (!sections.isEmpty()) {
			entryCall = javaMethodIdentifierService.mapToIdentifier(sections.get(0)) + "()";
		} else if (!paragraphs.isEmpty()) {
			entryCall = javaMethodIdentifierService.mapToIdentifier(paragraphs.get(0)) + "()";
		}

		if (entryCall == null) return;

		// Override procedureDivision() from CobolProgram for ProgramRunner.call()
		rc.pNl();
		rc.p("@Override");
		rc.pNl();
		rc.p("public void procedureDivision() {");
		rc.pNl();
		rc.getPrinter().indent();
		rc.p("try { %s; } catch (io.proleap.cobol.runtime.CobolStopRunException e) { /* normal GOBACK */ } catch (Exception e) { throw new RuntimeException(e); }", entryCall);
		rc.pNl();
		rc.getPrinter().unindent();
		rc.p("}");
		rc.pNl();
	}

	protected void printMainMethod(final CompilationUnit compilationUnit, final RuleContext rc) {
		final ProgramUnit programUnit = compilationUnit.getProgramUnit();
		if (programUnit == null) return;
		final ProcedureDivision procedureDivision = programUnit.getProcedureDivision();
		if (procedureDivision == null) return;

		// First, generate procedureDivision() override
		printProcedureDivisionMethod(compilationUnit, rc);

		// Then generate main()
		rc.pNl();
		rc.p("public static void main(String[] args) throws Exception {");
		rc.pNl();
		rc.getPrinter().indent();

		final String className = javaTypeService.mapToType(compilationUnit.getName());
		final String instanceName = javaIdentifierService.mapToIdentifier(compilationUnit.getName());

		rc.p("final %s %s = new %s();", className, instanceName, className);
		rc.pNl();
		rc.p("%s.procedureDivision();", instanceName);
		rc.pNl();

		rc.getPrinter().unindent();
		rc.p("}");
		rc.pNl();
	}
}
