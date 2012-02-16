package net.mirky.redis;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.mirky.redis.BinaryElementType.Struct.Step;
import net.mirky.redis.ParseUtil.IndentationSensitiveLexer;

abstract class StructureDescriptionParser {
    private StructureDescriptionParser() {
        // not a real constructor
    }

    static abstract class ParameterParser {
        /**
         * Parse parameters of a field type, if any, and return the complete
         * {@link StructFieldType}. Called with {@code lexer} positioned
         * immediately after the keyword, so it may need to start by ignoring
         * horizontal whitespace.
         * 
         * @throws ControlData.LineParseError
         * @throws IOException
         */
        abstract BinaryElementType parseParameters(ParseUtil.IndentationSensitiveLexer lexer) throws IOException;
    }

    // for field types without parameters
    static final class SimpleFieldParameterParser extends StructureDescriptionParser.ParameterParser {
        private final BinaryElementType fieldType;
    
        public SimpleFieldParameterParser(BinaryElementType fieldType) {
            this.fieldType = fieldType;
        }
    
        @Override
        final BinaryElementType parseParameters(ParseUtil.IndentationSensitiveLexer lexer) throws IOException {
            lexer.passLogicalNewline();
            return fieldType;
        }
    }

    static final class SlicedIntegerFieldParameterParser extends StructureDescriptionParser.ParameterParser {
        private final BinaryElementType.BasicInteger integerType;
    
        public SlicedIntegerFieldParameterParser(BinaryElementType.BasicInteger integerType) {
            this.integerType = integerType;
        }
    
        @Override
        final BinaryElementType.SlicedInteger parseParameters(ParseUtil.IndentationSensitiveLexer lexer) throws IOException {
            lexer.passLogicalNewline();
            lexer.passIndent();
            ArrayList<BinaryElementType.SlicedInteger.Slice> slices = new ArrayList<BinaryElementType.SlicedInteger.Slice>();
            while (!lexer.atDedent()) {
                lexer.noIndent();
                slices.add(StructureDescriptionParser.parseIntegerSlice(lexer));
            }
            lexer.discardDedent();
            return new BinaryElementType.SlicedInteger(integerType, slices.toArray(new BinaryElementType.SlicedInteger.Slice[0]));
        }
    }

    // Note that there are two forms of integer slices: 'basic' and 'flags'.
    // Basic slices hold an integer, flag slices hold a single bit.
    static final BinaryElementType.SlicedInteger.Slice parseIntegerSlice(ParseUtil.IndentationSensitiveLexer lexer) throws IOException {
        lexer.hor.pass('@');
        lexer.hor.pass('.');
        int rightShift = lexer.hor.readUnsignedInteger("right shift");
        lexer.hor.skipSpaces();
        BinaryElementType.SlicedInteger.Slice slice;
        if (lexer.hor.atDigit()) {
            // it's a basic slice; the field width (in bits) comes next
            int posBeforeSliceWidth = lexer.hor.getPos();
            int sliceWidth = lexer.hor.readUnsignedInteger("slice width");
            if (sliceWidth == 0) {
                lexer.hor.errorAtPos(posBeforeSliceWidth, "zero-bit slice?");
            }
            List<String> meanings = new ArrayList<String>();
            while (true) {
                lexer.hor.skipSpaces();
                if (!lexer.hor.at('"')) {
                    break;
                }
                meanings.add(lexer.hor.readStringLiteral(null));
            }
            slice = new BinaryElementType.SlicedInteger.Slice.Basic(rightShift, sliceWidth, meanings.toArray(new String[0]));
        } else {
            // it's a flag slice; the field width is implicitly one
            String setFlagName = lexer.hor.readOptStringLiteral(null);
            lexer.hor.skipSpaces();
            String clearFlagName;
            if (lexer.hor.at('/')) {
                lexer.hor.skipChar();
                lexer.hor.skipSpaces();
                clearFlagName = lexer.hor.readStringLiteral("cleared flag meaning");
            } else {
                clearFlagName = null;
            }
            if (setFlagName == null && clearFlagName == null) {
                lexer.error("expected bit meaning");
            }
            slice = new BinaryElementType.SlicedInteger.Slice.Flag(rightShift, setFlagName, clearFlagName);
        }
        lexer.passLogicalNewline();
        return slice;
    }

    public static final BinaryElementType parseStructureDescription(String name, BufferedReader reader) throws IOException {
        ParseUtil.IndentationSensitiveLexer lexer = new ParseUtil.IndentationSensitiveLexer(new ParseUtil.FileLineSource(reader), new ParseUtil.ErrorLocator(name, 0), '#');
        try {
            BinaryElementType type = parseType(lexer);
            lexer.requireEndOfFile();
            return type;
        } finally {
            reader.close();
        }
    }

    public static final BinaryElementType parseType(ParseUtil.IndentationSensitiveLexer lexer) throws IOException {
        int posBefore = lexer.hor.getPos();
        String keyword = lexer.hor.readDashedWord("type");
        StructureDescriptionParser.ParameterParser parameterParser = getFieldTypeParameterParser(keyword);
        BinaryElementType type;
        if (parameterParser == null) {
            try {
                type = BinaryElementType.MANAGER.get(keyword);
            } catch (ResourceManager.ResolutionError e) {
                lexer.hor.errorAtPos(posBefore, "unknown type");
                throw new RuntimeException("bug detected");
            }
            lexer.passLogicalNewline();
        } else {
            type = parameterParser.parseParameters(lexer);
        }
        return type;
    }

    public static final Step parseThisSeek(IndentationSensitiveLexer lexer) {
        assert lexer.hor.at('@');
        lexer.hor.skipChar();
        int sign;
        if (lexer.hor.at('+')) {
            lexer.hor.skipChar();
            sign = +1;
        } else if (lexer.hor.at('-')) {
            lexer.hor.skipChar();
            sign = -1;
        } else {
            sign = 0;
        }
        int offset = lexer.hor.readUnsignedInteger("offset");
        return sign == 0 ? new Step.LocalSeek(offset) : new Step.RelSeek(sign * offset);
    }

    private static final Map<String, StructureDescriptionParser.ParameterParser> KNOWN_FIELD_TYPES = new HashMap<String, StructureDescriptionParser.ParameterParser>();

    static final StructureDescriptionParser.ParameterParser getFieldTypeParameterParser(String name) {
        return KNOWN_FIELD_TYPES.get(name);
    }

    static {
        KNOWN_FIELD_TYPES.put("sliced-byte", new SlicedIntegerFieldParameterParser(BinaryElementType.BasicInteger.BYTE));
        KNOWN_FIELD_TYPES.put("sliced-lewyde", new SlicedIntegerFieldParameterParser(BinaryElementType.BasicInteger.LEWYDE));
        KNOWN_FIELD_TYPES.put("sliced-bewyde", new SlicedIntegerFieldParameterParser(BinaryElementType.BasicInteger.BEWYDE));

        KNOWN_FIELD_TYPES.put("padded-string", new ParameterParser() {
            @Override
            final BinaryElementType parseParameters(ParseUtil.IndentationSensitiveLexer lexer) throws IOException {
                lexer.hor.skipSpaces();
                int size = lexer.hor.readUnsignedInteger("string length");
                lexer.hor.skipSpaces();
                int beforePadding = lexer.hor.getPos();
                int padding = lexer.hor.readUnsignedInteger("char code");
                if (padding >= 0x100) {
                    lexer.hor.errorAtPos(beforePadding, "value too high to be a char code");
                }
                lexer.passLogicalNewline();
                return new BinaryElementType.PaddedString(size, (byte) padding);
            }
        });
        
        KNOWN_FIELD_TYPES.put("struct", new ParameterParser() {
            @Override
            final BinaryElementType parseParameters(IndentationSensitiveLexer lexer) throws IOException {
                ArrayList<BinaryElementType.Struct.Step> steps = new ArrayList<BinaryElementType.Struct.Step>();
                lexer.passLogicalNewline();
                lexer.passIndent();
                
                while (!lexer.atDedent()) {
                    lexer.noIndent();
                    if (lexer.hor.at('@')) {
                        Step seek = parseThisSeek(lexer);
                        steps.add(seek);
                        lexer.hor.skipSpaces();
                    }
                    if (!(lexer.hor.atEndOfLine() || lexer.atCommentChar())) {
                        ArrayList<BinaryElementType.Struct.Step> lineSteps = new ArrayList<BinaryElementType.Struct.Step>();
                        while (true) {
                            String fieldName = lexer.hor.readDashedWord("field name");
                            lineSteps.add(new Step.Pass(fieldName, null));
                            lexer.hor.skipSpaces();
                            if (!lexer.hor.at(',')) {
                                break;
                            }
                            lexer.hor.skipChar();
                            lexer.hor.skipSpaces();
                            if (lexer.hor.at('@')) {
                                Step seek = parseThisSeek(lexer);
                                lineSteps.add(seek);
                                lexer.hor.skipSpaces();
                            }
                        }
                        lexer.hor.pass(':');
                        lexer.hor.skipSpaces();
                        BinaryElementType fieldType = parseType(lexer);
                        for (Step step : lineSteps) {
                            step.setType(fieldType);
                        }
                        steps.addAll(lineSteps);
                    } else {
                        lexer.passLogicalNewline();
                    }
                }
                lexer.discardDedent();
                return new BinaryElementType.Struct(steps.toArray(new BinaryElementType.Struct.Step[0]));
            }
        });
    }
}