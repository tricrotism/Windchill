package com.tricrotism.windchill.agent;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent entry point, loaded onto the system class loader by a self-attach from the plugin.
 *
 * <p>The plugin reaches every method here reflectively, because the plugin class loader and the
 * system class loader hold different copies of this class and sharing types across them would fail.
 * Everything below therefore takes and returns only JDK types.
 *
 * <p>The agent is optional. Windchill's analysis runs without it, on JFR and JMX alone; attaching
 * adds exact class ownership, read from live class loaders rather than guessed from package names.
 *
 * <p>Nothing here is published to the bootstrap loader. Appending to the bootstrap search stops the
 * JVM serving application loader classes from an AOT cache for the rest of the run, and no plugin
 * class needs to call into the agent now that it injects no bytecode.
 */
public final class WindchillAgent {

    private static volatile Instrumentation instrumentation;

    private WindchillAgent() {}

    public static void agentmain(String args, Instrumentation inst) {
        instrumentation = inst;
    }

    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
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
}
