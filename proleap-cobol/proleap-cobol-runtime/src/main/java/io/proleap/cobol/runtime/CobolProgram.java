package io.proleap.cobol.runtime;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.Map;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import io.proleap.cobol.runtime.screen.ScreenIoContext;
import io.proleap.cobol.runtime.screen.ScreenIoHandler;

/**
 * Base class for all COBOL programs converted to Java.
 * Provides common COBOL runtime facilities.
 */
public abstract class CobolProgram {

	protected FileControlService fileControlService;

	protected SqlService sqlService;

	protected ProgramRunner programRunner;

	/**
	 * Optional screen I/O handler. When null (default) all screen operations
	 * fall through to the underlying {@link FileControlService}, preserving
	 * legacy behaviour for batch programs. When a handler is registered by the
	 * host (typically the Spring Boot wrapper, per
	 * docs/ux-java-screen-io-spec.md §6), the helpers below route EXFMT /
	 * subfile calls through it.
	 */
	protected ScreenIoHandler screenIoHandler;

	protected int returnCode = 0;

	// XML PARSE special registers (IBM ILE COBOL)
	protected String xml_event = "";
	protected String xml_text = "";
	protected String xml_ntext = "";
	protected BigDecimal xml_code = BigDecimal.ZERO;

	public void init(final FileControlService fileControlService, final SqlService sqlService,
			final ProgramRunner programRunner) {
		this.fileControlService = fileControlService;
		this.sqlService = sqlService;
		this.programRunner = programRunner;
	}

	public abstract void procedureDivision() throws Exception;

	/**
	 * Set LINKAGE SECTION parameters before calling procedureDivision().
	 * Parameters are mapped positionally to PROCEDURE DIVISION USING fields.
	 * Subclasses should override to implement parameter passing.
	 */
	public void setLinkageParameters(final Object... parameters) {
		// Default no-op; overridden by ReflectiveCobolProgram or generated programs
	}

	/**
	 * Get LINKAGE SECTION parameter values after procedureDivision() returns.
	 * Used for BY REFERENCE copy-back semantics.
	 * Returns null if not supported.
	 */
	public Object[] getLinkageParameters() {
		return null;
	}

	public int getReturnCode() {
		return returnCode;
	}

	/**
	 * Register (or clear with {@code null}) a screen I/O handler for this
	 * program instance. Typically called by the Spring Boot wrapper before
	 * {@link #procedureDivision()}. Also mirrors the handler onto
	 * {@link ScreenIoContext} so the {@code ScreenIoFileControlService}
	 * decorator can pick it up on the current thread. When unset, screen I/O
	 * falls back to the existing {@link FileControlService} behaviour — NO
	 * REGRESSION for batch programs.
	 */
	public void setScreenIoHandler(final ScreenIoHandler handler) {
		this.screenIoHandler = handler;
		ScreenIoContext.bind(handler);
	}

	public ScreenIoHandler getScreenIoHandler() {
		return screenIoHandler;
	}

	/**
	 * Convenience hook: EXFMT of a single (non-subfile) record format.
	 * Routes through the registered {@link ScreenIoHandler} when present,
	 * otherwise delegates to the legacy
	 * {@link FileControlService#write(String, String, Object)} path so existing
	 * generated code keeps working unchanged.
	 *
	 * @param displayFile DDS display-file name
	 * @param recordFormat record-format name (e.g. "RB00055CTL")
	 * @param outData output field values serialised for the client
	 * @param indicators current DDS indicator vector (may be null)
	 * @return the handler's response; {@code null} when no handler is registered
	 */
	protected ScreenIoHandler.ExfmtResponse exfmt(final String displayFile, final String recordFormat,
			final Map<String, Object> outData, final IndicatorArray indicators) {
		if (screenIoHandler == null) {
			if (fileControlService != null) {
				fileControlService.write(displayFile, recordFormat, indicators);
			}
			return null;
		}
		pushIndicators(indicators);
		final ScreenIoHandler.IndicatorSnapshot snap = snapshot(indicators);
		screenIoHandler.writeRecord(displayFile, recordFormat, outData, snap);
		final ScreenIoHandler.ExfmtResponse response = screenIoHandler.exfmt(displayFile, recordFormat, snap);
		pullIndicators(indicators);
		return response;
	}

	/**
	 * Convenience hook: WRITE SUBFILE record row. No-op against the legacy
	 * service (the existing generated code performs the write itself via
	 * {@link FileControlService#writeSubfile}); when a screen handler is
	 * registered the runtime re-routes the row to the handler instead.
	 */
	protected void writeSubfileRecord(final String recordFormat, final int rrn,
			final Map<String, Object> rowData) {
		if (screenIoHandler != null) {
			screenIoHandler.writeSubfileRecord(recordFormat, rrn, rowData);
		}
	}

	/**
	 * Convenience hook: READ SUBFILE NEXT MODIFIED. Returns {@code null} when
	 * no handler is registered (generated code continues to use
	 * {@link FileControlService#readSubfile} in that case) or when the subfile
	 * has no further modified rows — matching AS/400 file-status 23.
	 */
	protected ScreenIoHandler.ModifiedSubfileRow readNextModifiedSubfileRow(final String recordFormat) {
		if (screenIoHandler == null) {
			return null;
		}
		return screenIoHandler.readNextModifiedSubfileRow(recordFormat);
	}

	/**
	 * Convenience hook: CLEAR SUBFILE (SFLCLR). No-op without a handler.
	 */
	protected void clearSubfile(final String recordFormat) {
		if (screenIoHandler != null) {
			screenIoHandler.clearSubfile(recordFormat);
		}
	}

	private ScreenIoHandler.IndicatorSnapshot snapshot(final IndicatorArray indicators) {
		if (indicators == null) {
			return new ScreenIoHandler.IndicatorSnapshot(new boolean[100]);
		}
		final boolean[] copy = new boolean[100];
		for (int i = 1; i <= 99; i++) {
			copy[i] = indicators.get(i);
		}
		return new ScreenIoHandler.IndicatorSnapshot(copy);
	}

	/**
	 * Push the program's {@link IndicatorArray} into the registered
	 * {@link ScreenIoHandler} via
	 * {@link ScreenIoHandler#setIndicatorVector(boolean[])} before any handler
	 * write. Keeps the handler's DDS conditioning view in lock-step with the
	 * program's vector. Silent no-op when no handler is bound or indicators
	 * are null.
	 */
	private void pushIndicators(final IndicatorArray indicators) {
		if (screenIoHandler == null || indicators == null) {
			return;
		}
		final boolean[] copy = new boolean[100];
		for (int i = 1; i <= 99; i++) {
			copy[i] = indicators.get(i);
		}
		screenIoHandler.setIndicatorVector(copy);
	}

	/**
	 * Pull the handler's indicator vector back into the program's
	 * {@link IndicatorArray} via
	 * {@link ScreenIoHandler#getIndicatorVector()} after any handler
	 * read/exfmt. Propagates user-driven indicator changes (function keys,
	 * SFLEND, etc.) into the program before the next statement executes.
	 */
	private void pullIndicators(final IndicatorArray indicators) {
		if (screenIoHandler == null || indicators == null) {
			return;
		}
		final boolean[] fromHandler = screenIoHandler.getIndicatorVector();
		if (fromHandler == null) {
			return;
		}
		final int limit = Math.min(fromHandler.length - 1, 99);
		for (int i = 1; i <= limit; i++) {
			indicators.set(i, fromHandler[i]);
		}
	}

	// COBOL MOVE equivalent
	protected String moveAlphanumeric(final String value, final int length) {
		if (value == null) {
			return spaces(length);
		}
		if (value.length() >= length) {
			return value.substring(0, length);
		}
		return value + spaces(length - value.length());
	}

	protected BigDecimal moveNumeric(final BigDecimal value, final int integerDigits, final int decimalDigits) {
		if (value == null) {
			return BigDecimal.ZERO;
		}
		return value.setScale(decimalDigits, java.math.RoundingMode.HALF_UP);
	}

	protected String spaces(final int length) {
		return " ".repeat(Math.max(0, length));
	}

	/**
	 * Functional interface for XML PARSE processing procedures.
	 * Unlike Runnable, allows checked exceptions (COBOL procedures may throw).
	 */
	@FunctionalInterface
	protected interface XmlHandler {
		void handle() throws Exception;
	}

	/**
	 * IBM ILE COBOL XML PARSE implementation.
	 *
	 * Parses the XML content in {@code xmlContent} using SAX and calls
	 * {@code handler.handle()} for each XML event, after setting the special
	 * registers xml_event and xml_text to the appropriate values.
	 *
	 * The IBM ILE COBOL XML PARSE events are:
	 *   START-OF-DOCUMENT, END-OF-DOCUMENT,
	 *   START-OF-ELEMENT, END-OF-ELEMENT,
	 *   ATTRIBUTE-NAME, ATTRIBUTE-CHARACTERS,
	 *   CONTENT-CHARACTERS,
	 *   VERSION-INFORMATION, ENCODING-DECLARATION, STANDALONE-DECLARATION,
	 *   EXCEPTION, etc.
	 *
	 * The processing procedure is called once per event. The COBOL program
	 * can set XML-CODE to -1 in the handler to stop parsing early.
	 */
	protected void xmlParse(final String xmlContent, final XmlHandler handler) throws Exception {
		if (xmlContent == null || xmlContent.trim().isEmpty()) {
			xml_event = "EXCEPTION";
			xml_text = "";
			xml_code = BigDecimal.valueOf(1);
			handler.handle();
			return;
		}

		// Array wrapper for mutable state accessible from inner class
		final Exception[] handlerException = { null };
		final boolean[] stoppedByHandler = { false };

		try {
			final SAXParserFactory factory = SAXParserFactory.newInstance();
			factory.setNamespaceAware(false);
			// Disable external entities for security
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			final SAXParser saxParser = factory.newSAXParser();

			// Fire START-OF-DOCUMENT
			xml_event = "START-OF-DOCUMENT";
			xml_text = "";
			xml_code = BigDecimal.ZERO;
			handler.handle();
			if (xml_code.intValue() == -1) {
				return;
			}

			final CobolProgram self = this;

			saxParser.parse(new InputSource(new StringReader(xmlContent.trim())), new DefaultHandler() {

				private void fireEvent(final String event, final String text) throws SAXException {
					self.xml_event = event;
					self.xml_text = (text != null) ? text : "";
					try {
						handler.handle();
					} catch (final Exception e) {
						handlerException[0] = e;
						throw new SAXException("Handler exception", e);
					}
					if (self.xml_code.intValue() == -1) {
						stoppedByHandler[0] = true;
						throw new SAXException("Stopped by handler (XML-CODE = -1)");
					}
				}

				@Override
				public void startElement(final String uri, final String localName, final String qName,
						final Attributes attributes) throws SAXException {
					fireEvent("START-OF-ELEMENT", qName);
					// Fire attribute events
					for (int i = 0; i < attributes.getLength(); i++) {
						fireEvent("ATTRIBUTE-NAME", attributes.getQName(i));
						fireEvent("ATTRIBUTE-CHARACTERS", attributes.getValue(i));
					}
				}

				@Override
				public void endElement(final String uri, final String localName, final String qName)
						throws SAXException {
					fireEvent("END-OF-ELEMENT", qName);
				}

				@Override
				public void characters(final char[] ch, final int start, final int length) throws SAXException {
					final String text = new String(ch, start, length);
					if (!text.trim().isEmpty()) {
						fireEvent("CONTENT-CHARACTERS", text);
					}
				}
			});

			// Fire END-OF-DOCUMENT
			xml_event = "END-OF-DOCUMENT";
			xml_text = "";
			handler.handle();

			// Success
			xml_code = BigDecimal.ZERO;

		} catch (final SAXException e) {
			// Check if handler raised an exception
			if (handlerException[0] != null) {
				throw handlerException[0];
			}
			// Check if handler requested stop (XML-CODE = -1)
			if (stoppedByHandler[0]) {
				return;
			}
			// Genuine XML parsing error — fire EXCEPTION event
			xml_event = "EXCEPTION";
			xml_text = e.getMessage() != null ? e.getMessage() : "";
			xml_code = BigDecimal.valueOf(1);
			handler.handle();
		} catch (final Exception e) {
			// Other parsing error — fire EXCEPTION event
			xml_event = "EXCEPTION";
			xml_text = e.getMessage() != null ? e.getMessage() : "";
			xml_code = BigDecimal.valueOf(1);
			handler.handle();
		}
	}
}
