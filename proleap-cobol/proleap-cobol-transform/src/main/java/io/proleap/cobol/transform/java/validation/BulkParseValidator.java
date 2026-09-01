package io.proleap.cobol.transform.java.validation;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.proleap.cobol.asg.metamodel.Program;
import io.proleap.cobol.asg.params.CobolDialect;
import io.proleap.cobol.asg.params.CobolParserParams;
import io.proleap.cobol.asg.params.impl.CobolParserParamsImpl;
import io.proleap.cobol.asg.runner.impl.CobolParserRunnerImpl;
import io.proleap.cobol.preprocessor.CobolPreprocessor.CobolSourceFormatEnum;

/**
 * Bulk parser that validates all COBOL files can be parsed successfully.
 */
public class BulkParseValidator {

	public static void main(final String[] args) throws IOException {
		final Path baseDir = TransformerPaths.baseDir(args.length > 0 ? args[0] : null);

		final List<Path> sourceDirs = TransformerPaths.sourceDirs(baseDir);

		// Copybook directories
		final List<File> copybookDirs = TransformerPaths.copybookDirs(baseDir);
		// Also add source dirs themselves as copybook dirs
		for (final Path dir : sourceDirs) {
			if (Files.exists(dir)) {
				copybookDirs.add(dir.toFile());
			}
		}

		// Collect all COBOL files
		final List<File> cobolFiles = new ArrayList<>();
		for (final Path dir : sourceDirs) {
			if (Files.exists(dir)) {
				try (Stream<Path> walk = Files.walk(dir)) {
					cobolFiles.addAll(walk.filter(p -> {
						final String name = p.toString().toLowerCase();
						return name.endsWith(".cbl") || name.endsWith(".cob");
					}).map(Path::toFile).collect(Collectors.toList()));
				}
			}
		}

		System.out.printf("Found %d COBOL files%n", cobolFiles.size());
		System.out.printf("Copybook dirs: %s%n", copybookDirs);

		final CobolParserRunnerImpl runner = new CobolParserRunnerImpl();
		int success = 0;
		int failed = 0;
		final List<String> failures = new ArrayList<>();

		for (final File file : cobolFiles) {
			try {
				final CobolParserParams params = new CobolParserParamsImpl();
				// These tools always process IBM i source; request that dialect explicitly.
				params.setDialect(CobolDialect.IBM_ILE);
				params.setFormat(CobolSourceFormatEnum.FIXED);
				params.setCopyBookDirectories(copybookDirs);
				final Program program = runner.analyzeFile(file, params);
				success++;
			} catch (final Exception e) {
				failed++;
				failures.add(file.getName() + ": " + e.getMessage());
				if (failed <= 20) {
					System.err.printf("FAIL: %s - %s%n", file.getName(), e.getMessage());
				}
			}

			if ((success + failed) % 100 == 0) {
				System.out.printf("Progress: %d/%d (%.1f%% success)%n",
						success + failed, cobolFiles.size(),
						100.0 * success / (success + failed));
			}
		}

		final double pct = cobolFiles.isEmpty() ? 0 : 100.0 * success / cobolFiles.size();
		System.out.println();
		System.out.println("=== PARSE RESULTS ===");
		System.out.printf("Total:   %d%n", cobolFiles.size());
		System.out.printf("Success: %d (%.1f%%)%n", success, pct);
		System.out.printf("Failed:  %d (%.1f%%)%n", failed, 100.0 - pct);

		if (!failures.isEmpty()) {
			System.out.println();
			System.out.printf("First %d failures:%n", Math.min(failures.size(), 50));
			failures.stream().limit(50).forEach(f -> System.out.println("  " + f));
		}
	}
}
