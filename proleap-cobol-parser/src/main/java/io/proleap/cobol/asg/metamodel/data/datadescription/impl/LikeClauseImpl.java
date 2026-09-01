package io.proleap.cobol.asg.metamodel.data.datadescription.impl;

import io.proleap.cobol.CobolParser.DataLikeClauseContext;
import io.proleap.cobol.asg.metamodel.ProgramUnit;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.data.datadescription.LikeClause;
import io.proleap.cobol.asg.metamodel.impl.CobolDivisionElementImpl;

public class LikeClauseImpl extends CobolDivisionElementImpl implements LikeClause {

	protected DataLikeClauseContext ctx;

	protected Call likeCall;

	protected Integer lengthModifier;

	public LikeClauseImpl(final ProgramUnit programUnit, final DataLikeClauseContext ctx) {
		super(programUnit, ctx);
		this.ctx = ctx;
	}

	@Override
	public Call getLikeCall() {
		return likeCall;
	}

	@Override
	public void setLikeCall(final Call likeCall) {
		this.likeCall = likeCall;
	}

	@Override
	public Integer getLengthModifier() {
		return lengthModifier;
	}

	@Override
	public void setLengthModifier(final Integer lengthModifier) {
		this.lengthModifier = lengthModifier;
	}
}
