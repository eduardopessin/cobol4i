package com.example.orders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Idiomatic Java rewrite of the generated {@code ORDRPT} program.
 *
 * <p>This is step 2 of the migration approach demonstrated by this example:
 *
 * <pre>
 *   COBOL  --[deterministic transform]-->  generated Java  --[refactor]-->  this
 * </pre>
 *
 * <p>The generated Java is deliberately unidiomatic: flat inner classes
 * mirroring DDS record formats, {@code BigDecimal} everywhere, COBOL paragraph
 * names as methods. That is the point — it is a faithful, auditable
 * translation, not a pretty one.
 *
 * <p>This class is what a developer (or an LLM) produces from it: records,
 * streams, meaningful names, no DDS artefacts. The safety net is that both
 * versions must produce byte-identical output, which
 * {@code EquivalenceTest} enforces. The generated version acts as the
 * executable specification.
 *
 * <p>Nothing here consults the original COBOL. Everything was derived from the
 * generated Java, which is the contract.
 */
public final class OrderReport {

	/** VAT rate applied to the order total, as a percentage. */
	private static final BigDecimal VAT_RATE = new BigDecimal("23.00");

	/** Line amount above which a line counts as high value. */
	private static final BigDecimal HIGH_VALUE_THRESHOLD = new BigDecimal("300");

	/** Quantity above which a line counts as bulk. */
	private static final BigDecimal BULK_THRESHOLD = new BigDecimal("100");

	/** Width of the fixed-width description field in the DDS record. */
	private static final int DESCRIPTION_WIDTH = 30;

	/** Width of the report line the COBOL program built with STRING. */
	private static final int REPORT_LINE_WIDTH = 80;

	/**
	 * How a line is classified. In the COBOL original this drove DDS
	 * indicators *IN50 and *IN51, which switched 5250 screen attributes.
	 */
	public enum Category {

		HIGH_VALUE("HIGH VALUE", true, false),
		BULK("BULK", false, true),
		STANDARD("STANDARD", false, false);

		private final String label;

		private final boolean indicator50;

		private final boolean indicator51;

		Category(final String label, final boolean indicator50, final boolean indicator51) {
			this.label = label;
			this.indicator50 = indicator50;
			this.indicator51 = indicator51;
		}

		public String label() {
			return label;
		}

		public boolean indicator50() {
			return indicator50;
		}

		public boolean indicator51() {
			return indicator51;
		}
	}

	/** An order header. Replaces the ORDHDR DDS record format. */
	public record OrderHeader(int orderNumber, String customer, String date, String currency) {
	}

	/** An order line as supplied. Replaces the input half of ORDLIN. */
	public record OrderLine(int number, String itemCode, String description, int quantity, BigDecimal unitPrice) {
	}

	/** An order line after pricing. */
	public record PricedLine(OrderLine line, BigDecimal amount, Category category) {

		/**
		 * Spaces in the fixed-width description field.
		 *
		 * <p>Mirrors COBOL {@code INSPECT ... TALLYING FOR ALL SPACES}, which
		 * counts <em>every</em> space in the field, not just the trailing
		 * padding — "BLUE WIDGET" in a PIC X(30) field yields 20, not 19,
		 * because the space between the two words counts too.
		 */
		public int spaceCount() {
			final String padded = line.description().length() >= DESCRIPTION_WIDTH
					? line.description().substring(0, DESCRIPTION_WIDTH)
					: line.description() + " ".repeat(DESCRIPTION_WIDTH - line.description().length());

			return (int) padded.chars().filter(c -> c == ' ').count();
		}

		/** Length of the DDS VARCHAR payload. */
		public int descriptionLength() {
			return line.description().trim().length();
		}
	}

	/** Totals for the whole report. */
	public record Totals(int lineCount, BigDecimal net, BigDecimal vat, BigDecimal gross) {
	}

	private final Appendable out;

	public OrderReport(final Appendable out) {
		this.out = out;
	}

	/**
	 * Prices a single line.
	 *
	 * <p>The rounding is HALF_UP to two decimals, matching the COBOL
	 * {@code COMPUTE ... ROUNDED} into a {@code PIC S9(9)V99 COMP-3} field.
	 */
	public static PricedLine price(final OrderLine line) {
		final BigDecimal amount = BigDecimal.valueOf(line.quantity())
				.multiply(line.unitPrice())
				.setScale(2, RoundingMode.HALF_UP);

		return new PricedLine(line, amount, categorise(amount, line.quantity()));
	}

	/**
	 * Classifies a line. Order matters: the COBOL EVALUATE tested the amount
	 * before the quantity, so a line that is both high value and bulk is
	 * reported as high value.
	 */
	private static Category categorise(final BigDecimal amount, final int quantity) {
		if (amount.compareTo(HIGH_VALUE_THRESHOLD) > 0) {
			return Category.HIGH_VALUE;
		}

		if (BigDecimal.valueOf(quantity).compareTo(BULK_THRESHOLD) > 0) {
			return Category.BULK;
		}

		return Category.STANDARD;
	}

	/** Sums the priced lines and applies VAT. */
	public static Totals total(final List<PricedLine> lines) {
		final BigDecimal net = lines.stream()
				.map(PricedLine::amount)
				.reduce(BigDecimal.ZERO, BigDecimal::add)
				.setScale(2, RoundingMode.HALF_UP);

		final BigDecimal vat = net.multiply(VAT_RATE)
				.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

		return new Totals(lines.size(), net, vat, net.add(vat));
	}

	// ---------------------------------------------------------------- output
	// The formatting below reproduces the generated program's output exactly,
	// including its quirks: amounts inside the report line are printed as an
	// unformatted 14-digit field because the COBOL PIC ZZZ,ZZZ,ZZ9.99 edit mask
	// is not applied by the runtime. Reproducing the quirk is deliberate — the
	// equivalence test would otherwise fail, and a behaviour change must be a
	// separate, visible commit rather than a side effect of refactoring.

	private void line(final String text) {
		try {
			out.append(text).append(System.lineSeparator());
		} catch (final java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	/** Renders an amount the way the generated code does: 14 digits, no mask. */
	private static String unmaskedAmount(final BigDecimal amount) {
		return String.format("%014d", amount.movePointRight(2).longValueExact() / 100);
	}

	private static String pad(final String value, final int width) {
		return value.length() >= width ? value : value + " ".repeat(width - value.length());
	}

	public void printHeader(final OrderHeader header) {
		line("=== ORDER LINE PRICING REPORT ===");
		line(" ");
		line("ORDER  : " + header.orderNumber());
		line("CUSTOMER: " + pad(header.customer(), 20));
		line("DATE   : " + header.date());
		line("CURRENCY: " + header.currency());
		line(" ");
	}

	public void printLine(final PricedLine priced) {
		final OrderLine line = priced.line();

		final String reportLine = "LINE " + String.format("%03d", line.number())
				+ "  ITEM " + pad(line.itemCode(), 8)
				+ "  AMT " + unmaskedAmount(priced.amount())
				+ "  " + pad(priced.category().label(), 12);

		line(pad(reportLine, REPORT_LINE_WIDTH));
		line("     DESC='" + pad(line.description(), DESCRIPTION_WIDTH) + "'");
		line("     VARCHAR LEN=" + priced.descriptionLength()
				+ "  TRAILING SPACES=" + priced.spaceCount());
		line("     IND50=" + priced.category().indicator50()
				+ " IND51=" + priced.category().indicator51());
		line(" ");
	}

	public void printTotals(final Totals totals) {
		line("=== TOTALS ===");
		line("LINES     : " + totals.lineCount());
		line("NET       : " + totals.net());
		line("VAT (23%) : " + totals.vat());
		line("GROSS     : " + totals.gross());
	}

	/** Runs the whole report: header, priced lines, totals. */
	public Totals run(final OrderHeader header, final List<OrderLine> lines) {
		printHeader(header);

		final List<PricedLine> priced = lines.stream().map(OrderReport::price).toList();
		priced.forEach(this::printLine);

		final Totals totals = total(priced);
		printTotals(totals);

		return totals;
	}

	/** The same sample data the COBOL program hard-coded. */
	public static OrderHeader sampleHeader() {
		return new OrderHeader(1042, "NORTHWIND SUPPLIES", "2026-09-01", "EUR");
	}

	public static List<OrderLine> sampleLines() {
		return List.of(
				new OrderLine(1, "WIDGET01", "BLUE WIDGET", 12, new BigDecimal("4.50")),
				new OrderLine(2, "GEAR0042", "STEEL GEAR ASSEMBLY", 3, new BigDecimal("129.99")),
				new OrderLine(3, "BOLT0007", "HEX BOLT M8", 250, new BigDecimal("0.15")));
	}

	public static void main(final String[] args) {
		final StringBuilder sb = new StringBuilder();
		new OrderReport(sb).run(sampleHeader(), sampleLines());
		System.out.print(sb);
	}
}
