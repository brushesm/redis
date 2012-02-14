package net.mirky.redis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import net.mirky.redis.ControlData.LineParseError;
import net.mirky.redis.ResourceManager.ResolutionError;
import net.mirky.redis.analysers.ZXSBasicProgramAnalyser;

public final class Disassembler {
    private final byte[] data;
    private final Format format;
    private final API api;
    private final boolean[] undeciphered;
    private final Map<Integer, TreeMap<String, DecipheredInstruction>> deciphered;

    /**
     * Set of instructions referred by the format or other instructions.
     */
    private final HashSet<Integer> entryPoints;
    /**
     * Addresses that would be considered entry points because they are referred
     * to from the disassembled code but aren't because they lie outside the
     * code's boundaries. Multiple languages per interest point are supported.
     */
    private final TreeMap<Integer, TreeSet<String>> externalPointsOfInterest;

    /** The breadth-first traversal queue */
    private final LinkedList<PendingEntryPoint> queue;

    // Disassembler's internal state
    private int currentOffset;
    private final LangSequencer sequencer;
    private int currentInstructionSize;
    private int currentValue;

    /*
     * Bytecode values for the internal bytecode. Note that the bytecode is
     * currently not considered a medium for data storage or external
     * communication; it *will* change significantly and is not suitable for
     * external data storage yet.
     */

    static final class Bytecode {
        private Bytecode() {
            // not a real constructor
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.FIELD)
        private static @interface DeciphererStep {
            String name();

            // If -1, any non-zero preceding size of {@code currentValue} is
            // considered acceptable.
            // If >= 0, this operator can only be used when {@code currentValue}
            // has such a size.
            int sizeRequirement();

            // If -1, this operator does not change the size of {@code
            // currentValue}. If >= 0,
            // new size of {@code currentValue} after execution of this
            // operator.
            int sizeAfter() default -1;
        }

        // End processing of the current instruction.
        static final byte COMPLETE = 0x00;

        // Output the current integer value as a decimal number.
        static final byte DECIMAL = 0x01;

        // Add the current instruction's address and one to the current integer
        // value.
        @DeciphererStep(name = "signedrel 1", sizeRequirement = 1, sizeAfter = 2)
        static final byte BYTE_SIGNEDREL_1 = 0x02;

        // Add the current instruction's address and two to the current integer
        // value.
        @DeciphererStep(name = "signedrel 2", sizeRequirement = 1, sizeAfter = 2)
        static final byte BYTE_SIGNEDREL_2 = 0x03;

        // Mark the address pointed by current integer value an entry point in
        // the current language.
        @DeciphererStep(name = "entry", sizeRequirement = -1)
        static final byte ENTRY_POINT_REFERENCE = 0x04;

        // Ditto, plus check if the address has a special meaning in the current
        // API.
        // Call to such a special subroutine may be considered terminal (that
        // is, non-returning)
        // by the disassembler, or it may cause the disassembler to switch
        // language.
        @DeciphererStep(name = "subrentry", sizeRequirement = -1)
        static final byte SUBROUTINE_ENTRY_POINT_REFERENCE = 0x05;

        // Mark the address pointed by current integer value an entry point in
        // the 'byte' language.
        @DeciphererStep(name = "entry byte", sizeRequirement = -1)
        static final byte BYTE_ENTRY_POINT_REFERENCE = 0x06;

        // Mark the address pointed by current integer value an entry point in
        // the 'lewyde' language.
        @DeciphererStep(name = "entry lewyde", sizeRequirement = -1)
        static final byte LEWYDE_ENTRY_POINT_REFERENCE = 0x07;

        // Output the current integer value as an unsigned hex byte.
        static final byte UNSIGNED_BYTE = 0x08;

        // Output the current integer value as an unsigned hex wyde.
        static final byte UNSIGNED_WYDE = 0x09;

        // Output the current integer value as a signed hex byte.
        static final byte SIGNED_BYTE = 0x0A;

        // Output the current integer value as a signed hex wyde.
        static final byte SIGNED_WYDE = 0x0B;

        // AND the current integer value with 0x38.
        static final byte AND_0x38 = 0x0C;

        // AND the current integer value with 3.
        static final byte AND_3 = 0x0D;

        // AND the current integer value with 7.
        static final byte AND_7 = 0x0E;

        // Unsigned-shift the current integer value right by 3 bits.
        @DeciphererStep(name = "shr 3", sizeRequirement = -1)
        static final byte SHR_3 = 0x10;

        // Unsigned-shift the current integer value right by 4 bits.
        @DeciphererStep(name = "shr 4", sizeRequirement = -1)
        static final byte SHR_4 = 0x11;

        // Unsigned-shift the current integer value right by 5 bits.
        @DeciphererStep(name = "shr 5", sizeRequirement = -1)
        static final byte SHR_5 = 0x12;

        // Unsigned-shift the current integer value right by 6 bits.
        @DeciphererStep(name = "shr 6", sizeRequirement = -1)
        static final byte SHR_6 = 0x13;

        // Look the current integer value up in the minitable with the given
        // number,
        // and output the resulting string.
        static final byte MINITABLE_LOOKUP_0 = 0x18;
        static final int MAX_MINITABLE_COUNT = 8;

        // Fetch a byte or little-endian wyde, starting from the given offset
        // wrt the current instruction's
        // start, as the new current integer value.
        // Also updates the current instruction's length, if applicable.
        @DeciphererStep(name = "byte 0", sizeRequirement = 0, sizeAfter = 1)
        static final byte GET_BYTE_0 = (byte) 0x80;

        @DeciphererStep(name = "byte 1", sizeRequirement = 0, sizeAfter = 1)
        static final byte GET_BYTE_1 = (byte) 0x81;

        @DeciphererStep(name = "byte 2", sizeRequirement = 0, sizeAfter = 1)
        static final byte GET_BYTE_2 = (byte) 0x82;

        @DeciphererStep(name = "byte 3", sizeRequirement = 0, sizeAfter = 1)
        static final byte GET_BYTE_3 = (byte) 0x83;

        @DeciphererStep(name = "lewyde 0", sizeRequirement = 0, sizeAfter = 2)
        static final byte GET_LEWYDE_0 = (byte) 0x84;

        @DeciphererStep(name = "lewyde 1", sizeRequirement = 0, sizeAfter = 2)
        static final byte GET_LEWYDE_1 = (byte) 0x85;

        @DeciphererStep(name = "lewyde 2", sizeRequirement = 0, sizeAfter = 2)
        static final byte GET_LEWYDE_2 = (byte) 0x86;

        @DeciphererStep(name = "lewyde 3", sizeRequirement = 0, sizeAfter = 2)
        static final byte GET_LEWYDE_3 = (byte) 0x87;

        static final int MAX_SUBOFFSET = 3;

        // Re-dispatch according to a sublanguage:
        static final byte DISPATCH_0 = (byte) 0xA0;
        static final int MAX_REFERRED_LANGUAGE_COUNT = 8;
        
        // Switches and temporary switches:
        static final byte TEMPSWITCH_0 = (byte) 0xA8;

        @DeciphererStep(name = "set-countdown 6", sizeRequirement = 0, sizeAfter = 0)
        static final byte SET_COUNTDOWN_6 = (byte) 0x89;
        
        @DeciphererStep(name = "set-countdown 8", sizeRequirement = 0, sizeAfter = 0)
        static final byte SET_COUNTDOWN_8 = (byte) 0x8A;
        
        @DeciphererStep(name = "set-countdown 12", sizeRequirement = 0, sizeAfter = 0)
        static final byte SET_COUNTDOWN_12 = (byte) 0x8B;
        
        @DeciphererStep(name = "switchback", sizeRequirement = 0, sizeAfter = 0)
        static final byte SWITCH_BACK = (byte) 0x92; // switch back to the last
                                                     // language

        @DeciphererStep(name = "terminate", sizeRequirement = 0, sizeAfter = 0)
        static final byte TERMINATE = (byte) 0x93; // stop after this
                                                   // instruction is complete.
        // (Note that TERMINATE does not imply COMPLETE.)

        // Reserved placeholder code
        static final byte INVALID = (byte) 0xFF;
        
        private static final Map<String, StepDeclaration> initialSteps = new HashMap<String, StepDeclaration>();

        static {
            try {
                for (Field field : Bytecode.class.getDeclaredFields()) {
                    DeciphererStep ann = field.getAnnotation(DeciphererStep.class);
                    if (ann != null) {
                        assert !initialSteps.containsKey(ann.name());
                        initialSteps.put(ann.name(), new StepDeclaration(field.getByte(null), ann.sizeRequirement(),
                                ann.sizeAfter()));
                    }
                }
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("bug detected", e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("bug detected", e);
            }
        }

        static final StepDeclaration resolveInitialStep(String step) {
            return initialSteps.get(step);
        }

        static class StepDeclaration {
            final byte code;
            final int sizeRequirement;
            final int sizeAfter;

            StepDeclaration(int code, int sizeRequirement, int sizeAfter) {
                this.code = (byte) code;
                this.sizeRequirement = sizeRequirement;
                this.sizeAfter = sizeAfter;
            }

            final boolean typeMatches(int size) {
                if (sizeRequirement == -1) {
                    return size != 0;
                } else {
                    return size == sizeRequirement;
                }
            }
        }
    }

    // Constructs a disassembler. The given format is used to determine the
    // data's loading
    // origin and the API (that is, the special subroutines and their meanings).
    public Disassembler(byte[] data, Format format) {
        this.data = data;
        this.format = format;
        api = (API) ((Format.Option.SimpleOption) format.getOption("api")).value;
        undeciphered = new boolean[data.length];
        for (int i = 0; i < data.length; i++) {
            undeciphered[i] = true;
        }
        deciphered = new HashMap<Integer, TreeMap<String, DecipheredInstruction>>();
        entryPoints = new HashSet<Integer>();
        queue = new LinkedList<PendingEntryPoint>();
        externalPointsOfInterest = new TreeMap<Integer, TreeSet<String>>();
        currentOffset = -1;
        sequencer = new LangSequencer();
        currentInstructionSize = 0;
    }

    private final void storeInstructionAndPass(String lang, String asString) {
        addInstructionEntry(lang, asString, currentInstructionSize);
        for (int i = 0; i < currentInstructionSize; i++) {
            undeciphered[currentOffset + i] = false;
        }
        currentOffset += currentInstructionSize;
        currentInstructionSize = 0;
    }

    private final void addInstructionEntry(String lang, String asString, int size) {
        TreeMap<String, DecipheredInstruction> point = deciphered.get(new Integer(currentOffset));
        if (point == null) {
            point = new TreeMap<String, DecipheredInstruction>();
            deciphered.put(new Integer(currentOffset), point);
        }
        assert !point.containsKey(lang) || lang.equals("!") || lang.equals("!!");
        point.put(lang, new DecipheredInstruction(size, asString));
    }

    final void recordProblem(String message) {
        addInstructionEntry("!", "! " + message, 0);
    }

    final boolean haveProcessed(int offset, String langName) {
        TreeMap<String, DecipheredInstruction> point = deciphered.get(new Integer(offset));
        return point != null && point.containsKey(langName);
    }

    final int getUnsignedLewyde(int suboffset) throws IncompleteInstruction {
        updateInstructionSize(suboffset + 2);
        int base = currentOffset + suboffset;
        if (base < 0 || data.length - 2 < base) {
            throw new IncompleteInstruction();
        }
        return (data[base] & 0xFF) + (data[base + 1] & 0xFF) * 256;
    }

    private final void updateInstructionSize(int min) {
        if (min > currentInstructionSize) {
            currentInstructionSize = min;
        }
    }

    final int getUnsignedByte(int suboffset) throws IncompleteInstruction {
        updateInstructionSize(suboffset + 1);
        int base = currentOffset + suboffset;
        if (base < 0 || base >= data.length) {
            throw new IncompleteInstruction();
        }
        return data[base] & 0xFF;
    }

    public final void noteAbsoluteEntryPoint(int address, Disassembler.Lang lang) {
        // special case
        if (lang == Lang.NONE) {
            return;
        }
        int offset = address - format.getOrigin();
        if (offset >= 0 && offset < data.length) {
            entryPoints.add(new Integer(offset));
            if (!haveProcessed(offset, lang.name)) {
                queue.add(new PendingEntryPoint(offset, new LangSequencer.Frame[]{new LangSequencer.Frame(
                        lang.defaultCountdown, lang)}));
            }
        } else {
            TreeSet<String> set = externalPointsOfInterest.get(new Integer(address));
            if (set == null) {
                set = new TreeSet<String>();
                externalPointsOfInterest.put(new Integer(address), set);
            }
            set.add(lang.name);
        }
    }

    public final void run() throws RuntimeException {
        while (!queue.isEmpty()) {
            PendingEntryPoint entryPoint = queue.removeFirst();
            currentOffset = entryPoint.offset;
            sequencer.init(entryPoint.sequencerFrames);
            DECIPHERING_LOOP : do {
                if (haveProcessed(currentOffset, sequencer.getCurrentLang().name)) {
                    break DECIPHERING_LOOP;
                }
                try {
                    try {
                        StringBuilder sb = new StringBuilder();
                        sequencer.getCurrentLang().decipher(this, getUnsignedByte(0), sb);
                        storeInstructionAndPass(sequencer.getCurrentLang().name, sb.toString());
                        sequencer.advance();
                    } catch (Lang.UnknownOpcode e) {
                        this.recordProblem("unknown " + e.lang.name + " opcode");
                        StringBuilder sb = new StringBuilder();
                        sb.setLength(0);
                        sb.append("byte ");
                        for (int i = 0; i < currentInstructionSize; i++) {
                            if (i != 0) {
                                sb.append(", ");
                            }
                            sb.append("0x");
                            sb.append(Hex.b(this.getUnsignedByte(i)));
                        }
                        this.storeInstructionAndPass("!!", sb.toString());
                        break DECIPHERING_LOOP;
                    }
                } catch (IncompleteInstruction e) {
                    recordProblem("abrupt end of " + sequencer.getCurrentLang().name + " code");
                    break DECIPHERING_LOOP;
                }
            } while (sequencer.sequencerHasMore());
        }
    }

    // Run the given internal bytecode on this Disassembler instance and append
    // the output
    // to the given StringBuilder (retaining its previous content, if any).
    // Throws IncompleteInstruction if the end of the binary object in the
    // Disassembler is encountered
    // before the current instruction ends, or UnknownOpcode if Lang dispatch
    // fails (which is not
    // necessarily dispatch by the first byte in this instruction; some
    // languages have instructions
    // with multiple dispatches).
    final void decipher(byte[] code, int startPosition, Lang.Tabular.Linkage linkage, StringBuilder sb) throws RuntimeException,
            IncompleteInstruction, Lang.UnknownOpcode {
        currentValue = 0; // just in case
        for (int i = startPosition;; i++) {
            byte step = code[i];
            if (step >= 0x20 && step <= 0x7E) {
                sb.append((char) step);
            } else if (step >= Bytecode.MINITABLE_LOOKUP_0
                    && step < Bytecode.MINITABLE_LOOKUP_0 + Bytecode.MAX_MINITABLE_COUNT) {
                String[] minitable = linkage.minitables[step - Bytecode.MINITABLE_LOOKUP_0];
                // note that we're checking this at the minitable construction
                // time
                assert minitable.length > 0 && (minitable.length & (minitable.length - 1)) == 0;
                // mask off excess bits, then fetch a string from the minitable
                sb.append(minitable[currentValue & (minitable.length - 1)]);
            } else if (step >= Bytecode.DISPATCH_0
                    && step < Bytecode.DISPATCH_0 + Bytecode.MAX_REFERRED_LANGUAGE_COUNT) {
                String newLangName = linkage.referredLanguages[step - Bytecode.DISPATCH_0];
                // note the late binding
                try {
                    Lang.MANAGER.get(newLangName).decipher(this, currentValue, sb);
                } catch (ResourceManager.ResolutionError e) {
                    throw new RuntimeException("referred language unknown", e);
                }
            } else if (step >= Bytecode.TEMPSWITCH_0
                    && step < Bytecode.TEMPSWITCH_0 + Bytecode.MAX_REFERRED_LANGUAGE_COUNT) {
                String newLangName = linkage.referredLanguages[step - Bytecode.TEMPSWITCH_0];
                // note the late binding
                try {
                    sequencer.switchTemporarily(Lang.MANAGER.get(newLangName));
                } catch (ResourceManager.ResolutionError e) {
                    throw new RuntimeException("referred language unknown", e);
                }
            } else if (step >= Bytecode.GET_BYTE_0 && step <= Bytecode.GET_BYTE_0 + Bytecode.MAX_SUBOFFSET) {
                currentValue = getUnsignedByte(step - Bytecode.GET_BYTE_0);
            } else if (step >= Bytecode.GET_LEWYDE_0 && step <= Bytecode.GET_LEWYDE_0 + Bytecode.MAX_SUBOFFSET) {
                currentValue = getUnsignedLewyde(step - Bytecode.GET_LEWYDE_0);
            } else {
                switch (step) {
                    case Bytecode.SHR_3:
                        currentValue >>>= 3;
                        break;

                    case Bytecode.SHR_4:
                        currentValue >>>= 4;
                        break;

                    case Bytecode.SHR_5:
                        currentValue >>>= 5;
                        break;

                    case Bytecode.SHR_6:
                        currentValue >>>= 6;
                        break;

                    case Bytecode.ENTRY_POINT_REFERENCE:
                        noteAbsoluteEntryPoint(currentValue, sequencer.getCurrentLang());
                        break;

                    case Bytecode.SUBROUTINE_ENTRY_POINT_REFERENCE:
                        noteAbsoluteEntryPoint(currentValue, sequencer.getCurrentLang());
                        /*
                         * XXX: Note that we don't care which language is used
                         * to call the API entry point; all can cause the
                         * switch. This can theoretically cause false positives.
                         * In practice, they would be quite convoluted and
                         * reasonably unlikely.
                         */
                        SequencerEffect effect = api.getSequencerEffect(currentValue);
                        if (effect != null) {
                            effect.affectSequencer(sequencer);
                        }
                        break;

                    case Bytecode.BYTE_ENTRY_POINT_REFERENCE:
                        try {
                            noteAbsoluteEntryPoint(currentValue, Lang.MANAGER.get("byte"));
                        } catch (ResourceManager.ResolutionError e) {
                            throw new RuntimeException("bug detected", e);
                        }
                        break;

                    case Bytecode.LEWYDE_ENTRY_POINT_REFERENCE:
                        try {
                            noteAbsoluteEntryPoint(currentValue, Lang.MANAGER.get("lewyde"));
                        } catch (ResourceManager.ResolutionError e) {
                            throw new RuntimeException("bug detected", e);
                        }
                        break;

                    case Bytecode.UNSIGNED_BYTE:
                        sb.append("0x");
                        sb.append(Hex.b(currentValue));
                        break;

                    case Bytecode.UNSIGNED_WYDE:
                        sb.append("0x");
                        sb.append(Hex.w(currentValue));
                        break;

                    case Bytecode.SIGNED_BYTE:
                        if ((currentValue & 0x80) == 0) {
                            currentValue &= 0x7F;
                        } else {
                            currentValue |= ~0x7F;
                            currentValue = -currentValue;
                            sb.append('-');
                        }
                        sb.append("0x");
                        sb.append(Hex.b(currentValue));
                        break;

                    case Bytecode.SIGNED_WYDE:
                        if ((currentValue & 0x8000) == 0) {
                            currentValue &= 0x7FFF;
                        } else {
                            currentValue |= ~0x7FFF;
                            currentValue = -currentValue;
                            sb.append('-');
                        }
                        sb.append("0x");
                        sb.append(Hex.w(currentValue));
                        break;

                    case Bytecode.TERMINATE:
                        sequencer.switchPermanently(Lang.NONE);
                        break;

                    case Bytecode.SET_COUNTDOWN_6:
                        sequencer.setCountdown(6);
                        break;
                        
                    case Bytecode.SET_COUNTDOWN_8:
                        sequencer.setCountdown(8);
                        break;
                        
                    case Bytecode.SET_COUNTDOWN_12:
                        sequencer.setCountdown(12);
                        break;
                        
                    case Bytecode.SWITCH_BACK:
                        sequencer.switchBack();
                        break;

                    case Bytecode.BYTE_SIGNEDREL_1:
                        if ((currentValue & 0x80) == 0) {
                            currentValue &= 0x7F;
                        } else {
                            currentValue |= ~0x7F;
                        }
                        currentValue += format.getOrigin() + currentOffset + 1;
                        break;

                    case Bytecode.BYTE_SIGNEDREL_2:
                        if ((currentValue & 0x80) == 0) {
                            currentValue &= 0x7F;
                        } else {
                            currentValue |= ~0x7F;
                        }
                        currentValue += format.getOrigin() + currentOffset + 2;
                        break;

                    case Bytecode.AND_3:
                        currentValue &= 3;
                        break;

                    case Bytecode.AND_7:
                        currentValue &= 7;
                        break;

                    case Bytecode.AND_0x38:
                        currentValue &= 0x38;
                        break;

                    case Bytecode.DECIMAL:
                        sb.append(currentValue);
                        break;

                    case Bytecode.COMPLETE:
                        return;

                    default:
                        throw new RuntimeException("bug detected");
                }
            }
        }
    }

    /**
     * Print results of the disassembly to given port.
     * 
     * @param port
     */
    public final void printResults(PrintStream port) {
        TreeSet<Integer> decipheredKeys = new TreeSet<Integer>(deciphered.keySet());
        int lastOffset = 0;
        String lastLang = "<none>";
        for (Integer boxedOffset : decipheredKeys) {
            int offset = boxedOffset.intValue();
            if (offset > lastOffset) {
                port.println();
                lastOffset = offset;
            }
            TreeMap<String, DecipheredInstruction> instructions = deciphered.get(boxedOffset);
            for (Map.Entry<String, DecipheredInstruction> entry : instructions.entrySet()) {
                String lang = entry.getKey();
                DecipheredInstruction instruction = entry.getValue();
                if (offset < lastOffset) {
                    port.println("          ! retreat " + (lastOffset - offset));
                    lastOffset = offset;
                }
                try {
                    if (!lang.equals(lastLang) && lang.charAt(0) != '!' && !Lang.MANAGER.get(lang).isTrivial()) {
                        port.println("          .switch " + lang);
                        lastLang = lang;
                    }
                } catch (ResourceManager.ResolutionError e) {
                    // We've already used this lang for disassembly. Why would
                    // retrieving it again fail?
                    throw new RuntimeException("bug detected", e);
                }
                if (entryPoints.contains(new Integer(offset))) {
                    port.print(Hex.t(offset + format.getOrigin()));
                } else {
                    port.print("      " + Hex.b(offset + format.getOrigin()));
                }
                port.println(": " + instruction.asString);
                lastOffset = offset + instruction.size;
            }
        }
        port.println();
        port.println("External points of interest:");
        if (externalPointsOfInterest.isEmpty()) {
            port.println("    (none)");
        } else {
            for (Map.Entry<Integer, TreeSet<String>> entry : externalPointsOfInterest.entrySet()) {
                port.print(Hex.t(entry.getKey().intValue()) + ": ");
                boolean first = true;
                for (String langName : entry.getValue()) {
                    if (!first) {
                        port.print(", ");
                    }
                    port.print(langName);
                    first = false;
                }
                port.println();
            }
        }
        port.println();
        Hex.dump(data, format.getOrigin(), format.getDecoding(), undeciphered, port);
    }

    // An offset-lang pair, used to queue entry points not yet processed.
    static final class PendingEntryPoint {
        final int offset;
        final LangSequencer.Frame[] sequencerFrames;

        PendingEntryPoint(int offset, LangSequencer.Frame[] sequencerFrames) {
            this.offset = offset;
            this.sequencerFrames = sequencerFrames;
        }
    }

    // A size-string pair, used to store disassembled instructions.
    static final class DecipheredInstruction {
        final int size;
        final String asString;

        DecipheredInstruction(int size, String asString) {
            this.size = size;
            this.asString = asString;
        }
    }

    /**
     * A {@link Lang} roughly represents a particular bytecode/machine code
     * language such as {@code i8080} or {@code m68000}. Most of the actual
     * languages are stored in *.lang resource files, which are translated into
     * an internal bytecode when the {@link Lang} instance is constructed. (See
     * {@link Lang.Tabular} for details.)
     * 
     * {@link Lang}s are equity-comparable by their identity, and hashable and
     * ordering-comparable by their name. This allows them to be stored in both
     * hashed and tree:d sets. Note that {@link Lang} names are unique because
     * of the caching done by {@link Lang#get(String)}.
     */
    public static abstract class Lang implements Comparable<Lang> {
        final String name;
        final int defaultCountdown;

        private Lang(String name, int defaultCountdown) {
            this.name = name;
            this.defaultCountdown = defaultCountdown;
        }

        @Override
        public final int hashCode() {
            return name.hashCode();
        }

        @Override
        public final boolean equals(Object that) {
            return this == that;
        }

        public final int compareTo(Lang that) {
            return this.name.compareTo(that.name);
        }

        /**
         * Checks triviality status of the language. Switches to a trivial
         * language and back are not explicitly marked in disassembler's output.
         * This is handy for raw value languages.
         * 
         * @return whether the language is trivial
         */
        abstract boolean isTrivial();

        abstract void decipher(Disassembler disassembler, int opcode, StringBuilder sb) throws UnknownOpcode,
                IncompleteInstruction;

        void dumpLang(PrintStream port) {
            port.println(name + " is a builtin language");
        }

        @SuppressWarnings("synthetic-access")
        static final Lang NONE = new Lang("none", 0) {
            @Override
            final void decipher(Disassembler disassembler, int opcode, StringBuilder sb) {
                // should never be called -- the disassembler should check
                // against NONE
                throw new RuntimeException("bug detected");
            }

            @Override
            final boolean isTrivial() {
                return true;
            }
        };

        @SuppressWarnings("synthetic-access")
        static final Lang CONDENSED_ZXSNUM = new Lang("condensed-zxsnum", 1) {
            @Override
            final void decipher(Disassembler disassembler, int firstCondensedByte, StringBuilder sb)
                    throws IncompleteInstruction {
                int significandByteCount = (firstCondensedByte >> 6) + 1;
                byte condensedExponent = (byte) (firstCondensedByte & 0x3F);
                byte[] bytes = new byte[]{0, 0, 0, 0, 0};
                int significandOffset;
                if (condensedExponent == 0) {
                    bytes[0] = (byte) disassembler.getUnsignedByte(1);
                    significandOffset = 2;
                } else {
                    bytes[0] = condensedExponent;
                    significandOffset = 1;
                }
                bytes[0] += 0x50;
                for (int i = 0; i < significandByteCount; i++) {
                    bytes[i + 1] = (byte) disassembler.getUnsignedByte(significandOffset + i);
                }
                sb.append("byte ");
                for (int i = 0; i < significandOffset + significandByteCount; i++) {
                    if (i != 0) {
                        sb.append(", ");
                    }
                    sb.append("0x");
                    sb.append(Hex.b(disassembler.getUnsignedByte(i)));
                }
                ZXSBasicProgramAnalyser.ZXSpectrumNumber number = new ZXSBasicProgramAnalyser.ZXSpectrumNumber(bytes);
                sb.append(" // ");
                number.prepareForDisassemblyDisplay(sb);
            }

            @Override
            final boolean isTrivial() {
                return true;
            }
        };

        static final class Tabular extends Lang {
            static final Pattern SPACED_COMMA = Pattern.compile("\\s*,\\s*");

            private final boolean trivial;
            private final Tabular.Linkage linkage;
            private final byte[] bytecode;
            private final int[] dispatchTable;
            /*
             * Note that the dispatch suboffset declaration is only used for
             * dumping the parsed language table. The actual dispatch key is
             * either implicitly fetched from suboffset zero, or explicitly
             * declared by the parent language -- possibly without any reference
             * to a fixed suboffset at all.
             */
            private final int dispatchSuboffset;

            @SuppressWarnings("synthetic-access")
            private Tabular(String name, int defaultCountdown, boolean trivial, byte[] bytecode, int[] dispatchTable, Tabular.Linkage linkage, LangParser parser) {
                super(name, defaultCountdown);
                assert dispatchTable.length == 256;
                assert linkage.minitables.length <= Bytecode.MAX_MINITABLE_COUNT;
                this.trivial = trivial;
                this.linkage = linkage;
                this.dispatchSuboffset = parser.dispatchSuboffset;
                this.bytecode = bytecode;
                this.dispatchTable = dispatchTable;
            }

            static final Tabular loadTabular(String name, BufferedReader reader) throws IOException, LangParser.DisassemblyTableParseError, LineParseError {
                LangParser parser = new LangParser();
                parser.parse(name, reader);
                return new Tabular(name, parser.defaultCountdown, parser.trivial, parser.bytecode, parser.dispatchTable, parser.linkage, parser);
            }

            @Override
            final void decipher(Disassembler disassembler, int opcode, StringBuilder sb) throws UnknownOpcode,
                    IncompleteInstruction {
                if (dispatchTable[opcode] == -1) {
                    throw new Lang.UnknownOpcode(this);
                }
                disassembler.decipher(bytecode, dispatchTable[opcode], linkage, sb);
            }

            @Override
            final boolean isTrivial() {
                return trivial;
            }

            @Override
            final void dumpLang(PrintStream port) {
                port.println("# " + name + " is a tabular language");
                port.println("Dispatch-suboffset: " + dispatchSuboffset);
                port.println("Default-countdown: " + defaultCountdown);
                if (trivial) {
                    port.println("Trivial: true");
                }
                for (int i = 0; i < linkage.minitables.length; i++) {
                    String[] minitable = linkage.minitables[i];
                    if (minitable != null) {
                        port.print("minitable minitable#" + i + ":");
                        for (int j = 0; j < minitable.length; j++) {
                            port.print(' ');
                            port.print(minitable[j]);
                        }
                        port.println();
                    }
                }
                for (int i = 0; i < linkage.referredLanguages.length; i++) {
                    if (linkage.referredLanguages[i] != null) {
                        port.println("# referred lang " + i + " is " + linkage.referredLanguages[i]);
                    }
                }
                for (int i = 0; i < 256; i++) {
                    if ((i & 0x0F) == 0) {
                        port.println();
                    }
                    port.print("0x" + Hex.b(i) + ' ');
                    if (dispatchTable[i] != -1) {
                        port.print("(@" + dispatchTable[i] + ") ");
                        dumpDecipherer(i, dispatchTable[i], port);
                        port.println();
                    } else {
                        port.println('-');
                    }
                }
                port.println();
                try {
                    Hex.dump(bytecode, 0, Decoding.MANAGER.get("ascii"), port);
                } catch (ResolutionError e) {
                    throw new RuntimeException("The ASCII decoding is missing?");
                }
            }

            private final void dumpDecipherer(int opcode, int startPosition, PrintStream port) {
                boolean broketed = false;
                DECIPHERER_LOOP : for (int i = startPosition; bytecode[i] != Bytecode.COMPLETE; i++) {
                    byte b = bytecode[i];
                    if (b == Disassembler.Bytecode.GET_BYTE_0 + dispatchSuboffset) {
                        int currentValue = opcode;
                        for (int k = i + 1; k < bytecode.length; k++) {
                            if (bytecode[k] == Disassembler.Bytecode.SHR_3) {
                                currentValue >>>= 3;
                            } else if (bytecode[k] == Disassembler.Bytecode.SHR_4) {
                                currentValue >>>= 4;
                            } else if (bytecode[k] == Disassembler.Bytecode.SHR_5) {
                                currentValue >>>= 5;
                            } else if (bytecode[k] == Disassembler.Bytecode.SHR_6) {
                                currentValue >>>= 6;
                            } else if (bytecode[k] >= Disassembler.Bytecode.MINITABLE_LOOKUP_0
                                    && bytecode[k] < Disassembler.Bytecode.MINITABLE_LOOKUP_0
                                            + Disassembler.Bytecode.MAX_MINITABLE_COUNT) {
                                String[] minitable = linkage.minitables[bytecode[k]
                                        - Disassembler.Bytecode.MINITABLE_LOOKUP_0];
                                if (minitable == null) {
                                    break;
                                }
                                if (broketed) {
                                    port.print('>');
                                    broketed = false;
                                }
                                port.print(minitable[currentValue & (minitable.length - 1)]);
                                i = k;
                                continue DECIPHERER_LOOP;
                            } else {
                                break;
                            }
                        }
                    }
                    if (b >= 0x20 && b <= 0x7E) {
                        if (broketed) {
                            port.print('>');
                            broketed = false;
                        }
                        port.print((char) b);
                    } else {
                        if (!broketed) {
                            port.print('<');
                            broketed = true;
                        } else {
                            port.print(", ");
                        }
                        port.print("0x" + Hex.b(b));
                    }
                }
                if (broketed) {
                    port.print('>');
                }
            }

            static final class BytecodeCollector {
                private final ArrayList<Byte> steps;

                BytecodeCollector() {
                    steps = new ArrayList<Byte>();
                }

                final void add(byte code) {
                    steps.add(new Byte(code));
                }

                final byte[] finish() {
                    byte[] bytecode = new byte[steps.size()];
                    for (int i = 0; i < bytecode.length; i++) {
                        bytecode[i] = steps.get(i).byteValue();
                    }
                    return bytecode;
                }

                final int currentPosition() {
                    return steps.size();
                }
            }

            static final class Linkage {
                final String[][] minitables;
                final String[] referredLanguages;
                
                Linkage() {
                    minitables = new String[Disassembler.Bytecode.MAX_MINITABLE_COUNT][];
                    referredLanguages = new String[Disassembler.Bytecode.MAX_REFERRED_LANGUAGE_COUNT];
                }
            }
        }

        public static final ResourceManager<Lang> MANAGER = new ResourceManager<Lang>("lang") {
            @Override
            public final Lang load(String name, BufferedReader reader) throws IOException {
                try {
                    return Tabular.loadTabular(name, reader);
                } catch (LangParser.DisassemblyTableParseError e) {
                    throw new RuntimeException("parse error reading lang " + name, e);
                } catch (LineParseError e) {
                    throw new RuntimeException("parse error reading lang " + name, e);
                }
            }
        };
        
        static {
            MANAGER.registerSpecial("none", Lang.NONE);
            MANAGER.registerSpecial("condensed-zxsnum", Lang.CONDENSED_ZXSNUM);
        }
        

        /**
         * Thrown when a bytecode table lookup fails. {@link #run()} catches it,
         * terminates disassembly of the current sequence, and makes sure that
         * all fetched bytes of the current instruction are listed as raw bytes
         * for the user to be able to see what could not be parsed.
         */
        static final class UnknownOpcode extends Exception {
            final Lang lang;

            UnknownOpcode(Lang lang) {
                this.lang = lang;
            }
        }
    }

    static abstract class SequencerEffect {
        abstract void affectSequencer(LangSequencer sequencer);

        static final class Terminate extends SequencerEffect {
            @Override
            final void affectSequencer(LangSequencer sequencer) {
                sequencer.terminate();
            }
        }

        static final class SwitchPermanently extends SequencerEffect {
            private final Lang lang;

            SwitchPermanently(Lang lang) {
                this.lang = lang;
            }

            @Override
            final void affectSequencer(LangSequencer sequencer) {
                sequencer.switchPermanently(lang);
            }
        }

        static final class SwitchTemporarily extends SequencerEffect {
            private final Lang lang;

            SwitchTemporarily(Lang lang) {
                this.lang = lang;
            }

            @Override
            final void affectSequencer(LangSequencer sequencer) {
                sequencer.switchTemporarily(lang);
            }
        }
    }

    static final class API {
        final String name;
        private final Map<Integer, SequencerEffect> vectors;

        public API(String name, BufferedReader reader) throws IOException {
            this.name = name;
            vectors = new HashMap<Integer, SequencerEffect>();
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.charAt(0) == '#') {
                    continue;
                }
                String[] fields = line.trim().split("\\s+");
                try {
                    int address = ParseUtil.parseUnsignedInteger(fields[0]);
                    if (vectors.containsKey(new Integer(address))) {
                        throw new RuntimeException("duplicate API vector declaration \"" + line + "\"");
                    }
                    if (fields.length < 2) {
                        throw new RuntimeException("invalid API vector declaration \"" + line + "\"");
                    }
                    SequencerEffect effect;
                    if (fields[1].equals("terminate")) {
                        if (fields.length > 2) {
                            throw new RuntimeException("invalid API vector declaration \"" + line + "\"");
                        }
                        effect = new SequencerEffect.Terminate();
                    } else if (fields[1].equals("switch-temporarily")) {
                        if (fields.length > 3) {
                            throw new RuntimeException("invalid API vector declaration \"" + line + "\"");
                        }
                        // this will throw exception if the lang is unknown
                        Lang newLang = Lang.MANAGER.get(fields[2]);
                        effect = new SequencerEffect.SwitchTemporarily(newLang);
                    } else if (fields[1].equals("switch-permanently")) {
                        if (fields.length > 3) {
                            throw new RuntimeException("invalid API vector declaration \"" + line + "\"");
                        }
                        // this will throw exception if the lang is unknown
                        Lang newLang = Lang.MANAGER.get(fields[2]);
                        effect = new SequencerEffect.SwitchPermanently(newLang);
                    } else {
                        throw new RuntimeException("invalid API vector declaration \"" + line + "\"");
                    }
                    vectors.put(new Integer(address), effect);
                } catch (ResourceManager.ResolutionError e) {
                    throw new RuntimeException("reference to unknown language in API vector declaration \"" + line
                            + "\"", e);
                } catch (NumberFormatException e) {
                    throw new RuntimeException(
                            "invalid hexadecimal address in API vector declaration \"" + line + "\"", e);
                }
            }
        }

        final SequencerEffect getSequencerEffect(int vector) {
            return vectors.get(new Integer(vector));
        }

        public static final ResourceManager<API> MANAGER = new ResourceManager<API>("api") {
            @Override
            public final API load(String name, BufferedReader reader) throws IOException, RuntimeException {
                return new API(name, reader);
            }
        };
    }

    // Thrown when the decipherer attempts to access a byte beyond the end of
    // the byte vector.
    // Disassembler.run() catches it and reports "abrupt end of code" to the
    // user.
    static final class IncompleteInstruction extends Exception {
        //
    }

    static final class LangSequencer {
        private Lang currentLang;
        private LinkedList<Frame> stack;

        LangSequencer() {
            currentLang = null;
            stack = new LinkedList<Frame>();
        }

        final void init(Frame[] sequencerFrames) {
            currentLang = null;
            terminate();
            for (Frame newFrame : sequencerFrames) {
                stack.add(newFrame);
            }
        }

        Lang getCurrentLang() {
            if (currentLang == null) {
                if (!stack.isEmpty()) {
                    Frame topFrame = stack.getLast();
                    currentLang = topFrame.lang;
                    if (topFrame.takeOneDown()) {
                        stack.removeLast();
                    }
                } else {
                    currentLang = Lang.NONE;
                }
            }
            return currentLang;
        }

        /**
         * Clear the sequencer stack. This has the effect of terminating
         * disassembly of the current sequence after the current instruction has
         * been deciphered.
         */
        final void terminate() {
            stack.clear();
        }

        // note that the switch does not happen immediately but
        // after the next call to advance()
        final void switchPermanently(Lang newLang) {
            assert newLang != null;
            terminate();
            switchTemporarily(newLang);
        }

        final void switchTemporarily(Lang newLang) {
            assert newLang != null;
            assert newLang.defaultCountdown >= 0;
            stack.addLast(new Frame(newLang.defaultCountdown, newLang));
        }

        final void switchBack() {
            if (!stack.isEmpty()) {
                stack.removeLast();
            }
        }

        final void advance() {
            currentLang = null; // forcing the next call to getCurrentLang() to
                                // perform actual advance
        }

        final boolean sequencerHasMore() {
            return getCurrentLang() != Lang.NONE;
        }

        /**
         * Get a copy of the stack, for use in to decode a code sequence
         * starting from a branch destination.
         * 
         * @return generated copy as an array of {@link Frame} instances
         */
        final Frame[] getStack() {
            Frame[] copy = new Frame[stack.size()];
            int i = 0;
            for (Frame frame : stack) {
                copy[i++] = frame.dup();
            }
            return copy;
        }

        final void setCountdown(int newCountdown) {
            assert newCountdown >= 0;
            stack.getLast().countdown = newCountdown;
        }

        static final class Frame {
            int countdown;
            final Lang lang;

            Frame(int countdown, Lang lang) {
                this.countdown = countdown;
                this.lang = lang;
            }

            /**
             * Create a duplicate of this frame. An indefinite frame -- one with
             * countdown being zero -- is considered immutable, and as an
             * optimisation, we won't allocate a new Frame instance for such.
             */
            final Frame dup() {
                if (countdown > 0) {
                    return new Frame(countdown, lang);
                } else {
                    return this;
                }
            }

            /**
             * If this frame has an active countdown, decrement it.
             * 
             * @return whether the frame has run out of repetitions.
             */
            final boolean takeOneDown() {
                return countdown > 0 && --countdown == 0;
            }
        }
    }
}