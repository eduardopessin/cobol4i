package io.proleap.cobol.asg.metamodel.data.datadescription.impl;

import io.proleap.cobol.CobolParser.DataIndicatorClauseContext;
import io.proleap.cobol.asg.metamodel.ProgramUnit;
import io.proleap.cobol.asg.metamodel.data.datadescription.IndicatorClause;
import io.proleap.cobol.asg.metamodel.impl.CobolDivisionElementImpl;

public class IndicatorClauseImpl extends CobolDivisionElementImpl implements IndicatorClause {

	protected DataIndicatorClauseContext ctx;

	protected Integer indicatorNumber;

	public IndicatorClauseImpl(final ProgramUnit programUnit, final DataIndicatorClauseContext ctx) {
		super(programUnit, ctx);
		this.ctx = ctx;
	}

	@Override
	public Integer getIndicatorNumber() {
		return indicatorNumber;
	}

	@Override
	public void setIndicatorNumber(final Integer indicatorNumber) {
		this.indicatorNumber = indicatorNumber;
	}
}
