#!/usr/bin/env python3
"""
Equivalence harness for transpiled COBOL programs.

Why this exists
---------------
Transpiled COBOL is deterministic: same inputs, same outputs. Recording the
post-execution state of a set of programs gives you a behavioural baseline.
Any later change — a transformer fix, a runtime refactor, an LLM rewrite, a
different JDK — can then be checked against it.

This is the third gate described in the README. It does not prove the Java is
*correct* (that needs the original IBM i system); it proves the behaviour did
not *change* without someone noticing.

What it observes, and why not stdout
------------------------------------
Most transpiled programs are subroutines: they communicate through LINKAGE and
print nothing. An earlier version of this harness captured stdout and happily
recorded 21 baselines of zero bytes — a check that could never fail.

What matters is the state of the program's fields after execution:
WORKING-STORAGE, LINKAGE, indicators. That snapshot is the behavioural
signature.

Usage
-----
    python3 harness.py record --programs A,B,C
    python3 harness.py verify

Configuration (environment):
    HARNESS_BASE      project root containing generated/ and proleap-cobol/
    HARNESS_JAVA      java binary   (must match the JDK used to compile)
    HARNESS_JAVAC     javac binary
    HARNESS_GOLDEN    where baselines are stored (default: ./golden)
"""
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

BASE = Path(os.environ.get("HARNESS_BASE", ".")).resolve()
GOLDEN = Path(os.environ.get("HARNESS_GOLDEN", Path(__file__).parent / "golden"))
WORK = Path(os.environ.get("HARNESS_WORK", "/tmp/equivalence-probe"))

# The JDK must match the one used to compile the generated classes. Bytecode
# from a newer JDK will not load on an older JVM, and — more subtly — javac 17
# and javac 21 emit different synthetic fields for inner classes, which changes
# what reflection sees. Pin it; do not inherit it from PATH.
JAVA = os.environ.get("HARNESS_JAVA", "java")
JAVAC = os.environ.get("HARNESS_JAVAC", "javac")

TIMEOUT = int(os.environ.get("HARNESS_TIMEOUT", "90"))


def classpath():
    """Classpath for running generated programs. Override with HARNESS_CLASSPATH."""
    override = os.environ.get("HARNESS_CLASSPATH")
    if override:
        return override
    return ":".join([
        str(BASE / "generated/cobol"),
        str(BASE / "proleap-cobol/proleap-cobol-runtime/target/proleap-cobol-runtime-1.0.0.jar"),
        str(BASE / "proleap-cobol/proleap-cobol-app/proleap-cobol-app/target/lib/*"),
        str(BASE / "spring-boot-wrapper/target/classes"),
    ])


PROBE_SRC = r'''
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;

/** Runs a generated COBOL program and serialises its post-execution state. */
public class StateProbe {

    /** Fields that differ between runs and cannot serve as a baseline. */
    static boolean isVolatile(String n) {
        String l = n.toLowerCase();
        return l.contains("timestamp") || l.contains("datetime")
            || l.contains("_hora") || l.contains("hora_")
            || l.contains("data_sistema") || l.contains("token")
            || l.contains("sqlerrmc") || l.contains("sqlwarn");
    }

    static String render(Object v) {
        if (v == null) return "null";
        if (v instanceof BigDecimal) return ((BigDecimal) v).toPlainString();
        return String.valueOf(v);
    }

    public static void main(String[] a) throws Exception {
        String name = a[0];
        Map<String, String> state = new TreeMap<>();
        String status = "ok";
        String error = "";

        try {
            Class<?> c = Class.forName(name);
            Object p = c.getDeclaredConstructor().newInstance();

            Object fcs = new io.proleap.cobol.runtime.impl.FileControlServiceImpl();
            Object runner = new io.proleap.cobol.runtime.impl.ProgramRunnerImpl();
            for (Method m : c.getMethods())
                if (m.getName().equals("init") && m.getParameterCount() == 3)
                    try { m.invoke(p, fcs, null, runner); } catch (Throwable ignored) {}

            try {
                c.getMethod("procedureDivision").invoke(p);
            } catch (InvocationTargetException e) {
                status = "exception";
                error = e.getCause().getClass().getSimpleName();
            }

            for (Field f : c.getDeclaredFields()) {
                if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) continue;
                if (isVolatile(f.getName())) continue;
                Class<?> t = f.getType();
                if (t != String.class && t != BigDecimal.class
                    && t != boolean.class && t != int.class && t != long.class) continue;
                f.setAccessible(true);
                try { state.put(f.getName(), render(f.get(p))); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            status = "failed";
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"status\":\"").append(status).append("\",");
        sb.append("\"error\":\"").append(error.replace("\"", "'")).append("\",");
        sb.append("\"fields\":").append(state.size()).append(",");
        sb.append("\"state\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : state.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(e.getKey()).append("\":\"")
              .append(e.getValue().replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
        }
        sb.append("}}");
        System.out.println(sb);
    }
}
'''


def build_probe():
    WORK.mkdir(parents=True, exist_ok=True)
    src = WORK / "StateProbe.java"
    src.write_text(PROBE_SRC)
    r = subprocess.run([JAVAC, "-nowarn", "-d", str(WORK), "-cp", classpath(), str(src)],
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("Could not compile the probe:\n" + r.stderr[:800])


def snapshot(name):
    try:
        p = subprocess.run(
            [JAVA, "--add-opens", "java.base/java.lang=ALL-UNNAMED",
             "-cp", f"{WORK}:{classpath()}", "StateProbe", name],
            capture_output=True, text=True, timeout=TIMEOUT, cwd=str(BASE))
        line = p.stdout.strip().splitlines()[-1] if p.stdout.strip() else ""
        if line.startswith("{"):
            return json.loads(line)
        return {"status": "no-output", "error": p.stderr.strip()[-120:], "fields": 0, "state": {}}
    except subprocess.TimeoutExpired:
        return {"status": "timeout", "error": "", "fields": 0, "state": {}}
    except Exception as e:
        return {"status": "harness-error", "error": str(e)[:150], "fields": 0, "state": {}}


def program_list(args):
    if args.programs:
        return [p.strip() for p in args.programs.split(",") if p.strip()]
    listing = GOLDEN / "programs.txt"
    if listing.exists():
        return [l.strip() for l in listing.read_text().splitlines() if l.strip()]
    sys.exit("No programs given. Use --programs A,B,C (recorded for later runs).")


def record(args):
    programs = program_list(args)
    build_probe()
    GOLDEN.mkdir(parents=True, exist_ok=True)
    (GOLDEN / "programs.txt").write_text("\n".join(programs) + "\n")

    print(f"{'PROGRAM':<14} {'STATUS':<10} {'FIELDS':<8} NOTE")
    print("-" * 62)
    usable = 0
    for n in programs:
        s = snapshot(n)
        (GOLDEN / f"{n}.json").write_text(json.dumps(s, indent=1, sort_keys=True))
        if s["fields"] > 0:
            usable += 1
        print(f"{n:<14} {s['status']:<10} {s['fields']:<8} {s['error'][:30]}")

    print()
    print(f"baselines with observable state: {usable}/{len(programs)}")
    print(f"stored in: {GOLDEN}")
    if usable == 0:
        print("WARNING: no observable state — a baseline of nothing always passes.")
        return 1
    return 0


def verify(args):
    if not GOLDEN.exists():
        sys.exit("No baseline. Run: harness.py record --programs ...")
    programs = program_list(args)
    build_probe()

    same, differ = [], []
    print(f"{'PROGRAM':<14} RESULT")
    print("-" * 62)
    for n in programs:
        f = GOLDEN / f"{n}.json"
        if not f.exists():
            print(f"{n:<14} no baseline")
            continue
        expected = json.loads(f.read_text())
        actual = snapshot(n)
        if expected.get("state") == actual.get("state") and expected.get("status") == actual.get("status"):
            same.append(n)
            print(f"{n:<14} OK ({actual['fields']} fields)")
        else:
            differ.append(n)
            changed = [k for k in expected.get("state", {})
                       if expected["state"].get(k) != actual.get("state", {}).get(k)]
            print(f"{n:<14} DIFFERS  changed fields: {changed[:4]}")

    print()
    print(f"same={len(same)}  differ={len(differ)}")
    return 1 if differ else 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["record", "verify"])
    ap.add_argument("--programs", help="comma-separated program names")
    args = ap.parse_args()

    sys.exit(record(args) if args.command == "record" else verify(args))
