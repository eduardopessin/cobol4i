package io.proleap.cobol.transform.java.rules.lang.procedure.multiply;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.MultiplyStatementContext;
import io.proleap.cobol.asg.metamodel.procedure.NotOnSizeErrorPhrase;
import io.proleap.cobol.asg.metamodel.procedure.OnSizeErrorPhrase;
import io.proleap.cobol.asg.metamodel.procedure.Statement;
import io.proleap.cobol.asg.metamodel.procedure.multiply.ByOperand;
import io.proleap.cobol.asg.metamodel.procedure.multiply.ByPhrase;
import io.proleap.cobol.asg.metamodel.procedure.multiply.GivingPhrase;
import io.proleap.cobol.asg.metamodel.procedure.multiply.GivingResult;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.procedure.multiply.MultiplyStatement;
import io.proleap.cobol.asg.metamodel.procedure.multiply.MultiplyStatement.MultiplyType;
import io.proleap.cobol.asg.metamodel.valuestmt.CallValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.ValueStmt;
import io.proleap.cobol.commons.datadescription.CobolDataDescriptionEntryService;
import io.proleap.cobol.commons.datadescription.CobolPictureLengthService;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.transform.java.expression.JavaExpressionService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class MultiplyStatementRule extends CobolTransformRule<MultiplyStatementContext, MultiplyStatement> {

	@Inject
	private CobolDataDescriptionEntryService dataDescriptionEntryService;

	@Inject
	private CobolPictureLengthService cobolPictureLengthService;

	@Inject
	private JavaExpressionService javaExpressionService;

	@Inject
	private CobolTypeService cobolTypeService;

	@Override
	public void apply(final MultiplyStatementContext ctx, final MultiplyStatement multiplyStatement,
			final RuleContext rc) {
		final OnSizeErrorPhrase onSizeErrorPhrase = multiplyStatement.getOnSizeErrorPhrase();
		final NotOnSizeErrorPhrase notOnSizeErrorPhrase = multiplyStatement.getNotOnSizeErrorPhrase();
		final boolean hasOnSizeError = onSizeErrorPhrase != null;

		if (hasOnSizeError) {
			rc.p("try {");
			rc.pNl();
			rc.getPrinter().indent();
		}

		final MultiplyType type = multiplyStatement.getMultiplyType();

		switch (type) {
		case BY_GIVING:
			printMultiplyGiving(multiplyStatement, rc);
			break;
		case BY:
			printMultiplyRegular(multiplyStatement, rc);
			break;
		default:
			break;
		}

		if (notOnSizeErrorPhrase != null) {
			for (final Statement statement : notOnSizeErrorPhrase.getStatements()) {
				rc.visit(statement.getCtx());
			}
		}

		if (hasOnSizeError) {
			rc.getPrinter().unindent();
			rc.p("} catch (ArithmeticException e) {");
			rc.pNl();
			rc.getPrinter().indent();

			for (final Statement statement : onSizeErrorPhrase.getStatements()) {
				rc.visit(statement.getCtx());
			}

			rc.getPrinter().unindent();
			rc.p("}");
			rc.pNl(multiplyStatement);
		}
	}

	@Override
	public Class<MultiplyStatementContext> from() {
		return MultiplyStatementContext.class;
	}

	protected void printGiving(final MultiplyStatement multiplyStatement, final GivingPhrase giving,
			final GivingResult givingResult, final RuleContext rc) {
		final Call resultCall = givingResult.getResultCall();
		final CobolTypeEnum resultType = resultCall != null ? cobolTypeService.getType(resultCall) : null;
		final boolean targetIsAlphanumeric = CobolTypeEnum.STRING.equals(resultType);

		rc.visit(resultCall.getCtx());
		rc.p(" = ");

		if (targetIsAlphanumeric) {
			// Target is alphanumeric (String): wrap arithmetic result with moveNumericToAlphanumeric
			final Integer targetLength = resultCall != null
					? cobolPictureLengthService.getLength(dataDescriptionEntryService.getDataDescriptionEntry(resultCall))
					: null;
			final int len = targetLength != null ? targetLength : 18;
			rc.p("CobolMove.moveNumericToAlphanumeric(");
			visitNumericValueStmt(multiplyStatement.getOperandValueStmt(), rc);
			rc.p(".multiply(");
			visitNumericValueStmt(giving.getGivingOperand().getOperandValueStmt(), rc);
			rc.p("), %d);", len);
		} else {
			visitNumericValueStmt(multiplyStatement.getOperandValueStmt(), rc);
			rc.p(".multiply(");
			visitNumericValueStmt(giving.getGivingOperand().getOperandValueStmt(), rc);
			rc.p(");");
		}

		rc.pNl(givingResult);
	}

	protected void printMultiplyGiving(final MultiplyStatement multiplyStatement, final RuleContext rc) {
		final GivingPhrase giving = multiplyStatement.getGivingPhrase();

		for (final GivingResult givingResult : giving.getGivingResults()) {
			printGiving(multiplyStatement, giving, givingResult, rc);
		}
	}

	protected void printMultiplyRegular(final MultiplyStatement multiplyStatement, final RuleContext rc) {
		final ByPhrase regular = multiplyStatement.getByPhrase();

		// MULTIPLY A BY B means B = A * B
		// Each BY operand is a target that gets updated: target = target.multiply(operand)
		for (final ByOperand regularOperand : regular.getByOperands()) {
			final Call operandCall = regularOperand.getOperandCall();
			final CobolTypeEnum operandType = operandCall != null ? cobolTypeService.getType(operandCall) : null;
			final boolean targetIsAlphanumeric = CobolTypeEnum.STRING.equals(operandType);

			rc.visit(operandCall.getCtx());
			rc.p(" = ");

			if (targetIsAlphanumeric) {
				// Target is alphanumeric: wrap with moveNumericToAlphanumeric(moveAlphanumericToNumeric(target).multiply(operand), len)
				final Integer targetLength = operandCall != null
						? cobolPictureLengthService.getLength(dataDescriptionEntryService.getDataDescriptionEntry(operandCall))
						: null;
				final int len = targetLength != null ? targetLength : 18;
				rc.p("CobolMove.moveNumericToAlphanumeric(CobolMove.moveAlphanumericToNumeric(");
				rc.visit(operandCall.getCtx());
				rc.p(", 18, 0).multiply(");
				visitNumericValueStmt(multiplyStatement.getOperandValueStmt(), rc);
				rc.p("), %d);", len);
			} else {
				rc.visit(operandCall.getCtx());
				rc.p(".multiply(");
				visitNumericValueStmt(multiplyStatement.getOperandValueStmt(), rc);
				rc.p(");");
			}

			rc.pNl(regularOperand);
		}
	}

	/**
	 * Visits a ValueStmt for use as a numeric operand in multiply operations.
	 * Handles REDEFINES getter resolution and alphanumeric-to-numeric wrapping.
	 */
	private void visitNumericValueStmt(final ValueStmt valueStmt, final RuleContext rc) {
		if (valueStmt instanceof CallValueStmt) {
			final CallValueStmt callVs = (CallValueStmt) valueStmt;
			final String redefinesGetter = javaExpressionService.getRedefinesGetterExpression(callVs.getCall());
			if (redefinesGetter != null) {
				rc.p(redefinesGetter);
				return;
			}
			// Check if the operand is alphanumeric — wrap in numeric conversion
			final CobolTypeEnum type = cobolTypeService.getType(valueStmt);
			if (CobolTypeEnum.STRING.equals(type)) {
				rc.p("CobolMove.moveAlphanumericToNumeric(");
				rc.visit(valueStmt.getCtx());
				rc.p(", 18, 0)");
				return;
			}
		}
		rc.visit(valueStmt.getCtx());
	}

	/**
	 * Visits a ValueStmt, but if it's a CallValueStmt referencing a REDEFINES field,
	 * uses the getter expression instead of the raw base field name.
	 */
	private void visitValueStmtWithRedefines(final ValueStmt valueStmt, final RuleContext rc) {
		if (valueStmt instanceof CallValueStmt) {
			final CallValueStmt callVs = (CallValueStmt) valueStmt;
			final String redefinesGetter = javaExpressionService.getRedefinesGetterExpression(callVs.getCall());
			if (redefinesGetter != null) {
				rc.p(redefinesGetter);
				return;
			}
		}
		rc.visit(valueStmt.getCtx());
	}
}
