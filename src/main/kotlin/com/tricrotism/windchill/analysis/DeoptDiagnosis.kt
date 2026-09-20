package com.tricrotism.windchill.analysis

/**
 * Turns a HotSpot deoptimisation reason and action into advice.
 *
 * The action matters more than the reason. `make_not_compilable` means the method is back in the
 * interpreter permanently and no amount of warm-up recovers it; `reinterpret` and `make_not_entrant`
 * mean it is recompiled, so the cost is wasted compiler work and a brief interpreted stretch, repeated.
 */
object DeoptDiagnosis {

    /**
     * The method will never get C2 code again for the life of the JVM. Stock C2 on JDK 25 never posts
     * this action to JFR: a method barred by the recompilation cutoff shows as `reinterpret` and then
     * compiles at tier 1. Only JVMCI compilers such as Graal emit it.
     */
    fun isPermanent(action: String): Boolean = action.contains("not_compilable", ignoreCase = true)

    /**
     * The compiled version was thrown away and will be rebuilt, so repeats are churn.
     *
     * `reinterpret` is the action HotSpot takes for the common `unstable_if` trap: it invalidates the
     * compiled code and resets the counters, so the method climbs the tiers again from the interpreter.
     * `maybe_recompile` and `none` keep the compiled code.
     */
    fun isRecompiling(action: String): Boolean =
        action.contains("not_entrant", ignoreCase = true) || action.contains("reinterpret", ignoreCase = true)

    fun explain(reason: String): String = when (normalise(reason)) {
        "unstable_if" ->
            "A branch the JIT had profiled as never taken started firing."

        "unreached" ->
            "A branch that was never reached during profiling is now being executed."

        "class_check", "speculate_class_check" ->
            "A receiver of an unexpected type reached a call site the JIT had speculated was monomorphic."

        "bimorphic", "bimorphic_or_optimized_type_check" ->
            "A third implementation appeared at a call site the JIT had narrowed to two."

        "null_check", "speculate_null_check", "null_assert" ->
            "A value the JIT had proven non-null arrived null."

        "array_check" ->
            "An element of an unexpected type was stored into an array."

        "range_check" ->
            "An index fell outside the range the JIT had speculated on."

        "div0_check" -> "A division by zero reached code compiled on the assumption it could not happen."

        "loop_limit_check" -> "A loop ran past the trip count the JIT had speculated."

        "intrinsic" -> "An intrinsic's precondition was violated."

        "constraint", "predicate", "profile_predicate" ->
            "An assumption the optimiser had recorded stopped holding."

        "unhandled" -> "An exception path the compiled code did not cover was taken."

        else -> "HotSpot reported reason \"$reason\"."
    }

    fun advise(reason: String, action: String, methodLabel: String): String {
        val base = when (normalise(reason)) {
            "unstable_if", "unreached" ->
                "Find the branch in $methodLabel that used to be dead. The usual causes are a lazily " +
                    "initialised field, a config value flipped at runtime, or an error path that has " +
                    "started being taken. Hoist the condition out of the hot path, or initialise it " +
                    "eagerly so the profile is right the first time."

            "class_check", "speculate_class_check", "bimorphic", "bimorphic_or_optimized_type_check" ->
                "The call site in $methodLabel is megamorphic. Reduce how many implementations flow " +
                    "through it: split the hot type into its own path, hold one concrete type in the " +
                    "field, or dispatch through a map keyed by type instead of a chain of checks."

            "null_check", "speculate_null_check", "null_assert" ->
                "Something $methodLabel treats as always present is sometimes absent. Give it a " +
                    "non-null default, or split the nullable case into its own method so the hot path " +
                    "keeps a stable profile."

            "array_check" ->
                "$methodLabel stores mixed element types into one array. Use a typed collection, or " +
                    "separate arrays per type."

            "range_check", "loop_limit_check" ->
                "Bounds in $methodLabel move outside what the JIT profiled. Hoist the bound into a local " +
                    "before the loop so it stays constant across the iteration."

            else ->
                "Capture again over a longer window to confirm this repeats before changing $methodLabel."
        }

        return when {
            isPermanent(action) ->
                "$base HotSpot has stopped optimising this method with C2. It now runs as lightly " +
                    "optimised C1 code, and only a restart gives it C2 code again."

            isRecompiling(action) ->
                "$base Each occurrence throws away the compiled code and recompiles it, so the method " +
                    "spends part of its life interpreted."

            else -> base
        }
    }

    private fun normalise(reason: String): String = reason.lowercase().removePrefix("reason_")
}
