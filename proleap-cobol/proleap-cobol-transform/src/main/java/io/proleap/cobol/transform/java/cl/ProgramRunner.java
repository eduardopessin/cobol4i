package io.proleap.cobol.transform.java.cl;

/**
 * Runtime interface for executing IBM i operations from converted CL programs.
 * Implementations bridge to the actual Java-migrated programs or legacy system.
 */
public interface ProgramRunner {

    void call(String programName, Object... params);

    void execute(String command, String params);

    void checkObjectExists(String object, String objectType);

    void delete(String command, String target);

    void duplicateObject(String object, String fromLib, String toLib, String newObject);

    void compile(String command, String target, String srcFile, String srcMember);

    void clearFile(String file);

    void receiveFile();

    void runSqlStatement(String srcFile, String srcMember);

    void displayFileDescription(String file, String outFile);

    void shellCommand(String command);
}
