package io.proleap.cobol.transform.java.rules.lang;

import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.FigurativeConstantContext;
import io.proleap.cobol.asg.metamodel.FigurativeConstant;
import io.proleap.cobol.asg.metamodel.FigurativeConstant.FigurativeConstantType;
import io.proleap.cobol.asg.metamodel.Literal;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class FigurativeConstantRule extends CobolTransformRule<FigurativeConstantContext, FigurativeConstant> {

	@Override
	public void apply(final FigurativeConstantContext ctx, final FigurativeConstant figurativeConstant,
			final RuleContext rc) {
		final FigurativeConstantType type = figurativeConstant.getFigurativeConstantType();

		switch (type) {
		case ALL:
			final Literal allLiteral = figurativeConstant.getLiteral();
			if (allLiteral != null && allLiteral.getCtx() != null) {
				rc.visit(allLiteral.getCtx());
			} else {
				rc.p("\"\"");
			}
			break;
		case HIGH_VALUE:
		case HIGH_VALUES:
			rc.p("\"\\u00FF\"");
			break;
		case LOW_VALUE:
		case LOW_VALUES:
			rc.p("\"\\0\"");
			break;
		case NULL:
		case NULLS:
			rc.p("null");
			break;
		case QUOTE:
		case QUOTES:
			rc.p("\"\\\"\"");
			break;
		case SPACE:
		case SPACES:
			rc.p("\" \"");
			break;
		case ZERO:
		case ZEROES:
		case ZEROS:
			rc.p("BigDecimal.ZERO");
			break;
		default:
			break;
		}
	}

	@Override
	public Class<FigurativeConstantContext> from() {
		return FigurativeConstantContext.class;
	}
}
