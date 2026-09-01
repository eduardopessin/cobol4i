package io.proleap.cobol.transform.java.validation;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the input directory layout used by the bulk/single transformer CLI
 * tools.
 *
 * <p>The tools expect a base directory containing the extracted COBOL assets,
 * with a conventional layout:
 *
 * <pre>
 * &lt;baseDir&gt;/
 *   source_cobol/    COBOL programs (.cbl)
 *   copybooks/       copybooks and DDS stubs
 *   schemas/         DDS schema JSON used to resolve COPY DDSR
 * </pre>
 *
 * <p>Every element is configurable, so a project whose extraction uses
 * different folder names does not need to patch the tools:
 *
 * <ul>
 *   <li>{@code -Dcobol.base.dir=/path/to/extracted} — base directory. Defaults
 *       to {@code extracted} under the working directory.</li>
 *   <li>{@code -Dcobol.source.dirs=source_cobol,legacy_cobol} — source
 *       subdirectories, in order.</li>
 *   <li>{@code -Dcobol.copybook.dirs=copybooks,shared_copybooks} — copybook
 *       subdirectories, in order. Order matters: directories holding flat
 *       {@code PIC X} DDS stubs should come before directories holding fully
 *       typed copybooks, otherwise generated Java can end up with mismatched
 *       types.</li>
 *   <li>{@code -Dcobol.schema.dir=schemas} — DDS schema subdirectory.</li>
 * </ul>
 *
 * <p>Relative values are resolved against the base directory; absolute values
 * are used as-is. Directories that do not exist are silently dropped.
 */
public final class TransformerPaths {

	public static final String BASE_DIR_PROPERTY = "cobol.base.dir";

	public static final String SOURCE_DIRS_PROPERTY = "cobol.source.dirs";

	public static final String COPYBOOK_DIRS_PROPERTY = "cobol.copybook.dirs";

	public static final String SCHEMA_DIR_PROPERTY = "cobol.schema.dir";

	private static final String DEFAULT_BASE_DIR = "extracted";

	private static final String DEFAULT_SOURCE_DIRS = "source_cobol";

	private static final String DEFAULT_COPYBOOK_DIRS = "copybooks,source_cobol";

	private static final String DEFAULT_SCHEMA_DIR = "schemas";

	/**
	 * Base directory holding the extracted COBOL assets.
	 *
	 * @param override explicit base directory (e.g. taken from argv); when
	 *                 {@code null} or blank, {@code -Dcobol.base.dir} is used,
	 *                 falling back to {@code ./extracted}
	 */
	public static Path baseDir(final String override) {
		if (override != null && !override.isBlank()) {
			return Paths.get(override).toAbsolutePath().normalize();
		}

		final String configured = System.getProperty(BASE_DIR_PROPERTY);
		final String value = configured != null && !configured.isBlank() ? configured : DEFAULT_BASE_DIR;

		return Paths.get(value).toAbsolutePath().normalize();
	}

	/** Base directory resolved purely from configuration. */
	public static Path baseDir() {
		return baseDir(null);
	}

	/** Existing source directories, in configured order. */
	public static List<Path> sourceDirs(final Path baseDir) {
		final List<Path> result = new ArrayList<>();

		for (final Path dir : resolveAll(baseDir, SOURCE_DIRS_PROPERTY, DEFAULT_SOURCE_DIRS)) {
			if (dir.toFile().isDirectory()) {
				result.add(dir);
			}
		}

		return result;
	}

	/** Existing copybook directories, in configured order. */
	public static List<File> copybookDirs(final Path baseDir) {
		final List<File> result = new ArrayList<>();

		for (final Path dir : resolveAll(baseDir, COPYBOOK_DIRS_PROPERTY, DEFAULT_COPYBOOK_DIRS)) {
			final File file = dir.toFile();

			if (file.isDirectory()) {
				result.add(file);
			}
		}

		return result;
	}

	/**
	 * DDS schema directory used to resolve {@code COPY DDSR}, or {@code null}
	 * when it does not exist.
	 */
	public static File schemaDir(final Path baseDir) {
		final String configured = System.getProperty(SCHEMA_DIR_PROPERTY);
		final String value = configured != null && !configured.isBlank() ? configured : DEFAULT_SCHEMA_DIR;
		final File dir = resolve(baseDir, value).toFile();

		return dir.isDirectory() ? dir : null;
	}

	private static List<Path> resolveAll(final Path baseDir, final String property, final String fallback) {
		final String configured = System.getProperty(property);
		final String value = configured != null && !configured.isBlank() ? configured : fallback;

		// LinkedHashSet: keep configured order, drop accidental duplicates.
		final Set<Path> result = new LinkedHashSet<>();

		for (final String entry : value.split(",")) {
			final String trimmed = entry.trim();

			if (!trimmed.isEmpty()) {
				result.add(resolve(baseDir, trimmed));
			}
		}

		return new ArrayList<>(result);
	}

	private static Path resolve(final Path baseDir, final String value) {
		final Path path = Paths.get(value);

		return path.isAbsolute() ? path.normalize() : baseDir.resolve(path).normalize();
	}

	private TransformerPaths() {
		// utility class
	}
}
