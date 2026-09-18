package com.tricrotism.windchill.telemetry

/**
 * JFR event names Windchill consumes. All verified present on JDK 25; the compiler events are the
 * ones nothing else in the Minecraft tooling space reads.
 */
object EventNames {

    const val COMPILATION = "jdk.Compilation"
    const val COMPILATION_FAILURE = "jdk.CompilationFailure"
    const val COMPILER_INLINING = "jdk.CompilerInlining"
    const val DEOPTIMIZATION = "jdk.Deoptimization"
    const val CODE_CACHE_FULL = "jdk.CodeCacheFull"
    const val CODE_CACHE_STATISTICS = "jdk.CodeCacheStatistics"
    const val JIT_RESTART = "jdk.JITRestart"
    const val COMPILER_QUEUE = "jdk.CompilerQueueUtilization"
    const val COMPILER_STATISTICS = "jdk.CompilerStatistics"
    const val EXECUTION_SAMPLE = "jdk.ExecutionSample"
    const val METHOD_TIMING = "jdk.MethodTiming"

    /** `RecordedFrame.type` values, as JDK 25 spells them. The fourth is "Native". */
    const val FRAME_INTERPRETED = "Interpreted"
    const val FRAME_COMPILED = "JIT compiled"
    const val FRAME_INLINED = "Inlined"
}
