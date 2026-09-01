package io.proleap.cobol.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for BigDecimal fields in generated COBOL programs to record
 * the COBOL PIC display width. This allows groupToString/moveStringToGroup
 * to correctly serialize/deserialize numeric fields with fixed widths
 * (e.g., PIC 9(09) always serializes to 9 characters, not a variable-length
 * BigDecimal.toPlainString() representation).
 * <p>
 * Without this annotation, BigDecimal.ZERO.toPlainString() returns "0" (1 char),
 * but PIC 9(09) should serialize to "000000000" (9 chars). The discrepancy
 * corrupts flat byte serialization of group items.
 * <p>
 * The {@link #decimalDigits()} attribute records the implied decimal places
 * from PIC V (e.g., PIC S9(03)V9(02) has decimalDigits=2). This is needed
 * by moveStringToGroup to restore the implied decimal when deserializing:
 * groupToString scales 23.00 to "02300", and moveStringToGroup must scale
 * it back to 23.00 using movePointLeft(decimalDigits).
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CobolFieldWidth {
	int value();

	/**
	 * Number of implied decimal digits from PIC V specification.
	 * Default is 0 (no implied decimal). For PIC S9(03)V9(02), this is 2.
	 */
	int decimalDigits() default 0;
}
