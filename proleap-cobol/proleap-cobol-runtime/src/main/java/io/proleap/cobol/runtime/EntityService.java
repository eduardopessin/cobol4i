package io.proleap.cobol.runtime;

/**
 * Service for COBOL entity operations like INITIALIZE.
 */
public interface EntityService {

	/**
	 * Initialize all fields of a group to their default values
	 * (spaces for strings, zeros for numerics, false for booleans).
	 */
	void initialize(Object group);

	/**
	 * Returns the length of a field value.
	 * For Strings, returns the string length.
	 * For other types, returns the length of the string representation.
	 */
	int getLength(Object field);

	/**
	 * Returns the address of a field (SET PTR TO ADDRESS OF field).
	 * In the Java runtime, this returns the field value as a String reference
	 * since there are no real pointers.
	 */
	String getAddress(Object field);
}
