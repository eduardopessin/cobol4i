package io.proleap.cobol.transform.java.validation;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
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
 * Batch CLI tool to transform multiple COBOL files to Java in a single JVM.
 * Reads lines from stdin, each line: inputFile outputFile
 * Or: pass a directory as arg1 and output dir as arg2.
 */
public class BatchFileTransformer {

	public static void main(final String[] args) throws IOException {
		final Path baseDir = TransformerPaths.baseDir();

		final List<File> copybookDirs = TransformerPaths.copybookDirs(baseDir);

		final ApplicationContext context = ApplicationContext.run();
		try {
			final CobolTransformationRunner runner = context.getBean(CobolTransformationRunner.class);

			final CobolParserParams params = new CobolParserParamsImpl();
			// These tools always process IBM i source; request that dialect explicitly.
			params.setDialect(CobolDialect.IBM_ILE);
			params.setFormat(CobolSourceFormatEnum.FIXED);
			params.setCopyBookDirectories(copybookDirs);

			int ok = 0;
			int fail = 0;

			if (args.length == 2) {
				// Directory mode: args[0]=inputDir, args[1]=outputDir
				final File inputDir = new File(args[0]);
				final File outputDir = new File(args[1]);
				outputDir.mkdirs();

				final File[] cblFiles = inputDir.listFiles((dir, name) -> name.endsWith(".cbl"));
				if (cblFiles == null) {
					System.err.println("No .cbl files in " + inputDir);
					System.exit(1);
				}
				System.out.printf("Processing %d files from %s%n", cblFiles.length, inputDir);

				for (final File cbl : cblFiles) {
					final String progName = cbl.getName().replace(".cbl", "");
					final File outFile = new File(outputDir, progName + ".java");
					if (outFile.exists()) {
						ok++;
						continue;
					}
					try {
						final List<File> result = runner.transformFile(cbl, "", params);
						if (!result.isEmpty()) {
							Files.copy(result.get(0).toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
							ok++;
						} else {
							fail++;
							System.err.printf("NO_OUTPUT: %s%n", progName);
						}
					} catch (final Exception e) {
						fail++;
						System.err.printf("ERROR: %s: %s%n", progName, e.getMessage());
					}
					if ((ok + fail) % 50 == 0) {
						System.out.printf("Progress: %d/%d (ok=%d, fail=%d)%n", ok + fail, cblFiles.length, ok, fail);
					}
				}
			} else {
				// Stdin mode: read pairs of inputFile outputFile
				final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
				String line;
				while ((line = reader.readLine()) != null) {
					line = line.trim();
					if (line.isEmpty()) continue;
					final String[] parts = line.split("\\s+", 2);
					if (parts.length < 2) continue;

					final File inputFile = new File(parts[0]);
					final File outputFile = new File(parts[1]);
					try {
						final List<File> result = runner.transformFile(inputFile, "", params);
						if (!result.isEmpty()) {
							Files.copy(result.get(0).toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
							ok++;
						} else {
							fail++;
							System.err.printf("NO_OUTPUT: %s%n", inputFile.getName());
						}
					} catch (final Exception e) {
						fail++;
						System.err.printf("ERROR: %s: %s%n", inputFile.getName(), e.getMessage());
					}
					if ((ok + fail) % 50 == 0) {
						System.out.printf("Progress: %d (ok=%d, fail=%d)%n", ok + fail, ok, fail);
					}
				}
			}

			System.out.printf("BATCH COMPLETE: ok=%d, fail=%d, total=%d%n", ok, fail, ok + fail);
		} finally {
			context.close();
		}
	}
}
