package io.proleap.cobol.transform.java.validation;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import io.micronaut.context.ApplicationContext;

import io.proleap.cobol.asg.params.CobolDialect;
import io.proleap.cobol.asg.params.CobolParserParams;
import io.proleap.cobol.asg.params.impl.CobolParserParamsImpl;
import io.proleap.cobol.preprocessor.CobolPreprocessor.CobolSourceFormatEnum;
import io.proleap.cobol.transform.java.runner.CobolTransformationRunner;

/**
 * CLI tool to transform a single COBOL file to Java using ProLeap.
 * Usage: java SingleFileTransformer <input.cbl> <output.java>
 */
public class SingleFileTransformer {

	public static void main(final String[] args) throws IOException {
		if (args.length < 2) {
			System.err.println("Usage: SingleFileTransformer <input.cbl> <output.java>");
			System.exit(1);
		}

		File inputFile = new File(args[0]);
		final File outputFile = new File(args[1]);

		if (!inputFile.exists()) {
			System.err.println("ERROR: Input file not found: " + inputFile);
			System.exit(1);
		}

		// Preprocess: normalize DDSR-ALL-FORMAT (singular) to DDSR-ALL-FORMATS (plural)
		// IBM ILE COBOL accepts both, but the DdsCopyBookGenerator only handles FORMATS.
		{
			String src = new String(Files.readAllBytes(inputFile.toPath()));
			if (src.contains("DDSR-ALL-FORMAT ") && !src.contains("DDSR-ALL-FORMATS ")) {
				src = src.replace("DDSR-ALL-FORMAT ", "DDSR-ALL-FORMATS ");
				// Use same filename as original so ProLeap uses correct class name
				final Path tempDir = Files.createTempDirectory("cobol_norm_");
				final Path tempFile = tempDir.resolve(inputFile.getName());
				Files.write(tempFile, src.getBytes());
				inputFile = tempFile.toFile();
				System.out.printf("NOTE: Normalized DDSR-ALL-FORMAT to DDSR-ALL-FORMATS%n");
			}
		}

		final Path baseDir = TransformerPaths.baseDir();

		// Copybook directories — order matters. Directories holding flat PIC X
		// DDS stubs must come before directories holding fully typed copybooks,
		// otherwise generated Java hits moveAlphanumericToAlphanumeric() type
		// errors. Configure with -Dcobol.copybook.dirs (see TransformerPaths).
		final List<File> copybookDirs = TransformerPaths.copybookDirs(baseDir);

		System.out.printf("Input:  %s%n", inputFile);
		System.out.printf("Output: %s%n", outputFile);
		System.out.printf("Copybook dirs: %s%n", copybookDirs);

		final ApplicationContext context = ApplicationContext.run();
		try {
			final CobolTransformationRunner runner = context.getBean(CobolTransformationRunner.class);

			final CobolParserParams params = new CobolParserParamsImpl();
			// These tools always process IBM i source; request that dialect explicitly.
			params.setDialect(CobolDialect.IBM_ILE);
			params.setFormat(CobolSourceFormatEnum.FIXED);
			params.setCopyBookDirectories(copybookDirs);

			// Set DDS schema directory for COPY DDSR resolution
			final File schemasDir = TransformerPaths.schemaDir(baseDir);
			if (schemasDir != null) {
				params.setDdsSchemaDirectory(schemasDir);
				System.out.printf("DDS schemas: %s%n", schemasDir);
			}

			final List<File> result = runner.transformFile(inputFile, "", params);

			if (result.isEmpty()) {
				System.err.println("ERROR: No output generated");
				System.exit(1);
			}

			Files.copy(result.get(0).toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			System.out.printf("SUCCESS: Generated %s (%d bytes)%n", outputFile, outputFile.length());
		} finally {
			context.close();
		}
	}
}
