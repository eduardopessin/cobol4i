package io.proleap.cobol.asg.metamodel.data.datadescription;

import io.proleap.cobol.asg.metamodel.CobolDivisionElement;

public interface FormatClause extends CobolDivisionElement {

	enum FormatType {
		DATE, TIME, TIMESTAMP
	}

	FormatType getFormatType();

	void setFormatType(FormatType formatType);

	String getFormatPattern();

	void setFormatPattern(String formatPattern);

	Integer getSize();

	void setSize(Integer size);

	String getLocale();

	void setLocale(String locale);
}
