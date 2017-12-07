package com.incapptic.plugins.connect;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by tjurkiewicz on 07/03/2017.
 */
public class OutputUtils {
    public static final String ERROR   = "[Error]  ";
    public static final String INFO    = "[Info]   ";
    public static final String SUCCESS = "[Success]";

    private final PrintStream printStream;

    private static Map<Integer, OutputUtils> INSTANCES = new HashMap<>();

    static OutputUtils getLoggerForStream(PrintStream stream) {
        if (!INSTANCES.containsKey(stream.hashCode())) {
            INSTANCES.put(stream.hashCode(), new OutputUtils(stream));
        }
        return INSTANCES.get(stream.hashCode());
    }

    private OutputUtils(PrintStream printStream) {
        this.printStream = printStream;
    }

    public PrintStream getPrintStream(){ return this.printStream; }

    private void print(String prefix, String message) {
        printStream.println(String.format("%s %s", prefix, message));
    }

    void info(String message) {
        print(INFO, message);
    }

    void error(String message) {
        print(ERROR, message);
    }

    void success(String message) {
        print(SUCCESS, message);
    }
}
