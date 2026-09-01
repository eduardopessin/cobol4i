package io.proleap.cobol.commons.util;

import java.util.regex.Pattern;

public class CobolPictureParseUtils {

	public static Pattern PATTERN_9 = Pattern.compile("[9]+");

	public static Pattern PATTERN_9DOT9 = Pattern.compile("([9]+).([9]+)");

	public static Pattern PATTERN_9Length = Pattern.compile("9\\(([0-9]+)\\)");

	public static Pattern PATTERN_9LengthV9 = Pattern.compile("9\\(([0-9]+)\\)V([9]+)");

	public static Pattern PATTERN_9LengthV9Length = Pattern.compile("9\\(([0-9]+)\\)V9\\(([0-9]+)\\)");

	public static Pattern PATTERN_9V9 = Pattern.compile("([9]+)V([9]+)");

	public static Pattern PATTERN_9V9Length = Pattern.compile("([9]+)V9\\(([0-9]+)\\)");

	public static Pattern PATTERN_A = Pattern.compile("[A]+");

	public static Pattern PATTERN_ALength = Pattern.compile("A\\(([0-9]+)\\)");

	public static Pattern PATTERN_S9 = Pattern.compile("S[9]+");

	public static Pattern PATTERN_S9Length = Pattern.compile("S9\\(([0-9]+)\\)");

	public static Pattern PATTERN_S9LengthV9 = Pattern.compile("S9\\(([0-9]+)\\)V([9]+)");

	public static Pattern PATTERN_S9LengthV9Length = Pattern.compile("S9\\(([0-9]+)\\)V9\\(([0-9]+)\\)");

	public static Pattern PATTERN_S9V9 = Pattern.compile("S[9]+V[9]+");

	public static Pattern PATTERN_S9V9Length = Pattern.compile("S([9]+)V9\\(([0-9]+)\\)");

	public static Pattern PATTERN_X = Pattern.compile("[X]+");

	public static Pattern PATTERN_XLength = Pattern.compile("X\\(([0-9]+)\\)");

	// Numeric-edited patterns with + or - sign and implied decimal V
	// +9(n) or -9(n) — integer only with sign
	public static Pattern PATTERN_PLUS_9Length = Pattern.compile("[+-]9\\(([0-9]+)\\)");

	// +9(n)V9(m) or -9(n)V9(m) — sign, integer(n), implied decimal, decimal(m)
	public static Pattern PATTERN_PLUS_9LengthV9Length = Pattern.compile("[+-]9\\(([0-9]+)\\)[Vv]9\\(([0-9]+)\\)");

	// +9(n)V99... or -9(n)V99... — sign, integer(n), implied decimal, explicit 9s
	public static Pattern PATTERN_PLUS_9LengthV9 = Pattern.compile("[+-]9\\(([0-9]+)\\)[Vv]([9]+)");

	// +99...V9(m) or -99...V9(m) — sign, explicit 9s, implied decimal, decimal(m)
	public static Pattern PATTERN_PLUS_9V9Length = Pattern.compile("[+-]([9]+)[Vv]9\\(([0-9]+)\\)");

	// +99...V99... or -99...V99... — sign, explicit 9s, implied decimal, explicit 9s
	public static Pattern PATTERN_PLUS_9V9 = Pattern.compile("[+-]([9]+)[Vv]([9]+)");
}
