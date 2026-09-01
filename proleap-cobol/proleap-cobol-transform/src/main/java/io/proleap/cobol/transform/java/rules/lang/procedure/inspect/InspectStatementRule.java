package io.proleap.cobol.transform.java.rules.lang.procedure.inspect;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.InspectStatementContext;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.call.Call.CallType;
import io.proleap.cobol.asg.metamodel.call.DataDescriptionEntryCall;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry.DataDescriptionEntryType;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.procedure.inspect.AllLeading;
import io.proleap.cobol.asg.metamodel.procedure.inspect.AllLeadingPhrase;
import io.proleap.cobol.asg.metamodel.procedure.inspect.AllLeadingPhrase.AllLeadingsType;
import io.proleap.cobol.asg.metamodel.procedure.inspect.BeforeAfterPhrase;
import io.proleap.cobol.asg.metamodel.procedure.inspect.BeforeAfterPhrase.BeforeAfterType;
import io.proleap.cobol.asg.metamodel.procedure.inspect.Characters;
import io.proleap.cobol.asg.metamodel.procedure.inspect.For;
import io.proleap.cobol.asg.metamodel.procedure.inspect.InspectStatement;
import io.proleap.cobol.asg.metamodel.procedure.inspect.InspectStatement.InspectType;
import io.proleap.cobol.asg.metamodel.procedure.inspect.Replacing;
import io.proleap.cobol.asg.metamodel.procedure.inspect.ReplacingAllLeadings;
import io.proleap.cobol.asg.metamodel.procedure.inspect.ReplacingAllLeadings.ReplacingAllLeadingsType;
import io.proleap.cobol.asg.metamodel.procedure.inspect.ReplacingAllLeading;
import io.proleap.cobol.asg.metamodel.procedure.inspect.By;
import io.proleap.cobol.asg.metamodel.procedure.inspect.Converting;
import io.proleap.cobol.asg.metamodel.procedure.inspect.Tallying;
import io.proleap.cobol.asg.metamodel.procedure.inspect.To;
import io.proleap.cobol.asg.metamodel.valuestmt.ValueStmt;
import io.proleap.cobol.transform.java.expression.JavaExpressionService;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class InspectStatementRule extends CobolTransformRule<InspectStatementContext, InspectStatement> {

	@Inject
	private JavaExpressionService javaExpressionService;

	@Override
	public void apply(final InspectStatementContext ctx, final InspectStatement inspectStatement,
			final RuleContext rc) {
		final Call dataItemCall = inspectStatement.getDataItemCall();
		final String dataItemExpr = javaExpressionService.mapToCall(dataItemCall);
		final boolean isGroup = isGroupItem(dataItemCall);
		final InspectType inspectType = inspectStatement.getInspectType();

		if (inspectType == InspectType.REPLACING) {
			handleReplacing(dataItemExpr, isGroup, inspectStatement.getReplacing(), inspectStatement, rc);
		} else if (inspectType == InspectType.CONVERTING) {
			handleConverting(dataItemExpr, isGroup, inspectStatement.getConverting(), inspectStatement, rc);
		} else if (inspectType == InspectType.TALLYING) {
			handleTallying(dataItemExpr, inspectStatement.getTallying(), inspectStatement, rc);
		} else {
			rc.p("// TODO: INSPECT %s (type=%s) not yet supported", dataItemExpr, inspectType);
			rc.pNl(inspectStatement);
		}
	}

	/**
	 * Check if the data item call refers to a group item (has sub-fields).
	 */
	private boolean isGroupItem(final Call call) {
		if (call == null) {
			return false;
		}
		final Call unwrapped = call.unwrap();
		if (unwrapped.getCallType() == CallType.DATA_DESCRIPTION_ENTRY_CALL) {
			final DataDescriptionEntryCall ddeCall = (DataDescriptionEntryCall) unwrapped;
			final DataDescriptionEntry dde = ddeCall.getDataDescriptionEntry();
			if (dde != null && dde.getDataDescriptionEntryType() == DataDescriptionEntryType.GROUP) {
				final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) dde;
				return group.getDataDescriptionEntries() != null && !group.getDataDescriptionEntries().isEmpty();
			}
		}
		return false;
	}

	/**
	 * Generate the expression to convert the data item to a String.
	 * For String fields, returns the field expression directly.
	 * For group items, wraps with CobolMove.groupToString().
	 */
	private String toStringExpr(final String dataItemExpr, final boolean isGroup) {
		if (isGroup) {
			return String.format("io.proleap.cobol.runtime.CobolMove.groupToString(%s)", dataItemExpr);
		}
		return dataItemExpr;
	}

	/**
	 * Generate code to assign the INSPECT result back to the data item.
	 * For String fields: direct assignment.
	 * For group items: use moveStringToGroup to distribute characters back to sub-fields.
	 */
	private void emitAssignBack(final String dataItemExpr, final String resultVar,
			final boolean isGroup, final RuleContext rc) {
		if (isGroup) {
			rc.p("io.proleap.cobol.runtime.CobolMove.moveStringToGroup(%s, %s); ", resultVar, dataItemExpr);
		} else {
			rc.p("%s = %s; ", dataItemExpr, resultVar);
		}
	}

	/**
	 * Handle INSPECT ... TALLYING counter FOR {CHARACTERS | ALL | LEADING} pattern.
	 * Generates code that accumulates the tally count into the counter variable.
	 * The inspected item can be a plain variable or a function call (e.g., FUNCTION REVERSE).
	 */
	private void handleTallying(final String dataItemExpr, final Tallying tallying,
			final InspectStatement inspectStatement, final RuleContext rc) {
		if (tallying == null) {
			return;
		}

		for (final For tallyFor : tallying.getFors()) {
			final Call tallyCountCall = tallyFor.getTallyCountDataItemCall();
			final String counterExpr = javaExpressionService.mapToCall(tallyCountCall);

			// Process TALLYING FOR CHARACTERS
			for (final Characters characters : tallyFor.getCharacters()) {
				// INSPECT x TALLYING c FOR CHARACTERS → c = c + length(x)
				// Note: BEFORE/AFTER not yet handled for simplicity; use runtime for full support
				if (characters.getBeforeAfterPhrases().isEmpty()) {
					rc.p("{ String _inspTarget = String.valueOf(%s); ", dataItemExpr);
					rc.p("%s = %s.add(java.math.BigDecimal.valueOf(io.proleap.cobol.runtime.CobolStringOps.tallyCharacters(_inspTarget))); }",
							counterExpr, counterExpr);
					rc.pNl(inspectStatement);
				} else {
					rc.p("{ String _inspTarget = String.valueOf(%s); ", dataItemExpr);
					rc.p("int _charCount = 0; ");
					for (final BeforeAfterPhrase bap : characters.getBeforeAfterPhrases()) {
						final BeforeAfterType baType = bap.getBeforeAfterType();
						final ValueStmt delimStmt = bap.getDataItemValueStmt();
						final String delimExpr = javaExpressionService.mapToExpression(delimStmt);
						if (baType == BeforeAfterType.BEFORE) {
							// BEFORE INITIAL: count characters before first occurrence of delimiter
							// If delimiter not found, count all characters
							rc.p("{ String _delim = String.valueOf(%s); ", delimExpr);
							rc.p("int _pos = _inspTarget.indexOf(_delim); ");
							rc.p("_charCount += (_pos >= 0 ? _pos : _inspTarget.length()); } ");
						} else {
							// AFTER INITIAL: count characters after first occurrence of delimiter
							// If delimiter not found, count is 0
							rc.p("{ String _delim = String.valueOf(%s); ", delimExpr);
							rc.p("int _pos = _inspTarget.indexOf(_delim); ");
							rc.p("_charCount += (_pos >= 0 ? _inspTarget.length() - _pos - _delim.length() : 0); } ");
						}
					}
					rc.p("%s = %s.add(java.math.BigDecimal.valueOf(_charCount)); }",
							counterExpr, counterExpr);
					rc.pNl(inspectStatement);
				}
			}

			// Process TALLYING FOR ALL/LEADING pattern
			for (final AllLeadingPhrase allLeadingPhrase : tallyFor.getAllLeadingPhrase()) {
				final AllLeadingsType allLeadingsType = allLeadingPhrase.getAllLeadingsType();

				for (final AllLeading allLeading : allLeadingPhrase.getAllLeadings()) {
					final ValueStmt patternStmt = allLeading.getPatternDataItemValueStmt();
					if (patternStmt == null) {
						continue;
					}
					final String patternExpr = javaExpressionService.mapToExpression(patternStmt);

					if (allLeadingsType == AllLeadingsType.LEADING) {
						// INSPECT x TALLYING c FOR LEADING pattern
						rc.p("{ String _inspTarget = String.valueOf(%s); ", dataItemExpr);
						rc.p("%s = %s.add(java.math.BigDecimal.valueOf(io.proleap.cobol.runtime.CobolStringOps.tallyLeading(_inspTarget, String.valueOf(%s).charAt(0)))); }",
								counterExpr, counterExpr, patternExpr);
						rc.pNl(inspectStatement);
					} else if (allLeadingsType == AllLeadingsType.ALL) {
						// INSPECT x TALLYING c FOR ALL pattern
						rc.p("{ String _inspTarget = String.valueOf(%s); ", dataItemExpr);
						rc.p("%s = %s.add(java.math.BigDecimal.valueOf(io.proleap.cobol.runtime.CobolStringOps.tallyAll(_inspTarget, String.valueOf(%s).charAt(0)))); }",
								counterExpr, counterExpr, patternExpr);
						rc.pNl(inspectStatement);
					}
				}
			}
		}
	}

	private void handleReplacing(final String dataItemExpr, final boolean isGroup, final Replacing replacing,
			final InspectStatement inspectStatement, final RuleContext rc) {
		if (replacing == null) {
			return;
		}

		final String toStr = toStringExpr(dataItemExpr, isGroup);

		final List<ReplacingAllLeadings> allLeadingsList = replacing.getAllLeadings();
		if (allLeadingsList != null) {
			for (final ReplacingAllLeadings allLeadings : allLeadingsList) {
				final ReplacingAllLeadingsType type = allLeadings.getReplacingAllLeadingsType();
				final List<ReplacingAllLeading> entries = allLeadings.getAllLeadings();

				if (entries != null) {
					for (final ReplacingAllLeading entry : entries) {
						final ValueStmt patternStmt = entry.getPatternDataItemValueStmt();
						final By by = entry.getBy();

						if (patternStmt == null || by == null || by.getByValueStmt() == null) {
							continue;
						}

						String patternExpr = javaExpressionService.mapToExpression(patternStmt);
						String byExpr = javaExpressionService.mapToExpression(by.getByValueStmt());
						// Fix figurative constants that produce empty expressions
						if (patternExpr == null || patternExpr.isEmpty()) {
							patternExpr = resolveFigurativeConstantExpr(patternStmt);
						}
						if (byExpr == null || byExpr.isEmpty()) {
							byExpr = resolveFigurativeConstantExpr(by.getByValueStmt());
						}

						if (type == ReplacingAllLeadingsType.ALL) {
							// INSPECT field REPLACING ALL "x" BY "y"
							rc.p("{ String _inspResult = %s.replace(String.valueOf(%s), String.valueOf(%s)); ",
									toStr, patternExpr, byExpr);
							emitAssignBack(dataItemExpr, "_inspResult", isGroup, rc);
							rc.p("}");
							rc.pNl(inspectStatement);
						} else if (type == ReplacingAllLeadingsType.LEADING) {
							// INSPECT field REPLACING LEADING "x" BY "y"
							rc.p("{ String _p = String.valueOf(%s); String _r = String.valueOf(%s); ", patternExpr, byExpr);
							rc.p("int _i = 0; StringBuilder _sb = new StringBuilder(%s); ", toStr);
							rc.p("while (_i <= _sb.length() - _p.length() && _sb.substring(_i, _i + _p.length()).equals(_p)) ");
							rc.p("{ _sb.replace(_i, _i + _p.length(), _r); _i += _r.length(); } ");
							rc.p("String _inspResult = _sb.toString(); ");
							emitAssignBack(dataItemExpr, "_inspResult", isGroup, rc);
							rc.p("}");
							rc.pNl(inspectStatement);
						} else if (type == ReplacingAllLeadingsType.FIRST) {
							// INSPECT field REPLACING FIRST "x" BY "y"
							rc.p("{ String _inspResult = %s; ", toStr);
							rc.p("String _p = String.valueOf(%s); int _idx = _inspResult.indexOf(_p); ",
									patternExpr);
							rc.p("if (_idx >= 0) { _inspResult = _inspResult.substring(0, _idx) + String.valueOf(%s) + _inspResult.substring(_idx + _p.length()); } ",
									byExpr);
							emitAssignBack(dataItemExpr, "_inspResult", isGroup, rc);
							rc.p("}");
							rc.pNl(inspectStatement);
						}
					}
				}
			}
		}
	}

	private void handleConverting(final String dataItemExpr, final boolean isGroup, final Converting converting,
			final InspectStatement inspectStatement, final RuleContext rc) {
		if (converting == null) {
			return;
		}

		final ValueStmt fromStmt = converting.getFromValueStmt();
		final To to = converting.getTo();

		if (fromStmt == null || to == null || to.getToValueStmt() == null) {
			return;
		}

		final String fromExpr = javaExpressionService.mapToExpression(fromStmt);
		final String toExpr = javaExpressionService.mapToExpression(to.getToValueStmt());

		final String toStr = toStringExpr(dataItemExpr, isGroup);

		// INSPECT field CONVERTING "abc" TO "xyz"
		// Character-by-character translation (like tr)
		rc.p("{ String _from = String.valueOf(%s); String _to = String.valueOf(%s); ", fromExpr, toExpr);
		rc.p("StringBuilder _sb = new StringBuilder(%s); ", toStr);
		rc.p("for (int _i = 0; _i < _sb.length(); _i++) { ");
		rc.p("int _pos = _from.indexOf(_sb.charAt(_i)); ");
		rc.p("if (_pos >= 0 && _pos < _to.length()) _sb.setCharAt(_i, _to.charAt(_pos)); } ");
		rc.p("String _inspResult = _sb.toString(); ");
		emitAssignBack(dataItemExpr, "_inspResult", isGroup, rc);
		rc.p("}");
		rc.pNl(inspectStatement);
	}

	/**
	 * Resolves figurative constants that produce empty expressions via mapToExpression.
	 * LOW-VALUE → "\0", HIGH-VALUE → "\u00FF", SPACE → " ", ZERO → "0"
	 */
	private String resolveFigurativeConstantExpr(final io.proleap.cobol.asg.metamodel.valuestmt.ValueStmt valueStmt) {
		if (valueStmt == null || valueStmt.getCtx() == null) {
			return "\"\"";
		}
		final String text = valueStmt.getCtx().getText().toUpperCase().replace("-", "").replace("_", "");
		if (text.contains("LOWVALUE") || text.contains("LOWVALUES")) {
			return "\"\\0\"";
		} else if (text.contains("HIGHVALUE") || text.contains("HIGHVALUES")) {
			return "\"\\u00FF\"";
		} else if (text.contains("SPACE") || text.contains("SPACES")) {
			return "\" \"";
		} else if (text.contains("ZERO") || text.contains("ZEROS") || text.contains("ZEROES")) {
			return "\"0\"";
		}
		return "\"\"";
	}

	@Override
	public Class<InspectStatementContext> from() {
		return InspectStatementContext.class;
	}
}
