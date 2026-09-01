package io.proleap.cobol.transform.java.rules.lang.data;

import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.DataDivisionContext;
import io.proleap.cobol.asg.metamodel.data.DataDivision;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class DataDivisionRule extends CobolTransformRule<DataDivisionContext, DataDivision> {

	@Override
	public void apply(final DataDivisionContext ctx, final DataDivision dataDivision, final RuleContext rc) {
		rc.p("EntityService entityService = new io.proleap.cobol.runtime.impl.EntityServiceImpl();");
		rc.pNl(dataDivision);
		// sqlService, programRunner, fileControlService inherited from CobolProgram
		// Initialize them with default implementations
		rc.p("{ sqlService = new io.proleap.cobol.runtime.impl.SqlServiceImpl(); programRunner = new io.proleap.cobol.runtime.impl.ProgramRunnerImpl(); fileControlService = new io.proleap.cobol.runtime.impl.FileControlServiceImpl(); }");
		rc.pNl();
		rc.p("protected String sqlcaid = \"SQLCA   \";");
		rc.pNl();
		rc.p("protected BigDecimal sqlcabc = BigDecimal.ZERO;");
		rc.pNl();
		rc.p("protected BigDecimal sqlcode = BigDecimal.ZERO;");
		rc.pNl();
		rc.p("protected String sqlstate = \"00000\";");
		rc.pNl();
		rc.p("protected BigDecimal sqlerrml = BigDecimal.ZERO;");
		rc.pNl();
		rc.p("protected String sqlerrmc = \"\";");
		rc.pNl();
		rc.p("protected String sqlerrp = \"        \";");
		rc.pNl();
		rc.p("protected BigDecimal[] sqlerrd = new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};");
		rc.pNl();
		rc.p("protected String sqlwarn0 = \" \";");
		rc.pNl();
		rc.p("protected String sqlwarn1 = \" \";");
		rc.pNl();
		rc.p("protected String sqlwarn2 = \" \";");
		rc.pNl();
		rc.p("protected String sqlwarn3 = \" \";");
		rc.pNl();
		rc.p("protected String sqlwarn4 = \" \";");
		rc.pNl();
		rc.p("protected String sqlwarn5 = \" \";");
		rc.pNl();
		rc.p("protected String sqlwarn6 = \" \";");
		rc.pNl();
		rc.p("protected String sqlwarn7 = \" \";");
		rc.pNl();
		rc.p("protected String sqlwarn8 = \" \";");
		rc.pNl();
		rc.p("protected String sqlwarn9 = \" \";");
		rc.pNl();
		rc.p("protected String sqlwarna = \" \";");
		rc.pNl();
		rc.p("protected String sqlwarn = \"           \";");
		rc.pNl();
		rc.p("protected BigDecimal sort_return = BigDecimal.ZERO;");
		rc.pNl();
		rc.p("protected BigDecimal return_code = BigDecimal.ZERO;");
		rc.pNl();
		rc.pNl();

		rc.visitChildren(ctx);
	}

	@Override
	public Class<DataDivisionContext> from() {
		return DataDivisionContext.class;
	}
}
