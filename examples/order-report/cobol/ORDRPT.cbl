      *****************************************************************
      * ORDRPT - Order line pricing report                            *
      *                                                               *
      * Synthetic IBM i (AS/400) ILE COBOL program written for this   *
      * repository. It exercises the AS/400-specific features that    *
      * this fork adds on top of upstream ProLeap:                    *
      *                                                               *
      *   1. PROCESS compiler directive (IBM i, not in standard COBOL)*
      *   2. COPY DDSR ... OF <file> - DDS record format import,      *
      *      expanded from dds/ORDERP_schema.json                     *
      *   3. DDS VARCHAR field (ITEMDS) - length + data subfields     *
      *   4. DDS indicator fields (*IN50/*IN51) as PIC 1 INDIC        *
      *   5. Packed decimal (COMP-3) arithmetic with scale            *
      *   6. Alphanumeric/numeric MOVE with IBM padding semantics     *
      *   7. INSPECT, STRING, INITIALIZE, EVALUATE                    *
      *   8. GOBACK (IBM i preferred over STOP RUN)                   *
      *                                                               *
      * No proprietary source: all names and data are invented.       *
      *****************************************************************
       PROCESS NOMONOPRC.
       IDENTIFICATION DIVISION.
       PROGRAM-ID. ORDRPT.
       AUTHOR. PROLEAP-EXAMPLE.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       SOURCE-COMPUTER. IBM-AS400.
       OBJECT-COMPUTER. IBM-AS400.

       DATA DIVISION.
       WORKING-STORAGE SECTION.

      * DDS record formats imported from the ORDERP schema.
      * This COPY is resolved by DdsCopyBookGenerator, not by a
      * physical copybook file on disk.
       01  ORDER-RECORD.
           COPY DDSR-ALL-FORMATS OF ORDERP.

      * Report accumulators. COMP-3 is packed decimal, the native
      * numeric format on IBM i.
       01  WS-TOTALS.
           05  WS-LINE-COUNT       PIC S9(3)    COMP-3 VALUE 0.
           05  WS-NET-TOTAL        PIC S9(11)V99 COMP-3 VALUE 0.
           05  WS-VAT-TOTAL        PIC S9(11)V99 COMP-3 VALUE 0.
           05  WS-GROSS-TOTAL      PIC S9(11)V99 COMP-3 VALUE 0.

       01  WS-VAT-RATE             PIC S9(3)V99 COMP-3 VALUE 23.00.

      * Working fields for the formatting demonstrations.
       01  WS-WORK-FIELDS.
           05  WS-DESC-TEXT        PIC X(30).
           05  WS-DESC-LEN         PIC S9(4) COMP-4.
           05  WS-SPACE-COUNT      PIC S9(4) COMP-4 VALUE 0.
           05  WS-REPORT-LINE      PIC X(80).
           05  WS-AMOUNT-EDITED    PIC ZZZ,ZZZ,ZZ9.99.
           05  WS-CATEGORY         PIC X(12).

       PROCEDURE DIVISION.

       MAIN-PARAGRAPH.
           PERFORM INITIALISE-REPORT
           PERFORM LOAD-SAMPLE-HEADER
           PERFORM PROCESS-SAMPLE-LINES
           PERFORM PRINT-TOTALS
           GOBACK.

      *----------------------------------------------------------------
      * INITIALIZE on a DDS-derived group: every subordinate field is
      * reset to its type default (spaces for X, zero for 9).
      *----------------------------------------------------------------
       INITIALISE-REPORT.
           INITIALIZE ORDER-RECORD
           MOVE 0 TO WS-LINE-COUNT
           MOVE 0 TO WS-NET-TOTAL
           MOVE 0 TO WS-VAT-TOTAL
           MOVE 0 TO WS-GROSS-TOTAL
           DISPLAY "=== ORDER LINE PRICING REPORT ==="
           DISPLAY " ".

      *----------------------------------------------------------------
      * Header fields come from the ORDHDR DDS format. Note the IBM
      * MOVE semantics: a short literal into PIC X(20) pads on the
      * right with spaces; a numeric literal into COMP-3 keeps scale.
      *----------------------------------------------------------------
       LOAD-SAMPLE-HEADER.
           MOVE 1042 TO ORDNUM OF ORDHDR
           MOVE "NORTHWIND SUPPLIES" TO CUSTNM OF ORDHDR
           MOVE "2026-09-01" TO ORDDAT OF ORDHDR
           MOVE "EUR" TO CURRCD OF ORDHDR

           DISPLAY "ORDER  : " ORDNUM OF ORDHDR
           DISPLAY "CUSTOMER: " CUSTNM OF ORDHDR
           DISPLAY "DATE   : " ORDDAT OF ORDHDR
           DISPLAY "CURRENCY: " CURRCD OF ORDHDR
           DISPLAY " ".

      *----------------------------------------------------------------
      * Three sample lines, each exercising a different code path.
      *----------------------------------------------------------------
       PROCESS-SAMPLE-LINES.
           MOVE 1 TO LINNUM OF ORDLIN
           MOVE "WIDGET01" TO ITEMCD OF ORDLIN
           MOVE "BLUE WIDGET" TO WS-DESC-TEXT
           MOVE 12 TO QTYORD OF ORDLIN
           MOVE 4.50 TO UNTPRC OF ORDLIN
           PERFORM PROCESS-ONE-LINE

           MOVE 2 TO LINNUM OF ORDLIN
           MOVE "GEAR0042" TO ITEMCD OF ORDLIN
           MOVE "STEEL GEAR ASSEMBLY" TO WS-DESC-TEXT
           MOVE 3 TO QTYORD OF ORDLIN
           MOVE 129.99 TO UNTPRC OF ORDLIN
           PERFORM PROCESS-ONE-LINE

           MOVE 3 TO LINNUM OF ORDLIN
           MOVE "BOLT0007" TO ITEMCD OF ORDLIN
           MOVE "HEX BOLT M8" TO WS-DESC-TEXT
           MOVE 250 TO QTYORD OF ORDLIN
           MOVE 0.15 TO UNTPRC OF ORDLIN
           PERFORM PROCESS-ONE-LINE.

      *----------------------------------------------------------------
      * Per-line processing. Demonstrates VARCHAR handling, INSPECT,
      * COMP-3 arithmetic, EVALUATE and DDS indicators.
      *----------------------------------------------------------------
       PROCESS-ONE-LINE.
      *    DDS VARCHAR: the generator expands ITEMDS into a group with
      *    ITEMDS-LENGTH (binary) and ITEMDS-DATA (character).
           MOVE WS-DESC-TEXT TO ITEMDS-DATA OF ORDLIN
           MOVE FUNCTION LENGTH (FUNCTION TRIM (WS-DESC-TEXT))
             TO ITEMDS-LENGTH OF ORDLIN

      *    INSPECT counts trailing filler in the fixed-width field.
           MOVE 0 TO WS-SPACE-COUNT
           INSPECT WS-DESC-TEXT TALLYING WS-SPACE-COUNT
             FOR ALL SPACES

      *    Packed decimal arithmetic. LINAMT has scale 2, so the
      *    product is rounded to two decimal places.
           COMPUTE LINAMT OF ORDLIN ROUNDED =
               QTYORD OF ORDLIN * UNTPRC OF ORDLIN

           ADD LINAMT OF ORDLIN TO WS-NET-TOTAL
           ADD 1 TO WS-LINE-COUNT

      *    EVALUATE drives the DDS indicators: *IN50 flags a high-value
      *    line, *IN51 a bulk line. On a real 5250 screen these would
      *    switch display attributes.
           MOVE "0" TO IN50 OF ORDLIN-INDIC
           MOVE "0" TO IN51 OF ORDLIN-INDIC

           EVALUATE TRUE
               WHEN LINAMT OF ORDLIN > 300
                   MOVE "1" TO IN50 OF ORDLIN-INDIC
                   MOVE "HIGH VALUE" TO WS-CATEGORY
               WHEN QTYORD OF ORDLIN > 100
                   MOVE "1" TO IN51 OF ORDLIN-INDIC
                   MOVE "BULK" TO WS-CATEGORY
               WHEN OTHER
                   MOVE "STANDARD" TO WS-CATEGORY
           END-EVALUATE

      *    STRING builds the report line from mixed field types.
           MOVE SPACES TO WS-REPORT-LINE
           MOVE LINAMT OF ORDLIN TO WS-AMOUNT-EDITED

           STRING "LINE "        DELIMITED BY SIZE
                  LINNUM OF ORDLIN DELIMITED BY SIZE
                  "  ITEM "      DELIMITED BY SIZE
                  ITEMCD OF ORDLIN DELIMITED BY SIZE
                  "  AMT "       DELIMITED BY SIZE
                  WS-AMOUNT-EDITED DELIMITED BY SIZE
                  "  "           DELIMITED BY SIZE
                  WS-CATEGORY    DELIMITED BY SIZE
             INTO WS-REPORT-LINE
           END-STRING

           DISPLAY WS-REPORT-LINE
           DISPLAY "     DESC='" ITEMDS-DATA OF ORDLIN "'"
           DISPLAY "     VARCHAR LEN=" ITEMDS-LENGTH OF ORDLIN
                   "  TRAILING SPACES=" WS-SPACE-COUNT
           DISPLAY "     IND50=" IN50 OF ORDLIN-INDIC
                   " IND51=" IN51 OF ORDLIN-INDIC
           DISPLAY " ".

      *----------------------------------------------------------------
      * VAT is computed on the accumulated net, then the gross.
      * Division by 100 with ROUNDED shows IBM rounding behaviour.
      *----------------------------------------------------------------
       PRINT-TOTALS.
           COMPUTE WS-VAT-TOTAL ROUNDED =
               WS-NET-TOTAL * WS-VAT-RATE / 100
           COMPUTE WS-GROSS-TOTAL =
               WS-NET-TOTAL + WS-VAT-TOTAL

           DISPLAY "=== TOTALS ==="
           DISPLAY "LINES     : " WS-LINE-COUNT

           MOVE WS-NET-TOTAL TO WS-AMOUNT-EDITED
           DISPLAY "NET       : " WS-AMOUNT-EDITED

           MOVE WS-VAT-TOTAL TO WS-AMOUNT-EDITED
           DISPLAY "VAT (23%) : " WS-AMOUNT-EDITED

           MOVE WS-GROSS-TOTAL TO WS-AMOUNT-EDITED
           DISPLAY "GROSS     : " WS-AMOUNT-EDITED.
