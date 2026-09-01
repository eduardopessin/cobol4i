package io.proleap.cobol.runtime;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.proleap.cobol.runtime.impl.FileControlServiceImpl;
import io.proleap.cobol.runtime.impl.ProgramRunnerImpl;
import io.proleap.cobol.runtime.impl.SqlServiceImpl;

/**
 * Spring Boot configuration for the COBOL runtime.
 * Provides beans for FileControlService, SqlService, and ProgramRunner.
 */
@Configuration
public class CobolRuntimeConfiguration {

	@Bean
	public FileControlService fileControlService(final DataSource dataSource) {
		return new FileControlServiceImpl(dataSource);
	}

	@Bean
	public SqlService sqlService(final DataSource dataSource) {
		return new SqlServiceImpl(dataSource);
	}

	@Bean
	public ProgramRunner programRunner(final FileControlService fileControlService, final SqlService sqlService) {
		return new ProgramRunnerImpl(fileControlService, sqlService);
	}
}
