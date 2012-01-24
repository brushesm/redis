package net.mirky.redis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import net.mirky.redis.ControlData.LineParseError;
import net.mirky.redis.ParseUtil.IndentationSensitiveLexer;
import net.mirky.redis.ResourceManager.ResolutionError;

public abstract class Struct {
    public final String name;

    public Struct(String name) {
        this.name = name;
    }

    protected final void showBreadcrumbs(Cursor cursor, String path, PrintStream port) {
        port.println(Hex.t(cursor.tell()) + ": " + name + " @ " + path);
    }

    public abstract void show(Cursor cursor, String path, PrintStream port, Decoding decoding) throws ImageError;

    static final Struct.Basic BLANK = new Struct.Basic("blank");

    public static final class Union extends Struct {
        private final Rule[] rules;

        public Union(String name, Rule... rules) {
            super(name);
            if (rules.length == 0 || !(rules[rules.length - 1] instanceof Rule.Always)) {
                throw new RuntimeException("the last rule in a Struct.Conditional must be a Rule.Always");
            }
            this.rules = rules;
        }

        @Override
        public final void show(Cursor cursor, String path, PrintStream port, Decoding decoding) throws ImageError {
            for (Rule rule : rules) {
                if (rule.matches(cursor)) {
                    rule.struct.show(cursor, path, port, decoding);
                    return;
                }
            }
            // No rule matched. This must not happen.
            throw new RuntimeException("bug detected");
        }

        public static abstract class Rule {
            public final Struct struct;

            public Rule(Struct struct) {
                this.struct = struct;
            }

            public abstract boolean matches(Cursor cursor);

            public static final class RegionBlank extends Rule {
                public final int offset;
                public final int size;

                public RegionBlank(int offset, int size, Struct struct) {
                    super(struct);
                    this.offset = offset;
                    this.size = size;
                }

                @Override
                public final boolean matches(Cursor cursor) {
                    return cursor.regionBlank(offset, size);
                }
            }

            public static final class ByteEquals extends Rule {
                public final int offset;
                public final byte etalon;

                public ByteEquals(int offset, byte etalon, Struct struct) {
                    super(struct);
                    this.offset = offset;
                    this.etalon = etalon;
                }

                @Override
                public final boolean matches(Cursor cursor) {
                    return ((byte) cursor.getUnsignedByte(offset)) == etalon;
                }
            }

            public static final class Always extends Rule {
                public Always(Struct struct) {
                    super(struct);
                }

                @Override
                public final boolean matches(Cursor cursor) {
                    return true;
                }
            }
        }
    }

    static final class Basic extends Struct {
        private final Struct.AbstractField[] fields;

        public Basic(String name, AbstractField... fields) {
            super(name);
            this.fields = fields;
        }

        @Override
        public final void show(Cursor cursor, String path, PrintStream port, Decoding decoding) throws ImageError {
            showBreadcrumbs(cursor, path, port);
            for (AbstractField field : fields) {
                field.show(cursor, port, decoding);
            }
        }
    }

    static final class Void extends Struct {
        private final int size;

        public Void(String name, int size) {
            super(name);
            this.size = size;
        }

        @Override
        public final void show(Cursor cursor, String path, PrintStream port, Decoding decoding) throws ImageError {
            showBreadcrumbs(cursor, path, port);
            Hex.dump(cursor.getBytes(0, size), cursor.tell(), decoding, port);
        }
    }

    static abstract class AbstractField {
        public final int offset;
        public final String name;

        public AbstractField(int offset, String name) {
            this.offset = offset;
            this.name = name;
        }

        public final void show(Cursor cursor, PrintStream port, Decoding decoding) throws ImageError {
            StructFieldType.displayFieldPrefix(cursor, offset, name, port);
            showContent(cursor, port, decoding);
            port.println();
        }

        public abstract void showContent(Cursor cursor, PrintStream port, Decoding decoding) throws ImageError;
    }

    static final class OldField extends AbstractField {
        public final StructFieldType type;

        public OldField(int offset, String name, StructFieldType type) {
            super(offset, name);
            this.type = type;
        }

        @Override
        public final void showContent(Cursor cursor, PrintStream port, Decoding decoding) throws ImageError {
            type.showContent(cursor, offset, port, decoding);
        }
    }

    public static final ResourceManager<Struct> MANAGER = new ResourceManager<Struct>("struct") {
        @Override
        public final Struct load(String name, BufferedReader reader) {
            IndentationSensitiveLexer lexer = new ParseUtil.IndentationSensitiveFileLexer(reader, name,
                    '#');
            try {
                ArrayList<AbstractField> fields = new ArrayList<AbstractField>();
                while (!lexer.atEndOfFile()) {
                    lexer.noIndent();
                    lexer.pass('@');
                    int fieldOffset = lexer.parseUnsignedInteger("offset");
                    lexer.skipSpaces();
                    String fieldName = lexer.parseString("field name");
                    lexer.skipSpaces();
                    lexer.pass(':');
                    lexer.skipSpaces();
                    StructFieldType fieldType = parseFieldType(lexer);
                    fields.add(new OldField(fieldOffset, fieldName, fieldType));
                }
                reader.close();
                return new Struct.Basic(name, fields.toArray(new AbstractField[0]));
            } catch (IOException e) {
                throw new RuntimeException("I/O error reading resource " + name, e);
            } catch (ControlData.LineParseError e) {
                throw new RuntimeException("parse error reading resource " + name, e);
            }
        }

        /**
         * Parse a field type specification and advance past its last line.
         * 
         * @param lexer
         * @return the type object
         * @throws LineParseError
         * @throws IOException
         */
        private final StructFieldType parseFieldType(IndentationSensitiveLexer lexer)
                throws LineParseError, IOException {
            if (!lexer.atWord()) {
                lexer.complain("expected field type");
            }
            String fieldType = lexer.parseThisDashedWord();
            if (fieldType.equals("unsigned-byte")) {
                lexer.passNewline();
                return StructFieldType.UNSIGNED_BYTE;
            } else if (fieldType.equals("unsigned-lewyde")) {
                lexer.passNewline();
                return StructFieldType.UNSIGNED_LEWYDE;
            } else if (fieldType.equals("d64-sector-chain-start")) {
                lexer.passNewline();
                return StructFieldType.D64_SECTOR_CHAIN_START;
            } else if (fieldType.equals("padded-string")) {
                lexer.skipSpaces();
                int size = lexer.parseUnsignedInteger("string length");
                lexer.skipSpaces();
                int padding = lexer.parseUnsignedInteger("char code");
                if (padding >= 0x100) {
                    lexer.complain("value too high to be a char code");
                }
                lexer.passNewline();
                return new StructFieldType.PaddedString(size, (byte) padding);
            } else if (fieldType.equals("sliced-byte")) {
                lexer.skipSpaces();
                lexer.passNewline();
                lexer.passIndent();
                ArrayList<IntegerSlice> slices = new ArrayList<IntegerSlice>();
                while (!lexer.atDedent()) {
                    lexer.noIndent();
                    slices.add(parseIntegerSlice(lexer));
                }
                lexer.skipThisDedent();
                return new StructFieldType.SlicedByteField(slices.toArray(new IntegerSlice[0]));
            } else {
                lexer.complain("unknown field type");
                // {@link
                // ParseUtil.IndentationSensitiveFileLexer#complain(String)}
                // returned?
                throw new RuntimeException("bug detected");
            }
        }
        
        private final IntegerSlice parseIntegerSlice(IndentationSensitiveLexer lexer) throws LineParseError, IOException {
            lexer.pass('@');
            lexer.pass('.');
            int rightShift = lexer.parseUnsignedInteger("right shift");
            lexer.skipSpaces();
            IntegerSlice slice;
            if (lexer.atUnsignedInteger()) {
                // it's a basic field; the field width (in bits) comes next
                int fieldWidth = lexer.parseUnsignedInteger("field width");
                if (fieldWidth == 0) {
                    lexer.complain("zero-bit field?");
                }
                List<String> meanings = new ArrayList<String>();
                while (true) {
                    lexer.skipSpaces();
                    if (!lexer.at('"')) {
                        break;
                    }
                    meanings.add(lexer.parseThisString());
                }
                slice = new IntegerSlice.Basic(rightShift, fieldWidth, meanings.toArray(new String[0]));
            } else {
                // it's a flag field; the field width is implicitly one
                String setMessage;
                if (lexer.at('"')) {
                    setMessage = lexer.parseThisString();
                } else {
                    setMessage = null;
                }
                lexer.skipSpaces();
                String clearMessage;
                if (lexer.at('/')) {
                    lexer.skipChar();
                    lexer.skipSpaces();
                    clearMessage = lexer.parseString("cleared flag meaning");
                } else {
                    clearMessage = null;
                }
                if (setMessage == null && clearMessage == null) {
                    lexer.complain("expected bit meaning");
                }
                slice = new IntegerSlice.Flag(rightShift, setMessage, clearMessage);
            }
            lexer.passNewline();
            return slice;
        }
    };

    public static final Struct D64_DIRENT_UNION;

    static {
        try {
            D64_DIRENT_UNION = new Struct.Union("d64-dirent-union",
                    new Union.Rule.RegionBlank(2, 30, BLANK),
                    new Union.Rule.ByteEquals(2, (byte) 0, new Void("d64-dirent-blank", 32)),
                    new Union.Rule.Always(Struct.MANAGER.get("d64-dirent")));
        } catch (ResolutionError e) {
            throw new RuntimeException("Bug detected", e);
        }
        MANAGER.cache.put("d64-dirent-union", D64_DIRENT_UNION);
    }
}
