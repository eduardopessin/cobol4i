# Architecture — the layers, and why they are where they are

This fork adds IBM i support to ProLeap at four distinct points. Each addition
sits in the layer that owns the problem it solves, and this document explains
which layer that is and why.

The short version: **each layer works on the only representation where the
information it needs still exists.**

```
   COBOL text
      │
      │  1. PREPROCESSOR — text in, text out
      │     Nothing has been parsed yet. This is the only place where
      │     source that ProLeap's grammar cannot accept can still be fixed.
      ▼
   parseable COBOL
      │
      │  2. PARSER / ASG — upstream ProLeap, untouched
      │     Grammar, AST, semantic graph, symbol resolution.
      ▼
   ASG (resolved semantic graph)
      │
      │  3. TRANSFORM RULES — ASG node in, Java text out
      │     One rule per COBOL construct. This is where the ASG is still
      │     available, so decisions needing type or symbol information
      │     must happen here.
      ▼
   generated Java (text)
      │
      │  4. POST-PROCESSORS — text in, text out
      │     The ASG is gone. Only whole-file consistency problems that
      │     cannot be seen one node at a time are left.
      ▼
   compilable Java
      │
      │  5. RUNTIME — executes the generated Java
      │     COBOL semantics that cannot be expressed as Java syntax.
      ▼
   program behaviour
```

---

## 1. Preprocessor — because the grammar has not run yet

`proleap-cobol-parser/.../preprocessor/impl/CobolPreprocessorImpl.java`

The preprocessor is text-to-text, before any parsing. That makes it the only
layer that can accept source the grammar would reject outright. Five IBM i
transformations live here, and the ordering is not arbitrary:

| Step | What it does | Why it must be here |
|------|--------------|---------------------|
| `stripProcessDirectives` | Removes `PROCESS` / `CBL` compiler directives | They are not COBOL statements; the grammar has no rule for them |
| `stripTrailingCommasAfterPeriod` | Removes `PIC X(10).  ,` | In ILE COBOL commas are optional separators; the grammar sees an unexpected token |
| `injectImplicitWorkingStorageSection` | Adds a missing `WORKING-STORAGE SECTION` header | ILE allows it to be implicit; the grammar requires it |
| `injectImplicitPeriodBeforeParagraphs` | Adds a missing sentence terminator | ILE tolerates the omission; the grammar does not |
| `expandTypedefReferences` | Expands `TYPEDEF` references | IBM extension with no upstream grammar support |

`stripProcessDirectives` runs **twice** — once on the raw file, once after COPY
expansion — because a copybook member can itself contain `PROCESS` lines that
only become visible once it has been included.

### DDS copybook generation

`preprocessor/sub/copybook/DdsCopyBookGenerator.java`

`COPY DDSR-<format> OF <file>` has no copybook on disk: the record layout comes
from a DDS description of the file. The generator synthesises the copybook text
from a schema.

It sits beside `FilenameCopyBookFinder` and `LiteralCopyBookFinder`, in the
package that owns copybook resolution — because that is exactly what it is. The
source of the text differs; the job does not.

---

## 2. Parser and ASG — untouched upstream

The grammar, AST and semantic graph are Ulrich Wolffgang's work and are not
modified. Everything above arrives as COBOL the upstream grammar can already
parse; everything below consumes the ASG it produces.

Keeping this layer untouched is deliberate: it is the part most likely to
receive upstream improvements.

---

## 3. Transform rules — because the ASG is still there

`proleap-cobol/proleap-cobol-transform/.../rules/lang/...`

One rule per COBOL construct, in the upstream taxonomy: a `MOVE` rule under
`procedure/move`, a `COMPUTE` rule under `procedure/compute`, data description
rules under `data/datadescription`. IBM i rules are not segregated into a
separate tree — a MOVE rule belongs with the MOVE rules.

This layer is where anything requiring **type or symbol information** must
happen, because it is the last point at which the ASG exists. `MOVE` is the
clearest example: what the statement means depends on the PICTURE clauses of
both operands, and that is only knowable from resolved data description
entries. Once Java text has been emitted, that information is gone.

---

## 4. Post-processors — because the ASG is gone, and some problems are global

`proleap-cobol-transform/.../runner/impl/CobolTransformationRunnerImpl.java`

Around 36 text-to-text passes over the generated Java. They divide into three
kinds:

**Whole-file consistency.** Rules fire per node and cannot see the file.
`postProcessRemoveDuplicateClasses` exists because two `01`-level declarations
of the same record produce two identical inner classes — visible only once the
whole file is written.

**Forward references.** COBOL allows a variable to be used before the structure
declaring it has been seen. `postProcessFixUndeclaredCopybookVariables` and its
siblings create the missing declarations after the fact.

**Java type reconciliation.** COBOL is far more permissive about mixing
alphanumeric and numeric values than Java. A long tail of passes —
`postProcessFixCompareAlphanumericWithBigDecimal`,
`postProcessFixBigDecimalToStringMismatch`,
`postProcessFixMoveNumericFromString` — reconcile what COBOL allowed with what
javac will accept.

This is the least elegant layer, and honestly so: text manipulation on
generated output is a blunt instrument. The reason it exists is that the
alternative — carrying whole-file state through every rule — would make the
rules far worse. The trade is deliberate.

---

## 5. Runtime — because some COBOL semantics are not Java syntax

`proleap-cobol/proleap-cobol-runtime/`

Generated Java calls into a runtime that implements COBOL behaviour directly:

- `CobolMove` — `MOVE` with PICTURE-aware truncation and padding
- `CobolComparison` / `CobolCondition` — COBOL comparison and condition rules
  across mixed types
- `CobolArithmetic` / `CobolIntrinsic` — arithmetic with COBOL rounding, and
  intrinsic functions
- `CobolInitialize`, `EntityServiceImpl` — `INITIALIZE` over group structures,
  by reflection
- `ScreenIoHandler` — display files, subfiles, indicator vectors
- `MessageQueueHandler` — program message queues (`SNDPGMMSG`)
- `FileControlService` — record-level I/O

None of this can be expressed as generated syntax without duplicating it into
every program. Encoding it once, in a library, also makes it the single place
where "what does this COBOL statement actually mean?" is answered — which is
what lets the equivalence harness treat runtime behaviour as the specification.

---

## Selecting the dialect

The five preprocessor steps only run when IBM i is requested:

```java
final CobolParserParams params = new CobolParserParamsImpl();
params.setDialect(CobolDialect.IBM_ILE);
```

`CobolPreprocessorImpl.isIbmILE(params)` guards them at both entry points —
`process()` for the initial `PROCESS`/`CBL` strip, and `parseDocument()` for the
remaining four. Any other dialect, or none, gives upstream ProLeap behaviour.

The transform tooling (`SingleFileTransformer`, `BatchFileTransformer`,
`BulkParseValidator`, `BulkTransformCompiler`, `AbstractCobolService`) always
processes IBM i source, so each sets the dialect explicitly.

Making it opt-in rather than opt-out is deliberate: a caller who has not asked
for IBM i handling should not silently get source rewriting.

### What the switch does not cover

Preprocessing is text, so it can be turned off. The grammar cannot: `Cobol.g4`
and `CobolPreprocessor.g4` carry +375 lines of IBM i extensions that are
compiled into the generated parser.

That is why 8 upstream tests still fail with the standard dialect (see
[Status](../README.md#status-honestly)). Closing that gap means either
narrowing the grammar changes or building two parsers — neither is done.
