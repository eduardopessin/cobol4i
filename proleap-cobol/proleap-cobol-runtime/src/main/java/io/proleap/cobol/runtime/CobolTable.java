package io.proleap.cobol.runtime;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;

/**
 * COBOL table/array handling per IBM ILE COBOL Language Reference V7R3.
 *
 * Implements:
 * - OCCURS (fixed-size tables) with 1-based subscripting
 * - OCCURS DEPENDING ON (variable-length tables)
 * - SEARCH (serial search)
 * - SEARCH ALL (binary search, requires ASCENDING/DESCENDING KEY)
 * - SET index TO/UP BY/DOWN BY
 * - Subscript bounds checking
 */
public final class CobolTable {

	private CobolTable() {
	}

	// ===================== Subscript operations (1-based) =====================

	/**
	 * Convert COBOL 1-based subscript to Java 0-based index.
	 * Per IBM manual: COBOL subscripts start at 1.
	 *
	 * @param subscript COBOL 1-based subscript
	 * @return Java 0-based index
	 * @throws CobolTableException if subscript is out of bounds
	 */
	public static int toIndex(final int subscript) {
		if (subscript < 1) {
			throw new CobolTableException("Subscript " + subscript + " is less than 1");
		}
		return subscript - 1;
	}

	/**
	 * Convert COBOL 1-based subscript to Java 0-based index with bounds check.
	 *
	 * @param subscript  COBOL 1-based subscript
	 * @param tableSize  maximum number of occurrences
	 * @return Java 0-based index
	 * @throws CobolTableException if out of bounds
	 */
	public static int toIndex(final int subscript, final int tableSize) {
		if (subscript < 1 || subscript > tableSize) {
			throw new CobolTableException(
					"Subscript " + subscript + " out of range 1.." + tableSize);
		}
		return subscript - 1;
	}

	/**
	 * Convert Java 0-based index to COBOL 1-based subscript.
	 */
	public static int toSubscript(final int index) {
		return index + 1;
	}

	// ===================== OCCURS DEPENDING ON =====================

	/**
	 * Get effective table size for OCCURS DEPENDING ON.
	 * Per IBM manual: the current value of the DEPENDING ON identifier
	 * determines the current number of occurrences.
	 *
	 * @param dependingOnValue current value of the ODO object
	 * @param minOccurs        minimum OCCURS value
	 * @param maxOccurs        maximum OCCURS value
	 * @return effective table size, clamped to valid range
	 */
	public static int effectiveSize(final int dependingOnValue, final int minOccurs, final int maxOccurs) {
		if (dependingOnValue < minOccurs) {
			return minOccurs;
		}
		if (dependingOnValue > maxOccurs) {
			return maxOccurs;
		}
		return dependingOnValue;
	}

	// ===================== SET index operations =====================

	/**
	 * SET index TO value.
	 * Per IBM manual: sets the index to the occurrence number specified.
	 *
	 * @param value COBOL 1-based occurrence number
	 * @return Java 0-based index
	 */
	public static int setIndexTo(final int value) {
		return toIndex(value);
	}

	/**
	 * SET index UP BY value.
	 * Per IBM manual: increments the index by the specified value.
	 *
	 * @param currentIndex current Java 0-based index
	 * @param increment    amount to add
	 * @return new Java 0-based index
	 */
	public static int setIndexUpBy(final int currentIndex, final int increment) {
		return currentIndex + increment;
	}

	/**
	 * SET index DOWN BY value.
	 * Per IBM manual: decrements the index by the specified value.
	 *
	 * @param currentIndex current Java 0-based index
	 * @param decrement    amount to subtract
	 * @return new Java 0-based index
	 */
	public static int setIndexDownBy(final int currentIndex, final int decrement) {
		return currentIndex - decrement;
	}

	// ===================== SEARCH (serial) =====================

	/**
	 * SEARCH - serial search through a table.
	 * Per IBM manual: search begins at the current index setting and
	 * proceeds to the end. When the WHEN condition is true, the
	 * corresponding imperative statement is executed. If the end is
	 * reached without satisfaction, AT END is executed.
	 *
	 * @param tableSize    number of elements in the table
	 * @param startIndex   Java 0-based starting index
	 * @param condition    predicate that takes a 0-based index
	 * @return the 0-based index of the found element, or -1 if not found
	 */
	public static int search(final int tableSize, final int startIndex,
			final java.util.function.IntPredicate condition) {
		for (int i = startIndex; i < tableSize; i++) {
			if (condition.test(i)) {
				return i;
			}
		}
		return -1; // AT END
	}

	/**
	 * SEARCH returning 1-based subscript.
	 *
	 * @return 1-based subscript of found element, or 0 if not found (AT END)
	 */
	public static int searchSubscript(final int tableSize, final int startSubscript,
			final java.util.function.IntPredicate condition) {
		final int result = search(tableSize, startSubscript - 1, condition);
		return (result >= 0) ? result + 1 : 0;
	}

	// ===================== SEARCH ALL (binary search) =====================

	/**
	 * SEARCH ALL - binary search on an ordered table.
	 * Per IBM manual: the table must be ordered by the KEY specified
	 * in the OCCURS clause. The WHEN condition must test the key.
	 * The index is automatically set to the found element.
	 *
	 * @param sortedKeys  array of keys in sorted order
	 * @param searchKey   the key value to find
	 * @return the 0-based index of the found element, or -1 if not found
	 */
	public static int searchAll(final String[] sortedKeys, final String searchKey) {
		if (sortedKeys == null || searchKey == null) {
			return -1;
		}
		int result = Arrays.binarySearch(sortedKeys, searchKey);
		return (result >= 0) ? result : -1;
	}

	/**
	 * SEARCH ALL with numeric keys.
	 */
	public static int searchAll(final BigDecimal[] sortedKeys, final BigDecimal searchKey) {
		if (sortedKeys == null || searchKey == null) {
			return -1;
		}
		int result = Arrays.binarySearch(sortedKeys, searchKey);
		return (result >= 0) ? result : -1;
	}

	/**
	 * SEARCH ALL with integer keys.
	 */
	public static int searchAll(final int[] sortedKeys, final int searchKey) {
		if (sortedKeys == null) {
			return -1;
		}
		int result = Arrays.binarySearch(sortedKeys, searchKey);
		return (result >= 0) ? result : -1;
	}

	/**
	 * SEARCH ALL returning 1-based subscript.
	 */
	public static int searchAllSubscript(final String[] sortedKeys, final String searchKey) {
		final int result = searchAll(sortedKeys, searchKey);
		return (result >= 0) ? result + 1 : 0;
	}

	public static int searchAllSubscript(final BigDecimal[] sortedKeys, final BigDecimal searchKey) {
		final int result = searchAll(sortedKeys, searchKey);
		return (result >= 0) ? result + 1 : 0;
	}

	public static int searchAllSubscript(final int[] sortedKeys, final int searchKey) {
		final int result = searchAll(sortedKeys, searchKey);
		return (result >= 0) ? result + 1 : 0;
	}

	// ===================== Table initialization =====================

	/**
	 * Initialize a String table with SPACES.
	 */
	public static String[] initializeStringTable(final int size, final int elementLength) {
		final String[] table = new String[size];
		final String init = CobolConstants.spaces(elementLength);
		Arrays.fill(table, init);
		return table;
	}

	/**
	 * Initialize a numeric table with ZEROS.
	 */
	public static BigDecimal[] initializeNumericTable(final int size, final int scale) {
		final BigDecimal[] table = new BigDecimal[size];
		final BigDecimal init = BigDecimal.ZERO.setScale(scale);
		Arrays.fill(table, init);
		return table;
	}

	/**
	 * Initialize an integer table with zeros.
	 */
	public static int[] initializeIntTable(final int size) {
		return new int[size]; // Java initializes to 0
	}

	// ===================== SORT for table =====================

	/**
	 * Sort a table by ASCENDING KEY.
	 * Per IBM manual: ASCENDING KEY means the values must be in ascending order.
	 */
	public static void sortAscending(final String[] table) {
		if (table != null) {
			Arrays.sort(table);
		}
	}

	public static void sortAscending(final BigDecimal[] table) {
		if (table != null) {
			Arrays.sort(table);
		}
	}

	/**
	 * Sort a table by DESCENDING KEY.
	 */
	public static void sortDescending(final String[] table) {
		if (table != null) {
			Arrays.sort(table, Comparator.reverseOrder());
		}
	}

	public static void sortDescending(final BigDecimal[] table) {
		if (table != null) {
			Arrays.sort(table, Comparator.reverseOrder());
		}
	}

	// ===================== Exception =====================

	/**
	 * Runtime exception for COBOL table subscript errors.
	 */
	public static class CobolTableException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public CobolTableException(final String message) {
			super(message);
		}
	}
}
