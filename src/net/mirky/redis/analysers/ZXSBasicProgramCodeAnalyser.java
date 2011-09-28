package net.mirky.redis.analysers;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

import net.mirky.redis.BichromaticStringBuilder;
import net.mirky.redis.BichromaticStringBuilder.DelimitedMode;
import net.mirky.redis.Cursor;
import net.mirky.redis.Format;
import net.mirky.redis.Analyser;
import net.mirky.redis.Hex;
import net.mirky.redis.ImageError;
import net.mirky.redis.TextResource;

@Format.Options("zxs-basic-code/decoding:decoding=zx-spectrum/autostart:unsigned-decimal=0")
public final class ZXSBasicProgramCodeAnalyser extends Analyser.Leaf.PossiblyPartial {
    @Override
    protected final int disPartially(Format format, byte[] data, PrintStream port) throws Format.UnknownOption, RuntimeException {
        Cursor cursor = new Cursor.ByteArrayCursor(data, 0);
        int lastLineNumber = 0; // so we can check for out-of-order lines
        int autostart = format.getIntegerOption("autostart");
        boolean autostartLineSeen = false;
        try {
            while (true) {
                if (cursor.tell() == data.length) {
                    break;
                }
                /*
                 * We'll use backticks to delimit raw text from decoded text. ZX
                 * Spectrum's charset does not contains backticks, so there's no
                 * confusiont that might raise if we used, say, ASCII brokets.
                 */
                BichromaticStringBuilder bsb = new BichromaticStringBuilder("33;1");
                DelimitedMode del = new BichromaticStringBuilder.DelimitedMode(bsb, '`', ' ', '`');
                int lineNumber = cursor.getUnsignedBewyde(0); // sic, line number is big endian
                int lineSize = cursor.getUnsignedLewyde(2);
                if (!cursor.probe(4 + lineSize)) {
                    port.println("! line's declared size exceeds bytes available in input");
                    break;
                }
                if (lineNumber == 0 || lineNumber > 9999) {
                    port.println("! invalid line number");
                }
                if (lineNumber <= lastLineNumber) {
                    port.println("! line number sequence should strictly grow but doesn't here");
                }
                lastLineNumber = lineNumber;
                if (lineNumber == autostart) {
                    port.println("* autostart here");
                    autostartLineSeen = true;
                }
                bsb.sb.append(lineNumber);
                bsb.sb.append(' ');
                int i = 4;
                int digitSequenceStart = -1;
                boolean lineProperlyTerminated = false;
                while (i < 4 + lineSize) {
                    int c = cursor.getUnsignedByte(i);
                    if (c == 0x0D && i == 4 + lineSize - 1) {
                        lineProperlyTerminated = true;
                        break;
                    } else if (c >= 0x12 && c <= 0x14 && (cursor.getUnsignedByte(i + 1) == 0x00 || cursor.getUnsignedByte(i + 1) == 0x01)) {
                        boolean newState = cursor.getUnsignedByte(i + 1) != 0;
                        del.delimitForColour();
                        if (!newState) {
                            bsb.sb.append('/');
                        }
                        switch (c) {
                            case 0x12:
                                bsb.sb.append("flash");
                                break;
                            case 0x13:
                                bsb.sb.append("bright");
                                break;
                            case 0x14:
                                bsb.sb.append("inverse");
                                break;
                        }
                        i += 2;
                    } else if (c == 0x0E) {
                        // ZX Spectrum's BASIC stores a human-readable number followed by a preparsed
                        // machine-readable number.  Usually, they match.  In some copy protection
                        // schemes, the human-readable number can be deliberately misleading, so
                        // we want to compare the two numbers against each other and point out any
                        // discrepancies.
                        ZXSBasicProgramAnalyser.ZXSpectrumNumber binary = new ZXSBasicProgramAnalyser.ZXSpectrumNumber(cursor.getBytes(i + 1, 5));
                        boolean skip = false; // we'll skip the binary part if it matches the text part
                        if (digitSequenceStart != -1) {
                            // 32-bit calculations, we can detect overflow easily
                            // because ZX Spectrum's integer calculations were 16-bit
                            int number = 0;
                            boolean overflow = false;
                            for (int j = digitSequenceStart; j < i; j++) {
                                int d = cursor.getUnsignedByte(j);
                                assert d >= 0x30 && d <= 0x39;
                                number *= 10;
                                number += d - 0x30;
                                overflow |= number > 0xFFFF;
                            }
                            // FIXME: check the binary and decimal number's match for floats, too
                            if (!overflow && binary.is(number)) {
                                skip = true;
                            }
                        }
                        if (!skip) {
                            // If we're not skipping, it's because there is no matching text number before the 0x0E.  Why?
                            del.delimitForColour();
                            if (digitSequenceStart != -1) {
                                // If because there was a different text number:
                                bsb.sb.append("actually ");
                            } else {
                                // If because there was no text number:
                                bsb.sb.append("number ");
                            }
                            bsb.sb.append(binary.prepareForDisplay());
                        }
                        digitSequenceStart = -1;
                        i += 6;
                    } else {
                        String keyword = ZXSBasicProgramCodeAnalyser.KEYWORDS[c];
                        if (keyword != null) {
                            del.delimitForColour();
                            bsb.sb.append(keyword);
                            digitSequenceStart = -1;
                        } else {
                            if (c >= '0' && c <= '9') {
                                if (digitSequenceStart == -1) {
                                    digitSequenceStart = i;
                                }
                            } else {
                                digitSequenceStart = -1;
                            }
                            char decodedChar = format.getDecoding().decode((byte) c);
                            if (decodedChar != 0) {
                                del.delimitForPlain();
                                bsb.sb.append(decodedChar);
                            } else {
                                del.delimitForColour();
                                bsb.sb.append("0x");
                                bsb.sb.append(Hex.b(c));
                            }
                        }
                        i++;
                    }
                }
                if (!lineProperlyTerminated) {
                    del.delimitForColour();
                    bsb.sb.append("newline-missing");
                }
                del.delimitForPlain();
                try {
                    port.write(bsb.sb.toString().getBytes("utf-8"));
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException("utf-8 is not supported???", e);
                } catch (IOException e) {
                    throw new RuntimeException("I/O error writing to stdout", e);
                }
                port.println();
                cursor.advance(4 + lineSize);
            }
            if (autostart != 32768 && !autostartLineSeen) {
                if (cursor.tell() == data.length) {
                    port.println("! autostart declared but does not match any program line");
                } else {
                    port.println("! autostart declared but does not match any program line (so far)");
                }
            }
            return cursor.tell();
        } catch (ImageError e) {
            return cursor.tell();
        }
    }

    private static final String[] KEYWORDS = ZXSBasicProgramCodeAnalyser.loadStringTableAsArray("zxsbaskw.tab", 256);

    private static final class SaguaroLineLexer {
        private final String line;
        private int pos;

        public SaguaroLineLexer(String line) {
            this.line = line;
            pos = 0;
        }
        
        public final void skipSpaces() {
            while (pos < line.length() && line.charAt(pos) == ' ') {
                pos++;
            }
        }
        
        public final void skipChar() {
            if (pos < line.length()) {
                pos++;
            }
        }
        
        public final boolean atUnsignedInteger() {
            if (pos >= line.length()) {
                return false;
            }
            char c = line.charAt(pos);
            return c >= '0' && c <= '9';
        }
        
        public final boolean atString() {
            return at('"');
        }

        public final String parseString() {
            assert atString();
            int begin = pos + 1;
            for (int cur = begin; cur < line.length(); cur++) {
                if (line.charAt(cur) == '"') {
                    pos = cur + 1;
                    return line.substring(begin, cur);
                }
            }
            throw new RuntimeException("string not terminated");
        }
        
        public final boolean at(char etalon) {
            return pos < line.length() && line.charAt(pos) == etalon;
        }
        
        public final boolean atEndOfLine() {
            return pos >= line.length();
        }

        private final String parseWord() {
            int begin = pos;
            while (pos < line.length() && isWordChar(line.charAt(pos))) {
                pos++;
            }
            return line.substring(begin, pos);
        }

        public final int parseUnsignedInteger() throws NumberFormatException {
            assert atUnsignedInteger();
            String word = parseWord().toLowerCase();
            if (word.length() >= 3 && word.startsWith("0x")) {
                return Integer.parseInt(word.substring(2), 16);
            } else {
                return Integer.parseInt(word);
            }
        }

        private static final boolean isWordChar(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
        }
    }

    private static final String[] loadStringTableAsArray(String filename, int arraySize) throws NumberFormatException {
        String[] keywords = new String[arraySize];
        for (int i = 0; i < keywords.length; i++) {
            keywords[i] = null;
        }
        for (String line : new TextResource(filename)) {
            SaguaroLineLexer lexer = new SaguaroLineLexer(line);
            lexer.skipSpaces();
            if (!lexer.atUnsignedInteger()) {
                throw new RuntimeException("invalid " + filename + " line: " + line);
            }
            int key = lexer.parseUnsignedInteger();
            lexer.skipSpaces();
            if (!lexer.at(':')) {
                throw new RuntimeException("invalid " + filename + " line: " + line);
            }
            lexer.skipChar();
            lexer.skipSpaces();
            if (!lexer.atString()) {
                throw new RuntimeException("invalid " + filename + " line: " + line);
            }
            String value = lexer.parseString();
            lexer.skipSpaces();
            if (!lexer.atEndOfLine()) {
                throw new RuntimeException("invalid " + filename + " line: " + line);
            }
            if (key < 0 || key >= arraySize) {
                throw new RuntimeException("key " + key + " out of bounds in " + filename);
            }
            if (keywords[key] != null) {
                throw new RuntimeException("duplicate " + filename + " entry for " + key);
            }
            keywords[key] = value;
        }
        return keywords;
    }
}