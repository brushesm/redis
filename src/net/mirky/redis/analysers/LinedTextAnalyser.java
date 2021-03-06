package net.mirky.redis.analysers;

import java.io.PrintStream;

import net.mirky.redis.Analyser;
import net.mirky.redis.Decoding;
import net.mirky.redis.Format;
import net.mirky.redis.HighBitInterpretation;
import net.mirky.redis.ChromaticLineBuilder;
import net.mirky.redis.Format.UnknownOption;
import net.mirky.redis.ReconstructionDataCollector;

@Format.Options("lines/decoding:decoding=ascii/width!:positive-decimal=64/high-bit:high-bit=keep")
public final class LinedTextAnalyser extends Analyser.Leaf {
    @Override
    protected final ReconstructionDataCollector dis(Format format, byte[] data, PrintStream port) throws UnknownOption {
        Decoding decoding = format.getDecoding();
        int width = format.getIntegerOption("width");
        HighBitInterpretation hbi = (HighBitInterpretation) ((Format.Option.SimpleOption) format.getOption("high-bit")).value;

        int maxLineNumber = (data.length + width - 1) / width + 1;
        int lineNumberWidth = 0;
        while (maxLineNumber > 0) {
            lineNumberWidth++;
            maxLineNumber /= 10;
        }
        int pos = 0;
        int lineNumber = 1;
        ChromaticLineBuilder clb = new ChromaticLineBuilder();
        while (pos < data.length) {
            clb.appendLeftPadded(Integer.toString(lineNumber), '0', lineNumberWidth);
            clb.append(' ');
            for (int i = pos; i < pos + width && i < data.length; i++) {
                byte b = data[i];
                clb.processInputByte(b, hbi, decoding);
            }
            clb.terpri(port);
            pos += width;
            lineNumber++;
        }
        return null;
    }
}
