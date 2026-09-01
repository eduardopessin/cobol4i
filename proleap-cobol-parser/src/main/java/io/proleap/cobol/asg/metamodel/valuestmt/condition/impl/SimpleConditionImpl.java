/*
 * Copyright (C) 2017, Ulrich Wolffgang <ulrich.wolffgang@proleap.io>
 * All rights reserved.
 *
 * This software may be modified and distributed under the terms
 * of the MIT license. See the LICENSE file for details.
 */

package io.proleap.cobol.asg.metamodel.valuestmt.condition.impl;

import java.util.List;

import io.proleap.cobol.CobolParser.ClassConditionContext;
import io.proleap.cobol.CobolParser.ConditionContext;
import io.proleap.cobol.CobolParser.ConditionNameReferenceContext;
import io.proleap.cobol.CobolParser.ConditionNameSubscriptReferenceContext;
import io.proleap.cobol.CobolParser.InDataContext;
import io.proleap.cobol.CobolParser.InMnemonicContext;
import io.proleap.cobol.CobolParser.RelationConditionContext;
import io.proleap.cobol.CobolParser.SimpleConditionContext;
import io.proleap.cobol.asg.metamodel.ProgramUnit;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.call.Call.CallType;
import io.proleap.cobol.asg.metamodel.call.DataDescriptionEntryCall;
import io.proleap.cobol.asg.metamodel.call.impl.DataDescriptionEntryCallImpl;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.valuestmt.ConditionValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.RelationConditionValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.condition.ClassCondition;
import io.proleap.cobol.asg.metamodel.valuestmt.condition.ConditionNameReference;
import io.proleap.cobol.asg.metamodel.valuestmt.condition.SimpleCondition;
import io.proleap.cobol.asg.metamodel.valuestmt.impl.ValueStmtImpl;

public class SimpleConditionImpl extends ValueStmtImpl implements SimpleCondition {

	protected ClassCondition classCondition;

	protected ConditionValueStmt condition;

	protected ConditionNameReference conditionNameReference;

	protected SimpleConditionContext ctx;

	protected RelationConditionValueStmt relationCondition;

	protected SimpleConditionType simpleConditionType;

	public SimpleConditionImpl(final ProgramUnit programUnit, final SimpleConditionContext ctx) {
		super(programUnit, ctx);
	}

	@Override
	public ClassCondition addClassCondition(final ClassConditionContext ctx) {
		ClassCondition result = (ClassCondition) getASGElement(ctx);

		if (result == null) {
			result = new ClassConditionImpl(programUnit, ctx);

			// identifier
			if (ctx.identifier() != null) {
				final Call identifierCall = createCall(ctx.identifier());
				result.setIdentifierCall(identifierCall);
			}

			// not
			final boolean not = ctx.NOT() != null;
			result.setNot(not);

			// type
			final ClassCondition.ClassConditionType type;

			if (ctx.NUMERIC() != null) {
				type = ClassCondition.ClassConditionType.NUMERIC;
			} else if (ctx.ALPHABETIC() != null) {
				type = ClassCondition.ClassConditionType.ALPHABETIC;
			} else if (ctx.ALPHABETIC_LOWER() != null) {
				type = ClassCondition.ClassConditionType.ALPHABETIC_LOWER;
			} else if (ctx.ALPHABETIC_UPPER() != null) {
				type = ClassCondition.ClassConditionType.ALPHABETIC_UPPER;
			} else if (ctx.DBCS() != null) {
				type = ClassCondition.ClassConditionType.DBCS;
			} else if (ctx.KANJI() != null) {
				type = ClassCondition.ClassConditionType.KANJI;
			} else if (ctx.className() != null) {
				type = ClassCondition.ClassConditionType.CLASS_NAME;
			} else {
				type = null;
			}

			result.setClassConditionType(type);

			// class call
			if (ctx.className() != null) {
				final Call classCall = createCall(ctx.className());
				result.setClassCall(classCall);
			}

			classCondition = result;
			subValueStmts.add(result);
			registerASGElement(result);
		}

		return result;
	}

	@Override
	public ConditionValueStmt addCondition(final ConditionContext ctx) {
		ConditionValueStmt result = (ConditionValueStmt) getASGElement(ctx);

		if (result == null) {
			result = createConditionValueStmt(ctx);

			condition = result;
			subValueStmts.add(result);
		}

		return result;
	}

	@Override
	public ConditionNameReference addConditionNameReference(final ConditionNameReferenceContext ctx) {
		ConditionNameReference result = (ConditionNameReference) getASGElement(ctx);

		if (result == null) {
			result = new ConditionNameReferenceImpl(programUnit, ctx);

			// condition name — resolve with OF/IN qualifier awareness
			Call conditionCall = createCall(ctx.conditionName());

			// If there are OF/IN qualifiers (inData), verify the resolved call
			// matches the qualifier. When multiple 88-level conditions share the
			// same name under different parents, createCall picks the first match
			// which may be wrong. Re-resolve using the qualifier to disambiguate.
			if (!ctx.inData().isEmpty() && conditionCall != null
					&& conditionCall.getCallType() == CallType.DATA_DESCRIPTION_ENTRY_CALL) {
				final DataDescriptionEntryCall ddeCall = (DataDescriptionEntryCall) conditionCall.unwrap();
				final DataDescriptionEntry resolvedDde = ddeCall.getDataDescriptionEntry();

				// Collect qualifier names from inData (nearest to furthest)
				final java.util.List<String> qualifierNames = new java.util.ArrayList<>();
				for (final InDataContext inDataCtx : ctx.inData()) {
					if (inDataCtx.dataName() != null) {
						qualifierNames.add(inDataCtx.dataName().getText().toUpperCase().replace('-', ' ')
								.replace('_', ' ').trim().replaceAll("\\s+", ""));
					}
				}

				if (!qualifierNames.isEmpty()) {
					// Check if the resolved entry's parent chain matches the qualifier
					if (!ancestorMatchesQualifiers(resolvedDde, qualifierNames)) {
						// Re-resolve: find all candidates and pick the one matching the qualifier
						final String condName = determineName(ctx.conditionName());
						final List<DataDescriptionEntry> allCandidates = findDataDescriptionEntries(condName);

						for (final DataDescriptionEntry candidate : allCandidates) {
							if (candidate != resolvedDde && ancestorMatchesQualifiers(candidate, qualifierNames)) {
								// Directly construct a new call — cannot use createDataDescriptionEntryCall
								// because it would return the cached (wrong) call for the same parse context
								conditionCall = new DataDescriptionEntryCallImpl(condName, candidate, programUnit, ctx.conditionName());
								linkDataDescriptionEntryCallWithDataDescriptionEntry(
										(DataDescriptionEntryCall) conditionCall, candidate);
								break;
							}
						}
					}
				}
			}

			result.setConditionCall(conditionCall);

			// in data
			for (final InDataContext inDataContext : ctx.inData()) {
				result.addInData(inDataContext);
			}

			// in file
			if (ctx.inFile() != null) {
				result.addInFile(ctx.inFile());
			}

			// subscript
			for (final ConditionNameSubscriptReferenceContext conditionNameSubscriptReferenceContext : ctx
					.conditionNameSubscriptReference()) {
				result.addSubscriptReference(conditionNameSubscriptReferenceContext);
			}

			// mnemonic
			for (final InMnemonicContext inMnemonicContext : ctx.inMnemonic()) {
				result.addInMnemonic(inMnemonicContext);
			}

			conditionNameReference = result;
			subValueStmts.add(result);
			registerASGElement(result);
		}

		return result;
	}

	/**
	 * Checks whether any ancestor of the given entry matches the qualifier names.
	 * Qualifier names are normalized (uppercase, no hyphens/underscores/spaces).
	 */
	private boolean ancestorMatchesQualifiers(final DataDescriptionEntry entry,
			final java.util.List<String> qualifierNames) {
		int qualIdx = 0;
		DataDescriptionEntryGroup parent = entry.getParentDataDescriptionEntryGroup();

		while (parent != null && qualIdx < qualifierNames.size()) {
			final String parentName = parent.getName();
			if (parentName != null) {
				final String normalizedParent = parentName.toUpperCase().replace("-", "")
						.replace("_", "").replace(" ", "");
				if (normalizedParent.equals(qualifierNames.get(qualIdx))) {
					qualIdx++;
				}
			}
			parent = parent.getParentDataDescriptionEntryGroup();
		}

		return qualIdx == qualifierNames.size();
	}

	@Override
	public RelationConditionValueStmt addRelationCondition(final RelationConditionContext ctx) {
		RelationConditionValueStmt result = (RelationConditionValueStmt) getASGElement(ctx);

		if (result == null) {
			result = createRelationConditionValueStmt(ctx);

			relationCondition = result;
			subValueStmts.add(result);
		}

		return result;
	}

	@Override
	public ClassCondition getClassCondition() {
		return classCondition;
	}

	@Override
	public ConditionValueStmt getCondition() {
		return condition;
	}

	@Override
	public ConditionNameReference getConditionNameReference() {
		return conditionNameReference;
	}

	@Override
	public RelationConditionValueStmt getRelationCondition() {
		return relationCondition;
	}

	@Override
	public SimpleConditionType getSimpleConditionType() {
		return simpleConditionType;
	}

	@Override
	public void setSimpleConditionType(final SimpleConditionType simpleConditionType) {
		this.simpleConditionType = simpleConditionType;
	}
}
