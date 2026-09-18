package com.tricrotism.windchill.telemetry

import org.slf4j.Logger
import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassModel
import java.lang.classfile.attribute.CodeAttribute
import java.lang.classfile.constantpool.MemberRefEntry
import java.lang.reflect.AccessFlag

/**
 * Facts only a class file holds, read from the class files plugins shipped.
 *
 * Two HotSpot decisions turn on bytecode size rather than on anything a profiler shows: a callee over
 * FreqInlineSize is not inlined where it is hot, and a method over [JitLimits.HUGE_METHOD_LIMIT] is
 * never compiled at all. A sampled bytecode index only means something once the instruction there
 * is known. No JFR event carries either, so the class file is the only source.
 *
 * Reads through the owning plugin's class loader, which finds the class wherever that plugin actually
 * got it from. Runtime-generated classes have no class file and are skipped.
 */
object ClassFiles {

    data class Facts(
        val sizes: Map<MethodRef, Int>,
        val calls: Map<Pair<MethodRef, Int>, CallTarget>,
    )

    /**
     * @param sites bytecode indexes whose instruction is wanted, per method
     * @param loaderFor the class loader that defined a class, or null when it is not plugin code
     */
    fun inspect(
        methods: Collection<MethodRef>,
        sites: Map<MethodRef, Set<Int>>,
        loaderFor: (String) -> ClassLoader?,
        logger: Logger,
    ): Facts {
        val sizes = HashMap<MethodRef, Int>()
        val calls = HashMap<Pair<MethodRef, Int>, CallTarget>()
        val wanted = (methods + sites.keys).filterNot { it.hidden }.groupBy { it.className }

        for ((className, refs) in wanted) {
            val loader = loaderFor(className) ?: continue
            val bytes = try {
                loader.getResourceAsStream(className.replace('.', '/') + ".class")?.use { it.readBytes() }
            } catch (e: java.io.IOException) {
                logger.debug("Could not read the class file for {}", className, e)
                null
            } ?: continue

            try {
                val model = ClassFile.of().parse(bytes)
                val codeBySignature = model.methods().mapNotNull { method ->
                    method.findAttribute(Attributes.code()).orElse(null)?.let {
                        (method.methodName().stringValue() + method.methodType().stringValue()) to it
                    }
                }.toMap()

                for (ref in refs.distinct()) {
                    val code = codeBySignature[ref.methodName + ref.descriptor] ?: continue
                    sizes[ref] = code.codeLength()
                    sites[ref]?.forEach { bci -> callAt(model, code, bci)?.let { calls[ref to bci] = it } }
                }
            } catch (e: IllegalArgumentException) {
                logger.debug("Could not parse the class file for {}", className, e)
            }
        }

        return Facts(sizes, calls)
    }

    /** The call instruction at [bci], or null when the instruction there is not a call. */
    private fun callAt(model: ClassModel, code: CodeAttribute, bci: Int): CallTarget? {
        val bytecode = code.codeArray()
        if (bci < 0 || bci + 2 >= bytecode.size) return null

        val opcode = INVOKES[bytecode[bci].toInt() and 0xFF] ?: return null
        val index = ((bytecode[bci + 1].toInt() and 0xFF) shl 8) or (bytecode[bci + 2].toInt() and 0xFF)
        val member = model.constantPool().entryByIndex(index) as? MemberRefEntry ?: return null
        val name = member.name().stringValue()
        val descriptor = member.type().stringValue()

        return CallTarget(
            opcode,
            member.owner().asInternalName().replace('/', '.'),
            name,
            descriptor,
            bound = opcode == "invokestatic" || opcode == "invokespecial" ||
                (
                    opcode == "invokevirtual" &&
                        member.owner().asInternalName() == model.thisClass().asInternalName() &&
                        fixedHere(model, name, descriptor)
                    ),
        )
    }

    /** A private or final method of this class, or any method of a final class, cannot be overridden. */
    private fun fixedHere(model: ClassModel, name: String, descriptor: String): Boolean {
        if (model.flags().has(AccessFlag.FINAL)) return true
        val method = model.methods().firstOrNull {
            it.methodName().stringValue() == name && it.methodType().stringValue() == descriptor
        } ?: return false

        return method.flags().has(AccessFlag.PRIVATE) || method.flags().has(AccessFlag.FINAL)
    }

    private val INVOKES = mapOf(
        0xB6 to "invokevirtual",
        0xB7 to "invokespecial",
        0xB8 to "invokestatic",
        0xB9 to "invokeinterface",
    )
}
