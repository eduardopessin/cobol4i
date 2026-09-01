package io.proleap.cobol.asg.metamodel.data.datadescription.impl;

import io.proleap.cobol.CobolParser.DataFormatClauseContext;
import io.proleap.cobol.asg.metamodel.ProgramUnit;
import io.proleap.cobol.asg.metamodel.data.datadescription.FormatClause;
import io.proleap.cobol.asg.metamodel.impl.CobolDivisionElementImpl;

public class FormatClauseImpl extends CobolDivisionElementImpl implements FormatClause {

	protected DataFormatClauseContext ctx;

	protected FormatType formatType;

	protected String formatPattern;

	protected Integer size;

	protected String locale;

	public FormatClauseImpl(final ProgramUnit programUnit, final DataFormatClauseContext ctx) {
		super(programUnit, ctx);
		this.ctx = ctx;
	}

	@Override
	public FormatType getFormatType() {
		return formatType;
	}

	@Override
	public void setFormatType(final FormatType formatType) {
		this.formatType = formatType;
	}

	@Override
	public String getFormatPattern() {
		return formatPattern;
	}

	@Override
	public void setFormatPattern(final String formatPattern) {
		this.formatPattern = formatPattern;
	}

	@Override
	public Integer getSize() {
		return size;
	}

	@Override
	public void setSize(final Integer size) {
		this.size = size;
	}

	@Override
	public String getLocale() {
		return locale;
	}

	@Override
	public void setLocale(final String locale) {
		this.locale = locale;
	}
}
