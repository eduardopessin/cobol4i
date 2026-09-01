package io.proleap.cobol.transform.java.expression;

import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.call.DataDescriptionEntryCall;
import io.proleap.cobol.asg.metamodel.call.SpecialRegisterCall;
import io.proleap.cobol.asg.metamodel.call.TableCall;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.file.FileDescriptionEntry;
import io.proleap.cobol.asg.metamodel.valuestmt.ArithmeticValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.CallValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.ConditionValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.RelationConditionValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.ValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.arithmetic.Basis;
import io.proleap.cobol.asg.metamodel.valuestmt.arithmetic.MultDiv;
import io.proleap.cobol.asg.metamodel.valuestmt.arithmetic.MultDivs;
import io.proleap.cobol.asg.metamodel.valuestmt.arithmetic.PlusMinus;
import io.proleap.cobol.asg.metamodel.valuestmt.arithmetic.Powers;
import io.proleap.cobol.asg.metamodel.valuestmt.condition.AndOrCondition;
import io.proleap.cobol.asg.metamodel.valuestmt.condition.CombinableCondition;
import io.proleap.cobol.asg.metamodel.valuestmt.condition.ConditionNameReference;
import io.proleap.cobol.asg.metamodel.valuestmt.condition.SimpleCondition;
import io.proleap.cobol.asg.metamodel.valuestmt.relation.ArithmeticComparison;

public interface JavaExpressionService {

	String mapToCall(Call call);

	String mapToCall(DataDescriptionEntryCall dataDescriptionEntryCall);

	String mapToCall(FileDescriptionEntry fileDescriptionEntry);

	String mapToCall(SpecialRegisterCall specialRegisterCall);

	String mapToCall(TableCall tableCall);

	/**
	 * For 88-level condition entries, returns the parent field path (e.g., "erros" for ERRO/NOT-ERRO).
	 */
	String mapConditionToCall(DataDescriptionEntry conditionEntry);

	/**
	 * For subscripted 88-level conditions, builds the parent field path with .get(subscript)
	 * inserted at the OCCURS group level (e.g., "bkpartigos.bkpartigo.get(idx).subs_article_flag").
	 */
	String mapConditionToCallWithSubscripts(DataDescriptionEntry conditionEntry,
			java.util.List<String> subscriptExprs);

	/**
	 * Returns true if the 88-level condition value represents a boolean false (B"0", 0, etc.)
	 */
	boolean isConditionFalseValue(DataDescriptionEntry conditionEntry);

	String mapToExpression(AndOrCondition andOrCondition);

	String mapToExpression(ArithmeticComparison arithmeticComparison);

	String mapToExpression(ArithmeticValueStmt arithmeticValueStmt);

	String mapToExpression(Basis basis);

	String mapToExpression(CallValueStmt callValueStmt);

	String mapToExpression(CombinableCondition combinableCondition);

	String mapToExpression(ConditionNameReference conditionNameReference);

	String mapToExpression(ConditionValueStmt conditionValueStmt);

	String mapToExpression(MultDiv multDiv);

	String mapToExpression(MultDivs multDivs);

	String mapToExpression(PlusMinus plusMinus);

	String mapToExpression(Powers powers);

	String mapToExpression(RelationConditionValueStmt relationCondition);

	String mapToExpression(SimpleCondition simpleCondition);

	String mapToExpression(ValueStmt valueStmt);

	/**
	 * If the call references a REDEFINES field, builds the getter expression
	 * (e.g., parent.getLimckps()) with proper OCCURS subscript handling.
	 * Returns null if the call does not reference a REDEFINES field.
	 */
	String getRedefinesGetterExpression(Call call);

	/**
	 * Converts a COBOL reference modification expression (position or length) to
	 * a Java int expression. Resolves COBOL identifiers to their fully qualified
	 * Java paths using ASG when a Program context is available.
	 *
	 * @param expr the raw COBOL expression text (e.g., "WSNUM3V0", "3", "WS-POS+1")
	 * @param program the ASG Program for identifier resolution (may be null)
	 * @return a Java int expression string
	 */
	String convertRefModExpression(String expr, io.proleap.cobol.asg.metamodel.Program program);

	/**
	 * Converts an ArithmeticExpressionContext from a reference modifier to a Java int expression
	 * by walking the parse tree. Unlike convertRefModExpression(String), this properly handles
	 * qualified names (e.g., W-POSICAO OF W-PARSADOR → w_parsador.w_posicao.intValue()).
	 *
	 * @param arithExpr the arithmetic expression parse tree context
	 * @param program the ASG Program for identifier resolution (may be null)
	 * @return a Java int expression string
	 */
	String convertArithExprCtxToJavaInt(io.proleap.cobol.CobolParser.ArithmeticExpressionContext arithExpr,
			io.proleap.cobol.asg.metamodel.Program program);

	/**
	 * If the call references a child field of a group-over-elementary REDEFINES,
	 * returns the getter expression (e.g., parent.getDatmov_yyyy()).
	 * Returns null if the call is not such a child.
	 */
	String getGroupOverElementaryGetterExpression(Call call);

	/**
	 * If the call references a child field of a group-over-elementary REDEFINES,
	 * returns the setter expression prefix (e.g., parent.setDatmov_yyyy()
	 * — the caller must append the value in parentheses).
	 * Returns null if the call is not such a child.
	 */
	String getGroupOverElementarySetterPrefix(Call call);

	/**
	 * If the call references a numeric leaf that is a descendant of a group-over-elementary
	 * REDEFINES whose base is alphanumeric (PIC X), returns a String expression that reads
	 * the raw bytes of the base field at the leaf's offset. This matches COBOL MOVE semantics
	 * for MOVE numeric-USAGE-DISPLAY-over-alphanumeric-REDEFINES to an alphanumeric target
	 * (byte-for-byte copy; SPACES stay SPACES). Returns null if not applicable.
	 */
	String getGroupOverElementaryRawBaseExpression(Call call);

	/**
	 * If the call references a REDEFINES field (01-level or nested), returns the
	 * setter prefix expression (e.g., "setW_dividend_x") without parentheses.
	 * The caller must append "(value)" to complete the setter call.
	 * Returns null if the call does not reference a REDEFINES field.
	 */
	String getRedefinesSetterPrefix(Call call);
}
