package com.fish5.macro;

import com.fish5.automation.Msg;
import com.fish5.utils.DaemonExecutors;
import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Loads (without initializing) every fish5 class a macro can reach, before the macro starts.
 *
 * <p>The JVM loads classes lazily, so a class first touched mid-macro is read from the jar right then. If
 * the jar was replaced on disk since launch, that read fails ({@code ZipException: invalid LOC header})
 * in the middle of a run. Preloading moves the read to start time: either everything loads and the macro
 * can't hit that crash later, or the start is refused with a "restart the game" message.
 *
 * <p>Reachability is found by scanning each class file's constant pool for {@code com/fish5/...} names.
 * Classes are only loaded, never initialized, so no static init code runs early. Mixin classes are
 * skipped (Mixin forbids loading them directly), and a few hub classes that reference every module are
 * loaded but not expanded, so starting one macro doesn't pull in the whole mod.
 */
public final class ClassPreloader {

    /** Outcome of a preload. {@code error} is null on success. */
    public record Result(int classes, long millis, String error) {}

    private static final String MIXIN_PREFIX = "com/fish5/mixin/";
    private static final String[] NO_EXPAND = {
            "com/fish5/Fish5",
            "com/fish5/features/ModuleManager",
            "com/fish5/clickgui/",
            "com/fish5/hud/",
            "com/fish5/config/",
    };
    private static final byte[] ROOT_BYTES = "com/fish5/".getBytes(StandardCharsets.ISO_8859_1);

    private static final ExecutorService EXECUTOR = DaemonExecutors.single("fish5-preload");

    private ClassPreloader() {}

    /** Called at boot so the preloader itself is in memory before the jar can change. */
    public static void warmUp() {
        // loading this class is the point
    }

    /** Preloads {@code root}'s reachable classes off-thread, then runs {@code done} on the client thread. */
    public static void preloadAsync(Class<?> root, Consumer<Result> done) {
        EXECUTOR.execute(() -> {
            Result r = preload(root);
            Minecraft.getInstance().execute(() -> done.accept(r));
        });
    }

    public static Result preload(Class<?> root) {
        long start = System.nanoTime();
        ClassLoader loader = root.getClassLoader();
        HashSet<String> seen = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        String rootName = root.getName().replace('.', '/');
        seen.add(rootName);
        queue.add(rootName);
        int loaded = 0;
        while (!queue.isEmpty()) {
            String name = queue.poll();
            byte[] bytes;
            try (InputStream in = loader.getResourceAsStream(name + ".class")) {
                if (in == null) continue; // not a class (a name inside a string constant, a package path)
                bytes = in.readAllBytes();
                Class.forName(name.replace('/', '.'), false, loader);
            } catch (Throwable t) {
                return fail(name, t, start, loaded);
            }
            loaded++;
            if (!expands(name)) continue;
            try {
                collectReferences(bytes, seen, queue);
            } catch (RuntimeException t) {
                return fail(name, t, start, loaded);
            }
        }
        long ms = (System.nanoTime() - start) / 1_000_000L;
        Msg.LOG.info("[ClassPreloader] {}: {} classes in {} ms", root.getSimpleName(), loaded, ms);
        return new Result(loaded, ms, null);
    }

    private static Result fail(String name, Throwable t, long start, int loaded) {
        Msg.LOG.warn("[ClassPreloader] failed on {}", name, t);
        return new Result(loaded, (System.nanoTime() - start) / 1_000_000L, t.getClass().getSimpleName());
    }

    private static boolean expands(String name) {
        for (String p : NO_EXPAND) {
            if (p.endsWith("/") ? name.startsWith(p) : name.equals(p)) return false;
        }
        return true;
    }

    /** Walks the constant pool and queues every com/fish5 class name found in its UTF-8 entries. */
    private static void collectReferences(byte[] b, HashSet<String> seen, ArrayDeque<String> queue) {
        int pos = 8; // magic + minor + major
        int count = u2(b, pos);
        pos += 2;
        for (int i = 1; i < count; i++) {
            int tag = b[pos++] & 0xFF;
            switch (tag) {
                case 1 -> { // Utf8
                    int len = u2(b, pos);
                    pos += 2;
                    scanUtf8(b, pos, pos + len, seen, queue);
                    pos += len;
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> pos += 4;
                case 5, 6 -> { pos += 8; i++; } // long/double take two slots
                case 7, 8, 16, 19, 20 -> pos += 2;
                case 15 -> pos += 3;
                default -> throw new IllegalStateException("bad constant pool tag " + tag);
            }
        }
    }

    // Finds "com/fish5/..." runs inside one UTF-8 constant: class names and descriptors like Lcom/fish5/X;
    private static void scanUtf8(byte[] b, int from, int to, HashSet<String> seen, ArrayDeque<String> queue) {
        int last = to - ROOT_BYTES.length;
        outer:
        for (int i = from; i <= last; i++) {
            for (int k = 0; k < ROOT_BYTES.length; k++) {
                if (b[i + k] != ROOT_BYTES[k]) continue outer;
            }
            int end = i + ROOT_BYTES.length;
            while (end < to && isNameByte(b[end])) end++;
            String name = new String(b, i, end - i, StandardCharsets.ISO_8859_1);
            if (!name.startsWith(MIXIN_PREFIX) && !name.endsWith("/") && seen.add(name)) queue.add(name);
            i = end - 1;
        }
    }

    private static boolean isNameByte(byte c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '$' || c == '/';
    }

    private static int u2(byte[] b, int pos) {
        return ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
    }
}
