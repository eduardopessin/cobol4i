/*
* Copyright (C) 2017, Ulrich Wolffgang <ulrich.wolffgang@proleap.io>
* All rights reserved.
*
* This software may be modified and distributed under the terms
* of the MIT license. See the LICENSE file for details.
*/

/*
* COBOL Grammar for ANTLR4
*
* This is a COBOL grammar, which is part of the COBOL parser at
* https://github.com/uwol/proleap-cobol-parser.
*
* The grammar passes the NIST test suite and has successfully been applied to
* numerous COBOL files from banking and insurance. To be used in conjunction
* with the provided preprocessor, which executes COPY and REPLACE statements.
*/

grammar Cobol;

startRule : compilationUnit EOF;

compilationUnit
   : programUnit*
   ;

programUnit
   : compilerDirectives? identificationDivision environmentDivision? dataDivision? procedureDivision? programUnit* endProgramStatement?
   ;

compilerDirectives
   : (PROCESS | CBL) ~DOT_FS* DOT_FS
   ;

endProgramStatement
   : END PROGRAM programName DOT_FS
   ;

// --- identification division --------------------------------------------------------------------

identificationDivision
   : (IDENTIFICATION | ID) DIVISION DOT_FS programIdParagraph identificationDivisionBody*
   ;

identificationDivisionBody
   : authorParagraph | installationParagraph | dateWrittenParagraph | dateCompiledParagraph | securityParagraph | remarksParagraph
   ;

// - program id paragraph ----------------------------------

programIdParagraph
   : PROGRAM_ID DOT_FS programName (IS? (COMMON | INITIAL | LIBRARY | DEFINITION | RECURSIVE) PROGRAM?)? DOT_FS? commentEntry?
   ;

// - author paragraph ----------------------------------

authorParagraph
   : AUTHOR DOT_FS commentEntry?
   ;

// - installation paragraph ----------------------------------

installationParagraph
   : INSTALLATION DOT_FS commentEntry?
   ;

// - date written paragraph ----------------------------------

dateWrittenParagraph
   : DATE_WRITTEN DOT_FS commentEntry?
   ;

// - date compiled paragraph ----------------------------------

dateCompiledParagraph
   : DATE_COMPILED DOT_FS commentEntry?
   ;

// - security paragraph ----------------------------------

securityParagraph
   : SECURITY DOT_FS commentEntry?
   ;

// - remarks paragraph ----------------------------------

remarksParagraph
   : REMARKS DOT_FS commentEntry? END_REMARKS? DOT_FS?
   ;

// --- environment division --------------------------------------------------------------------

environmentDivision
   : (ENVIRONMENT DIVISION DOT_FS)? environmentDivisionBody+
   ;

environmentDivisionBody
   : configurationSection | specialNamesParagraph | inputOutputSection
   ;

// -- configuration section ----------------------------------

configurationSection
   : CONFIGURATION SECTION DOT_FS configurationSectionParagraph*
   ;

// - configuration section paragraph ----------------------------------

configurationSectionParagraph
   : sourceComputerParagraph | objectComputerParagraph | specialNamesParagraph
   // strictly, specialNamesParagraph does not belong into configurationSectionParagraph, but IBM-COBOL allows this
   ;

// - source computer paragraph ----------------------------------

sourceComputerParagraph
   : SOURCE_COMPUTER DOT_FS (computerName (WITH? DEBUGGING MODE)? DOT_FS)?
   ;

// - object computer paragraph ----------------------------------

objectComputerParagraph
   : OBJECT_COMPUTER DOT_FS (computerName objectComputerClause* DOT_FS)?
   ;

objectComputerClause
   : memorySizeClause | diskSizeClause | collatingSequenceClause | segmentLimitClause | characterSetClause
   ;

memorySizeClause
   : MEMORY SIZE? (integerLiteral | cobolWord) (WORDS | CHARACTERS | MODULES)?
   ;

diskSizeClause
   : DISK SIZE? IS? (integerLiteral | cobolWord) (WORDS | MODULES)?
   ;

collatingSequenceClause
   : PROGRAM? COLLATING? SEQUENCE (IS? alphabetName+) collatingSequenceClauseAlphanumeric? collatingSequenceClauseNational?
   ;

collatingSequenceClauseAlphanumeric
   : FOR? ALPHANUMERIC IS? alphabetName
   ;

collatingSequenceClauseNational
   : FOR? NATIONAL IS? alphabetName
   ;

segmentLimitClause
   : SEGMENT_LIMIT IS? integerLiteral
   ;

characterSetClause
   : CHARACTER SET DOT_FS
   ;

// - special names paragraph ----------------------------------

specialNamesParagraph
   : SPECIAL_NAMES DOT_FS (specialNameClause+ DOT_FS?)*
   ;

specialNameClause
   : channelClause | odtClause | alphabetClause | classClause | currencySignClause | decimalPointClause | symbolicCharactersClause | environmentSwitchNameClause | defaultDisplaySignClause | defaultComputationalSignClause | reserveNetworkClause | linkageTypeClause | cursorIsClause | crtStatusClause | programStatusClause
   ;

// IBM ILE: CURSOR IS data-name
cursorIsClause
   : CURSOR IS? dataDescName
   ;

// IBM ILE: CRT STATUS IS data-name
crtStatusClause
   : CRT STATUS IS? dataDescName
   ;

// IBM ILE: PROGRAM STATUS IS data-name [START POSITION IS integer]
programStatusClause
   : PROGRAM STATUS IS? dataDescName (START POSITION IS? integerLiteral)?
   ;

linkageTypeClause
   : LINKAGE TYPE? IS? (PROCEDURE | PROGRAM | cobolWord) FOR? (literal | cobolWord)
   ;


alphabetClause
   : alphabetClauseFormat1 | alphabetClauseFormat2
   ;

alphabetClauseFormat1
   : ALPHABET alphabetName (FOR ALPHANUMERIC)? IS? (EBCDIC | ASCII | STANDARD_1 | STANDARD_2 | NATIVE | cobolWord | alphabetLiterals+)
   ;

alphabetLiterals
   : literal (alphabetThrough | alphabetAlso+)?
   ;

alphabetThrough
   : (THROUGH | THRU) literal
   ;

alphabetAlso
   : ALSO literal+
   ;

alphabetClauseFormat2
   : ALPHABET alphabetName FOR? NATIONAL IS? (NATIVE | CCSVERSION literal)
   ;

channelClause
   : CHANNEL integerLiteral IS? mnemonicName
   ;

classClause
   : CLASS className (FOR? (ALPHANUMERIC | NATIONAL))? IS? classClauseThrough+
   ;

classClauseThrough
   : classClauseFrom ((THROUGH | THRU) classClauseTo)?
   ;

classClauseFrom
   : identifier | literal
   ;

classClauseTo
   : identifier | literal
   ;

currencySignClause
   : CURRENCY SIGN? IS? literal (WITH? PICTURE SYMBOL literal)?
   ;

decimalPointClause
   : DECIMAL_POINT IS? COMMA
   ;

defaultComputationalSignClause
   : DEFAULT (COMPUTATIONAL | COMP)? (SIGN IS?)? (LEADING | TRAILING)? (SEPARATE CHARACTER?)
   ;

defaultDisplaySignClause
   : DEFAULT_DISPLAY (SIGN IS?)? (LEADING | TRAILING) (SEPARATE CHARACTER?)?
   ;

environmentSwitchNameClause
   : environmentName IS? mnemonicName environmentSwitchNameSpecialNamesStatusPhrase? | environmentSwitchNameSpecialNamesStatusPhrase
   ;

environmentSwitchNameSpecialNamesStatusPhrase
   : ON STATUS? IS? condition (OFF STATUS? IS? condition)? | OFF STATUS? IS? condition (ON STATUS? IS? condition)?
   ;

odtClause
   : ODT IS? mnemonicName
   ;

reserveNetworkClause
   : RESERVE WORDS? LIST? IS? NETWORK CAPABLE?
   ;

symbolicCharactersClause
   : SYMBOLIC CHARACTERS? (FOR? (ALPHANUMERIC | NATIONAL))? symbolicCharacters+ (IN alphabetName)?
   ;

symbolicCharacters
   : symbolicCharacter+ (IS | ARE)? integerLiteral+
   ;

// -- input output section ----------------------------------

inputOutputSection
   : INPUT_OUTPUT SECTION DOT_FS inputOutputSectionParagraph*
   ;

// - input output section paragraph ----------------------------------

inputOutputSectionParagraph
   : fileControlParagraph | ioControlParagraph
   ;

// - file control paragraph ----------------------------------

fileControlParagraph
   : FILE_CONTROL? (DOT_FS? fileControlEntry)* DOT_FS
   ;

fileControlEntry
   : selectClause fileControlClause*
   ;

selectClause
   : SELECT OPTIONAL? fileName
   ;

fileControlClause
   : assignClause | reserveClause | organizationClause | paddingCharacterClause | recordDelimiterClause | accessModeClause | recordKeyClause | alternateRecordKeyClause | fileStatusClause | passwordClause | relativeKeyClause | controlAreaClause
   ;

controlAreaClause
   : CONTROL_AREA IS? qualifiedDataName
   ;

assignClause
   : ASSIGN TO? (DISK | DISPLAY | KEYBOARD | PORT | PRINTER | READER | REMOTE | TAPE | VIRTUAL | (DYNAMIC | EXTERNAL)? assignmentName | literal)
   ;

reserveClause
   : RESERVE (NO | integerLiteral) ALTERNATE? (AREA | AREAS)?
   ;

organizationClause
   : (ORGANIZATION IS?)? (LINE | RECORD BINARY | RECORD | BINARY)? (SEQUENTIAL | RELATIVE | INDEXED | TRANSACTION)
   ;

paddingCharacterClause
   : PADDING CHARACTER? IS? (qualifiedDataName | literal)
   ;

recordDelimiterClause
   : RECORD DELIMITER IS? (STANDARD_1 | IMPLICIT | assignmentName)
   ;

accessModeClause
   : ACCESS MODE? IS? (SEQUENTIAL | RANDOM | DYNAMIC | EXCLUSIVE)
   ;

recordKeyClause
   : RECORD KEY? IS? qualifiedDataName passwordClause? (WITH? DUPLICATES)?
   ;

alternateRecordKeyClause
   : ALTERNATE RECORD KEY? IS? qualifiedDataName passwordClause? (WITH? DUPLICATES)?
   ;

passwordClause
   : PASSWORD IS? dataName
   ;

fileStatusClause
   : FILE? STATUS IS? qualifiedDataName qualifiedDataName?
   ;

relativeKeyClause
   : RELATIVE KEY? IS? qualifiedDataName
   ;

// - io control paragraph ----------------------------------

ioControlParagraph
   : I_O_CONTROL DOT_FS (fileName DOT_FS)? (ioControlClause* DOT_FS)?
   ;

ioControlClause
   : rerunClause | sameClause | multipleFileClause | commitmentControlClause
   ;

rerunClause
   : RERUN (ON (assignmentName | fileName))? EVERY (rerunEveryRecords | rerunEveryOf | rerunEveryClock)
   ;

rerunEveryRecords
   : integerLiteral RECORDS
   ;

rerunEveryOf
   : END? OF? (REEL | UNIT) OF fileName
   ;

rerunEveryClock
   : integerLiteral CLOCK_UNITS?
   ;

sameClause
   : SAME (RECORD | SORT | SORT_MERGE)? AREA? FOR? fileName+
   ;

multipleFileClause
   : MULTIPLE FILE TAPE? CONTAINS? multipleFilePosition+
   ;

multipleFilePosition
   : fileName (POSITION integerLiteral)?
   ;

commitmentControlClause
   : COMMITMENT CONTROL FOR? fileName
   ;

// --- data division --------------------------------------------------------------------

dataDivision
   : DATA DIVISION DOT_FS dataDivisionSection*
   ;

dataDivisionSection
   : fileSection | dataBaseSection | workingStorageSection | linkageSection | communicationSection | localStorageSection | screenSection | reportSection | programLibrarySection
   ;

// -- file section ----------------------------------

fileSection
   : FILE SECTION DOT_FS fileDescriptionEntry*
   ;

fileDescriptionEntry
   : (FD | SD) fileName (DOT_FS? fileDescriptionEntryClause)* DOT_FS dataDescriptionEntry*
   ;

fileDescriptionEntryClause
   : externalClause | globalClause | blockContainsClause | recordContainsClause | labelRecordsClause | valueOfClause | dataRecordsClause | linageClause | codeSetClause | reportClause | recordingModeClause
   ;

externalClause
   : IS? EXTERNAL
   ;

globalClause
   : IS? GLOBAL
   ;

blockContainsClause
   : BLOCK CONTAINS? integerLiteral blockContainsTo? (RECORDS | CHARACTERS)?
   ;

blockContainsTo
   : TO integerLiteral
   ;

recordContainsClause
   : RECORD (recordContainsClauseFormat1 | recordContainsClauseFormat2 | recordContainsClauseFormat3)
   ;

recordContainsClauseFormat1
   : CONTAINS? integerLiteral CHARACTERS?
   ;

recordContainsClauseFormat2
   : IS? VARYING IN? SIZE? (FROM? integerLiteral recordContainsTo? CHARACTERS?)? (DEPENDING ON? qualifiedDataName)?
   ;

recordContainsClauseFormat3
   : CONTAINS? integerLiteral recordContainsTo CHARACTERS?
   ;

recordContainsTo
   : TO integerLiteral
   ;

labelRecordsClause
   : LABEL (RECORD IS? | RECORDS ARE?) (OMITTED | STANDARD | dataName+)
   ;

valueOfClause
   : VALUE OF valuePair+
   ;

valuePair
   : systemName IS? (qualifiedDataName | literal)
   ;

dataRecordsClause
   : DATA (RECORD IS? | RECORDS ARE?) dataName+
   ;

linageClause
   : LINAGE IS? (dataName | integerLiteral) LINES? linageAt*
   ;

linageAt
   : linageFootingAt | linageLinesAtTop | linageLinesAtBottom
   ;

linageFootingAt
   : WITH? FOOTING AT? (dataName | integerLiteral)
   ;

linageLinesAtTop
   : LINES? AT? TOP (dataName | integerLiteral)
   ;

linageLinesAtBottom
   : LINES? AT? BOTTOM (dataName | integerLiteral)
   ;

recordingModeClause
   : RECORDING MODE? IS? modeStatement
   ;

modeStatement
   : cobolWord
   ;

codeSetClause
   : CODE_SET IS? alphabetName
   ;

reportClause
   : (REPORT IS? | REPORTS ARE?) reportName+
   ;

// -- data base section ----------------------------------

dataBaseSection
   : DATA_BASE SECTION DOT_FS dataBaseSectionEntry*
   ;

dataBaseSectionEntry
   : integerLiteral literal INVOKE literal
   ;

// -- working storage section ----------------------------------

workingStorageSection
   : WORKING_STORAGE SECTION DOT_FS dataDescriptionEntry*
   ;

// -- linkage section ----------------------------------

linkageSection
   : LINKAGE SECTION DOT_FS dataDescriptionEntry*
   ;

// -- communication section ----------------------------------

communicationSection
   : COMMUNICATION SECTION DOT_FS (communicationDescriptionEntry | dataDescriptionEntry)*
   ;

communicationDescriptionEntry
   : communicationDescriptionEntryFormat1 | communicationDescriptionEntryFormat2 | communicationDescriptionEntryFormat3
   ;

communicationDescriptionEntryFormat1
   : CD cdName FOR? INITIAL? INPUT ((symbolicQueueClause | symbolicSubQueueClause | messageDateClause | messageTimeClause | symbolicSourceClause | textLengthClause | endKeyClause | statusKeyClause | messageCountClause) | dataDescName)* DOT_FS
   ;

communicationDescriptionEntryFormat2
   : CD cdName FOR? OUTPUT (destinationCountClause | textLengthClause | statusKeyClause | destinationTableClause | errorKeyClause | symbolicDestinationClause)* DOT_FS
   ;

communicationDescriptionEntryFormat3
   : CD cdName FOR? INITIAL I_O ((messageDateClause | messageTimeClause | symbolicTerminalClause | textLengthClause | endKeyClause | statusKeyClause) | dataDescName)* DOT_FS
   ;

destinationCountClause
   : DESTINATION COUNT IS? dataDescName
   ;

destinationTableClause
   : DESTINATION TABLE OCCURS integerLiteral TIMES (INDEXED BY indexName+)?
   ;

endKeyClause
   : END KEY IS? dataDescName
   ;

errorKeyClause
   : ERROR KEY IS? dataDescName
   ;

messageCountClause
   : MESSAGE? COUNT IS? dataDescName
   ;

messageDateClause
   : MESSAGE DATE IS? dataDescName
   ;

messageTimeClause
   : MESSAGE TIME IS? dataDescName
   ;

statusKeyClause
   : STATUS KEY IS? dataDescName
   ;

symbolicDestinationClause
   : SYMBOLIC? DESTINATION IS? dataDescName
   ;

symbolicQueueClause
   : SYMBOLIC? QUEUE IS? dataDescName
   ;

symbolicSourceClause
   : SYMBOLIC? SOURCE IS? dataDescName
   ;

symbolicTerminalClause
   : SYMBOLIC? TERMINAL IS? dataDescName
   ;

symbolicSubQueueClause
   : SYMBOLIC? (SUB_QUEUE_1 | SUB_QUEUE_2 | SUB_QUEUE_3) IS? dataDescName
   ;

textLengthClause
   : TEXT LENGTH IS? dataDescName
   ;

// -- local storage section ----------------------------------

localStorageSection
   : LOCAL_STORAGE SECTION DOT_FS (LD localName DOT_FS)? dataDescriptionEntry*
   ;

// -- screen section ----------------------------------

screenSection
   : SCREEN SECTION DOT_FS screenDescriptionEntry*
   ;

screenDescriptionEntry
   : INTEGERLITERAL (FILLER | screenName)? (screenDescriptionBlankClause | screenDescriptionBellClause | screenDescriptionBlinkClause | screenDescriptionEraseClause | screenDescriptionLightClause | screenDescriptionGridClause | screenDescriptionReverseVideoClause | screenDescriptionUnderlineClause | screenDescriptionSizeClause | screenDescriptionLineClause | screenDescriptionColumnClause | screenDescriptionForegroundColorClause | screenDescriptionBackgroundColorClause | screenDescriptionControlClause | screenDescriptionValueClause | screenDescriptionPictureClause | (screenDescriptionFromClause | screenDescriptionUsingClause) | screenDescriptionUsageClause | screenDescriptionBlankWhenZeroClause | screenDescriptionJustifiedClause | screenDescriptionSignClause | screenDescriptionAutoClause | screenDescriptionSecureClause | screenDescriptionRequiredClause | screenDescriptionPromptClause | screenDescriptionFullClause | screenDescriptionZeroFillClause)* DOT_FS
   ;

screenDescriptionBlankClause
   : BLANK (SCREEN | LINE)
   ;

screenDescriptionBellClause
   : BELL | BEEP
   ;

screenDescriptionBlinkClause
   : BLINK
   ;

screenDescriptionEraseClause
   : ERASE (EOL | EOS)
   ;

screenDescriptionLightClause
   : HIGHLIGHT | LOWLIGHT
   ;

screenDescriptionGridClause
   : GRID | LEFTLINE | OVERLINE
   ;

screenDescriptionReverseVideoClause
   : REVERSE_VIDEO
   ;

screenDescriptionUnderlineClause
   : UNDERLINE
   ;

screenDescriptionSizeClause
   : SIZE IS? (identifier | integerLiteral)
   ;

screenDescriptionLineClause
   : LINE (NUMBER? IS? (PLUS | PLUSCHAR | MINUSCHAR))? (identifier | integerLiteral)
   ;

screenDescriptionColumnClause
   : (COLUMN | COL) (NUMBER? IS? (PLUS | PLUSCHAR | MINUSCHAR))? (identifier | integerLiteral)
   ;

screenDescriptionForegroundColorClause
   : (FOREGROUND_COLOR | FOREGROUND_COLOUR) IS? (identifier | integerLiteral)
   ;

screenDescriptionBackgroundColorClause
   : (BACKGROUND_COLOR | BACKGROUND_COLOUR) IS? (identifier | integerLiteral)
   ;

screenDescriptionControlClause
   : CONTROL IS? identifier
   ;

screenDescriptionValueClause
   : (VALUE IS?) literal
   ;

screenDescriptionPictureClause
   : (PICTURE | PIC) IS? pictureString
   ;

screenDescriptionFromClause
   : FROM (identifier | literal) screenDescriptionToClause?
   ;

screenDescriptionToClause
   : TO identifier
   ;

screenDescriptionUsingClause
   : USING identifier
   ;

screenDescriptionUsageClause
   : (USAGE IS?) (DISPLAY | DISPLAY_1)
   ;

screenDescriptionBlankWhenZeroClause
   : BLANK WHEN? ZERO
   ;

screenDescriptionJustifiedClause
   : (JUSTIFIED | JUST) RIGHT?
   ;

screenDescriptionSignClause
   : (SIGN IS?)? (LEADING | TRAILING) (SEPARATE CHARACTER?)?
   ;

screenDescriptionAutoClause
   : AUTO | AUTO_SKIP
   ;

screenDescriptionSecureClause
   : SECURE | NO_ECHO
   ;

screenDescriptionRequiredClause
   : REQUIRED | EMPTY_CHECK
   ;

screenDescriptionPromptClause
   : PROMPT CHARACTER? IS? (identifier | literal) screenDescriptionPromptOccursClause?
   ;

screenDescriptionPromptOccursClause
   : OCCURS integerLiteral TIMES?
   ;

screenDescriptionFullClause
   : FULL | LENGTH_CHECK
   ;

screenDescriptionZeroFillClause
   : ZERO_FILL
   ;

// -- report section ----------------------------------

reportSection
   : REPORT SECTION DOT_FS reportDescription*
   ;

reportDescription
   : reportDescriptionEntry reportGroupDescriptionEntry+
   ;

reportDescriptionEntry
   : RD reportName reportDescriptionGlobalClause? (reportDescriptionPageLimitClause reportDescriptionHeadingClause? reportDescriptionFirstDetailClause? reportDescriptionLastDetailClause? reportDescriptionFootingClause?)? DOT_FS
   ;

reportDescriptionGlobalClause
   : IS? GLOBAL
   ;

reportDescriptionPageLimitClause
   : PAGE (LIMIT IS? | LIMITS ARE?)? integerLiteral (LINE | LINES)?
   ;

reportDescriptionHeadingClause
   : HEADING integerLiteral
   ;

reportDescriptionFirstDetailClause
   : FIRST DETAIL integerLiteral
   ;

reportDescriptionLastDetailClause
   : LAST DETAIL integerLiteral
   ;

reportDescriptionFootingClause
   : FOOTING integerLiteral
   ;

reportGroupDescriptionEntry
   : reportGroupDescriptionEntryFormat1 | reportGroupDescriptionEntryFormat2 | reportGroupDescriptionEntryFormat3
   ;

reportGroupDescriptionEntryFormat1
   : integerLiteral dataName reportGroupLineNumberClause? reportGroupNextGroupClause? reportGroupTypeClause reportGroupUsageClause? DOT_FS
   ;

reportGroupDescriptionEntryFormat2
   : integerLiteral dataName? reportGroupLineNumberClause? reportGroupUsageClause DOT_FS
   ;

reportGroupDescriptionEntryFormat3
   : integerLiteral dataName? (reportGroupPictureClause | reportGroupUsageClause | reportGroupSignClause | reportGroupJustifiedClause | reportGroupBlankWhenZeroClause | reportGroupLineNumberClause | reportGroupColumnNumberClause | (reportGroupSourceClause | reportGroupValueClause | reportGroupSumClause | reportGroupResetClause) | reportGroupIndicateClause)* DOT_FS
   ;

reportGroupBlankWhenZeroClause
   : BLANK WHEN? ZERO
   ;

reportGroupColumnNumberClause
   : COLUMN NUMBER? IS? integerLiteral
   ;

reportGroupIndicateClause
   : GROUP INDICATE?
   ;

reportGroupJustifiedClause
   : (JUSTIFIED | JUST) RIGHT?
   ;

reportGroupLineNumberClause
   : LINE? NUMBER? IS? (reportGroupLineNumberNextPage | reportGroupLineNumberPlus)
   ;

reportGroupLineNumberNextPage
   : integerLiteral (ON? NEXT PAGE)?
   ;

reportGroupLineNumberPlus
   : PLUS integerLiteral
   ;

reportGroupNextGroupClause
   : NEXT GROUP IS? (integerLiteral | reportGroupNextGroupNextPage | reportGroupNextGroupPlus)
   ;

reportGroupNextGroupPlus
   : PLUS integerLiteral
   ;

reportGroupNextGroupNextPage
   : NEXT PAGE
   ;

reportGroupPictureClause
   : (PICTURE | PIC) IS? pictureString
   ;

reportGroupResetClause
   : RESET ON? (FINAL | dataName)
   ;

reportGroupSignClause
   : SIGN IS? (LEADING | TRAILING) SEPARATE CHARACTER?
   ;

reportGroupSourceClause
   : SOURCE IS? identifier
   ;

reportGroupSumClause
   : SUM identifier (COMMACHAR? identifier)* (UPON dataName (COMMACHAR? dataName)*)?
   ;

reportGroupTypeClause
   : TYPE IS? (reportGroupTypeReportHeading | reportGroupTypePageHeading | reportGroupTypeControlHeading | reportGroupTypeDetail | reportGroupTypeControlFooting | reportGroupTypePageFooting | reportGroupTypeReportFooting)
   ;

reportGroupTypeReportHeading
   : REPORT HEADING | RH
   ;

reportGroupTypePageHeading
   : PAGE HEADING | PH
   ;

reportGroupTypeControlHeading
   : (CONTROL HEADING | CH) (FINAL | dataName)
   ;

reportGroupTypeDetail
   : DETAIL | DE
   ;

reportGroupTypeControlFooting
   : (CONTROL FOOTING | CF) (FINAL | dataName)
   ;

reportGroupUsageClause
   : (USAGE IS?)? (DISPLAY | DISPLAY_1)
   ;

reportGroupTypePageFooting
   : PAGE FOOTING | PF
   ;

reportGroupTypeReportFooting
   : REPORT FOOTING | RF
   ;

reportGroupValueClause
   : VALUE IS? literal
   ;

// -- program library section ----------------------------------

programLibrarySection
   : PROGRAM_LIBRARY SECTION DOT_FS libraryDescriptionEntry*
   ;

libraryDescriptionEntry
   : libraryDescriptionEntryFormat1 | libraryDescriptionEntryFormat2
   ;

libraryDescriptionEntryFormat1
   : LD libraryName EXPORT libraryAttributeClauseFormat1? libraryEntryProcedureClauseFormat1?
   ;

libraryDescriptionEntryFormat2
   : LB libraryName IMPORT libraryIsGlobalClause? libraryIsCommonClause? (libraryAttributeClauseFormat2 | libraryEntryProcedureClauseFormat2)*
   ;

libraryAttributeClauseFormat1
   : ATTRIBUTE (SHARING IS? (DONTCARE | PRIVATE | SHAREDBYRUNUNIT | SHAREDBYALL))?
   ;

libraryAttributeClauseFormat2
   : ATTRIBUTE libraryAttributeFunction? (LIBACCESS IS? (BYFUNCTION | BYTITLE))? libraryAttributeParameter? libraryAttributeTitle?
   ;

libraryAttributeFunction
   : FUNCTIONNAME IS literal
   ;

libraryAttributeParameter
   : LIBPARAMETER IS? literal
   ;

libraryAttributeTitle
   : TITLE IS? literal
   ;

libraryEntryProcedureClauseFormat1
   : ENTRY_PROCEDURE programName libraryEntryProcedureForClause?
   ;

libraryEntryProcedureClauseFormat2
   : ENTRY_PROCEDURE programName libraryEntryProcedureForClause? libraryEntryProcedureWithClause? libraryEntryProcedureUsingClause? libraryEntryProcedureGivingClause?
   ;

libraryEntryProcedureForClause
   : FOR literal
   ;

libraryEntryProcedureGivingClause
   : GIVING dataName
   ;

libraryEntryProcedureUsingClause
   : USING libraryEntryProcedureUsingName+
   ;

libraryEntryProcedureUsingName
   : dataName | fileName
   ;

libraryEntryProcedureWithClause
   : WITH libraryEntryProcedureWithName+
   ;

libraryEntryProcedureWithName
   : localName | fileName
   ;

libraryIsCommonClause
   : IS? COMMON
   ;

libraryIsGlobalClause
   : IS? GLOBAL
   ;

// data description entry ----------------------------------

dataDescriptionEntry
   : dataDescriptionEntryFormat1 | dataDescriptionEntryFormat2 | dataDescriptionEntryFormat3 | dataDescriptionEntryExecSql
   ;

dataDescriptionEntryFormat1
   : (INTEGERLITERAL | LEVEL_NUMBER_77) (FILLER | dataName)? (dataRedefinesClause | dataIntegerStringClause | dataExternalClause | dataGlobalClause | dataTypeDefClause | dataThreadLocalClause | dataPictureClause | dataCommonOwnLocalClause | dataTypeClause | dataUsingClause | dataUsageClause | dataValueClause | dataReceivedByClause | dataOccursClause | dataSignClause | dataSynchronizedClause | dataJustifiedClause | dataBlankWhenZeroClause | dataWithLowerBoundsClause | dataAlignedClause | dataRecordAreaClause | dataLikeClause | dataIndicatorClause | dataFormatClause)* DOT_FS
   ;

dataDescriptionEntryFormat2
   : LEVEL_NUMBER_66 dataName dataRenamesClause DOT_FS
   ;

dataDescriptionEntryFormat3
   : LEVEL_NUMBER_88 conditionName dataValueClause DOT_FS
   ;

dataDescriptionEntryExecSql
   : (EXECSQLLINE | EXECSQLEND)+ DOT_FS?
   ;

dataAlignedClause
   : ALIGNED
   ;

dataBlankWhenZeroClause
   : BLANK WHEN? (ZERO | ZEROS | ZEROES)
   ;

dataCommonOwnLocalClause
   : COMMON | OWN | LOCAL
   ;

dataExternalClause
   : IS? EXTERNAL (BY literal)?
   ;

dataGlobalClause
   : IS? GLOBAL
   ;

dataIntegerStringClause
   : INTEGER | STRING
   ;

dataJustifiedClause
   : (JUSTIFIED | JUST) RIGHT?
   ;

dataOccursClause
   : OCCURS (identifier | integerLiteral) dataOccursTo? TIMES? dataOccursDepending? (dataOccursSort | dataOccursIndexed)*
   ;

dataOccursTo
   : TO integerLiteral
   ;

dataOccursDepending
   : DEPENDING ON? qualifiedDataName
   ;

dataOccursSort
   : (ASCENDING | DESCENDING) KEY? IS? qualifiedDataName+
   ;

dataOccursIndexed
   : INDEXED BY? LOCAL? indexName+
   ;

dataPictureClause
   : (PICTURE | PIC) IS? pictureString
   ;

pictureString
   : (pictureChars+ pictureCardinality?)+
   ;

pictureChars
   : DOLLARCHAR | IDENTIFIER | NUMERICLITERAL | SLASHCHAR | COMMACHAR | DOT | COLONCHAR | ASTERISKCHAR | DOUBLEASTERISKCHAR | LPARENCHAR | RPARENCHAR | PLUSCHAR | MINUSCHAR | LESSTHANCHAR | MORETHANCHAR | integerLiteral
   ;

pictureCardinality
   : LPARENCHAR integerLiteral RPARENCHAR
   ;

dataReceivedByClause
   : RECEIVED? BY? (CONTENT | REFERENCE | REF)
   ;

dataRecordAreaClause
   : RECORD AREA
   ;

dataLikeClause
   : LIKE qualifiedDataName (LPARENCHAR integerLiteral RPARENCHAR)?
   ;

dataIndicatorClause
   : (INDICATOR | INDICATORS | INDIC) integerLiteral
   ;

dataFormatClause
   : FORMAT (DATE | TIME | TIMESTAMP) (NONNUMERICLITERAL | IDENTIFIER)? (SIZE IS? integerLiteral)? (LOCALE IS? (IDENTIFIER | NONNUMERICLITERAL))?
   ;

dataRedefinesClause
   : REDEFINES dataName
   ;

dataRenamesClause
   : RENAMES qualifiedDataName ((THROUGH | THRU) qualifiedDataName)?
   ;

dataSignClause
   : (SIGN IS?)? (LEADING | TRAILING) (SEPARATE CHARACTER?)?
   ;

dataSynchronizedClause
   : (SYNCHRONIZED | SYNC) (LEFT | RIGHT)?
   ;

dataThreadLocalClause
   : IS? THREAD_LOCAL
   ;

dataTypeClause
   : TYPE IS? (SHORT_DATE | LONG_DATE | NUMERIC_DATE | NUMERIC_TIME | LONG_TIME | (CLOB | BLOB | DBCLOB) LPARENCHAR integerLiteral RPARENCHAR)
   ;

dataTypeDefClause
   : IS? TYPEDEF
   ;

dataUsageClause
   : (USAGE IS?)? (BINARY (TRUNCATED | EXTENDED)? | BIT | COMP | COMP_1 | COMP_2 | COMP_3 | COMP_4 | COMP_5 | COMPUTATIONAL | COMPUTATIONAL_1 | COMPUTATIONAL_2 | COMPUTATIONAL_3 | COMPUTATIONAL_4 | COMPUTATIONAL_5 | CONTROL_POINT | DATE | DISPLAY | DISPLAY_1 | DOUBLE | EVENT | FUNCTION_POINTER | INDEX | KANJI | LOCK | NATIONAL | PACKED_DECIMAL | POINTER | PROCEDURE_POINTER | REAL | SQL | TASK)
   ;

dataUsingClause
   : USING (LANGUAGE | CONVENTION) OF? (cobolWord | dataName)
   ;

dataValueClause
   : ((VALUE | VALUES) (IS | ARE)?)? dataValueInterval (COMMACHAR? dataValueInterval)*
   ;

dataValueInterval
   : dataValueIntervalFrom dataValueIntervalTo?
   ;

dataValueIntervalFrom
   : literal | cobolWord
   ;

dataValueIntervalTo
   : (THROUGH | THRU) literal
   ;

dataWithLowerBoundsClause
   : WITH? LOWER BOUNDS
   ;

// --- procedure division --------------------------------------------------------------------

procedureDivision
   : PROCEDURE DIVISION procedureDivisionUsingClause? procedureDivisionGivingClause? DOT_FS procedureDeclaratives? procedureDivisionBody
   ;

procedureDivisionUsingClause
   : (USING | CHAINING) procedureDivisionUsingParameter+
   ;

procedureDivisionGivingClause
   : (GIVING | RETURNING) dataName
   ;

procedureDivisionUsingParameter
   : procedureDivisionByReferencePhrase | procedureDivisionByValuePhrase
   ;

procedureDivisionByReferencePhrase
   : (BY? REFERENCE)? procedureDivisionByReference+
   ;

procedureDivisionByReference
   : (OPTIONAL? (identifier | fileName)) | ANY
   ;

procedureDivisionByValuePhrase
   : BY? VALUE procedureDivisionByValue+
   ;

procedureDivisionByValue
   : identifier | literal | ANY
   ;

procedureDeclaratives
   : DECLARATIVES DOT_FS procedureDeclarative+ END DECLARATIVES DOT_FS
   ;

procedureDeclarative
   : procedureSectionHeader DOT_FS useStatement DOT_FS paragraphs
   ;

procedureSectionHeader
   : sectionName SECTION integerLiteral?
   ;

procedureDivisionBody
   : paragraphs procedureSection*
   ;

// -- procedure section ----------------------------------

procedureSection
   : procedureSectionHeader DOT_FS paragraphs
   ;

paragraphs
   : sentence* statement* paragraph*
   ;

paragraph
   : paragraphName DOT_FS? (alteredGoTo | sentence* statement*)
   ;

sentence
   : statement* DOT_FS
   ;

statement
   : acceptStatement | acquireStatement | addStatement | alterStatement | callStatement | cancelStatement | closeStatement | commitStatement | computeStatement | continueStatement | deleteStatement | disableStatement | displayStatement | divideStatement | dropStatement | enableStatement | entryStatement | evaluateStatement | exhibitStatement | execCicsStatement | execSqlStatement | execSqlImsStatement | exitStatement | generateStatement | gobackStatement | goToStatement | ifStatement | initializeStatement | initiateStatement | inspectStatement | mergeStatement | moveStatement | multiplyStatement | nextSentenceStatement | openStatement | performStatement | purgeStatement | readStatement | receiveStatement | releaseStatement | returnStatement | rewriteStatement | rollbackStatement | searchStatement | sendStatement | setStatement | sortStatement | startStatement | stopStatement | stringStatement | subtractStatement | terminateStatement | unstringStatement | writeStatement | xmlGenerateStatement | xmlParseStatement
   ;

// accept statement

acceptStatement
   : ACCEPT identifier (acceptFromDateStatement | acceptFromEscapeKeyStatement | acceptFromMnemonicStatement | acceptMessageCountStatement | acceptFromCrtStatement)? onExceptionClause? notOnExceptionClause? END_ACCEPT?
   ;

// IBM ILE: Format 7 — Extended ACCEPT from CRT
acceptFromCrtStatement
   : FROM CRT (MODE IS? BLOCK)? acceptAtPhrase? acceptWithPhrase*
   | (MODE IS? BLOCK) acceptAtPhrase? acceptWithPhrase*
   | acceptAtPhrase acceptWithPhrase*
   | acceptWithPhrase+
   ;

acceptFromDateStatement
   : FROM (DATE YYYYMMDD? | DAY YYYYDDD? | DAY_OF_WEEK | TIME | TIMER | TODAYS_DATE MMDDYYYY? | TODAYS_NAME | YEAR | YYYYMMDD | YYYYDDD)
   ;

acceptFromMnemonicStatement
   : FROM mnemonicName (FOR (identifier | literal) (IN LIBRARY? (identifier | literal))?)?
   ;

acceptFromEscapeKeyStatement
   : FROM ESCAPE KEY
   ;

acceptMessageCountStatement
   : MESSAGE? COUNT
   ;

// IBM ILE: AT LINE number COLUMN number
acceptAtPhrase
   : AT (LINE (identifier | integerLiteral) (COLUMN (identifier | integerLiteral))? | COLUMN (identifier | integerLiteral) | (identifier | literal))
   ;

// IBM ILE: WITH phrase for extended ACCEPT/DISPLAY
acceptWithPhrase
   : WITH? (AUTO | AUTO_SKIP | BELL | BEEP | BLINK | FULL | LENGTH_CHECK | HIGHLIGHT | REQUIRED | EMPTY_CHECK | REVERSE_VIDEO | SECURE | NO_ECHO | UNDERLINE | RIGHT_JUSTIFY | LEFT_JUSTIFY | SPACE_FILL | TRAILING_SIGN | UPDATE | ZERO_FILL | SIZE IS? (identifier | integerLiteral) | PROMPT CHARACTER? IS? (identifier | literal) | FOREGROUND_COLOR IS? (identifier | integerLiteral) | FOREGROUND_COLOUR IS? (identifier | integerLiteral) | BACKGROUND_COLOR IS? (identifier | integerLiteral) | BACKGROUND_COLOUR IS? (identifier | integerLiteral))
   ;

// add statement

addStatement
   : ADD (addToStatement | addToGivingStatement | addCorrespondingStatement) onSizeErrorPhrase? notOnSizeErrorPhrase? END_ADD?
   ;

addToStatement
   : addFrom+ TO addTo+
   ;

addToGivingStatement
   : addFrom+ (TO addToGiving+)? GIVING addGiving+
   ;

addCorrespondingStatement
   : (CORRESPONDING | CORR) identifier TO addTo
   ;

addFrom
   : identifier | literal
   ;

addTo
   : identifier ROUNDED?
   ;

addToGiving
   : identifier | literal
   ;

addGiving
   : identifier ROUNDED?
   ;

// altered go to statement

alteredGoTo
   : GO TO? DOT_FS
   ;

// alter statement

alterStatement
   : ALTER alterProceedTo+
   ;

alterProceedTo
   : procedureName TO (PROCEED TO)? procedureName
   ;

// call statement

callStatement
   : CALL PROCEDURE? (identifier | literal) callLinkagePhrase? callUsingPhrase? callGivingPhrase? onOverflowPhrase? onExceptionClause? notOnExceptionClause? END_CALL?
   ;

// IBM ILE: LINKAGE TYPE IS {PGM|PROGRAM|PRC|PROCEDURE} [IN LIBRARY {identifier|literal}]
callLinkagePhrase
   : LINKAGE TYPE? IS? (PROCEDURE | PROGRAM | cobolWord) (IN LIBRARY? (identifier | literal))?
   ;

callUsingPhrase
   : USING callUsingParameter+
   ;

callUsingParameter
   : callByReferencePhrase | callByValuePhrase | callByContentPhrase
   ;

callByReferencePhrase
   : (BY? REFERENCE)? callByReference+
   ;

callByReference
   : ((ADDRESS OF | INTEGER | STRING)? identifier | literal | fileName) | OMITTED
   ;

callByValuePhrase
   : BY? VALUE callByValue+
   ;

callByValue
   : (ADDRESS OF | LENGTH OF?)? (identifier | literal)
   ;

callByContentPhrase
   : BY? CONTENT callByContent+
   ;

callByContent
   : (ADDRESS OF | LENGTH OF?)? identifier | literal | OMITTED
   ;

callGivingPhrase
   : (GIVING | RETURNING) identifier
   ;

// cancel statement

cancelStatement
   : CANCEL cancelCall+
   ;

cancelCall
   : libraryName (BYTITLE | BYFUNCTION) | identifier | literal
   ;

// close statement

closeStatement
   : CLOSE closeFile+
   ;

closeFile
   : fileName (closeReelUnitStatement | closeRelativeStatement | closePortFileIOStatement)?
   ;

closeReelUnitStatement
   : (REEL | UNIT) (FOR? REMOVAL)? (WITH? (NO REWIND | LOCK))?
   ;

closeRelativeStatement
   : WITH? (NO REWIND | LOCK)
   ;

closePortFileIOStatement
   : (WITH? NO WAIT | WITH WAIT) (USING closePortFileIOUsing+)?
   ;

closePortFileIOUsing
   : closePortFileIOUsingCloseDisposition | closePortFileIOUsingAssociatedData | closePortFileIOUsingAssociatedDataLength
   ;

closePortFileIOUsingCloseDisposition
   : CLOSE_DISPOSITION OF? (ABORT | ORDERLY)
   ;

closePortFileIOUsingAssociatedData
   : ASSOCIATED_DATA (identifier | integerLiteral)
   ;

closePortFileIOUsingAssociatedDataLength
   : ASSOCIATED_DATA_LENGTH OF? (identifier | integerLiteral)
   ;

// commit statement

commitStatement
   : COMMIT
   ;

// compute statement

computeStatement
   : COMPUTE computeStore+ (EQUALCHAR | EQUAL) arithmeticExpression onSizeErrorPhrase? notOnSizeErrorPhrase? END_COMPUTE?
   ;

computeStore
   : identifier ROUNDED?
   ;

// continue statement

continueStatement
   : CONTINUE
   ;

// delete statement

deleteStatement
   : DELETE fileName RECORD? deleteFormatPhrase? deleteNullKeyMapPhrase? invalidKeyPhrase? notInvalidKeyPhrase? END_DELETE?
   ;

// IBM ILE: FORMAT IS {identifier|literal}
deleteFormatPhrase
   : FORMAT IS? (identifier | literal)
   ;

// IBM ILE: NULL-KEY-MAP IS identifier
deleteNullKeyMapPhrase
   : NULL_KEY_MAP IS? identifier
   ;

// disable statement

disableStatement
   : DISABLE (INPUT TERMINAL? | I_O TERMINAL | OUTPUT) cdName WITH? KEY (identifier | literal)
   ;

// display statement

displayStatement
   : DISPLAY displayOperand+ displayAt? displayUpon? (MODE IS? BLOCK)? displayWith* onExceptionClause? notOnExceptionClause? END_DISPLAY?
   ;

displayOperand
   : identifier | literal
   ;

displayAt
   : AT (LINE (identifier | integerLiteral) (COLUMN (identifier | integerLiteral))? | COLUMN (identifier | integerLiteral) | (identifier | literal))
   ;

displayUpon
   : UPON (CRT | CRT_UNDER | mnemonicName | environmentName) (FOR (identifier | literal) (IN LIBRARY? (identifier | literal))?)?
   ;

displayWith
   : WITH? (NO ADVANCING | BELL | BEEP | BLINK | HIGHLIGHT | REVERSE_VIDEO | UNDERLINE | FOREGROUND_COLOR IS? (identifier | integerLiteral) | FOREGROUND_COLOUR IS? (identifier | integerLiteral) | BACKGROUND_COLOR IS? (identifier | integerLiteral) | BACKGROUND_COLOUR IS? (identifier | integerLiteral))
   ;

// divide statement

divideStatement
   : DIVIDE (identifier | literal) (divideIntoStatement | divideIntoGivingStatement | divideByGivingStatement) divideRemainder? onSizeErrorPhrase? notOnSizeErrorPhrase? END_DIVIDE?
   ;

divideIntoStatement
   : INTO divideInto+
   ;

divideIntoGivingStatement
   : INTO (identifier | literal) divideGivingPhrase?
   ;

divideByGivingStatement
   : BY (identifier | literal) divideGivingPhrase?
   ;

divideGivingPhrase
   : GIVING divideGiving+
   ;

divideInto
   : identifier ROUNDED?
   ;

divideGiving
   : identifier ROUNDED?
   ;

divideRemainder
   : REMAINDER identifier
   ;

// enable statement

enableStatement
   : ENABLE (INPUT TERMINAL? | I_O TERMINAL | OUTPUT) cdName WITH? KEY (literal | identifier)
   ;

// entry statement

entryStatement
   : ENTRY literal (USING identifier+)?
   ;

// evaluate statement

evaluateStatement
   : EVALUATE evaluateSelect evaluateAlsoSelect* evaluateWhenPhrase* evaluateWhenOther? END_EVALUATE?
   ;

evaluateSelect
   : identifier | literal | arithmeticExpression | condition
   ;

evaluateAlsoSelect
   : ALSO evaluateSelect
   ;

evaluateWhenPhrase
   : evaluateWhen+ statement*
   ;

evaluateWhen
   : WHEN evaluateCondition evaluateAlsoCondition*
   ;

evaluateCondition
   : ANY | NOT? evaluateValue evaluateThrough? | condition | booleanLiteral
   ;

evaluateThrough
   : (THROUGH | THRU) evaluateValue
   ;

evaluateAlsoCondition
   : ALSO evaluateCondition
   ;

evaluateWhenOther
   : WHEN OTHER statement*
   ;

evaluateValue
   : identifier | literal | arithmeticExpression
   ;

// exec cics statement

execCicsStatement
   : EXECCICSLINE+
   ;

// exec sql statement

execSqlStatement
   : EXECSQLLINE* EXECSQLEND
   ;

// exec sql ims statement

execSqlImsStatement
   : EXECSQLIMSLINE+
   ;

// exhibit statement

exhibitStatement
   : EXHIBIT NAMED? CHANGED? exhibitOperand+
   ;

exhibitOperand
   : identifier | literal
   ;

// exit statement

exitStatement
   : EXIT PROGRAM?
   ;

// generate statement

generateStatement
   : GENERATE reportName
   ;

// goback statement

gobackStatement
   : GOBACK
   ;

// goto statement

goToStatement
   : GO TO? (goToStatementSimple | goToDependingOnStatement)
   ;

goToStatementSimple
   : procedureName
   ;

goToDependingOnStatement
   : MORE_LABELS | procedureName+ (DEPENDING ON? identifier)?
   ;

// if statement

ifStatement
   : IF condition ifThen ifElse? END_IF?
   ;

ifThen
   : THEN? (NEXT SENTENCE | statement*)
   ;

ifElse
   : ELSE (NEXT SENTENCE | statement*)
   ;

// initialize statement

initializeStatement
   : INITIALIZE identifier+ initializeReplacingPhrase?
   ;

initializeReplacingPhrase
   : REPLACING initializeReplacingBy+
   ;

initializeReplacingBy
   : (ALPHABETIC | ALPHANUMERIC | ALPHANUMERIC_EDITED | NATIONAL | NATIONAL_EDITED | NUMERIC | NUMERIC_EDITED | DBCS | EGCS) DATA? BY (identifier | literal)
   ;

// initiate statement

initiateStatement
   : INITIATE reportName+
   ;

// inspect statement

inspectStatement
   : INSPECT identifier (inspectTallyingPhrase | inspectReplacingPhrase | inspectTallyingReplacingPhrase | inspectConvertingPhrase)
   ;

inspectTallyingPhrase
   : TALLYING inspectFor+
   ;

inspectReplacingPhrase
   : REPLACING (inspectReplacingCharacters | inspectReplacingAllLeadings)+
   ;

inspectTallyingReplacingPhrase
   : TALLYING inspectFor+ inspectReplacingPhrase+
   ;

inspectConvertingPhrase
   : CONVERTING (identifier | literal) inspectTo inspectBeforeAfter*
   ;

inspectFor
   : identifier FOR (inspectCharacters | inspectAllLeadings)+
   ;

inspectCharacters
   : (CHARACTER | CHARACTERS) inspectBeforeAfter*
   ;

inspectReplacingCharacters
   : (CHARACTER | CHARACTERS) inspectBy inspectBeforeAfter*
   ;

inspectAllLeadings
   : (ALL | LEADING) inspectAllLeading+
   ;

inspectReplacingAllLeadings
   : (ALL | LEADING | FIRST) inspectReplacingAllLeading+
   ;

inspectAllLeading
   : (identifier | literal) inspectBeforeAfter*
   ;

inspectReplacingAllLeading
   : (identifier | literal) inspectBy inspectBeforeAfter*
   ;

inspectBy
   : BY (identifier | literal)
   ;

inspectTo
   : TO (identifier | literal)
   ;

inspectBeforeAfter
   : (BEFORE | AFTER) INITIAL? (identifier | literal)
   ;

// merge statement

mergeStatement
   : MERGE fileName mergeOnKeyClause+ mergeCollatingSequencePhrase? mergeUsing* mergeOutputProcedurePhrase? mergeGivingPhrase*
   ;

mergeOnKeyClause
   : ON? (ASCENDING | DESCENDING) KEY? qualifiedDataName+
   ;

mergeCollatingSequencePhrase
   : COLLATING? SEQUENCE IS? alphabetName+ mergeCollatingAlphanumeric? mergeCollatingNational?
   ;

mergeCollatingAlphanumeric
   : FOR? ALPHANUMERIC IS alphabetName
   ;

mergeCollatingNational
   : FOR? NATIONAL IS? alphabetName
   ;

mergeUsing
   : USING fileName+
   ;

mergeOutputProcedurePhrase
   : OUTPUT PROCEDURE IS? procedureName mergeOutputThrough?
   ;

mergeOutputThrough
   : (THROUGH | THRU) procedureName
   ;

mergeGivingPhrase
   : GIVING mergeGiving+
   ;

mergeGiving
   : fileName (LOCK | SAVE | NO REWIND | CRUNCH | RELEASE | WITH REMOVE CRUNCH)?
   ;

// move statement

moveStatement
   : MOVE ALL? (moveToStatement | moveCorrespondingToStatement)
   ;

moveToStatement
   : moveToSendingArea TO identifier+
   ;

moveToSendingArea
   : identifier | literal
   ;

moveCorrespondingToStatement
   : (CORRESPONDING | CORR) moveCorrespondingToSendingArea TO identifier+
   ;

moveCorrespondingToSendingArea
   : identifier
   ;

// multiply statement

multiplyStatement
   : MULTIPLY (identifier | literal) BY (multiplyRegular | multiplyGiving) onSizeErrorPhrase? notOnSizeErrorPhrase? END_MULTIPLY?
   ;

multiplyRegular
   : multiplyRegularOperand+
   ;

multiplyRegularOperand
   : identifier ROUNDED?
   ;

multiplyGiving
   : multiplyGivingOperand GIVING multiplyGivingResult+
   ;

multiplyGivingOperand
   : identifier | literal
   ;

multiplyGivingResult
   : identifier ROUNDED?
   ;

// next sentence

nextSentenceStatement
   : NEXT SENTENCE
   ;

// open statement

openStatement
   : OPEN (openInputStatement | openOutputStatement | openIOStatement | openExtendStatement)+
   ;

openInputStatement
   : INPUT openInput+
   ;

openInput
   : fileName (REVERSED | WITH? NO REWIND)?
   ;

openOutputStatement
   : OUTPUT openOutput+
   ;

openOutput
   : fileName (WITH? NO REWIND)?
   ;

openIOStatement
   : I_O fileName+
   ;

openExtendStatement
   : EXTEND fileName+
   ;

// perform statement

performStatement
   : PERFORM (performInlineStatement | performProcedureStatement)
   ;

performInlineStatement
   : performType? statement* END_PERFORM
   ;

performProcedureStatement
   : procedureName ((THROUGH | THRU) procedureName)? performType?
   ;

performType
   : performTimes | performUntil | performVarying
   ;

performTimes
   : (identifier | integerLiteral) TIMES
   ;

performUntil
   : performTestClause? UNTIL condition
   ;

performVarying
   : performTestClause performVaryingClause | performVaryingClause performTestClause?
   ;

performVaryingClause
   : VARYING performVaryingPhrase performAfter*
   ;

performVaryingPhrase
   : (identifier | literal) performFrom performBy performUntil
   ;

performAfter
   : AFTER performVaryingPhrase
   ;

performFrom
   : FROM? (identifier | literal | arithmeticExpression)
   ;

performBy
   : BY (identifier | literal | arithmeticExpression)
   ;

performTestClause
   : WITH? TEST (BEFORE | AFTER)
   ;

// purge statement

purgeStatement
   : PURGE cdName+
   ;

// read statement

readStatement
   : READ SUBFILE? fileName (FIRST | LAST | NEXT | PREVIOUS | PRIOR)? MODIFIED? RECORD? readInto? readWith? readFormatPhrase? readTerminalPhrase? readKey? readIndicatorsPhrase? readNullMapPhrase? readNullKeyMapPhrase? readNoDataPhrase? readNotNoDataPhrase? invalidKeyPhrase? notInvalidKeyPhrase? atEndPhrase? notAtEndPhrase? END_READ?
   ;

readFormatPhrase
   : FORMAT IS? (identifier | literal)
   ;

readInto
   : INTO identifier
   ;

readWith
   : WITH? ((KEPT | NO) LOCK | WAIT)
   ;

readKey
   : KEY IS? qualifiedDataName
   ;

readIndicatorsPhrase
   : (INDICATOR | INDICATORS | INDIC) (IS | ARE)? identifier
   ;

// IBM ILE: TERMINAL IS {identifier|literal}
readTerminalPhrase
   : TERMINAL IS? (identifier | literal)
   ;

// IBM ILE: NULL-MAP IS identifier
readNullMapPhrase
   : NULL_MAP IS? identifier
   ;

// IBM ILE: NULL-KEY-MAP IS identifier
readNullKeyMapPhrase
   : NULL_KEY_MAP IS? identifier
   ;

readNoDataPhrase
   : NO DATA statement+
   ;

readNotNoDataPhrase
   : NOT NO DATA statement+
   ;

// acquire statement

acquireStatement
   : ACQUIRE (identifier | literal) FOR fileName
   ;

// drop statement

dropStatement
   : DROP (identifier | literal) FROM fileName
   ;

// receive statement

receiveStatement
   : RECEIVE (receiveFromStatement | receiveIntoStatement) onExceptionClause? notOnExceptionClause? END_RECEIVE?
   ;

receiveFromStatement
   : dataName FROM receiveFrom (receiveBefore | receiveWith | receiveThread | receiveSize | receiveStatus)*
   ;

receiveFrom
   : THREAD dataName | LAST THREAD | ANY THREAD
   ;

receiveIntoStatement
   : cdName (MESSAGE | SEGMENT) INTO? identifier receiveNoData? receiveWithData?
   ;

receiveNoData
   : NO DATA statement*
   ;

receiveWithData
   : WITH DATA statement*
   ;

receiveBefore
   : BEFORE TIME? (numericLiteral | identifier)
   ;

receiveWith
   : WITH? NO WAIT
   ;

receiveThread
   : THREAD IN? dataName
   ;

receiveSize
   : SIZE IN? (numericLiteral | identifier)
   ;

receiveStatus
   : STATUS IN? (identifier)
   ;

// release statement

releaseStatement
   : RELEASE recordName (FROM qualifiedDataName)?
   ;

// rollback statement

rollbackStatement
   : ROLLBACK
   ;

// return statement

returnStatement
   : RETURN fileName RECORD? returnInto? atEndPhrase notAtEndPhrase? END_RETURN?
   ;

returnInto
   : INTO qualifiedDataName
   ;

// rewrite statement

rewriteStatement
   : REWRITE SUBFILE? recordName rewriteFrom? rewriteFormatPhrase? rewriteTerminalPhrase? rewriteIndicatorsPhrase? rewriteNullMapPhrase? rewriteNullKeyMapPhrase? invalidKeyPhrase? notInvalidKeyPhrase? END_REWRITE?
   ;

rewriteIndicatorsPhrase
   : (INDICATOR | INDICATORS | INDIC) (IS | ARE)? identifier
   ;

rewriteFormatPhrase
   : FORMAT IS? (identifier | literal)
   ;

rewriteFrom
   : FROM identifier
   ;

// IBM ILE: TERMINAL IS {identifier|literal}
rewriteTerminalPhrase
   : TERMINAL IS? (identifier | literal)
   ;

// IBM ILE: NULL-MAP IS identifier
rewriteNullMapPhrase
   : NULL_MAP IS? identifier
   ;

// IBM ILE: NULL-KEY-MAP IS identifier
rewriteNullKeyMapPhrase
   : NULL_KEY_MAP IS? identifier
   ;

// search statement

searchStatement
   : SEARCH ALL? qualifiedDataName searchVarying? atEndPhrase? searchWhen* END_SEARCH?
   ;

searchVarying
   : VARYING qualifiedDataName
   ;

searchWhen
   : WHEN condition (NEXT SENTENCE | statement*)
   ;

// send statement

sendStatement
   : SEND (sendStatementSync | sendStatementAsync) onExceptionClause? notOnExceptionClause?
   ;

sendStatementSync
   : (identifier | literal) sendFromPhrase? sendWithPhrase? sendReplacingPhrase? sendAdvancingPhrase?
   ;

sendStatementAsync
   : TO (TOP | BOTTOM) identifier
   ;

sendFromPhrase
   : FROM identifier
   ;

sendWithPhrase
   : WITH (EGI | EMI | ESI | identifier)
   ;

sendReplacingPhrase
   : REPLACING LINE?
   ;

sendAdvancingPhrase
   : (BEFORE | AFTER) ADVANCING? (sendAdvancingPage | sendAdvancingLines | sendAdvancingMnemonic)
   ;

sendAdvancingPage
   : PAGE
   ;

sendAdvancingLines
   : (identifier | literal) (LINE | LINES)?
   ;

sendAdvancingMnemonic
   : mnemonicName
   ;

// set statement

setStatement
   : SET (setLocaleStatement | setToStatement+ | setUpDownByStatement)
   ;

// IBM ILE: SET LOCALE {LC_ALL|LC_COLLATE|LC_CTYPE|LC_MESSAGES|LC_MONETARY|LC_NUMERIC|LC_TIME|LC_TOD} TO {mnemonic|identifier|literal}
setLocaleStatement
   : LOCALE cobolWord TO (identifier | literal)
   ;

setToStatement
   : setTo+ TO setToValue+
   ;

setUpDownByStatement
   : setTo+ (UP BY | DOWN BY) setByValue
   ;

setTo
   : identifier
   ;

setToValue
   : ON | OFF | ENTRY (identifier | literal) setEntryLinkagePhrase? | ENTRY setEntryLinkagePhrase | identifier | literal
   ;

// IBM ILE: SET id TO ENTRY literal LINKAGE TYPE IS {PROCEDURE|PROGRAM} [IN LIBRARY {id|literal}]
// Also supports: SET id TO ENTRY LINKAGE TYPE IS PROCEDURE literal
setEntryLinkagePhrase
   : LINKAGE TYPE? IS? (PROCEDURE | PROGRAM | cobolWord) (IN LIBRARY? (identifier | literal))?
   ;

setByValue
   : identifier | literal
   ;

// sort statement

sortStatement
   : SORT fileName sortOnKeyClause+ sortDuplicatesPhrase? sortCollatingSequencePhrase? sortInputProcedurePhrase? sortUsing* sortOutputProcedurePhrase? sortGivingPhrase*
   ;

sortOnKeyClause
   : ON? (ASCENDING | DESCENDING) KEY? qualifiedDataName+
   ;

sortDuplicatesPhrase
   : WITH? DUPLICATES IN? ORDER?
   ;

sortCollatingSequencePhrase
   : COLLATING? SEQUENCE IS? alphabetName+ sortCollatingAlphanumeric? sortCollatingNational?
   ;

sortCollatingAlphanumeric
   : FOR? ALPHANUMERIC IS alphabetName
   ;

sortCollatingNational
   : FOR? NATIONAL IS? alphabetName
   ;

sortInputProcedurePhrase
   : INPUT PROCEDURE IS? procedureName sortInputThrough?
   ;

sortInputThrough
   : (THROUGH | THRU) procedureName
   ;

sortUsing
   : USING fileName+
   ;

sortOutputProcedurePhrase
   : OUTPUT PROCEDURE IS? procedureName sortOutputThrough?
   ;

sortOutputThrough
   : (THROUGH | THRU) procedureName
   ;

sortGivingPhrase
   : GIVING sortGiving+
   ;

sortGiving
   : fileName (LOCK | SAVE | NO REWIND | CRUNCH | RELEASE | WITH REMOVE CRUNCH)?
   ;

// start statement

startStatement
   : START fileName startKey? startFormatPhrase? startNullKeyMapPhrase? invalidKeyPhrase? notInvalidKeyPhrase? END_START?
   ;

// IBM ILE: FORMAT IS {identifier|literal}
startFormatPhrase
   : FORMAT IS? (identifier | literal)
   ;

// IBM ILE: NULL-KEY-MAP IS identifier
startNullKeyMapPhrase
   : NULL_KEY_MAP IS? identifier
   ;

startKey
   : KEY IS? (EQUAL TO? | EQUALCHAR | GREATER THAN? | MORETHANCHAR | NOT LESS THAN? | NOT LESSTHANCHAR | GREATER THAN? OR EQUAL TO? | MORETHANOREQUAL) qualifiedDataName
   ;

// stop statement

stopStatement
   : STOP (RUN | literal | stopStatementGiving)
   ;

stopStatementGiving
   : RUN (GIVING | RETURNING) (identifier | integerLiteral)
   ;

// string statement

stringStatement
   : STRING stringSendingPhrase+ stringIntoPhrase stringWithPointerPhrase? onOverflowPhrase? notOnOverflowPhrase? END_STRING?
   ;

stringSendingPhrase
   : stringSending (COMMACHAR? stringSending)* (stringDelimitedByPhrase | stringForPhrase)
   ;

stringSending
   : identifier | literal
   ;

stringDelimitedByPhrase
   : DELIMITED BY? (SIZE | identifier | literal)
   ;

stringForPhrase
   : FOR (identifier | literal)
   ;

stringIntoPhrase
   : INTO identifier
   ;

stringWithPointerPhrase
   : WITH? POINTER qualifiedDataName
   ;

// subtract statement

subtractStatement
   : SUBTRACT (subtractFromStatement | subtractFromGivingStatement | subtractCorrespondingStatement) onSizeErrorPhrase? notOnSizeErrorPhrase? END_SUBTRACT?
   ;

subtractFromStatement
   : subtractSubtrahend+ FROM subtractMinuend+
   ;

subtractFromGivingStatement
   : subtractSubtrahend+ FROM subtractMinuendGiving GIVING subtractGiving+
   ;

subtractCorrespondingStatement
   : (CORRESPONDING | CORR) qualifiedDataName FROM subtractMinuendCorresponding
   ;

subtractSubtrahend
   : identifier | literal
   ;

subtractMinuend
   : identifier ROUNDED?
   ;

subtractMinuendGiving
   : identifier | literal
   ;

subtractGiving
   : identifier ROUNDED?
   ;

subtractMinuendCorresponding
   : qualifiedDataName ROUNDED?
   ;

// terminate statement

terminateStatement
   : TERMINATE reportName
   ;

// unstring statement

unstringStatement
   : UNSTRING unstringSendingPhrase unstringIntoPhrase unstringWithPointerPhrase? unstringTallyingPhrase? onOverflowPhrase? notOnOverflowPhrase? END_UNSTRING?
   ;

unstringSendingPhrase
   : identifier (unstringDelimitedByPhrase unstringOrAllPhrase*)?
   ;

unstringDelimitedByPhrase
   : DELIMITED BY? ALL? (identifier | literal)
   ;

unstringOrAllPhrase
   : OR ALL? (identifier | literal)
   ;

unstringIntoPhrase
   : INTO unstringInto+
   ;

unstringInto
   : identifier unstringDelimiterIn? unstringCountIn?
   ;

unstringDelimiterIn
   : DELIMITER IN? identifier
   ;

unstringCountIn
   : COUNT IN? identifier
   ;

unstringWithPointerPhrase
   : WITH? POINTER qualifiedDataName
   ;

unstringTallyingPhrase
   : TALLYING IN? qualifiedDataName
   ;

// use statement

useStatement
   : USE (useAfterClause | useDebugClause)
   ;

useAfterClause
   : GLOBAL? AFTER STANDARD? (EXCEPTION | ERROR) PROCEDURE ON? useAfterOn
   ;

useAfterOn
   : INPUT | OUTPUT | I_O | EXTEND | fileName+
   ;

useDebugClause
   : FOR? DEBUGGING ON? useDebugOn+
   ;

useDebugOn
   : ALL PROCEDURES | ALL REFERENCES? OF? identifier | procedureName | fileName
   ;

// write statement

writeStatement
   : WRITE SUBFILE? recordName writeFromPhrase? writeFormatPhrase* writeTerminalPhrase? writeAdvancingPhrase? writeStartingPhrase? writeRollingPhrase? writeAtEndOfPagePhrase? writeNotAtEndOfPagePhrase? writeIndicatorsPhrase? writeNullMapPhrase? writeNullKeyMapPhrase? invalidKeyPhrase? notInvalidKeyPhrase? END_WRITE?
   ;

writeFormatPhrase
   : FORMAT IS? (identifier | literal)
   ;

writeTerminalPhrase
   : TERMINAL IS? (identifier | literal)
   ;

writeStartingPhrase
   : STARTING AT? LINE? (identifier | integerLiteral)
   ;

writeIndicatorsPhrase
   : (INDICATOR | INDICATORS | INDIC) (IS | ARE)? identifier
   ;

writeFromPhrase
   : FROM (identifier | literal)
   ;

writeAdvancingPhrase
   : (BEFORE | AFTER) ADVANCING? (writeAdvancingPage | writeAdvancingLines | writeAdvancingMnemonic)
   ;

writeAdvancingPage
   : PAGE
   ;

writeAdvancingLines
   : (identifier | literal) (LINE | LINES)?
   ;

writeAdvancingMnemonic
   : mnemonicName
   ;

writeAtEndOfPagePhrase
   : AT? (END_OF_PAGE | EOP) statement*
   ;

writeNotAtEndOfPagePhrase
   : NOT AT? (END_OF_PAGE | EOP) statement*
   ;

// IBM ILE: ROLLING {UP|DOWN} integer LINES [FROM LINE ... TO LINE ...]
writeRollingPhrase
   : ROLLING (UP | DOWN) (identifier | integerLiteral) LINES? (FROM LINE? (identifier | integerLiteral) TO LINE? (identifier | integerLiteral))?
   ;

// IBM ILE: NULL-MAP IS identifier
writeNullMapPhrase
   : NULL_MAP IS? identifier
   ;

// IBM ILE: NULL-KEY-MAP IS identifier
writeNullKeyMapPhrase
   : NULL_KEY_MAP IS? identifier
   ;

// IBM ILE: XML GENERATE statement
xmlGenerateStatement
   : XML GENERATE identifier FROM identifier xmlGenerateCountPhrase? xmlGenerateWithPhrase? onExceptionClause? notOnExceptionClause? END_XML?
   ;

xmlGenerateCountPhrase
   : COUNT IN? identifier
   ;

xmlGenerateWithPhrase
   : WITH? (ENCODING (identifier | integerLiteral) | XML_DECLARATION | ATTRIBUTES | NAMESPACE IS? (identifier | literal) (NAMESPACE_PREFIX IS? (identifier | literal))?)
   ;

// IBM ILE: XML PARSE statement
xmlParseStatement
   : XML PARSE identifier xmlParseProcessingPhrase? xmlParseValidatingPhrase? onExceptionClause? notOnExceptionClause? END_XML?
   ;

xmlParseProcessingPhrase
   : PROCESSING PROCEDURE IS? procedureName (THROUGH | THRU) procedureName
   | PROCESSING PROCEDURE IS? procedureName
   ;

xmlParseValidatingPhrase
   : VALIDATING WITH? (identifier | FILE (identifier | literal))
   ;

// statement phrases ----------------------------------

atEndPhrase
   : AT? END statement*
   ;

notAtEndPhrase
   : NOT AT? END statement*
   ;

invalidKeyPhrase
   : INVALID KEY? statement*
   ;

notInvalidKeyPhrase
   : NOT INVALID KEY? statement*
   ;

onOverflowPhrase
   : ON? OVERFLOW statement*
   ;

notOnOverflowPhrase
   : NOT ON? OVERFLOW statement*
   ;

onSizeErrorPhrase
   : ON? SIZE ERROR statement*
   ;

notOnSizeErrorPhrase
   : NOT ON? SIZE ERROR statement*
   ;

// statement clauses ----------------------------------

onExceptionClause
   : ON? EXCEPTION statement*
   ;

notOnExceptionClause
   : NOT ON? EXCEPTION statement*
   ;

// arithmetic expression ----------------------------------

arithmeticExpression
   : multDivs plusMinus*
   ;

plusMinus
   : (PLUSCHAR | MINUSCHAR) multDivs
   ;

multDivs
   : powers multDiv*
   ;

multDiv
   : (ASTERISKCHAR | SLASHCHAR) powers
   ;

powers
   : (PLUSCHAR | MINUSCHAR)? basis power*
   ;

power
   : DOUBLEASTERISKCHAR basis
   ;

basis
   : LPARENCHAR arithmeticExpression RPARENCHAR | identifier | literal
   ;

// condition ----------------------------------

condition
   : combinableCondition andOrCondition*
   ;

andOrCondition
   : (AND | OR) (combinableCondition | abbreviation+)
   ;

combinableCondition
   : NOT? simpleCondition
   ;

simpleCondition
   : classCondition | LPARENCHAR condition RPARENCHAR | relationCondition | conditionNameReference
   ;

classCondition
   : identifier IS? NOT? (NUMERIC | ALPHABETIC | ALPHABETIC_LOWER | ALPHABETIC_UPPER | DBCS | KANJI | className)
   | LPARENCHAR identifier RPARENCHAR IS? NOT? (NUMERIC | ALPHABETIC | ALPHABETIC_LOWER | ALPHABETIC_UPPER | DBCS | KANJI | className)
   ;

conditionNameReference
   : conditionName (inData* inFile? conditionNameSubscriptReference* | inMnemonic*)
   ;

conditionNameSubscriptReference
   : LPARENCHAR subscript (COMMACHAR? subscript)* RPARENCHAR
   ;

// relation ----------------------------------

relationCondition
   : relationSignCondition | relationArithmeticComparison | relationCombinedComparison
   ;

relationSignCondition
   : arithmeticExpression IS? NOT? (POSITIVE | NEGATIVE | ZERO)
   ;

relationArithmeticComparison
   : arithmeticExpression relationalOperator arithmeticExpression
   ;

relationCombinedComparison
   : arithmeticExpression relationalOperator LPARENCHAR relationCombinedCondition RPARENCHAR
   ;

relationCombinedCondition
   : arithmeticExpression ((AND | OR) arithmeticExpression)+
   ;

relationalOperator
   : (IS | ARE)? (NOT? (GREATER THAN? | MORETHANCHAR | LESS THAN? | LESSTHANCHAR | EQUAL TO? | EQUALCHAR | MORETHANOREQUAL | LESSTHANOREQUAL) | NOTEQUALCHAR | GREATER THAN? OR EQUAL TO? | MORETHANOREQUAL | LESS THAN? OR EQUAL TO? | LESSTHANOREQUAL)
   ;

abbreviation
   : NOT? relationalOperator? (arithmeticExpression | LPARENCHAR arithmeticExpression abbreviation RPARENCHAR)
   ;

// identifier ----------------------------------

identifier
   : qualifiedDataName referenceModifier? | tableCall | functionCall | specialRegister
   ;

tableCall
   : qualifiedDataName (LPARENCHAR subscript (COMMACHAR? subscript)* RPARENCHAR)+ referenceModifier?
   ;

functionCall
   : FUNCTION functionName (LPARENCHAR argument (COMMACHAR? argument)* RPARENCHAR)* referenceModifier?
   ;

referenceModifier
   : LPARENCHAR characterPosition COLONCHAR length? RPARENCHAR
   ;

characterPosition
   : arithmeticExpression
   ;

length
   : arithmeticExpression
   ;

subscript
   : ALL | integerLiteral | qualifiedDataName ((PLUSCHAR | MINUSCHAR) integerLiteral)? | indexName ((PLUSCHAR | MINUSCHAR) integerLiteral)? | arithmeticExpression
   ;

argument
   : literal | identifier | qualifiedDataName integerLiteral? | indexName integerLiteral? | arithmeticExpression
   ;

// qualified data name ----------------------------------

qualifiedDataName
   : qualifiedDataNameFormat1 | qualifiedDataNameFormat2 | qualifiedDataNameFormat3 | qualifiedDataNameFormat4
   ;

qualifiedDataNameFormat1
   : (dataName | conditionName) (qualifiedInData+ inFile? | inFile)?
   ;

qualifiedDataNameFormat2
   : paragraphName inSection
   ;

qualifiedDataNameFormat3
   : textName inLibrary
   ;

qualifiedDataNameFormat4
   : LINAGE_COUNTER inFile
   ;

qualifiedInData
   : inData | inTable
   ;

// in ----------------------------------

inData
   : (IN | OF) dataName
   ;

inFile
   : (IN | OF) fileName
   ;

inMnemonic
   : (IN | OF) mnemonicName
   ;

inSection
   : (IN | OF) sectionName
   ;

inLibrary
   : (IN | OF) libraryName
   ;

inTable
   : (IN | OF) tableCall
   ;

// names ----------------------------------

alphabetName
   : cobolWord
   ;

assignmentName
   : systemName
   ;

basisName
   : programName
   ;

cdName
   : cobolWord
   ;

className
   : cobolWord
   ;

computerName
   : systemName
   ;

conditionName
   : cobolWord
   ;

dataName
   : cobolWord
   ;

dataDescName
   : FILLER | CURSOR | dataName
   ;

environmentName
   : systemName
   ;

fileName
   : cobolWord
   ;

functionName
   : INTEGER | LENGTH | RANDOM | SUM | WHEN_COMPILED | cobolWord
   ;

indexName
   : cobolWord
   ;

languageName
   : systemName
   ;

libraryName
   : cobolWord
   ;

localName
   : cobolWord
   ;

mnemonicName
   : cobolWord
   ;

paragraphName
   : cobolWord | integerLiteral
   ;

procedureName
   : paragraphName inSection? | sectionName
   ;

programName
   : NONNUMERICLITERAL | cobolWord
   ;

recordName
   : qualifiedDataName
   ;

reportName
   : qualifiedDataName
   ;

routineName
   : cobolWord
   ;

screenName
   : cobolWord
   ;

sectionName
   : cobolWord | integerLiteral
   ;

systemName
   : cobolWord
   ;

symbolicCharacter
   : cobolWord
   ;

textName
   : cobolWord
   ;

// literal ----------------------------------

cobolWord
   : IDENTIFIER
   | ABORT | ACQUIRE | AS | ASCII | ASSOCIATED_DATA | ASSOCIATED_DATA_LENGTH | ATTRIBUTE | AUTO | AUTO_SKIP
   | BACKGROUND_COLOR | BACKGROUND_COLOUR | BEEP | BELL | BINARY | BIT | BLINK | BLOB | BOUNDS
   | CAPABLE | CCSVERSION | CHANGED | CHANNEL | CLOB | CLOSE_DISPOSITION | COBOL | COMMITMENT | CONTROL_POINT | CONVENTION | CRUNCH | CURSOR
   | DBCLOB | DEFAULT | DEFAULT_DISPLAY | DEFINITION | DFHRESP | DFHVALUE | DISK | DONTCARE | DOUBLE | DROP
   | EBCDIC | EMPTY_CHECK | ENTER | ENTRY_PROCEDURE | EOL | EOS | ERASE | ESCAPE | EVENT | EXCLUSIVE | EXPORT | EXTENDED
   | FOREGROUND_COLOR | FOREGROUND_COLOUR | FULL | FUNCTIONNAME | FUNCTION_POINTER
   | GRID
   | HIGHLIGHT
   | IMPLICIT | IMPORT | INTEGER
   | KEPT | KEYBOARD
   | LANGUAGE | LB | LD | LEFTLINE | LENGTH_CHECK | LIBACCESS | LIBPARAMETER | LIBRARY | LIST | LOCAL | LONG_DATE | LONG_TIME | LOWER | LOWLIGHT
   | MMDDYYYY
   | NAMED | NATIONAL | NATIONAL_EDITED | NETWORK | NO_ECHO | NUMERIC_DATE | NUMERIC_TIME
   | ODT | ORDERLY | OVERLINE | OWN
   | PASSWORD | PORT | PREVIOUS | PRINTER | PRIVATE | PROCESS | PROGRAM | PROMPT
   | READER | REAL | RECEIVED | RECURSIVE | REF | REMOTE | REMOVE | REQUIRED | REVERSE_VIDEO
   | SAVE | SECURE | SHARED | SHAREDBYALL | SHAREDBYRUNUNIT | SHARING | SHORT_DATE | SQL | SYMBOL
   | TASK | THREAD | THREAD_LOCAL | TIMER | TODAYS_DATE | TODAYS_NAME | TRUNCATED | TYPEDEF
   | UNDERLINE
   | VIRTUAL
   | WAIT
   | YEAR | YYYYMMDD | YYYYDDD
   | ZERO_FILL
   ;

literal
   : NONNUMERICLITERAL | figurativeConstant | numericLiteral | booleanLiteral | cicsDfhRespLiteral | cicsDfhValueLiteral
   ;

booleanLiteral
   : TRUE | FALSE | BOOLEANLITERAL
   ;

numericLiteral
   : NUMERICLITERAL | ZERO | integerLiteral
   ;

integerLiteral
   : INTEGERLITERAL | LEVEL_NUMBER_66 | LEVEL_NUMBER_77 | LEVEL_NUMBER_88
   ;

cicsDfhRespLiteral
   : DFHRESP LPARENCHAR (cobolWord | literal) RPARENCHAR
   ;

cicsDfhValueLiteral
   : DFHVALUE LPARENCHAR (cobolWord | literal) RPARENCHAR
   ;

// keywords ----------------------------------

figurativeConstant
   : ALL literal | HIGH_VALUE | HIGH_VALUES | LOW_VALUE | LOW_VALUES | NULL | NULLS | QUOTE | QUOTES | SPACE | SPACES | ZERO | ZEROS | ZEROES
   ;

specialRegister
   : ADDRESS OF identifier
   | DATE | DAY | DAY_OF_WEEK | DEBUG_CONTENTS | DEBUG_ITEM | DEBUG_LINE | DEBUG_NAME | DEBUG_SUB_1 | DEBUG_SUB_2 | DEBUG_SUB_3
   | LENGTH OF? identifier | LINAGE_COUNTER | LINE_COUNTER
   | PAGE_COUNTER
   | RETURN_CODE
   | SHIFT_IN | SHIFT_OUT | SORT_CONTROL | SORT_CORE_SIZE | SORT_FILE_SIZE | SORT_MESSAGE | SORT_MODE_SIZE | SORT_RETURN
   | TALLY | TIME
   | WHEN_COMPILED
   | XML_CODE | XML_EVENT | XML_NTEXT | XML_TEXT
   ;

// comment entry

commentEntry
   : COMMENTENTRYLINE+
   ;

// lexer rules --------------------------------------------------------------------------------

// keywords
ABORT : A B O R T;
ACCEPT : A C C E P T;
ACCESS : A C C E S S;
ACQUIRE : A C Q U I R E;
ADD : A D D;
ADDRESS : A D D R E S S;
ADVANCING : A D V A N C I N G;
AFTER : A F T E R;
ALIGNED : A L I G N E D;
ALL : A L L;
ALPHABET : A L P H A B E T;
ALPHABETIC : A L P H A B E T I C;
ALPHABETIC_LOWER : A L P H A B E T I C MINUSCHAR L O W E R;
ALPHABETIC_UPPER : A L P H A B E T I C MINUSCHAR U P P E R;
ALPHANUMERIC : A L P H A N U M E R I C;
ALPHANUMERIC_EDITED : A L P H A N U M E R I C MINUSCHAR E D I T E D;
ALSO : A L S O;
ALTER : A L T E R;
ALTERNATE : A L T E R N A T E;
AND : A N D;
ANY : A N Y;
ARE : A R E;
AREA : A R E A;
AREAS : A R E A S;
AS : A S;
ASCENDING : A S C E N D I N G;
ASCII : A S C I I;
ASSIGN : A S S I G N;
ASSOCIATED_DATA : A S S O C I A T E D MINUSCHAR D A T A;
ASSOCIATED_DATA_LENGTH : A S S O C I A T E D MINUSCHAR D A T A MINUSCHAR L E N G T H;
AT : A T;
ATTRIBUTE : A T T R I B U T E;
ATTRIBUTES : A T T R I B U T E S;
AUTHOR : A U T H O R;
AUTO : A U T O;
AUTO_SKIP : A U T O MINUSCHAR S K I P;
BACKGROUND_COLOR : B A C K G R O U N D MINUSCHAR C O L O R;
BACKGROUND_COLOUR : B A C K G R O U N D MINUSCHAR C O L O U R;
BASIS : B A S I S;
BEEP : B E E P;
BEFORE : B E F O R E;
BEGINNING : B E G I N N I N G;
BELL : B E L L;
BINARY : B I N A R Y;
BIT : B I T;
BLANK : B L A N K;
BLINK : B L I N K;
BLOB : B L O B;
BLOCK : B L O C K;
BOUNDS : B O U N D S;
BOTTOM : B O T T O M;
BY : B Y;
CBL : C B L;
BYFUNCTION : B Y F U N C T I O N;
BYTITLE : B Y T I T L E;
CALL : C A L L;
CANCEL : C A N C E L;
CAPABLE : C A P A B L E;
CCSVERSION : C C S V E R S I O N;
CD : C D;
CF : C F;
CH : C H;
CHAINING : C H A I N I N G;
CHANGED : C H A N G E D;
CHANNEL : C H A N N E L;
CHARACTER : C H A R A C T E R;
CHARACTERS : C H A R A C T E R S;
CLASS : C L A S S;
CLASS_ID : C L A S S MINUSCHAR I D;
CLOB : C L O B;
CLOCK_UNITS : C L O C K MINUSCHAR U N I T S;
CLOSE : C L O S E;
CLOSE_DISPOSITION : C L O S E MINUSCHAR D I S P O S I T I O N;
COBOL : C O B O L;
CODE : C O D E;
CODE_SET : C O D E MINUSCHAR S E T;
COLLATING : C O L L A T I N G;
COL : C O L;
COLUMN : C O L U M N;
COM_REG : C O M MINUSCHAR R E G;
COMMA : C O M M A;
COMMIT : C O M M I T;
COMMITMENT : C O M M I T M E N T;
COMMON : C O M M O N;
COMMUNICATION : C O M M U N I C A T I O N;
COMP : C O M P;
COMP_1 : C O M P MINUSCHAR '1';
COMP_2 : C O M P MINUSCHAR '2';
COMP_3 : C O M P MINUSCHAR '3';
COMP_4 : C O M P MINUSCHAR '4';
COMP_5 : C O M P MINUSCHAR '5';
COMPUTATIONAL : C O M P U T A T I O N A L;
COMPUTATIONAL_1 : C O M P U T A T I O N A L MINUSCHAR '1';
COMPUTATIONAL_2 : C O M P U T A T I O N A L MINUSCHAR '2';
COMPUTATIONAL_3 : C O M P U T A T I O N A L MINUSCHAR '3';
COMPUTATIONAL_4 : C O M P U T A T I O N A L MINUSCHAR '4';
COMPUTATIONAL_5 : C O M P U T A T I O N A L MINUSCHAR '5';
COMPUTE : C O M P U T E;
CONFIGURATION : C O N F I G U R A T I O N;
CONTAINS : C O N T A I N S;
CONTENT : C O N T E N T;
CONTINUE : C O N T I N U E;
CONTROL : C O N T R O L;
CONTROL_AREA : C O N T R O L MINUSCHAR A R E A;
CONTROL_POINT : C O N T R O L MINUSCHAR P O I N T;
CONTROLS : C O N T R O L S;
CONVENTION : C O N V E N T I O N;
CONVERTING : C O N V E R T I N G;
COPY : C O P Y;
CORR : C O R R;
CORRESPONDING : C O R R E S P O N D I N G;
COUNT : C O U N T;
CRT : C R T;
CRT_UNDER : C R T MINUSCHAR U N D E R;
CRUNCH : C R U N C H;
CURRENCY : C U R R E N C Y;
CURSOR : C U R S O R;
DATA : D A T A;
DATA_BASE : D A T A MINUSCHAR B A S E;
DATE : D A T E;
DATE_COMPILED : D A T E MINUSCHAR C O M P I L E D;
DATE_WRITTEN : D A T E MINUSCHAR W R I T T E N;
DAY : D A Y;
DAY_OF_WEEK : D A Y MINUSCHAR O F MINUSCHAR W E E K;
DBCS : D B C S;
DBCLOB : D B C L O B;
DE : D E;
DEBUG_CONTENTS : D E B U G MINUSCHAR C O N T E N T S;
DEBUG_ITEM : D E B U G MINUSCHAR I T E M;
DEBUG_LINE : D E B U G MINUSCHAR L I N E;
DEBUG_NAME : D E B U G MINUSCHAR N A M E;
DEBUG_SUB_1 : D E B U G MINUSCHAR S U B MINUSCHAR '1';
DEBUG_SUB_2 : D E B U G MINUSCHAR S U B MINUSCHAR '2';
DEBUG_SUB_3 : D E B U G MINUSCHAR S U B MINUSCHAR '3';
DEBUGGING : D E B U G G I N G;
DECIMAL_POINT : D E C I M A L MINUSCHAR P O I N T;
DECLARATIVES : D E C L A R A T I V E S;
DEFAULT : D E F A U L T;
DEFAULT_DISPLAY : D E F A U L T MINUSCHAR D I S P L A Y;
DEFINITION : D E F I N I T I O N;
DELETE : D E L E T E;
DELIMITED : D E L I M I T E D;
DELIMITER : D E L I M I T E R;
DEPENDING : D E P E N D I N G;
DESCENDING : D E S C E N D I N G;
DESTINATION : D E S T I N A T I O N;
DETAIL : D E T A I L;
DFHRESP : D F H R E S P;
DFHVALUE : D F H V A L U E;
DISABLE : D I S A B L E;
DISK : D I S K;
DISPLAY : D I S P L A Y;
DISPLAY_1 : D I S P L A Y MINUSCHAR '1';
DIVIDE : D I V I D E;
DIVISION : D I V I S I O N;
DONTCARE : D O N T C A R E;
DOUBLE : D O U B L E;
DOWN : D O W N;
DROP : D R O P;
DUPLICATES : D U P L I C A T E S;
DYNAMIC : D Y N A M I C;
EBCDIC : E B C D I C;
EGCS : E G C S; // E X T E N S I O N
EGI : E G I;
ELSE : E L S E;
EMI : E M I;
EMPTY_CHECK : E M P T Y MINUSCHAR C H E C K;
ENABLE : E N A B L E;
ENCODING : E N C O D I N G;
END : E N D;
END_ACCEPT : E N D MINUSCHAR A C C E P T;
END_ADD : E N D MINUSCHAR A D D;
END_CALL : E N D MINUSCHAR C A L L;
END_COMPUTE : E N D MINUSCHAR C O M P U T E;
END_DELETE : E N D MINUSCHAR D E L E T E;
END_DISPLAY : E N D MINUSCHAR D I S P L A Y;
END_DIVIDE : E N D MINUSCHAR D I V I D E;
END_EVALUATE : E N D MINUSCHAR E V A L U A T E;
END_IF : E N D MINUSCHAR I F;
END_MULTIPLY : E N D MINUSCHAR M U L T I P L Y;
END_OF_PAGE : E N D MINUSCHAR O F MINUSCHAR P A G E;
END_PERFORM : E N D MINUSCHAR P E R F O R M;
END_READ : E N D MINUSCHAR R E A D;
END_RECEIVE : E N D MINUSCHAR R E C E I V E;
END_REMARKS : E N D MINUSCHAR R E M A R K S;
END_RETURN : E N D MINUSCHAR R E T U R N;
END_REWRITE : E N D MINUSCHAR R E W R I T E;
END_SEARCH : E N D MINUSCHAR S E A R C H;
END_START : E N D MINUSCHAR S T A R T;
END_STRING : E N D MINUSCHAR S T R I N G;
END_SUBTRACT : E N D MINUSCHAR S U B T R A C T;
END_UNSTRING : E N D MINUSCHAR U N S T R I N G;
END_WRITE : E N D MINUSCHAR W R I T E;
END_XML : E N D MINUSCHAR X M L;
ENDING : E N D I N F;
ENTER : E N T E R;
ENTRY : E N T R Y;
ENTRY_PROCEDURE : E N T R Y MINUSCHAR P R O C E D U R E;
ENVIRONMENT : E N V I R O N M E N T;
EOP : E O P;
EQUAL : E Q U A L;
ERASE : E R A S E;
ERROR : E R R O R;
EOL : E O L;
EOS : E O S;
ESCAPE : E S C A P E;
ESI : E S I;
EVALUATE : E V A L U A T E;
EVENT : E V E N T;
EVERY : E V E R Y;
EXCEPTION : E X C E P T I O N;
EXCLUSIVE : E X C L U S I V E;
EXHIBIT : E X H I B I T;
EXIT : E X I T;
EXPORT : E X P O R T;
EXTEND : E X T E N D;
EXTENDED : E X T E N D E D;
EXTERNAL : E X T E R N A L;
FALSE : F A L S E;
FD : F D;
FILE : F I L E;
FILE_CONTROL : F I L E MINUSCHAR C O N T R O L;
FILLER : F I L L E R;
FINAL : F I N A L;
FIRST : F I R S T;
FOOTING : F O O T I N G;
FOR : F O R;
FORMAT : F O R M A T;
FOREGROUND_COLOR : F O R E G R O U N D MINUSCHAR C O L O R;
FOREGROUND_COLOUR : F O R E G R O U N D MINUSCHAR C O L O U R;
FROM : F R O M;
FULL : F U L L;
FUNCTION : F U N C T I O N;
FUNCTIONNAME : F U N C T I O N N A M E;
FUNCTION_POINTER : F U N C T I O N MINUSCHAR P O I N T E R;
GENERATE : G E N E R A T E;
GOBACK : G O B A C K;
GIVING : G I V I N G;
GLOBAL : G L O B A L;
GO : G O;
GREATER : G R E A T E R;
GRID : G R I D;
GROUP : G R O U P;
HEADING : H E A D I N G;
HIGHLIGHT : H I G H L I G H T;
HIGH_VALUE : H I G H MINUSCHAR V A L U E;
HIGH_VALUES : H I G H MINUSCHAR V A L U E S;
I_O : I MINUSCHAR O;
I_O_CONTROL : I MINUSCHAR O MINUSCHAR C O N T R O L;
ID : I D;
IDENTIFICATION : I D E N T I F I C A T I O N;
IF : I F;
IMPLICIT : I M P L I C I T;
IMPORT : I M P O R T;
IN : I N;
INDEX : I N D E X;
INDEXED : I N D E X E D;
INDICATE : I N D I C A T E;
INDIC : I N D I C;
INDICATOR : I N D I C A T O R;
INDICATORS : I N D I C A T O R S;
INITIAL : I N I T I A L;
INITIALIZE : I N I T I A L I Z E;
INITIATE : I N I T I A T E;
INPUT : I N P U T;
INPUT_OUTPUT : I N P U T MINUSCHAR O U T P U T;
INSPECT : I N S P E C T;
INSTALLATION : I N S T A L L A T I O N;
INTEGER : I N T E G E R;
INTO : I N T O;
INVALID : I N V A L I D;
INVOKE : I N V O K E;
IS : I S;
JUST : J U S T;
JUSTIFIED : J U S T I F I E D;
KANJI : K A N J I;
KEPT : K E P T;
KEY : K E Y;
KEYBOARD : K E Y B O A R D;
LABEL : L A B E L;
LANGUAGE : L A N G U A G E;
LAST : L A S T;
LB : L B;
LD : L D;
LEADING : L E A D I N G;
LEFT : L E F T;
LEFT_JUSTIFY : L E F T MINUSCHAR J U S T I F Y;
LEFTLINE : L E F T L I N E;
LENGTH : L E N G T H;
LENGTH_CHECK : L E N G T H MINUSCHAR C H E C K;
LESS : L E S S;
LIKE : L I K E;
LOCALE : L O C A L E;
LIBACCESS : L I B A C C E S S;
LIBPARAMETER : L I B P A R A M E T E R;
LIBRARY : L I B R A R Y;
LIMIT : L I M I T;
LIMITS : L I M I T S;
LINAGE : L I N A G E;
LINAGE_COUNTER : L I N A G E MINUSCHAR C O U N T E R;
LINE : L I N E;
LINES : L I N E S;
LINE_COUNTER : L I N E MINUSCHAR C O U N T E R;
LINKAGE : L I N K A G E;
LIST : L I S T;
LOCAL : L O C A L;
LOCAL_STORAGE : L O C A L MINUSCHAR S T O R A G E;
LOCK : L O C K;
LONG_DATE : L O N G MINUSCHAR D A T E;
LONG_TIME : L O N G MINUSCHAR T I M E;
LOWER : L O W E R;
LOWLIGHT : L O W L I G H T;
LOW_VALUE : L O W MINUSCHAR V A L U E;
LOW_VALUES : L O W MINUSCHAR V A L U E S;
MEMORY : M E M O R Y;
MERGE : M E R G E;
MESSAGE : M E S S A G E;
MMDDYYYY : M M D D Y Y Y Y;
MODE : M O D E;
MODIFIED : M O D I F I E D;
MODULES : M O D U L E S;
MORE_LABELS : M O R E MINUSCHAR L A B E L S;
MOVE : M O V E;
MULTIPLE : M U L T I P L E;
MULTIPLY : M U L T I P L Y;
NAMED : N A M E D;
NATIONAL : N A T I O N A L;
NATIONAL_EDITED : N A T I O N A L MINUSCHAR E D I T E D;
NAMESPACE : N A M E S P A C E;
NAMESPACE_PREFIX : N A M E S P A C E MINUSCHAR P R E F I X;
NATIVE : N A T I V E;
NEGATIVE : N E G A T I V E;
NETWORK : N E T W O R K;
NEXT : N E X T;
NO : N O;
NO_ECHO : N O MINUSCHAR E C H O;
NOT : N O T;
NULL : N U L L;
NULLS : N U L L S;
NULL_KEY_MAP : N U L L MINUSCHAR K E Y MINUSCHAR M A P;
NULL_MAP : N U L L MINUSCHAR M A P;
NUMBER : N U M B E R;
NUMERIC : N U M E R I C;
NUMERIC_DATE : N U M E R I C MINUSCHAR D A T E;
NUMERIC_EDITED : N U M E R I C MINUSCHAR E D I T E D;
NUMERIC_TIME : N U M E R I C MINUSCHAR T I M E;
OBJECT_COMPUTER : O B J E C T MINUSCHAR C O M P U T E R;
OCCURS : O C C U R S;
ODT : O D T;
OF : O F;
OFF : O F F;
OMITTED : O M I T T E D;
ON : O N;
OPEN : O P E N;
OPTIONAL : O P T I O N A L;
OR : O R;
ORDER : O R D E R;
ORDERLY : O R D E R L Y;
ORGANIZATION : O R G A N I Z A T I O N;
OTHER : O T H E R;
OUTPUT : O U T P U T;
OVERFLOW : O V E R F L O W;
OVERLINE : O V E R L I N E;
OWN : O W N;
PACKED_DECIMAL : P A C K E D MINUSCHAR D E C I M A L;
PADDING : P A D D I N G;
PAGE : P A G E;
PAGE_COUNTER : P A G E MINUSCHAR C O U N T E R;
PASSWORD : P A S S W O R D;
PERFORM : P E R F O R M;
PF : P F;
PH : P H;
PIC : P I C;
PICTURE : P I C T U R E;
PLUS : P L U S;
POINTER : P O I N T E R;
POSITION : P O S I T I O N;
POSITIVE : P O S I T I V E;
PORT : P O R T;
PRINTER : P R I N T E R;
PRINTING : P R I N T I N G;
PRIOR : P R I O R;
PREVIOUS : P R E V I O U S;
PRIVATE : P R I V A T E;
PROCEDURE : P R O C E D U R E;
PROCEDURE_POINTER : P R O C E D U R E MINUSCHAR P O I N T E R;
PROCEDURES : P R O C E D U R E S;
PROCEED : P R O C E E D;
PROCESS : P R O C E S S;
PROCESSING : P R O C E S S I N G;
PROGRAM : P R O G R A M;
PROGRAM_ID : P R O G R A M MINUSCHAR I D;
PROGRAM_LIBRARY : P R O G R A M MINUSCHAR L I B R A R Y;
PROMPT : P R O M P T;
PURGE : P U R G E;
QUEUE : Q U E U E;
QUOTE : Q U O T E;
QUOTES : Q U O T E S;
RANDOM : R A N D O M;
READER : R E A D E R;
REMOTE : R E M O T E;
RD : R D;
REAL : R E A L;
READ : R E A D;
RECEIVE : R E C E I V E;
RECEIVED : R E C E I V E D;
RECORD : R E C O R D;
RECORDING : R E C O R D I N G;
RECORDS : R E C O R D S;
RECURSIVE : R E C U R S I V E;
REDEFINES : R E D E F I N E S;
REEL : R E E L;
REF : R E F;
REFERENCE : R E F E R E N C E;
REFERENCES : R E F E R E N C E S;
RELATIVE : R E L A T I V E;
RELEASE : R E L E A S E;
REMAINDER : R E M A I N D E R;
REMARKS : R E M A R K S;
REMOVAL : R E M O V A L;
REMOVE : R E M O V E;
RENAMES : R E N A M E S;
REPLACE : R E P L A C E;
REPLACING : R E P L A C I N G;
REPORT : R E P O R T;
REPORTING : R E P O R T I N G;
REPORTS : R E P O R T S;
REQUIRED : R E Q U I R E D;
RERUN : R E R U N;
RESERVE : R E S E R V E;
REVERSE_VIDEO : R E V E R S E MINUSCHAR V I D E O;
RESET : R E S E T;
RETURN : R E T U R N;
RETURN_CODE : R E T U R N MINUSCHAR C O D E;
RETURNING: R E T U R N I N G;
REVERSED : R E V E R S E D;
REWIND : R E W I N D;
REWRITE : R E W R I T E;
RF : R F;
RH : R H;
RIGHT : R I G H T;
RIGHT_JUSTIFY : R I G H T MINUSCHAR J U S T I F Y;
ROLLBACK : R O L L B A C K;
ROLLING : R O L L I N G;
ROUNDED : R O U N D E D;
RUN : R U N;
SAME : S A M E;
SAVE : S A V E;
SCREEN : S C R E E N;
SD : S D;
SEARCH : S E A R C H;
SECTION : S E C T I O N;
SECURE : S E C U R E;
SECURITY : S E C U R I T Y;
SEGMENT : S E G M E N T;
SEGMENT_LIMIT : S E G M E N T MINUSCHAR L I M I T;
SELECT : S E L E C T;
SEND : S E N D;
SENTENCE : S E N T E N C E;
SEPARATE : S E P A R A T E;
SEQUENCE : S E Q U E N C E;
SEQUENTIAL : S E Q U E N T I A L;
SET : S E T;
SHARED : S H A R E D;
SHAREDBYALL : S H A R E D B Y A L L;
SHAREDBYRUNUNIT : S H A R E D B Y R U N U N I T;
SHARING : S H A R I N G;
SHIFT_IN : S H I F T MINUSCHAR I N;
SHIFT_OUT : S H I F T MINUSCHAR O U T;
SHORT_DATE : S H O R T MINUSCHAR D A T E;
SIGN : S I G N;
SIZE : S I Z E;
SORT : S O R T;
SORT_CONTROL : S O R T MINUSCHAR C O N T R O L;
SORT_CORE_SIZE : S O R T MINUSCHAR C O R E MINUSCHAR S I Z E;
SORT_FILE_SIZE : S O R T MINUSCHAR F I L E MINUSCHAR S I Z E;
SORT_MERGE : S O R T MINUSCHAR M E R G E;
SORT_MESSAGE : S O R T MINUSCHAR M E S S A G E;
SORT_MODE_SIZE : S O R T MINUSCHAR M O D E MINUSCHAR S I Z E;
SORT_RETURN : S O R T MINUSCHAR R E T U R N;
SOURCE : S O U R C E;
SOURCE_COMPUTER : S O U R C E MINUSCHAR C O M P U T E R;
SPACE : S P A C E;
SPACES : S P A C E S;
SPACE_FILL : S P A C E MINUSCHAR F I L L;
SPECIAL_NAMES : S P E C I A L MINUSCHAR N A M E S;
SQL : S Q L;
STANDARD : S T A N D A R D;
STANDARD_1 : S T A N D A R D MINUSCHAR '1';
STANDARD_2 : S T A N D A R D MINUSCHAR '2';
START : S T A R T;
STATUS : S T A T U S;
STOP : S T O P;
STRING : S T R I N G;
SUB_QUEUE_1 : S U B MINUSCHAR Q U E U E MINUSCHAR '1';
SUB_QUEUE_2 : S U B MINUSCHAR Q U E U E MINUSCHAR '2';
SUB_QUEUE_3 : S U B MINUSCHAR Q U E U E MINUSCHAR '3';
SUBFILE : S U B F I L E;
SUBTRACT : S U B T R A C T;
SUM : S U M;
SUPPRESS : S U P P R E S S;
SYMBOL : S Y M B O L;
SYMBOLIC : S Y M B O L I C;
SYNC : S Y N C;
SYNCHRONIZED : S Y N C H R O N I Z E D;
TABLE : T A B L E;
TALLY : T A L L Y;
TALLYING : T A L L Y I N G;
TASK : T A S K;
TAPE : T A P E;
TERMINAL : T E R M I N A L;
TERMINATE : T E R M I N A T E;
TEST : T E S T;
TEXT : T E X T;
THAN : T H A N;
THEN : T H E N;
THREAD : T H R E A D;
THREAD_LOCAL : T H R E A D MINUSCHAR L O C A L;
THROUGH : T H R O U G H;
THRU : T H R U;
TIME : T I M E;
TIMER : T I M E R;
TIMES : T I M E S;
TITLE : T I T L E;
TO : T O;
TODAYS_DATE : T O D A Y S MINUSCHAR D A T E;
TODAYS_NAME : T O D A Y S MINUSCHAR N A M E;
TOP : T O P;
STARTING : S T A R T I N G;
TRAILING : T R A I L I N G;
TRAILING_SIGN : T R A I L I N G MINUSCHAR S I G N;
TRANSACTION : T R A N S A C T I O N;
TIMESTAMP : T I M E S T A M P;
TRUE : T R U E;
TRUNCATED : T R U N C A T E D;
TYPE : T Y P E;
TYPEDEF : T Y P E D E F;
UNDERLINE : U N D E R L I N E;
UNIT : U N I T;
UNSTRING : U N S T R I N G;
UNTIL : U N T I L;
UPDATE : U P D A T E;
UP : U P;
UPON : U P O N;
USAGE : U S A G E;
USE : U S E;
USING : U S I N G;
VALUE : V A L U E;
VALIDATING : V A L I D A T I N G;
VALUES : V A L U E S;
VARYING : V A R Y I N G;
VIRTUAL : V I R T U A L;
WAIT : W A I T;
WHEN : W H E N;
WHEN_COMPILED : W H E N MINUSCHAR C O M P I L E D;
WITH : W I T H;
WORDS : W O R D S;
WORKING_STORAGE : W O R K I N G MINUSCHAR S T O R A G E;
WRITE : W R I T E;
XML : X M L;
XML_CODE : X M L MINUSCHAR C O D E;
XML_DECLARATION : X M L MINUSCHAR D E C L A R A T I O N;
XML_EVENT : X M L MINUSCHAR E V E N T;
XML_NTEXT : X M L MINUSCHAR N T E X T;
XML_TEXT : X M L MINUSCHAR T E X T;
YEAR : Y E A R;
YYYYMMDD : Y Y Y Y M M D D;
YYYYDDD : Y Y Y Y D D D;
ZERO : Z E R O;
ZERO_FILL : Z E R O MINUSCHAR F I L L;
ZEROS : Z E R O S;
ZEROES : Z E R O E S;

// symbols
AMPCHAR : '&';
ASTERISKCHAR : '*';
DOUBLEASTERISKCHAR : '**';
COLONCHAR : ':';
COMMACHAR : ',';
COMMENTENTRYTAG : '*>CE';
COMMENTTAG : '*>';
DOLLARCHAR : '$';
DOUBLEQUOTE : '"';
// period full stop
DOT_FS : '.' ('\r' | '\n' | '\f' | '\t' | ' ')+ | '.' EOF;
DOT : '.';
EQUALCHAR : '=';
EXECCICSTAG : '*>EXECCICS';
EXECSQLTAG : '*>EXECSQL';
EXECSQLIMSTAG : '*>EXECSQLIMS';
LESSTHANCHAR : '<';
LESSTHANOREQUAL : '<=';
LPARENCHAR : '(';
MINUSCHAR : '-';
MORETHANCHAR : '>';
MORETHANOREQUAL : '>=';
NOTEQUALCHAR : '<>';
PLUSCHAR : '+';
SINGLEQUOTE : '\'';
RPARENCHAR : ')';
SLASHCHAR : '/';

// literals
BOOLEANLITERAL : B '"' [01] '"' | B '\'' [01] '\'';
NONNUMERICLITERAL : STRINGLITERAL | DBCSLITERAL | HEXNUMBER  | NULLTERMINATED;

fragment HEXNUMBER :
	X '"' [0-9A-F]+ '"'
	| X '\'' [0-9A-F]+ '\''
;

fragment NULLTERMINATED :
	Z '"' (~["\n\r] | '""' | '\'')* '"'
	| Z '\'' (~['\n\r] | '\'\'' | '"')* '\''
;

fragment STRINGLITERAL :
	'"' (~["\n\r] | '""' | '\'')* '"'
	| '\'' (~['\n\r] | '\'\'' | '"')* '\''
;

fragment DBCSLITERAL :
	[GN] '"' (~["\n\r] | '""' | '\'')* '"'
	| [GN] '\'' (~['\n\r] | '\'\'' | '"')* '\''
;

LEVEL_NUMBER_66 : '66';
LEVEL_NUMBER_77 : '77';
LEVEL_NUMBER_88 : '88';

INTEGERLITERAL : (PLUSCHAR | MINUSCHAR)? [0-9]+;

NUMERICLITERAL : (PLUSCHAR | MINUSCHAR)? [0-9]* (DOT | COMMACHAR) [0-9]+ (('e' | 'E') (PLUSCHAR | MINUSCHAR)? [0-9]+)?;

IDENTIFIER : [a-zA-Z0-9]+ ([-_]+ [a-zA-Z0-9]+)*;

// whitespace, line breaks, comments, ...
NEWLINE : '\r'? '\n' -> channel(HIDDEN);
EXECCICSLINE : EXECCICSTAG WS ~('\n' | '\r' | '}')* ('\n' | '\r' | '}');
EXECSQLIMSLINE : EXECSQLIMSTAG WS ~('\n' | '\r' | '}')* ('\n' | '\r' | '}');
EXECSQLEND : EXECSQLTAG WS ~('\n' | '\r' | '}')* '}';
EXECSQLLINE : EXECSQLTAG WS ~('\n' | '\r' | '}')* ('\n' | '\r');
COMMENTENTRYLINE : COMMENTENTRYTAG WS ~('\n' | '\r')*;
COMMENTLINE : COMMENTTAG WS ~('\n' | '\r')* -> channel(HIDDEN);
WS : [ \t\f;]+ -> channel(HIDDEN);
SEPARATOR : ', ' -> channel(HIDDEN);

// case insensitive chars
fragment A:('a'|'A');
fragment B:('b'|'B');
fragment C:('c'|'C');
fragment D:('d'|'D');
fragment E:('e'|'E');
fragment F:('f'|'F');
fragment G:('g'|'G');
fragment H:('h'|'H');
fragment I:('i'|'I');
fragment J:('j'|'J');
fragment K:('k'|'K');
fragment L:('l'|'L');
fragment M:('m'|'M');
fragment N:('n'|'N');
fragment O:('o'|'O');
fragment P:('p'|'P');
fragment Q:('q'|'Q');
fragment R:('r'|'R');
fragment S:('s'|'S');
fragment T:('t'|'T');
fragment U:('u'|'U');
fragment V:('v'|'V');
fragment W:('w'|'W');
fragment X:('x'|'X');
fragment Y:('y'|'Y');
fragment Z:('z'|'Z');
