package io.proleap.cobol.transform.java.rules.lang;

import jakarta.inject.Singleton;

import io.proleap.cobol.CobolParser.LiteralContext;
import io.proleap.cobol.asg.metamodel.Literal;
import io.proleap.cobol.transform.rule.CobolTransformRule;
import io.proleap.cobol.transform.rule.RuleContext;

@Singleton
public class CobolLiteralRule extends CobolTransformRule<LiteralContext, Literal> {

	@Override
	public void apply(final LiteralContext ctx, final Literal literal, final RuleContext rc) {

		switch (literal.getLiteralType()) {
		case BOOLEAN:
			printBoolean(literal, rc);
			break;
		case NON_NUMERIC:
			printNonNumeric(literal, rc);
			break;
		case NUMERIC:
			printNumeric(literal, rc);
			break;
		case FIGURATIVE_CONSTANT:
			printFigurativeConstant(literal, rc);
			break;
		case CICS_DFH_RESP:
			break;
		case CICS_DFH_VALUE:
			break;
		default:
			break;
		}
	}

	protected String escapeBackslash(final String str) {
		final String result = str.replace("\\", "\\\\");
		return result;
	}

	protected String escapeQuote(final String str) {
		final String result = str.replaceAll("\"\"", "\\\\\"");
		return result;
	}

	@Override
	public Class<LiteralContext> from() {
		return LiteralContext.class;
	}

	protected void printBoolean(final Literal literal, final RuleContext rc) {
		rc.visit(literal.getBooleanLiteral().getCtx());
	}

	protected void printFigurativeConstant(final Literal literal, final RuleContext rc) {
		rc.visit(literal.getFigurativeConstant().getCtx());
	}

	/**
	 * EBCDIC to ASCII/Latin-1 conversion table.
	 * Maps EBCDIC byte values (0x00-0xFF) to their ASCII/Unicode equivalents.
	 * Covers CCSID 037 (US/Canada) plus CCSID 285/1146 (UK) variant positions,
	 * so that hex literals from either code page are correctly converted.
	 * Used for converting COBOL hex literals (X"...") from AS/400 EBCDIC to Java strings.
	 */
	private static final int[] EBCDIC_037_TO_ASCII = new int[256];
	static {
		// Initialize all to Unicode replacement char
		java.util.Arrays.fill(EBCDIC_037_TO_ASCII, 0xFFFD);
		// Control characters
		EBCDIC_037_TO_ASCII[0x00] = 0x00; // NUL
		EBCDIC_037_TO_ASCII[0x01] = 0x01; // SOH
		EBCDIC_037_TO_ASCII[0x02] = 0x02; // STX
		EBCDIC_037_TO_ASCII[0x03] = 0x03; // ETX
		EBCDIC_037_TO_ASCII[0x05] = 0x09; // HT (EBCDIC HT)
		EBCDIC_037_TO_ASCII[0x07] = 0x7F; // DEL
		EBCDIC_037_TO_ASCII[0x0B] = 0x0B; // VT
		EBCDIC_037_TO_ASCII[0x0C] = 0x0C; // FF
		EBCDIC_037_TO_ASCII[0x0D] = 0x0D; // CR
		EBCDIC_037_TO_ASCII[0x0E] = 0x0E; // SO
		EBCDIC_037_TO_ASCII[0x0F] = 0x0F; // SI
		EBCDIC_037_TO_ASCII[0x10] = 0x10; // DLE
		EBCDIC_037_TO_ASCII[0x11] = 0x11; // DC1
		EBCDIC_037_TO_ASCII[0x12] = 0x12; // DC2
		EBCDIC_037_TO_ASCII[0x13] = 0x13; // DC3
		EBCDIC_037_TO_ASCII[0x15] = 0x0A; // NL → LF (EBCDIC newline)
		EBCDIC_037_TO_ASCII[0x16] = 0x08; // BS
		EBCDIC_037_TO_ASCII[0x1A] = 0x1A; // SUB
		EBCDIC_037_TO_ASCII[0x1C] = 0x1C; // FS
		EBCDIC_037_TO_ASCII[0x1D] = 0x1D; // GS
		EBCDIC_037_TO_ASCII[0x1E] = 0x1E; // RS
		EBCDIC_037_TO_ASCII[0x1F] = 0x1F; // US
		EBCDIC_037_TO_ASCII[0x25] = 0x0A; // LF (EBCDIC LF → ASCII LF)
		EBCDIC_037_TO_ASCII[0x26] = 0x17; // ETB
		EBCDIC_037_TO_ASCII[0x27] = 0x1B; // ESC
		EBCDIC_037_TO_ASCII[0x2F] = 0x1A; // SUB
		EBCDIC_037_TO_ASCII[0x37] = 0x04; // EOT
		// Space
		EBCDIC_037_TO_ASCII[0x40] = 0x20; // SP
		// Special characters
		EBCDIC_037_TO_ASCII[0x4A] = 0xA2; // ¢
		EBCDIC_037_TO_ASCII[0x4B] = 0x2E; // .
		EBCDIC_037_TO_ASCII[0x4C] = 0x3C; // <
		EBCDIC_037_TO_ASCII[0x4D] = 0x28; // (
		EBCDIC_037_TO_ASCII[0x4E] = 0x2B; // +
		EBCDIC_037_TO_ASCII[0x4F] = 0x7C; // |
		EBCDIC_037_TO_ASCII[0x50] = 0x26; // &
		EBCDIC_037_TO_ASCII[0x5A] = 0x21; // !
		EBCDIC_037_TO_ASCII[0x5B] = 0x24; // $
		EBCDIC_037_TO_ASCII[0x5C] = 0x2A; // *
		EBCDIC_037_TO_ASCII[0x5D] = 0x29; // )
		EBCDIC_037_TO_ASCII[0x5E] = 0x3B; // ;
		EBCDIC_037_TO_ASCII[0x5F] = 0xAC; // ¬
		EBCDIC_037_TO_ASCII[0x60] = 0x2D; // -
		EBCDIC_037_TO_ASCII[0x61] = 0x2F; // /
		EBCDIC_037_TO_ASCII[0x6B] = 0x2C; // ,
		EBCDIC_037_TO_ASCII[0x6C] = 0x25; // %
		EBCDIC_037_TO_ASCII[0x6D] = 0x5F; // _
		EBCDIC_037_TO_ASCII[0x6E] = 0x3E; // >
		EBCDIC_037_TO_ASCII[0x6F] = 0x3F; // ?
		EBCDIC_037_TO_ASCII[0x7A] = 0x3A; // :
		EBCDIC_037_TO_ASCII[0x7B] = 0x23; // #
		EBCDIC_037_TO_ASCII[0x7C] = 0x40; // @
		EBCDIC_037_TO_ASCII[0x7D] = 0x27; // '
		EBCDIC_037_TO_ASCII[0x7E] = 0x3D; // =
		EBCDIC_037_TO_ASCII[0x7F] = 0x22; // "
		// Brackets and braces — covers both CCSID 037 and CCSID 285/1146 (UK) positions
		EBCDIC_037_TO_ASCII[0xAD] = 0x5B; // [ (CCSID 037)
		EBCDIC_037_TO_ASCII[0xB1] = 0x5B; // [ (CCSID 285/1146 UK)
		EBCDIC_037_TO_ASCII[0xBD] = 0x5D; // ] (CCSID 037)
		EBCDIC_037_TO_ASCII[0xBB] = 0x5D; // ] (CCSID 285/1146 UK)
		EBCDIC_037_TO_ASCII[0xC0] = 0x7B; // {
		EBCDIC_037_TO_ASCII[0xD0] = 0x7D; // }
		EBCDIC_037_TO_ASCII[0xE0] = 0x5C; // backslash
		EBCDIC_037_TO_ASCII[0xA1] = 0x7E; // ~
		EBCDIC_037_TO_ASCII[0xB0] = 0x5E; // ^ (CCSID 037)
		EBCDIC_037_TO_ASCII[0x79] = 0x60; // ` (backtick)
		// Lowercase letters
		EBCDIC_037_TO_ASCII[0x81] = 0x61; // a
		EBCDIC_037_TO_ASCII[0x82] = 0x62; // b
		EBCDIC_037_TO_ASCII[0x83] = 0x63; // c
		EBCDIC_037_TO_ASCII[0x84] = 0x64; // d
		EBCDIC_037_TO_ASCII[0x85] = 0x65; // e
		EBCDIC_037_TO_ASCII[0x86] = 0x66; // f
		EBCDIC_037_TO_ASCII[0x87] = 0x67; // g
		EBCDIC_037_TO_ASCII[0x88] = 0x68; // h
		EBCDIC_037_TO_ASCII[0x89] = 0x69; // i
		EBCDIC_037_TO_ASCII[0x91] = 0x6A; // j
		EBCDIC_037_TO_ASCII[0x92] = 0x6B; // k
		EBCDIC_037_TO_ASCII[0x93] = 0x6C; // l
		EBCDIC_037_TO_ASCII[0x94] = 0x6D; // m
		EBCDIC_037_TO_ASCII[0x95] = 0x6E; // n
		EBCDIC_037_TO_ASCII[0x96] = 0x6F; // o
		EBCDIC_037_TO_ASCII[0x97] = 0x70; // p
		EBCDIC_037_TO_ASCII[0x98] = 0x71; // q
		EBCDIC_037_TO_ASCII[0x99] = 0x72; // r
		EBCDIC_037_TO_ASCII[0xA2] = 0x73; // s
		EBCDIC_037_TO_ASCII[0xA3] = 0x74; // t
		EBCDIC_037_TO_ASCII[0xA4] = 0x75; // u
		EBCDIC_037_TO_ASCII[0xA5] = 0x76; // v
		EBCDIC_037_TO_ASCII[0xA6] = 0x77; // w
		EBCDIC_037_TO_ASCII[0xA7] = 0x78; // x
		EBCDIC_037_TO_ASCII[0xA8] = 0x79; // y
		EBCDIC_037_TO_ASCII[0xA9] = 0x7A; // z
		// Uppercase letters
		EBCDIC_037_TO_ASCII[0xC1] = 0x41; // A
		EBCDIC_037_TO_ASCII[0xC2] = 0x42; // B
		EBCDIC_037_TO_ASCII[0xC3] = 0x43; // C
		EBCDIC_037_TO_ASCII[0xC4] = 0x44; // D
		EBCDIC_037_TO_ASCII[0xC5] = 0x45; // E
		EBCDIC_037_TO_ASCII[0xC6] = 0x46; // F
		EBCDIC_037_TO_ASCII[0xC7] = 0x47; // G
		EBCDIC_037_TO_ASCII[0xC8] = 0x48; // H
		EBCDIC_037_TO_ASCII[0xC9] = 0x49; // I
		EBCDIC_037_TO_ASCII[0xD1] = 0x4A; // J
		EBCDIC_037_TO_ASCII[0xD2] = 0x4B; // K
		EBCDIC_037_TO_ASCII[0xD3] = 0x4C; // L
		EBCDIC_037_TO_ASCII[0xD4] = 0x4D; // M
		EBCDIC_037_TO_ASCII[0xD5] = 0x4E; // N
		EBCDIC_037_TO_ASCII[0xD6] = 0x4F; // O
		EBCDIC_037_TO_ASCII[0xD7] = 0x50; // P
		EBCDIC_037_TO_ASCII[0xD8] = 0x51; // Q
		EBCDIC_037_TO_ASCII[0xD9] = 0x52; // R
		EBCDIC_037_TO_ASCII[0xE2] = 0x53; // S
		EBCDIC_037_TO_ASCII[0xE3] = 0x54; // T
		EBCDIC_037_TO_ASCII[0xE4] = 0x55; // U
		EBCDIC_037_TO_ASCII[0xE5] = 0x56; // V
		EBCDIC_037_TO_ASCII[0xE6] = 0x57; // W
		EBCDIC_037_TO_ASCII[0xE7] = 0x58; // X
		EBCDIC_037_TO_ASCII[0xE8] = 0x59; // Y
		EBCDIC_037_TO_ASCII[0xE9] = 0x5A; // Z
		// Digits
		EBCDIC_037_TO_ASCII[0xF0] = 0x30; // 0
		EBCDIC_037_TO_ASCII[0xF1] = 0x31; // 1
		EBCDIC_037_TO_ASCII[0xF2] = 0x32; // 2
		EBCDIC_037_TO_ASCII[0xF3] = 0x33; // 3
		EBCDIC_037_TO_ASCII[0xF4] = 0x34; // 4
		EBCDIC_037_TO_ASCII[0xF5] = 0x35; // 5
		EBCDIC_037_TO_ASCII[0xF6] = 0x36; // 6
		EBCDIC_037_TO_ASCII[0xF7] = 0x37; // 7
		EBCDIC_037_TO_ASCII[0xF8] = 0x38; // 8
		EBCDIC_037_TO_ASCII[0xF9] = 0x39; // 9
	}

	protected void printNonNumeric(final Literal literal, final RuleContext rc) {
		final String nonNumericLiteral = literal.getNonNumericLiteral();

		if (nonNumericLiteral.startsWith("X\"") || nonNumericLiteral.startsWith("x\"")) {
			// COBOL hex literal X"..." — EBCDIC code points, convert to ASCII/Java
			final String hexContent = nonNumericLiteral.substring(2, nonNumericLiteral.length() - 1);
			final StringBuilder sb = new StringBuilder("\"");
			for (int i = 0; i + 1 < hexContent.length(); i += 2) {
				final int ebcdicValue = Integer.parseInt(hexContent.substring(i, i + 2), 16);
				final int charValue = EBCDIC_037_TO_ASCII[ebcdicValue];
				switch (charValue) {
				case 0x0D:
					sb.append("\\r");
					break;
				case 0x0A:
					sb.append("\\n");
					break;
				case 0x09:
					sb.append("\\t");
					break;
				case 0x08:
					sb.append("\\b");
					break;
				case 0x0C:
					sb.append("\\f");
					break;
				case 0x00:
					sb.append("\\0");
					break;
				case 0x22:
					sb.append("\\\"");
					break;
				case 0x5C:
					sb.append("\\\\");
					break;
				default:
					if (charValue >= 0x20 && charValue < 0x7F) {
						sb.append((char) charValue);
					} else {
						sb.append(String.format("\\u%04X", charValue));
					}
					break;
				}
			}
			sb.append("\"");
			rc.p(sb.toString());
		} else {
			final String nonNumericLiteralEscaped = escapeQuote(escapeBackslash(nonNumericLiteral));
			rc.p("\"%s\"", nonNumericLiteralEscaped);
		}
	}

	protected void printNumeric(final Literal literal, final RuleContext rc) {
		rc.visit(literal.getNumericLiteral().getCtx());
	}
}
