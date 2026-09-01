#!/usr/bin/env bash
#
# End-to-end demonstration: IBM i (AS/400) ILE COBOL -> Java -> execution.
#
#   1. transform  cobol/ORDRPT.cbl  ->  generated/ORDRPT.java
#   2. compile    generated/ORDRPT.java -> classes/
#   3. run        the generated Java and print the report
#
# Prerequisites: JDK 17+, Maven, and both modules installed:
#     (cd ../../proleap-cobol-parser && mvn install -DskipTests)
#     (cd ../../proleap-cobol        && mvn install -DskipTests)
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

TRANSFORM_MODULE="$ROOT/proleap-cobol/proleap-cobol-transform"
RUNTIME_MODULE="$ROOT/proleap-cobol/proleap-cobol-runtime"

CP_CACHE="$HERE/.cp"
mkdir -p "$CP_CACHE"

say() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

# --------------------------------------------------------------- classpaths
# Resolved once via Maven and cached, since dependency:build-classpath is slow.
if [ ! -s "$CP_CACHE/transform.txt" ]; then
  say "Resolving transformer classpath (first run only)"
  mvn -q -f "$TRANSFORM_MODULE/pom.xml" dependency:build-classpath \
      -Dmdep.outputFile="$CP_CACHE/transform.txt"
fi

if [ ! -s "$CP_CACHE/runtime.txt" ]; then
  say "Resolving runtime classpath (first run only)"
  mvn -q -f "$RUNTIME_MODULE/pom.xml" dependency:build-classpath \
      -Dmdep.outputFile="$CP_CACHE/runtime.txt"
fi

TRANSFORM_CP="$TRANSFORM_MODULE/target/classes:$(cat "$CP_CACHE/transform.txt")"
RUNTIME_CP="$RUNTIME_MODULE/target/classes:$(cat "$CP_CACHE/runtime.txt")"

if [ ! -d "$TRANSFORM_MODULE/target/classes" ]; then
  echo "ERROR: $TRANSFORM_MODULE/target/classes not found — run 'mvn install -DskipTests' first." >&2
  exit 1
fi

rm -rf "$HERE/generated" "$HERE/classes" "$HERE/refactored/classes"
mkdir -p "$HERE/generated" "$HERE/classes"

# ----------------------------------------------------------------- 1. transform
# -Dcobol.schema.dir points at the DDS schema JSON used to expand
# "COPY DDSR-ALL-FORMATS OF ORDERP" — there is no physical copybook file.
say "1/3  COBOL -> Java"
java -Dcobol.base.dir="$HERE" \
     -Dcobol.copybook.dirs=cobol \
     -Dcobol.schema.dir=dds \
     -cp "$TRANSFORM_CP" \
     io.proleap.cobol.transform.java.validation.SingleFileTransformer \
     "$HERE/cobol/ORDRPT.cbl" \
     "$HERE/generated/ORDRPT.java" 2>&1 | grep -vE '^[0-9]{4}-[0-9]{2}-[0-9]{2}.*(INFO|DEBUG)' || true

if [ ! -s "$HERE/generated/ORDRPT.java" ]; then
  echo "ERROR: transformation produced no output." >&2
  exit 1
fi

# A stub means a paragraph body was lost — the generated code would compile
# but silently do nothing. Fail loudly instead of shipping a broken demo.
if grep -q 'Auto-generated stub' "$HERE/generated/ORDRPT.java"; then
  echo "ERROR: generated code contains auto-generated stubs (paragraph body lost)." >&2
  grep -n 'Auto-generated stub' "$HERE/generated/ORDRPT.java" >&2
  exit 1
fi

# ------------------------------------------------------------------ 2. compile
say "2/3  Compiling generated Java"
javac -nowarn -d "$HERE/classes" -cp "$RUNTIME_CP" "$HERE/generated/ORDRPT.java"
echo "OK: $(find "$HERE/classes" -name '*.class' | wc -l) class files"

# ---------------------------------------------------------------------- 3. run
say "3/4  Running the generated program"
java -cp "$HERE/classes:$RUNTIME_CP" ORDRPT

# ------------------------------------------------------------- 4. equivalence
# Step 2 of the migration: an idiomatic rewrite, checked against the generated
# code. The generated version is the executable specification — if the rewrite
# changes observable behaviour, this fails.
say "4/4  Refactored version — equivalence check"
mkdir -p "$HERE/refactored/classes"
javac -nowarn -d "$HERE/refactored/classes" \
      -cp "$HERE/classes:$RUNTIME_CP" \
      "$HERE"/refactored/src/com/example/orders/*.java

java -cp "$HERE/refactored/classes:$HERE/classes:$RUNTIME_CP" \
     com.example.orders.EquivalenceTest

say "Done"
cat <<'EOF'
Generated Java:  generated/ORDRPT.java     (faithful, unidiomatic)
Refactored:      refactored/src/...        (idiomatic, same behaviour)
Compiled:        classes/, refactored/classes/

Expected totals (verify against the output above):
    NET   = 481.47      (12*4.50 + 3*129.99 + 250*0.15)
    VAT   = 110.74      (23% of NET, half-up)
    GROSS = 592.21
EOF
