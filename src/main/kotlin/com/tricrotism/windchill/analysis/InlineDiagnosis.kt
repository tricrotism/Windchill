package com.tricrotism.windchill.analysis

/**
 * Turns HotSpot's own inlining refusal message into advice.
 *
 * Only C2's refusals are worth acting on. Measured on JDK 25 by joining each refusal to the tier of
 * the compile that made it: "callee is too large", "callee uses too much stack" and "no static
 * binding" come from C1 at tier 3 and only from C1. C2 then decides the same call for itself, and
 * inlined a callee C1 had refused in the test that established this. Tier 3 code is replaced within
 * seconds, so a C1 refusal describes code that is about to stop running.
 */
object InlineDiagnosis {

    /**
     * C2's size refusals, the one class a developer can fix directly.
     *
     * "hot method too big" and "too big" are the callee's bytecode over FreqInlineSize or
     * MaxInlineSize. "already compiled into a big method" is the callee's existing machine code over
     * InlineSmallCode, and "medium" the same at a cold site. "size > DesiredMethodLimit" is the
     * method being compiled out of room for any more inlined bytecode.
     */
    fun isSizeRelated(message: String): Boolean {
        val m = message.lowercase()

        return m.contains("too big")
            || m.contains("already compiled into a")
            || m.contains("desiredmethodlimit")
    }

    /**
     * Size refusals where the caller has no room left, so the caller is the method to change.
     *
     * Only DesiredMethodLimit. "already compiled into a big method" reads like a caller problem and is
     * not: measured on JDK 25, a 288-byte caller refused a callee whose own compiled code was 1,488
     * bytes once InlineSmallCode was lowered to 500, and inlined it at the default 2,500.
     */
    fun blamesCaller(message: String): Boolean = message.contains("DesiredMethodLimit", ignoreCase = true)

    /** The callee was refused for its machine code rather than its bytecode. */
    fun isCompiledSize(message: String): Boolean = message.contains("already compiled into a", ignoreCase = true)

    fun advise(message: String, calleeLabel: String): String = when {
        isCompiledSize(message) ->
            "$calleeLabel was already compiled on its own into more machine code than C2 will inline, so " +
                "hot callers call it instead. It is usually that large because of what it inlines itself. " +
                "Move its rarely taken paths into separate methods so its compiled form shrinks."

        blamesCaller(message) ->
            "Inlining $calleeLabel would take the method being compiled past DesiredMethodLimit. Split the " +
                "caller so the hot path has room for it."

        else ->
            "$calleeLabel is over HotSpot's inlining size limit. Move the rarely taken branches out " +
                "into their own method so the part that actually runs fits."
    }
}
