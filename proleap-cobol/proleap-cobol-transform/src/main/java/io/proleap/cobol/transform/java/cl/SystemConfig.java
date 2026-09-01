package io.proleap.cobol.transform.java.cl;

/**
 * Runtime interface for IBM i system configuration from converted CL programs.
 * Manages library lists, file overrides, data areas, and job attributes.
 */
public interface SystemConfig {

    void overrideFile(String logicalFile, String physicalFile);

    void setLibraryList(String libraryList);

    void setCurrentLibrary(String library);

    String getJobAttribute(String attribute);

    String getSystemValue(String sysval);

    String getDataArea(String dataArea);

    void setDataArea(String dataArea, String value);

    void setEnvironmentVariable(String name, String value);
}
