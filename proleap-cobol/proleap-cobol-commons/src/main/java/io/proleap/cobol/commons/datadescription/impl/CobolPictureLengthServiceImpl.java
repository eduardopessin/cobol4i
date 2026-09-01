package io.proleap.cobol.commons.datadescription.impl;

import java.util.regex.Matcher;

import jakarta.inject.Singleton;

import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.call.DataDescriptionEntryCall;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.data.datadescription.FormatClause;
import io.proleap.cobol.asg.metamodel.data.datadescription.LikeClause;
import io.proleap.cobol.asg.metamodel.data.datadescription.PictureClause;
import io.proleap.cobol.commons.datadescription.CobolPictureLengthService;
import io.proleap.cobol.commons.util.CobolPictureParseUtils;

@Singleton
public class CobolPictureLengthServiceImpl implements CobolPictureLengthService {

	@Override
	public Integer getFractionalPartLength(final String pictureString) {
		final Matcher matcher9 = CobolPictureParseUtils.PATTERN_9.matcher(pictureString);
		final Matcher matcher9Length = CobolPictureParseUtils.PATTERN_9Length.matcher(pictureString);
		final Matcher matcher9DOT9 = CobolPictureParseUtils.PATTERN_9DOT9.matcher(pictureString);
		final Matcher matcher9V9 = CobolPictureParseUtils.PATTERN_9V9.matcher(pictureString);
		final Matcher matcherS9 = CobolPictureParseUtils.PATTERN_S9.matcher(pictureString);
		final Matcher matcherS9Length = CobolPictureParseUtils.PATTERN_S9Length.matcher(pictureString);
		final Matcher matcher9LengthV9 = CobolPictureParseUtils.PATTERN_9LengthV9.matcher(pictureString);
		final Matcher matcher9LengthV9Length = CobolPictureParseUtils.PATTERN_9LengthV9Length.matcher(pictureString);
		final Matcher matcher9V9Length = CobolPictureParseUtils.PATTERN_9V9Length.matcher(pictureString);
		final Matcher matcherS9LengthV9 = CobolPictureParseUtils.PATTERN_S9LengthV9.matcher(pictureString);
		final Matcher matcherS9LengthV9Length = CobolPictureParseUtils.PATTERN_S9LengthV9Length.matcher(pictureString);
		final Matcher matcherS9V9 = CobolPictureParseUtils.PATTERN_S9V9.matcher(pictureString);
		final Matcher matcherS9V9Length = CobolPictureParseUtils.PATTERN_S9V9Length.matcher(pictureString);

		final Integer result;

		if (matcher9.matches()) {
			result = 0;
		} else if (matcher9Length.matches()) {
			result = 0;
		} else if (matcher9DOT9.matches()) {
			result = matcher9DOT9.group(2).length();
		} else if (matcher9V9.matches()) {
			result = matcher9V9.group(2).length();
		} else if (matcherS9.matches()) {
			result = 0;
		} else if (matcherS9Length.matches()) {
			result = 0;
		} else if (matcher9LengthV9.matches()) {
			result = matcher9LengthV9.group(2).length();
		} else if (matcher9LengthV9Length.matches()) {
			result = Integer.valueOf(matcher9LengthV9Length.group(2));
		} else if (matcher9V9Length.matches()) {
			result = Integer.valueOf(matcher9V9Length.group(2));
		} else if (matcherS9LengthV9.matches()) {
			result = matcherS9LengthV9.group(2).length();
		} else if (matcherS9LengthV9Length.matches()) {
			result = Integer.valueOf(matcherS9LengthV9Length.group(2));
		} else if (matcherS9V9.matches()) {
			// S9V9 pattern has no capture groups; count '9' chars after 'V'
			final String match = matcherS9V9.group(0);
			result = match.length() - match.indexOf('V') - 1;
		} else if (matcherS9V9Length.matches()) {
			result = Integer.valueOf(matcherS9V9Length.group(2));
		} else {
			// Try numeric-edited patterns with + or - sign
			final Matcher matcherPlus9Length = CobolPictureParseUtils.PATTERN_PLUS_9Length.matcher(pictureString);
			final Matcher matcherPlus9LengthV9Length = CobolPictureParseUtils.PATTERN_PLUS_9LengthV9Length.matcher(pictureString);
			final Matcher matcherPlus9LengthV9 = CobolPictureParseUtils.PATTERN_PLUS_9LengthV9.matcher(pictureString);
			final Matcher matcherPlus9V9Length = CobolPictureParseUtils.PATTERN_PLUS_9V9Length.matcher(pictureString);
			final Matcher matcherPlus9V9 = CobolPictureParseUtils.PATTERN_PLUS_9V9.matcher(pictureString);

			if (matcherPlus9Length.matches()) {
				result = 0;
			} else if (matcherPlus9LengthV9Length.matches()) {
				result = Integer.valueOf(matcherPlus9LengthV9Length.group(2));
			} else if (matcherPlus9LengthV9.matches()) {
				result = matcherPlus9LengthV9.group(2).length();
			} else if (matcherPlus9V9Length.matches()) {
				result = Integer.valueOf(matcherPlus9V9Length.group(2));
			} else if (matcherPlus9V9.matches()) {
				result = matcherPlus9V9.group(2).length();
			} else {
				result = null;
			}
		}

		return result;
	}

	@Override
	public Integer getIntegerPartLength(final String pictureString) {
		final Matcher matcher9 = CobolPictureParseUtils.PATTERN_9.matcher(pictureString);
		final Matcher matcher9Length = CobolPictureParseUtils.PATTERN_9Length.matcher(pictureString);
		final Matcher matcher9DOT9 = CobolPictureParseUtils.PATTERN_9DOT9.matcher(pictureString);
		final Matcher matcher9V9 = CobolPictureParseUtils.PATTERN_9V9.matcher(pictureString);
		final Matcher matcherS9 = CobolPictureParseUtils.PATTERN_S9.matcher(pictureString);
		final Matcher matcherS9Length = CobolPictureParseUtils.PATTERN_S9Length.matcher(pictureString);
		final Matcher matcher9LengthV9 = CobolPictureParseUtils.PATTERN_9LengthV9.matcher(pictureString);
		final Matcher matcher9LengthV9Length = CobolPictureParseUtils.PATTERN_9LengthV9Length.matcher(pictureString);
		final Matcher matcher9V9Length = CobolPictureParseUtils.PATTERN_9V9Length.matcher(pictureString);
		final Matcher matcherS9LengthV9 = CobolPictureParseUtils.PATTERN_S9LengthV9.matcher(pictureString);
		final Matcher matcherS9LengthV9Length = CobolPictureParseUtils.PATTERN_S9LengthV9Length.matcher(pictureString);
		final Matcher matcherS9V9 = CobolPictureParseUtils.PATTERN_S9V9.matcher(pictureString);
		final Matcher matcherS9V9Length = CobolPictureParseUtils.PATTERN_S9V9Length.matcher(pictureString);

		final Integer result;

		if (matcher9.matches()) {
			result = matcher9.group(0).length();
		} else if (matcher9Length.matches()) {
			result = Integer.valueOf(matcher9Length.group(1));
		} else if (matcher9DOT9.matches()) {
			result = matcher9DOT9.group(1).length();
		} else if (matcher9V9.matches()) {
			result = matcher9V9.group(1).length();
		} else if (matcherS9.matches()) {
			// PIC S99...: integer part = count(9s), exclude the 'S'
			result = matcherS9.group(0).length() - 1;
		} else if (matcherS9Length.matches()) {
			result = Integer.valueOf(matcherS9Length.group(1));
		} else if (matcher9LengthV9.matches()) {
			result = Integer.valueOf(matcher9LengthV9.group(1));
		} else if (matcher9LengthV9Length.matches()) {
			result = Integer.valueOf(matcher9LengthV9Length.group(1));
		} else if (matcher9V9Length.matches()) {
			result = matcher9V9Length.group(1).length();
		} else if (matcherS9LengthV9.matches()) {
			result = Integer.valueOf(matcherS9LengthV9.group(1));
		} else if (matcherS9LengthV9Length.matches()) {
			result = Integer.valueOf(matcherS9LengthV9Length.group(1));
		} else if (matcherS9V9.matches()) {
			// S9V9 pattern has no capture groups; count '9' chars between 'S' and 'V'
			final String match = matcherS9V9.group(0);
			result = match.indexOf('V') - 1; // subtract 1 for the 'S'
		} else if (matcherS9V9Length.matches()) {
			result = matcherS9V9Length.group(1).length();
		} else {
			// Try numeric-edited patterns with + or - sign
			final Matcher matcherPlus9Length = CobolPictureParseUtils.PATTERN_PLUS_9Length.matcher(pictureString);
			final Matcher matcherPlus9LengthV9Length = CobolPictureParseUtils.PATTERN_PLUS_9LengthV9Length.matcher(pictureString);
			final Matcher matcherPlus9LengthV9 = CobolPictureParseUtils.PATTERN_PLUS_9LengthV9.matcher(pictureString);
			final Matcher matcherPlus9V9Length = CobolPictureParseUtils.PATTERN_PLUS_9V9Length.matcher(pictureString);
			final Matcher matcherPlus9V9 = CobolPictureParseUtils.PATTERN_PLUS_9V9.matcher(pictureString);

			if (matcherPlus9Length.matches()) {
				result = Integer.valueOf(matcherPlus9Length.group(1));
			} else if (matcherPlus9LengthV9Length.matches()) {
				result = Integer.valueOf(matcherPlus9LengthV9Length.group(1));
			} else if (matcherPlus9LengthV9.matches()) {
				result = Integer.valueOf(matcherPlus9LengthV9.group(1));
			} else if (matcherPlus9V9Length.matches()) {
				result = matcherPlus9V9Length.group(1).length();
			} else if (matcherPlus9V9.matches()) {
				result = matcherPlus9V9.group(1).length();
			} else {
				result = null;
			}
		}

		return result;
	}

	@Override
	public Integer getLength(final DataDescriptionEntry dataDescriptionEntry) {
		final Integer result;

		switch (dataDescriptionEntry.getDataDescriptionEntryType()) {
		case GROUP:
		case SCALAR:
			final DataDescriptionEntryGroup dataDescriptionEntryGroup = (DataDescriptionEntryGroup) dataDescriptionEntry;
			final PictureClause pictureClause = dataDescriptionEntryGroup.getPictureClause();
			if (pictureClause != null) {
				final String pictureString = pictureClause.getPictureString();
				return pictureString == null ? null : getLength(pictureString);
			}
			// LIKE clause: resolve length from the referenced data item
			final LikeClause likeClause = dataDescriptionEntryGroup.getLikeClause();
			if (likeClause != null && likeClause.getLikeCall() != null) {
				final Call likeCall = likeClause.getLikeCall();
				if (likeCall.getCallType() == Call.CallType.DATA_DESCRIPTION_ENTRY_CALL) {
					final DataDescriptionEntryCall ddeCall = (DataDescriptionEntryCall) likeCall.unwrap();
					final Integer baseLength = getLength(ddeCall.getDataDescriptionEntry());
					if (baseLength != null && likeClause.getLengthModifier() != null) {
						return baseLength + likeClause.getLengthModifier();
					}
					return baseLength;
				}
			}
			// FORMAT DATE/TIME/TIMESTAMP clause: derive length from format type
			final FormatClause formatClause = dataDescriptionEntryGroup.getFormatClause();
			if (formatClause != null) {
				if (formatClause.getSize() != null) {
					return formatClause.getSize();
				}
				// Default lengths per IBM ILE COBOL:
				// DATE "@Y-%m-%d" = 10 (YYYY-MM-DD), TIME = 8 (HH.MM.SS), TIMESTAMP = 26
				final FormatClause.FormatType formatType = formatClause.getFormatType();
				if (FormatClause.FormatType.DATE.equals(formatType)) {
					return 10;
				} else if (FormatClause.FormatType.TIME.equals(formatType)) {
					return 8;
				} else if (FormatClause.FormatType.TIMESTAMP.equals(formatType)) {
					return 26;
				}
			}
			result = null;
			break;
		default:
			result = null;
		}

		return result;
	}

	@Override
	public Integer getLength(final String pictureString) {
		// Normalize to uppercase — COBOL allows lowercase pic x(1)
		final String normalizedPic = pictureString != null ? pictureString.toUpperCase() : pictureString;
		final Matcher matcher9 = CobolPictureParseUtils.PATTERN_9.matcher(normalizedPic);
		final Matcher matcher9Length = CobolPictureParseUtils.PATTERN_9Length.matcher(normalizedPic);
		final Matcher matcher9DOT9 = CobolPictureParseUtils.PATTERN_9DOT9.matcher(normalizedPic);
		final Matcher matcher9V9 = CobolPictureParseUtils.PATTERN_9V9.matcher(normalizedPic);
		final Matcher matcherS9 = CobolPictureParseUtils.PATTERN_S9.matcher(normalizedPic);
		final Matcher matcherS9Length = CobolPictureParseUtils.PATTERN_S9Length.matcher(normalizedPic);
		final Matcher matcher9LengthV9 = CobolPictureParseUtils.PATTERN_9LengthV9.matcher(normalizedPic);
		final Matcher matcher9LengthV9Length = CobolPictureParseUtils.PATTERN_9LengthV9Length.matcher(normalizedPic);
		final Matcher matcher9V9Length = CobolPictureParseUtils.PATTERN_9V9Length.matcher(normalizedPic);
		// Signed numeric patterns with implied decimal (S9V9, S9(n)V9, S9(n)V9(m), S9V9(m))
		final Matcher matcherS9V9 = CobolPictureParseUtils.PATTERN_S9V9.matcher(normalizedPic);
		final Matcher matcherS9LengthV9 = CobolPictureParseUtils.PATTERN_S9LengthV9.matcher(normalizedPic);
		final Matcher matcherS9LengthV9Length = CobolPictureParseUtils.PATTERN_S9LengthV9Length.matcher(normalizedPic);
		final Matcher matcherS9V9Length = CobolPictureParseUtils.PATTERN_S9V9Length.matcher(normalizedPic);
		final Matcher matcherA = CobolPictureParseUtils.PATTERN_A.matcher(normalizedPic);
		final Matcher matcherALength = CobolPictureParseUtils.PATTERN_ALength.matcher(normalizedPic);
		final Matcher matcherX = CobolPictureParseUtils.PATTERN_X.matcher(normalizedPic);
		final Matcher matcherXLength = CobolPictureParseUtils.PATTERN_XLength.matcher(normalizedPic);

		final Integer result;

		if (matcher9.matches()) {
			result = matcher9.group(0).length();
		} else if (matcher9Length.matches()) {
			result = Integer.valueOf(matcher9Length.group(1));
		} else if (matcher9DOT9.matches()) {
			result = matcher9DOT9.group(1).length() + 1 + matcher9DOT9.group(2).length();
		} else if (matcher9V9.matches()) {
			// PIC 99V99: DISPLAY length = integer digits + decimal digits (V is implied, no position)
			result = matcher9V9.group(1).length() + matcher9V9.group(2).length();
		} else if (matcherS9.matches()) {
			// PIC S99...: DISPLAY length = count(9s only), sign is embedded (not separate)
			result = matcherS9.group(0).length() - 1; // subtract 1 for the 'S'
		} else if (matcherS9Length.matches()) {
			// PIC S9(n): DISPLAY length = n, sign is embedded (not separate)
			result = Integer.valueOf(matcherS9Length.group(1));
		} else if (matcher9LengthV9.matches()) {
			// PIC 9(n)V99: DISPLAY length = n + count(9s after V), V is implied
			result = Integer.valueOf(matcher9LengthV9.group(1)) + matcher9LengthV9.group(2).length();
		} else if (matcher9LengthV9Length.matches()) {
			result = Integer.valueOf(matcher9LengthV9Length.group(1))
					+ Integer.valueOf(matcher9LengthV9Length.group(2));
		} else if (matcher9V9Length.matches()) {
			// PIC 99V9(m): DISPLAY length = count(9s before V) + m, V is implied
			result = matcher9V9Length.group(1).length() + Integer.valueOf(matcher9V9Length.group(2));
		} else if (matcherS9LengthV9Length.matches()) {
			// PIC S9(n)V9(m): DISPLAY length = n + m (sign is embedded, V is implied)
			result = Integer.valueOf(matcherS9LengthV9Length.group(1))
					+ Integer.valueOf(matcherS9LengthV9Length.group(2));
		} else if (matcherS9LengthV9.matches()) {
			// PIC S9(n)V99...: DISPLAY length = n + count(9s after V)
			result = Integer.valueOf(matcherS9LengthV9.group(1)) + matcherS9LengthV9.group(2).length();
		} else if (matcherS9V9Length.matches()) {
			// PIC S99...V9(m): DISPLAY length = count(9s before V) + m
			result = matcherS9V9Length.group(1).length() + Integer.valueOf(matcherS9V9Length.group(2));
		} else if (matcherS9V9.matches()) {
			// PIC S99...V99...: DISPLAY length = count(9s before V) + count(9s after V)
			final String match = matcherS9V9.group(0);
			final int vIdx = match.indexOf('V');
			result = (vIdx - 1) + (match.length() - vIdx - 1); // subtract 1 for S, 1 for V
		} else if (matcherA.matches()) {
			result = matcherA.group(0).length();
		} else if (matcherALength.matches()) {
			result = Integer.valueOf(matcherALength.group(1));
		} else if (matcherX.matches()) {
			result = matcherX.group(0).length();
		} else if (matcherXLength.matches()) {
			result = Integer.valueOf(matcherXLength.group(1));
		} else {
			// Fallback: handle numeric-edited pictures containing Z, B, *, /, $, etc.
			result = computeEditedPicLength(pictureString);
		}

		return result;
	}

	/**
	 * Computes the display length of a numeric-edited PIC string by expanding
	 * repeat counts (e.g., Z(5) -> 5) and counting display positions.
	 * Returns null if the string doesn't look like a valid edited picture.
	 */
	private Integer computeEditedPicLength(final String pictureString) {
		final String upper = pictureString.toUpperCase().trim();
		// PIC 1: boolean (1 byte)
		if ("1".equals(upper)) {
			return 1;
		}
		// Only attempt if the PIC contains editing characters
		if (!upper.matches("[ZB*$0-9/,. ()V+-]+")) {
			return null;
		}
		int length = 0;
		for (int i = 0; i < upper.length(); i++) {
			final char c = upper.charAt(i);
			if (c == '(' || c == ')') {
				continue; // handled with the preceding character
			}
			// Check if followed by (n) repeat count
			if (i + 1 < upper.length() && upper.charAt(i + 1) == '(') {
				final int closeIdx = upper.indexOf(')', i + 1);
				if (closeIdx > i + 2) {
					try {
						final int count = Integer.parseInt(upper.substring(i + 2, closeIdx));
						length += count;
						i = closeIdx;
						continue;
					} catch (final NumberFormatException e) {
						// fall through
					}
				}
			}
			// V (implied decimal) and S (sign) don't occupy a display position
			if (c == 'V' || c == 'S') {
				continue;
			}
			length++;
		}
		return length > 0 ? length : null;
	}

	@Override
	public Integer getStringLength(final String pictureString) {
		final String normalizedPic = pictureString != null ? pictureString.toUpperCase() : pictureString;
		final Matcher matcherA = CobolPictureParseUtils.PATTERN_A.matcher(normalizedPic);
		final Matcher matcherALength = CobolPictureParseUtils.PATTERN_ALength.matcher(normalizedPic);
		final Matcher matcherX = CobolPictureParseUtils.PATTERN_X.matcher(normalizedPic);
		final Matcher matcherXLength = CobolPictureParseUtils.PATTERN_XLength.matcher(normalizedPic);

		final Integer result;

		if (matcherA.matches()) {
			result = matcherA.group(0).length();
		} else if (matcherALength.matches()) {
			result = Integer.valueOf(matcherALength.group(1));
		} else if (matcherX.matches()) {
			result = matcherX.group(0).length();
		} else if (matcherXLength.matches()) {
			result = Integer.valueOf(matcherXLength.group(1));
		} else {
			result = null;
		}

		return result;
	}
}
