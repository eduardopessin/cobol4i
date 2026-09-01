package io.proleap.cobol.asg.metamodel.data.datadescription;

import io.proleap.cobol.asg.metamodel.CobolDivisionElement;

public interface IndicatorClause extends CobolDivisionElement {

	Integer getIndicatorNumber();

	void setIndicatorNumber(Integer indicatorNumber);
}
