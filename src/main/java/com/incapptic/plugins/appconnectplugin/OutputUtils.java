package com.incapptic.plugins.appconnectplugin;

import java.io.PrintStream;

/**
 * Created by tjurkiewicz on 07/03/2017.
 */
public class OutputUtils {
    public static final String ERROR   = "[Error]  ";
    public static final String INFO    = "[Info]   ";
    public static final String SUCCESS = "[Success]";


    private static void print(PrintStream printStream, String prefix, String message) {
        printStream.println(String.format("%s %s", prefix, message));
    }

    public static void info(PrintStream printStream, String message) {
        print(printStream, INFO, message);
    }

    public static void error(PrintStream printStream, String message) {
        print(printStream, ERROR, message);
    }

    public static void success(PrintStream printStream, String message) {
        print(printStream, SUCCESS, message);
    }
}
