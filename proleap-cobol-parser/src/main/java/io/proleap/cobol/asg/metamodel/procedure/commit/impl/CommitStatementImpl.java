/*
 * Copyright (C) 2017, Ulrich Wolffgang <ulrich.wolffgang@proleap.io>
 * All rights reserved.
 *
 * This software may be modified and distributed under the terms
 * of the MIT license. See the LICENSE file for details.
 */

package io.proleap.cobol.asg.metamodel.procedure.commit.impl;

import io.proleap.cobol.CobolParser.CommitStatementContext;
import io.proleap.cobol.asg.metamodel.ProgramUnit;
import io.proleap.cobol.asg.metamodel.Scope;
import io.proleap.cobol.asg.metamodel.procedure.StatementType;
import io.proleap.cobol.asg.metamodel.procedure.StatementTypeEnum;
import io.proleap.cobol.asg.metamodel.procedure.commit.CommitStatement;
import io.proleap.cobol.asg.metamodel.procedure.impl.StatementImpl;

public class CommitStatementImpl extends StatementImpl implements CommitStatement {

	protected final CommitStatementContext ctx;

	protected final StatementType statementType = StatementTypeEnum.COMMIT;

	public CommitStatementImpl(final ProgramUnit programUnit, final Scope scope, final CommitStatementContext ctx) {
		super(programUnit, scope, ctx);

		this.ctx = ctx;
	}

	@Override
	public StatementType getStatementType() {
		return statementType;
	}

}
