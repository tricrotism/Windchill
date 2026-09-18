package com.tricrotism.windchill.telemetry

import com.tricrotism.windchill.attribution.Owner
import org.slf4j.Logger
import javax.management.JMException

/**
 * Machine code one owner currently holds in the code cache.
 *
 * Measured from what is resident now, not from what compiled during a window, so a plugin that
 * compiled everything an hour ago still shows its full footprint.
 */
data class Residency(val owner: Owner, val bytes: Long, val methods: Int)

/**
 * Reads the code cache's contents through `Compiler.codelist`, the same diagnostic command `jcmd`
 * runs, invoked in-process through the DiagnosticCommand MBean.
 *
 * Each line is `compileId level state Class.method(descriptor) [header, codeBegin - codeEnd]`. The
 * span from header to code end undercounts an nmethod slightly, since its metadata follows the code,
 * so these are floors rather than exact sizes. Measured on JDK 25 at about 3ms for 1,900 nmethods.
 */
object CodeResidency {

    /**
     * @return per-owner totals, largest first, or empty when the command is unavailable
     */
    fun read(owner: (String) -> Owner, logger: Logger): List<Residency> {
        val listing = try {
            DiagnosticCommand.run("compilerCodelist")
        } catch (e: JMException) {
            logger.warn("Compiler.codelist failed, so code cache residency is unavailable", e)
            null
        } ?: return emptyList()

        val owners = HashMap<String, Owner>()
        val bytes = HashMap<Owner, Long>()
        val methods = HashMap<Owner, Int>()

        for (line in listing.lineSequence()) {
            val entry = parse(line) ?: continue
            val who = owners.getOrPut(entry.first) { owner(entry.first) }
            bytes.merge(who, entry.second, Long::plus)
            methods.merge(who, 1, Int::plus)
        }

        return bytes.map { (who, total) -> Residency(who, total, methods[who] ?: 0) }.sortedByDescending { it.bytes }
    }

    /** @return the class name and the nmethod's size, or null for a line that is not an nmethod */
    private fun parse(line: String): Pair<String, Long>? {
        val open = line.lastIndexOf('[')
        val close = line.lastIndexOf(']')
        if (open < 0 || close < open) return null

        val signature = line.substring(0, open).trim().split(' ', limit = 4).getOrNull(3) ?: return null
        val className = signature.substringBefore('(').substringBeforeLast('.', "")
        if (className.isEmpty()) return null

        val addresses = line.substring(open + 1, close)
        val start = address(addresses.substringBefore(',')) ?: return null
        val end = address(addresses.substringAfterLast('-')) ?: return null

        return MethodRef.normalise(className) to (end - start).coerceAtLeast(0)
    }

    private fun address(text: String): Long? = text.trim().removePrefix("0x").toLongOrNull(16)
}
