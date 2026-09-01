# Example: IBM i (AS/400) COBOL → Java, end to end

A self-contained demonstration of a two-step migration:

```
COBOL  --[deterministic transform]-->  generated Java  --[refactor]-->  idiomatic Java
       \_____________________________________________________________________/
                          equivalence-checked at every step
```

Everything here is invented for this repository — no proprietary source.

```bash
./run.sh
```

## Why two steps

Asking an LLM to translate COBOL straight to idiomatic Java is not verifiable:
structure and semantics change at the same time, so there is no way to tell
what was lost. Splitting the work removes that problem.

**Step 1 is deterministic.** COBOL becomes Java that is faithful but ugly:
flat inner classes mirroring DDS formats, `BigDecimal` everywhere, paragraph
names as methods. It is auditable and repeatable, with no model in the loop.

**Step 2 is where the AI (or a developer) works.** The generated code becomes
idiomatic — records, streams, real names. This is safe precisely *because*
step 1 was boring: the generated program is an executable specification, so
any behavioural change shows up immediately.

The generated Java being unpleasant to read is not a shortcoming. It is the
property that makes the rest verifiable.

## What `run.sh` does

| Step | Action |
|------|--------|
| 1 | `cobol/ORDRPT.cbl` → `generated/ORDRPT.java` |
| 2 | compile the generated Java |
| 3 | run it and print the report |
| 4 | compile the refactored version and diff its output against the generated one |

Step 4 fails the build on any difference.

Expected output (abridged):

```
LINE 001  ITEM WIDGET01  AMT 00000000000054  STANDARD
     DESC='BLUE WIDGET                   '
     VARCHAR LEN=11  TRAILING SPACES=20
     IND50=false IND51=false
...
=== TOTALS ===
LINES     : 3
NET       : 481.47
VAT (23%) : 110.74
GROSS     : 592.21

=== EQUIVALENCE CHECK ===
RESULT: IDENTICAL — refactoring preserved behaviour.
```

The totals are independently verifiable:
`12 × 4.50 + 3 × 129.99 + 250 × 0.15 = 481.47`, VAT at 23% half-up = `110.74`.

## The harness earning its keep

The equivalence check caught a real bug in the refactored code on its first
run. The COBOL is:

```cobol
INSPECT WS-DESC-TEXT TALLYING WS-SPACE-COUNT FOR ALL SPACES
```

`FOR ALL SPACES` counts *every* space in the fixed-width field, including the
ones between words. The rewrite assumed it meant trailing padding only, so
`"BLUE WIDGET"` in a `PIC X(30)` field gave 19 instead of 20. The generated
code had it right; the idiomatic version did not.

That is the whole argument for this approach in one line of output. A plausible
reimplementation was subtly wrong, and nothing but a behavioural diff would
have caught it.

## Files

| Path | What it is |
|------|-----------|
| `cobol/ORDRPT.cbl` | The COBOL source (IBM i ILE dialect, fixed format) |
| `dds/ORDERP_schema.json` | DDS record formats, expanded by `DdsCopyBookGenerator` |
| `refactored/src/.../OrderReport.java` | Idiomatic rewrite (step 2) |
| `refactored/src/.../EquivalenceTest.java` | Output comparison harness |
| `run.sh` | transform → compile → run → verify |
| `generated/`, `classes/` | Build output (created by `run.sh`) |

## What this exercises

These are the AS/400-specific capabilities this fork adds on top of upstream
ProLeap, which targets z/OS mainframe COBOL:

| Feature | Where to look |
|---------|---------------|
| `PROCESS` compiler directive | first line of `ORDRPT.cbl`; stripped by the preprocessor |
| `COPY DDSR-ALL-FORMATS OF ORDERP` | expanded from `dds/ORDERP_schema.json` — no physical copybook exists |
| DDS `VARCHAR` field | `ITEMDS` becomes a group with `-LENGTH` (binary) + `-DATA` (character) |
| DDS indicators (`*IN50`, `*IN51`) | generated as `PIC 1 INDIC nn` inside an `ORDLIN-INDIC` group |
| DDS `-I` / `-O` format variants | `ORDHDR-I`, `ORDHDR-O` generated automatically alongside `ORDHDR` |
| Packed decimal (`COMP-3`) with scale | `LINAMT`, `WS-NET-TOTAL`; `COMPUTE ... ROUNDED` |
| IBM `MOVE` padding semantics | `"EUR"` into `PIC X(3)`, short literals into wider fields |
| `INSPECT ... TALLYING` | counts trailing spaces in the fixed-width description |
| `STRING ... DELIMITED BY SIZE` | builds the report line |
| `INITIALIZE` over a DDS group | `INITIALISE-REPORT` paragraph |
| `EVALUATE TRUE` | category selection driving the indicators |
| `GOBACK` | IBM i's preferred program terminator |

## Known gaps this example exposes

The example is deliberately honest: it shows working behaviour *and* current
limitations. Nothing below is hidden by the demo.

1. **Numeric-edited PICTURE masks are not applied.**
   `WS-AMOUNT-EDITED PIC ZZZ,ZZZ,ZZ9.99` is generated as a plain `BigDecimal`.
   Inside `STRING` it renders as `00000000000054` instead of `54.00`. The
   arithmetic is correct; only the display formatting is wrong. A real
   implementation needs a picture-mask formatter in the runtime.

2. **Indicators print as Java booleans.**
   `PIC 1` fields become `boolean`, so they display as `true`/`false` rather
   than the COBOL `1`/`0`. Semantically equivalent, textually different.

3. **`DISPLAY` of a `COMP-3` field shows the unscaled value.**
   Visible where line amounts are echoed; the totals, which go through
   `MOVE` first, are correct.

These are runtime formatting gaps, not parser or transformer failures. They are
good first contributions.

## One real bug, and one lesson about compilers

Writing this example surfaced a genuine defect:

- **`PROCESS-*` paragraphs were silently deleted.** The preprocessor stripped
  IBM `PROCESS`/`CBL` compiler directives with the regex `^.{6} (PROCESS|CBL)\b.*`.
  `\b` also matches before a hyphen, so a paragraph named `PROCESS-ONE-LINE.`
  was removed as if it were a directive. Its body was absorbed by the preceding
  paragraph and an empty stub was generated in its place — code that compiles
  and does nothing. Fixed in `CobolPreprocessorImpl.stripProcessDirectives`;
  see `CobolPreprocessorProcessDirectiveTest`.

It also surfaced something subtler that is *not* a bug in this codebase:

- **`INITIALIZE` behaves differently depending on the JDK that compiled the
  generated sources.** Generated groups are non-static inner classes. JDK 17
  emits the synthetic `this$N` back-reference to the enclosing instance even
  when the inner class never uses it; JDK 21 optimises it away. Where `this$N`
  exists, the reflective walk in `EntityServiceImpl.initialize` follows it and
  recurses forever.

  The same generated sources run fine under JDK 21 and overflow the stack under
  JDK 17. `EntityServiceImpl` now carries an identity-based cycle guard so the
  behaviour no longer depends on the compiler — see
  `EntityServiceImplInitializeTest`.

  The wider point: for generated code walked by reflection, the compiler
  version is part of the artefact's definition, not an implementation detail.
