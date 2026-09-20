package com.tricrotism.windchill.telemetry

/**
 * One method, as JFR names it. The descriptor is kept because overloads compile, deoptimise and
 * inline independently.
 *
 * Class names are normalised on the way in. JFR reports the same class differently depending on the
 * event: `jdk.CompilerInlining` describes its callee in internal form with a `+` before a hidden
 * class's address, while every other event uses binary form with a `.`. Matching a compiler decision
 * against a sample is the whole basis of the inlining analysis, so both are converted to binary form
 * by [of] before a `MethodRef` exists.
 */
data class MethodRef(
    val className: String,
    val methodName: String,
    val descriptor: String,
) {

    /**
     * A lambda, method reference, or other class the JVM generated at runtime.
     *
     * These are real code and worth reporting, but their names embed a memory address that changes
     * on every run, so they are displayed by their enclosing class rather than printed raw.
     */
    val hidden: Boolean
        get() = className.contains(HIDDEN_MARKER) || className.contains(LAMBDA_MARKER)

    /** `Shop.buy`, or `lambda in Shop`, for report lines that have to stay narrow. */
    val shortLabel: String
        get() = if (hidden) hiddenLabel(qualified = false) else "${className.substringAfterLast('.')}.$methodName"

    val fullLabel: String
        get() = if (hidden) hiddenLabel(qualified = true) else "$className.$methodName"

    /**
     * Names a generated class by what a reader can actually find: the class that declares it.
     *
     * `Shop$$Lambda.0x00007f2a` becomes `lambda in Shop`. A synthetic body the compiler lifted out of
     * a lambda is already named `lambda$method$0` by javac, so that case reads naturally on its own.
     */
    private fun hiddenLabel(qualified: Boolean): String {
        val declaring = className.substringBefore(HIDDEN_MARKER).substringBefore(LAMBDA_MARKER)
        val shown = if (qualified) declaring else declaring.substringAfterLast('.')

        return when {
            shown.isEmpty() -> "generated method $methodName"
            className.contains(LAMBDA_MARKER) -> "lambda in $shown"
            else -> "$shown.$methodName (generated)"
        }
    }

    companion object {

        private const val HIDDEN_MARKER = ".0x"
        private const val LAMBDA_MARKER = "\$\$Lambda"

        /**
         * @param rawClassName either form JFR produces, internal or binary
         */
        fun of(rawClassName: String, methodName: String, descriptor: String): MethodRef =
            MethodRef(normalise(rawClassName), methodName, descriptor)

        /**
         * Converts a class name to the binary form every non-inlining event already uses.
         *
         * The `+` before a hidden class's address is the only difference that survives the slash
         * conversion, and it is the difference that would otherwise stop a refused inlining from ever
         * matching the sample that proves the method is hot.
         */
        fun normalise(rawClassName: String): String =
            rawClassName.replace('/', '.').replace("+0x", HIDDEN_MARKER)
    }
}
