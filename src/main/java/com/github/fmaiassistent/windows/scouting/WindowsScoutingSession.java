package com.github.fmaiassistent.windows.scouting;

import com.github.fmaiassistent.memory.ScoutingReportLayout;
import com.github.fmaiassistent.windows.WindowsProcessReader;
import com.sun.jna.Callback;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Windows transport only. No game routine executes on the installer remote thread.
 *
 * <p>A single RX bridge and RW control page stay pinned for each live FM process.
 * Even after removing a WNDPROC, an outer forwarded/reentrant invocation may still
 * have a return address in it. Keeping this bounded allocation until process exit
 * avoids freeing executable code underneath such a frame. It is reused across jobs.
 */
final class WindowsScoutingSession implements AutoCloseable {
    private static final Map<Integer, Bridge> BRIDGES = new HashMap<>();
    private static final int PRIVATE_MESSAGE = 0x8351;
    private static final int CONTROL_BYTES = 85 * Long.BYTES;
    private final Bridge bridge;

    WindowsScoutingSession(int pid) throws IOException {
        synchronized (BRIDGES) {
            var iterator = BRIDGES.entrySet().iterator();
            while (iterator.hasNext()) {
                Bridge candidate = iterator.next().getValue();
                IntByReference exit = new IntByReference();
                if (!K.INSTANCE.GetExitCodeProcess(candidate.process, exit) || exit.getValue() != 259) {
                    K.INSTANCE.CloseHandle(candidate.process);
                    iterator.remove();
                }
            }
            Bridge existing = BRIDGES.get(pid);
            if (existing == null) {
                existing = create(pid);
                BRIDGES.put(pid, existing);
            }
            if (existing.poisoned) throw new IOException(
                    "A previous scouting callback has uncertain completion; restart FM before scouting again");
            bridge = existing;
        }
    }

    long base() { return bridge.base; }

    long[] execute(long[] input) throws IOException {
        synchronized (bridge) {
            if (bridge.poisoned) throw new IOException("Scouting requires an FM restart after an incomplete callback");
            if (input.length * 8 != CONTROL_BYTES) throw new IOException("Invalid scouting control size");
            Pointer window = findWindow(bridge.pid);
            IntByReference owner = new IntByReference();
            int thread = U32.INSTANCE.GetWindowThreadProcessId(window, owner);
            if (thread == 0 || owner.getValue() != bridge.pid) throw new IOException("FM window identity changed");
            // The previous callback may have been nested inside a forwarded message.
            // Never replace its control data until every such invocation has returned.
            if (readState()[56] != 0 || !send(window, 0, 0, 0)) {
                throw new IOException("FM is busy; wait until it responds before retrying scouting");
            }
            if (readState()[56] != 0) throw new IOException("FM still has a scouting callback in progress");
            long[] state = input.clone();
            state[1] = Pointer.nativeValue(window);
            state[2] = bridge.getWindowLong; state[3] = bridge.setWindowLong;
            state[4] = bridge.callWindowProc; state[5] = bridge.getThreadId;
            state[6] = Integer.toUnsignedLong(thread); state[7] = new SecureRandom().nextLong();
            state[8] = PRIVATE_MESSAGE;
            write(bridge.process, bridge.control, encode(state));
            boolean installed = false;
            boolean dispatched = false;
            try {
                installed = true; // A failed return can still follow a successful SetWindowLongPtr.
                if (remote(bridge, bridge.image.offset("bridge_install")) != 1) {
                    throw new IOException("Could not install FM's one-shot scouting callback");
                }
                dispatched = true;
                boolean replied = send(window, PRIVATE_MESSAGE, state[7], bridge.control);
                long[] completed = readState();
                if (!replied || completed[9] == 0 || completed[9] == 1) {
                    bridge.poisoned = true;
                    throw new IOException("FM did not complete the scouting callback; restart FM before retrying");
                }
                if (completed[54] != 1 || remote(bridge, bridge.image.offset("bridge_uninstall")) != 1) {
                    bridge.poisoned = true;
                    throw new IOException("FM's window handler restoration could not be verified; restart FM");
                }
                installed = false;
                if (completed[9] != 2) throw new IOException("FM rejected scouting (native status " + completed[9] + ")");
                return completed;
            } catch (IOException failure) {
                // A failed host read after dispatch is not evidence that the game callback stopped.
                if (dispatched && installed) bridge.poisoned = true;
                throw failure;
            } finally {
                if (installed && !bridge.poisoned) {
                    try {
                        if (remote(bridge, bridge.image.offset("bridge_uninstall")) != 1) bridge.poisoned = true;
                    } catch (IOException cleanupFailure) {
                        bridge.poisoned = true;
                        throw cleanupFailure;
                    }
                }
            }
        }
    }

    private long[] readState() throws IOException {
        try (Memory memory = new Memory(CONTROL_BYTES)) {
            LongByReference count = new LongByReference();
            if (!K.INSTANCE.ReadProcessMemory(bridge.process, Pointer.createConstant(bridge.control), memory,
                    CONTROL_BYTES, count) || count.getValue() != CONTROL_BYTES) throw error("Read scouting state");
            long[] result = new long[CONTROL_BYTES / 8];
            for (int index = 0; index < result.length; index++) result[index] = memory.getLong(index * 8L);
            return result;
        }
    }

    private static Bridge create(int pid) throws IOException {
        Map<String, Module> modules = modules(pid);
        Module plugin = modules.get("game_plugin.dll");
        if (plugin == null) throw new IOException("FM's game_plugin.dll is not loaded");
        verifyModule(plugin.path());
        ScoutingBridgeImage image = ScoutingBridgeImage.load();
        Pointer process = K.INSTANCE.OpenProcess(0x10043A, false, pid);
        if (process == null) throw error("Open FM for scouting");
        long code = 0, control = 0;
        try {
            code = allocate(process, image.size());
            control = allocate(process, 4096);
            write(process, code, image.relocate(code, control));
            IntByReference protection = new IntByReference();
            if (!K.INSTANCE.VirtualProtectEx(process, Pointer.createConstant(code), image.size(), 0x20, protection)
                    || !K.INSTANCE.FlushInstructionCache(process, Pointer.createConstant(code), image.size())) {
                throw error("Protect scouting bridge");
            }
            return new Bridge(pid, process, plugin.base(), code, control, image,
                    resolveExport("user32", "GetWindowLongPtrW", modules),
                    resolveExport("user32", "SetWindowLongPtrW", modules),
                    resolveExport("user32", "CallWindowProcW", modules),
                    resolveExport("kernel32", "GetCurrentThreadId", modules));
        } catch (IOException | RuntimeException failure) {
            // No helper has been executed or hooked yet, so these allocations are safe to free.
            if (code != 0) K.INSTANCE.VirtualFreeEx(process, Pointer.createConstant(code), 0, 0x8000);
            if (control != 0) K.INSTANCE.VirtualFreeEx(process, Pointer.createConstant(control), 0, 0x8000);
            K.INSTANCE.CloseHandle(process);
            throw failure;
        }
    }

    private static void verifyModule(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int length;
                while ((length = stream.read(buffer)) != -1) digest.update(buffer, 0, length);
            }
            if (!ScoutingReportLayout.MODULE_SHA256.equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new IOException("This FM build is not supported for creating scout reports");
            }
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static long resolveExport(String library, String name, Map<String, Module> modules) throws IOException {
        Pointer function = NativeLibrary.getInstance(library).getFunction(name);
        PointerByReference owner = new PointerByReference();
        if (!K.INSTANCE.GetModuleHandleExW(6, function, owner)) throw error("Resolve " + name);
        char[] filename = new char[1024];
        int length = K.INSTANCE.GetModuleFileNameW(owner.getValue(), filename, filename.length);
        if (length == 0 || length >= filename.length) throw error("Resolve API module");
        String moduleName = Path.of(new String(filename, 0, length)).getFileName().toString().toLowerCase(Locale.ROOT);
        Module remote = modules.get(moduleName);
        long rva = Pointer.nativeValue(function) - Pointer.nativeValue(owner.getValue());
        if (remote == null || rva < 0 || rva >= remote.size()) throw new IOException("Missing FM API module: " + moduleName);
        return remote.base() + rva;
    }

    private static Map<String, Module> modules(int pid) throws IOException {
        Pointer snapshot = K.INSTANCE.CreateToolhelp32Snapshot(0x18, pid);
        if (snapshot == null || Pointer.nativeValue(snapshot) == -1) throw error("Read FM modules");
        try {
            Map<String, Module> result = new HashMap<>();
            WindowsProcessReader.ModuleEntry32 entry = new WindowsProcessReader.ModuleEntry32();
            entry.dwSize = entry.size(); entry.write();
            boolean next = K.INSTANCE.Module32FirstW(snapshot, entry);
            while (next) {
                entry.read();
                if (result.size() >= 4096) throw new IOException("FM module inventory exceeds its limit");
                result.put(Native.toString(entry.szModule).toLowerCase(Locale.ROOT), new Module(
                        Pointer.nativeValue(entry.modBaseAddr), Integer.toUnsignedLong(entry.modBaseSize),
                        Path.of(Native.toString(entry.szExePath))));
                entry.dwSize = entry.size(); entry.write(); next = K.INSTANCE.Module32NextW(snapshot, entry);
            }
            return result;
        } finally { K.INSTANCE.CloseHandle(snapshot); }
    }

    private static Pointer findWindow(int pid) throws IOException {
        List<Pointer> windows = new ArrayList<>();
        WindowCallback callback = (window, ignored) -> {
            IntByReference owner = new IntByReference();
            U32.INSTANCE.GetWindowThreadProcessId(window, owner);
            if (owner.getValue() == pid && U32.INSTANCE.IsWindowVisible(window)) windows.add(window);
            return true;
        };
        if (!U32.INSTANCE.EnumWindows(callback, null)) throw error("Find FM window");
        return windows.stream().findFirst().orElseThrow(() -> new IOException("FM has no visible game window"));
    }

    private static boolean send(Pointer window, int message, long wp, long lp) {
        LongByReference result = new LongByReference();
        return U32.INSTANCE.SendMessageTimeoutW(window, message, wp, lp, 0x2, 5_000, result) != 0;
    }

    private static int remote(Bridge bridge, int offset) throws IOException {
        Pointer thread = K.INSTANCE.CreateRemoteThread(bridge.process, null, 0,
                Pointer.createConstant(bridge.code + offset), Pointer.createConstant(bridge.control), 0, null);
        if (thread == null) throw error("Install scouting callback");
        try {
            if (K.INSTANCE.WaitForSingleObject(thread, 5_000) != 0) {
                bridge.poisoned = true;
                throw new IOException("The scouting installer did not finish; restart FM before retrying");
            }
            IntByReference result = new IntByReference();
            if (!K.INSTANCE.GetExitCodeThread(thread, result)) throw error("Read scouting installer result");
            return result.getValue();
        } finally { K.INSTANCE.CloseHandle(thread); }
    }

    private static long allocate(Pointer process, int bytes) throws IOException {
        Pointer result = K.INSTANCE.VirtualAllocEx(process, null, bytes, 0x3000, 0x04);
        if (result == null) throw error("Allocate scouting bridge");
        return Pointer.nativeValue(result);
    }

    private static void write(Pointer process, long address, byte[] bytes) throws IOException {
        try (Memory memory = new Memory(bytes.length)) {
            memory.write(0, bytes, 0, bytes.length);
            LongByReference count = new LongByReference();
            if (!K.INSTANCE.WriteProcessMemory(process, Pointer.createConstant(address), memory, bytes.length, count)
                    || count.getValue() != bytes.length) throw error("Write scouting bridge");
        }
    }

    private static byte[] encode(long[] state) {
        ByteBuffer result = ByteBuffer.allocate(CONTROL_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (long value : state) result.putLong(value);
        return result.array();
    }

    private static IOException error(String action) {
        return new IOException(action + " failed (Windows error " + Native.getLastError() + ")");
    }

    @Override public void close() { /* Process-lifetime allocation: see class lifecycle contract. */ }

    private record Module(long base, long size, Path path) { }
    private static final class Bridge {
        final int pid;
        final Pointer process;
        final long base, code, control, getWindowLong, setWindowLong, callWindowProc, getThreadId;
        final ScoutingBridgeImage image;
        boolean poisoned;
        Bridge(int pid, Pointer process, long base, long code, long control, ScoutingBridgeImage image,
               long getWindowLong, long setWindowLong, long callWindowProc, long getThreadId) {
            this.pid = pid; this.process = process; this.base = base; this.code = code; this.control = control;
            this.image = image; this.getWindowLong = getWindowLong; this.setWindowLong = setWindowLong;
            this.callWindowProc = callWindowProc; this.getThreadId = getThreadId;
        }
    }

    private interface WindowCallback extends Callback { boolean invoke(Pointer window, Pointer parameter); }
    private interface U32 extends StdCallLibrary {
        U32 INSTANCE = Native.load("user32", U32.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean EnumWindows(WindowCallback callback, Pointer parameter);
        boolean IsWindowVisible(Pointer window);
        int GetWindowThreadProcessId(Pointer window, IntByReference pid);
        long SendMessageTimeoutW(Pointer window, int message, long wp, long lp, int flags, int timeout, LongByReference result);
    }
    private interface K extends StdCallLibrary {
        K INSTANCE = Native.load("kernel32", K.class, W32APIOptions.DEFAULT_OPTIONS);
        Pointer OpenProcess(int access, boolean inherit, int pid);
        boolean CloseHandle(Pointer handle);
        boolean GetExitCodeProcess(Pointer handle, IntByReference code);
        Pointer CreateToolhelp32Snapshot(int flags, int pid);
        boolean Module32FirstW(Pointer snapshot, WindowsProcessReader.ModuleEntry32 entry);
        boolean Module32NextW(Pointer snapshot, WindowsProcessReader.ModuleEntry32 entry);
        boolean GetModuleHandleExW(int flags, Pointer address, PointerByReference module);
        int GetModuleFileNameW(Pointer module, char[] filename, int size);
        Pointer VirtualAllocEx(Pointer process, Pointer address, long bytes, int allocation, int protection);
        boolean VirtualFreeEx(Pointer process, Pointer address, long bytes, int freeType);
        boolean VirtualProtectEx(Pointer process, Pointer address, long bytes, int protection, IntByReference old);
        boolean FlushInstructionCache(Pointer process, Pointer address, long bytes);
        boolean ReadProcessMemory(Pointer process, Pointer address, Pointer buffer, long bytes, LongByReference count);
        boolean WriteProcessMemory(Pointer process, Pointer address, Pointer buffer, long bytes, LongByReference count);
        Pointer CreateRemoteThread(Pointer process, Pointer attributes, long stackSize, Pointer start,
                                   Pointer parameter, int flags, IntByReference id);
        int WaitForSingleObject(Pointer handle, int milliseconds);
        boolean GetExitCodeThread(Pointer thread, IntByReference code);
    }
}
