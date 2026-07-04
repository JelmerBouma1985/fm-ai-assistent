package com.github.fmaiassistent.memory;

import com.github.fmaiassistent.linux.LinuxProcessReader;
import com.github.fmaiassistent.linux.ProcessInfo;
import com.github.fmaiassistent.windows.WindowsProcessReader;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class ProcessReaders {
    private ProcessReaders() {
    }

    public static ProcessMemoryReader open(int pid) throws IOException {
        if (isWindows()) {
            return new WindowsProcessReader(pid);
        }
        if (isLinux()) {
            return new LinuxProcessReader(pid);
        }
        throw new UnsupportedOperationException("Unsupported operating system: " + System.getProperty("os.name"));
    }

    public static List<ProcessInfo> findProcesses(String query) throws IOException {
        if (isWindows()) {
            return WindowsProcessReader.findProcesses(query);
        }
        if (isLinux()) {
            return LinuxProcessReader.findProcesses(query);
        }
        throw new UnsupportedOperationException("Unsupported operating system: " + System.getProperty("os.name"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");
    }
}
