Licensing
=========

The root LICENSE is MIT and covers the work added by this fork. It is
deliberately plain MIT text so that automated licence detection recognises it.

**Important:** this repository vendors two upstream projects that keep their
own licences. Code inside those directories is NOT covered by the root MIT
licence:

    proleap-cobol-parser/     MIT           (c) 2017 Ulrich Wolffgang
    proleap-cobol/            AGPL-3.0      (c) 2017 Ulrich Wolffgang

Each has its own LICENSE file, which governs everything beneath it.

Modifications and additions in this fork — IBM i dialect support, the COBOL
runtime, DDS/CL tooling, the equivalence harness and the examples — are
released under the licence of the module they live in:

    proleap-cobol-parser/     ... MIT
    proleap-cobol/            ... AGPL-3.0
    tools/                    ... MIT
    examples/                 ... MIT

Practical consequences
----------------------

If you only need the parser, the ASG and the IBM i preprocessing (DDS copybook
expansion, PROCESS directives), you can use proleap-cobol-parser under MIT.

The transformer and runtime live under proleap-cobol and are AGPL-3.0. If you
run a modified version as a network service, AGPL requires you to offer the
corresponding source to its users. For an offline batch migration this
obligation does not arise, but check it against your situation.

Upstream
--------

    https://github.com/uwol/proleap-cobol-parser     (MIT)
    https://github.com/uwol/proleap-cobol            (AGPL-3.0)

Copyright (c) 2017 Ulrich Wolffgang for the upstream work.
