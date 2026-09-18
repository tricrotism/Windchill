package com.tricrotism.windchill.telemetry

import com.tricrotism.windchill.aot.VmFlags

/**
 * The HotSpot limits that decide whether a method is inlined, or compiled at all, read from the
 * running VM. FreqInlineSize is platform-dependent and any of these may have been tuned, so a finding
 * that quotes a remembered default can be wrong on the server it is written about.
 */
data class JitLimits(
    val freqInlineSize: Int,
    val maxInlineSize: Int,
    val inlineSmallCode: Int,
    val compilesHugeMethods: Boolean,
) {

    /** Whether HotSpot refuses to compile a method of this many bytecode bytes, ever. */
    fun neverCompiles(bytecodeSize: Int): Boolean = !compilesHugeMethods && bytecodeSize > HUGE_METHOD_LIMIT

    companion object {

        /**
         * HugeMethodLimit is a develop flag, compiled into product builds as a constant and not
         * readable. Measured on JDK 25: a hot 8000-byte method compiles to tier 4, an 8001-byte one
         * never compiles and runs interpreted in every sample.
         */
        const val HUGE_METHOD_LIMIT = 8000

        /** @return the live limits, or null when this VM does not expose them */
        fun read(): JitLimits? {
            val freq = VmFlags.long(VmFlags.FREQ_INLINE_SIZE) ?: return null
            val max = VmFlags.long(VmFlags.MAX_INLINE_SIZE) ?: return null
            val smallCode = VmFlags.long(VmFlags.INLINE_SMALL_CODE) ?: return null
            val dontCompileHuge = VmFlags.boolean(VmFlags.DONT_COMPILE_HUGE_METHODS) ?: return null

            return JitLimits(freq.toInt(), max.toInt(), smallCode.toInt(), !dontCompileHuge)
        }
    }
}
