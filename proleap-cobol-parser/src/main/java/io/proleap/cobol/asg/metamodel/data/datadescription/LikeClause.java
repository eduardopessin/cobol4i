package io.proleap.cobol.asg.metamodel.data.datadescription;

import io.proleap.cobol.asg.metamodel.CobolDivisionElement;
import io.proleap.cobol.asg.metamodel.call.Call;

public interface LikeClause extends CobolDivisionElement {

	Call getLikeCall();

	void setLikeCall(Call likeCall);

	Integer getLengthModifier();

	void setLengthModifier(Integer lengthModifier);
}
