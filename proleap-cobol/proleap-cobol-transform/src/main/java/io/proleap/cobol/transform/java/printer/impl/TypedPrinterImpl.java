package io.proleap.cobol.transform.java.printer.impl;

import org.antlr.v4.runtime.tree.ParseTree;

import io.proleap.cobol.commons.type.CobolTypeEnum;
import io.proleap.cobol.transform.java.printer.TypedPrinter;
import io.proleap.cobol.transform.rule.RuleContext;

public class TypedPrinterImpl implements TypedPrinter {

	protected class Printable {

		protected final ParseTree ctx;

		protected final String suffix;

		public Printable(final ParseTree ctx) {
			this.ctx = ctx;
			suffix = null;
		}

		public Printable(final ParseTree ctx, final String suffix) {
			this.ctx = ctx;
			this.suffix = suffix;
		}

		public String getString() {
			return ctx.getText();
		}

		public void print() {
			rc.visit(ctx);

			if (suffix != null) {
				rc.p(suffix);
			}
		}
	}

	protected final RuleContext rc;

	public TypedPrinterImpl(final RuleContext rc) {
		this.rc = rc;
	}

	@Override
	public void printWithAdjustedType(final ParseTree ctx, final CobolTypeEnum originalType,
			final CobolTypeEnum typeToConvertTo) {
		final Printable printable = new Printable(ctx);

		printWithAdjustedType(printable, originalType, typeToConvertTo);
	}

	@Override
	public void printWithAdjustedType(final ParseTree ctx, final String suffix, final CobolTypeEnum originalType,
			final CobolTypeEnum typeToConvertTo) {
		final Printable printable = new Printable(ctx, suffix);

		printWithAdjustedType(printable, originalType, typeToConvertTo);
	}

	protected void printWithAdjustedType(final Printable printable, final CobolTypeEnum originalType,
			final CobolTypeEnum typeToConvertTo) {

		final boolean isNull = printable.getString().toLowerCase().equals("null");

		// null value
		if (isNull) {
			printable.print();
		}
		// same types
		else if (originalType == typeToConvertTo) {
			printable.print();
		}
		// BOOLEAN ---> INTEGER
		else if (CobolTypeEnum.BOOLEAN.equals(originalType) && CobolTypeEnum.INTEGER.equals(typeToConvertTo)) {
			rc.p("BigDecimal.valueOf(");
			printable.print();
			rc.p(")");
		}
		// BOOLEAN ---> STRING
		else if (CobolTypeEnum.BOOLEAN.equals(originalType) && CobolTypeEnum.STRING.equals(typeToConvertTo)) {
			rc.p("String.valueOf(");
			printable.print();
			rc.p(")");
		}
		// INTEGER ---> BOOLEAN
		else if (CobolTypeEnum.INTEGER.equals(originalType) && CobolTypeEnum.BOOLEAN.equals(typeToConvertTo)) {
			rc.p("Boolean.valueOf(");
			printable.print();
			rc.p(")");
		}
		// INTEGER ---> STRING
		else if (CobolTypeEnum.INTEGER.equals(originalType) && CobolTypeEnum.STRING.equals(typeToConvertTo)) {
			rc.p("String.valueOf(");
			printable.print();
			rc.p(")");
		}
		// FLOAT ---> STRING
		else if (CobolTypeEnum.FLOAT.equals(originalType) && CobolTypeEnum.STRING.equals(typeToConvertTo)) {
			rc.p("String.valueOf(");
			printable.print();
			rc.p(")");
		}
		// STRING ---> INTEGER
		else if (CobolTypeEnum.STRING.equals(originalType) && CobolTypeEnum.INTEGER.equals(typeToConvertTo)) {
			rc.p("new BigDecimal(");
			printable.print();
			rc.p(".trim())");
		}
		// DATA DESCRIPTION ENTRY ---> STRING
		else if (CobolTypeEnum.DATA_DESCRIPTION_GROUP.equals(originalType)
				&& CobolTypeEnum.STRING.equals(typeToConvertTo)) {
			rc.p("String.valueOf(");
			printable.print();
			rc.p(")");
		}
		// null ---> any: pass through without conversion (source type unknown)
		else if (originalType == null) {
			printable.print();
		}
		// null --> *
		else if (originalType == null) {
			printable.print();
		}

		// --------------- Fallbacks --------------------

		// * ---> *
		else {
			printable.print();
		}
	}
}
