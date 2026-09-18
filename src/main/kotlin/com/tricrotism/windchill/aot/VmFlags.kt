package com.tricrotism.windchill.aot

import com.sun.management.HotSpotDiagnosticMXBean
import java.lang.management.ManagementFactory

/**
 * Live VM flag values, read from the running JVM rather than assumed.
 *
 * Every tuning number HotSpot uses is deployment-dependent: ReservedCodeCacheSize and CICompilerCount
 * are set by ergonomics from the machine's cores and memory, and a flag's default differs between
 * JDK builds. A recommendation that cites a remembered default is a guess, so everything here comes
 * from HotSpotDiagnosticMXBean.
 */
object VmFlags {

    private val bean: HotSpotDiagnosticMXBean? = runCatching {
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
    }.getOrNull()

    /**
     * @return the flag's current value, or null when the flag does not exist on this JVM
     */
    fun value(name: String): String? =
        runCatching { bean?.getVMOption(name)?.value }.getOrNull()

    fun long(name: String): Long? = value(name)?.toLongOrNull()

    fun boolean(name: String): Boolean? = value(name)?.toBooleanStrictOrNull()

    const val FREQ_INLINE_SIZE = "FreqInlineSize"
    const val MAX_INLINE_SIZE = "MaxInlineSize"
    const val INLINE_SMALL_CODE = "InlineSmallCode"
    const val DONT_COMPILE_HUGE_METHODS = "DontCompileHugeMethods"
    const val COMPILE_COMMAND = "CompileCommand"
    const val RESERVED_CODE_CACHE_SIZE = "ReservedCodeCacheSize"
    const val CI_COMPILER_COUNT = "CICompilerCount"
    const val PER_METHOD_RECOMPILATION_CUTOFF = "PerMethodRecompilationCutoff"

    const val AOT_RECORD_TRAINING = "AOTRecordTraining"
    const val AOT_REPLAY_TRAINING = "AOTReplayTraining"
}
