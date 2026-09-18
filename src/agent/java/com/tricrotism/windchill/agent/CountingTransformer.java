package com.tricrotism.windchill.agent;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;

/**
 * Injects a single counter increment at the entry of each targeted method.
 *
 * <p>Only methods named in the target map are touched, and the injection is one constant load plus
 * one {@code invokestatic}. Nothing is wrapped, no exception table is rewritten and no method is
 * renamed, which is what keeps this safe to install on a running server. Transformation uses the
 * JDK's own class-file API, so there is no bytecode library on the classpath to clash with a
 * plugin's own shaded copy.
 *
 * <p>Counts pair with the sampled self-time Windchill already has from JFR: samples say where time
 * goes, counts say how many calls it took to get there, and the quotient is the per-call cost that
 * decides whether a method is slow or merely popular.
 */
public final class CountingTransformer implements ClassFileTransformer {

    private static final ClassDesc COUNTERS = ClassDesc.of("com.tricrotism.windchill.agent.MethodCounters");
    private static final MethodTypeDesc HIT = MethodTypeDesc.ofDescriptor("(I)V");

    // Internal class name ("com/example/Foo") to "name descriptor" to counter slot.
    private final Map<String, Map<String, Integer>> targets;

    public CountingTransformer(Map<String, Map<String, Integer>> targets) {
        this.targets = targets;
    }

    @Override
    public byte[] transform(
        ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] classfileBuffer
    ) {
        Map<String, Integer> slots = className == null ? null : targets.get(className);
        if (slots == null) return null;

        try {
            ClassFile cf = ClassFile.of(hierarchyOption(loader));
            return cf.transformClass(cf.parse(classfileBuffer), classTransform(slots));
        } catch (Throwable t) {
            // A failed transform must leave the class exactly as it was, which returning null does.
            // The JVM would swallow a thrown exception and load the class uninstrumented without
            // telling anyone, so report it and fall back deliberately.
            WindchillAgent.reportTransformFailure(className, t);
            return null;
        }
    }

    /**
     * Resolves the class hierarchy through the loader that owns the class being transformed.
     *
     * Rewriting a method body makes the class-file API recompute stack map frames, which needs to
     * know the supertypes of everything on the stack. Its default resolver looks classes up through
     * the system class loader, which cannot see a plugin's own types, and the transform then fails
     * with "Could not resolve class". Handing it the defining loader is what makes plugin types
     * resolvable; the default is kept underneath for anything in java.base.
     */
    private static ClassFile.ClassHierarchyResolverOption hierarchyOption(ClassLoader loader) {
        ClassHierarchyResolver resolver = loader == null
            ? ClassHierarchyResolver.defaultResolver()
            : ClassHierarchyResolver.ofClassLoading(loader).orElse(ClassHierarchyResolver.defaultResolver());

        return ClassFile.ClassHierarchyResolverOption.of(resolver.cached());
    }

    private static ClassTransform classTransform(Map<String, Integer> slots) {
        return (builder, element) -> {
            if (element instanceof MethodModel method && method.code().isPresent()) {
                Integer slot = slots.get(method.methodName().stringValue() + " " + method.methodType().stringValue());
                if (slot != null) {
                    builder.transformMethod(method, MethodTransform.transformingCode(new EntryCounter(slot)));
                    return;
                }
            }

            builder.with(element);
        };
    }

    /**
     * Prepends the counter call to one method body. {@code atStart} runs before the original code is
     * copied through, so the increment lands ahead of the first real instruction and the original
     * labels, branch targets and exception ranges are reproduced untouched.
     */
    private record EntryCounter(int slot) implements CodeTransform {

        @Override
        public void atStart(CodeBuilder builder) {
            builder.loadConstant(slot);
            builder.invokestatic(COUNTERS, "hit", HIT);
        }

        @Override
        public void accept(CodeBuilder builder, CodeElement element) {
            builder.with(element);
        }
    }
}
