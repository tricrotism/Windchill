package com.tricrotism.windchill.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Agent entry point, loaded onto the system class loader by a self-attach from the plugin.
 *
 * <p>The plugin reaches every method here reflectively, because the plugin class loader and the
 * system class loader hold different copies of this class and sharing types across them would fail.
 * Everything below therefore takes and returns only JDK types.
 *
 * <p>The agent is optional. Windchill's analysis runs without it, on JFR and JMX alone; attaching
 * adds exact class ownership, read from live class loaders rather than guessed from package names,
 * and exact invocation counts for methods the analysis has already flagged.
 */
public final class WindchillAgent {

    private static volatile Instrumentation instrumentation;
    private static volatile CountingTransformer activeTransformer;
    private static volatile Class<?>[] instrumented = new Class<?>[0];
    private static final List<String> transformFailures = new ArrayList<>();

    private WindchillAgent() {}

    public static void agentmain(String args, Instrumentation inst) {
        publishToBootstrap(args, inst);
        instrumentation = inst;
    }

    public static void premain(String args, Instrumentation inst) {
        publishToBootstrap(args, inst);
        instrumentation = inst;
    }

    /**
     * Puts this jar on the bootstrap class loader's search path.
     *
     * Injected bytecode inside a plugin class has to reach {@link MethodCounters}, and a plugin class
     * loader will not find it on the system class loader: server plugin loaders delegate to the
     * server and to each other, not to the application class path an agent is appended to. Bootstrap
     * is the one loader every other loader delegates to, so publishing there is what makes the
     * counter call resolve from anywhere.
     *
     * Safe because these classes reference nothing outside java.base. It happens before the
     * instrumentation is published, so nothing can touch MethodCounters before it is reachable.
     */
    private static void publishToBootstrap(String agentJarPath, Instrumentation inst) {
        if (agentJarPath == null || agentJarPath.isBlank()) return;

        try (JarFile jar = new JarFile(agentJarPath)) {
            inst.appendToBootstrapClassLoaderSearch(jar);
        } catch (IOException e) {
            reportTransformFailure("<bootstrap>", e);
        }
    }

    public static boolean attached() {
        return instrumentation != null;
    }

    /**
     * Every loaded class paired with the identity hash of its defining loader, as
     * {@code {className, loaderIdentity}} rows. The plugin resolves loader identities against the
     * loaders it reads off the server's own plugin list, which is what makes attribution exact
     * instead of a package-name guess.
     */
    public static Object[][] loadedClasses() {
        Instrumentation inst = instrumentation;
        if (inst == null) return new Object[0][];

        Class<?>[] all = inst.getAllLoadedClasses();
        List<Object[]> rows = new ArrayList<>(all.length);
        for (Class<?> c : all) {
            ClassLoader loader = c.getClassLoader();
            if (loader == null) continue;
            rows.add(new Object[] { c.getName(), System.identityHashCode(loader) });
        }

        return rows.toArray(new Object[0][]);
    }

    /**
     * Installs invocation counting on the given methods, each described as
     * {@code "binary.Class name (Ldescriptor;)V"}. Slot indices follow the argument order, so the
     * caller reads counts back positionally.
     *
     * @return the number of classes that were successfully retransformed
     */
    public static int installCounters(String[] methods) {
        Instrumentation inst = instrumentation;
        if (inst == null) return 0;

        removeCounters();
        synchronized (transformFailures) {
            transformFailures.clear();
        }

        Map<String, Map<String, Integer>> targets = new HashMap<>();
        for (int slot = 0; slot < methods.length; slot++) {
            int split = methods[slot].indexOf(' ');
            if (split <= 0) continue;

            String internalName = methods[slot].substring(0, split).replace('.', '/');
            targets.computeIfAbsent(internalName, k -> new HashMap<>())
                .put(methods[slot].substring(split + 1), slot);
        }
        if (targets.isEmpty()) return 0;

        MethodCounters.allocate(methods.length);
        CountingTransformer transformer = new CountingTransformer(targets);
        inst.addTransformer(transformer, true);
        activeTransformer = transformer;

        List<Class<?>> retransform = new ArrayList<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (targets.containsKey(c.getName().replace('.', '/')) && inst.isModifiableClass(c)) {
                retransform.add(c);
            }
        }
        if (retransform.isEmpty()) {
            removeCounters();
            return 0;
        }

        Class<?>[] batch = retransform.toArray(new Class<?>[0]);
        try {
            inst.retransformClasses(batch);
        } catch (UnmodifiableClassException | RuntimeException e) {
            reportTransformFailure("<retransform>", e);
            removeCounters();
            return 0;
        }

        instrumented = batch;
        return batch.length;
    }

    /** Reverts every instrumented class to its original bytecode. */
    public static void removeCounters() {
        Instrumentation inst = instrumentation;
        CountingTransformer transformer = activeTransformer;
        if (inst == null || transformer == null) return;

        Class<?>[] revert = instrumented;
        activeTransformer = null;
        instrumented = new Class<?>[0];
        inst.removeTransformer(transformer);

        if (revert.length == 0) return;
        try {
            inst.retransformClasses(revert);
        } catch (UnmodifiableClassException | RuntimeException ignored) {
            // The transformer is already unregistered, so these classes revert on any later
            // retransform regardless. Nothing here is worth failing a command over.
        }
    }

    public static long[] counts() {
        return MethodCounters.read();
    }

    public static String[] transformFailures() {
        synchronized (transformFailures) {
            return transformFailures.toArray(new String[0]);
        }
    }

    static void reportTransformFailure(String className, Throwable t) {
        synchronized (transformFailures) {
            if (transformFailures.size() < 32) {
                transformFailures.add(className + ": " + t.getClass().getSimpleName() + " " + t.getMessage());
            }
        }
    }
}
