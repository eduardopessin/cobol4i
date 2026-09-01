# ProLeap COBOL for IBM i

Deterministic gates for migrating IBM i (AS/400) ILE COBOL to Java, so that an
LLM can do the refactoring afterwards **and you can prove it did not change
behaviour**.

This is a fork of [ProLeap](https://github.com/uwol/proleap-cobol) — an
excellent ANTLR4-based COBOL analyzer, interpreter and transformer — extended
for the IBM i dialect: DDS, display files, indicators, packed decimal, `COPY
DDSR`, and a COBOL runtime for the generated Java.

```bash
cd examples/order-report && ./run.sh
```

That command transforms a COBOL program to Java, compiles it, runs it, then
diffs its output against an idiomatic hand-written rewrite. No AS/400 required.

---

## The problem this solves

Ask an LLM to convert COBOL to idiomatic Java and it will produce something
plausible. You will have no way to tell what it silently changed, because
structure and semantics moved at the same time. For a payroll or billing
system, "plausible" is not a standard anyone can sign off on.

The fix is not a better prompt. It is to put deterministic gates around the
model, so that its output can be checked rather than trusted.

### The three gates

| Gate | What it guarantees | Model involved? |
|------|--------------------|-----------------|
| **Transformer** | COBOL → Java, faithful and repeatable. Same input, same output, every time. | No |
| **Runtime** | COBOL semantics encoded once: `MOVE` padding, `COMP-3` scale, `INSPECT`, IBM numeric coercion. This is the definition of "correct". | No |
| **Equivalence harness** | Snapshots program state after execution; any behavioural change shows up as a diff. See [`tools/equivalence`](tools/equivalence/). | No |

The LLM works *after* the gates, not instead of them:

```
COBOL  --[transformer]-->  faithful Java  --[LLM refactor]-->  idiomatic Java
                                 └──────── equivalence harness ────────┘
```

The generated Java is deliberately unpleasant — flat inner classes mirroring
DDS records, `BigDecimal` everywhere, paragraph names as methods. That is a
requirement, not a shortcoming. A gate has to be predictable, not elegant. Its
job is to be an executable specification for the step that follows.

### It catches real mistakes

While writing the example, the harness rejected a refactoring that looked
obviously correct. The COBOL was:

```cobol
INSPECT WS-DESC-TEXT TALLYING WS-SPACE-COUNT FOR ALL SPACES
```

`FOR ALL SPACES` counts *every* space in the fixed-width field, including the
ones between words — not just trailing padding. The idiomatic rewrite assumed
padding, and was wrong by one for `"BLUE WIDGET"`. Nothing but a behavioural
diff would have caught that.

---

## Why IBM i specifically

Almost every open-source COBOL tool targets z/OS mainframe. IBM i is a
different dialect on a different machine, and it is close to unrepresented.

That matters more than it sounds, because it also means the information is not
online. z/OS COBOL is well covered by public examples; DDS record formats,
`*INxx` indicator semantics, subfile behaviour, `COPY DDSR` expansion and PCML
are documented in IBM manuals and almost nowhere else. Language models are
confidently wrong about most of it.

The runtime in this repository was derived from those manuals and from
observing a real system — not from public examples, because there are none.
That is the part of this work that is hard to reproduce.

### What the IBM i support covers

| Feature | Where |
|---------|-------|
| `COPY DDSR` / `COPY DDS-ALL-FORMATS` expansion from DDS schema | `DdsCopyBookGenerator` (parser) |
| DDS `VARCHAR` → `-LENGTH` + `-DATA` subfields | parser + transform |
| DDS indicators (`*IN50`) → `PIC 1 INDIC nn` groups | parser + transform |
| DDS `-I` / `-O` format variants | parser |
| `PROCESS` / `CBL` compiler directives | preprocessor |
| COBOL runtime semantics (`MOVE`, `COMP-3`, `INSPECT`, `STRING`, `INITIALIZE`) | `proleap-cobol-runtime` |
| CL → Java | `ClToJavaConverter` |
| DDS → JPA entities / Vaadin views | `DdsToJpaGenerator`, `DdsToVaadinGenerator` |

---

## Layout

```
proleap-cobol-parser/          ANTLR4 grammar, AST/ASG  (upstream + IBM i preprocessing)
proleap-cobol/
  proleap-cobol-transform/     COBOL → Java rules       (upstream + IBM i rules, DDS, CL)
  proleap-cobol-runtime/       COBOL semantics in Java  (new here)
  proleap-cobol-analysis/      upstream
  proleap-cobol-interpreter/   upstream
examples/order-report/         end-to-end demo + equivalence check
tools/equivalence/             behavioural baselines for a whole codebase
docs/architecture.md           the layers, and why each addition sits where it does
scan-proprietary.sh            checks no client code leaked into the tree
```

The IBM i support is not one component but four, at different points of the
pipeline: preprocessing (text, before parsing), transform rules (on the ASG),
post-processors (on generated Java) and the runtime (at execution). Each sits
in the layer that still holds the information it needs — see
[docs/architecture.md](docs/architecture.md).

## Build

Requires JDK 17+ and Maven.

```bash
(cd proleap-cobol-parser && mvn install -DskipTests)
(cd proleap-cobol        && mvn install -DskipTests)
cd examples/order-report && ./run.sh
```

`-DskipTests` is needed because 28 upstream parser tests fail by design in this
fork — see [Status](#status-honestly) for why.

### Maven coordinates

This fork publishes under its own coordinates, so it can be installed alongside
upstream ProLeap without replacing it:

| | upstream | this fork |
|---|---|---|
| parser | `io.github.uwol:proleap-cobol-parser:4.0.0` | `io.github.proleap.ibmi:proleap-cobol-parser-ibmi:4.0.0-ibmi1` |
| transformer | `io.proleap:proleap-cobol:1.0.0` | `io.github.proleap.ibmi:proleap-cobol-ibmi:1.0.0-ibmi1` |

This matters more than it looks. The IBM i preprocessing is always on, so a
fork installed under the upstream coordinates would silently change the
behaviour of any other ProLeap project on the same machine — same GAV, two
different artefacts, no warning.

> **The JDK version matters.** Generated groups are non-static inner classes.
> JDK 17 emits the synthetic `this$N` back-reference to the enclosing instance
> even when unused; JDK 21 optimises it away. Anything walking generated
> objects by reflection sees a different object graph on each. For generated
> code, the compiler version is part of the artefact's definition — pin it.

---

## Status, honestly

**Works:** the transformer handles a large real IBM i codebase; the runtime
covers the COBOL constructs listed above; the example runs end to end and its
arithmetic is exact.

**Known gaps:**

- Numeric-edited PICTURE masks (`PIC ZZZ,ZZZ,ZZ9.99`) are not applied — values
  are correct, display formatting is not. Good first contribution.
- `PIC 1` indicators surface as Java `boolean`, printing `true`/`false` rather
  than `1`/`0`.
- Interactive (subfile/display-file) programs are less complete than batch.
- Equivalence harnesses currently cover self-contained programs; those needing
  SQL or file I/O require fixtures.

**Standard COBOL still parses — the dialect is selectable.**

The IBM i source transformations are off unless you ask for them:

```java
final CobolParserParams params = new CobolParserParamsImpl();
params.setDialect(CobolDialect.IBM_ILE);   // opt in
```

Without that line the preprocessor behaves as upstream ProLeap always did. This
took upstream parser failures from 28 down to 8 — the 20 caused by
preprocessing are gone.

The remaining 8 (`FunctionCallTest`, `PictureGreedyTest`, `TableCallTest`,
`ProgramIdCommentEntryTest` and four NIST cases) come from the grammar itself:
`Cobol.g4` and `CobolPreprocessor.g4` carry +375 lines of IBM i extensions, and
a grammar cannot be switched at runtime the way a preprocessing step can.
Fixing those means either narrowing the grammar changes or generating two
parsers. Neither is done yet.

So: standard COBOL is close to upstream behaviour but not identical, and the
gap is now 8 known tests instead of a blanket 28.

**Not claimed:** that generated Java is verified against the original system.
The harness proves behaviour does not *change*; proving it is *right* needs the
IBM i machine and a comparison run.

---

## Credits and licence

Built on [ProLeap COBOL](https://github.com/uwol/proleap-cobol) and
[ProLeap COBOL parser](https://github.com/uwol/proleap-cobol-parser) by
Ulrich Wolffgang. The grammars, AST/ASG, analyzer and interpreter are his work;
this fork adds IBM i dialect support, the runtime, and the verification tooling.

`proleap-cobol` is AGPL-3.0 and `proleap-cobol-parser` is MIT; this fork
inherits those terms per module.

No proprietary source is included. The COBOL under `examples/` was written for
this repository; `scan-proprietary.sh` enforces that nothing else leaks in.
