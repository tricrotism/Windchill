package com.tricrotism.windchill.telemetry

/**
 * Samples whose top frame was compiled code of [method], paused at bytecode index [bci].
 *
 * When that index is a call instruction, the thread was in the call itself rather than in code the
 * call had been inlined into: an inlined callee shows up as its own `Inlined` frame instead. Measured
 * on JDK 25, a four-way interface call site took 535 of its method's 552 samples at exactly the
 * `invokeinterface`, while the same loop with one receiver type had none there.
 */
data class SiteSamples(val method: MethodRef, val bci: Int, val line: Int, val samples: Long)

/**
 * The instruction at a sampled site, read from the class file. [owner] is in binary form.
 *
 * @param bound whether [method] is exactly what runs: a static, special or constructor call, or a
 *   virtual call to a private or final method of the caller's own class, which javac emits as
 *   `invokevirtual` for private methods since nestmates
 */
data class CallTarget(
    val opcode: String,
    val owner: String,
    val name: String,
    val descriptor: String,
    val bound: Boolean,
) {

    /** A call HotSpot has to resolve by receiver type, which is the only kind a type profile decides. */
    val dispatched: Boolean
        get() = !bound && (opcode == "invokevirtual" || opcode == "invokeinterface")

    /** The method the instruction names. For a dispatched call, the declaration rather than what runs. */
    val method: MethodRef
        get() = MethodRef(owner, name, descriptor)

    val label: String
        get() = "${owner.substringAfterLast('.')}.$name"
}
