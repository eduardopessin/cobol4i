package io.proleap.cobol.transform.java.validation;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import io.micronaut.context.ApplicationContext;

import io.proleap.cobol.asg.params.CobolDialect;
import io.proleap.cobol.asg.params.CobolParserParams;
import io.proleap.cobol.asg.params.impl.CobolParserParamsImpl;
import io.proleap.cobol.preprocessor.CobolPreprocessor.CobolSourceFormatEnum;
import io.proleap.cobol.transform.java.runner.CobolTransformationRunner;

/**
 * Bulk transformer + compiler for all COBOL programs.
 * Runs in a single JVM to avoid startup overhead.
 *
 * The ApplicationContext is recycled every CONTEXT_RECYCLE_INTERVAL programs
 * to prevent state leakage between transformations (the parser/ASG can accumulate
 * state across runs that causes incorrect code generation, e.g., OCCURS-flattened
 * variables like w_tannmvt1/2/3 instead of proper array access).
 *
 * When a program fails compilation, it is retried once with a fresh ApplicationContext
 * to rule out state leakage as the cause.
 *
 * Usage: java BulkTransformCompiler <sourceDir> <outputDir> <csvFile> <errorLog> <compileClasspath> [--stop-on-first-error]
 */
public class BulkTransformCompiler {

	/** Recycle ApplicationContext every N programs to prevent state leakage */
	private static final int CONTEXT_RECYCLE_INTERVAL = 1;

	public static void main(final String[] args) throws Exception {
		if (args.length < 5) {
			System.err.println("Usage: BulkTransformCompiler <sourceDir> <outputDir> <csvFile> <errorLog> <compileClasspath> [--stop-on-first-error]");
			System.exit(1);
		}

		final String sourceDir = args[0];
		final String outputDir = args[1];
		final String csvFile = args[2];
		final String errorLog = args[3];
		final String compileClasspath = args[4];
		final boolean stopOnFirstError = args.length > 5 && "--stop-on-first-error".equals(args[5]);

		// Derive baseDir from sourceDir: sourceDir is normally a child of the
		// extraction root (e.g. <base>/source_cobol → base). Falls back to the
		// configured base (-Dcobol.base.dir) when the parent is unavailable.
		final Path sourceDirResolved = Paths.get(sourceDir).toAbsolutePath().normalize();
		final Path sourceDirParent = sourceDirResolved.getParent();
		final Path baseDir;
		if (sourceDirParent != null && sourceDirParent.toFile().isDirectory()) {
			baseDir = sourceDirParent;
		} else {
			baseDir = TransformerPaths.baseDir();
		}
		System.out.printf(" Base dir: %s\n", baseDir);

		// Copybook directories (order matters — copybooks with flat PIC X stubs first)
		final List<File> copybookDirs = TransformerPaths.copybookDirs(baseDir);

		// DDS schema directory for COPY DDSR resolution (matches SingleFileTransformer)
		final File ddsSchemaDir = TransformerPaths.schemaDir(baseDir);
		if (ddsSchemaDir != null) {
			System.out.printf(" DDS schemas: %s\n", ddsSchemaDir);
		}

		// Load skip list (programs with missing non-DDS copybooks)
		final Set<String> skipPrograms = loadSkipPrograms(baseDir);
		if (!skipPrograms.isEmpty()) {
			System.out.printf(" Skip programs:  %d (%s)\n", skipPrograms.size(), String.join(", ", skipPrograms));
		}

		// Collect all .cbl files sorted
		final List<File> cblFiles;
		try (Stream<Path> walk = Files.walk(Paths.get(sourceDir), 1)) {
			cblFiles = walk
				.filter(p -> p.toString().toLowerCase().endsWith(".cbl"))
				.map(Path::toFile)
				.sorted()
				.collect(Collectors.toList());
		}

		System.out.printf("==========================================\n");
		System.out.printf(" ProLeap Bulk Transform + Compile\n");
		System.out.printf(" Total COBOL programs: %d\n", cblFiles.size());
		System.out.printf(" Context recycle every: %d programs\n", CONTEXT_RECYCLE_INTERVAL);
		System.out.printf("==========================================\n");

		Files.createDirectories(Paths.get(outputDir));

		final PrintWriter csv = new PrintWriter(new FileWriter(csvFile));
		csv.println("program,generate_status,compile_status,error_summary");

		final PrintWriter errOut = new PrintWriter(new FileWriter(errorLog));

		final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			System.err.println("ERROR: No Java compiler available. Ensure JDK (not JRE) is on PATH.");
			System.exit(1);
		}

		ApplicationContext context = ApplicationContext.run();
		CobolTransformationRunner runner = context.getBean(CobolTransformationRunner.class);
		int contextAge = 0;

		int total = 0;
		int genOk = 0;
		int genFail = 0;
		int compOk = 0;
		int compFail = 0;
		int retryOk = 0;
		int skipped = 0;

		for (final File cblFile : cblFiles) {
			total++;
			contextAge++;
			final String progName = cblFile.getName().replaceFirst("\\.[^.]+$", "");
			final File javaFile = new File(outputDir, progName + ".java");

			// Recycle ApplicationContext periodically to prevent state leakage
			if (contextAge >= CONTEXT_RECYCLE_INTERVAL) {
				context.close();
				context = ApplicationContext.run();
				runner = context.getBean(CobolTransformationRunner.class);
				contextAge = 0;
				System.out.printf("[%d/%d] (context recycled)\n", total, cblFiles.size());
				System.out.flush();
			}

			System.out.printf("[%d/%d] %s", total, cblFiles.size(), progName);

			// Check if program is in skip list (missing copybooks)
			if (skipPrograms.contains(progName)) {
				skipped++;
				System.out.printf(" SKIP (missing copybooks)\n");
				System.out.flush();
				csv.printf("%s,SKIP,SKIP,Missing copybooks\n", progName);
				continue;
			}

			System.out.println();
			System.out.flush();

			// Step 1: Generate Java
			boolean genSuccess = generateJava(runner, cblFile, javaFile, copybookDirs, ddsSchemaDir);

			if (!genSuccess) {
				genFail++;
				csv.printf("%s,FAIL,SKIP,Generation failed\n", progName);
				errOut.printf("=== %s === GENERATION ERROR ===\n\n", progName);
				if (stopOnFirstError) { csv.close(); errOut.close(); context.close(); System.out.printf("STOPPED: %s generation failed\n", progName); System.exit(1); }
				continue;
			}

			genOk++;

			// Step 2: Compile Java
			final String compileError = compileJava(compiler, javaFile, compileClasspath);

			if (compileError == null) {
				compOk++;
				csv.printf("%s,OK,OK,\n", progName);
			} else {
				// Compilation failed — retry with fresh ApplicationContext to rule out state leakage
				System.out.printf("[%d/%d] %s RETRY (fresh context to rule out state leakage)\n", total, cblFiles.size(), progName);
				System.out.flush();

				context.close();
				context = ApplicationContext.run();
				runner = context.getBean(CobolTransformationRunner.class);
				contextAge = 0;

				boolean retryGenSuccess = generateJava(runner, cblFile, javaFile, copybookDirs, ddsSchemaDir);
				String retryCompileError = null;
				if (retryGenSuccess) {
					retryCompileError = compileJava(compiler, javaFile, compileClasspath);
				}

				if (retryGenSuccess && retryCompileError == null) {
					// Retry succeeded — state leakage was the cause
					retryOk++;
					compOk++;
					csv.printf("%s,OK,OK,(retry after context recycle)\n", progName);
					System.out.printf("[%d/%d] %s RETRY OK (state leakage was the cause)\n", total, cblFiles.size(), progName);
				} else {
					// Retry also failed — genuine compilation error
					compFail++;
					final String finalError = retryGenSuccess ? retryCompileError : "Retry generation also failed";
					csv.printf("%s,OK,FAIL,%s\n", progName, sanitizeCsv(finalError));
					errOut.printf("=== %s === COMPILE ERROR ===\n", progName);
					errOut.print(finalError);
					errOut.println();
					if (stopOnFirstError) { csv.close(); errOut.close(); context.close(); System.out.printf("STOPPED: %s compile failed (%s)\n", progName, sanitizeCsv(firstLineStr(finalError))); System.exit(1); }
				}
			}
		}

		csv.close();
		errOut.close();
		context.close();

		System.out.printf("\n==========================================\n");
		System.out.printf(" RESULTS SUMMARY\n");
		System.out.printf("==========================================\n");
		System.out.printf(" Total programs:       %d\n", total);
		if (skipped > 0) {
			System.out.printf(" Skipped (missing cpy):%d\n", skipped);
		}
		System.out.printf(" Generation OK:        %d\n", genOk);
		System.out.printf(" Generation FAIL:      %d\n", genFail);
		System.out.printf(" Compile OK:           %d\n", compOk);
		if (retryOk > 0) {
			System.out.printf("   (of which %d fixed by context recycle)\n", retryOk);
		}
		System.out.printf(" Compile FAIL:         %d\n", compFail);
		if (total > 0) {
			System.out.printf(" Full success rate:    %.1f%% (excl. skipped: %.1f%%)\n",
				100.0 * compOk / total,
				(total - skipped) > 0 ? 100.0 * compOk / (total - skipped) : 0.0);
		}
		System.out.printf("==========================================\n");
		System.out.printf("Results: %s\n", csvFile);
		System.out.printf("Errors:  %s\n", errorLog);
	}

	/**
	 * Generate Java from a COBOL file using the given runner.
	 * Returns true on success, false on failure.
	 *
	 * Applies the same preprocessing as SingleFileTransformer:
	 * - Normalizes DDSR-ALL-FORMAT to DDSR-ALL-FORMATS
	 * - Sets DDS schema directory for COPY DDSR resolution
	 */
	private static boolean generateJava(final CobolTransformationRunner runner, File cblFile,
			final File javaFile, final List<File> copybookDirs, final File ddsSchemaDir) {
		try {
			// Preprocess: normalize DDSR-ALL-FORMAT (singular) to DDSR-ALL-FORMATS (plural)
			// IBM ILE COBOL accepts both, but the DdsCopyBookGenerator only handles FORMATS.
			final String src = new String(Files.readAllBytes(cblFile.toPath()));
			if (src.contains("DDSR-ALL-FORMAT ") && !src.contains("DDSR-ALL-FORMATS ")) {
				final String normalized = src.replace("DDSR-ALL-FORMAT ", "DDSR-ALL-FORMATS ");
				final Path tempDir = Files.createTempDirectory("cobol_norm_");
				final Path tempFile = tempDir.resolve(cblFile.getName());
				Files.write(tempFile, normalized.getBytes());
				cblFile = tempFile.toFile();
			}

			final CobolParserParams params = new CobolParserParamsImpl();
			// These tools always process IBM i source; request that dialect explicitly.
			params.setDialect(CobolDialect.IBM_ILE);
			params.setFormat(CobolSourceFormatEnum.FIXED);
			params.setCopyBookDirectories(copybookDirs);

			// Set DDS schema directory for COPY DDSR resolution (matches SingleFileTransformer)
			if (ddsSchemaDir != null && ddsSchemaDir.isDirectory()) {
				params.setDdsSchemaDirectory(ddsSchemaDir);
			}

			final List<File> result = runner.transformFile(cblFile, "", params);

			if (result.isEmpty()) {
				return false;
			}

			Files.copy(result.get(0).toPath(), javaFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

			return javaFile.exists() && javaFile.length() > 0;
		} catch (final Exception e) {
			System.err.printf("=== %s === GENERATION EXCEPTION ===\n",
					cblFile.getName().replaceFirst("\\.[^.]+$", ""));
			e.printStackTrace(System.err);
			System.err.println();
			return false;
		}
	}

	/**
	 * Compile a Java file. Returns null on success, or error details string on failure.
	 */
	private static String compileJava(final JavaCompiler compiler, final File javaFile, final String compileClasspath) {
		try {
			final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
			final StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);

			final Iterable<? extends JavaFileObject> compilationUnits =
				fileManager.getJavaFileObjectsFromFiles(Collections.singletonList(javaFile));

			final List<String> options = Arrays.asList(
				"-cp", compileClasspath,
				"-proc:none"
			);

			final JavaCompiler.CompilationTask task =
				compiler.getTask(null, fileManager, diagnostics, options, null, compilationUnits);

			final boolean success = task.call();
			fileManager.close();

			if (success) {
				return null;
			}

			final StringBuilder allErrors = new StringBuilder();
			for (final Diagnostic<? extends JavaFileObject> diag : diagnostics.getDiagnostics()) {
				if (diag.getKind() == Diagnostic.Kind.ERROR) {
					allErrors.append(String.format("line %d: %s\n", diag.getLineNumber(), diag.getMessage(null)));
				}
			}
			return allErrors.toString();
		} catch (final Exception e) {
			return e.getClass().getSimpleName() + ": " + e.getMessage();
		}
	}

	private static String sanitizeCsv(final String s) {
		if (s == null) return "";
		return s.replace(',', ';').replace('\n', ' ').replace('\r', ' ');
	}

	private static String firstLine(final Exception e) {
		final String msg = e.getMessage();
		if (msg == null) return e.getClass().getSimpleName();
		return firstLineStr(msg);
	}

	private static String firstLineStr(final String s) {
		if (s == null) return "";
		final int nl = s.indexOf('\n');
		if (nl > 0) return s.substring(0, Math.min(nl, 200));
		return s.substring(0, Math.min(s.length(), 200));
	}

	/**
	 * Load the set of program names to skip from baseDir/skip_programs.txt.
	 * Each line is a program name (without .cbl extension).
	 * Lines starting with # and blank lines are ignored.
	 * Returns empty set if the file does not exist.
	 */
	private static Set<String> loadSkipPrograms(final Path baseDir) {
		final Set<String> skip = new HashSet<>();
		final File skipFile = baseDir.resolve("skip_programs.txt").toFile();
		if (!skipFile.exists()) {
			return skip;
		}
		try (final BufferedReader br = new BufferedReader(new FileReader(skipFile))) {
			String line;
			while ((line = br.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				skip.add(line);
			}
		} catch (final Exception e) {
			System.err.printf("WARNING: Could not read skip file %s: %s\n", skipFile, e.getMessage());
		}
		return skip;
	}
}
