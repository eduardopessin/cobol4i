package io.proleap.cobol.transform.java.expression.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;

import io.proleap.cobol.CobolParser;
import io.proleap.cobol.asg.metamodel.CompilationUnit;
import io.proleap.cobol.asg.metamodel.Program;
import io.proleap.cobol.asg.metamodel.ProgramUnit;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.call.Call.CallType;
import io.proleap.cobol.asg.metamodel.call.DataDescriptionEntryCall;
import io.proleap.cobol.asg.metamodel.call.SpecialRegisterCall;
import io.proleap.cobol.asg.metamodel.call.SpecialRegisterCall.SpecialRegisterType;
import io.proleap.cobol.asg.metamodel.call.TableCall;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryContainer;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry.DataDescriptionEntryType;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryCondition;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.data.datadescription.ValueClause;
import io.proleap.cobol.asg.metamodel.data.datadescription.ValueInterval;
import io.proleap.cobol.asg.metamodel.data.file.FileDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.file.FileSection;
import io.proleap.cobol.asg.metamodel.registry.ASGElementRegistry;
import io.proleap.cobol.asg.metamodel.valuestmt.ArithmeticValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.CallValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.ConditionValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.RelationConditionValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.RelationConditionValueStmt.RelationConditionType;
import io.proleap.cobol.asg.metamodel.valuestmt.Subscript;
import io.proleap.cobol.asg.metamodel.valuestmt.ValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.arithmetic.Basis;
import io.proleap.cobol.asg.metamodel.valuestmt.arithmetic.MultDiv;
import io.proleap.cobol.asg.metamodel.valuestmt.arithmetic.MultDiv.MultDivType;
import io.proleap.cobol.asg.metamodel.valuestmt.arithmetic.MultDivs;
import io.proleap.cobol.asg.metamodel.valuestmt.arithmetic.PlusMinus;
import io.proleap.cobol.asg.metamodel.valuestmt.arithmetic.PlusMinus.PlusMinusType;
import io.proleap.cobol.asg.metamodel.valuestmt.arithmetic.Power;
import io.proleap.cobol.asg.metamodel.valuestmt.arithmetic.Powers;
import io.proleap.cobol.asg.metamodel.valuestmt.arithmetic.Powers.PowersType;
import io.proleap.cobol.asg.metamodel.valuestmt.condition.AndOrCondition;
import io.proleap.cobol.asg.metamodel.valuestmt.condition.AndOrCondition.AndOrConditionType;
import io.proleap.cobol.asg.metamodel.valuestmt.condition.ClassCondition;
import io.proleap.cobol.asg.metamodel.valuestmt.condition.ClassCondition.ClassConditionType;
import io.proleap.cobol.asg.metamodel.valuestmt.condition.CombinableCondition;
import io.proleap.cobol.asg.metamodel.valuestmt.condition.ConditionNameReference;
import io.proleap.cobol.asg.metamodel.valuestmt.condition.SimpleCondition;
import io.proleap.cobol.asg.metamodel.valuestmt.condition.SimpleCondition.SimpleConditionType;
import io.proleap.cobol.asg.metamodel.valuestmt.condition.ConditionNameSubscriptReference;
import io.proleap.cobol.asg.metamodel.valuestmt.relation.Abbreviation;
import io.proleap.cobol.asg.metamodel.valuestmt.relation.ArithmeticComparison;
import io.proleap.cobol.asg.metamodel.valuestmt.relation.CombinedComparison;
import io.proleap.cobol.asg.metamodel.valuestmt.relation.CombinedCondition;
import io.proleap.cobol.asg.metamodel.valuestmt.relation.CombinedCondition.CombinedConditionType;
import io.proleap.cobol.asg.metamodel.valuestmt.relation.RelationalOperator;
import io.proleap.cobol.asg.metamodel.valuestmt.relation.RelationalOperator.RelationalOperatorType;
import io.proleap.cobol.asg.util.ANTLRUtils;
import io.proleap.cobol.commons.datadescription.CobolDataDescriptionEntryService;
import io.proleap.cobol.commons.datadescription.CobolPictureLengthService;
import io.proleap.cobol.commons.datadescription.CobolPictureStringService;
import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.commons.type.CobolTypeService;
import io.proleap.cobol.commons.value.CobolValueService;
import io.proleap.cobol.commons.value.CobolValueStmtService;
import io.proleap.cobol.commons.value.domain.CobolBooleanValue;
import io.proleap.cobol.commons.value.domain.CobolValue;
import io.proleap.cobol.transform.java.expression.JavaArithmeticExpressionClassifier;
import io.proleap.cobol.transform.java.expression.JavaArithmeticExpressionClassifier.JavaArithmeticExpressionTypeEnum;
import io.proleap.cobol.transform.java.expression.JavaExpressionService;
import io.proleap.cobol.transform.java.identifier.JavaIdentifierService;
import io.proleap.cobol.transform.java.identifier.variable.JavaFileDescriptionEntryIdentifierService;
import io.proleap.cobol.transform.java.identifier.variable.JavaVariableIdentifierService;
import io.proleap.cobol.transform.java.util.JavaLiteralUtils;

@Singleton
public class JavaExpressionServiceImpl
implements JavaExpressionService {
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(JavaExpressionServiceImpl.class);
	private static final String DOT = ".";
	@Inject
	private JavaArithmeticExpressionClassifier javaArithmeticExpressionClassifier;
	@Inject
	private JavaFileDescriptionEntryIdentifierService javaFileDescriptionEntryIdentifierService;
	@Inject
	private JavaIdentifierService javaIdentifierService;
	@Inject
	private JavaVariableIdentifierService javaVariableIdentifierService;
	@Inject
	private CobolDataDescriptionEntryService cobolDataDescriptionEntryService;
	@Inject
	private CobolPictureLengthService cobolPictureLengthService;
	@Inject
	private CobolPictureStringService pictureStringService;
	@Inject
	private CobolTypeService cobolTypeService;
	@Inject
	private CobolValueService valueService;
	@Inject
	private CobolValueStmtService valueStmtService;

	private List<DataDescriptionEntryContainer> collectAllDataSections(ProgramUnit pu) {
		FileSection fileSection;
		ArrayList<DataDescriptionEntryContainer> sections = new ArrayList<DataDescriptionEntryContainer>();
		if (pu.getDataDivision() == null) {
			return sections;
		}
		if (pu.getDataDivision().getWorkingStorageSection() != null) {
			sections.add(pu.getDataDivision().getWorkingStorageSection());
		}
		if (pu.getDataDivision().getLinkageSection() != null) {
			sections.add(pu.getDataDivision().getLinkageSection());
		}
		if ((fileSection = pu.getDataDivision().getFileSection()) != null) {
			for (FileDescriptionEntry fde : fileSection.getFileDescriptionEntries()) {
				sections.add(fde);
			}
		}
		return sections;
	}

	private String getFileDescriptionPrefix(DataDescriptionEntry entry) {
		Program program = entry.getProgram();
		if (program == null) {
			return null;
		}
		ASGElementRegistry asgElementRegistry = program.getASGElementRegistry();
		FileDescriptionEntry fde = (FileDescriptionEntry)ANTLRUtils.findParent(FileDescriptionEntry.class, (ParseTree)entry.getCtx(), asgElementRegistry);
		if (fde != null) {
			return this.javaFileDescriptionEntryIdentifierService.mapToIdentifier(fde);
		}
		return null;
	}

	private List<DataDescriptionEntry> findEntriesByNameHyphenInsensitive(DataDescriptionEntryContainer section, String name) {
		List<DataDescriptionEntry> candidates = section.getDataDescriptionEntries(name);
		if (candidates != null && !candidates.isEmpty()) {
			return candidates;
		}
		String normalized = name.replace('_', '-');
		if (!normalized.equals(name) && (candidates = section.getDataDescriptionEntries(normalized)) != null && !candidates.isEmpty()) {
			return candidates;
		}
		String strippedUpper = name.toUpperCase().replace("-", "").replace("_", "");
		ArrayList<DataDescriptionEntry> result = new ArrayList<DataDescriptionEntry>();
		for (DataDescriptionEntry entry : section.getDataDescriptionEntries()) {
			String entryStripped;
			if (entry.getName() == null || !(entryStripped = entry.getName().toUpperCase().replace("-", "").replace("_", "")).equals(strippedUpper)) continue;
			result.add(entry);
		}
		if (!result.isEmpty()) {
			return result;
		}
		String nameUpper = name.toUpperCase();
		if (nameUpper.length() == 10 && nameUpper.matches("[A-Z0-9_-]{5}\\d{5}")) {
			String prefix = nameUpper.substring(0, 5).replace("-", "_");
			int targetSeq = Integer.parseInt(nameUpper.substring(5));
			LinkedHashMap<DataDescriptionEntryGroup, List> byParent = new LinkedHashMap<DataDescriptionEntryGroup, List>();
			for (DataDescriptionEntry entry : section.getDataDescriptionEntries()) {
				String entryNameUpper;
				String entryPrefix;
				if (entry.getName() == null || entry.getName().length() <= 10 || !(entryPrefix = (entryNameUpper = entry.getName().toUpperCase()).length() >= 5 ? entryNameUpper.substring(0, 5).replace("-", "_") : "").equals(prefix)) continue;
				DataDescriptionEntryGroup parent = entry.getParentDataDescriptionEntryGroup();
				byParent.computeIfAbsent(parent, k -> new ArrayList()).add(entry);
			}
			for (List entries : byParent.values()) {
				if (targetSeq > entries.size()) continue;
				result.add((DataDescriptionEntry)entries.get(targetSeq - 1));
			}
		}
		return result;
	}

	/**
	 * Recursively searches for data description entries by name through all nested groups.
	 * This handles fields included via COPY that are registered in their parent group's
	 * symbol table rather than the section's top-level symbol table.
	 */
	private List<DataDescriptionEntry> findEntriesRecursivelyByName(DataDescriptionEntryContainer container, String name) {
		ArrayList<DataDescriptionEntry> results = new ArrayList<DataDescriptionEntry>();
		String nameUpper = name.toUpperCase().replace('_', '-');
		String nameStripped = nameUpper.replace("-", "");
		this.searchEntriesRecursive(container, nameUpper, nameStripped, results);
		return results;
	}

	private void searchEntriesRecursive(DataDescriptionEntryContainer container, String nameUpper, String nameStripped, List<DataDescriptionEntry> results) {
		for (DataDescriptionEntry entry : container.getDataDescriptionEntries()) {
			if (entry.getName() != null) {
				String entryUpper = entry.getName().toUpperCase().replace('_', '-');
				if (entryUpper.equals(nameUpper) || entryUpper.replace("-", "").equals(nameStripped)) {
					results.add(entry);
				}
			}
			// Recurse into groups
			if (entry instanceof DataDescriptionEntryGroup) {
				this.searchGroupEntriesRecursive((DataDescriptionEntryGroup)entry, nameUpper, nameStripped, results);
			}
		}
	}

	private void searchGroupEntriesRecursive(DataDescriptionEntryGroup group, String nameUpper, String nameStripped, List<DataDescriptionEntry> results) {
		for (DataDescriptionEntry entry : group.getDataDescriptionEntries()) {
			if (entry.getName() != null) {
				String entryUpper = entry.getName().toUpperCase().replace('_', '-');
				if (entryUpper.equals(nameUpper) || entryUpper.replace("-", "").equals(nameStripped)) {
					results.add(entry);
				}
			}
			// Recurse into nested groups
			if (entry instanceof DataDescriptionEntryGroup) {
				this.searchGroupEntriesRecursive((DataDescriptionEntryGroup)entry, nameUpper, nameStripped, results);
			}
		}
	}

	protected List<DataDescriptionEntry> collectCallHierarchy(DataDescriptionEntry dataDescriptionEntry) {
		ArrayList<DataDescriptionEntry> result = new ArrayList<DataDescriptionEntry>();
		DataDescriptionEntry currentDataDescriptionEntry = dataDescriptionEntry;
		do {
			// Skip SQLCA group — its fields are emitted at class level, not inside a container
			String entryName = currentDataDescriptionEntry.getName();
			if (entryName == null || !"SQLCA".equalsIgnoreCase(entryName)) {
				result.add(currentDataDescriptionEntry);
			}
		} while ((currentDataDescriptionEntry = currentDataDescriptionEntry.getParentDataDescriptionEntryGroup()) != null);
		Collections.reverse(result);
		return result;
	}

	protected DataDescriptionEntry resolveRedefinesEntry(DataDescriptionEntry entry) {
		// Chase the REDEFINES chain to the ultimate base field
		// (e.g., LkNrRvcDisN -> LkNrRvcDisS -> LkNrRvcDis1)
		DataDescriptionEntry current = entry;
		java.util.Set<String> visited = new java.util.HashSet<>();
		while (current instanceof DataDescriptionEntryGroup) {
			DataDescriptionEntryGroup group = (DataDescriptionEntryGroup)current;
			if (group.getRedefinesClause() == null || group.getRedefinesClause().getRedefinesCall() == null) {
				break;
			}
			String redefinesName = group.getRedefinesClause().getRedefinesCall().getName();
			if (redefinesName == null || !visited.add(redefinesName.toUpperCase())) {
				break; // avoid infinite loop
			}
			DataDescriptionEntry resolved = null;
			DataDescriptionEntryGroup parent = current.getParentDataDescriptionEntryGroup();
			if (parent != null) {
				for (DataDescriptionEntry sibling : parent.getDataDescriptionEntries()) {
					if (redefinesName.equalsIgnoreCase(sibling.getName())) {
						resolved = sibling;
						break;
					}
				}
			}
			if (resolved == null) {
				Program prog = current.getProgram();
				if (prog != null) {
					outer: for (CompilationUnit cu : prog.getCompilationUnits()) {
						for (ProgramUnit pu : cu.getProgramUnits()) {
							if (pu.getDataDivision() == null) continue;
							for (DataDescriptionEntryContainer sec : this.collectAllDataSections(pu)) {
								DataDescriptionEntry found = sec.getDataDescriptionEntry(redefinesName);
								if (found != null) {
									resolved = found;
									break outer;
								}
							}
						}
					}
				}
			}
			if (resolved == null) {
				break;
			}
			current = resolved;
		}
		return current;
	}

	public String mapToCall(Call call) {
		Call.CallType callType = call.getCallType();
		String result;
		switch (callType) {
			case DATA_DESCRIPTION_ENTRY_CALL: {
				DataDescriptionEntryCall dataDescriptionEntryCall = (DataDescriptionEntryCall)call.unwrap();
				result = this.mapToCall(dataDescriptionEntryCall, call);
				break;
			}
			case TABLE_CALL: {
				TableCall tableCall = (TableCall)call.unwrap();
				result = this.mapToCall(tableCall);
				break;
			}
			case SPECIAL_REGISTER_CALL: {
				SpecialRegisterCall specialRegisterCall = (SpecialRegisterCall)call.unwrap();
				result = this.mapToCall(specialRegisterCall);
				break;
			}
			case FUNCTION_CALL: {
				result = this.mapToIntrinsicFunctionCall(call);
				break;
			}
			default: {
				result = this.resolveUndefinedCall(call);
				break;
			}
		}
		result = this.applyContextSubscripts(result, call);
		result = this.applyReferenceModification(result, call);
		return result;
	}

	private String resolveUndefinedCall(Call call) {
		Matcher arithMatcher;
		String resolved;
		Program program;
		ParserRuleContext callCtx = call.getCtx();
		CobolParser.QualifiedDataNameFormat1Context qdf1 = this.findQualifiedDataNameFormat1(callCtx);
		if (qdf1 != null) {
			Program program2;
			String resolved2;
			boolean isVarcharSubfield;
			boolean hasQualifiers = qdf1.qualifiedInData() != null && !qdf1.qualifiedInData().isEmpty();
			String upperCallName = call.getName() != null ? call.getName().toUpperCase() : "";
			boolean bl = isVarcharSubfield = upperCallName.endsWith("-DATA") || upperCallName.endsWith("-LENGTH");
			if ((hasQualifiers || isVarcharSubfield) && (resolved2 = this.buildQualifiedPath(qdf1, program2 = call.getProgram())) != null && !resolved2.equals(this.javaIdentifierService.mapToIdentifier(call.getName()))) {
				return resolved2;
			}
		}
		if ((program = call.getProgram()) != null && call.getName() != null && (resolved = this.resolveFieldByNameViaASG(call.getName(), Collections.emptyList(), program)) != null && (resolved.contains(DOT) || resolved.startsWith("get"))) {
			return resolved;
		}
		String callName = call.getName();
		if (callName != null && (arithMatcher = Pattern.compile("([A-Za-z0-9-]+)([+])([A-Za-z0-9-]+)").matcher(callName)).matches()) {
			String left = arithMatcher.group(1) != null ? arithMatcher.group(1) : arithMatcher.group(4);
			String op = arithMatcher.group(2) != null ? arithMatcher.group(2) : arithMatcher.group(5);
			String right = arithMatcher.group(3) != null ? arithMatcher.group(3) : arithMatcher.group(6);
			return "(" + this.convertRefModTerm(left, program) + " " + op + " " + this.convertRefModTerm(right, program) + ")";
		}
		// Text-based fallback: detect mangled "OF"/"IN" qualifiers in call names
		// e.g., "NOCCURSOFFH0700EVT-INFTEL" -> resolve as NOCCURS OF FH0700EVT-INFTEL
		if (callName != null && program != null) {
			String upperName = callName.toUpperCase();
			// Try "OF" first, then "IN"
			for (String kw : new String[]{"OF", "IN"}) {
				int kwPos = upperName.indexOf(kw);
				while (kwPos > 0) {
					String potentialLeaf = callName.substring(0, kwPos);
					String potentialQualifier = callName.substring(kwPos + kw.length());
					if (!potentialLeaf.isEmpty() && !potentialQualifier.isEmpty()
							&& !potentialLeaf.matches("\\d+") && !potentialQualifier.matches("\\d+")) {
						List<String> qualList = new java.util.ArrayList<>();
						qualList.add(potentialQualifier.toUpperCase().replace('_', '-'));
						String qualResolved = this.resolveFieldByNameViaASG(potentialLeaf, qualList, program);
						if (qualResolved != null && qualResolved.contains(DOT)) {
							return qualResolved;
						}
					}
					kwPos = upperName.indexOf(kw, kwPos + kw.length());
				}
			}
		}
		return this.javaIdentifierService.mapToIdentifier(call.getName());
	}

	private CobolParser.QualifiedDataNameFormat1Context findQualifiedDataNameFormat1(ParserRuleContext ctx) {
		CobolParser.TableCallContext tcCtx;
		if (ctx == null) {
			return null;
		}
		if (ctx instanceof CobolParser.QualifiedDataNameFormat1Context) {
			return (CobolParser.QualifiedDataNameFormat1Context)ctx;
		}
		if (ctx instanceof CobolParser.IdentifierContext) {
			CobolParser.IdentifierContext idCtx = (CobolParser.IdentifierContext)ctx;
			if (idCtx.tableCall() != null && idCtx.tableCall().qualifiedDataName() != null && idCtx.tableCall().qualifiedDataName().qualifiedDataNameFormat1() != null) {
				return idCtx.tableCall().qualifiedDataName().qualifiedDataNameFormat1();
			}
			if (idCtx.qualifiedDataName() != null && idCtx.qualifiedDataName().qualifiedDataNameFormat1() != null) {
				return idCtx.qualifiedDataName().qualifiedDataNameFormat1();
			}
		}
		if (ctx instanceof CobolParser.TableCallContext && (tcCtx = (CobolParser.TableCallContext)ctx).qualifiedDataName() != null && tcCtx.qualifiedDataName().qualifiedDataNameFormat1() != null) {
			return tcCtx.qualifiedDataName().qualifiedDataNameFormat1();
		}
		if (ctx instanceof CobolParser.QualifiedDataNameContext) {
			return ((CobolParser.QualifiedDataNameContext)ctx).qualifiedDataNameFormat1();
		}
		ParserRuleContext parent = ctx.getParent();
		for (int depth = 0; depth < 5 && parent != null; parent = parent.getParent(), ++depth) {
			if (!(parent instanceof CobolParser.IdentifierContext) && !(parent instanceof CobolParser.TableCallContext) && !(parent instanceof CobolParser.QualifiedDataNameContext) && !(parent instanceof CobolParser.QualifiedDataNameFormat1Context)) continue;
			return this.findQualifiedDataNameFormat1(parent);
		}
		return null;
	}

	private String applyReferenceModification(String baseExpr, Call call) {
		// If mapToCall(TableCall) already applied reference modification, skip.
		if (baseExpr.contains("CobolReference.referenceModification(")) {
			return baseExpr;
		}
		Program refModProgram = call.getProgram();
		for (ParserRuleContext ctx = call.getCtx(); ctx != null; ctx = ctx.getParent()) {
			// Stop if we cross a SubscriptContext boundary — the reference modifier
			// at the parent level belongs to the enclosing field, not to this
			// subscript expression (e.g., FIELD(W-NUM)(2:5) — the (2:5) is on FIELD,
			// not on W-NUM).
			if (ctx instanceof CobolParser.SubscriptContext) {
				break;
			}
			CobolParser.ReferenceModifierContext refMod = null;
			if (ctx instanceof CobolParser.IdentifierContext) {
				CobolParser.IdentifierContext identCtx = (CobolParser.IdentifierContext)ctx;
				refMod = identCtx.referenceModifier();
				// When identifier uses the tableCall alternative (subscripted field),
				// referenceModifier is on the TableCallContext child, not on IdentifierContext.
				if (refMod == null && identCtx.tableCall() != null) {
					refMod = identCtx.tableCall().referenceModifier();
				}
				// When the identifier is parsed as qualifiedDataName but the grammar
				// embeds the reference modifier deeper in the tree (e.g., subscripted
				// fields like FIELD OF GROUP(IDX)(pos:len)), search recursively.
				if (refMod == null) {
					refMod = this.findReferenceModifier(identCtx);
				}
			} else if (ctx instanceof CobolParser.TableCallContext) {
				CobolParser.TableCallContext tableCtx = (CobolParser.TableCallContext)ctx;
				refMod = tableCtx.referenceModifier();
			} else {
				continue;
			}
			if (refMod == null) break;
			String posExpr = this.convertArithExprCtxToJavaInt(refMod.characterPosition().arithmeticExpression(), refModProgram);
			if (refMod.length() != null) {
				return "CobolReference.referenceModification(" + baseExpr + ", " + posExpr + ", " + this.convertArithExprCtxToJavaInt(refMod.length().arithmeticExpression(), refModProgram) + ")";
			}
			return "CobolReference.referenceModification(" + baseExpr + ", " + posExpr + ")";
		}
		return baseExpr;
	}

	private CobolParser.ReferenceModifierContext findReferenceModifier(ParseTree tree) {
		if (tree instanceof CobolParser.ReferenceModifierContext) {
			return (CobolParser.ReferenceModifierContext)tree;
		}
		for (int i = 0; i < tree.getChildCount(); ++i) {
			CobolParser.ReferenceModifierContext found = this.findReferenceModifier(tree.getChild(i));
			if (found == null) continue;
			return found;
		}
		return null;
	}

	private String mapToIntrinsicFunctionCall(Call call) {
		Call unwrapped;
		CobolParser.FunctionCallContext funcCtx = null;
		for (ParserRuleContext ctx = call.getCtx(); ctx != null; ctx = ctx.getParent()) {
			if (!(ctx instanceof CobolParser.FunctionCallContext)) continue;
			funcCtx = (CobolParser.FunctionCallContext)ctx;
			break;
		}
		if (funcCtx == null && (unwrapped = call.unwrap()).getCtx() instanceof CobolParser.FunctionCallContext) {
			funcCtx = (CobolParser.FunctionCallContext)unwrapped.getCtx();
		}
		if (funcCtx == null) {
			return this.javaIdentifierService.mapToIdentifier(call.getName());
		}
		String cobolName = call.getName();
		String javaMethodName = this.cobolFunctionNameToJava(cobolName);
		StringBuilder sb = new StringBuilder();
		sb.append("CobolIntrinsic.").append(javaMethodName).append("(");
		List<CobolParser.ArgumentContext> args = funcCtx.argument();
		if (args != null) {
			int emittedCount = 0;
			for (int i = 0; i < args.size(); ++i) {
				CobolParser.ArgumentContext nextArg;
				CobolParser.ArgumentContext arg = args.get(i);
				if (i + 1 < args.size() && this.isQualifiedArgWithOccursQualifier(arg, call.getProgram()) && ((nextArg = args.get(i + 1)).arithmeticExpression() != null || nextArg.identifier() != null || nextArg.integerLiteral() != null)) {
					if (emittedCount > 0) {
						sb.append(", ");
					}
					ArrayList<CobolParser.ArgumentContext> subArgs = new ArrayList<CobolParser.ArgumentContext>();
					subArgs.add(nextArg);
					sb.append(this.mapIntrinsicArgumentWithSubscripts(arg, subArgs, call.getProgram()));
					++emittedCount;
					++i;
					continue;
				}
				if (emittedCount > 0) {
					sb.append(", ");
				}
				sb.append(this.mapIntrinsicArgument(arg, call.getProgram()));
				++emittedCount;
			}
		}
		sb.append(")");
		return sb.toString();
	}

	private String buildQualifiedPath(CobolParser.QualifiedDataNameFormat1Context qdf1, Program program) {
		Object topQualifier;
		String topResolvedPath;
		String dataName = qdf1.dataName().getText();
		String mappedName = this.javaIdentifierService.mapToIdentifier(dataName);
		if (qdf1.qualifiedInData() == null || qdf1.qualifiedInData().isEmpty()) {
			String resolved;
			String upperName = dataName.toUpperCase();
			if (upperName.endsWith("-DATA") || upperName.endsWith("-LENGTH")) {
				// First check if the full name (e.g., W-DATA) resolves directly as a known field.
				// Only apply the VARCHAR parent-child heuristic if the full name does NOT resolve on its own.
				String directResolved = program != null
						? this.resolveFieldByNameViaASG(dataName, Collections.emptyList(), program)
						: null;
				if (directResolved != null) {
					// The full name resolves directly — use it, don't split into parent + child
					return directResolved.contains(DOT) ? directResolved : mappedName;
				}
				String parentPath;
				String parentName = dataName.substring(0, dataName.length() - (upperName.endsWith("-DATA") ? 5 : 7));
				if (program != null && (parentPath = this.resolveFieldByNameViaASG(parentName, Collections.emptyList(), program)) != null) {
					DataDescriptionEntry parentEntry = this.findFieldByNameViaASG(parentName, Collections.emptyList(), program);
					if (parentEntry != null && parentEntry.getDataDescriptionEntryType() == DataDescriptionEntry.DataDescriptionEntryType.GROUP && !((DataDescriptionEntryGroup)parentEntry).getDataDescriptionEntries().isEmpty()) {
						String suffix = upperName.endsWith("-DATA") ? "-DATA" : "-LENGTH";
						String childMapped = this.findChildMappedName((DataDescriptionEntryGroup)parentEntry, suffix);
						return parentPath + DOT + (childMapped != null ? childMapped : mappedName);
					}
					return parentPath;
				}
				return this.javaIdentifierService.mapToIdentifier(parentName) + DOT + mappedName;
			}
			if (program != null && (resolved = this.resolveFieldByNameViaASG(dataName, Collections.emptyList(), program)) != null && resolved.contains(DOT)) {
				return resolved;
			}
			return mappedName;
		}
		if (program != null) {
			String resolved = this.resolveQualifiedPathViaASG(qdf1, program);
			if (resolved != null) {
				return resolved;
			}
			String upperName = dataName.toUpperCase();
			if (upperName.endsWith("-DATA") || upperName.endsWith("-LENGTH")) {
				String varcharParentName = dataName.substring(0, dataName.length() - (upperName.endsWith("-DATA") ? 5 : 7));
				ArrayList<String> qualifierNames = new ArrayList<String>();
				ArrayList<CobolParser.SubscriptContext> varcharSubscripts = new ArrayList<CobolParser.SubscriptContext>();
				for (CobolParser.QualifiedInDataContext qid : qdf1.qualifiedInData()) {
					if (qid.inData() != null && qid.inData().dataName() != null) {
						qualifierNames.add(qid.inData().dataName().getText().toUpperCase().replace('_', '-'));
						continue;
					}
					if (qid.inTable() == null || qid.inTable().tableCall() == null) continue;
					CobolParser.TableCallContext tc = qid.inTable().tableCall();
					if (tc.qualifiedDataName() != null) {
						qualifierNames.add(tc.qualifiedDataName().getText().toUpperCase().replace('_', '-'));
					}
					if (tc.subscript() == null) continue;
					varcharSubscripts.addAll(tc.subscript());
				}
				// When the VARCHAR parent name matches the first qualifier (e.g.,
				// CODPOS-DATA OF CODPOS OF FILE500401 → parent = CODPOS, quals = [CODPOS, FILE500401]),
				// remove it to avoid looking for a CODPOS under another CODPOS.
				String varcharParentUpper = varcharParentName.toUpperCase().replace('_', '-');
				ArrayList<String> effectiveQualifiers = qualifierNames;
				if (!qualifierNames.isEmpty() && qualifierNames.get(0).equals(varcharParentUpper)) {
					effectiveQualifiers = new ArrayList<>(qualifierNames.subList(1, qualifierNames.size()));
				}
				String parentPath = this.resolveFieldByNameViaASGWithSubscripts(varcharParentName, effectiveQualifiers, varcharSubscripts, program);
				if (parentPath != null) {
					DataDescriptionEntry parentEntry = this.findFieldByNameViaASG(varcharParentName, effectiveQualifiers, program);
					if (parentEntry != null && parentEntry.getDataDescriptionEntryType() == DataDescriptionEntry.DataDescriptionEntryType.GROUP && !((DataDescriptionEntryGroup)parentEntry).getDataDescriptionEntries().isEmpty()) {
						String suffix = upperName.endsWith("-DATA") ? "-DATA" : "-LENGTH";
						String childMapped = this.findChildMappedName((DataDescriptionEntryGroup)parentEntry, suffix);
						return parentPath + DOT + (childMapped != null ? childMapped : mappedName);
					}
					return parentPath;
				}
			}
		}
		StringBuilder path = new StringBuilder();
		ArrayList<String> fallbackQualifierNames = new ArrayList<String>();
		// Map qualifier name (lowercase) -> subscript contexts for that qualifier
		java.util.Map<String, List<CobolParser.SubscriptContext>> qualifierSubscriptMap = new java.util.LinkedHashMap<>();
		// Collect subscripts from implicit qualifiers (e.g., SQLCA(3)) to transfer to leaf field
		ArrayList<CobolParser.SubscriptContext> implicitQualifierSubscripts = new ArrayList<CobolParser.SubscriptContext>();
		for (int i = qdf1.qualifiedInData().size() - 1; i >= 0; --i) {
			CobolParser.QualifiedInDataContext qid = qdf1.qualifiedInData().get(i);
			if (qid.inData() != null && qid.inData().dataName() != null) {
				fallbackQualifierNames.add(qid.inData().dataName().getText());
				continue;
			}
			if (qid.inTable() == null || qid.inTable().tableCall() == null || qid.inTable().tableCall().qualifiedDataName() == null) continue;
			String qualText = qid.inTable().tableCall().qualifiedDataName().getText();
			fallbackQualifierNames.add(qualText);
			// Capture subscripts for this qualifier (both implicit and non-implicit)
			if (qid.inTable().tableCall().subscript() != null && !qid.inTable().tableCall().subscript().isEmpty()) {
				if (IMPLICIT_QUALIFIER_NAMES.contains(qualText.toUpperCase())) {
					implicitQualifierSubscripts.addAll(qid.inTable().tableCall().subscript());
				} else {
					String qualKey = this.javaIdentifierService.mapToIdentifier(qualText);
					qualifierSubscriptMap.put(qualKey, qid.inTable().tableCall().subscript());
				}
			}
		}
		boolean topResolved = false;
		if (program != null && !fallbackQualifierNames.isEmpty() && (topResolvedPath = this.resolveFieldByNameViaASG((String)(topQualifier = (String)fallbackQualifierNames.get(0)), Collections.emptyList(), program)) != null && topResolvedPath.contains(DOT)) {
			// When the top qualifier has subscripts and resolves to a multi-segment path
			// (e.g., CpyTB2005 -> wkrspint.wklstpag.cpytb2005), we must apply the subscript
			// at the OCCURS level within the hierarchy (e.g., wklstpag), not at the end of
			// the full resolved path. Use resolveFieldByNameViaASGWithSubscripts which walks
			// the hierarchy and places safeGet at the correct OCCURS parent.
			String topQualKey = this.javaIdentifierService.mapToIdentifier((String)topQualifier);
			List<CobolParser.SubscriptContext> topQualSubs = qualifierSubscriptMap.get(topQualKey);
			if (topQualSubs != null && !topQualSubs.isEmpty()) {
				String resolvedWithSubs = this.resolveFieldByNameViaASGWithSubscripts(
						(String)topQualifier, Collections.emptyList(), topQualSubs, program);
				if (resolvedWithSubs != null && resolvedWithSubs.contains(DOT)) {
					path.append(resolvedWithSubs);
				} else {
					// Fallback: use the old approach if ASG-with-subscripts fails
					path.append(topResolvedPath);
					this.applyQualifierSubscriptsToPath(path, topQualKey, qualifierSubscriptMap, program);
				}
			} else {
				path.append(topResolvedPath);
			}
			path.append(DOT);
			topResolved = true;
			for (int qi = 1; qi < fallbackQualifierNames.size(); ++qi) {
				String qualId = this.javaIdentifierService.mapToIdentifier((String)fallbackQualifierNames.get(qi));
				path.append(qualId);
				this.applyQualifierSubscriptsToPath(path, qualId, qualifierSubscriptMap, program);
				path.append(DOT);
			}
		}
		if (!topResolved) {
			// When a qualifier is a copy-book-derived name (e.g., CpyTR5001, CpyTS0040)
			// that doesn't resolve to an actual data item, try to resolve the leaf field
			// directly via ASG with the copy-book qualifier's subscripts.
			boolean hasCpyQualifier = false;
			ArrayList<CobolParser.SubscriptContext> cpySubscripts = new ArrayList<CobolParser.SubscriptContext>();
			for (String qualName : fallbackQualifierNames) {
				if (qualName.toUpperCase().startsWith("CPY") && program != null) {
					String cpyResolved = this.resolveFieldByNameViaASG(qualName, Collections.emptyList(), program);
					if (cpyResolved == null || !cpyResolved.contains(DOT)) {
						hasCpyQualifier = true;
						String cpyQualKey = this.javaIdentifierService.mapToIdentifier(qualName);
						List<CobolParser.SubscriptContext> cpySubs = qualifierSubscriptMap.get(cpyQualKey);
						if (cpySubs != null) {
							cpySubscripts.addAll(cpySubs);
						}
					}
				}
			}
			if (hasCpyQualifier && program != null) {
				// Try resolving the leaf field directly, with subscripts from the Cpy qualifier
				ArrayList<String> emptyQuals = new ArrayList<String>();
				String directResolved = this.resolveFieldByNameViaASGWithSubscripts(dataName, emptyQuals, cpySubscripts, program);
				if (directResolved != null && directResolved.contains(DOT)) {
					return directResolved;
				}
			}
			// Fall through to text-based path building (uses unresolvable qualifiers as-is)
			for (String qualName : fallbackQualifierNames) {
				// Skip implicit qualifiers (e.g., SQLCA) whose fields are emitted flat at class level
				if (IMPLICIT_QUALIFIER_NAMES.contains(qualName.toUpperCase())) {
					continue;
				}
				String qualId = this.javaIdentifierService.mapToIdentifier(qualName);
				path.append(qualId);
				this.applyQualifierSubscriptsToPath(path, qualId, qualifierSubscriptMap, program);
				path.append(DOT);
			}
		}
		// Check if the leaf field is an elementary REDEFINES (e.g., NUMCLIs REDEFINES NUMCLI PIC +9(10)).
		// Such fields are generated as getter/setter methods, not direct fields.
		// When the call is UNDEFINED (e.g., qualifier not resolvable), we must use the getter.
		boolean leafIsRedefines = false;
		if (program != null) {
			ArrayList<String> leafQualNames = new ArrayList<String>();
			for (String q : fallbackQualifierNames) {
				leafQualNames.add(q.toUpperCase().replace('_', '-'));
			}
			DataDescriptionEntry leafEntry = this.findFieldByNameViaASG(dataName, leafQualNames, program);
			if (leafEntry == null) {
				// Try without qualifiers (field might be unique in program)
				leafEntry = this.findFieldByNameViaASG(dataName, Collections.emptyList(), program);
			}
			if (leafEntry instanceof DataDescriptionEntryGroup) {
				DataDescriptionEntryGroup leafGroup = (DataDescriptionEntryGroup)leafEntry;
				if (leafGroup.getRedefinesClause() != null && leafGroup.getRedefinesClause().getRedefinesCall() != null) {
					// Check that it's an elementary REDEFINES (no children other than conditions)
					boolean hasNonCondChildren = leafGroup.getDataDescriptionEntries().stream()
							.anyMatch(e -> e.getDataDescriptionEntryType() != DataDescriptionEntry.DataDescriptionEntryType.CONDITION);
					if (!hasNonCondChildren) {
						leafIsRedefines = true;
					}
				}
			}
		}
		if (leafIsRedefines) {
			String getterName = "get" + Character.toUpperCase(mappedName.charAt(0)) + mappedName.substring(1);
			path.append(getterName).append("()");
		} else {
			path.append(mappedName);
		}
		// Apply subscripts transferred from implicit qualifiers (e.g., SQLERRD OF SQLCA(3) → sqlerrd[2])
		if (!implicitQualifierSubscripts.isEmpty()) {
			for (CobolParser.SubscriptContext subCtx : implicitQualifierSubscripts) {
				String subText = subCtx.getText().trim();
				try {
					int idx = Integer.parseInt(subText);
					path.append("[").append(idx - 1).append("]");
				} catch (NumberFormatException e) {
					String arithResult = this.convertRefModExpr(subText, program);
					if (arithResult.startsWith("(")) {
						path.append("[").append(arithResult).append(" - 1]");
					} else {
						path.append("[").append(this.javaIdentifierService.mapToIdentifier(subText)).append(".intValue() - 1]");
					}
				}
			}
		}
		String upperNameFallback = dataName.toUpperCase();
		if (upperNameFallback.endsWith("-DATA") || upperNameFallback.endsWith("-LENGTH")) {
			String parentName = dataName.substring(0, dataName.length() - (upperNameFallback.endsWith("-DATA") ? 5 : 7));
			String parentMapped = this.javaIdentifierService.mapToIdentifier(parentName);
			String pathPrefix = path.substring(0, path.length() - mappedName.length());
			// If the path already ends with the parent name as a qualifier (e.g., ...codpos.codpos_data),
			// do NOT insert the parent again — it would duplicate (e.g., ...codpos.codpos.codpos_data).
			if (pathPrefix.endsWith(parentMapped + DOT)) {
				return path.toString();
			}
			return pathPrefix + parentMapped + DOT + mappedName;
		}
		return path.toString();
	}

	private String resolveQualifiedPathViaASG(CobolParser.QualifiedDataNameFormat1Context qdf1, Program program) {
		String leafName = qdf1.dataName().getText();
		ArrayList<String> qualifierNames = new ArrayList<String>();
		ArrayList<CobolParser.SubscriptContext> collectedSubscripts = new ArrayList<CobolParser.SubscriptContext>();
		for (CobolParser.QualifiedInDataContext qid : qdf1.qualifiedInData()) {
			if (qid.inData() != null && qid.inData().dataName() != null) {
				qualifierNames.add(qid.inData().dataName().getText().toUpperCase().replace('_', '-'));
				continue;
			}
			if (qid.inTable() == null || qid.inTable().tableCall() == null) continue;
			CobolParser.TableCallContext tc = qid.inTable().tableCall();
			if (tc.qualifiedDataName() != null) {
				qualifierNames.add(tc.qualifiedDataName().getText().toUpperCase().replace('_', '-'));
			}
			if (tc.subscript() == null) continue;
			collectedSubscripts.addAll(tc.subscript());
		}
		for (CompilationUnit cu : program.getCompilationUnits()) {
			for (ProgramUnit pu : cu.getProgramUnits()) {
				if (pu.getDataDivision() == null) continue;
				for (DataDescriptionEntryContainer section : this.collectAllDataSections(pu)) {
					List<DataDescriptionEntry> candidates = this.findEntriesByNameHyphenInsensitive(section, leafName);
					if (candidates == null || candidates.isEmpty()) {
						candidates = this.findEntriesRecursivelyByName(section, leafName);
					}
					if (candidates == null || candidates.isEmpty()) continue;
					for (DataDescriptionEntry candidate : candidates) {
						if (!this.matchesQualifiers(candidate, qualifierNames)) continue;
						List<DataDescriptionEntry> hierarchy = this.collectCallHierarchy(candidate);
						StringBuilder result = new StringBuilder();
						boolean isFirst = true;
						if (section instanceof FileDescriptionEntry) {
							result.append(this.javaFileDescriptionEntryIdentifierService.mapToIdentifier((FileDescriptionEntry)section));
							isFirst = false;
						}
						Iterator subIter = collectedSubscripts.iterator();
						for (int hIdx = 0; hIdx < hierarchy.size(); ++hIdx) {
							DataDescriptionEntry entry = hierarchy.get(hIdx);
							DataDescriptionEntryGroup grp;
							int maxOccurs;
							if (!isFirst) {
								result.append(DOT);
							}
							// If the leaf entry is an elementary REDEFINES (no children except 88s),
							// use getter access (e.g., getNumclis()) instead of direct field (numclis).
							if (hIdx == hierarchy.size() - 1 && entry instanceof DataDescriptionEntryGroup) {
								DataDescriptionEntryGroup leafGrp = (DataDescriptionEntryGroup)entry;
								if (leafGrp.getRedefinesClause() != null && leafGrp.getRedefinesClause().getRedefinesCall() != null) {
									boolean hasNonCondChildren = leafGrp.getDataDescriptionEntries().stream()
											.anyMatch(e -> e.getDataDescriptionEntryType() != DataDescriptionEntry.DataDescriptionEntryType.CONDITION);
									if (!hasNonCondChildren) {
										String fieldId = this.javaVariableIdentifierService.mapToIdentifier(entry);
										result.append("get").append(Character.toUpperCase(fieldId.charAt(0))).append(fieldId.substring(1)).append("()");
										isFirst = false;
										continue;
									}
								}
							}
							result.append(this.javaVariableIdentifierService.mapToIdentifier(entry));
							if (DataDescriptionEntry.DataDescriptionEntryType.GROUP.equals(entry.getDataDescriptionEntryType()) && (maxOccurs = this.pictureStringService.getMaxOccurs(grp = (DataDescriptionEntryGroup)entry)) > 1 && subIter.hasNext()) {
								boolean isGroupWithChildren = this.cobolDataDescriptionEntryService.hasChildren((DataDescriptionEntry)grp);
								CobolParser.SubscriptContext subCtx = (CobolParser.SubscriptContext)((Object)subIter.next());
								String subText = subCtx.getText().trim();
								if (isGroupWithChildren) {
									// Use CobolConstants.safeGet for OCCURS access - COBOL allows
									// out-of-bounds access (reads adjacent memory), Java List.get() throws.
									// Capture the field access built so far and wrap with safeGet.
									String fieldAccess = result.toString();
									result.setLength(0);
									result.append("io.proleap.cobol.runtime.CobolConstants.safeGet(");
									result.append(fieldAccess);
									result.append(", ");
								} else {
									result.append("[");
								}
								try {
									int idx = Integer.parseInt(subText);
									result.append(idx - 1);
								}
								catch (NumberFormatException e) {
									// Check if subscript is a qualified data name (e.g., NOCCURS OF FH0700EVT-INFTEL)
									// and resolve through ASG instead of using concatenated getText()
									String subExpr = null;
									// First try: parse tree-based resolution via qualifiedDataName child
									if (subCtx.qualifiedDataName() != null && program != null) {
										CobolParser.QualifiedDataNameContext qdn = subCtx.qualifiedDataName();
										String subLeafName = null;
										List<String> qualNames = new java.util.ArrayList<>();
										if (qdn.qualifiedDataNameFormat1() != null) {
											CobolParser.QualifiedDataNameFormat1Context qdn1 = qdn.qualifiedDataNameFormat1();
											subLeafName = qdn1.dataName() != null ? qdn1.dataName().getText().trim() : null;
											if (qdn1.qualifiedInData() != null) {
												for (CobolParser.QualifiedInDataContext qid : qdn1.qualifiedInData()) {
													if (qid.inData() != null && qid.inData().dataName() != null) {
														qualNames.add(qid.inData().dataName().getText().trim().toUpperCase().replace('_', '-'));
													}
												}
											}
										}
										if (subLeafName != null) {
											subExpr = this.resolveFieldByNameViaASG(subLeafName, qualNames, program);
										}
									}
									// Second try: text-based fallback for mangled "OF"/"IN" qualified names
									// getText() concatenates "NOCCURS OF FH0700EVT-INFTEL" -> "NOCCURSOFFH0700EVT-INFTEL"
									if (subExpr == null && program != null) {
										String upperSub = subText.toUpperCase();
										// Check for "OF" or "IN" embedded in the name (case-insensitive)
										int ofPos = upperSub.indexOf("OF");
										int inPos = upperSub.indexOf("IN");
										int splitPos = -1;
										String keyword = null;
										if (ofPos > 0) { splitPos = ofPos; keyword = "OF"; }
										else if (inPos > 0) { splitPos = inPos; keyword = "IN"; }
										if (splitPos > 0) {
											String potentialLeaf = subText.substring(0, splitPos);
											String potentialQualifier = subText.substring(splitPos + keyword.length());
											// Verify both parts look like valid COBOL identifiers (not empty, no digits only)
											if (!potentialLeaf.isEmpty() && !potentialQualifier.isEmpty()
													&& !potentialLeaf.matches("\\d+") && !potentialQualifier.matches("\\d+")) {
												List<String> tQual = new java.util.ArrayList<>();
												tQual.add(potentialQualifier.toUpperCase().replace('_', '-'));
												String resolved = this.resolveFieldByNameViaASG(potentialLeaf, tQual, program);
												if (resolved != null && resolved.contains(DOT)) {
													subExpr = resolved;
												}
											}
										}
									}
									if (subExpr == null) {
										String arithResult = this.convertRefModExpr(subText, program);
										boolean isArith = arithResult.startsWith("(");
										if (isArith) {
											result.append(arithResult);
											result.append(" - 1");
										} else {
											if (program != null) {
												subExpr = this.resolveFieldByNameViaASG(subText, Collections.emptyList(), program);
											}
											if (subExpr != null && subExpr.contains(DOT)) {
												result.append(subExpr);
											} else {
												result.append(this.javaIdentifierService.mapToIdentifier(subText));
											}
											result.append(".intValue() - 1");
										}
									} else {
										result.append(subExpr);
										result.append(".intValue() - 1");
									}
								}
								if (isGroupWithChildren) {
									result.append(")");
								} else {
									result.append("]");
								}
							}
							isFirst = false;
						}
						return result.toString();
					}
				}
			}
		}
		// Fallback: if qualifiers contain a copy-book-derived name (e.g., CpyTR2001, CpyTS0040)
		// that is NOT an actual data item, retry without that qualifier but transfer its subscripts
		// to the nearest OCCURS parent of the leaf field.
		if (!qualifierNames.isEmpty() && !collectedSubscripts.isEmpty()) {
			// Build a list of non-Cpy qualifiers to use as fallback
			ArrayList<String> nonCpyQualifiers = new ArrayList<String>();
			boolean hasCopyBookQualifier = false;
			for (String q : qualifierNames) {
				if (q.toUpperCase().startsWith("CPY")) {
					hasCopyBookQualifier = true;
				} else {
					nonCpyQualifiers.add(q);
				}
			}
			if (hasCopyBookQualifier) {
				// Try resolving the leaf field without the copy-book qualifier but with any remaining qualifiers
				String resolved = this.resolveFieldByNameViaASGWithSubscripts(leafName, nonCpyQualifiers, collectedSubscripts, program);
				if (resolved != null && resolved.contains(DOT)) {
					return resolved;
				}
			}
		}
		return null;
	}

	private String resolveFieldByNameViaASG(String fieldName, List<String> qualifierNames, Program program) {
		if (fieldName != null && fieldName.toUpperCase().contains("STATUS-CUR")) {
		}
		for (CompilationUnit cu : program.getCompilationUnits()) {
			for (ProgramUnit pu : cu.getProgramUnits()) {
				if (pu.getDataDivision() == null) continue;
				for (DataDescriptionEntryContainer section : this.collectAllDataSections(pu)) {
					List<DataDescriptionEntry> candidates = this.findEntriesByNameHyphenInsensitive(section, fieldName);
					if (candidates == null || candidates.isEmpty()) {
						candidates = this.findEntriesRecursivelyByName(section, fieldName);
					}
					if (candidates == null || candidates.isEmpty()) continue;
					for (DataDescriptionEntry candidate : candidates) {
						if (!this.matchesQualifiers(candidate, qualifierNames)) continue;
						List<DataDescriptionEntry> hierarchy = this.collectCallHierarchy(candidate);
						StringBuilder result = new StringBuilder();
						boolean isFirst = true;
						if (section instanceof FileDescriptionEntry) {
							result.append(this.javaFileDescriptionEntryIdentifierService.mapToIdentifier((FileDescriptionEntry)section));
							isFirst = false;
						}
						for (int hi = 0; hi < hierarchy.size(); ++hi) {
							DataDescriptionEntry entry = hierarchy.get(hi);
							if (entry instanceof DataDescriptionEntryGroup && this.isGroupOverElementaryRedefines((DataDescriptionEntryGroup)entry) && hi + 1 < hierarchy.size()) {
								int ci;
								ArrayList<DataDescriptionEntry> consumed = new ArrayList<DataDescriptionEntry>();
								for (ci = hi + 1; ci < hierarchy.size(); ++ci) {
									consumed.add(hierarchy.get(ci));
									if (!this.isNonTrivialGroup(hierarchy.get(ci)) || ci + 1 >= hierarchy.size()) break;
								}
								String qualifiedCapChildId = this.buildQualifiedChildAccessorName((DataDescriptionEntryGroup)entry, consumed);
								if (!isFirst) {
									result.append(DOT);
								}
								result.append("get").append(qualifiedCapChildId).append("()");
								hi = ci;
								isFirst = false;
								continue;
							}
							if (!isFirst) {
								result.append(DOT);
							}
							// If the leaf entry is an elementary REDEFINES (no children except 88s),
							// use getter access (e.g., getNumclis()) instead of direct field (numclis).
							if (hi == hierarchy.size() - 1 && entry instanceof DataDescriptionEntryGroup) {
								DataDescriptionEntryGroup leafGrp = (DataDescriptionEntryGroup)entry;
								if (leafGrp.getRedefinesClause() != null && leafGrp.getRedefinesClause().getRedefinesCall() != null) {
									boolean hasNonCondChildren = leafGrp.getDataDescriptionEntries().stream()
											.anyMatch(e -> e.getDataDescriptionEntryType() != DataDescriptionEntry.DataDescriptionEntryType.CONDITION);
									if (!hasNonCondChildren) {
										String fieldId = this.javaVariableIdentifierService.mapToIdentifier(entry);
										result.append("get").append(Character.toUpperCase(fieldId.charAt(0))).append(fieldId.substring(1)).append("()");
										isFirst = false;
										continue;
									}
								}
							}
							result.append(this.javaVariableIdentifierService.mapToIdentifier(entry));
							isFirst = false;
						}
						return result.toString();
					}
				}
			}
		}
		return null;
	}

	private String resolveFieldByNameViaASGWithSubscripts(String fieldName, List<String> qualifierNames, List<CobolParser.SubscriptContext> subscripts, Program program) {
		if (fieldName != null && fieldName.toUpperCase().contains("STATUS-CUR")) {
		}
		for (CompilationUnit cu : program.getCompilationUnits()) {
			for (ProgramUnit pu : cu.getProgramUnits()) {
				if (pu.getDataDivision() == null) continue;
				for (DataDescriptionEntryContainer section : this.collectAllDataSections(pu)) {
					List<DataDescriptionEntry> candidates = this.findEntriesByNameHyphenInsensitive(section, fieldName);
					if (candidates == null || candidates.isEmpty()) {
						// Fallback: search recursively through all nested groups
						candidates = this.findEntriesRecursivelyByName(section, fieldName);
					}
					if (candidates == null || candidates.isEmpty()) continue;
					for (DataDescriptionEntry candidate : candidates) {
						if (!this.matchesQualifiers(candidate, qualifierNames)) continue;
						List<DataDescriptionEntry> hierarchy = this.collectCallHierarchy(candidate);
						StringBuilder result = new StringBuilder();
						boolean isFirst = true;
						if (section instanceof FileDescriptionEntry) {
							result.append(this.javaFileDescriptionEntryIdentifierService.mapToIdentifier((FileDescriptionEntry)section));
							isFirst = false;
						}
						Iterator<CobolParser.SubscriptContext> subIter = subscripts.iterator();
						for (int hi = 0; hi < hierarchy.size(); ++hi) {
							DataDescriptionEntryGroup grp;
							int maxOccurs;
							DataDescriptionEntry entry = hierarchy.get(hi);
							if (entry instanceof DataDescriptionEntryGroup && this.isGroupOverElementaryRedefines((DataDescriptionEntryGroup)entry) && hi + 1 < hierarchy.size()) {
								int ci;
								ArrayList<DataDescriptionEntry> consumed = new ArrayList<DataDescriptionEntry>();
								for (ci = hi + 1; ci < hierarchy.size(); ++ci) {
									consumed.add(hierarchy.get(ci));
									if (!this.isNonTrivialGroup(hierarchy.get(ci)) || ci + 1 >= hierarchy.size()) break;
								}
								String qualifiedCapChildId = this.buildQualifiedChildAccessorName((DataDescriptionEntryGroup)entry, consumed);
								if (!isFirst) {
									result.append(DOT);
								}
								result.append("get").append(qualifiedCapChildId).append("()");
								hi = ci;
								isFirst = false;
								continue;
							}
							if (!isFirst) {
								result.append(DOT);
							}
							// If the leaf entry is an elementary REDEFINES (no children except 88s),
							// use getter access (e.g., getNumclis()) instead of direct field (numclis).
							if (hi == hierarchy.size() - 1 && entry instanceof DataDescriptionEntryGroup) {
								DataDescriptionEntryGroup leafGrp = (DataDescriptionEntryGroup)entry;
								if (leafGrp.getRedefinesClause() != null && leafGrp.getRedefinesClause().getRedefinesCall() != null) {
									boolean hasNonCondChildren = leafGrp.getDataDescriptionEntries().stream()
											.anyMatch(e -> e.getDataDescriptionEntryType() != DataDescriptionEntry.DataDescriptionEntryType.CONDITION);
									if (!hasNonCondChildren) {
										String fieldId = this.javaVariableIdentifierService.mapToIdentifier(entry);
										result.append("get").append(Character.toUpperCase(fieldId.charAt(0))).append(fieldId.substring(1)).append("()");
										isFirst = false;
										continue;
									}
								}
							}
							result.append(this.javaVariableIdentifierService.mapToIdentifier(entry));
							if (DataDescriptionEntry.DataDescriptionEntryType.GROUP.equals(entry.getDataDescriptionEntryType()) && (maxOccurs = this.pictureStringService.getMaxOccurs(grp = (DataDescriptionEntryGroup)entry)) > 1 && subIter.hasNext()) {
								boolean isGroupWithChildren = this.cobolDataDescriptionEntryService.hasChildren((DataDescriptionEntry)grp);
								CobolParser.SubscriptContext subCtx = subIter.next();
								String subText = subCtx.getText().trim();
								if (isGroupWithChildren) {
									String fieldAccess = result.toString();
									result.setLength(0);
									result.append("io.proleap.cobol.runtime.CobolConstants.safeGet(");
									result.append(fieldAccess);
									result.append(", ");
								} else {
									result.append("[");
								}
								try {
									int idx = Integer.parseInt(subText);
									result.append(idx - 1);
								}
								catch (NumberFormatException e) {
									// Check if subscript is a qualified data name (e.g., NOCCURS OF FH0700EVT-INFTEL)
									String qualSubExpr = null;
									if (subCtx.qualifiedDataName() != null && program != null) {
										CobolParser.QualifiedDataNameContext qdn = subCtx.qualifiedDataName();
										String leafN = null;
										List<String> qualN = new java.util.ArrayList<>();
										if (qdn.qualifiedDataNameFormat1() != null) {
											CobolParser.QualifiedDataNameFormat1Context qdn1 = qdn.qualifiedDataNameFormat1();
											leafN = qdn1.dataName() != null ? qdn1.dataName().getText().trim() : null;
											if (qdn1.qualifiedInData() != null) {
												for (CobolParser.QualifiedInDataContext qid : qdn1.qualifiedInData()) {
													if (qid.inData() != null && qid.inData().dataName() != null) {
														qualN.add(qid.inData().dataName().getText().trim().toUpperCase().replace('_', '-'));
													}
												}
											}
										}
										if (leafN != null) {
											qualSubExpr = this.resolveFieldByNameViaASG(leafN, qualN, program);
										}
									}
									// Text-based fallback for mangled OF/IN in getText()
									if (qualSubExpr == null && program != null) {
										String upperSub = subText.toUpperCase();
										int ofPos2 = upperSub.indexOf("OF");
										int inPos2 = upperSub.indexOf("IN");
										int splitPos2 = -1;
										String kw2 = null;
										if (ofPos2 > 0) { splitPos2 = ofPos2; kw2 = "OF"; }
										else if (inPos2 > 0) { splitPos2 = inPos2; kw2 = "IN"; }
										if (splitPos2 > 0) {
											String pLeaf = subText.substring(0, splitPos2);
											String pQual = subText.substring(splitPos2 + kw2.length());
											if (!pLeaf.isEmpty() && !pQual.isEmpty()
													&& !pLeaf.matches("\\d+") && !pQual.matches("\\d+")) {
												List<String> tQ = new java.util.ArrayList<>();
												tQ.add(pQual.toUpperCase().replace('_', '-'));
												String r = this.resolveFieldByNameViaASG(pLeaf, tQ, program);
												if (r != null && r.contains(DOT)) {
													qualSubExpr = r;
												}
											}
										}
									}
									if (qualSubExpr != null) {
										result.append(qualSubExpr);
										result.append(".intValue() - 1");
									} else {
										String arithResult = this.convertRefModExpr(subText, program);
										boolean isArith = arithResult.startsWith("(");
										if (isArith) {
											result.append(arithResult);
											result.append(" - 1");
										} else {
											result.append(this.javaIdentifierService.mapToIdentifier(subText));
											result.append(".intValue() - 1");
										}
									}
								}
								if (isGroupWithChildren) {
									result.append(")");
								} else {
									result.append("]");
								}
							}
							isFirst = false;
						}
						return result.toString();
					}
				}
			}
		}
		return null;
	}

	private DataDescriptionEntry findFieldByNameViaASG(String fieldName, List<String> qualifierNames, Program program) {
		for (CompilationUnit cu : program.getCompilationUnits()) {
			for (ProgramUnit pu : cu.getProgramUnits()) {
				if (pu.getDataDivision() == null) continue;
				for (DataDescriptionEntryContainer section : this.collectAllDataSections(pu)) {
					List<DataDescriptionEntry> candidates = this.findEntriesByNameHyphenInsensitive(section, fieldName);
					if (candidates == null || candidates.isEmpty()) {
						candidates = this.findEntriesRecursivelyByName(section, fieldName);
					}
					if (candidates == null || candidates.isEmpty()) continue;
					for (DataDescriptionEntry candidate : candidates) {
						if (!this.matchesQualifiers(candidate, qualifierNames)) continue;
						return candidate;
					}
				}
			}
		}
		return null;
	}

	private String findChildMappedName(DataDescriptionEntryGroup parentGroup, String suffix) {
		for (DataDescriptionEntry child : parentGroup.getDataDescriptionEntries()) {
			if (child.getName() == null || !child.getName().toUpperCase().endsWith(suffix)) continue;
			return this.javaVariableIdentifierService.mapToIdentifier(child);
		}
		return null;
	}

	private static final java.util.Set<String> IMPLICIT_QUALIFIER_NAMES = java.util.Set.of("SQLCA");

	private boolean matchesQualifiers(DataDescriptionEntry entry, List<String> qualifierNames) {
		// Filter out implicit qualifiers (e.g., SQLCA) that don't exist as declared data groups
		List<String> effectiveQualifiers = qualifierNames;
		if (!qualifierNames.isEmpty()) {
			effectiveQualifiers = new ArrayList<>();
			for (String q : qualifierNames) {
				if (!IMPLICIT_QUALIFIER_NAMES.contains(q.toUpperCase())) {
					effectiveQualifiers.add(q);
				}
			}
		}
		int qualIdx = 0;
		for (DataDescriptionEntryGroup parent = entry.getParentDataDescriptionEntryGroup(); parent != null && qualIdx < effectiveQualifiers.size(); parent = parent.getParentDataDescriptionEntryGroup()) {
			String qualNorm;
			String parentNorm;
			if (parent.getName() == null || !(parentNorm = parent.getName().toUpperCase().replace('_', '-')).equals(qualNorm = effectiveQualifiers.get(qualIdx)) && !parentNorm.replace("-", "").equals(qualNorm.replace("-", ""))) continue;
			++qualIdx;
		}
		// If we still have unmatched qualifiers after exhausting parent data groups,
		// check if the remaining qualifier matches the containing FileDescriptionEntry (FD) name.
		// In COBOL, "CODMOV OF FA1000SFR" qualifies a field by the file name (FD),
		// not by the record name — the FD is not a DataDescriptionEntryGroup parent.
		if (qualIdx < effectiveQualifiers.size() && entry.getProgram() != null) {
			ASGElementRegistry asgReg = entry.getProgram().getASGElementRegistry();
			FileDescriptionEntry fde = (FileDescriptionEntry) ANTLRUtils.findParent(
					FileDescriptionEntry.class, (ParseTree) entry.getCtx(), asgReg);
			if (fde != null && fde.getName() != null) {
				String fdeNorm = fde.getName().toUpperCase().replace('_', '-');
				String qualNorm = effectiveQualifiers.get(qualIdx).toUpperCase().replace('_', '-');
				if (fdeNorm.equals(qualNorm) || fdeNorm.replace("-", "").equals(qualNorm.replace("-", ""))) {
					++qualIdx;
				}
			}
		}
		return qualIdx == effectiveQualifiers.size();
	}

	/**
	 * Generates a subscript index expression (0-based) from a subscript ValueStmt.
	 * Handles both BigDecimal (numeric) and String (alphanumeric used as subscript) types.
	 * For numeric: generates "expr.intValue() - 1"
	 * For string: generates "Integer.parseInt(expr.trim()) - 1"
	 */
	private String generateSubscriptIndexExpr(ValueStmt subscriptValueStmt, String subExprStr) {
		CobolTypeEnum subType = this.cobolTypeService.getType(subscriptValueStmt);
		if (CobolTypeEnum.STRING.equals(subType)) {
			return "Integer.parseInt(" + subExprStr + ".trim()) - 1";
		}
		return subExprStr + ".intValue() - 1";
	}

	private static final java.util.Set<String> JAVA_RESERVED_WORDS = java.util.Set.of(
		"abstract", "assert", "boolean", "break", "byte",
		"case", "catch", "char", "class", "const",
		"continue", "default", "do", "double", "else",
		"enum", "extends", "final", "finally", "float",
		"for", "goto", "if", "implements", "import",
		"instanceof", "int", "interface", "long", "native",
		"new", "package", "private", "protected", "public",
		"return", "short", "static", "strictfp", "super",
		"switch", "synchronized", "this", "throw", "throws",
		"transient", "try", "void", "volatile", "while",
		"null", "true", "false"
	);

	private String cobolFunctionNameToJava(String cobolName) {
		String[] parts = cobolName.split("-");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < parts.length; ++i) {
			String part = parts[i].toLowerCase();
			if (i == 0) {
				sb.append(part);
				continue;
			}
			sb.append(Character.toUpperCase(part.charAt(0)));
			sb.append(part.substring(1));
		}
		String result = sb.toString();
		// If the generated method name is a Java reserved word, suffix with "Function"
		// e.g., COBOL FUNCTION CHAR -> charFunction (since "char" is reserved)
		if (JAVA_RESERVED_WORDS.contains(result)) {
			result = result + "Function";
		}
		return result;
	}

	private String mapIntrinsicArgument(CobolParser.ArgumentContext arg, Program program) {
		if (arg.identifier() != null) {
			String idText = arg.identifier().getText().toUpperCase();
			if ("DATE".equals(idText) || "TIME".equals(idText) || "TIMESTAMP".equals(idText) || "DATE-TIME".equals(idText) || "DAY".equals(idText) || "DAY-OF-WEEK".equals(idText) || "DAYS".equals(idText) || "MONTHS".equals(idText) || "YEARS".equals(idText) || "HOURS".equals(idText) || "MINUTES".equals(idText) || "SECONDS".equals(idText) || "MICROSECONDS".equals(idText)) {
				return "\"" + idText + "\"";
			}
			// Handle ALL subscript: FUNCTION SUM(QUANT OF REG-DET-50(ALL))
			// The identifier contains a qualifier with an ALL subscript, meaning
			// "iterate all elements". Generate a stream expression.
			String allResult = this.tryResolveAllSubscriptIntrinsicArg(arg.identifier(), program);
			if (allResult != null) {
				return allResult;
			}
			// Handle nested intrinsic function calls: e.g., FUNCTION TRIM(ETIQ-RECIP OF RS0060EIO1-I)
			// When an argument is itself a FUNCTION call, the identifier contains a functionCall().
			// We must build the nested call properly instead of concatenating the raw getText().
			if (arg.identifier().functionCall() != null) {
				CobolParser.FunctionCallContext nestedFunc = arg.identifier().functionCall();
				String nestedName = nestedFunc.functionName().getText();
				String nestedJavaMethod = this.cobolFunctionNameToJava(nestedName);
				StringBuilder nestedSb = new StringBuilder();
				nestedSb.append("CobolIntrinsic.").append(nestedJavaMethod).append("(");
				List<CobolParser.ArgumentContext> nestedArgs = nestedFunc.argument();
				if (nestedArgs != null) {
					for (int ni = 0; ni < nestedArgs.size(); ++ni) {
						if (ni > 0) {
							nestedSb.append(", ");
						}
						nestedSb.append(this.mapIntrinsicArgument(nestedArgs.get(ni), program));
					}
				}
				nestedSb.append(")");
				return nestedSb.toString();
			}
			String result = null;
			if (arg.identifier().qualifiedDataName() != null && arg.identifier().qualifiedDataName().qualifiedDataNameFormat1() != null) {
				String path = this.buildQualifiedPath(arg.identifier().qualifiedDataName().qualifiedDataNameFormat1(), program);
				String redefinesPath = this.resolveRedefinesGetterForReadContext(arg.identifier().qualifiedDataName().qualifiedDataNameFormat1().dataName().getText(), program);
				result = redefinesPath != null ? redefinesPath : path;
			} else {
				result = this.javaIdentifierService.mapToIdentifier(arg.identifier().getText());
			}
			if (arg.identifier().referenceModifier() != null) {
				CobolParser.ReferenceModifierContext refMod = arg.identifier().referenceModifier();
				String posExpr = this.convertArithExprCtxToJavaInt(refMod.characterPosition().arithmeticExpression(), program);
				result = refMod.length() != null ? "CobolReference.referenceModification(" + result + ", " + posExpr + ", " + this.convertArithExprCtxToJavaInt(refMod.length().arithmeticExpression(), program) + ")" : "CobolReference.referenceModification(" + result + ", " + posExpr + ")";
			}
			return result;
		}
		if (arg.qualifiedDataName() != null) {
			if (arg.qualifiedDataName().qualifiedDataNameFormat1() != null) {
				String path = this.buildQualifiedPath(arg.qualifiedDataName().qualifiedDataNameFormat1(), program);
				String redefinesPath = this.resolveRedefinesGetterForReadContext(arg.qualifiedDataName().qualifiedDataNameFormat1().dataName().getText(), program);
				return redefinesPath != null ? redefinesPath : path;
			}
			return this.javaIdentifierService.mapToIdentifier(arg.qualifiedDataName().getText());
		}
		if (arg.literal() != null) {
			String litText = arg.literal().getText();
			try {
				new BigDecimal(litText);
				return "BigDecimal.valueOf(" + litText + ")";
			}
			catch (NumberFormatException e) {
				return JavaLiteralUtils.mapToLiteral((String)litText);
			}
		}
		if (arg.arithmeticExpression() != null) {
			return arg.arithmeticExpression().getText();
		}
		String text = arg.getText().trim();
		return "\"" + text.toUpperCase() + "\"";
	}

	/**
	 * Detects COBOL ALL subscript in intrinsic function arguments, e.g.,
	 * FUNCTION SUM(QUANT OF REG-DET-50(ALL)). When found, generates a stream
	 * expression that maps over all elements and extracts the leaf field:
	 * list.stream().map(_allElem -> _allElem.leafField).toArray(BigDecimal[]::new)
	 *
	 * Returns null if no ALL subscript is found in the identifier.
	 */
	private String tryResolveAllSubscriptIntrinsicArg(CobolParser.IdentifierContext identCtx, Program program) {
		if (identCtx == null || program == null) return null;
		CobolParser.QualifiedDataNameContext qdn = identCtx.qualifiedDataName();
		if (qdn == null || qdn.qualifiedDataNameFormat1() == null) return null;
		CobolParser.QualifiedDataNameFormat1Context qdf1 = qdn.qualifiedDataNameFormat1();
		if (qdf1.qualifiedInData() == null || qdf1.qualifiedInData().isEmpty()) return null;

		// Check if any qualifier has an ALL subscript
		boolean hasAllSubscript = false;
		for (CobolParser.QualifiedInDataContext qid : qdf1.qualifiedInData()) {
			if (qid.inTable() == null || qid.inTable().tableCall() == null) continue;
			CobolParser.TableCallContext tc = qid.inTable().tableCall();
			if (tc.subscript() == null) continue;
			for (CobolParser.SubscriptContext sub : tc.subscript()) {
				if ("ALL".equalsIgnoreCase(sub.getText().trim())) {
					hasAllSubscript = true;
					break;
				}
			}
			if (hasAllSubscript) break;
		}
		if (!hasAllSubscript) return null;

		// Resolve the leaf field and build the hierarchy path
		String leafName = qdf1.dataName().getText();
		ArrayList<String> qualifierNames = new ArrayList<String>();
		for (CobolParser.QualifiedInDataContext qid : qdf1.qualifiedInData()) {
			if (qid.inData() != null && qid.inData().dataName() != null) {
				qualifierNames.add(qid.inData().dataName().getText().toUpperCase().replace('_', '-'));
				continue;
			}
			if (qid.inTable() != null && qid.inTable().tableCall() != null && qid.inTable().tableCall().qualifiedDataName() != null) {
				qualifierNames.add(qid.inTable().tableCall().qualifiedDataName().getText().toUpperCase().replace('_', '-'));
			}
		}

		DataDescriptionEntry leafDde = this.findFieldByNameViaASG(leafName, qualifierNames, program);
		if (leafDde == null) return null;

		List<DataDescriptionEntry> hierarchy = this.collectCallHierarchy(leafDde);

		// Walk the hierarchy and build the path, generating a stream at the ALL-subscripted entry
		StringBuilder result = new StringBuilder();
		boolean isFirst = true;
		// Track which qualifier index has the ALL subscript
		int qualWithAllIdx = -1;
		int qualIdx = 0;
		for (CobolParser.QualifiedInDataContext qid : qdf1.qualifiedInData()) {
			if (qid.inTable() != null && qid.inTable().tableCall() != null) {
				CobolParser.TableCallContext tc = qid.inTable().tableCall();
				if (tc.subscript() != null) {
					for (CobolParser.SubscriptContext sub : tc.subscript()) {
						if ("ALL".equalsIgnoreCase(sub.getText().trim())) {
							qualWithAllIdx = qualIdx;
							break;
						}
					}
				}
			}
			qualIdx++;
		}

		// Find which hierarchy entry has OCCURS and matches the ALL-subscripted qualifier
		for (int hIdx = 0; hIdx < hierarchy.size(); hIdx++) {
			DataDescriptionEntry entry = hierarchy.get(hIdx);
			if (!isFirst) {
				result.append(DOT);
			}
			result.append(this.javaVariableIdentifierService.mapToIdentifier(entry));
			isFirst = false;

			if (entry instanceof DataDescriptionEntryGroup) {
				int maxOccurs = this.pictureStringService.getMaxOccurs((DataDescriptionEntryGroup) entry);
				if (maxOccurs > 1) {
					// This is the OCCURS entry — generate the stream
					String listExpr = result.toString();
					// Build the remaining leaf path (entries after this one)
					StringBuilder leafPath = new StringBuilder();
					for (int ri = hIdx + 1; ri < hierarchy.size(); ri++) {
						leafPath.append(DOT);
						leafPath.append(this.javaVariableIdentifierService.mapToIdentifier(hierarchy.get(ri)));
					}
					return listExpr + ".stream().map(_allElem -> _allElem" + leafPath + ").toArray(BigDecimal[]::new)";
				}
			}
		}
		return null;
	}

	private boolean isQualifiedArgWithOccursQualifier(CobolParser.ArgumentContext arg, Program program) {
		if (program == null) {
			return false;
		}
		CobolParser.QualifiedDataNameFormat1Context qdf1 = null;
		if (arg.identifier() != null && arg.identifier().qualifiedDataName() != null) {
			qdf1 = arg.identifier().qualifiedDataName().qualifiedDataNameFormat1();
		} else if (arg.qualifiedDataName() != null) {
			qdf1 = arg.qualifiedDataName().qualifiedDataNameFormat1();
		}
		if (qdf1 == null || qdf1.qualifiedInData() == null || qdf1.qualifiedInData().isEmpty()) {
			return false;
		}
		String lastQualifierName = null;
		for (CobolParser.QualifiedInDataContext qid : qdf1.qualifiedInData()) {
			if (qid.inData() != null && qid.inData().dataName() != null) {
				lastQualifierName = qid.inData().dataName().getText();
				continue;
			}
			if (qid.inTable() == null || qid.inTable().tableCall() == null || qid.inTable().tableCall().qualifiedDataName() == null) continue;
			return false;
		}
		if (lastQualifierName == null) {
			return false;
		}
		DataDescriptionEntry entry = this.findFieldByNameViaASG(lastQualifierName, Collections.emptyList(), program);
		if (entry == null || !(entry instanceof DataDescriptionEntryGroup)) {
			return false;
		}
		DataDescriptionEntryGroup grp = (DataDescriptionEntryGroup)entry;
		int maxOccurs = this.pictureStringService.getMaxOccurs(grp);
		return maxOccurs > 1;
	}

	private String mapIntrinsicArgumentWithSubscripts(CobolParser.ArgumentContext arg, List<CobolParser.ArgumentContext> subscriptArgs, Program program) {
		CobolParser.QualifiedDataNameFormat1Context qdf1 = null;
		if (arg.identifier() != null && arg.identifier().qualifiedDataName() != null) {
			qdf1 = arg.identifier().qualifiedDataName().qualifiedDataNameFormat1();
		} else if (arg.qualifiedDataName() != null) {
			qdf1 = arg.qualifiedDataName().qualifiedDataNameFormat1();
		}
		if (qdf1 == null || program == null) {
			return this.mapIntrinsicArgument(arg, program);
		}
		String leafName = qdf1.dataName().getText();
		ArrayList<String> qualifierNames = new ArrayList<String>();
		for (CobolParser.QualifiedInDataContext qid : qdf1.qualifiedInData()) {
			CobolParser.TableCallContext tc;
			if (qid.inData() != null && qid.inData().dataName() != null) {
				qualifierNames.add(qid.inData().dataName().getText().toUpperCase().replace('_', '-'));
				continue;
			}
			if (qid.inTable() == null || qid.inTable().tableCall() == null || (tc = qid.inTable().tableCall()).qualifiedDataName() == null) continue;
			qualifierNames.add(tc.qualifiedDataName().getText().toUpperCase().replace('_', '-'));
		}
		for (CompilationUnit cu : program.getCompilationUnits()) {
			for (ProgramUnit pu : cu.getProgramUnits()) {
				if (pu.getDataDivision() == null) continue;
				for (DataDescriptionEntryContainer section : this.collectAllDataSections(pu)) {
					List<DataDescriptionEntry> candidates = this.findEntriesByNameHyphenInsensitive(section, leafName);
					if (candidates == null || candidates.isEmpty()) continue;
					for (DataDescriptionEntry candidate : candidates) {
						if (!this.matchesQualifiers(candidate, qualifierNames)) continue;
						List<DataDescriptionEntry> hierarchy = this.collectCallHierarchy(candidate);
						StringBuilder result = new StringBuilder();
						boolean isFirst = true;
						if (section instanceof FileDescriptionEntry) {
							result.append(this.javaFileDescriptionEntryIdentifierService.mapToIdentifier((FileDescriptionEntry)section));
							isFirst = false;
						}
						Iterator<CobolParser.ArgumentContext> subArgIter = subscriptArgs.iterator();
						for (DataDescriptionEntry entry : hierarchy) {
							DataDescriptionEntryGroup grp;
							int maxOccurs;
							if (!isFirst) {
								result.append(DOT);
							}
							result.append(this.javaVariableIdentifierService.mapToIdentifier(entry));
							if (DataDescriptionEntry.DataDescriptionEntryType.GROUP.equals(entry.getDataDescriptionEntryType()) && (maxOccurs = this.pictureStringService.getMaxOccurs(grp = (DataDescriptionEntryGroup)entry)) > 1 && subArgIter.hasNext()) {
								boolean isGroupWithChildren = this.cobolDataDescriptionEntryService.hasChildren((DataDescriptionEntry)grp);
								CobolParser.ArgumentContext subArg = subArgIter.next();
								String subText = subArg.getText().trim();
								if (subText.startsWith("(") && subText.endsWith(")")) {
									subText = subText.substring(1, subText.length() - 1).trim();
								}
								// Handle ALL subscript: iterate all elements via stream
								if ("ALL".equalsIgnoreCase(subText) && isGroupWithChildren) {
									String listExpr = result.toString();
									// Build the remaining leaf field path
									StringBuilder leafPath = new StringBuilder();
									for (int ri = hierarchy.indexOf(entry) + 1; ri < hierarchy.size(); ri++) {
										leafPath.append(DOT);
										leafPath.append(this.javaVariableIdentifierService.mapToIdentifier(hierarchy.get(ri)));
									}
									result.setLength(0);
									result.append(listExpr).append(".stream().map(_allElem -> _allElem").append(leafPath).append(").toArray(BigDecimal[]::new)");
									// Skip remaining hierarchy entries — already incorporated in lambda
									return result.toString();
								}
								if (isGroupWithChildren) {
									String fieldAccess = result.toString();
									result.setLength(0);
									result.append("io.proleap.cobol.runtime.CobolConstants.safeGet(");
									result.append(fieldAccess);
									result.append(", ");
								} else {
									result.append("[");
								}
								try {
									int idx = Integer.parseInt(subText);
									result.append(idx - 1);
								}
								catch (NumberFormatException e) {
									String arithResult = this.convertRefModExpr(subText, program);
									boolean isArith = arithResult.startsWith("(");
									if (isArith) {
										result.append(arithResult);
										result.append(" - 1");
									} else {
										String subExpr = this.resolveFieldByNameViaASG(subText, Collections.emptyList(), program);
										if (subExpr != null && subExpr.contains(DOT)) {
											result.append(subExpr);
										} else {
											result.append(this.javaIdentifierService.mapToIdentifier(subText));
										}
										result.append(".intValue() - 1");
									}
								}
								if (isGroupWithChildren) {
									result.append(")");
								} else {
									result.append("]");
								}
							}
							isFirst = false;
						}
						return result.toString();
					}
				}
			}
		}
		return this.mapIntrinsicArgument(arg, program);
	}

	private String resolveRedefinesGetterForReadContext(String fieldName, Program program) {
		if (program == null || fieldName == null) {
			return null;
		}
		DataDescriptionEntry entry = this.findFieldByNameViaASG(fieldName, Collections.emptyList(), program);
		if (entry == null || !(entry instanceof DataDescriptionEntryGroup)) {
			return null;
		}
		DataDescriptionEntryGroup group = (DataDescriptionEntryGroup)entry;
		if (group.getRedefinesClause() == null || group.getRedefinesClause().getRedefinesCall() == null) {
			return null;
		}
		String variableId = this.javaVariableIdentifierService.mapToIdentifier(entry);
		String getterName = "get" + Character.toUpperCase(variableId.charAt(0)) + variableId.substring(1) + "()";
		DataDescriptionEntryGroup parentGroup = entry.getParentDataDescriptionEntryGroup();
		if (parentGroup != null) {
			List<DataDescriptionEntry> hierarchy = this.collectCallHierarchy(parentGroup);
			StringBuilder path = new StringBuilder();
			for (int i = 0; i < hierarchy.size(); ++i) {
				if (i > 0) {
					path.append(DOT);
				}
				path.append(this.javaVariableIdentifierService.mapToIdentifier(hierarchy.get(i)));
			}
			path.append(DOT).append(getterName);
			return path.toString();
		}
		return getterName;
	}

	private String normalizeBooleanLiteralForComparison(String expr) {
		if ("false".equals(expr)) {
			return "BigDecimal.ZERO";
		}
		if ("true".equals(expr)) {
			return "BigDecimal.ONE";
		}
		return expr;
	}

	private String normalizeForAlphanumericComparison(String expr) {
		if ("BigDecimal.ZERO".equals(expr)) {
			return "\"0\"";
		}
		return expr;
	}

	/**
	 * Ensures an expression is usable as a BigDecimal for numeric comparisons.
	 * When the expression is already a BigDecimal (e.g., a numeric variable or BigDecimal literal),
	 * it is returned unchanged. When it is a String literal or String expression,
	 * it is wrapped in a BigDecimal conversion.
	 */
	private String normalizeForNumericComparison(String expr) {
		if (expr == null) {
			return "BigDecimal.ZERO";
		}
		// Already a BigDecimal expression — no wrapping needed
		if (expr.startsWith("BigDecimal.") || expr.startsWith("new BigDecimal(")
				|| expr.startsWith("new java.math.BigDecimal(")) {
			return expr;
		}
		// Numeric variable references (no quotes, not a string literal)
		// — these are already BigDecimal fields in the generated Java
		if (!expr.startsWith("\"") && !expr.contains(".substring(")
				&& !expr.contains(".toString()") && !expr.contains("String.valueOf(")
				&& !expr.equals("CobolConstants.spaces(")
				&& !expr.startsWith("CobolMove.moveAlphanumericToAlphanumeric(")
				&& !expr.startsWith("CobolMove.moveNumericToAlphanumeric(")
				&& !expr.startsWith("CobolReference.referenceModification(")) {
			return expr;
		}
		// String literal like "1", "500" — wrap in new BigDecimal(...)
		if (expr.startsWith("\"") && expr.endsWith("\"")) {
			String inner = expr.substring(1, expr.length() - 1);
			// Check if it's a valid number
			try {
				new java.math.BigDecimal(inner.trim());
				return "new BigDecimal(\"" + inner.trim() + "\")";
			} catch (NumberFormatException e) {
				// Non-numeric string: compare as string via alphanumeric comparison
				return expr;
			}
		}
		// String expression (reference modification, substring, etc.)
		// — parse at runtime
		return "new BigDecimal(" + expr + ".trim().isEmpty() ? \"0\" : " + expr + ".trim())";
	}

	private String getFigurativeConstantHelper(String expr) {
		if ("BigDecimal.ZERO".equals(expr)) {
			return "CobolConstants.isZeros";
		}
		if ("\" \"".equals(expr)) {
			return "CobolConstants.isSpaces";
		}
		if ("\"\\u00FF\"".equals(expr)) {
			return "CobolConstants.isHighValues";
		}
		if ("\"\\0\"".equals(expr)) {
			return "CobolConstants.isLowValues";
		}
		return null;
	}

	public String convertRefModExpression(String expr, Program program) {
		return this.convertRefModExpr(expr, program);
	}

	/**
	 * Applies qualifier subscripts from qualifierSubscriptMap to the path StringBuilder.
	 * When a qualifier (e.g., E-TB1002) has subscripts (e.g., W-ROW), wraps the current
	 * path with safeGet or appends .get(idx) as appropriate.
	 */
	private void applyQualifierSubscriptsToPath(StringBuilder path, String qualId,
			java.util.Map<String, List<CobolParser.SubscriptContext>> qualifierSubscriptMap, Program program) {
		List<CobolParser.SubscriptContext> subs = qualifierSubscriptMap.get(qualId);
		if (subs == null || subs.isEmpty()) {
			return;
		}
		for (CobolParser.SubscriptContext subCtx : subs) {
			String subText = subCtx.getText().trim();
			// Use safeGet pattern (consistent with OCCURS list access)
			String fieldAccess = path.toString();
			path.setLength(0);
			path.append("io.proleap.cobol.runtime.CobolConstants.safeGet(");
			path.append(fieldAccess);
			path.append(", ");
			try {
				int idx = Integer.parseInt(subText);
				path.append(idx - 1);
			} catch (NumberFormatException e) {
				// Try to resolve the subscript via ASG
				String subExpr = null;
				if (subCtx.qualifiedDataName() != null && program != null) {
					CobolParser.QualifiedDataNameContext qdn = subCtx.qualifiedDataName();
					String subLeafName = null;
					List<String> qualNames = new java.util.ArrayList<>();
					if (qdn.qualifiedDataNameFormat1() != null) {
						CobolParser.QualifiedDataNameFormat1Context qdn1 = qdn.qualifiedDataNameFormat1();
						subLeafName = qdn1.dataName() != null ? qdn1.dataName().getText().trim() : null;
						if (qdn1.qualifiedInData() != null) {
							for (CobolParser.QualifiedInDataContext qid : qdn1.qualifiedInData()) {
								if (qid.inData() != null && qid.inData().dataName() != null) {
									qualNames.add(qid.inData().dataName().getText().trim().toUpperCase().replace('_', '-'));
								}
							}
						}
					}
					if (subLeafName != null) {
						subExpr = this.resolveFieldByNameViaASG(subLeafName, qualNames, program);
					}
				}
				if (subExpr == null && program != null) {
					subExpr = this.resolveFieldByNameViaASG(subText, Collections.emptyList(), program);
				}
				if (subExpr != null && subExpr.contains(DOT)) {
					path.append(subExpr).append(".intValue() - 1");
				} else {
					String arithResult = this.convertRefModExpr(subText, program);
					if (arithResult.startsWith("(")) {
						path.append(arithResult).append(" - 1");
					} else {
						path.append(this.javaIdentifierService.mapToIdentifier(subText)).append(".intValue() - 1");
					}
				}
			}
			path.append(")");
		}
	}

	private String convertRefModExpr(String expr) {
		return this.convertRefModExpr(expr, null);
	}

	private String convertRefModExpr(String expr, Program program) {
		if (expr == null || expr.isEmpty()) {
			return expr;
		}
		try {
			int parsed = Integer.parseInt(expr);
			return String.valueOf(parsed);
		}
		catch (NumberFormatException e) {
			String resolved;
			Matcher arithMatcher = Pattern.compile("([A-Za-z0-9-]+)\\s*([+])\\s*([A-Za-z0-9-]+)|([A-Za-z0-9-]+)\\s+([-])\\s+([A-Za-z0-9-]+)|([0-9]+)([-])([A-Za-z][A-Za-z0-9-]*)").matcher(expr.trim());
			if (arithMatcher.matches()) {
				String left = arithMatcher.group(1) != null ? arithMatcher.group(1) : (arithMatcher.group(4) != null ? arithMatcher.group(4) : arithMatcher.group(7));
				String op = arithMatcher.group(2) != null ? arithMatcher.group(2) : (arithMatcher.group(5) != null ? arithMatcher.group(5) : arithMatcher.group(8));
				String right = arithMatcher.group(3) != null ? arithMatcher.group(3) : (arithMatcher.group(6) != null ? arithMatcher.group(6) : arithMatcher.group(9));
				return "(" + this.convertRefModTerm(left, program) + " " + op + " " + this.convertRefModTerm(right, program) + ")";
			}
			if (program != null && (resolved = this.resolveFieldByNameViaASG(expr, Collections.emptyList(), program)) != null && resolved.contains(DOT)) {
				return resolved + ".intValue()";
			}
			return this.javaIdentifierService.mapToIdentifier(expr) + ".intValue()";
		}
	}

	private String convertRefModTerm(String term, Program program) {
		try {
			int parsed = Integer.parseInt(term);
			return String.valueOf(parsed);
		}
		catch (NumberFormatException e) {
			String resolved;
			if (program != null && (resolved = this.resolveFieldByNameViaASG(term, Collections.emptyList(), program)) != null && resolved.contains(DOT)) {
				return resolved + ".intValue()";
			}
			return this.javaIdentifierService.mapToIdentifier(term) + ".intValue()";
		}
	}

	public String convertArithExprCtxToJavaInt(CobolParser.ArithmeticExpressionContext arithExpr, Program program) {
		if (arithExpr == null) {
			return "0";
		}
		StringBuilder sb = new StringBuilder();
		sb.append(this.convertMultDivsCtxToJavaInt(arithExpr.multDivs(), program));
		if (arithExpr.plusMinus() != null) {
			for (CobolParser.PlusMinusContext pm : arithExpr.plusMinus()) {
				String op = pm.PLUSCHAR() != null ? " + " : " - ";
				sb.append(op);
				sb.append(this.convertMultDivsCtxToJavaInt(pm.multDivs(), program));
			}
		}
		return sb.toString();
	}

	private String convertMultDivsCtxToJavaInt(CobolParser.MultDivsContext multDivs, Program program) {
		if (multDivs == null) {
			return "0";
		}
		StringBuilder sb = new StringBuilder();
		sb.append(this.convertPowersCtxToJavaInt(multDivs.powers(), program));
		if (multDivs.multDiv() != null) {
			for (CobolParser.MultDivContext md : multDivs.multDiv()) {
				String op = md.ASTERISKCHAR() != null ? " * " : " / ";
				sb.append(op);
				sb.append(this.convertPowersCtxToJavaInt(md.powers(), program));
			}
		}
		return sb.toString();
	}

	private String convertPowersCtxToJavaInt(CobolParser.PowersContext powers, Program program) {
		if (powers == null) {
			return "0";
		}
		StringBuilder sb = new StringBuilder();
		if (powers.MINUSCHAR() != null) {
			sb.append("-");
		}
		sb.append(this.convertBasisCtxToJavaInt(powers.basis(), program));
		return sb.toString();
	}

	private String convertBasisCtxToJavaInt(CobolParser.BasisContext basis, Program program) {
		if (basis == null) {
			return "0";
		}
		if (basis.arithmeticExpression() != null) {
			return "(" + this.convertArithExprCtxToJavaInt(basis.arithmeticExpression(), program) + ")";
		}
		if (basis.identifier() != null) {
			return this.convertIdentifierCtxToJavaInt(basis.identifier(), program);
		}
		if (basis.literal() != null) {
			String litText = basis.literal().getText().trim();
			try {
				int parsed = Integer.parseInt(litText);
				return String.valueOf(parsed);
			}
			catch (NumberFormatException e) {
				return litText;
			}
		}
		return this.convertRefModExpr(basis.getText(), program);
	}

	private String convertIdentifierCtxToJavaInt(CobolParser.IdentifierContext identCtx, Program program) {
		CobolParser.QualifiedDataNameContext qdn;
		if (identCtx == null) {
			return "0";
		}
		if (identCtx.qualifiedDataName() != null && (qdn = identCtx.qualifiedDataName()).qualifiedDataNameFormat1() != null) {
			String resolved;
			CobolParser.QualifiedDataNameFormat1Context qdf1 = qdn.qualifiedDataNameFormat1();
			String dataName = qdf1.dataName() != null ? qdf1.dataName().getText().trim() : (qdf1.conditionName() != null ? qdf1.conditionName().getText().trim() : qdn.getText().trim());
			ArrayList<String> qualifierNames = new ArrayList<String>();
			if (qdf1.qualifiedInData() != null) {
				for (CobolParser.QualifiedInDataContext qid : qdf1.qualifiedInData()) {
					if (qid.inData() == null || qid.inData().dataName() == null) continue;
					qualifierNames.add(qid.inData().dataName().getText().trim());
				}
			}
			if (program != null && (resolved = this.resolveFieldByNameViaASG(dataName, qualifierNames, program)) != null && resolved.contains(DOT)) {
				return resolved + ".intValue()";
			}
			return this.javaIdentifierService.mapToIdentifier(dataName) + ".intValue()";
		}
		// Handle tableCall (subscripted identifier like EMilLen(L))
		if (identCtx.tableCall() != null) {
			CobolParser.TableCallContext tcCtx = identCtx.tableCall();
			if (tcCtx.qualifiedDataName() != null && tcCtx.qualifiedDataName().qualifiedDataNameFormat1() != null) {
				CobolParser.QualifiedDataNameFormat1Context qdf1 = tcCtx.qualifiedDataName().qualifiedDataNameFormat1();
				String dataName = qdf1.dataName() != null ? qdf1.dataName().getText().trim() : tcCtx.qualifiedDataName().getText().trim();
				ArrayList<String> qualifierNames = new ArrayList<String>();
				if (qdf1.qualifiedInData() != null) {
					for (CobolParser.QualifiedInDataContext qid : qdf1.qualifiedInData()) {
						if (qid.inData() == null || qid.inData().dataName() == null) continue;
						qualifierNames.add(qid.inData().dataName().getText().trim());
					}
				}
				// Try to resolve through ASG — the expression service's mapToCall
				// for TABLE_CALL handles OCCURS-within-REDEFINES with proper substring logic.
				if (program != null) {
					ASGElementRegistry asgReg = program.getASGElementRegistry();
					if (asgReg != null) {
						Object asgElem = asgReg.getASGElement(tcCtx);
						if (asgElem instanceof TableCall) {
							String expr = this.mapToCall((TableCall)asgElem);
							// If the result is a substring (String), wrap in BigDecimal for .intValue()
							if (expr.contains(".substring(")) {
								return "new BigDecimal(" + expr + ".trim()).intValue()";
							}
							return expr + ".intValue()";
						}
					}
				}
				// Fallback: try to resolve OCCURS-within-REDEFINES sub-field by name
				// and generate the proper substring expression with subscript.
				if (program != null) {
					String occursSubExpr = this.resolveOccursRedefinesSubFieldInt(dataName, qualifierNames, tcCtx, program);
					if (occursSubExpr != null) {
						return occursSubExpr;
					}
				}
				// Fallback: resolve field name without subscript
				String resolved;
				if (program != null && (resolved = this.resolveFieldByNameViaASG(dataName, qualifierNames, program)) != null && resolved.contains(DOT)) {
					return resolved + ".intValue()";
				}
				return this.javaIdentifierService.mapToIdentifier(dataName) + ".intValue()";
			}
		}
		return this.convertRefModExpr(identCtx.getText(), program);
	}

	/**
	 * Resolves a subscripted reference to an OCCURS sub-field within a group-over-elementary
	 * REDEFINES and returns an int expression. Used when the ASG element registry doesn't
	 * contain an entry for the parse tree context (e.g., identifiers inside reference
	 * modification expressions like WAlfa(POS:EMilLen(L))).
	 *
	 * Returns null if the field is not an OCCURS-within-REDEFINES sub-field.
	 */
	private String resolveOccursRedefinesSubFieldInt(String fieldName, List<String> qualifierNames,
			CobolParser.TableCallContext tcCtx, Program program) {
		// Find the DDE for this field name
		DataDescriptionEntry leafDde = null;
		for (CompilationUnit cu : program.getCompilationUnits()) {
			for (ProgramUnit pu : cu.getProgramUnits()) {
				if (pu.getDataDivision() == null) continue;
				for (DataDescriptionEntryContainer section : this.collectAllDataSections(pu)) {
					List<DataDescriptionEntry> candidates = this.findEntriesByNameHyphenInsensitive(section, fieldName);
					if (candidates == null || candidates.isEmpty()) {
						candidates = this.findEntriesRecursivelyByName(section, fieldName);
					}
					if (candidates == null || candidates.isEmpty()) continue;
					for (DataDescriptionEntry candidate : candidates) {
						if (!this.matchesQualifiers(candidate, qualifierNames)) continue;
						leafDde = candidate;
						break;
					}
					if (leafDde != null) break;
				}
				if (leafDde != null) break;
			}
			if (leafDde != null) break;
		}
		if (leafDde == null) return null;

		// Walk up from the leaf to find: OCCURS parent, then REDEFINES ancestor
		DataDescriptionEntryGroup occursParent = null;
		int occursMaxOccurs = 0;
		DataDescriptionEntryGroup redefinesAncestor = null;
		int leafOffset = 0;

		// Collect path from leaf to REDEFINES ancestor
		DataDescriptionEntry current = leafDde;
		DataDescriptionEntryGroup parent = current.getParentDataDescriptionEntryGroup();

		// First, find the OCCURS parent (immediate parent with OCCURS > 1)
		if (parent instanceof DataDescriptionEntryGroup) {
			int parentMaxOccurs = this.pictureStringService.getMaxOccurs(parent);
			if (parentMaxOccurs > 1) {
				occursParent = parent;
				occursMaxOccurs = parentMaxOccurs;
				// Compute leaf offset within the OCCURS element by walking siblings
				for (DataDescriptionEntry sibling : parent.getDataDescriptionEntries()) {
					if (sibling == current || (sibling.getName() != null && current.getName() != null
							&& sibling.getName().equalsIgnoreCase(current.getName()))) {
						break;
					}
					if (sibling.getDataDescriptionEntryType() != DataDescriptionEntry.DataDescriptionEntryType.CONDITION) {
						Integer sibLen = this.cobolPictureLengthService.getLength(sibling);
						if (sibLen != null) {
							leafOffset += sibLen;
						}
					}
				}
			}
		}
		if (occursParent == null) return null;

		// Now check if the OCCURS parent is inside a group-over-elementary REDEFINES
		DataDescriptionEntryGroup occursGrandparent = occursParent.getParentDataDescriptionEntryGroup();
		if (occursGrandparent != null && this.isGroupOverElementaryRedefines(occursGrandparent)) {
			redefinesAncestor = occursGrandparent;
		}
		if (redefinesAncestor == null) return null;

		// Get the base field name (what the REDEFINES overlays)
		String baseName = redefinesAncestor.getRedefinesClause().getRedefinesCall().getName();
		String baseId = this.javaIdentifierService.mapToIdentifier(baseName);

		// Compute element size
		DataDescriptionEntry baseDde = this.resolveRedefinesBase(redefinesAncestor, baseName);
		Integer baseLenObj = baseDde != null ? this.cobolPictureLengthService.getLength(baseDde) : null;
		int elemSize;
		if (baseLenObj != null) {
			elemSize = baseLenObj / occursMaxOccurs;
		} else {
			Integer occursLen = this.cobolPictureLengthService.getLength(occursParent);
			elemSize = occursLen != null ? occursLen : 1;
		}

		// Get leaf field length
		Integer leafLenObj = this.cobolPictureLengthService.getLength(leafDde);
		int leafLen = leafLenObj != null ? leafLenObj : 1;

		// Get subscript from the tableCall context
		List<CobolParser.SubscriptContext> subscripts = tcCtx.subscript();
		if (subscripts == null || subscripts.isEmpty()) return null;

		CobolParser.SubscriptContext subCtx = subscripts.get(0);
		String subText = subCtx.getText().trim();

		// Build the substring expression
		StringBuilder expr = new StringBuilder();
		expr.append("new BigDecimal(");
		try {
			int idx = Integer.parseInt(subText) - 1;
			int start = idx * elemSize + leafOffset;
			int end = start + leafLen;
			expr.append(baseId).append(".substring(").append(start).append(", ").append(end).append(")");
		} catch (NumberFormatException e) {
			String subExpr = this.convertRefModExpr(subText, program);
			// Check if it's an arithmetic expression (starts with paren) or a field reference
			if (!subExpr.endsWith(".intValue()") && !subExpr.startsWith("(")) {
				String resolved = this.resolveFieldByNameViaASG(subText, Collections.emptyList(), program);
				if (resolved != null && resolved.contains(DOT)) {
					subExpr = resolved + ".intValue()";
				} else {
					subExpr = this.javaIdentifierService.mapToIdentifier(subText) + ".intValue()";
				}
			}
			expr.append(baseId).append(".substring((").append(subExpr).append(" - 1) * ").append(elemSize);
			if (leafOffset > 0) {
				expr.append(" + ").append(leafOffset);
			}
			expr.append(", (").append(subExpr).append(" - 1) * ").append(elemSize);
			expr.append(" + ").append(leafOffset + leafLen).append(")");
		}
		expr.append(".trim()).intValue()");
		return expr.toString();
	}

	private String resolveIndexArithmeticSubscript(CobolParser.SubscriptContext subCtx, Program program) {
		if (subCtx == null) {
			return null;
		}
		if ((subCtx.PLUSCHAR() != null || subCtx.MINUSCHAR() != null) && subCtx.integerLiteral() != null) {
			int litVal;
			String varName = null;
			if (subCtx.indexName() != null) {
				varName = subCtx.indexName().getText().trim();
			} else if (subCtx.qualifiedDataName() != null) {
				varName = subCtx.qualifiedDataName().getText().trim();
			}
			if (varName == null) {
				return null;
			}
			String op = subCtx.PLUSCHAR() != null ? "+" : "-";
			String litText = subCtx.integerLiteral().getText().trim();
			try {
				litVal = Integer.parseInt(litText);
			}
			catch (NumberFormatException e) {
				litVal = 0;
			}
			String varExpr = null;
			if (program != null) {
				varExpr = this.resolveFieldByNameViaASG(varName, Collections.emptyList(), program);
			}
			if (varExpr == null || !varExpr.contains(DOT)) {
				varExpr = this.javaIdentifierService.mapToIdentifier(varName);
			}
			return varExpr + ".intValue() " + op + " " + litVal + " - 1";
		}
		return null;
	}

	private String applyContextSubscripts(String baseExpr, Call call) {
		List<CobolParser.SubscriptContext> subscriptCtxs;
		if (call.getCallType() == Call.CallType.TABLE_CALL) {
			return baseExpr;
		}
		CobolParser.TableCallContext tableCtx = null;
		ParserRuleContext callCtx = call.getCtx();
		boolean insideSubscript = false;
		for (ParserRuleContext check = callCtx; check != null; check = check.getParent()) {
			if (check instanceof CobolParser.SubscriptContext) {
				insideSubscript = true;
				break;
			}
			if (check instanceof CobolParser.IdentifierContext || check instanceof CobolParser.TableCallContext) break;
		}
		if (insideSubscript) {
			return baseExpr;
		}
		if (callCtx instanceof CobolParser.TableCallContext) {
			tableCtx = (CobolParser.TableCallContext)callCtx;
		} else if (callCtx instanceof CobolParser.IdentifierContext) {
			tableCtx = ((CobolParser.IdentifierContext)callCtx).tableCall();
		} else {
			for (ParserRuleContext ctx = callCtx; ctx != null; ctx = ctx.getParent()) {
				if (ctx instanceof CobolParser.TableCallContext) {
					tableCtx = (CobolParser.TableCallContext)ctx;
					break;
				}
				if (ctx instanceof CobolParser.IdentifierContext) break;
			}
		}
		if (tableCtx != null && (subscriptCtxs = tableCtx.subscript()) != null && !subscriptCtxs.isEmpty()) {
			StringBuilder sb = new StringBuilder(baseExpr);
			for (CobolParser.SubscriptContext subCtx : subscriptCtxs) {
				String subText = subCtx.getText().trim();
				try {
					int idx = Integer.parseInt(subText);
					sb.append("[").append(idx - 1).append("]");
				}
				catch (NumberFormatException e) {
					String arithResult = this.convertRefModExpr(subText, call.getProgram());
					if (arithResult.startsWith("(")) {
						sb.append("[").append(arithResult).append(" - 1]");
						continue;
					}
					sb.append("[").append(this.javaIdentifierService.mapToIdentifier(subText)).append(".intValue() - 1]");
				}
			}
			return sb.toString();
		}
		return baseExpr;
	}

	public String mapToCall(DataDescriptionEntryCall dataDescriptionEntryCall) {
		return this.mapToCall(dataDescriptionEntryCall, null);
	}

	private String mapToCall(DataDescriptionEntryCall dataDescriptionEntryCall, Call outerCall) {
		List<CobolParser.SubscriptContext> contextSubscripts;
		DataDescriptionEntry dataDescriptionEntry = dataDescriptionEntryCall.getDataDescriptionEntry();
		if (DataDescriptionEntry.DataDescriptionEntryType.CONDITION.equals(dataDescriptionEntry.getDataDescriptionEntryType())) {
			return this.mapConditionToCall(dataDescriptionEntry);
		}
		// Skip resolveRedefinesEntry when the entry is under a group-over-elementary
		// REDEFINES ancestor — each child has its own getter (e.g., getWknumrown_Valoraux)
		// and resolving VALORAUX->VALOR would lose the type-specific accessor.
		boolean underGroupOverElemRedefines = false;
		for (DataDescriptionEntryGroup anc = dataDescriptionEntry.getParentDataDescriptionEntryGroup(); anc != null; anc = anc.getParentDataDescriptionEntryGroup()) {
			if (this.isGroupOverElementaryRedefines(anc)) {
				underGroupOverElemRedefines = true;
				break;
			}
		}
		if (!underGroupOverElemRedefines) {
			dataDescriptionEntry = this.resolveRedefinesEntry(dataDescriptionEntry);
		}
		Program program = dataDescriptionEntry.getProgram();
		ASGElementRegistry asgElementRegistry = program.getASGElementRegistry();
		List<DataDescriptionEntry> callHierarchy = this.collectCallHierarchy(dataDescriptionEntry);
		StringBuffer result = new StringBuffer();
		boolean isFirst = true;
		FileDescriptionEntry fileDescriptionEntry = (FileDescriptionEntry)ANTLRUtils.findParent(FileDescriptionEntry.class, (ParseTree)dataDescriptionEntry.getCtx(), asgElementRegistry);
		if (fileDescriptionEntry != null) {
			result.append(this.mapToCall(fileDescriptionEntry));
			isFirst = false;
		}
		Iterator<CobolParser.SubscriptContext> subCtxIterator = (contextSubscripts = this.findContextSubscripts(dataDescriptionEntryCall, outerCall)) != null ? contextSubscripts.iterator() : Collections.emptyIterator();
		for (int hi = 0; hi < callHierarchy.size(); ++hi) {
			DataDescriptionEntryGroup grp;
			int maxOccurs;
			DataDescriptionEntry currentDataDescriptionEntry = callHierarchy.get(hi);
			if (currentDataDescriptionEntry instanceof DataDescriptionEntryGroup && this.isGroupOverElementaryRedefines((DataDescriptionEntryGroup)currentDataDescriptionEntry) && hi + 1 < callHierarchy.size()) {
				int ci;
				boolean redefGrpHasOccurs;
				DataDescriptionEntryGroup redefGrp = (DataDescriptionEntryGroup)currentDataDescriptionEntry;
				DataDescriptionEntry redefChild = callHierarchy.get(hi + 1);
				int redefGrpMaxOccurs = this.pictureStringService.getMaxOccurs(redefGrp);
				boolean bl = redefGrpHasOccurs = redefGrpMaxOccurs > 1;
				if (redefGrpHasOccurs && subCtxIterator.hasNext()) {
					Integer rChildLenFb;
					Integer rBaseLenObj;
					String rBaseName = redefGrp.getRedefinesClause().getRedefinesCall().getName();
					String rBaseId = this.javaIdentifierService.mapToIdentifier(rBaseName);
					DataDescriptionEntry rBaseDde = this.resolveRedefinesBase(redefGrp, rBaseName);
					Integer n = rBaseLenObj = rBaseDde != null ? this.cobolPictureLengthService.getLength(rBaseDde) : null;
					int rElemSize = rBaseLenObj != null ? rBaseLenObj / redefGrpMaxOccurs : ((rChildLenFb = this.cobolPictureLengthService.getLength(redefChild)) != null ? rChildLenFb : 1);
					int rChildOffset = 0;
					Integer rChildLenObj = this.cobolPictureLengthService.getLength(redefChild);
					int rChildLen = rChildLenObj != null ? rChildLenObj : rElemSize;
					for (DataDescriptionEntry sibling : redefGrp.getDataDescriptionEntries()) {
						Integer sibLen;
						if (sibling == redefChild || sibling.getName().equalsIgnoreCase(redefChild.getName())) break;
						if (sibling.getDataDescriptionEntryType() == DataDescriptionEntry.DataDescriptionEntryType.CONDITION || (sibLen = this.cobolPictureLengthService.getLength(sibling)) == null) continue;
						rChildOffset += sibLen.intValue();
					}
					if (!isFirst) {
						result.append(DOT);
					}
					CobolParser.SubscriptContext rSubCtx = (CobolParser.SubscriptContext)((Object)subCtxIterator.next());
					String rSubText = rSubCtx.getText().trim();
					try {
						int rIdx = Integer.parseInt(rSubText) - 1;
						int rStart = rIdx * rElemSize + rChildOffset;
						int rEnd = rStart + rChildLen;
						result.append(rBaseId).append(".substring(").append(rStart).append(", ").append(rEnd).append(")");
					}
					catch (NumberFormatException e) {
						Object rSubExpr;
						String rArithResult = this.convertRefModExpr(rSubText, program);
						boolean rIsArith = rArithResult.startsWith("(");
						if (rIsArith) {
							rSubExpr = rArithResult;
						} else {
							rSubExpr = null;
							if (program != null) {
								rSubExpr = this.resolveFieldByNameViaASG(rSubText, Collections.emptyList(), program);
							}
							// Text-based fallback for mangled OF/IN
							if ((rSubExpr == null || !((String)rSubExpr).contains(DOT)) && program != null) {
								String rUpperSub = rSubText.toUpperCase();
								int rOfPos = rUpperSub.indexOf("OF");
								int rInPos = rUpperSub.indexOf("IN");
								int rSplitPos = rOfPos > 0 ? rOfPos : (rInPos > 0 ? rInPos : -1);
								String rKw = rOfPos > 0 ? "OF" : "IN";
								if (rSplitPos > 0) {
									String rLeaf = rSubText.substring(0, rSplitPos);
									String rQual = rSubText.substring(rSplitPos + rKw.length());
									if (!rLeaf.isEmpty() && !rQual.isEmpty()) {
										List<String> rQ = new java.util.ArrayList<>();
										rQ.add(rQual.toUpperCase().replace('_', '-'));
										String rRes = this.resolveFieldByNameViaASG(rLeaf, rQ, program);
										if (rRes != null && rRes.contains(DOT)) {
											rSubExpr = rRes;
										}
									}
								}
							}
							if (rSubExpr == null || !((String)rSubExpr).contains(DOT)) {
								rSubExpr = this.javaIdentifierService.mapToIdentifier(rSubText);
							}
							rSubExpr = (String)rSubExpr + ".intValue()";
						}
						result.append(rBaseId).append(".substring((").append((String)rSubExpr).append(" - 1) * ").append(rElemSize);
						if (rChildOffset > 0) {
							result.append(" + ").append(rChildOffset);
						}
						result.append(", (").append((String)rSubExpr).append(" - 1) * ").append(rElemSize);
						result.append(" + ").append(rChildOffset + rChildLen).append(")");
					}
					++hi;
					while (hi + 1 < callHierarchy.size() && this.isNonTrivialGroup(callHierarchy.get(hi + 1))) {
						++hi;
					}
					isFirst = false;
					continue;
				}
				ArrayList<DataDescriptionEntry> consumed = new ArrayList<DataDescriptionEntry>();
				for (ci = hi + 1; ci < callHierarchy.size(); ++ci) {
					consumed.add(callHierarchy.get(ci));
					if (!this.isNonTrivialGroup(callHierarchy.get(ci)) || ci + 1 >= callHierarchy.size()) break;
				}
				String qualifiedCapChildId = this.buildQualifiedChildAccessorName(redefGrp, consumed);
				if (!isFirst) {
					result.append(DOT);
				}
				result.append("get").append(qualifiedCapChildId).append("()");
				hi = ci;
				isFirst = false;
				continue;
			}
			if (!isFirst) {
				result.append(DOT);
			}
			String identifier = this.javaVariableIdentifierService.mapToIdentifier(currentDataDescriptionEntry);
			result.append(identifier);
			if (DataDescriptionEntry.DataDescriptionEntryType.GROUP.equals(currentDataDescriptionEntry.getDataDescriptionEntryType()) && (maxOccurs = this.pictureStringService.getMaxOccurs(grp = (DataDescriptionEntryGroup)currentDataDescriptionEntry)) > 1 && subCtxIterator.hasNext()) {
				boolean isGroupWithChildren = this.cobolDataDescriptionEntryService.hasChildren((DataDescriptionEntry)grp);
				CobolParser.SubscriptContext subCtx = (CobolParser.SubscriptContext)((Object)subCtxIterator.next());
				String subText = subCtx.getText().trim();
				if (isGroupWithChildren) {
					String fieldAccess = result.toString();
					result.setLength(0);
					result.append("io.proleap.cobol.runtime.CobolConstants.safeGet(");
					result.append(fieldAccess);
					result.append(", ");
				} else {
					result.append("[");
				}
				try {
					int idx = Integer.parseInt(subText);
					result.append(idx - 1);
				}
				catch (NumberFormatException e) {
					// First try: parse tree-based resolution via qualifiedDataName
					String subExprQual = null;
					if (subCtx.qualifiedDataName() != null && program != null) {
						CobolParser.QualifiedDataNameContext qdnSub = subCtx.qualifiedDataName();
						String qdnLeaf = null;
						List<String> qdnQuals = new java.util.ArrayList<>();
						if (qdnSub.qualifiedDataNameFormat1() != null) {
							CobolParser.QualifiedDataNameFormat1Context qdnF1 = qdnSub.qualifiedDataNameFormat1();
							qdnLeaf = qdnF1.dataName() != null ? qdnF1.dataName().getText().trim() : null;
							if (qdnF1.qualifiedInData() != null) {
								for (CobolParser.QualifiedInDataContext qdnQid : qdnF1.qualifiedInData()) {
									if (qdnQid.inData() != null && qdnQid.inData().dataName() != null) {
										qdnQuals.add(qdnQid.inData().dataName().getText().trim().toUpperCase().replace('_', '-'));
									}
								}
							}
						}
						if (qdnLeaf != null) {
							subExprQual = this.resolveFieldByNameViaASG(qdnLeaf, qdnQuals, program);
						}
					}
					if (subExprQual != null && subExprQual.contains(DOT)) {
						result.append(subExprQual);
						result.append(".intValue() - 1");
					} else {
						String arithResult = this.convertRefModExpr(subText, program);
						boolean isArith = arithResult.startsWith("(");
						if (isArith) {
							result.append(arithResult);
							result.append(" - 1");
						} else {
							String subExpr = null;
							if (program != null) {
								subExpr = this.resolveFieldByNameViaASG(subText, Collections.emptyList(), program);
							}
							// Text-based fallback for mangled OF/IN
							if ((subExpr == null || !subExpr.contains(DOT)) && program != null) {
								String subUpper = subText.toUpperCase();
								for (String kwSub : new String[]{"OF", "IN"}) {
									int kwSubPos = subUpper.indexOf(kwSub);
									if (kwSubPos > 0) {
										String subLeaf2 = subText.substring(0, kwSubPos);
										String subQual2 = subText.substring(kwSubPos + kwSub.length());
										if (!subLeaf2.isEmpty() && !subQual2.isEmpty()) {
											List<String> subQList = new java.util.ArrayList<>();
											subQList.add(subQual2.toUpperCase().replace('_', '-'));
											String subRes = this.resolveFieldByNameViaASG(subLeaf2, subQList, program);
											if (subRes != null && subRes.contains(DOT)) {
												subExpr = subRes;
												break;
											}
										}
									}
								}
							}
							if (subExpr != null && subExpr.contains(DOT)) {
								result.append(subExpr);
							} else {
								result.append(this.javaIdentifierService.mapToIdentifier(subText));
							}
							result.append(".intValue() - 1");
						}
					}
				}
				if (isGroupWithChildren) {
					result.append(")");
				} else {
					result.append("]");
				}
			}
			isFirst = false;
		}
		// Fix VARCHAR subfield mismatch for DataDescriptionEntryCall: when the ASG
		// resolves a VARCHAR child reference (e.g., NOME-DATA) to the parent group (NOME),
		// the hierarchy includes only the parent. Detect from the outer call name and
		// append the child accessor.
		if (outerCall != null && outerCall.getName() != null) {
			String ddeCallName = outerCall.getName().toUpperCase();
			if (ddeCallName.endsWith("-DATA") || ddeCallName.endsWith("-LENGTH")) {
				if (dataDescriptionEntry != null
						&& DataDescriptionEntry.DataDescriptionEntryType.GROUP.equals(dataDescriptionEntry.getDataDescriptionEntryType())) {
					DataDescriptionEntryGroup ddeVcGroup = (DataDescriptionEntryGroup) dataDescriptionEntry;
					java.util.List<DataDescriptionEntry> ddeVcNonCond = new java.util.ArrayList<>();
					for (DataDescriptionEntry ddeVcChild : ddeVcGroup.getDataDescriptionEntries()) {
						if (ddeVcChild.getDataDescriptionEntryType() != DataDescriptionEntry.DataDescriptionEntryType.CONDITION) {
							ddeVcNonCond.add(ddeVcChild);
						}
					}
					if (ddeVcNonCond.size() == 2) {
						String dn0 = ddeVcNonCond.get(0).getName() != null ? ddeVcNonCond.get(0).getName().toUpperCase() : "";
						String dn1 = ddeVcNonCond.get(1).getName() != null ? ddeVcNonCond.get(1).getName().toUpperCase() : "";
						boolean ddeIsVarchar = (dn0.endsWith("-LENGTH") && dn1.endsWith("-DATA"))
								|| (dn0.endsWith("-DATA") && dn1.endsWith("-LENGTH"))
								|| (dn0.endsWith("-L") && dn1.endsWith("-V"))
								|| (dn0.endsWith("-V") && dn1.endsWith("-L"));
						if (ddeIsVarchar) {
							String ddeSuffix = ddeCallName.endsWith("-DATA") ? "-DATA" : "-LENGTH";
							for (DataDescriptionEntry ddeVcChild : ddeVcNonCond) {
								if (ddeVcChild.getName() != null && ddeVcChild.getName().toUpperCase().endsWith(ddeSuffix)) {
									result.append(DOT);
									result.append(this.javaVariableIdentifierService.mapToIdentifier(ddeVcChild));
									break;
								}
							}
						}
					}
				}
			}
		}
		return result.toString();
	}

	private List<CobolParser.SubscriptContext> findContextSubscripts(DataDescriptionEntryCall call, Call outerCall) {
		CobolParser.QualifiedDataNameContext qdn;
		CobolParser.IdentifierContext identCtx;
		CobolParser.QualifiedDataNameFormat1Context qdf1;
		ParserRuleContext ctx = call.getCtx();
		if (ctx instanceof CobolParser.QualifiedDataNameFormat1Context && (qdf1 = (CobolParser.QualifiedDataNameFormat1Context)ctx).qualifiedInData() != null) {
			for (CobolParser.QualifiedInDataContext qid : qdf1.qualifiedInData()) {
				CobolParser.TableCallContext tc;
				if (qid.inTable() == null || qid.inTable().tableCall() == null || (tc = qid.inTable().tableCall()).subscript() == null || tc.subscript().isEmpty()) continue;
				return tc.subscript();
			}
		}
		while (ctx != null) {
			CobolParser.TableCallContext tableCtx;
			if (ctx instanceof CobolParser.TableCallContext && (tableCtx = (CobolParser.TableCallContext)ctx).subscript() != null && !tableCtx.subscript().isEmpty()) {
				return tableCtx.subscript();
			}
			if (ctx instanceof CobolParser.IdentifierContext) {
				identCtx = (CobolParser.IdentifierContext)ctx;
				if (identCtx.tableCall() == null || identCtx.tableCall().subscript() == null || identCtx.tableCall().subscript().isEmpty()) break;
				return identCtx.tableCall().subscript();
			}
			ctx = ctx.getParent();
		}
		if (outerCall != null && outerCall.getCtx() instanceof CobolParser.IdentifierContext && (identCtx = (CobolParser.IdentifierContext)outerCall.getCtx()).qualifiedDataName() != null && (qdn = identCtx.qualifiedDataName()).qualifiedDataNameFormat1() != null && qdn.qualifiedDataNameFormat1().qualifiedInData() != null) {
			for (CobolParser.QualifiedInDataContext qid : qdn.qualifiedDataNameFormat1().qualifiedInData()) {
				CobolParser.TableCallContext tc;
				if (qid.inTable() == null || qid.inTable().tableCall() == null || (tc = qid.inTable().tableCall()).subscript() == null || tc.subscript().isEmpty()) continue;
				return tc.subscript();
			}
		}
		return null;
	}

	public String mapConditionToCall(DataDescriptionEntry conditionEntry) {
		DataDescriptionEntry resolvedParent = conditionEntry.getParentDataDescriptionEntryGroup();
		if (resolvedParent == null) {
			return this.javaVariableIdentifierService.mapToIdentifier(conditionEntry);
		}
		resolvedParent = this.resolveRedefinesEntry(resolvedParent);
		List<DataDescriptionEntry> parentHierarchy = this.collectCallHierarchy(resolvedParent);
		StringBuffer result = new StringBuffer();
		boolean isFirst = true;
		for (DataDescriptionEntry entry : parentHierarchy) {
			if (!isFirst) {
				result.append(DOT);
			}
			result.append(this.javaVariableIdentifierService.mapToIdentifier(entry));
			isFirst = false;
		}
		return result.toString();
	}

	public String mapConditionToCallWithSubscripts(DataDescriptionEntry conditionEntry, List<String> subscriptExprs) {
		DataDescriptionEntry resolvedParent = conditionEntry.getParentDataDescriptionEntryGroup();
		if (resolvedParent == null) {
			return this.javaVariableIdentifierService.mapToIdentifier(conditionEntry);
		}
		resolvedParent = this.resolveRedefinesEntry(resolvedParent);
		List<DataDescriptionEntry> parentHierarchy = this.collectCallHierarchy(resolvedParent);
		StringBuffer result = new StringBuffer();
		boolean isFirst = true;
		Iterator<String> subIterator = subscriptExprs != null ? subscriptExprs.iterator() : Collections.emptyIterator();
		for (DataDescriptionEntry entry : parentHierarchy) {
			DataDescriptionEntryGroup grp;
			int maxOccurs;
			if (!isFirst) {
				result.append(DOT);
			}
			result.append(this.javaVariableIdentifierService.mapToIdentifier(entry));
			if (entry instanceof DataDescriptionEntryGroup && subIterator.hasNext() && (maxOccurs = this.pictureStringService.getMaxOccurs(grp = (DataDescriptionEntryGroup)entry)) > 1) {
				boolean isGroupWithChildren = this.cobolDataDescriptionEntryService.hasChildren((DataDescriptionEntry)grp);
				String subExpr = (String)subIterator.next();
				if (isGroupWithChildren) {
					String fieldAccess = result.toString();
					result.setLength(0);
					result.append("io.proleap.cobol.runtime.CobolConstants.safeGet(").append(fieldAccess).append(", ").append(subExpr).append(")");
				} else {
					result.append("[").append(subExpr).append("]");
				}
			}
			isFirst = false;
		}
		return result.toString();
	}

	public boolean isConditionFalseValue(DataDescriptionEntry conditionEntry) {
		if (!(conditionEntry instanceof DataDescriptionEntryCondition)) {
			return false;
		}
		DataDescriptionEntryCondition condEntry = (DataDescriptionEntryCondition)conditionEntry;
		ValueClause valueClause = condEntry.getValueClause();
		if (valueClause == null || valueClause.getValueIntervals().isEmpty()) {
			return false;
		}
		ValueInterval interval = valueClause.getValueIntervals().get(0);
		ValueStmt fromValueStmt = interval.getFromValueStmt();
		if (fromValueStmt == null) {
			return false;
		}
		CobolValue value = this.valueStmtService.getValue(fromValueStmt, null);
		Boolean boolVal = this.valueService.getBoolean(value);
		if (boolVal != null) {
			return boolVal == false;
		}
		BigDecimal decVal = this.valueService.getDecimal(value);
		if (decVal != null) {
			return decVal.compareTo(BigDecimal.ZERO) == 0;
		}
		return false;
	}

	public String mapToCall(FileDescriptionEntry fileDescriptionEntry) {
		return this.javaFileDescriptionEntryIdentifierService.mapToIdentifier(fileDescriptionEntry);
	}

	public String mapToCall(SpecialRegisterCall specialRegisterCall) {
		SpecialRegisterCall.SpecialRegisterType type = specialRegisterCall.getSpecialRegisterType();
		StringBuffer result = new StringBuffer();
		if (type == null) {
			// IBM ILE COBOL XML special registers — map to runtime fields
			// in CobolProgram (xml_event, xml_text, xml_ntext, xml_code).
			// getName() returns null for special registers (CallImpl constructor
			// receives null), so we check the parse tree context tokens instead.
			final org.antlr.v4.runtime.ParserRuleContext ctx = specialRegisterCall.getCtx();
			if (ctx instanceof io.proleap.cobol.CobolParser.SpecialRegisterContext) {
				final io.proleap.cobol.CobolParser.SpecialRegisterContext srCtx =
						(io.proleap.cobol.CobolParser.SpecialRegisterContext) ctx;
				if (srCtx.XML_EVENT() != null) {
					result.append("xml_event");
					return result.toString();
				}
				if (srCtx.XML_TEXT() != null) {
					result.append("xml_text");
					return result.toString();
				}
				if (srCtx.XML_NTEXT() != null) {
					result.append("xml_ntext");
					return result.toString();
				}
				if (srCtx.XML_CODE() != null) {
					result.append("xml_code");
					return result.toString();
				}
			}
			// Fallback: try name, then context text
			final String name = specialRegisterCall.getName();
			if (name != null) {
				result.append(name.toLowerCase().replace('-', '_'));
			} else if (ctx != null) {
				result.append(ctx.getText().toLowerCase().replace('-', '_'));
			} else {
				result.append("\"\"");
			}
			return result.toString();
		}
		switch (type) {
			case ADDRESS_OF: {
				result.append("entityService.getAddress(");
				result.append(this.mapToCall(specialRegisterCall.getIdentifierCall()));
				result.append(")");
				break;
			}
			case LENGTH_OF: {
				DataDescriptionEntry dde;
				Call unwrapped;
				Call identifierCall = specialRegisterCall.getIdentifierCall();
				Integer picLength = null;
				if (identifierCall != null && (unwrapped = identifierCall.unwrap()) instanceof DataDescriptionEntryCall && (dde = ((DataDescriptionEntryCall)unwrapped).getDataDescriptionEntry()) != null) {
					picLength = this.cobolPictureLengthService.getLength(dde);
				}
				if (picLength != null) {
					result.append("BigDecimal.valueOf(").append(picLength).append(")");
					break;
				}
				result.append("BigDecimal.valueOf(entityService.getLength(");
				result.append(this.mapToCall(identifierCall));
				result.append("))");
				break;
			}
			case SORT_RETURN: {
				result.append("sort_return");
				break;
			}
			case RETURN_CODE: {
				result.append("return_code");
				break;
			}
			default: {
				// Unhandled but non-null type — emit as variable name
				String name = specialRegisterCall.getName();
				if (name != null) {
					result.append(name.toLowerCase().replace('-', '_'));
				} else {
					result.append(type.name().toLowerCase());
				}
				break;
			}
		}
		return result.toString();
	}

	public String mapToCall(TableCall tableCall) {
		CobolParser.TableCallContext tableCtx;
		Program tableCallProgram;
		CobolParser.IdentifierContext idCtx;
		List<Subscript> subscripts = tableCall.getSubscripts();
		Iterator<Subscript> subscriptIterator = subscripts.iterator();
		DataDescriptionEntry resolvedEntry = tableCall.getDataDescriptionEntry();
		resolvedEntry = this.resolveRedefinesEntry(resolvedEntry);
		CobolParser.TableCallContext tableCtxForSubscripts = null;
		if (tableCall.getCtx() instanceof CobolParser.TableCallContext) {
			tableCtxForSubscripts = (CobolParser.TableCallContext)tableCall.getCtx();
		} else if (tableCall.getCtx() instanceof CobolParser.IdentifierContext && (idCtx = (CobolParser.IdentifierContext)tableCall.getCtx()).tableCall() != null) {
			tableCtxForSubscripts = idCtx.tableCall();
		}
		List parseTreeSubscripts = tableCtxForSubscripts != null && tableCtxForSubscripts.subscript() != null ? tableCtxForSubscripts.subscript() : Collections.emptyList();
		Program program = tableCallProgram = tableCall.getDataDescriptionEntry() != null ? tableCall.getDataDescriptionEntry().getProgram() : null;
		if (DataDescriptionEntry.DataDescriptionEntryType.CONDITION.equals(resolvedEntry.getDataDescriptionEntryType())) {
			CobolTypeEnum parentType;
			ArrayList<String> condSubscriptExprs = new ArrayList<String>();
			int condSubIdx = 0;
			for (Subscript subscript : subscripts) {
				CobolParser.SubscriptContext condSubCtx = condSubIdx < parseTreeSubscripts.size() ? (CobolParser.SubscriptContext)((Object)parseTreeSubscripts.get(condSubIdx)) : null;
				String indexArith = this.resolveIndexArithmeticSubscript(condSubCtx, tableCallProgram);
				++condSubIdx;
				if (indexArith != null) {
					condSubscriptExprs.add(indexArith);
					continue;
				}
				CobolValue subscriptValue = this.valueStmtService.getValue(subscript.getSubscriptValueStmt(), null);
				BigDecimal decimalSubscriptValue = this.valueService.getDecimal(subscriptValue);
				if (decimalSubscriptValue != null) {
					condSubscriptExprs.add(String.format("%d", decimalSubscriptValue.intValue() - 1));
					continue;
				}
				// Try parsing the subscript text directly as a number (handles cases
				// where the value service can't resolve it, e.g., literal "03")
				String subExprText = this.mapToExpression(subscript.getSubscriptValueStmt());
				try {
					int literalIdx = Integer.parseInt(subExprText.trim());
					condSubscriptExprs.add(String.format("%d", literalIdx - 1));
				} catch (NumberFormatException e) {
					condSubscriptExprs.add(subExprText + ".intValue() - 1");
				}
			}
			String parentPath = !condSubscriptExprs.isEmpty() ? this.mapConditionToCallWithSubscripts(resolvedEntry, condSubscriptExprs) : this.mapConditionToCall(resolvedEntry);
			DataDescriptionEntryGroup parent = resolvedEntry.getParentDataDescriptionEntryGroup();
			if (parent != null && CobolTypeEnum.BOOLEAN.equals((parentType = this.cobolTypeService.getType((DataDescriptionEntry)parent)))) {
				String boolExpr = parentPath;
				if (parent.getRedefinesClause() != null && parent.getRedefinesClause().getRedefinesCall() != null) {
					String parentId = this.javaVariableIdentifierService.mapToIdentifier(parent);
					boolExpr = "get" + Character.toUpperCase(parentId.charAt(0)) + parentId.substring(1) + "()";
				}
				if (this.isConditionFalseValue(resolvedEntry)) {
					return "!" + boolExpr;
				}
				return boolExpr;
			}
			return parentPath;
		}
		List<DataDescriptionEntry> callHierarchy = this.collectCallHierarchy(resolvedEntry);
		if (resolvedEntry.getName() != null && resolvedEntry.getName().toUpperCase().contains("STATUS-CUR")) {
			for (int di = 0; di < callHierarchy.size(); ++di) {
				DataDescriptionEntry dde = callHierarchy.get(di);
				boolean hasOccurs = false;
				boolean isRedefines = false;
				if (dde instanceof DataDescriptionEntryGroup) {
					hasOccurs = this.pictureStringService.getMaxOccurs((DataDescriptionEntryGroup)dde) > 1;
					isRedefines = ((DataDescriptionEntryGroup)dde).getRedefinesClause() != null;
				}
			}
		}
		ArrayList<Integer> occursIndices = new ArrayList<Integer>();
		for (int oi = 0; oi < callHierarchy.size(); ++oi) {
			int nOccurs;
			DataDescriptionEntry dde = callHierarchy.get(oi);
			if (!(dde instanceof DataDescriptionEntryGroup) || (nOccurs = this.pictureStringService.getMaxOccurs((DataDescriptionEntryGroup)dde)) <= 1) continue;
			occursIndices.add(oi);
		}
		java.util.HashMap<Integer, Integer> occursToSubscriptMap = new java.util.HashMap<Integer, Integer>();
		if (occursIndices.size() > 1 && subscripts.size() == occursIndices.size()) {
			for (int mi = 0; mi < occursIndices.size(); ++mi) {
				occursToSubscriptMap.put((Integer)occursIndices.get(mi), mi);
			}
		}
		StringBuffer result = new StringBuffer();
		boolean isFirst = true;
		int mainSubIdx = 0;
		for (int hi = 0; hi < callHierarchy.size(); ++hi) {
			DataDescriptionEntry dataDescriptionEntry = callHierarchy.get(hi);
			if (dataDescriptionEntry instanceof DataDescriptionEntryGroup && this.isGroupOverElementaryRedefines((DataDescriptionEntryGroup)dataDescriptionEntry) && hi + 1 < callHierarchy.size()) {
				DataDescriptionEntryGroup redefinesGroup = (DataDescriptionEntryGroup)dataDescriptionEntry;
				DataDescriptionEntry childEntry = callHierarchy.get(hi + 1);
				boolean childHasOccurs = false;
				int childMaxOccurs = 1;
				if (childEntry instanceof DataDescriptionEntryGroup) {
					childMaxOccurs = this.pictureStringService.getMaxOccurs((DataDescriptionEntryGroup)childEntry);
					boolean bl = childHasOccurs = childMaxOccurs > 1;
				}
				if (childHasOccurs && subscriptIterator.hasNext()) {
					Integer childLenObj = this.cobolPictureLengthService.getLength(childEntry);
					// When the OCCURS child is a group without a PIC clause, getLength
					// returns null. Compute element size from base field length / maxOccurs.
					if (childLenObj == null && childEntry instanceof DataDescriptionEntryGroup) {
						String bn = redefinesGroup.getRedefinesClause().getRedefinesCall().getName();
						DataDescriptionEntry bd = this.resolveRedefinesBase(redefinesGroup, bn);
						Integer bl2 = bd != null ? this.cobolPictureLengthService.getLength(bd) : null;
						if (bl2 != null && childMaxOccurs > 0) {
							childLenObj = bl2 / childMaxOccurs;
						} else {
							// Last resort: sum children lengths
							int sum = 0;
							boolean allResolved = true;
							for (DataDescriptionEntry ch : ((DataDescriptionEntryGroup)childEntry).getDataDescriptionEntries()) {
								if (ch.getDataDescriptionEntryType() == DataDescriptionEntry.DataDescriptionEntryType.CONDITION) continue;
								Integer chLen = this.cobolPictureLengthService.getLength(ch);
								if (chLen != null) { sum += chLen; } else { allResolved = false; break; }
							}
							if (allResolved && sum > 0) childLenObj = sum;
						}
					}
					int elemLen = childLenObj != null ? childLenObj : 1;
					String baseName = redefinesGroup.getRedefinesClause().getRedefinesCall().getName();
					String baseId = this.javaIdentifierService.mapToIdentifier(baseName);
					// When the redefined field is numeric (BigDecimal), convert to digit string
					// before calling .substring(). Numeric fields don't have substring().
					boolean baseIsNumeric = false;
					Integer baseLength = null;
					io.proleap.cobol.asg.metamodel.call.Call redefCall = redefinesGroup.getRedefinesClause().getRedefinesCall();
					if (redefCall instanceof io.proleap.cobol.asg.metamodel.call.DataDescriptionEntryCall) {
						DataDescriptionEntry redefinedEntry = ((io.proleap.cobol.asg.metamodel.call.DataDescriptionEntryCall)redefCall).getDataDescriptionEntry();
						CobolTypeEnum redefinedType = this.cobolTypeService.getType(redefinedEntry);
						if (CobolTypeEnum.INTEGER.equals(redefinedType) || CobolTypeEnum.FLOAT.equals(redefinedType)) {
							baseIsNumeric = true;
							baseLength = this.cobolPictureLengthService.getLength(redefinedEntry);
						}
					}
					String baseExprStr;
					if (baseIsNumeric) {
						int bLen = baseLength != null ? baseLength : elemLen;
						// Build the full qualified path to the numeric field
						String fullBaseId = isFirst ? baseId : result.toString() + DOT + baseId;
						baseExprStr = "CobolMove.numericToDigitString(" + fullBaseId + ", " + bLen + ", 0)";
						// Reset result since we've incorporated it into the wrapper
						result.setLength(0);
						isFirst = true;
					} else {
						baseExprStr = baseId;
					}
					if (!isFirst) {
						result.append(DOT);
					}

					// Compute leaf field offset/length within the OCCURS element.
					// When the hierarchy has entries AFTER the OCCURS child (e.g.,
					// Milhares -> EltMilhs(OCCURS) -> EMilDesc), the leaf field is a
					// sub-portion of the element. We need to adjust the substring to
					// extract only the leaf field, not the entire element.
					int leafOffset = 0;
					int leafLen = elemLen;
					int leafSkipCount = 0;
					boolean leafIsNumeric = false;
					if (hi + 2 < callHierarchy.size()) {
						// Find the deepest leaf entry in the remaining hierarchy
						DataDescriptionEntry leafEntry = callHierarchy.get(callHierarchy.size() - 1);
						// Compute offset by walking siblings of the OCCURS child (childEntry)
						// up to the leaf (or the intermediate group containing it)
						DataDescriptionEntry targetInChild = callHierarchy.get(hi + 2);
						if (childEntry instanceof DataDescriptionEntryGroup) {
							for (DataDescriptionEntry sibling : ((DataDescriptionEntryGroup)childEntry).getDataDescriptionEntries()) {
								if (sibling == targetInChild || (sibling.getName() != null && targetInChild.getName() != null && sibling.getName().equalsIgnoreCase(targetInChild.getName()))) {
									break;
								}
								if (sibling.getDataDescriptionEntryType() != DataDescriptionEntry.DataDescriptionEntryType.CONDITION) {
									Integer sibLen = this.cobolPictureLengthService.getLength(sibling);
									if (sibLen != null) {
										leafOffset += sibLen;
									}
								}
							}
						}
						// If the target within the child has further children (intermediate group),
						// walk deeper to accumulate offset
						for (int lfi = hi + 2; lfi < callHierarchy.size() - 1; lfi++) {
							DataDescriptionEntry lfParent = callHierarchy.get(lfi);
							DataDescriptionEntry lfChild = callHierarchy.get(lfi + 1);
							if (lfParent instanceof DataDescriptionEntryGroup) {
								for (DataDescriptionEntry sibling : ((DataDescriptionEntryGroup)lfParent).getDataDescriptionEntries()) {
									if (sibling == lfChild || (sibling.getName() != null && lfChild.getName() != null && sibling.getName().equalsIgnoreCase(lfChild.getName()))) {
										break;
									}
									if (sibling.getDataDescriptionEntryType() != DataDescriptionEntry.DataDescriptionEntryType.CONDITION) {
										Integer sibLen = this.cobolPictureLengthService.getLength(sibling);
										if (sibLen != null) {
											leafOffset += sibLen;
										}
									}
								}
							}
						}
						Integer leafLenObj = this.cobolPictureLengthService.getLength(leafEntry);
						if (leafLenObj != null) {
							leafLen = leafLenObj;
						}
						// Check if the leaf field is numeric — substring returns String,
						// but callers (ADD, COMPUTE, etc.) expect BigDecimal for numeric fields.
						CobolTypeEnum leafType = this.cobolTypeService.getType(leafEntry);
						leafIsNumeric = CobolTypeEnum.INTEGER.equals(leafType) || CobolTypeEnum.FLOAT.equals(leafType);
						leafSkipCount = callHierarchy.size() - 1 - (hi + 1);
					}

					Subscript subscript = subscriptIterator.next();
					ValueStmt subscriptValueStmt = subscript.getSubscriptValueStmt();
					CobolParser.SubscriptContext subCtx = mainSubIdx < parseTreeSubscripts.size() ? (CobolParser.SubscriptContext)((Object)parseTreeSubscripts.get(mainSubIdx)) : null;
					String indexArith = this.resolveIndexArithmeticSubscript(subCtx, tableCallProgram);
					++mainSubIdx;
					CobolValue subscriptValue = this.valueStmtService.getValue(subscriptValueStmt, null);
					BigDecimal decimalSubscriptValue = this.valueService.getDecimal(subscriptValue);
					if (leafIsNumeric) {
						result.append("new BigDecimal(");
					}
					if (indexArith != null) {
						result.append(baseExprStr).append(".substring((").append(indexArith).append(") * ").append(elemLen);
						if (leafOffset > 0) {
							result.append(" + ").append(leafOffset);
						}
						result.append(", (").append(indexArith).append(") * ").append(elemLen).append(" + ").append(leafOffset + leafLen).append(")");
					} else if (decimalSubscriptValue != null) {
						int idx = decimalSubscriptValue.intValue() - 1;
						int start = idx * elemLen + leafOffset;
						int end = start + leafLen;
						result.append(baseExprStr).append(".substring(").append(start).append(", ").append(end).append(")");
					} else {
						String subExpr = this.mapToExpression(subscriptValueStmt);
						result.append(baseExprStr).append(".substring((").append(subExpr).append(".intValue() - 1) * ").append(elemLen);
						if (leafOffset > 0) {
							result.append(" + ").append(leafOffset);
						}
						result.append(", (").append(subExpr).append(".intValue() - 1) * ").append(elemLen).append(" + ").append(leafOffset + leafLen).append(")");
					}
					if (leafIsNumeric) {
						result.append(".trim())");
					}
					// Skip past the child entry and any remaining leaf entries
					// — the substring already extracts the specific leaf field data
					hi += 1 + leafSkipCount;
				} else {
					boolean redefinesGroupHasOccurs;
					int redefinesGroupMaxOccurs = this.pictureStringService.getMaxOccurs(redefinesGroup);
					boolean bl = redefinesGroupHasOccurs = redefinesGroupMaxOccurs > 1;
					if (redefinesGroup.getName() != null && redefinesGroup.getName().toUpperCase().contains("DESCLONG")) {
					}
					if (redefinesGroupHasOccurs && subscriptIterator.hasNext()) {
						Integer rChildLenFb;
						Integer rBaseLenObj;
						String rBaseName = redefinesGroup.getRedefinesClause().getRedefinesCall().getName();
						String rBaseId = this.javaIdentifierService.mapToIdentifier(rBaseName);
						DataDescriptionEntry rBaseDde = this.resolveRedefinesBase(redefinesGroup, rBaseName);
						Integer n = rBaseLenObj = rBaseDde != null ? this.cobolPictureLengthService.getLength(rBaseDde) : null;
						int rElemSize = rBaseLenObj != null ? rBaseLenObj / redefinesGroupMaxOccurs : ((rChildLenFb = this.cobolPictureLengthService.getLength(childEntry)) != null ? rChildLenFb : 1);
						int rChildOffset = 0;
						Integer rChildLenObj = this.cobolPictureLengthService.getLength(childEntry);
						int rChildLen = rChildLenObj != null ? rChildLenObj : rElemSize;
						for (DataDescriptionEntry sibling : redefinesGroup.getDataDescriptionEntries()) {
							Integer sibLen;
							if (sibling == childEntry || sibling.getName().equalsIgnoreCase(childEntry.getName())) break;
							if (sibling.getDataDescriptionEntryType() == DataDescriptionEntry.DataDescriptionEntryType.CONDITION || (sibLen = this.cobolPictureLengthService.getLength(sibling)) == null) continue;
							rChildOffset += sibLen.intValue();
						}
						if (!isFirst) {
							result.append(DOT);
						}
						Subscript rSubscript = subscriptIterator.next();
						ValueStmt rSubscriptValueStmt = rSubscript.getSubscriptValueStmt();
						CobolParser.SubscriptContext rSubCtx = mainSubIdx < parseTreeSubscripts.size() ? (CobolParser.SubscriptContext)((Object)parseTreeSubscripts.get(mainSubIdx)) : null;
						String rIndexArith = this.resolveIndexArithmeticSubscript(rSubCtx, tableCallProgram);
						++mainSubIdx;
						CobolValue rSubscriptValue = this.valueStmtService.getValue(rSubscriptValueStmt, null);
						BigDecimal rDecimalSubscriptValue = this.valueService.getDecimal(rSubscriptValue);
						if (rIndexArith != null) {
							result.append(rBaseId).append(".substring((").append(rIndexArith).append(") * ").append(rElemSize);
							if (rChildOffset > 0) {
								result.append(" + ").append(rChildOffset);
							}
							result.append(", (").append(rIndexArith).append(") * ").append(rElemSize);
							result.append(" + ").append(rChildOffset + rChildLen).append(")");
						} else if (rDecimalSubscriptValue != null) {
							int rIdx = rDecimalSubscriptValue.intValue() - 1;
							int rStart = rIdx * rElemSize + rChildOffset;
							int rEnd = rStart + rChildLen;
							result.append(rBaseId).append(".substring(").append(rStart).append(", ").append(rEnd).append(")");
						} else {
							String rSubExpr = this.mapToExpression(rSubscriptValueStmt);
							result.append(rBaseId).append(".substring((").append(rSubExpr).append(".intValue() - 1) * ").append(rElemSize);
							if (rChildOffset > 0) {
								result.append(" + ").append(rChildOffset);
							}
							result.append(", (").append(rSubExpr).append(".intValue() - 1) * ").append(rElemSize);
							result.append(" + ").append(rChildOffset + rChildLen).append(")");
						}
						++hi;
						while (hi + 1 < callHierarchy.size() && this.isNonTrivialGroup(callHierarchy.get(hi + 1))) {
							++hi;
						}
					} else {
						int ci;
						ArrayList<DataDescriptionEntry> consumed = new ArrayList<DataDescriptionEntry>();
						for (ci = hi + 1; ci < callHierarchy.size(); ++ci) {
							consumed.add(callHierarchy.get(ci));
							if (!this.isNonTrivialGroup(callHierarchy.get(ci)) || ci + 1 >= callHierarchy.size()) break;
						}
						String qualifiedCapChildId = this.buildQualifiedChildAccessorName(redefinesGroup, consumed);
						if (!isFirst) {
							result.append(DOT);
						}
						result.append("get").append(qualifiedCapChildId).append("()");
						hi = ci;
					}
				}
				isFirst = false;
				continue;
			}
			if (!isFirst) {
				result.append(DOT);
			}
			result.append(this.javaVariableIdentifierService.mapToIdentifier(dataDescriptionEntry));
			DataDescriptionEntry.DataDescriptionEntryType dataDescriptionEntryType = dataDescriptionEntry.getDataDescriptionEntryType();
			if (DataDescriptionEntry.DataDescriptionEntryType.GROUP.equals(dataDescriptionEntryType)) {
				DataDescriptionEntryGroup dataDescriptionEntryGroup = (DataDescriptionEntryGroup)dataDescriptionEntry;
				int numberOfInstances = this.pictureStringService.getMaxOccurs(dataDescriptionEntryGroup);
				if (subscriptIterator.hasNext() && numberOfInstances > 1) {
					boolean isGroupWithChildren = this.cobolDataDescriptionEntryService.hasChildren((DataDescriptionEntry)dataDescriptionEntryGroup);
					int effectiveSubIdx = occursToSubscriptMap.containsKey(hi) ? (Integer)occursToSubscriptMap.get(hi) : mainSubIdx;
					Subscript subscript = subscripts.get(effectiveSubIdx);
					if (subscriptIterator.hasNext()) {
						subscriptIterator.next();
					}
					ValueStmt subscriptValueStmt = subscript.getSubscriptValueStmt();
					CobolParser.SubscriptContext mainSubCtx = effectiveSubIdx < parseTreeSubscripts.size() ? (CobolParser.SubscriptContext)((Object)parseTreeSubscripts.get(effectiveSubIdx)) : null;
					String mainIndexArith = this.resolveIndexArithmeticSubscript(mainSubCtx, tableCallProgram);
					++mainSubIdx;
					if (mainIndexArith != null) {
						if (isGroupWithChildren) {
							String fieldAccess = result.toString();
							result.setLength(0);
							result.append("io.proleap.cobol.runtime.CobolConstants.safeGet(");
							result.append(fieldAccess);
							result.append(", ");
							result.append(mainIndexArith);
							result.append(")");
						} else {
							result.append("[");
							result.append(mainIndexArith);
							result.append("]");
						}
					} else {
						CobolValue subscriptValue = this.valueStmtService.getValue(subscriptValueStmt, null);
						BigDecimal decimalSubscriptValue = this.valueService.getDecimal(subscriptValue);
						// Fallback: try parsing the expression text as a literal number
						if (decimalSubscriptValue == null) {
							String subExprCheck = this.mapToExpression(subscriptValueStmt).trim();
							try {
								decimalSubscriptValue = new BigDecimal(Integer.parseInt(subExprCheck));
							} catch (NumberFormatException ignored) {
							}
						}
						if (isGroupWithChildren) {
							String fieldAccess = result.toString();
							result.setLength(0);
							result.append("io.proleap.cobol.runtime.CobolConstants.safeGet(");
							result.append(fieldAccess);
							result.append(", ");
							if (decimalSubscriptValue != null) {
								result.append(String.format("%d", decimalSubscriptValue.intValue() - 1));
							} else {
								String subExprStr = this.mapToExpression(subscriptValueStmt);
								result.append(this.generateSubscriptIndexExpr(subscriptValueStmt, subExprStr));
							}
							result.append(")");
						} else {
							result.append("[");
							if (decimalSubscriptValue != null) {
								result.append(String.format("%d", decimalSubscriptValue.intValue() - 1));
							} else {
								String subExprStr = this.mapToExpression(subscriptValueStmt);
								result.append(this.generateSubscriptIndexExpr(subscriptValueStmt, subExprStr));
							}
							result.append("]");
						}
					}
				}
			}
			isFirst = false;
		}
		if (subscriptIterator.hasNext()) {
			Subscript subscript = subscriptIterator.next();
			ValueStmt subscriptValueStmt = subscript.getSubscriptValueStmt();
			CobolParser.SubscriptContext uncSubCtx = mainSubIdx < parseTreeSubscripts.size() ? (CobolParser.SubscriptContext)((Object)parseTreeSubscripts.get(mainSubIdx)) : null;
			String uncIndexArith = this.resolveIndexArithmeticSubscript(uncSubCtx, tableCallProgram);
			++mainSubIdx;
			result.append("[");
			if (uncIndexArith != null) {
				result.append(uncIndexArith);
			} else {
				CobolValue subscriptValue = this.valueStmtService.getValue(subscriptValueStmt, null);
				BigDecimal decimalSubscriptValue = this.valueService.getDecimal(subscriptValue);
				if (decimalSubscriptValue == null) {
					String uncSubExprCheck = this.mapToExpression(subscriptValueStmt).trim();
					try {
						decimalSubscriptValue = new BigDecimal(Integer.parseInt(uncSubExprCheck));
					} catch (NumberFormatException ignored) {
					}
				}
				if (decimalSubscriptValue != null) {
					result.append(String.format("%d", decimalSubscriptValue.intValue() - 1));
				} else {
					result.append(this.mapToExpression(subscriptValueStmt));
					result.append(".intValue() - 1");
				}
			}
			result.append("]");
		}
		CobolParser.TableCallContext tableCallContext = tableCtx = tableCall.getCtx() instanceof CobolParser.TableCallContext ? (CobolParser.TableCallContext)tableCall.getCtx() : null;
		if (tableCtx == null && tableCtxForSubscripts != null) {
			tableCtx = tableCtxForSubscripts;
		}
		if (tableCtx != null && tableCtx.referenceModifier() != null) {
			CobolParser.ReferenceModifierContext refMod = tableCtx.referenceModifier();
			Program refModProg = tableCall.getDataDescriptionEntry() != null ? tableCall.getDataDescriptionEntry().getProgram() : null;
			String posExpr = this.convertArithExprCtxToJavaInt(refMod.characterPosition().arithmeticExpression(), refModProg);
			String baseExpr = result.toString();
			result.setLength(0);
			if (refMod.length() != null) {
				String lenExpr = this.convertArithExprCtxToJavaInt(refMod.length().arithmeticExpression(), refModProg);
				result.append("CobolReference.referenceModification(");
				result.append(baseExpr);
				result.append(", ");
				result.append(posExpr);
				result.append(", ");
				result.append(lenExpr);
				result.append(")");
			} else {
				result.append("CobolReference.referenceModification(");
				result.append(baseExpr);
				result.append(", ");
				result.append(posExpr);
				result.append(")");
			}
		}
		// Fix VARCHAR subfield mismatch: when the ASG resolves a VARCHAR child
		// reference (e.g., NOME-DATA) to the parent group (NOME), the hierarchy
		// includes only the parent. The call name still has the original "-DATA"
		// or "-LENGTH" suffix, so detect the mismatch and append the child accessor.
		{
			String tcCallName = tableCall.getName();
			if (tcCallName != null) {
				String tcUpperName = tcCallName.toUpperCase();
				if (tcUpperName.endsWith("-DATA") || tcUpperName.endsWith("-LENGTH")) {
					// Check if resolvedEntry is the VARCHAR parent group (not the child)
					if (resolvedEntry != null
							&& DataDescriptionEntry.DataDescriptionEntryType.GROUP.equals(resolvedEntry.getDataDescriptionEntryType())) {
						DataDescriptionEntryGroup vcGroup = (DataDescriptionEntryGroup) resolvedEntry;
						java.util.List<DataDescriptionEntry> vcNonCond = new java.util.ArrayList<>();
						for (DataDescriptionEntry vcChild : vcGroup.getDataDescriptionEntries()) {
							if (vcChild.getDataDescriptionEntryType() != DataDescriptionEntry.DataDescriptionEntryType.CONDITION) {
								vcNonCond.add(vcChild);
							}
						}
						if (vcNonCond.size() == 2) {
							String n0 = vcNonCond.get(0).getName() != null ? vcNonCond.get(0).getName().toUpperCase() : "";
							String n1 = vcNonCond.get(1).getName() != null ? vcNonCond.get(1).getName().toUpperCase() : "";
							boolean isVarchar = (n0.endsWith("-LENGTH") && n1.endsWith("-DATA"))
									|| (n0.endsWith("-DATA") && n1.endsWith("-LENGTH"))
									|| (n0.endsWith("-L") && n1.endsWith("-V"))
									|| (n0.endsWith("-V") && n1.endsWith("-L"));
							if (isVarchar) {
								String suffix = tcUpperName.endsWith("-DATA") ? "-DATA" : "-LENGTH";
								for (DataDescriptionEntry vcChild : vcNonCond) {
									if (vcChild.getName() != null && vcChild.getName().toUpperCase().endsWith(suffix)) {
										result.append(DOT);
										result.append(this.javaVariableIdentifierService.mapToIdentifier(vcChild));
										break;
									}
								}
							}
						}
					}
				}
			}
		}
		return result.toString();
	}

	public String mapToExpression(AndOrCondition andOrCondition) {
		return this.mapToExpression(andOrCondition, null);
	}

	public String mapToExpression(AndOrCondition andOrCondition, String abbreviationSubject) {
		return this.mapToExpression(andOrCondition, abbreviationSubject, null, false);
	}

	public String mapToExpression(AndOrCondition andOrCondition, String abbreviationSubject, RelationalOperator.RelationalOperatorType inheritedOp, boolean isNumericSubject) {
		Boolean value = this.valueService.getBoolean(this.valueStmtService.getValue((ValueStmt)andOrCondition, null));
		StringBuffer result = new StringBuffer();
		if (value != null) {
			result.append(JavaLiteralUtils.mapToLiteral((Boolean)value));
		} else {
			String conditionExpr = null;
			if (andOrCondition.getCombinableCondition() != null) {
				if (abbreviationSubject != null && inheritedOp != null && this.isNonBooleanDataReference(andOrCondition.getCombinableCondition())) {
					Call rhsCall = andOrCondition.getCombinableCondition().getSimpleCondition().getConditionNameReference().getConditionCall();
					String rhsExpr = this.mapToCall(rhsCall);
					// Check for lost subscripts in abbreviated conditions:
					// When a subscripted field like FIELD(IDX) is used as an abbreviated operand,
					// the ASG's conditionNameReference may lose the subscript. Detect this by
					// checking the parse tree's conditionNameSubscriptReference.
					if (!rhsExpr.contains("safeGet(") && !rhsExpr.contains("[")
							&& andOrCondition.getCombinableCondition().getSimpleCondition().getConditionNameReference() != null
							&& andOrCondition.getCombinableCondition().getSimpleCondition().getConditionNameReference().getCtx() != null) {
						CobolParser.ConditionNameReferenceContext cnrCtx = (CobolParser.ConditionNameReferenceContext) andOrCondition.getCombinableCondition().getSimpleCondition().getConditionNameReference().getCtx();
						if (cnrCtx.conditionNameSubscriptReference() != null && !cnrCtx.conditionNameSubscriptReference().isEmpty()) {
							// There are subscripts in the parse tree that were lost in the ASG call
							for (CobolParser.ConditionNameSubscriptReferenceContext subRef : cnrCtx.conditionNameSubscriptReference()) {
								if (subRef.subscript() != null && !subRef.subscript().isEmpty()) {
									// Get the field name and resolve with subscripts
									Program prog = rhsCall.getProgram();
									if (prog != null) {
										String fieldName = rhsCall.getName();
										String resolvedBase = this.resolveFieldByNameViaASG(fieldName, Collections.emptyList(), prog);
										if (resolvedBase != null && resolvedBase.contains(DOT)) {
											// Resolve with subscripts
											String resolvedWithSubs = this.resolveFieldByNameViaASGWithSubscripts(fieldName, Collections.emptyList(), subRef.subscript(), prog);
											if (resolvedWithSubs != null && resolvedWithSubs.contains(DOT)) {
												rhsExpr = resolvedWithSubs;
											}
										}
									}
								}
							}
						}
					}
					boolean rhsIsNumeric = this.isNumericCall(rhsCall);
					if (!isNumericSubject && rhsIsNumeric) {
						rhsExpr = this.numericCallToDisplayString(rhsCall, rhsExpr);
					}
					// When subject is numeric but RHS is alphanumeric, force alphanumeric comparison
					// to avoid calling .compareTo(BigDecimal) on a String variable
					boolean effectiveNumSubject = isNumericSubject && rhsIsNumeric;
					conditionExpr = this.buildComparison(abbreviationSubject, inheritedOp, rhsExpr, effectiveNumSubject);
				} else {
					conditionExpr = this.mapToExpression(andOrCondition.getCombinableCondition());
				}
			} else if (andOrCondition.getAbbreviations() != null && !andOrCondition.getAbbreviations().isEmpty() && abbreviationSubject != null) {
				conditionExpr = this.expandAbbreviations(andOrCondition.getAbbreviations(), abbreviationSubject, inheritedOp, isNumericSubject);
			}
			if (conditionExpr != null && !conditionExpr.isEmpty()) {
				AndOrCondition.AndOrConditionType type = andOrCondition.getAndOrConditionType();
				switch (type) {
					case AND: {
						result.append(" && ");
						break;
					}
					case OR: {
						result.append(" || ");
						break;
					}
				}
				result.append(conditionExpr);
			}
		}
		return result.toString();
	}

	private boolean isNumericCall(Call call) {
		if (call == null) {
			return false;
		}
		if (call.getCallType() == Call.CallType.DATA_DESCRIPTION_ENTRY_CALL || call.getCallType() == Call.CallType.TABLE_CALL) {
			DataDescriptionEntryCall ddeCall = (DataDescriptionEntryCall)call.unwrap();
			DataDescriptionEntry dde = ddeCall.getDataDescriptionEntry();
			CobolTypeEnum ddeType = this.cobolTypeService.getType(dde);
			return CobolTypeEnum.INTEGER.equals(ddeType) || CobolTypeEnum.FLOAT.equals(ddeType);
		}
		return false;
	}

	private String numericToDisplayString(ArithmeticValueStmt numericExpr, String javaExpr) {
		String picString;
		DataDescriptionEntryGroup group;
		DataDescriptionEntry dde = this.extractDDEFromArithmetic(numericExpr);
		if (dde != null && dde instanceof DataDescriptionEntryGroup && (group = (DataDescriptionEntryGroup)dde).getPictureClause() != null && (picString = group.getPictureClause().getPictureString()) != null) {
			Integer intDigits = this.cobolPictureLengthService.getIntegerPartLength(picString);
			Integer decDigits = this.cobolPictureLengthService.getFractionalPartLength(picString);
			Integer picLength = this.cobolPictureLengthService.getLength(dde);
			if (intDigits != null && picLength != null) {
				if (decDigits != null && decDigits > 0) {
					return "CobolMove.moveNumericToAlphanumeric(" + javaExpr + ", " + intDigits + ", " + decDigits + ", " + picLength + ")";
				}
				return "CobolMove.moveNumericToAlphanumeric(" + javaExpr + ", " + intDigits + ", " + picLength + ")";
			}
		}
		return "String.valueOf(" + javaExpr + ")";
	}

	private String numericCallToDisplayString(Call call, String javaExpr) {
		String picString;
		DataDescriptionEntryGroup group;
		DataDescriptionEntryCall ddeCall;
		DataDescriptionEntry dde;
		if (call != null && (call.getCallType() == Call.CallType.DATA_DESCRIPTION_ENTRY_CALL || call.getCallType() == Call.CallType.TABLE_CALL) && (dde = (ddeCall = (DataDescriptionEntryCall)call.unwrap()).getDataDescriptionEntry()) != null && dde instanceof DataDescriptionEntryGroup && (group = (DataDescriptionEntryGroup)dde).getPictureClause() != null && (picString = group.getPictureClause().getPictureString()) != null) {
			Integer intDigits = this.cobolPictureLengthService.getIntegerPartLength(picString);
			Integer decDigits = this.cobolPictureLengthService.getFractionalPartLength(picString);
			Integer picLength = this.cobolPictureLengthService.getLength(dde);
			if (intDigits != null && picLength != null) {
				if (decDigits != null && decDigits > 0) {
					return "CobolMove.moveNumericToAlphanumeric(" + javaExpr + ", " + intDigits + ", " + decDigits + ", " + picLength + ")";
				}
				return "CobolMove.moveNumericToAlphanumeric(" + javaExpr + ", " + intDigits + ", " + picLength + ")";
			}
		}
		return "String.valueOf(" + javaExpr + ")";
	}

	private DataDescriptionEntry extractDDEFromArithmetic(ArithmeticValueStmt arithmeticValueStmt) {
		Call call;
		if (arithmeticValueStmt == null) {
			return null;
		}
		if (arithmeticValueStmt.getPlusMinus() != null && !arithmeticValueStmt.getPlusMinus().isEmpty()) {
			return null;
		}
		MultDivs multDivs = arithmeticValueStmt.getMultDivs();
		if (multDivs == null) {
			return null;
		}
		if (multDivs.getMultDivs() != null && !multDivs.getMultDivs().isEmpty()) {
			return null;
		}
		Powers powers = multDivs.getPowers();
		if (powers == null) {
			return null;
		}
		if (powers.getPowers() != null && !powers.getPowers().isEmpty()) {
			return null;
		}
		Basis basis = powers.getBasis();
		if (basis == null) {
			return null;
		}
		ValueStmt basisValueStmt = basis.getBasisValueStmt();
		if (basisValueStmt instanceof CallValueStmt && (call = ((CallValueStmt)basisValueStmt).getCall()) != null && (call.getCallType() == Call.CallType.DATA_DESCRIPTION_ENTRY_CALL || call.getCallType() == Call.CallType.TABLE_CALL)) {
			DataDescriptionEntryCall ddeCall = (DataDescriptionEntryCall)call.unwrap();
			return ddeCall.getDataDescriptionEntry();
		}
		return null;
	}

	private boolean isNonBooleanDataReference(CombinableCondition combinableCondition) {
		if (combinableCondition == null || combinableCondition.getSimpleCondition() == null) {
			return false;
		}
		SimpleCondition sc = combinableCondition.getSimpleCondition();
		if (sc.getSimpleConditionType() != SimpleCondition.SimpleConditionType.CONDITION_NAME_REFERENCE) {
			return false;
		}
		ConditionNameReference cnr = sc.getConditionNameReference();
		if (cnr == null || cnr.getConditionCall() == null) {
			return false;
		}
		Call call = cnr.getConditionCall();
		if (call.getCallType() == Call.CallType.DATA_DESCRIPTION_ENTRY_CALL) {
			DataDescriptionEntryCall ddeCall = (DataDescriptionEntryCall)call.unwrap();
			DataDescriptionEntry dde = ddeCall.getDataDescriptionEntry();
			if (DataDescriptionEntry.DataDescriptionEntryType.CONDITION.equals(dde.getDataDescriptionEntryType())) {
				return false;
			}
			CobolTypeEnum ddeType = this.cobolTypeService.getType(dde);
			return !CobolTypeEnum.BOOLEAN.equals(ddeType);
		}
		return false;
	}

	private String buildComparison(String subject, RelationalOperator.RelationalOperatorType op, String rhsExpr, boolean isNumericSubject) {
		// Reference modification always returns String — force alphanumeric comparison
		if (isNumericSubject && (subject.contains("CobolReference.referenceModification(")
				|| rhsExpr.contains(".substring(") || rhsExpr.contains("CobolReference.referenceModification("))) {
			isNumericSubject = false;
		}
		if (isNumericSubject) {
			return subject + ".compareTo(" + normalizeForNumericComparison(rhsExpr) + ")" + (switch (op) {
				case EQUAL -> " == 0";
				case NOT_EQUAL -> " != 0";
				case GREATER -> " > 0";
				case GREATER_OR_EQUAL -> " >= 0";
				case LESS -> " < 0";
				case LESS_OR_EQUAL -> " <= 0";
				default -> " != 0";
			});
		}
		return "CobolComparison.compareAlphanumeric(" + subject + ", " + rhsExpr + ")" + (switch (op) {
			case EQUAL -> " == 0";
			case NOT_EQUAL -> " != 0";
			case GREATER -> " > 0";
			case GREATER_OR_EQUAL -> " >= 0";
			case LESS -> " < 0";
			case LESS_OR_EQUAL -> " <= 0";
			default -> " != 0";
		});
	}

	private String expandAbbreviations(List<Abbreviation> abbreviations, String subject, RelationalOperator.RelationalOperatorType inheritedOp, boolean isNumericSubject) {
		StringBuffer result = new StringBuffer();
		boolean first = true;
		block21: for (Abbreviation abbrev : abbreviations) {
			String figurativeHelper;
			RelationalOperator op = abbrev.getOperator();
			ArithmeticValueStmt rhs = abbrev.getArithmeticExpression();
			if (rhs == null) continue;
			if (!first) {
				result.append(" && ");
			}
			first = false;
			String rhsStr = this.mapToExpression(rhs);
			// If the abbreviated operand has subscripts (table call), the ASG-based
			// mapToExpression may lose them (resolving as DATA_DESCRIPTION_ENTRY_CALL
			// without subscripts). Try parse-tree-based resolution as a fallback.
			if (abbrev.getCtx() instanceof CobolParser.AbbreviationContext) {
				CobolParser.AbbreviationContext abbrevCtx = (CobolParser.AbbreviationContext)abbrev.getCtx();
				if (abbrevCtx.arithmeticExpression() != null) {
					String arithText = abbrevCtx.arithmeticExpression().getText().trim();
					// Check if the text contains subscripts (parenthesized expression after a name)
					// e.g., PRMO-COND-SEASON-2(WK-POSICAO)
					if (arithText.contains("(") && !arithText.startsWith("(") && arithText.lastIndexOf(')') > arithText.indexOf('(')) {
						// The ASG may have resolved this as a simple data call without subscripts.
						// Check if the generated rhsStr is missing subscripts (no safeGet/array access).
						if (!rhsStr.contains("safeGet(") && !rhsStr.contains("[")) {
							// Try resolving through ASG with subscripts
							Program abbrevProg = rhs.getProgram();
							if (abbrevProg != null) {
								int parenPos = arithText.indexOf('(');
								String fieldName = arithText.substring(0, parenPos).trim();
								String subText = arithText.substring(parenPos + 1, arithText.lastIndexOf(')')).trim();
								// Resolve the field without subscripts to get the base path
								String resolvedBase = this.resolveFieldByNameViaASG(fieldName, Collections.emptyList(), abbrevProg);
								if (resolvedBase != null && resolvedBase.contains(DOT)) {
									// Find which OCCURS parent needs the subscript
									DataDescriptionEntry fieldEntry = this.findFieldByNameViaASG(fieldName, Collections.emptyList(), abbrevProg);
									if (fieldEntry != null) {
										List<DataDescriptionEntry> hierarchy = this.collectCallHierarchy(fieldEntry);
										// Find the OCCURS ancestor
										for (int hi = 0; hi < hierarchy.size() - 1; hi++) {
											DataDescriptionEntry hEntry = hierarchy.get(hi);
											if (hEntry instanceof DataDescriptionEntryGroup) {
												int maxOcc = this.pictureStringService.getMaxOccurs((DataDescriptionEntryGroup) hEntry);
												if (maxOcc > 1) {
													// Build the subscripted path
													StringBuilder pathWithSub = new StringBuilder();
													boolean isFirstH = true;
													for (int hj = 0; hj < hierarchy.size(); hj++) {
														DataDescriptionEntry he = hierarchy.get(hj);
														if (!isFirstH) pathWithSub.append(DOT);
														pathWithSub.append(this.javaVariableIdentifierService.mapToIdentifier(he));
														if (hj == hi) {
															// Apply subscript at the OCCURS level
															boolean isGroupWithChildren = this.cobolDataDescriptionEntryService.hasChildren(hEntry);
															String subExpr = this.javaIdentifierService.mapToIdentifier(subText);
															if (isGroupWithChildren) {
																String fieldAccess = pathWithSub.toString();
																pathWithSub.setLength(0);
																pathWithSub.append("io.proleap.cobol.runtime.CobolConstants.safeGet(");
																pathWithSub.append(fieldAccess);
																pathWithSub.append(", ");
																pathWithSub.append(subExpr).append(".intValue() - 1)");
															} else {
																pathWithSub.append("[").append(subExpr).append(".intValue() - 1]");
															}
														}
														isFirstH = false;
													}
													String newPath = pathWithSub.toString();
													// Replace the unsubscripted path within rhsStr
													rhsStr = rhsStr.replace(resolvedBase, newPath);
													break;
												}
											}
										}
									}
								}
							}
						}
					}
				}
			}
			boolean isNot = false;
			if (abbrev.getCtx() instanceof CobolParser.AbbreviationContext) {
				CobolParser.AbbreviationContext abbrevCtx = (CobolParser.AbbreviationContext)abbrev.getCtx();
				isNot = abbrevCtx.NOT() != null;
			}
			RelationalOperator.RelationalOperatorType effectiveOpType = null;
			if (op != null) {
				effectiveOpType = op.getRelationalOperatorType();
			} else if (inheritedOp != null) {
				effectiveOpType = inheritedOp;
			}
			if (isNot && effectiveOpType != null) {
				switch (effectiveOpType) {
					case EQUAL: {
						effectiveOpType = RelationalOperator.RelationalOperatorType.NOT_EQUAL;
						break;
					}
					case GREATER: {
						effectiveOpType = RelationalOperator.RelationalOperatorType.LESS_OR_EQUAL;
						break;
					}
					case LESS: {
						effectiveOpType = RelationalOperator.RelationalOperatorType.GREATER_OR_EQUAL;
						break;
					}
				}
			} else if (isNot && effectiveOpType == null) {
				effectiveOpType = RelationalOperator.RelationalOperatorType.NOT_EQUAL;
			}
			if ((figurativeHelper = this.getFigurativeConstantHelper(rhsStr)) != null) {
				boolean isNotOp;
				boolean bl = isNotOp = effectiveOpType == RelationalOperator.RelationalOperatorType.NOT_EQUAL;
				if (isNotOp) {
					result.append("!");
				}
				result.append(figurativeHelper).append("(").append(subject).append(")");
				continue;
			}
			// Reference modification and substring always return String — force alphanumeric.
			// Also check the COBOL type of the RHS: if it's STRING, the comparison
			// should use compareAlphanumeric even when the subject is numeric.
			CobolTypeEnum rhsType = this.cobolTypeService.getType(rhs);
			boolean rhsIsString = CobolTypeEnum.STRING.equals(rhsType);
			boolean effectiveNumeric = isNumericSubject
				&& !rhsIsString
				&& !subject.contains("CobolReference.referenceModification(")
				&& !rhsStr.contains(".substring(")
				&& !rhsStr.contains("CobolReference.referenceModification(");
			if (effectiveNumeric) {
				result.append("(").append(subject).append(".compareTo(").append(normalizeForNumericComparison(rhsStr)).append(") ");
				if (effectiveOpType != null) {
					switch (effectiveOpType) {
						case NOT_EQUAL: {
							result.append("!= 0)");
							continue block21;
						}
						case EQUAL: {
							result.append("== 0)");
							continue block21;
						}
						case GREATER: {
							result.append("> 0)");
							continue block21;
						}
						case GREATER_OR_EQUAL: {
							result.append(">= 0)");
							continue block21;
						}
						case LESS: {
							result.append("< 0)");
							continue block21;
						}
						case LESS_OR_EQUAL: {
							result.append("<= 0)");
							continue block21;
						}
					}
					result.append("== 0)");
					continue;
				}
				result.append("== 0)");
				continue;
			}
			if (effectiveOpType != null) {
				String alphaRhs = this.normalizeForAlphanumericComparison(rhsStr);
				result.append("(CobolComparison.compareAlphanumeric(").append(subject).append(", ").append(alphaRhs).append(") ");
				switch (effectiveOpType) {
					case NOT_EQUAL: {
						result.append("!= 0)");
						break;
					}
					case EQUAL: {
						result.append("== 0)");
						break;
					}
					case GREATER: {
						result.append("> 0)");
						break;
					}
					case GREATER_OR_EQUAL: {
						result.append(">= 0)");
						break;
					}
					case LESS: {
						result.append("< 0)");
						break;
					}
					case LESS_OR_EQUAL: {
						result.append("<= 0)");
						break;
					}
					default: {
						result.append("== 0)");
						break;
					}
				}
				continue;
			}
			String alphaRhs = this.normalizeForAlphanumericComparison(rhsStr);
			result.append("(CobolComparison.compareAlphanumeric(").append(subject).append(", ").append(alphaRhs).append(") == 0)");
		}
		return result.toString();
	}

	public String mapToExpression(ArithmeticComparison arithmeticComparison) {
		Boolean value = this.valueService.getBoolean(this.valueStmtService.getValue((ValueStmt)arithmeticComparison, null));
		StringBuffer result = new StringBuffer();
		if (value != null) {
			result.append(JavaLiteralUtils.mapToLiteral((Boolean)value));
		} else {
			RelationalOperator operator = arithmeticComparison.getOperator();
			RelationalOperator.RelationalOperatorType relationalOperatorType = operator.getRelationalOperatorType();
			ArithmeticValueStmt arithmeticExpressionLeft = arithmeticComparison.getArithmeticExpressionLeft();
			ArithmeticValueStmt arithmeticExpressionRight = arithmeticComparison.getArithmeticExpressionRight();
			JavaArithmeticExpressionClassifier.JavaArithmeticExpressionTypeEnum arithmeticExpressionType = this.javaArithmeticExpressionClassifier.classify(arithmeticComparison);
			switch (arithmeticExpressionType) {
				case COMPARISON_BETWEEN_STRING_AND_BLANK: {
					switch (relationalOperatorType) {
						case NOT_EQUAL: {
							result.append("!");
							break;
						}
					}
					String spacesLeftExpr = this.mapToExpression(arithmeticComparison.getArithmeticExpressionLeft());
					result.append("CobolConstants.isSpaces(");
					result.append(spacesLeftExpr);
					result.append(")");
					break;
				}
				case COMPARISON_BETWEEN_STRING_AND_ZEROS: {
					switch (relationalOperatorType) {
						case NOT_EQUAL: {
							result.append("!");
							break;
						}
					}
					String zerosLeftExpr = this.mapToExpression(arithmeticComparison.getArithmeticExpressionLeft());
					result.append("CobolConstants.isZeros(");
					result.append(zerosLeftExpr);
					result.append(")");
					break;
				}
				case COMPARISON_BETWEEN_STRING_AND_LOW_VALUES: {
					switch (relationalOperatorType) {
						case NOT_EQUAL: {
							result.append("!");
							break;
						}
					}
					String lowValLeftExpr = this.mapToExpression(arithmeticComparison.getArithmeticExpressionLeft());
					result.append("CobolConstants.isLowValues(");
					result.append(lowValLeftExpr);
					result.append(")");
					break;
				}
				case COMPARISON_BETWEEN_STRING_AND_HIGH_VALUES: {
					switch (relationalOperatorType) {
						case NOT_EQUAL: {
							result.append("!");
							break;
						}
					}
					String highValLeftExpr = this.mapToExpression(arithmeticComparison.getArithmeticExpressionLeft());
					result.append("CobolConstants.isHighValues(");
					result.append(highValLeftExpr);
					result.append(")");
					break;
				}
				case COMPARISON_BETWEEN_GROUP_AND_STRING: {
					String leftExprRaw = this.mapToExpression(arithmeticComparison.getArithmeticExpressionLeft());
					String rightExprRaw = this.mapToExpression(arithmeticComparison.getArithmeticExpressionRight());
					CobolTypeEnum leftTypeG = this.cobolTypeService.getType((ValueStmt)arithmeticExpressionLeft);
					CobolTypeEnum rightTypeG = this.cobolTypeService.getType((ValueStmt)arithmeticExpressionRight);
					boolean leftIsGroup = CobolTypeEnum.DATA_DESCRIPTION_GROUP.equals(leftTypeG);
					boolean rightIsGroup = CobolTypeEnum.DATA_DESCRIPTION_GROUP.equals(rightTypeG);
					// For group comparisons with figurative constants (ZEROS, SPACES, etc.),
					// use the CobolConstants helper to correctly compare all characters.
					String figurativeHelperGS = null;
					if (leftIsGroup && !rightIsGroup) {
						figurativeHelperGS = this.getFigurativeConstantHelper(rightExprRaw);
					}
					if (figurativeHelperGS != null) {
						switch (relationalOperatorType) {
							case NOT_EQUAL: {
								result.append("!");
								break;
							}
						}
						result.append(figurativeHelperGS).append("(CobolMove.groupToString(").append(leftExprRaw).append("))");
						break;
					}
					String lhsFinal = leftIsGroup ? "CobolMove.groupToString(" + leftExprRaw + ")" : leftExprRaw;
					boolean rhsIsNumericG = CobolTypeEnum.INTEGER.equals(rightTypeG) || CobolTypeEnum.FLOAT.equals(rightTypeG);
					boolean lhsIsNumericG = CobolTypeEnum.INTEGER.equals(leftTypeG) || CobolTypeEnum.FLOAT.equals(leftTypeG);
					String rhsFinal;
					if (rightIsGroup) {
						rhsFinal = "CobolMove.groupToString(" + rightExprRaw + ")";
					} else if (rhsIsNumericG) {
						rhsFinal = this.numericToDisplayString(arithmeticExpressionRight, rightExprRaw);
					} else {
						rhsFinal = this.normalizeForAlphanumericComparison(rightExprRaw);
					}
					String lhsAdjusted = lhsFinal;
					if (!leftIsGroup && lhsIsNumericG) {
						lhsAdjusted = this.numericToDisplayString(arithmeticExpressionLeft, leftExprRaw);
					}
					result.append("(CobolComparison.compareAlphanumeric(");
					result.append(lhsAdjusted);
					result.append(", ");
					result.append(rhsFinal);
					result.append(") ");
					switch (relationalOperatorType) {
						case GREATER: {
							result.append(">");
							break;
						}
						case GREATER_OR_EQUAL: {
							result.append(">=");
							break;
						}
						case LESS: {
							result.append("<");
							break;
						}
						case LESS_OR_EQUAL: {
							result.append("<=");
							break;
						}
						case EQUAL: {
							result.append("==");
							break;
						}
						case NOT_EQUAL: {
							result.append("!=");
							break;
						}
						default: {
							result.append("==");
						}
					}
					result.append(" 0)");
					break;
				}
				case COMPARISON_BETWEEN_NUMERIC_AND_STRING: {
					String rightStr;
					String leftStr;
					CobolTypeEnum leftType = this.cobolTypeService.getType((ValueStmt)arithmeticExpressionLeft);
					String leftExpr = this.mapToExpression(arithmeticExpressionLeft);
					String rightExpr = this.mapToExpression(arithmeticExpressionRight);
					if (CobolTypeEnum.STRING.equals(leftType)) {
						leftStr = leftExpr;
						rightStr = this.numericToDisplayString(arithmeticExpressionRight, rightExpr);
					} else {
						leftStr = this.numericToDisplayString(arithmeticExpressionLeft, leftExpr);
						rightStr = rightExpr;
					}
					result.append("(CobolComparison.compareAlphanumeric(");
					result.append(leftStr);
					result.append(", ");
					result.append(rightStr);
					result.append(") ");
					switch (relationalOperatorType) {
						case GREATER: {
							result.append(">");
							break;
						}
						case GREATER_OR_EQUAL: {
							result.append(">=");
							break;
						}
						case LESS: {
							result.append("<");
							break;
						}
						case LESS_OR_EQUAL: {
							result.append("<=");
							break;
						}
						case EQUAL: {
							result.append("==");
							break;
						}
						case NOT_EQUAL: {
							result.append("!=");
							break;
						}
					}
					result.append(" 0)");
					break;
				}
				default: {
					String leftExprStr = this.mapToExpression(arithmeticExpressionLeft);
					String rightExprRaw = this.mapToExpression(arithmeticExpressionRight);
					String rightExprStr = this.normalizeBooleanLiteralForComparison(rightExprRaw);
					CobolTypeEnum leftTypeDefault = this.cobolTypeService.getType((ValueStmt)arithmeticExpressionLeft);
					CobolTypeEnum rightTypeDefault = this.cobolTypeService.getType((ValueStmt)arithmeticExpressionRight);
					boolean isStringComparison = CobolTypeEnum.STRING.equals(leftTypeDefault);
					boolean isGroupComparison = CobolTypeEnum.DATA_DESCRIPTION_GROUP.equals(leftTypeDefault);
					boolean isRhsGroup = CobolTypeEnum.DATA_DESCRIPTION_GROUP.equals(rightTypeDefault);
					// For group comparisons with figurative constants (ZEROS, SPACES, etc.),
					// use the CobolConstants helper instead of compareAlphanumeric.
					// COBOL expands figurative constants to match the field length, so
					// comparing a PIC X(03) group to ZEROS means checking every character is '0'.
					String figurativeHelper = null;
					if (isGroupComparison && !isRhsGroup) {
						figurativeHelper = this.getFigurativeConstantHelper(rightExprStr);
					}
					if (figurativeHelper != null) {
						// Generate: CobolConstants.isZeros(CobolMove.groupToString(LHS))
						// or: !CobolConstants.isZeros(CobolMove.groupToString(LHS))
						switch (relationalOperatorType) {
							case NOT_EQUAL: {
								result.append("!");
								break;
							}
						}
						result.append(figurativeHelper).append("(CobolMove.groupToString(").append(leftExprStr).append("))");
						break;
					}
					// When leftType is unknown (null) and rightType is numeric,
					// treat like COMPARISON_BETWEEN_NUMERIC_AND_STRING to avoid
					// calling .compareTo(BigDecimal) on a String variable.
					boolean isRhsNumeric = CobolTypeEnum.INTEGER.equals(rightTypeDefault) || CobolTypeEnum.FLOAT.equals(rightTypeDefault);
					boolean isLhsNumeric = CobolTypeEnum.INTEGER.equals(leftTypeDefault) || CobolTypeEnum.FLOAT.equals(leftTypeDefault);
					// Reference modification always produces a String (substring) at runtime,
					// even when applied to a numeric field. Detect this from the generated expr.
					boolean lhsIsRefMod = leftExprStr.contains("CobolReference.referenceModification(");
					boolean rhsIsRefMod = rightExprStr.contains("CobolReference.referenceModification(");
					// .substring() calls (from OCCURS over REDEFINES) also return String
					boolean lhsIsSubstring = leftExprStr.endsWith(")") && leftExprStr.contains(".substring(");
					boolean rhsIsSubstring = rightExprStr.endsWith(")") && rightExprStr.contains(".substring(");
					boolean isRhsString = CobolTypeEnum.STRING.equals(rightTypeDefault);
					boolean isLhsString = CobolTypeEnum.STRING.equals(leftTypeDefault);
					boolean forceAlphanumeric = (leftTypeDefault == null && isRhsNumeric)
							|| (rightTypeDefault == null && isLhsNumeric)
							|| (isLhsNumeric && isRhsString)
							|| (isRhsNumeric && isLhsString)
							// When one side is unresolved (null) and the other is STRING,
							// force alphanumeric to avoid .compareTo(BigDecimal) on String
							|| (leftTypeDefault == null && isRhsString)
							|| (rightTypeDefault == null && isLhsString)
							// When both sides are unresolved, default to alphanumeric
							// to avoid type mismatch in .compareTo() calls
							|| (leftTypeDefault == null && rightTypeDefault == null)
							|| (lhsIsRefMod && isRhsNumeric)
							|| (rhsIsRefMod && isLhsNumeric)
							|| (lhsIsSubstring && isRhsNumeric)
							|| (rhsIsSubstring && isLhsNumeric)
							// When either side is ref-mod/substring, always force alphanumeric
							// because the expression produces String at runtime
							|| lhsIsRefMod || rhsIsRefMod
							|| lhsIsSubstring || rhsIsSubstring;
					if (isStringComparison || isGroupComparison || isRhsGroup || forceAlphanumeric) {
						result.append("(CobolComparison.compareAlphanumeric(");
					} else {
						result.append("(");
						result.append(leftExprStr);
						result.append(".compareTo(");
					}
					if (isGroupComparison) {
						result.append("CobolMove.groupToString(").append(leftExprStr).append(")");
						result.append(", ");
						if (isRhsGroup) {
							result.append("CobolMove.groupToString(").append(rightExprStr).append(")");
						} else {
							result.append(this.normalizeForAlphanumericComparison(rightExprStr));
						}
						result.append(") ");
					} else if (isStringComparison || isRhsGroup || forceAlphanumeric) {
						// When the LHS is numeric but was forced to alphanumeric (e.g., RHS is ref-mod),
						// convert the LHS to display string for alphanumeric comparison.
						if (isLhsNumeric && !lhsIsRefMod) {
							result.append(this.numericToDisplayString(arithmeticExpressionLeft, leftExprStr));
						} else {
							result.append(leftExprStr);
						}
						result.append(", ");
						if (isRhsGroup) {
							result.append("CobolMove.groupToString(").append(rightExprStr).append(")");
						} else if (isRhsNumeric && !rhsIsRefMod) {
							result.append(this.numericToDisplayString(arithmeticExpressionRight, rightExprStr));
						} else {
							result.append(this.normalizeForAlphanumericComparison(rightExprStr));
						}
						result.append(") ");
					} else {
						result.append(normalizeForNumericComparison(rightExprStr));
						result.append(") ");
					}
					switch (relationalOperatorType) {
						case GREATER: {
							result.append(">");
							break;
						}
						case GREATER_OR_EQUAL: {
							result.append(">=");
							break;
						}
						case LESS: {
							result.append("<");
							break;
						}
						case LESS_OR_EQUAL: {
							result.append("<=");
							break;
						}
						case EQUAL: {
							result.append("==");
							break;
						}
						case NOT_EQUAL: {
							result.append("!=");
							break;
						}
					}
					result.append(" 0)");
				}
			}
		}
		return result.toString();
	}

	public String mapToExpression(ArithmeticValueStmt arithmeticValueStmt) {
		BigDecimal value = this.valueService.getDecimal(this.valueStmtService.getValue((ValueStmt)arithmeticValueStmt, null));
		StringBuffer result = new StringBuffer();
		if (value != null) {
			result.append(JavaLiteralUtils.mapToLiteral(value));
		} else {
			String firstOperand = this.mapToExpression(arithmeticValueStmt.getMultDivs());
			List<PlusMinus> plusMinusList = arithmeticValueStmt.getPlusMinus();
			if (plusMinusList != null && !plusMinusList.isEmpty()) {
				// Arithmetic operations require BigDecimal operands. When the first
				// operand resolves to a String (alphanumeric field used in numeric
				// context), wrap it in a runtime numeric conversion.
				// However, if the operand already contains arithmetic method calls
				// (.multiply, .divide, .add, .subtract) or is already wrapped with
				// CobolMove.move*, the Java result is already BigDecimal — skip wrapping.
				CobolTypeEnum firstType = this.cobolTypeService.getType(arithmeticValueStmt.getMultDivs());
				boolean alreadyNumericExpr = firstOperand.contains(".multiply(")
						|| firstOperand.contains(".divide(")
						|| firstOperand.contains(".add(")
						|| firstOperand.contains(".subtract(")
						|| firstOperand.startsWith("CobolMove.move")
						|| firstOperand.startsWith("(CobolMove.move");
				if (CobolTypeEnum.STRING.equals(firstType) && !alreadyNumericExpr) {
					firstOperand = "CobolMove.moveAlphanumericToNumeric(" + firstOperand + ", 18, 0)";
				}
			}
			result.append(firstOperand);
			if (plusMinusList != null) {
				for (PlusMinus plusMinus : plusMinusList) {
					result.append(this.mapToExpression(plusMinus));
				}
			}
		}
		return result.toString();
	}

	public String mapToExpression(Basis basis) {
		BigDecimal value;
		if (basis.getCtx() != null && basis.getCtx().getText() != null && basis.getCtx().getText().toUpperCase().contains("STATUS-CUR")) {
		}
		String result = (value = this.valueService.getDecimal(this.valueStmtService.getValue((ValueStmt)basis, null))) != null ? JavaLiteralUtils.mapToLiteral(value) : (basis.getBasisValueStmt() instanceof ArithmeticValueStmt ? "(" + this.mapToExpression(basis.getBasisValueStmt()) + ")" : this.mapToExpression(basis.getBasisValueStmt()));
		return result;
	}

	public String mapToExpression(CallValueStmt callValueStmt) {
		String condExpr;
		DataDescriptionEntry dde;
		DataDescriptionEntryCall ddeCall;
		Call call = callValueStmt.getCall();
		if ((call.getCallType() == Call.CallType.DATA_DESCRIPTION_ENTRY_CALL || call.getCallType() == Call.CallType.TABLE_CALL) && (ddeCall = (DataDescriptionEntryCall)call.unwrap()) != null && (dde = ddeCall.getDataDescriptionEntry()) != null && DataDescriptionEntry.DataDescriptionEntryType.CONDITION.equals(dde.getDataDescriptionEntryType()) && (condExpr = this.buildConditionExpression(dde, call)) != null) {
			return condExpr;
		}
		String result = this.mapToCall(call);
		// Check overlay accessor FIRST — when a field is inside a group-over-elementary
		// REDEFINES (e.g., VALORAUX inside WKNUMROWN REDEFINES WKNUMROW PIC X(09)),
		// the overlay accessor produces the correct flattened getter (e.g., getWknumrown_Valoraux()),
		// while buildRedefinesGetterExpression would produce an invalid path (e.g., wknumrown.getValoraux()).
		String overlayGetter = this.buildGroupOverElementaryAccessor(call, false);
		if (overlayGetter != null) {
			return overlayGetter;
		}
		String redefinesGetter = this.buildRedefinesGetterExpression(call);
		if (redefinesGetter != null) {
			return redefinesGetter;
		}
		return result;
	}

	public String getRedefinesGetterExpression(Call call) {
		return this.buildRedefinesGetterExpression(call);
	}

	public String getRedefinesSetterPrefix(Call call) {
		return this.buildRedefinesSetterPrefix(call);
	}

	private String buildRedefinesSetterPrefix(Call call) {
		if (call == null) {
			return null;
		}
		Call unwrapped = call.unwrap();
		if (unwrapped == null || unwrapped.getCallType() != Call.CallType.DATA_DESCRIPTION_ENTRY_CALL && unwrapped.getCallType() != Call.CallType.TABLE_CALL) {
			return null;
		}
		DataDescriptionEntryCall ddeCall = (DataDescriptionEntryCall)unwrapped;
		DataDescriptionEntry dde = ddeCall.getDataDescriptionEntry();
		if (!(dde instanceof DataDescriptionEntryGroup)) {
			return null;
		}
		DataDescriptionEntryGroup group = (DataDescriptionEntryGroup)dde;
		if (group.getRedefinesClause() == null || group.getRedefinesClause().getRedefinesCall() == null) {
			return null;
		}
		String variableId = this.javaVariableIdentifierService.mapToIdentifier(dde);
		String setterName = "set" + Character.toUpperCase(variableId.charAt(0)) + variableId.substring(1);
		// Collect subscripts from the call context (same as getter logic)
		List<CobolParser.SubscriptContext> contextSubscripts = this.findContextSubscripts(ddeCall, call);
		Iterator<CobolParser.SubscriptContext> subCtxIterator = contextSubscripts != null ? contextSubscripts.iterator() : Collections.emptyIterator();
		DataDescriptionEntryGroup parentGroup = dde.getParentDataDescriptionEntryGroup();
		if (parentGroup != null) {
			List<DataDescriptionEntry> parentHierarchy = this.collectCallHierarchy(parentGroup);
			StringBuilder path = new StringBuilder();
			boolean isFirst = true;
			for (DataDescriptionEntry entry : parentHierarchy) {
				DataDescriptionEntryGroup grp;
				int maxOccurs;
				if (!isFirst) {
					path.append(DOT);
				}
				path.append(this.javaVariableIdentifierService.mapToIdentifier(entry));
				// Handle OCCURS subscripts on parent groups (same as getter)
				if (DataDescriptionEntry.DataDescriptionEntryType.GROUP.equals(entry.getDataDescriptionEntryType()) && (maxOccurs = this.pictureStringService.getMaxOccurs(grp = (DataDescriptionEntryGroup)entry)) > 1 && subCtxIterator.hasNext()) {
					boolean isGroupWithChildren = this.cobolDataDescriptionEntryService.hasChildren((DataDescriptionEntry)grp);
					CobolParser.SubscriptContext subCtx = (CobolParser.SubscriptContext)((Object)subCtxIterator.next());
					String subText = subCtx.getText().trim();
					if (isGroupWithChildren) {
						String fieldAccess = path.toString();
						path.setLength(0);
						path.append("io.proleap.cobol.runtime.CobolConstants.safeGet(");
						path.append(fieldAccess);
						path.append(", ");
					} else {
						path.append("[");
					}
					try {
						int idx = Integer.parseInt(subText);
						path.append(idx - 1);
					}
					catch (NumberFormatException e) {
						String arithResult = this.convertRefModExpr(subText, call.getProgram());
						if (arithResult.startsWith("(")) {
							path.append(arithResult);
							path.append(" - 1");
						} else {
							String subExpr = null;
							if (call.getProgram() != null) {
								subExpr = this.resolveFieldByNameViaASG(subText, Collections.emptyList(), call.getProgram());
							}
							if (subExpr != null && subExpr.contains(DOT)) {
								path.append(subExpr);
							} else {
								path.append(this.javaIdentifierService.mapToIdentifier(subText));
							}
							path.append(".intValue() - 1");
						}
					}
					if (isGroupWithChildren) {
						path.append(")");
					} else {
						path.append("]");
					}
				}
				isFirst = false;
			}
			path.append(DOT);
			path.append(setterName);
			return path.toString();
		}
		return setterName;
	}

	private String buildRedefinesGetterExpression(Call call) {
		if (call == null) {
			return null;
		}
		Call unwrapped = call.unwrap();
		if (unwrapped == null || unwrapped.getCallType() != Call.CallType.DATA_DESCRIPTION_ENTRY_CALL && unwrapped.getCallType() != Call.CallType.TABLE_CALL) {
			return null;
		}
		DataDescriptionEntryCall ddeCall = (DataDescriptionEntryCall)unwrapped;
		DataDescriptionEntry dde = ddeCall.getDataDescriptionEntry();
		if (!(dde instanceof DataDescriptionEntryGroup)) {
			return null;
		}
		DataDescriptionEntryGroup group = (DataDescriptionEntryGroup)dde;
		if (group.getRedefinesClause() == null || group.getRedefinesClause().getRedefinesCall() == null) {
			return null;
		}
		// If a parent ancestor is a group-over-elementary REDEFINES, the parent instance
		// variable doesn't exist (it was flattened). Delegate to buildGroupOverElementaryAccessor.
		for (DataDescriptionEntryGroup ancestor = dde.getParentDataDescriptionEntryGroup(); ancestor != null; ancestor = ancestor.getParentDataDescriptionEntryGroup()) {
			if (this.isGroupOverElementaryRedefines(ancestor)) {
				return null;
			}
		}
		String variableId = this.javaVariableIdentifierService.mapToIdentifier(dde);
		String getterName = "get" + Character.toUpperCase(variableId.charAt(0)) + variableId.substring(1);
		List<CobolParser.SubscriptContext> contextSubscripts = this.findContextSubscripts(ddeCall, call);
		Iterator<CobolParser.SubscriptContext> subCtxIterator = contextSubscripts != null ? contextSubscripts.iterator() : Collections.emptyIterator();
		DataDescriptionEntryGroup parentGroup = dde.getParentDataDescriptionEntryGroup();
		if (parentGroup != null) {
			List<DataDescriptionEntry> parentHierarchy = this.collectCallHierarchy(parentGroup);
			StringBuilder path = new StringBuilder();
			boolean isFirst = true;
			for (DataDescriptionEntry entry : parentHierarchy) {
				DataDescriptionEntryGroup grp;
				int maxOccurs;
				if (!isFirst) {
					path.append(DOT);
				}
				path.append(this.javaVariableIdentifierService.mapToIdentifier(entry));
				if (DataDescriptionEntry.DataDescriptionEntryType.GROUP.equals(entry.getDataDescriptionEntryType()) && (maxOccurs = this.pictureStringService.getMaxOccurs(grp = (DataDescriptionEntryGroup)entry)) > 1 && subCtxIterator.hasNext()) {
					boolean isGroupWithChildren = this.cobolDataDescriptionEntryService.hasChildren((DataDescriptionEntry)grp);
					CobolParser.SubscriptContext subCtx = (CobolParser.SubscriptContext)((Object)subCtxIterator.next());
					String subText = subCtx.getText().trim();
					if (isGroupWithChildren) {
						path.append(".get(");
					} else {
						path.append("[");
					}
					try {
						int idx = Integer.parseInt(subText);
						path.append(idx - 1);
					}
					catch (NumberFormatException e) {
						String arithResult = this.convertRefModExpr(subText, call.getProgram());
						if (arithResult.startsWith("(")) {
							path.append(arithResult);
							path.append(" - 1");
						} else {
							// Text-based fallback for mangled OF/IN
							String resolvedSub = null;
							Program subProg = call.getProgram();
							if (subProg != null) {
								resolvedSub = this.resolveFieldByNameViaASG(subText, Collections.emptyList(), subProg);
							}
							if ((resolvedSub == null || !resolvedSub.contains(DOT)) && subProg != null) {
								String sUpper = subText.toUpperCase();
								int sOfPos = sUpper.indexOf("OF");
								int sInPos = sUpper.indexOf("IN");
								int sSplitPos = sOfPos > 0 ? sOfPos : (sInPos > 0 ? sInPos : -1);
								String sKw = sOfPos > 0 ? "OF" : "IN";
								if (sSplitPos > 0) {
									String sLeaf = subText.substring(0, sSplitPos);
									String sQual = subText.substring(sSplitPos + sKw.length());
									if (!sLeaf.isEmpty() && !sQual.isEmpty()) {
										List<String> sQ = new java.util.ArrayList<>();
										sQ.add(sQual.toUpperCase().replace('_', '-'));
										String sRes = this.resolveFieldByNameViaASG(sLeaf, sQ, subProg);
										if (sRes != null && sRes.contains(DOT)) {
											resolvedSub = sRes;
										}
									}
								}
							}
							if (resolvedSub != null && resolvedSub.contains(DOT)) {
								path.append(resolvedSub);
							} else {
								path.append(this.javaIdentifierService.mapToIdentifier(subText));
							}
							path.append(".intValue() - 1");
						}
					}
					if (isGroupWithChildren) {
						path.append(")");
					} else {
						path.append("]");
					}
				}
				isFirst = false;
			}
			path.append(DOT);
			path.append(getterName);
			path.append("()");
			return path.toString();
		}
		return getterName + "()";
	}

	private boolean isGroupOverElementaryRedefines(DataDescriptionEntryGroup group) {
		DataDescriptionEntryGroup baseGroup;
		boolean baseHasChildren;
		if (group.getRedefinesClause() == null || group.getRedefinesClause().getRedefinesCall() == null) {
			return false;
		}
		boolean hasNonConditionChildren = group.getDataDescriptionEntries().stream().anyMatch(e -> e.getDataDescriptionEntryType() != DataDescriptionEntry.DataDescriptionEntryType.CONDITION);
		if (!hasNonConditionChildren) {
			return false;
		}
		String baseName = group.getRedefinesClause().getRedefinesCall().getName();
		DataDescriptionEntry baseDde = this.resolveRedefinesBase(group, baseName);
		if (baseDde == null) {
			return false;
		}
		if (baseDde.getDataDescriptionEntryType() == DataDescriptionEntry.DataDescriptionEntryType.GROUP && (baseHasChildren = (baseGroup = (DataDescriptionEntryGroup)baseDde).getDataDescriptionEntries().stream().anyMatch(e -> e.getDataDescriptionEntryType() != DataDescriptionEntry.DataDescriptionEntryType.CONDITION))) {
			return false;
		}
		CobolTypeEnum baseType = this.cobolTypeService.getType(baseDde);
		return CobolTypeEnum.STRING.equals(baseType) || CobolTypeEnum.INTEGER.equals(baseType) || CobolTypeEnum.FLOAT.equals(baseType);
	}

	private String buildQualifiedChildAccessorName(DataDescriptionEntryGroup redefinesGroup, DataDescriptionEntry childEntry) {
		String groupId = this.javaVariableIdentifierService.mapToIdentifier(redefinesGroup);
		String capGroupId = Character.toUpperCase(groupId.charAt(0)) + groupId.substring(1);
		String childId = this.javaVariableIdentifierService.mapToIdentifier(childEntry);
		return capGroupId + "_" + Character.toUpperCase(childId.charAt(0)) + childId.substring(1);
	}

	private String buildQualifiedChildAccessorName(DataDescriptionEntryGroup redefinesGroup, List<DataDescriptionEntry> entries) {
		if (redefinesGroup.getName() != null && redefinesGroup.getName().toUpperCase().contains("DESCLONG")) {
		}
		String groupId = this.javaVariableIdentifierService.mapToIdentifier(redefinesGroup);
		StringBuilder sb = new StringBuilder();
		sb.append(Character.toUpperCase(groupId.charAt(0)));
		sb.append(groupId.substring(1));
		for (DataDescriptionEntry e2 : entries) {
			String id = this.javaVariableIdentifierService.mapToIdentifier(e2);
			sb.append("_");
			sb.append(Character.toUpperCase(id.charAt(0)));
			sb.append(id.substring(1));
		}
		return sb.toString();
	}

	private boolean isNonTrivialGroup(DataDescriptionEntry entry) {
		if (!(entry instanceof DataDescriptionEntryGroup)) {
			return false;
		}
		return ((DataDescriptionEntryGroup)entry).getDataDescriptionEntries().stream().anyMatch(e -> e.getDataDescriptionEntryType() != DataDescriptionEntry.DataDescriptionEntryType.CONDITION);
	}

	private DataDescriptionEntry resolveRedefinesBase(DataDescriptionEntry redefinesDde, String origName) {
		block5: {
			block4: {
				DataDescriptionEntryGroup parent = redefinesDde.getParentDataDescriptionEntryGroup();
				if (parent == null) break block4;
				for (DataDescriptionEntry sibling : parent.getDataDescriptionEntries()) {
					if (!origName.equalsIgnoreCase(sibling.getName())) continue;
					return sibling;
				}
				break block5;
			}
			Program prog = redefinesDde.getProgram();
			if (prog == null) break block5;
			for (CompilationUnit cu : prog.getCompilationUnits()) {
				for (ProgramUnit pu : cu.getProgramUnits()) {
					if (pu.getDataDivision() == null) continue;
					for (DataDescriptionEntryContainer sec : this.collectAllDataSections(pu)) {
						DataDescriptionEntry found = sec.getDataDescriptionEntry(origName);
						if (found == null) continue;
						return found;
					}
				}
			}
		}
		return null;
	}

	private String buildGroupOverElementaryAccessor(Call call, boolean isSetter) {
		int childMaxOccurs;
		if (call == null) {
			return null;
		}
		Call unwrapped = call.unwrap();
		DataDescriptionEntry dde = null;
		if (unwrapped != null && unwrapped.getCallType() == Call.CallType.DATA_DESCRIPTION_ENTRY_CALL) {
			dde = ((DataDescriptionEntryCall)unwrapped).getDataDescriptionEntry();
		} else if (unwrapped != null && unwrapped.getCallType() == Call.CallType.TABLE_CALL) {
			dde = ((TableCall)unwrapped).getDataDescriptionEntry();
		}
		if (dde == null) {
			return this.buildGroupOverElementaryAccessorFromParseTree(call, isSetter);
		}
		DataDescriptionEntryGroup redefinesAncestor = null;
		ArrayList<DataDescriptionEntry> pathFromRedefines = new ArrayList<DataDescriptionEntry>();
		pathFromRedefines.add(dde);
		for (DataDescriptionEntryGroup cursor = dde.getParentDataDescriptionEntryGroup(); cursor != null; cursor = cursor.getParentDataDescriptionEntryGroup()) {
			if (this.isGroupOverElementaryRedefines(cursor)) {
				redefinesAncestor = cursor;
				break;
			}
			pathFromRedefines.add(cursor);
		}
		if (redefinesAncestor == null) {
			return null;
		}
		if (unwrapped != null && unwrapped.getCallType() == Call.CallType.TABLE_CALL && dde instanceof DataDescriptionEntryGroup && (childMaxOccurs = this.pictureStringService.getMaxOccurs((DataDescriptionEntryGroup)dde)) > 1) {
			return null;
		}
		// When the call is a TABLE_CALL and an intermediate entry between the leaf
		// and the REDEFINES ancestor has OCCURS, the getter methods don't handle
		// subscript indexing. Delegate to mapToCall(TableCall) which uses substring.
		// Only for getters — setters must continue to use accessor methods because
		// mapToCall generates substring expressions that cannot be used as assignment targets.
		if (!isSetter && unwrapped != null && unwrapped.getCallType() == Call.CallType.TABLE_CALL) {
			for (DataDescriptionEntry pathEntry : pathFromRedefines) {
				if (pathEntry instanceof DataDescriptionEntryGroup && this.pictureStringService.getMaxOccurs((DataDescriptionEntryGroup)pathEntry) > 1) {
					return null;
				}
			}
		}
		String redefinesId = this.javaVariableIdentifierService.mapToIdentifier(redefinesAncestor);
		StringBuilder capAccessor = new StringBuilder();
		capAccessor.append(Character.toUpperCase(redefinesId.charAt(0)));
		capAccessor.append(redefinesId.substring(1));
		for (int i = pathFromRedefines.size() - 1; i >= 0; --i) {
			DataDescriptionEntry entry = (DataDescriptionEntry)pathFromRedefines.get(i);
			String entryId = this.javaVariableIdentifierService.mapToIdentifier(entry);
			capAccessor.append("_");
			capAccessor.append(Character.toUpperCase(entryId.charAt(0)));
			capAccessor.append(entryId.substring(1));
		}
		String accessorName = (isSetter ? "set" : "get") + capAccessor.toString();
		DataDescriptionEntryGroup grandparent = redefinesAncestor.getParentDataDescriptionEntryGroup();
		if (grandparent != null) {
			List<DataDescriptionEntry> hierarchy = this.collectCallHierarchy(grandparent);
			StringBuilder path = new StringBuilder();
			boolean isFirst = true;
			List<CobolParser.SubscriptContext> contextSubscripts = unwrapped instanceof DataDescriptionEntryCall ? this.findContextSubscripts((DataDescriptionEntryCall)unwrapped, call) : null;
			Iterator<CobolParser.SubscriptContext> subCtxIterator = contextSubscripts != null ? contextSubscripts.iterator() : Collections.emptyIterator();
			for (DataDescriptionEntry entry : hierarchy) {
				DataDescriptionEntryGroup grp;
				int maxOccurs;
				if (!isFirst) {
					path.append(DOT);
				}
				path.append(this.javaVariableIdentifierService.mapToIdentifier(entry));
				if (DataDescriptionEntry.DataDescriptionEntryType.GROUP.equals(entry.getDataDescriptionEntryType()) && (maxOccurs = this.pictureStringService.getMaxOccurs(grp = (DataDescriptionEntryGroup)entry)) > 1 && subCtxIterator.hasNext()) {
					boolean isGroupWithChildren = this.cobolDataDescriptionEntryService.hasChildren((DataDescriptionEntry)grp);
					CobolParser.SubscriptContext subCtx = (CobolParser.SubscriptContext)((Object)subCtxIterator.next());
					String subText = subCtx.getText().trim();
					if (isGroupWithChildren) {
						path.append(".get(");
					} else {
						path.append("[");
					}
					try {
						int idx = Integer.parseInt(subText);
						path.append(idx - 1);
					}
					catch (NumberFormatException e) {
						String arithResult = this.convertRefModExpr(subText, call.getProgram());
						if (arithResult.startsWith("(")) {
							path.append(arithResult);
							path.append(" - 1");
						} else {
							String subExpr3 = this.resolveFieldByNameViaASG(subText, Collections.emptyList(), call.getProgram());
							// Text-based fallback for mangled OF/IN
							if ((subExpr3 == null || !subExpr3.contains(DOT)) && call.getProgram() != null) {
								String s3Upper = subText.toUpperCase();
								int s3OfPos = s3Upper.indexOf("OF");
								int s3InPos = s3Upper.indexOf("IN");
								int s3SplitPos = s3OfPos > 0 ? s3OfPos : (s3InPos > 0 ? s3InPos : -1);
								String s3Kw = s3OfPos > 0 ? "OF" : "IN";
								if (s3SplitPos > 0) {
									String s3Leaf = subText.substring(0, s3SplitPos);
									String s3Qual = subText.substring(s3SplitPos + s3Kw.length());
									if (!s3Leaf.isEmpty() && !s3Qual.isEmpty()) {
										List<String> s3Q = new java.util.ArrayList<>();
										s3Q.add(s3Qual.toUpperCase().replace('_', '-'));
										String s3Res = this.resolveFieldByNameViaASG(s3Leaf, s3Q, call.getProgram());
										if (s3Res != null && s3Res.contains(DOT)) {
											subExpr3 = s3Res;
										}
									}
								}
							}
							if (subExpr3 != null && subExpr3.contains(DOT)) {
								path.append(subExpr3);
							} else {
								path.append(this.javaIdentifierService.mapToIdentifier(subText));
							}
							path.append(".intValue() - 1");
						}
					}
					if (isGroupWithChildren) {
						path.append(")");
					} else {
						path.append("]");
					}
				}
				isFirst = false;
			}

			// Check if the REDEFINES ancestor itself has OCCURS and there's a remaining subscript.
			// In that case, instead of using the static getter, we need to use substring
			// on the base field to select the correct OCCURS element and extract the child.
			final int redefMaxOccurs = this.pictureStringService.getMaxOccurs(redefinesAncestor);
			if (redefMaxOccurs > 1 && subCtxIterator.hasNext() && !isSetter) {
				// The REDEFINES group overlays the base field with repeated elements.
				final String rBaseName = redefinesAncestor.getRedefinesClause().getRedefinesCall().getName();
				final String rBaseId = this.javaIdentifierService.mapToIdentifier(rBaseName);

				// Find the base entry to get its total length
				final DataDescriptionEntry rBaseDde = this.resolveRedefinesBase(redefinesAncestor, rBaseName);
				final Integer rBaseLenObj = rBaseDde != null ? this.cobolPictureLengthService.getLength(rBaseDde) : null;
				final int rElemSize;
				if (rBaseLenObj != null) {
					rElemSize = rBaseLenObj / redefMaxOccurs;
				} else {
					final Integer rChildLenFb = this.cobolPictureLengthService.getLength(dde);
					rElemSize = rChildLenFb != null ? rChildLenFb : 1;
				}

				// Compute child offset within each element
				int rChildOffset = 0;
				final Integer rChildLenObj = this.cobolPictureLengthService.getLength(dde);
				final int rChildLen = rChildLenObj != null ? rChildLenObj : rElemSize;
				for (final DataDescriptionEntry sibling : redefinesAncestor.getDataDescriptionEntries()) {
					if (sibling == dde || sibling.getName().equalsIgnoreCase(dde.getName())) {
						break;
					}
					if (sibling.getDataDescriptionEntryType() != DataDescriptionEntry.DataDescriptionEntryType.CONDITION) {
						final Integer sibLen = this.cobolPictureLengthService.getLength(sibling);
						if (sibLen != null) {
							rChildOffset += sibLen;
						}
					}
				}

				// Consume the subscript from context
				final CobolParser.SubscriptContext rSubCtx = subCtxIterator.next();
				final String rSubText = rSubCtx.getText().trim();

				path.append(DOT);
				try {
					final int rIdx = Integer.parseInt(rSubText) - 1;
					final int rStart = rIdx * rElemSize + rChildOffset;
					final int rEnd = rStart + rChildLen;
					path.append(rBaseId).append(".substring(").append(rStart).append(", ").append(rEnd).append(")");
				} catch (final NumberFormatException e) {
					final String rArithResult = this.convertRefModExpr(rSubText, call.getProgram());
					String rSubExpr;
					if (rArithResult.startsWith("(")) {
						rSubExpr = rArithResult;
					} else {
						rSubExpr = this.resolveFieldByNameViaASG(rSubText, Collections.emptyList(), call.getProgram());
						if (rSubExpr == null || !rSubExpr.contains(DOT)) {
							rSubExpr = this.javaIdentifierService.mapToIdentifier(rSubText);
						}
						rSubExpr = rSubExpr + ".intValue()";
					}
					path.append(rBaseId).append(".substring((").append(rSubExpr).append(" - 1) * ").append(rElemSize);
					if (rChildOffset > 0) {
						path.append(" + ").append(rChildOffset);
					}
					path.append(", (").append(rSubExpr).append(" - 1) * ").append(rElemSize);
					path.append(" + ").append(rChildOffset + rChildLen).append(")");
				}
				return path.toString();
			}

			path.append(DOT);
			path.append(accessorName);
			if (!isSetter) {
				path.append("()");
			}
			return path.toString();
		}
		if (!isSetter) {
			return accessorName + "()";
		}
		return accessorName;
	}

	private String buildGroupOverElementaryAccessorFromParseTree(Call call, boolean isSetter) {
		ParserRuleContext callCtx = call.getCtx();
		CobolParser.QualifiedDataNameFormat1Context qdf1 = this.findQualifiedDataNameFormat1(callCtx);
		if (qdf1 == null || qdf1.qualifiedInData() == null || qdf1.qualifiedInData().isEmpty()) {
			return null;
		}
		String leafName = qdf1.dataName().getText();
		Program program = call.getProgram();
		if (program == null) {
			return null;
		}
		ArrayList<String> qualifierNames = new ArrayList<String>();
		for (CobolParser.QualifiedInDataContext qid : qdf1.qualifiedInData()) {
			if (qid.inData() != null && qid.inData().dataName() != null) {
				qualifierNames.add(qid.inData().dataName().getText());
				continue;
			}
			if (qid.inTable() == null || qid.inTable().tableCall() == null || qid.inTable().tableCall().qualifiedDataName() == null) continue;
			qualifierNames.add(qid.inTable().tableCall().qualifiedDataName().getText());
		}
		if (qualifierNames.isEmpty()) {
			return null;
		}
		String nearestQualifier = (String)qualifierNames.get(0);
		DataDescriptionEntry qualEntry = this.findFieldByNameViaASG(nearestQualifier, qualifierNames.subList(1, qualifierNames.size()).stream().map(String::toUpperCase).collect(Collectors.toList()), program);
		if (qualEntry == null || qualEntry.getDataDescriptionEntryType() != DataDescriptionEntry.DataDescriptionEntryType.GROUP) {
			return null;
		}
		DataDescriptionEntryGroup qualGroup = (DataDescriptionEntryGroup)qualEntry;
		if (!this.isGroupOverElementaryRedefines(qualGroup)) {
			return null;
		}
		String redefinesId = this.javaVariableIdentifierService.mapToIdentifier(qualGroup);
		String leafId = this.javaIdentifierService.mapToIdentifier(leafName);
		String capAccessor = Character.toUpperCase(redefinesId.charAt(0)) + redefinesId.substring(1) + "_" + Character.toUpperCase(leafId.charAt(0)) + leafId.substring(1);
		String accessorName = (isSetter ? "set" : "get") + capAccessor;
		DataDescriptionEntryGroup grandparent = qualGroup.getParentDataDescriptionEntryGroup();
		if (grandparent != null) {
			List<DataDescriptionEntry> hierarchy = this.collectCallHierarchy(grandparent);
			StringBuilder path = new StringBuilder();
			boolean isFirst = true;
			for (DataDescriptionEntry entry : hierarchy) {
				if (!isFirst) {
					path.append(DOT);
				}
				path.append(this.javaVariableIdentifierService.mapToIdentifier(entry));
				isFirst = false;
			}
			path.append(DOT);
			path.append(accessorName);
			if (!isSetter) {
				path.append("()");
			}
			return path.toString();
		}
		return isSetter ? accessorName : accessorName + "()";
	}

	public String getGroupOverElementaryGetterExpression(Call call) {
		return this.buildGroupOverElementaryAccessor(call, false);
	}

	public String getGroupOverElementarySetterPrefix(Call call) {
		return this.buildGroupOverElementaryAccessor(call, true);
	}

	/**
	 * If the call references a numeric leaf that is a descendant of a group-over-elementary
	 * REDEFINES whose base is ALPHANUMERIC (PIC X), returns a String expression that reads
	 * the raw bytes of the base field at the leaf's offset.
	 * <p>
	 * This matches COBOL MOVE semantics: when a numeric PIC 9 field is REDEFINES-backed by
	 * alphanumeric storage, MOVE to an alphanumeric target copies the raw bytes of the base
	 * (so SPACES in the base stay SPACES), not the numeric interpretation of the bytes
	 * (which would convert SPACES to "0000000000" through BigDecimal).
	 * <p>
	 * Example: for source NRCOLIS-int OF OrderLines(idx) where NRCOLIS PIC X(11),
	 * NRCOLISm REDEFINES NRCOLIS, NRCOLIS-int is child PIC 9(10) at offset 1 within NRCOLISm,
	 * returns an expression equivalent to
	 *   {@code orderlines.get(idx-1).nrcolis.substring(1, 11)}.
	 * <p>
	 * Returns null if the call is not a numeric descendant of a group-over-elementary
	 * REDEFINES over an alphanumeric base.
	 */
	public String getGroupOverElementaryRawBaseExpression(Call call) {
		if (call == null) {
			return null;
		}
		Call unwrapped = call.unwrap();
		if (unwrapped == null) {
			return null;
		}
		DataDescriptionEntry dde;
		if (unwrapped.getCallType() == Call.CallType.DATA_DESCRIPTION_ENTRY_CALL) {
			dde = ((DataDescriptionEntryCall) unwrapped).getDataDescriptionEntry();
		} else if (unwrapped.getCallType() == Call.CallType.TABLE_CALL) {
			dde = ((TableCall) unwrapped).getDataDescriptionEntry();
		} else {
			return null;
		}
		if (dde == null) {
			return null;
		}
		// The leaf must itself be numeric (PIC 9/S9/V9) — that's the precondition
		// for this alphanumeric-via-base-bytes semantics to apply.
		CobolTypeEnum leafType = this.cobolTypeService.getType(dde);
		if (leafType != CobolTypeEnum.INTEGER && leafType != CobolTypeEnum.FLOAT) {
			return null;
		}

		// Walk up to find a group-over-elementary REDEFINES ancestor.
		DataDescriptionEntryGroup redefinesAncestor = null;
		for (DataDescriptionEntryGroup cursor = dde.getParentDataDescriptionEntryGroup(); cursor != null; cursor = cursor.getParentDataDescriptionEntryGroup()) {
			if (this.isGroupOverElementaryRedefines(cursor)) {
				redefinesAncestor = cursor;
				break;
			}
		}
		if (redefinesAncestor == null) {
			return null;
		}

		// The REDEFINES base must be ALPHANUMERIC for the byte-copy semantics to apply.
		final String baseName = redefinesAncestor.getRedefinesClause().getRedefinesCall().getName();
		final DataDescriptionEntry baseDde = this.resolveRedefinesBase(redefinesAncestor, baseName);
		if (baseDde == null) {
			return null;
		}
		final CobolTypeEnum baseType = this.cobolTypeService.getType(baseDde);
		if (!CobolTypeEnum.STRING.equals(baseType)) {
			return null;
		}

		// Leaf length
		final Integer leafLenObj = this.cobolPictureLengthService.getLength(dde);
		if (leafLenObj == null) {
			return null;
		}
		final int leafLen = leafLenObj;

		// Compute leaf offset within the REDEFINES group: sum lengths of preceding
		// non-condition entries along the path from each intermediate group.
		// pathFromRedefines = [leaf, intermediate1, intermediate2, ...]
		int leafOffsetWithinRedef = 0;
		DataDescriptionEntry currentChild = dde;
		DataDescriptionEntryGroup currentParent = dde.getParentDataDescriptionEntryGroup();
		while (currentParent != null && currentParent != redefinesAncestor) {
			for (final DataDescriptionEntry sibling : currentParent.getDataDescriptionEntries()) {
				if (sibling == currentChild || (sibling.getName() != null && currentChild.getName() != null
						&& sibling.getName().equalsIgnoreCase(currentChild.getName()))) {
					break;
				}
				if (sibling.getDataDescriptionEntryType() != DataDescriptionEntry.DataDescriptionEntryType.CONDITION) {
					final Integer sibLen = this.cobolPictureLengthService.getLength(sibling);
					if (sibLen != null) {
						leafOffsetWithinRedef += sibLen;
					}
				}
			}
			currentChild = currentParent;
			currentParent = currentParent.getParentDataDescriptionEntryGroup();
		}
		// Now currentParent == redefinesAncestor; add offsets of preceding siblings
		// of currentChild within the REDEFINES group.
		if (currentParent == redefinesAncestor) {
			for (final DataDescriptionEntry sibling : redefinesAncestor.getDataDescriptionEntries()) {
				if (sibling == currentChild || (sibling.getName() != null && currentChild.getName() != null
						&& sibling.getName().equalsIgnoreCase(currentChild.getName()))) {
					break;
				}
				if (sibling.getDataDescriptionEntryType() != DataDescriptionEntry.DataDescriptionEntryType.CONDITION) {
					final Integer sibLen = this.cobolPictureLengthService.getLength(sibling);
					if (sibLen != null) {
						leafOffsetWithinRedef += sibLen;
					}
				}
			}
		}

		// Build the path to the BASE field (the alphanumeric sibling of the REDEFINES group).
		// The base field lives in the same grandparent container as the REDEFINES group.
		final String baseId = this.javaVariableIdentifierService.mapToIdentifier(baseDde);
		DataDescriptionEntryGroup grandparent = redefinesAncestor.getParentDataDescriptionEntryGroup();
		StringBuilder path = new StringBuilder();
		if (grandparent != null) {
			// Compute the Java access path to grandparent, consuming subscripts on OCCURS
			// groups along the way.
			List<DataDescriptionEntry> hierarchy = this.collectCallHierarchy(grandparent);
			List<CobolParser.SubscriptContext> contextSubscripts = unwrapped instanceof DataDescriptionEntryCall
					? this.findContextSubscripts((DataDescriptionEntryCall) unwrapped, call) : null;
			Iterator<CobolParser.SubscriptContext> subCtxIterator = contextSubscripts != null
					? contextSubscripts.iterator() : Collections.emptyIterator();
			boolean isFirst = true;
			for (DataDescriptionEntry entry : hierarchy) {
				DataDescriptionEntryGroup grp;
				int maxOccurs;
				if (!isFirst) {
					path.append(DOT);
				}
				path.append(this.javaVariableIdentifierService.mapToIdentifier(entry));
				if (DataDescriptionEntry.DataDescriptionEntryType.GROUP.equals(entry.getDataDescriptionEntryType())
						&& (maxOccurs = this.pictureStringService.getMaxOccurs(grp = (DataDescriptionEntryGroup) entry)) > 1
						&& subCtxIterator.hasNext()) {
					boolean isGroupWithChildren = this.cobolDataDescriptionEntryService.hasChildren((DataDescriptionEntry) grp);
					CobolParser.SubscriptContext subCtx = subCtxIterator.next();
					String subText = subCtx.getText().trim();
					if (isGroupWithChildren) {
						String fieldAccess = path.toString();
						path.setLength(0);
						path.append("io.proleap.cobol.runtime.CobolConstants.safeGet(");
						path.append(fieldAccess);
						path.append(", ");
					} else {
						path.append("[");
					}
					try {
						int idx = Integer.parseInt(subText);
						path.append(idx - 1);
					} catch (NumberFormatException e) {
						String arithResult = this.convertRefModExpr(subText, call.getProgram());
						if (arithResult.startsWith("(")) {
							path.append(arithResult);
							path.append(" - 1");
						} else {
							String subExpr = null;
							if (call.getProgram() != null) {
								subExpr = this.resolveFieldByNameViaASG(subText, Collections.emptyList(), call.getProgram());
							}
							if (subExpr != null && subExpr.contains(DOT)) {
								path.append(subExpr);
							} else {
								path.append(this.javaIdentifierService.mapToIdentifier(subText));
							}
							path.append(".intValue() - 1");
						}
					}
					if (isGroupWithChildren) {
						path.append(")");
					} else {
						path.append("]");
					}
				}
				isFirst = false;
			}

			// If the REDEFINES ancestor itself has OCCURS and a subscript remains, we cannot
			// simply reference the base field once — each OCCURS iteration slices the base
			// string. That case is not common for this scenario and we bail out.
			if (this.pictureStringService.getMaxOccurs(redefinesAncestor) > 1 && subCtxIterator.hasNext()) {
				return null;
			}

			path.append(DOT);
			path.append(baseId);
		} else {
			path.append(baseId);
		}

		// Emit: <basePath>.substring(leafOffset, leafOffset + leafLen)
		final int endOffset = leafOffsetWithinRedef + leafLen;
		return path.toString() + ".substring(" + leafOffsetWithinRedef + ", " + endOffset + ")";
	}

	public String mapToExpression(CombinableCondition combinableCondition) {
		Boolean value = this.valueService.getBoolean(this.valueStmtService.getValue((ValueStmt)combinableCondition, null));
		StringBuffer result = new StringBuffer();
		if (value != null) {
			result.append(JavaLiteralUtils.mapToLiteral((Boolean)value));
		} else {
			if (combinableCondition.isNot()) {
				result.append("!");
			}
			result.append(this.mapToExpression(combinableCondition.getSimpleCondition()));
		}
		return result.toString();
	}

	/*
	 * WARNING - void declaration
	 */
	public String mapToExpression(ConditionNameReference conditionNameReference) {
		Call conditionCall = conditionNameReference.getConditionCall();
		if (conditionCall.getCallType() == Call.CallType.DATA_DESCRIPTION_ENTRY_CALL || conditionCall.getCallType() == Call.CallType.TABLE_CALL) {
			DataDescriptionEntryCall ddeCall = (DataDescriptionEntryCall)conditionCall.unwrap();
			DataDescriptionEntry dde = ddeCall.getDataDescriptionEntry();
			if (DataDescriptionEntry.DataDescriptionEntryType.CONDITION.equals(dde.getDataDescriptionEntryType())) {
				ArrayList<String> subscriptExprs = new ArrayList<String>();
				if (conditionCall.getCallType() == Call.CallType.TABLE_CALL) {
					TableCall tableCall = (TableCall)conditionCall.unwrap();
					for (Subscript subscript : tableCall.getSubscripts()) {
						subscriptExprs.add(this.buildSubscriptExprForCondition(subscript, dde));
					}
				} else if (conditionNameReference.getConditionNameSubscriptReferences() != null && !conditionNameReference.getConditionNameSubscriptReferences().isEmpty()) {
					for (ConditionNameSubscriptReference subRef : conditionNameReference.getConditionNameSubscriptReferences()) {
						for (Subscript subscript : subRef.getSubscripts()) {
							subscriptExprs.add(this.buildSubscriptExprForCondition(subscript, dde));
						}
					}
				}
				if (subscriptExprs.isEmpty() && conditionNameReference.getCtx() != null) {
					String ctxText = conditionNameReference.getCtx().getText();
					int lparen = ctxText.lastIndexOf(40);
					int n = ctxText.lastIndexOf(41);
					if (lparen >= 0 && n > lparen) {
						String subsText = ctxText.substring(lparen + 1, n);
						for (String sub : subsText.split(",")) {
							String trimmed = sub.trim().replace("-", "_").toLowerCase();
							if (trimmed.isEmpty()) continue;
							subscriptExprs.add(trimmed + ".intValue() - 1");
						}
					}
				}
				String parentPath = !subscriptExprs.isEmpty() ? this.mapConditionToCallWithSubscripts(dde, subscriptExprs) : this.mapConditionToCall(dde);
				DataDescriptionEntryGroup parent = dde.getParentDataDescriptionEntryGroup();
				if (parent != null) {
					DataDescriptionEntryCondition dataDescriptionEntryCondition;
					ValueClause vc;
					DataDescriptionEntry dataDescriptionEntry;
					CobolTypeEnum parentType = this.cobolTypeService.getType((DataDescriptionEntry)parent);
					if (parent.getRedefinesClause() != null && parent.getRedefinesClause().getRedefinesCall() != null && (dataDescriptionEntry = this.resolveRedefinesEntry(parent)) != null && dataDescriptionEntry != parent) {
						parentType = this.cobolTypeService.getType(dataDescriptionEntry);
					}
					if (CobolTypeEnum.BOOLEAN.equals(parentType)) {
						String boolPath = parentPath;
						if (parent.getRedefinesClause() != null && parent.getRedefinesClause().getRedefinesCall() != null) {
							String parentId = this.javaVariableIdentifierService.mapToIdentifier(parent);
							String getterName = "get" + Character.toUpperCase(parentId.charAt(0)) + parentId.substring(1) + "()";
							int lastDot = parentPath.lastIndexOf('.');
							if (lastDot >= 0) {
								boolPath = parentPath.substring(0, lastDot + 1) + getterName;
							} else {
								boolPath = getterName;
							}
						}
						if (this.isConditionFalseValue(dde)) {
							return "!" + boolPath;
						}
						return boolPath;
					}
					if (dde instanceof DataDescriptionEntryCondition && (vc = (dataDescriptionEntryCondition = (DataDescriptionEntryCondition)dde).getValueClause()) != null && !vc.getValueIntervals().isEmpty()) {
						List<ValueInterval> intervals = vc.getValueIntervals();
						if (intervals.size() == 1) {
							ValueInterval vi = intervals.get(0);
							CobolValue val = this.valueStmtService.getValue(vi.getFromValueStmt(), null);
							String literal = this.getConditionLiteral(val, parentType);
							if (literal != null) {
								if (CobolTypeEnum.STRING.equals(parentType)) {
									return "\"" + literal + "\".equals(" + parentPath + ")";
								}
								return parentPath + ".compareTo(new BigDecimal(\"" + literal + "\")) == 0";
							}
						} else {
							StringBuilder sb = new StringBuilder("(");
							boolean first = true;
							for (ValueInterval vi : intervals) {
								if (!first) {
									sb.append(" || ");
								}
								first = false;
								CobolValue val = this.valueStmtService.getValue(vi.getFromValueStmt(), null);
								String literal = this.getConditionLiteral(val, parentType);
								if (literal == null) continue;
								if (CobolTypeEnum.STRING.equals(parentType)) {
									sb.append("\"").append(literal).append("\".equals(").append(parentPath).append(")");
									continue;
								}
								sb.append(parentPath).append(".compareTo(new BigDecimal(\"").append(literal).append("\")) == 0");
							}
							sb.append(")");
							return sb.toString();
						}
					}
				}
				if (this.isConditionFalseValue(dde)) {
					return "!" + parentPath;
				}
				return parentPath;
			}
			if (dde instanceof DataDescriptionEntryGroup) {
				DataDescriptionEntryGroup parentGroup = (DataDescriptionEntryGroup)dde;
				String ddeNameUpper = dde.getName() != null ? dde.getName().toUpperCase() : null;
				DataDescriptionEntry sameNameCondChild = null;
				for (DataDescriptionEntry dataDescriptionEntry : parentGroup.getDataDescriptionEntries()) {
					if (!DataDescriptionEntry.DataDescriptionEntryType.CONDITION.equals(dataDescriptionEntry.getDataDescriptionEntryType()) || dataDescriptionEntry.getName() == null || !dataDescriptionEntry.getName().toUpperCase().equals(ddeNameUpper)) continue;
					sameNameCondChild = dataDescriptionEntry;
					break;
				}
				if (sameNameCondChild != null) {
					ValueInterval vi;
					CobolValue val;
					String literal;
					DataDescriptionEntryCondition condEntry;
					ValueClause vc;
					String selfPath = this.mapToCall(conditionCall);
					CobolTypeEnum cobolTypeEnum = this.cobolTypeService.getType(dde);
					if (sameNameCondChild instanceof DataDescriptionEntryCondition && (vc = (condEntry = (DataDescriptionEntryCondition)sameNameCondChild).getValueClause()) != null && !vc.getValueIntervals().isEmpty() && (literal = this.getConditionLiteral(val = this.valueStmtService.getValue((vi = vc.getValueIntervals().get(0)).getFromValueStmt(), null), cobolTypeEnum)) != null) {
						if (CobolTypeEnum.STRING.equals(cobolTypeEnum)) {
							return "\"" + literal + "\".equals(" + selfPath + ")";
						}
						return selfPath + ".compareTo(new BigDecimal(\"" + literal + "\")) == 0";
					}
				}
			}
		}
		return this.mapToCall(conditionCall);
	}

	private String getConditionLiteral(CobolValue val, CobolTypeEnum parentType) {
		Boolean boolVal;
		if (val == null) {
			return null;
		}
		String strVal = this.valueService.getString(val);
		if (strVal != null) {
			return strVal;
		}
		BigDecimal decVal = this.valueService.getDecimal(val);
		if (decVal != null) {
			return decVal.toPlainString();
		}
		if (val instanceof CobolBooleanValue && (boolVal = ((CobolBooleanValue)val).getBoolean()) != null) {
			// B"1" / B"0" are boolean bit-pattern literals.
			// In EBCDIC: B"1" = X"01", B"0" = X"00".
			// These are NOT the characters "1" (X"F1") or "0" (X"F0").
			// Use \u0001 / \u0000 for all parent types to preserve correct semantics.
			return boolVal != false ? "\\u0001" : "\\u0000";
		}
		return null;
	}

	private String buildConditionExpression(DataDescriptionEntry dde, Call call) {
		DataDescriptionEntryCondition condEntry;
		ValueClause vc;
		DataDescriptionEntry resolvedParent;
		if (!DataDescriptionEntry.DataDescriptionEntryType.CONDITION.equals(dde.getDataDescriptionEntryType())) {
			return null;
		}
		ArrayList<String> subscriptExprs = new ArrayList<String>();
		if (call.getCallType() == Call.CallType.TABLE_CALL) {
			TableCall tableCall = (TableCall)call.unwrap();
			for (Subscript subscript : tableCall.getSubscripts()) {
				subscriptExprs.add(this.mapToExpression(subscript.getSubscriptValueStmt()) + ".intValue() - 1");
			}
		}
		if (subscriptExprs.isEmpty() && call.getCtx() != null) {
			String ctxText = call.getCtx().getText();
			int lparen = ctxText.lastIndexOf(40);
			int rparen = ctxText.lastIndexOf(41);
			if (lparen >= 0 && rparen > lparen) {
				String subsText = ctxText.substring(lparen + 1, rparen);
				for (String sub : subsText.split(",")) {
					String trimmed = sub.trim().replace("-", "_").toLowerCase();
					if (trimmed.isEmpty()) continue;
					subscriptExprs.add(trimmed + ".intValue() - 1");
				}
			}
		}
		String parentPath = !subscriptExprs.isEmpty() ? this.mapConditionToCallWithSubscripts(dde, subscriptExprs) : this.mapConditionToCall(dde);
		DataDescriptionEntryGroup parent = dde.getParentDataDescriptionEntryGroup();
		if (parent == null) {
			return null;
		}
		CobolTypeEnum parentType = this.cobolTypeService.getType((DataDescriptionEntry)parent);
		if (parent.getRedefinesClause() != null && parent.getRedefinesClause().getRedefinesCall() != null && (resolvedParent = this.resolveRedefinesEntry(parent)) != null && resolvedParent != parent) {
			parentType = this.cobolTypeService.getType(resolvedParent);
		}
		if (CobolTypeEnum.BOOLEAN.equals(parentType)) {
			String boolExpr = parentPath;
			if (parent.getRedefinesClause() != null && parent.getRedefinesClause().getRedefinesCall() != null) {
				String parentId = this.javaVariableIdentifierService.mapToIdentifier(parent);
				String getterName = "get" + Character.toUpperCase(parentId.charAt(0)) + parentId.substring(1) + "()";
				int lastDot = parentPath.lastIndexOf(46);
				boolExpr = lastDot >= 0 ? parentPath.substring(0, lastDot + 1) + getterName : getterName;
			}
			if (this.isConditionFalseValue(dde)) {
				return "!" + boolExpr;
			}
			return boolExpr;
		}
		if (dde instanceof DataDescriptionEntryCondition && (vc = (condEntry = (DataDescriptionEntryCondition)dde).getValueClause()) != null && !vc.getValueIntervals().isEmpty()) {
			List<ValueInterval> intervals = vc.getValueIntervals();
			if (intervals.size() == 1) {
				ValueInterval vi = intervals.get(0);
				CobolValue val = this.valueStmtService.getValue(vi.getFromValueStmt(), null);
				String literal = this.getConditionLiteral(val, parentType);
				if (literal != null) {
					if (CobolTypeEnum.STRING.equals(parentType)) {
						return "\"" + literal + "\".equals(" + parentPath + ")";
					}
					return parentPath + ".compareTo(new BigDecimal(\"" + literal + "\")) == 0";
				}
			} else {
				StringBuilder sb = new StringBuilder("(");
				boolean first = true;
				for (ValueInterval vi : intervals) {
					if (!first) {
						sb.append(" || ");
					}
					first = false;
					CobolValue val = this.valueStmtService.getValue(vi.getFromValueStmt(), null);
					String literal = this.getConditionLiteral(val, parentType);
					if (literal == null) continue;
					if (CobolTypeEnum.STRING.equals(parentType)) {
						sb.append("\"").append(literal).append("\".equals(").append(parentPath).append(")");
						continue;
					}
					sb.append(parentPath).append(".compareTo(new BigDecimal(\"").append(literal).append("\")) == 0");
				}
				sb.append(")");
				return sb.toString();
			}
		}
		if (this.isConditionFalseValue(dde)) {
			return "!" + parentPath;
		}
		return parentPath;
	}

	public String mapToExpression(ConditionValueStmt conditionValueStmt) {
		Boolean value = this.valueService.getBoolean(this.valueStmtService.getValue((ValueStmt)conditionValueStmt, null));
		StringBuffer result = new StringBuffer();
		if (value != null) {
			result.append(JavaLiteralUtils.mapToLiteral((Boolean)value));
		} else {
			Object abbreviationSubject = this.extractRelationSubject(conditionValueStmt.getCombinableCondition());
			RelationalOperator.RelationalOperatorType inheritedOp = this.extractRelationOperator(conditionValueStmt.getCombinableCondition());
			CobolTypeEnum subjectType = this.extractRelationSubjectType(conditionValueStmt.getCombinableCondition());
			if (CobolTypeEnum.DATA_DESCRIPTION_GROUP.equals(subjectType) && abbreviationSubject != null) {
				abbreviationSubject = "CobolMove.groupToString(" + (String)abbreviationSubject + ")";
			}
			boolean isNumericSubject = !CobolTypeEnum.STRING.equals(subjectType) && !CobolTypeEnum.DATA_DESCRIPTION_GROUP.equals(subjectType) && subjectType != null;
			List<AndOrCondition> andOrConditions = conditionValueStmt.getAndOrConditions();
			boolean hasMixedOperators = this.hasMixedAndOr(andOrConditions);
			if (hasMixedOperators) {
				boolean firstFollowedByAnd;
				boolean bl = firstFollowedByAnd = !andOrConditions.isEmpty() && andOrConditions.get(0).getAndOrConditionType() == AndOrCondition.AndOrConditionType.AND;
				if (firstFollowedByAnd) {
					result.append("(");
				}
				result.append(this.mapToExpression(conditionValueStmt.getCombinableCondition()));
				for (int i = 0; i < andOrConditions.size(); ++i) {
					AndOrCondition.AndOrConditionType nextType;
					AndOrCondition current = andOrConditions.get(i);
					AndOrCondition.AndOrConditionType currentType = current.getAndOrConditionType();
					boolean isLast = i == andOrConditions.size() - 1;
					AndOrCondition.AndOrConditionType andOrConditionType = nextType = isLast ? null : andOrConditions.get(i + 1).getAndOrConditionType();
					if (currentType == AndOrCondition.AndOrConditionType.OR && nextType == AndOrCondition.AndOrConditionType.AND) {
						result.append(" || (");
						result.append(this.mapAndOrConditionOperand(current, (String)abbreviationSubject, inheritedOp, isNumericSubject));
					} else {
						result.append(this.mapToExpression(current, (String)abbreviationSubject, inheritedOp, isNumericSubject));
					}
					if (currentType == AndOrCondition.AndOrConditionType.AND && (isLast || nextType == AndOrCondition.AndOrConditionType.OR)) {
						result.append(")");
					}
					if (current.getCombinableCondition() == null || this.isNonBooleanDataReference(current.getCombinableCondition())) continue;
					String newSubject = this.extractRelationSubject(current.getCombinableCondition());
					RelationalOperator.RelationalOperatorType newOp = this.extractRelationOperator(current.getCombinableCondition());
					if (newSubject == null || newOp == null) continue;
					CobolTypeEnum newSubjectType = this.extractRelationSubjectType(current.getCombinableCondition());
					abbreviationSubject = newSubject;
					if (CobolTypeEnum.DATA_DESCRIPTION_GROUP.equals(newSubjectType)) {
						abbreviationSubject = "CobolMove.groupToString(" + (String)abbreviationSubject + ")";
					}
					inheritedOp = newOp;
					subjectType = newSubjectType;
					isNumericSubject = !CobolTypeEnum.STRING.equals(newSubjectType) && !CobolTypeEnum.DATA_DESCRIPTION_GROUP.equals(newSubjectType) && newSubjectType != null;
				}
			} else {
				result.append(this.mapToExpression(conditionValueStmt.getCombinableCondition()));
				for (AndOrCondition andOrCondition : andOrConditions) {
					result.append(this.mapToExpression(andOrCondition, (String)abbreviationSubject, inheritedOp, isNumericSubject));
					if (andOrCondition.getCombinableCondition() == null || this.isNonBooleanDataReference(andOrCondition.getCombinableCondition())) continue;
					String newSubject = this.extractRelationSubject(andOrCondition.getCombinableCondition());
					RelationalOperator.RelationalOperatorType newOp = this.extractRelationOperator(andOrCondition.getCombinableCondition());
					if (newSubject == null || newOp == null) continue;
					CobolTypeEnum newSubjectType = this.extractRelationSubjectType(andOrCondition.getCombinableCondition());
					abbreviationSubject = newSubject;
					if (CobolTypeEnum.DATA_DESCRIPTION_GROUP.equals(newSubjectType)) {
						abbreviationSubject = "CobolMove.groupToString(" + (String)abbreviationSubject + ")";
					}
					inheritedOp = newOp;
					subjectType = newSubjectType;
					isNumericSubject = !CobolTypeEnum.STRING.equals(newSubjectType) && !CobolTypeEnum.DATA_DESCRIPTION_GROUP.equals(newSubjectType) && newSubjectType != null;
				}
			}
		}
		return result.toString();
	}

	private String mapAndOrConditionOperand(AndOrCondition andOrCondition, String abbreviationSubject, RelationalOperator.RelationalOperatorType inheritedOp, boolean isNumericSubject) {
		Boolean value = this.valueService.getBoolean(this.valueStmtService.getValue((ValueStmt)andOrCondition, null));
		if (value != null) {
			return JavaLiteralUtils.mapToLiteral((Boolean)value);
		}
		if (andOrCondition.getCombinableCondition() != null) {
			return this.mapToExpression(andOrCondition.getCombinableCondition());
		}
		if (andOrCondition.getAbbreviations() != null && !andOrCondition.getAbbreviations().isEmpty() && abbreviationSubject != null) {
			return this.expandAbbreviations(andOrCondition.getAbbreviations(), abbreviationSubject, inheritedOp, isNumericSubject);
		}
		return "";
	}

	private boolean hasMixedAndOr(List<AndOrCondition> andOrConditions) {
		if (andOrConditions == null || andOrConditions.size() < 2) {
			return false;
		}
		boolean hasAnd = false;
		boolean hasOr = false;
		for (AndOrCondition aoc : andOrConditions) {
			if (aoc.getAndOrConditionType() == AndOrCondition.AndOrConditionType.AND) {
				hasAnd = true;
			} else if (aoc.getAndOrConditionType() == AndOrCondition.AndOrConditionType.OR) {
				hasOr = true;
			}
			if (!hasAnd || !hasOr) continue;
			return true;
		}
		return false;
	}

	private RelationalOperator.RelationalOperatorType extractRelationOperator(CombinableCondition combinableCondition) {
		if (combinableCondition == null) {
			return null;
		}
		SimpleCondition simpleCondition = combinableCondition.getSimpleCondition();
		if (simpleCondition == null || simpleCondition.getSimpleConditionType() != SimpleCondition.SimpleConditionType.RELATION_CONDITION) {
			return null;
		}
		RelationConditionValueStmt rel = simpleCondition.getRelationCondition();
		if (rel == null || rel.getRelationConditionType() != RelationConditionValueStmt.RelationConditionType.ARITHMETIC) {
			return null;
		}
		ArithmeticComparison arith = rel.getArithmeticComparison();
		if (arith == null || arith.getOperator() == null) {
			return null;
		}
		RelationalOperator.RelationalOperatorType opType = arith.getOperator().getRelationalOperatorType();
		if (combinableCondition.isNot() && opType != null) {
			switch (opType) {
				case EQUAL: {
					opType = RelationalOperator.RelationalOperatorType.NOT_EQUAL;
					break;
				}
				case NOT_EQUAL: {
					opType = RelationalOperator.RelationalOperatorType.EQUAL;
					break;
				}
				case GREATER: {
					opType = RelationalOperator.RelationalOperatorType.LESS_OR_EQUAL;
					break;
				}
				case LESS: {
					opType = RelationalOperator.RelationalOperatorType.GREATER_OR_EQUAL;
					break;
				}
				case GREATER_OR_EQUAL: {
					opType = RelationalOperator.RelationalOperatorType.LESS;
					break;
				}
				case LESS_OR_EQUAL: {
					opType = RelationalOperator.RelationalOperatorType.GREATER;
					break;
				}
			}
		}
		return opType;
	}

	private String extractRelationSubject(CombinableCondition combinableCondition) {
		if (combinableCondition == null) {
			return null;
		}
		SimpleCondition simpleCondition = combinableCondition.getSimpleCondition();
		if (simpleCondition == null || simpleCondition.getSimpleConditionType() != SimpleCondition.SimpleConditionType.RELATION_CONDITION) {
			return null;
		}
		RelationConditionValueStmt rel = simpleCondition.getRelationCondition();
		if (rel == null || rel.getRelationConditionType() != RelationConditionValueStmt.RelationConditionType.ARITHMETIC) {
			return null;
		}
		ArithmeticComparison arith = rel.getArithmeticComparison();
		if (arith == null) {
			return null;
		}
		return this.mapToExpression(arith.getArithmeticExpressionLeft());
	}

	private CobolTypeEnum extractRelationSubjectType(CombinableCondition combinableCondition) {
		if (combinableCondition == null) {
			return null;
		}
		SimpleCondition simpleCondition = combinableCondition.getSimpleCondition();
		if (simpleCondition == null || simpleCondition.getSimpleConditionType() != SimpleCondition.SimpleConditionType.RELATION_CONDITION) {
			return null;
		}
		RelationConditionValueStmt rel = simpleCondition.getRelationCondition();
		if (rel == null || rel.getRelationConditionType() != RelationConditionValueStmt.RelationConditionType.ARITHMETIC) {
			return null;
		}
		ArithmeticComparison arith = rel.getArithmeticComparison();
		if (arith == null) {
			return null;
		}
		return this.cobolTypeService.getType((ValueStmt)arith.getArithmeticExpressionLeft());
	}

	public String mapToExpression(MultDiv multDiv) {
		BigDecimal value = this.valueService.getDecimal(this.valueStmtService.getValue((ValueStmt)multDiv, null));
		StringBuffer result = new StringBuffer();
		MultDiv.MultDivType type = multDiv.getMultDivType();
		String operand = value != null ? JavaLiteralUtils.mapToLiteral(value) : this.mapToExpression(multDiv.getPowers());
		// When the operand is an alphanumeric field used in multiply/divide context,
		// wrap it in a numeric conversion so .multiply()/.divide() compile.
		// However, if the operand already contains arithmetic method calls
		// (.multiply, .divide, .add, .subtract) or is already wrapped with
		// CobolMove.move*, the Java result is already BigDecimal — skip wrapping.
		if (value == null) {
			CobolTypeEnum operandType = this.cobolTypeService.getType(multDiv.getPowers());
			boolean alreadyNumericExpr = operand.contains(".multiply(")
					|| operand.contains(".divide(")
					|| operand.contains(".add(")
					|| operand.contains(".subtract(")
					|| operand.startsWith("CobolMove.move")
					|| operand.startsWith("(CobolMove.move");
			if (CobolTypeEnum.STRING.equals(operandType) && !alreadyNumericExpr) {
				operand = "CobolMove.moveAlphanumericToNumeric(" + operand + ", 18, 0)";
			}
			// Wrap substring results used in arithmetic context.
			// Only wrap when the expression directly ends with a substring call.
			if (!CobolTypeEnum.STRING.equals(operandType) && operand.endsWith(")")
					&& operand.contains(".substring(")
					&& !operand.contains(".multiply(") && !operand.contains(".add(")
					&& !operand.contains(".subtract(") && !operand.contains(".divide(")
					&& !operand.startsWith("CobolMove.move") && !operand.startsWith("new BigDecimal(")) {
				operand = "new BigDecimal(" + operand + ".trim())";
			}
		}
		switch (type) {
			case DIV: {
				result.append(".divide(");
				result.append(operand);
				result.append(", 34, java.math.RoundingMode.HALF_UP)");
				break;
			}
			case MULT: {
				result.append(".multiply(");
				result.append(operand);
				result.append(")");
				break;
			}
			default: {
				result.append(operand);
			}
		}
		return result.toString();
	}

	public String mapToExpression(MultDivs multDivs) {
		BigDecimal value = this.valueService.getDecimal(this.valueStmtService.getValue((ValueStmt)multDivs, null));
		StringBuffer result = new StringBuffer();
		if (value != null) {
			result.append(JavaLiteralUtils.mapToLiteral(value));
		} else {
			String firstOperand = this.mapToExpression(multDivs.getPowers());
			List<MultDiv> multDivList = multDivs.getMultDivs();
			if (multDivList != null && !multDivList.isEmpty()) {
				// Arithmetic operations require BigDecimal. When the first
				// operand is String (alphanumeric field), wrap it.
				// However, if the operand already contains arithmetic method calls
				// (.multiply, .divide, .add, .subtract) or is already wrapped with
				// CobolMove.move*, the Java result is already BigDecimal — skip wrapping.
				CobolTypeEnum firstType = this.cobolTypeService.getType(multDivs.getPowers());
				boolean alreadyNumericExpr = firstOperand.contains(".multiply(")
						|| firstOperand.contains(".divide(")
						|| firstOperand.contains(".add(")
						|| firstOperand.contains(".subtract(")
						|| firstOperand.startsWith("CobolMove.move")
						|| firstOperand.startsWith("(CobolMove.move");
				if (CobolTypeEnum.STRING.equals(firstType) && !alreadyNumericExpr) {
					firstOperand = "CobolMove.moveAlphanumericToNumeric(" + firstOperand + ", 18, 0)";
				}
				// Also wrap substring results used in arithmetic context.
				// OCCURS items accessed via REDEFINES produce .substring() calls
				// that return String even when the COBOL PIC is numeric.
				// Only wrap when the expression ENDS with a substring call (not when
				// substring is buried inside an already-wrapped expression).
				if (!CobolTypeEnum.STRING.equals(firstType) && firstOperand.endsWith(")")
						&& firstOperand.contains(".substring(")
						&& !firstOperand.contains(".multiply(") && !firstOperand.contains(".add(")
						&& !firstOperand.contains(".subtract(") && !firstOperand.contains(".divide(")
						&& !firstOperand.startsWith("CobolMove.move") && !firstOperand.startsWith("new BigDecimal(")) {
					firstOperand = "new BigDecimal(" + firstOperand + ".trim())";
				}
			}
			result.append(firstOperand);
			if (multDivList != null) {
				for (MultDiv multDiv : multDivList) {
					result.append(this.mapToExpression(multDiv));
				}
			}
		}
		return result.toString();
	}

	public String mapToExpression(PlusMinus plusMinus) {
		StringBuffer result = new StringBuffer();
		PlusMinus.PlusMinusType type = plusMinus.getPlusMinusType();
		BigDecimal operandValue = this.valueService.getDecimal(this.valueStmtService.getValue((ValueStmt)plusMinus.getMultDivs(), null));
		String operand = operandValue != null ? JavaLiteralUtils.mapToLiteral((BigDecimal)operandValue) : this.mapToExpression(plusMinus.getMultDivs());
		// When the operand is an alphanumeric field used in arithmetic context,
		// wrap it in a numeric conversion so .add()/.subtract() compile.
		// However, if the operand already contains arithmetic method calls
		// (.multiply, .divide, .add, .subtract) or is already wrapped with
		// CobolMove.move*, the Java result is already BigDecimal — skip wrapping.
		if (operandValue == null) {
			CobolTypeEnum operandType = this.cobolTypeService.getType(plusMinus.getMultDivs());
			boolean alreadyNumericExpr = operand.contains(".multiply(")
					|| operand.contains(".divide(")
					|| operand.contains(".add(")
					|| operand.contains(".subtract(")
					|| operand.startsWith("CobolMove.move")
					|| operand.startsWith("(CobolMove.move");
			if (CobolTypeEnum.STRING.equals(operandType) && !alreadyNumericExpr) {
				operand = "CobolMove.moveAlphanumericToNumeric(" + operand + ", 18, 0)";
			}
			// Wrap substring results used in arithmetic context.
			// Only wrap when the expression directly ends with a substring call.
			if (!CobolTypeEnum.STRING.equals(operandType) && operand.endsWith(")")
					&& operand.contains(".substring(")
					&& !operand.contains(".multiply(") && !operand.contains(".add(")
					&& !operand.contains(".subtract(") && !operand.contains(".divide(")
					&& !operand.startsWith("CobolMove.move") && !operand.startsWith("new BigDecimal(")) {
				operand = "new BigDecimal(" + operand + ".trim())";
			}
		}
		switch (type) {
			case MINUS: {
				result.append(".subtract(");
				result.append(operand);
				result.append(")");
				break;
			}
			case PLUS: {
				result.append(".add(");
				result.append(operand);
				result.append(")");
				break;
			}
			default: {
				result.append(operand);
			}
		}
		return result.toString();
	}

	public String mapToExpression(Powers powers) {
		BigDecimal value = this.valueService.getDecimal(this.valueStmtService.getValue((ValueStmt)powers, null));
		StringBuffer result = new StringBuffer();
		if (value != null) {
			result.append(JavaLiteralUtils.mapToLiteral(value));
		} else {
			Powers.PowersType type = powers.getPowersType();
			if (type != null) {
				switch (type) {
					case MINUS: {
						result.append("-");
						break;
					}
					case PLUS: {
						break;
					}
				}
			}
			if (!powers.getPowers().isEmpty()) {
				// Exponentiation: A ** B → BigDecimal.valueOf(Math.pow(A.doubleValue(), B.doubleValue()))
				// Math.pow takes double args, so we must convert BigDecimal operands.
				result.append("BigDecimal.valueOf(");
			}
			for (Power power : powers.getPowers()) {
				result.append("Math.pow(");
			}
			String basisExpr = this.mapToExpression(powers.getBasis());
			if (!powers.getPowers().isEmpty()) {
				result.append("(").append(basisExpr).append(").doubleValue()");
			} else {
				result.append(basisExpr);
			}
			for (Power power : powers.getPowers()) {
				result.append(",");
				String powerExpr = this.mapToExpression(power.getBasis());
				result.append("(").append(powerExpr).append(").doubleValue()");
				result.append(")");
			}
			if (!powers.getPowers().isEmpty()) {
				result.append(")");
			}
		}
		return result.toString();
	}

	public String mapToExpression(RelationConditionValueStmt relationCondition) {
		Boolean value = this.valueService.getBoolean(this.valueStmtService.getValue((ValueStmt)relationCondition, null));
		StringBuffer result = new StringBuffer();
		if (value != null) {
			result.append(JavaLiteralUtils.mapToLiteral((Boolean)value));
		} else {
			RelationConditionValueStmt.RelationConditionType type = relationCondition.getRelationConditionType();
			switch (type) {
				case ARITHMETIC: {
					result.append(this.mapToExpression(relationCondition.getArithmeticComparison()));
					break;
				}
				case COMBINED: {
					result.append(this.mapToExpression(relationCondition.getCombinedComparison(), relationCondition));
					break;
				}
				case SIGN: {
					result.append(this.mapToExpression(relationCondition.getSignCondition()));
					break;
				}
			}
		}
		return result.toString();
	}

	public String mapToExpression(CombinedComparison combinedComparison, RelationConditionValueStmt parent) {
		StringBuffer result = new StringBuffer();
		ArithmeticValueStmt lhs = combinedComparison.getArithmeticExpression();
		CombinedCondition combinedCondition = combinedComparison.getCombinedCondition();
		if (combinedCondition != null) {
			CombinedCondition.CombinedConditionType condType = combinedCondition.getCombinedConditionType();
			RelationalOperator operator = combinedComparison.getOperator();
			RelationalOperator.RelationalOperatorType opType = operator != null ? operator.getRelationalOperatorType() : null;
			List<ArithmeticValueStmt> rhsExpressions = combinedCondition.getArithmeticExpressions();
			boolean isFirst = true;
			CobolTypeEnum lhsType = this.cobolTypeService.getType((ValueStmt)lhs);
			boolean lhsIsGroup = CobolTypeEnum.DATA_DESCRIPTION_GROUP.equals(lhsType);
			for (ArithmeticValueStmt rhs : rhsExpressions) {
				Object rhsStr;
				if (!isFirst) {
					if (CombinedCondition.CombinedConditionType.AND.equals(condType)) {
						result.append(" && ");
					} else {
						result.append(" || ");
					}
				}
				String lhsRaw = this.mapToExpression(lhs);
				Object lhsStr = lhsIsGroup ? "CobolMove.groupToString(" + lhsRaw + ")" : lhsRaw;
				CobolTypeEnum rhsType = this.cobolTypeService.getType((ValueStmt)rhs);
				boolean rhsIsGroup = CobolTypeEnum.DATA_DESCRIPTION_GROUP.equals(rhsType);
				String rhsRaw = this.mapToExpression(rhs);
				Object object = rhsStr = rhsIsGroup ? "CobolMove.groupToString(" + rhsRaw + ")" : this.normalizeForAlphanumericComparison(rhsRaw);
				if (opType != null) {
					result.append("(CobolComparison.compareAlphanumeric(").append((String)lhsStr).append(", ").append((String)rhsStr).append(") ");
					switch (opType) {
						case NOT_EQUAL: {
							result.append("!= 0)");
							break;
						}
						case EQUAL: {
							result.append("== 0)");
							break;
						}
						case GREATER: {
							result.append("> 0)");
							break;
						}
						case GREATER_OR_EQUAL: {
							result.append(">= 0)");
							break;
						}
						case LESS: {
							result.append("< 0)");
							break;
						}
						case LESS_OR_EQUAL: {
							result.append("<= 0)");
							break;
						}
						default: {
							result.append("== 0)");
							break;
						}
					}
				} else {
					result.append("(CobolComparison.compareAlphanumeric(").append((String)lhsStr).append(", ").append((String)rhsStr).append(") == 0)");
				}
				isFirst = false;
			}
		}
		return result.toString();
	}

	public String mapToExpression(ClassCondition classCondition) {
		CobolParser.ReferenceModifierContext refMod;
		CobolParser.ClassConditionContext classCtx;
		Object identifier;
		StringBuffer result = new StringBuffer();
		ClassCondition.ClassConditionType classType = classCondition.getClassConditionType();
		Call identifierCall = classCondition.getIdentifierCall();
		Object object = identifier = identifierCall != null ? this.mapToCall(identifierCall) : "null";
		if (identifierCall != null && !((String)identifier).contains("CobolReference.referenceModification(") && classCondition.getCtx() instanceof CobolParser.ClassConditionContext && (classCtx = (CobolParser.ClassConditionContext)classCondition.getCtx()).identifier() != null && (refMod = this.findReferenceModifier((ParseTree)classCtx.identifier())) != null) {
			Program refModProgram = classCondition.getProgram();
			String posExpr = this.convertArithExprCtxToJavaInt(refMod.characterPosition().arithmeticExpression(), refModProgram);
			identifier = refMod.length() != null ? "CobolReference.referenceModification(" + (String)identifier + ", " + posExpr + ", " + this.convertArithExprCtxToJavaInt(refMod.length().arithmeticExpression(), refModProgram) + ")" : "CobolReference.referenceModification(" + (String)identifier + ", " + posExpr + ")";
		}
		boolean isNot = classCondition.getNot();
		switch (classType) {
			case NUMERIC: {
				if (isNot) {
					result.append("!CobolRuntime.isNumeric(").append((String)identifier).append(")");
					break;
				}
				result.append("CobolRuntime.isNumeric(").append((String)identifier).append(")");
				break;
			}
			case ALPHABETIC: {
				if (isNot) {
					result.append("!CobolRuntime.isAlphabetic(").append((String)identifier).append(")");
					break;
				}
				result.append("CobolRuntime.isAlphabetic(").append((String)identifier).append(")");
				break;
			}
			case ALPHABETIC_LOWER: {
				if (isNot) {
					result.append("!CobolRuntime.isAlphabeticLower(").append((String)identifier).append(")");
					break;
				}
				result.append("CobolRuntime.isAlphabeticLower(").append((String)identifier).append(")");
				break;
			}
			case ALPHABETIC_UPPER: {
				if (isNot) {
					result.append("!CobolRuntime.isAlphabeticUpper(").append((String)identifier).append(")");
					break;
				}
				result.append("CobolRuntime.isAlphabeticUpper(").append((String)identifier).append(")");
				break;
			}
			case DBCS: {
				if (isNot) {
					result.append("!CobolRuntime.isDbcs(").append((String)identifier).append(")");
					break;
				}
				result.append("CobolRuntime.isDbcs(").append((String)identifier).append(")");
				break;
			}
			case KANJI: {
				if (isNot) {
					result.append("!CobolRuntime.isKanji(").append((String)identifier).append(")");
					break;
				}
				result.append("CobolRuntime.isKanji(").append((String)identifier).append(")");
				break;
			}
			case CLASS_NAME: {
				String className;
				Call classCall = classCondition.getClassCall();
				String string = className = classCall != null ? classCall.getName() : "UNKNOWN";
				if (isNot) {
					result.append("!CobolRuntime.isClass(").append((String)identifier).append(", \"").append(className).append("\")");
					break;
				}
				result.append("CobolRuntime.isClass(").append((String)identifier).append(", \"").append(className).append("\")");
				break;
			}
			default: {
				result.append("true /* unsupported class condition */");
			}
		}
		return result.toString();
	}

	public String mapToExpression(SimpleCondition simpleCondition) {
		Boolean value = this.valueService.getBoolean(this.valueStmtService.getValue((ValueStmt)simpleCondition, null));
		StringBuffer result = new StringBuffer();
		if (value != null) {
			result.append(JavaLiteralUtils.mapToLiteral((Boolean)value));
		} else {
			SimpleCondition.SimpleConditionType type = simpleCondition.getSimpleConditionType();
			switch (type) {
				case CLASS_CONDITION: {
					result.append(this.mapToExpression(simpleCondition.getClassCondition()));
					break;
				}
				case CONDITION: {
					result.append("(");
					result.append(this.mapToExpression(simpleCondition.getCondition()));
					result.append(")");
					break;
				}
				case CONDITION_NAME_REFERENCE: {
					result.append(this.mapToExpression(simpleCondition.getConditionNameReference()));
					break;
				}
				case RELATION_CONDITION: {
					result.append(this.mapToExpression(simpleCondition.getRelationCondition()));
					break;
				}
			}
		}
		return result.toString();
	}

	public String mapToExpression(ValueStmt valueStmt) {
		StringBuffer result = new StringBuffer();
		CobolValue value = this.valueStmtService.getValue(valueStmt, null);
		BigDecimal decimalValue = this.valueService.getDecimal(value);
		Boolean booleanValue = this.valueService.getBoolean(value);
		String stringValue = this.valueService.getString(value);
		if (decimalValue != null) {
			result.append(JavaLiteralUtils.mapToLiteral((BigDecimal)decimalValue));
		} else if (booleanValue != null) {
			result.append(JavaLiteralUtils.mapToLiteral((Boolean)booleanValue));
		} else if (stringValue != null) {
			result.append(JavaLiteralUtils.mapToLiteral((String)stringValue));
		} else if (valueStmt instanceof AndOrCondition) {
			result.append(this.mapToExpression((AndOrCondition)valueStmt));
		} else if (valueStmt instanceof ArithmeticValueStmt) {
			result.append(this.mapToExpression((ArithmeticValueStmt)valueStmt));
		} else if (valueStmt instanceof ArithmeticComparison) {
			result.append(this.mapToExpression((ArithmeticComparison)valueStmt));
		} else if (valueStmt instanceof Basis) {
			result.append(this.mapToExpression((Basis)valueStmt));
		} else if (valueStmt instanceof CallValueStmt) {
			result.append(this.mapToExpression((CallValueStmt)valueStmt));
		} else if (valueStmt instanceof CombinableCondition) {
			result.append(this.mapToExpression((CombinableCondition)valueStmt));
		} else if (valueStmt instanceof ConditionValueStmt) {
			result.append(this.mapToExpression((ConditionValueStmt)valueStmt));
		} else if (valueStmt instanceof ConditionNameReference) {
			result.append(this.mapToExpression((ConditionNameReference)valueStmt));
		} else if (valueStmt instanceof MultDiv) {
			result.append(this.mapToExpression((MultDiv)valueStmt));
		} else if (valueStmt instanceof MultDivs) {
			result.append(this.mapToExpression((MultDivs)valueStmt));
		} else if (valueStmt instanceof PlusMinus) {
			result.append(this.mapToExpression((PlusMinus)valueStmt));
		} else if (valueStmt instanceof Powers) {
			result.append(this.mapToExpression((Powers)valueStmt));
		} else if (valueStmt instanceof RelationConditionValueStmt) {
			result.append(this.mapToExpression((RelationConditionValueStmt)valueStmt));
		} else if (valueStmt instanceof ClassCondition) {
			result.append(this.mapToExpression((ClassCondition)valueStmt));
		} else if (valueStmt instanceof SimpleCondition) {
			result.append(this.mapToExpression((SimpleCondition)valueStmt));
		}
		return result.toString();
	}

	/**
	 * Build a subscript expression for condition array access (e.g., IND-ON(IN52)).
	 * When the subscript resolves to a boolean (wrong ASG resolution due to name
	 * collision with DDS indicator fields), find the numeric WORKING-STORAGE constant
	 * with the same name and use its literal value instead.
	 *
	 * @param subscript the subscript to resolve
	 * @param contextDde a DataDescriptionEntry from the same program (for ProgramUnit access)
	 * @return the subscript expression string (e.g., "51" or "r_ind.in52.intValue() - 1")
	 */
	String buildSubscriptExprForCondition(Subscript subscript, DataDescriptionEntry contextDde) {
		String subExpr = this.mapToExpression(subscript.getSubscriptValueStmt());
		// Check if the subscript resolved to a boolean (wrong ASG resolution)
		ValueStmt subVS = subscript.getSubscriptValueStmt();
		if (subVS instanceof CallValueStmt) {
			Call subCall = ((CallValueStmt) subVS).getCall();
			if (subCall != null) {
				Call unwrapped = subCall.unwrap();
				if (unwrapped != null && unwrapped.getCallType() == Call.CallType.DATA_DESCRIPTION_ENTRY_CALL) {
					DataDescriptionEntry subDde = ((DataDescriptionEntryCall) unwrapped).getDataDescriptionEntry();
					if (subDde != null && CobolTypeEnum.BOOLEAN.equals(this.cobolTypeService.getType(subDde))) {
						// Boolean subscript: find the numeric WORKING-STORAGE field with same name
						BigDecimal numericValue = findNumericWsFieldValue(subDde.getName(), contextDde);
						if (numericValue != null) {
							return String.format("%d", numericValue.intValue() - 1);
						}
					}
				}
			}
		}
		return subExpr + ".intValue() - 1";
	}

	/**
	 * Search WORKING-STORAGE for a numeric field with the given name and a VALUE clause.
	 * Used to resolve boolean/numeric name collisions for DDS indicator subscripts.
	 */
	private BigDecimal findNumericWsFieldValue(String fieldName, DataDescriptionEntry contextDde) {
		if (fieldName == null || contextDde == null) return null;
		ProgramUnit pu = contextDde.getProgramUnit();
		if (pu == null || pu.getDataDivision() == null) return null;
		io.proleap.cobol.asg.metamodel.data.workingstorage.WorkingStorageSection ws =
				pu.getDataDivision().getWorkingStorageSection();
		if (ws == null) return null;
		return findNumericValueByNameRecursive(ws.getDataDescriptionEntries(), fieldName);
	}

	/**
	 * Recursively search data description entries for a numeric field with a VALUE clause.
	 */
	private BigDecimal findNumericValueByNameRecursive(List<DataDescriptionEntry> entries, String name) {
		for (DataDescriptionEntry entry : entries) {
			if (name.equalsIgnoreCase(entry.getName()) && entry instanceof DataDescriptionEntryGroup) {
				DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) entry;
				CobolTypeEnum type = this.cobolTypeService.getType(entry);
				if (CobolTypeEnum.INTEGER.equals(type) || CobolTypeEnum.FLOAT.equals(type)) {
					ValueClause vc = group.getValueClause();
					if (vc != null && !vc.getValueIntervals().isEmpty()) {
						ValueInterval vi = vc.getValueIntervals().get(0);
						CobolValue cv = this.valueStmtService.getValue(vi.getFromValueStmt(), null);
						BigDecimal dv = this.valueService.getDecimal(cv);
						if (dv != null) return dv;
					}
				}
			}
			if (entry instanceof DataDescriptionEntryGroup) {
				BigDecimal result = findNumericValueByNameRecursive(
						((DataDescriptionEntryGroup) entry).getDataDescriptionEntries(), name);
				if (result != null) return result;
			}
		}
		return null;
	}
}
