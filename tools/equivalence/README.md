# Equivalence harness

Behavioural baselines for transpiled COBOL programs — the third gate described
in the [root README](../../README.md).

## What it does

Transpiled COBOL is deterministic. This tool runs a set of generated programs,
records the state of their fields after execution, and stores that as a
baseline. Later runs are compared against it.

It answers one question: **did anything change?** That is what makes it safe to
fix a transformer rule, refactor the runtime, upgrade the JDK, or let a model
rewrite the generated Java.

It does *not* answer whether the Java is correct against the original system —
that needs the IBM i machine and a comparison run.

## Usage

```bash
export HARNESS_BASE=/path/to/migration-project
export HARNESS_JAVA=/path/to/jdk21/bin/java
export HARNESS_JAVAC=/path/to/jdk21/bin/javac

# record a baseline
python3 harness.py record --programs PROG1,PROG2,PROG3

# later — after any change
python3 harness.py verify        # exit 1 if behaviour changed
```

`verify` reads the program list from the recorded baseline, so it takes no
arguments. Exit code 1 makes it usable as a CI gate.

### Configuration

| Variable | Purpose |
|----------|---------|
| `HARNESS_BASE` | Project root containing `generated/` and `proleap-cobol/` |
| `HARNESS_JAVA` / `HARNESS_JAVAC` | JDK to use — must match the one that compiled the generated classes |
| `HARNESS_CLASSPATH` | Override the whole classpath if your layout differs |
| `HARNESS_GOLDEN` | Where baselines live (default: `./golden`) |
| `HARNESS_TIMEOUT` | Seconds per program (default: 90) |

Baselines are not committed here: they contain program names and field values
from whatever codebase you point the harness at.

## Which programs to pick

Start with self-contained ones — no SQL, no file I/O, no `CALL`, no display
file. They run without any external system. To find them:

```bash
grep -L 'sqlService\.\|fileControlService\.\|programRunner\.call' generated/cobol/*.java
```

Programs needing SQL or file I/O can be included later with fixtures or stubs.

## Two design decisions worth knowing

**It observes state, not stdout.** Most transpiled programs are subroutines:
they communicate through LINKAGE and print nothing. An earlier version captured
stdout and cheerfully recorded baselines of zero bytes — a check that could
never fail. What matters is the value of WORKING-STORAGE, LINKAGE and indicator
fields after `procedureDivision()` returns.

**The JDK is pinned, not inherited.** Generated classes compiled by JDK 21 will
not load on a JDK 17 JVM. Less obviously, javac 17 and javac 21 emit different
synthetic fields for inner classes, so reflection sees a different object graph.
For generated code, the compiler version is part of the artefact's definition.

Volatile fields — timestamps, tokens, session data — are excluded from the
snapshot, since they differ between runs by design.

## Verifying the harness itself

A check that cannot fail is worse than no check. To confirm it works, introduce
a deliberate fault and make sure it is caught. For example, in
`CobolMove.moveAlphanumericToAlphanumeric`, swap right padding for left:

```java
return CobolConstants.spaces(targetLength - src.length()) + src;   // wrong
```

Rebuild the runtime and run `verify`. It should report differing fields and
exit 1. Revert, rebuild, and it should return to green.

When this was done on a real codebase, 20 of 21 programs showed no difference
and one caught it — which is exactly why coverage matters.
