package io.proleap.cobol.transform.java.rules.lang.data.linkage;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.LinkageSectionContext;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry.DataDescriptionEntryType;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.data.datadescription.Index;
import io.proleap.cobol.asg.metamodel.data.datadescription.OccursClause;
import io.proleap.cobol.asg.metamodel.data.linkage.LinkageSection;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class LinkageSectionRule extends CobolTransformRule<LinkageSectionContext, LinkageSection> {

	@Override
	public void apply(final LinkageSectionContext ctx, final LinkageSection linkageSection, final RuleContext rc) {
		for (final DataDescriptionEntry dataDescriptionEntry : linkageSection.getRootDataDescriptionEntries()) {
			rc.visit(dataDescriptionEntry.getCtx());
		}

		// Emit INDEXED BY variables as program-level BigDecimal fields.
		// COBOL INDEX items are used as numeric subscripts in SEARCH and array access.
		final List<Index> indexedByVars = new ArrayList<>();
		collectIndexedByVariables(linkageSection.getRootDataDescriptionEntries(), indexedByVars);

		for (final Index index : indexedByVars) {
			final String name = index.getName();
			if (name != null) {
				final String javaName = name.toLowerCase().replace("-", "_");
				rc.p("protected BigDecimal %s = BigDecimal.ONE;", javaName);
				rc.pNl();
			}
		}
	}

	/**
	 * Recursively collects all Index items from INDEXED BY clauses
	 * in OCCURS throughout the data description hierarchy.
	 */
	private void collectIndexedByVariables(final List<DataDescriptionEntry> entries, final List<Index> result) {
		for (final DataDescriptionEntry entry : entries) {
			if (entry.getDataDescriptionEntryType() == DataDescriptionEntryType.GROUP) {
				final DataDescriptionEntryGroup group = (DataDescriptionEntryGroup) entry;

				// Check OCCURS clauses for INDEXED BY
				if (group.getOccursClauses() != null) {
					for (final OccursClause occurs : group.getOccursClauses()) {
						if (occurs.getOccursIndexed() != null && occurs.getOccursIndexed().getIndices() != null) {
							result.addAll(occurs.getOccursIndexed().getIndices());
						}
					}
				}

				// Recurse into children
				if (group.getDataDescriptionEntries() != null) {
					collectIndexedByVariables(group.getDataDescriptionEntries(), result);
				}
			}
		}
	}

	@Override
	public Class<LinkageSectionContext> from() {
		return LinkageSectionContext.class;
	}
}
