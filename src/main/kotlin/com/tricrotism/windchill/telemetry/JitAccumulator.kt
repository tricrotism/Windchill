package com.tricrotism.windchill.telemetry

import com.tricrotism.windchill.attribution.Owner
import com.tricrotism.windchill.attribution.PluginIndex
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedMethod
import jdk.jfr.consumer.RecordedObject

/**
 * Folds a window of JFR compiler events into per-method totals.
 *
 * Confined to the JFR stream thread. That thread is the single consumer, so the maps below are plain
 * and unsynchronised on purpose: confinement is the faster choice here, not a compromise. Nothing
 * outside reads them until [snapshot] is taken, after the stream has terminated.
 *
 * Every map is capped. jdk.CompilerInlining fires once per inlining decision per compilation, which
 * on a busy server is six figures a minute, so an uncapped map here would be the leak this tool
 * exists to find.
 */
internal class JitAccumulator(
    private val index: PluginIndex,
    private val limits: Limits,
) {

    data class Limits(
        val maxMethods: Int,
        val maxInlineFailures: Int,
        val maxDeoptGroups: Int,
        val maxFrameDepth: Int,
    )

    private class MutableProfile {
        var selfSamples = 0L
        var stackSamples = 0L
        var interpretedSelfSamples = 0L
        var compilations = 0
        var topCompileLevel = 0
        var compiledBytes = 0L
        var largestCompiledBytes = 0L
        var inlinedBytes = 0L
        var deoptimisations = 0L
        var failureMessages: HashMap<String, Int>? = null
    }

    private class MutableDeopt {
        var occurrences = 0L
        val sites = HashSet<DeoptSite>()
        val callers = HashMap<DeoptCaller, Long>()
    }

    private class MutableHeap(val sizeBytes: Long) {
        var peakUsedBytes = 0L
        var fullCount = 0
    }

    private class MutableSite(val line: Int) {
        var samples = 0L
    }

    private data class InlineKey(val caller: MethodRef, val callee: MethodRef, val bci: Int, val reason: String)
    private data class DeoptKey(val method: MethodRef, val reason: String, val action: String)
    private data class SiteKey(val method: MethodRef, val bci: Int)
    private data class CompileRecord(val method: MethodRef, val succeeded: Boolean)

    private val profiles = HashMap<MethodRef, MutableProfile>()
    private val inlineFailures = HashMap<InlineKey, Long>()
    private val deopts = HashMap<DeoptKey, MutableDeopt>()
    private val sites = HashMap<SiteKey, MutableSite>()
    private val compiles = HashMap<Int, CompileRecord>()
    private val failuresByCompile = HashMap<Int, String>()
    private val ownerCache = HashMap<String, Owner>()

    private var droppedMethods = 0L
    private var droppedInlineFailures = 0L
    private var droppedDeopts = 0L
    private var droppedSites = 0L

    private val heaps = HashMap<String, MutableHeap>()

    private var totalSamples = 0L
    private var codeCacheFullEvents = 0
    private var jitRestarts = 0
    private var peakQueueSize = 0L

    private val c2QueueSizes = ArrayList<Long>()
    private var standardCompiles = 0L
    private var osrCompiles = 0L

    fun accept(event: RecordedEvent) {
        when (event.eventType.name) {
            EventNames.EXECUTION_SAMPLE -> onExecutionSample(event)
            EventNames.COMPILATION -> onCompilation(event)
            EventNames.COMPILATION_FAILURE -> onCompilationFailure(event)
            EventNames.COMPILER_INLINING -> onInlining(event)
            EventNames.DEOPTIMIZATION -> onDeoptimisation(event)
            EventNames.CODE_CACHE_FULL -> codeCacheFullEvents++
            EventNames.CODE_CACHE_STATISTICS -> onCodeCacheStatistics(event)
            EventNames.JIT_RESTART -> jitRestarts++
            EventNames.COMPILER_QUEUE -> onCompilerQueue(event)
            EventNames.COMPILER_STATISTICS -> onCompilerStatistics(event)
        }
    }

    private fun onExecutionSample(event: RecordedEvent) {
        val frames = event.stackTrace?.frames ?: return
        if (frames.isEmpty()) return

        totalSamples++
        val top = frames[0]
        methodRef(top.method)?.let { ref ->
            profile(ref)?.let {
                it.selfSamples++
                if (top.type == EventNames.FRAME_INTERPRETED) it.interpretedSelfSamples++
            }
            if ((top.type == EventNames.FRAME_COMPILED || top.type == EventNames.FRAME_INLINED) && blameable(ref.className)) {
                site(ref, top.bytecodeIndex, top.lineNumber)
            }
        }

        val depth = minOf(frames.size, limits.maxFrameDepth)
        val seen = HashSet<MethodRef>(depth)
        for (i in 0 until depth) {
            val ref = methodRef(frames[i].method) ?: continue
            if (seen.add(ref)) profile(ref)?.let { it.stackSamples++ }
        }
    }

    private fun onCompilation(event: RecordedEvent) {
        val ref = methodRef(event.getValue<RecordedMethod>("method")) ?: return

        // The JDK spells this field with one 'e' on jdk.Compilation and two on jdk.CompilerInlining.
        val succeeded = event.getBoolean("succeded")
        if (compiles.size < limits.maxMethods) compiles[event.getInt("compileId")] = CompileRecord(ref, succeeded)
        if (!succeeded) return

        val profile = profile(ref) ?: return
        profile.compilations++
        profile.topCompileLevel = maxOf(profile.topCompileLevel, event.getInt("compileLevel"))
        val codeSize = event.getLong("codeSize")
        profile.compiledBytes += codeSize
        profile.largestCompiledBytes = maxOf(profile.largestCompiledBytes, codeSize)
        profile.inlinedBytes += event.getLong("inlinedBytes")
    }

    /**
     * Held by compile id until the window closes, then kept only if that compile ended in failure.
     *
     * C2 posts a failure for each internal retry, such as retrying without escape analysis, and the
     * retry often succeeds under the same compile id. Retransforming any class also abandons every
     * compile in flight with "Jvmti state change invalidated dependencies", so an agent attaching or a
     * JFR timing window opening would otherwise read as a burst of methods too complex to compile.
     */
    private fun onCompilationFailure(event: RecordedEvent) {
        val message = event.getString("failureMessage") ?: "unknown"
        if (message.contains("jvmti", ignoreCase = true)) return
        if (failuresByCompile.size < limits.maxDeoptGroups) failuresByCompile.putIfAbsent(event.getInt("compileId"), message)
    }

    private fun site(ref: MethodRef, bci: Int, line: Int) {
        val key = SiteKey(ref, bci)
        val existing = sites[key]
        if (existing == null && sites.size >= limits.maxMethods) {
            droppedSites++
            return
        }

        (existing ?: MutableSite(line).also { sites[key] = it }).samples++
    }

    private fun onInlining(event: RecordedEvent) {
        if (event.getBoolean("succeeded")) return

        val caller = methodRef(event.getValue<RecordedMethod>("caller")) ?: return
        val callee = calleeRef(event.getValue<RecordedObject>("callee")) ?: return
        if (!blameable(caller.className) && !blameable(callee.className)) return

        val key = InlineKey(caller, callee, event.getInt("bci"), event.getString("message") ?: "unknown")
        val existing = inlineFailures[key]
        if (existing == null && inlineFailures.size >= limits.maxInlineFailures) {
            droppedInlineFailures++
            return
        }

        inlineFailures[key] = (existing ?: 0L) + 1L
    }

    private fun onDeoptimisation(event: RecordedEvent) {
        val ref = methodRef(event.getValue<RecordedMethod>("method")) ?: return
        profile(ref)?.let { it.deoptimisations++ }
        if (!blameable(ref.className)) return

        val key = DeoptKey(ref, event.getString("reason") ?: "unknown", event.getString("action") ?: "unknown")
        val tally = deopts[key] ?: run {
            if (deopts.size >= limits.maxDeoptGroups) {
                droppedDeopts++
                return
            }

            MutableDeopt().also { deopts[key] = it }
        }

        tally.occurrences++
        if (tally.sites.size < DEOPT_SITE_CAP) {
            tally.sites.add(
                DeoptSite(event.getInt("bci"), event.getInt("lineNumber"), event.getString("instruction") ?: "?"),
            )
        }
        caller(event, ref)?.let { caller ->
            if (tally.callers.size < DEOPT_CALLER_CAP || caller in tally.callers) tally.callers.merge(caller, 1L, Long::plus)
        }
    }

    /** The frame below the deoptimised method on the event's stack, or null when the stack does not show it. */
    private fun caller(event: RecordedEvent, deoptimised: MethodRef): DeoptCaller? {
        val frames = event.stackTrace?.frames ?: return null
        val at = frames.indexOfFirst { methodRef(it.method) == deoptimised }
        if (at < 0) return null
        val frame = frames.getOrNull(at + 1) ?: return null
        val method = methodRef(frame.method) ?: return null

        return DeoptCaller(method, frame.lineNumber)
    }

    /**
     * One row per code heap per period. The heap's size is the span between its start and reserved top,
     * which matches `-XX:ProfiledCodeHeapSize` and its siblings exactly. HotSpot has at most three code
     * heaps, so the map is bounded by the VM rather than by a cap.
     */
    private fun onCodeCacheStatistics(event: RecordedEvent) {
        val name = event.getString("codeBlobType") ?: return
        val size = event.getLong("reservedTopAddress") - event.getLong("startAddress")
        if (size <= 0) return

        val heap = heaps.getOrPut(name) { MutableHeap(size) }
        heap.peakUsedBytes = maxOf(heap.peakUsedBytes, size - event.getLong("unallocatedCapacity"))
        heap.fullCount = maxOf(heap.fullCount, event.getInt("fullCount"))
    }

    private fun onCompilerQueue(event: RecordedEvent) {
        peakQueueSize = maxOf(peakQueueSize, event.getLong("peakQueueSize"))
        if (event.getString("compiler") == C2) c2QueueSizes += event.getLong("queueSize")
    }

    private fun onCompilerStatistics(event: RecordedEvent) {
        standardCompiles = maxOf(standardCompiles, event.getInt("standardCompileCount").toLong())
        osrCompiles = maxOf(osrCompiles, event.getInt("osrCompileCount").toLong())
    }

    fun snapshot(windowMillis: Long): JitSnapshot {
        for ((compileId, message) in failuresByCompile) {
            val compile = compiles[compileId] ?: continue
            if (compile.succeeded) continue

            val profile = profile(compile.method) ?: continue
            val messages = profile.failureMessages ?: HashMap<String, Int>().also { profile.failureMessages = it }
            if (messages.size < FAILURE_MESSAGE_CAP || message in messages) messages.merge(message, 1, Int::plus)
        }

        val resolved = profiles.map { (ref, p) ->
            MethodProfile(
                method = ref,
                selfSamples = p.selfSamples,
                stackSamples = p.stackSamples,
                interpretedSelfSamples = p.interpretedSelfSamples,
                compilations = p.compilations,
                topCompileLevel = p.topCompileLevel,
                compiledBytes = p.compiledBytes,
                largestCompiledBytes = p.largestCompiledBytes,
                inlinedBytes = p.inlinedBytes,
                deoptimisations = p.deoptimisations,
                failureMessages = p.failureMessages?.toMap() ?: emptyMap(),
            )
        }

        return JitSnapshot(
            windowMillis = windowMillis,
            totalSamples = totalSamples,
            profiles = resolved,
            inlineFailures = inlineFailures.map { (key, count) ->
                InlineFailure(key.caller, key.callee, key.bci, key.reason, count)
            },
            deoptimisations = deopts.map { (key, tally) ->
                DeoptTally(key.method, key.reason, key.action, tally.occurrences, tally.sites.toSet(), tally.callers.toMap())
            },
            callSites = sites.map { (key, site) -> SiteSamples(key.method, key.bci, site.line, site.samples) },
            codeCache = CodeCacheState(
                fullEvents = codeCacheFullEvents,
                jitRestarts = jitRestarts,
                heaps = heaps.map { (name, heap) -> CodeHeapState(name, heap.sizeBytes, heap.peakUsedBytes, heap.fullCount) },
                peakQueueLength = peakQueueSize,
                medianC2QueueLength = c2QueueSizes.sorted().let { if (it.isEmpty()) 0L else it[it.size / 2] },
                standardCompileCount = standardCompiles,
                osrCompileCount = osrCompiles,
            ),
            droppedMethods = droppedMethods,
            droppedInlineFailures = droppedInlineFailures,
            droppedDeoptGroups = droppedDeopts,
            droppedSites = droppedSites,
            owners = ownerCache.toMap(),
        )
    }

    private fun profile(method: RecordedMethod?): MutableProfile? = methodRef(method)?.let { profile(it) }

    private fun profile(ref: MethodRef): MutableProfile? {
        profiles[ref]?.let { return it }

        if (profiles.size >= limits.maxMethods) {
            droppedMethods++
            return null
        }

        return MutableProfile().also { profiles[ref] = it }
    }

    private fun methodRef(method: RecordedMethod?): MethodRef? {
        val type = method?.type?.name ?: return null
        val ref = MethodRef.of(type, method.name, method.descriptor)
        owner(ref.className)

        return ref
    }

    /**
     * jdk.CompilerInlining describes its callee with a plain CalleeMethod rather than the Method type
     * every other event uses, so the class name arrives in internal form and, for generated classes,
     * with a different separator before the address. [MethodRef.of] reconciles both.
     */
    private fun calleeRef(callee: RecordedObject?): MethodRef? {
        val type = callee?.getString("type") ?: return null
        val name = callee.getString("name") ?: return null
        val ref = MethodRef.of(type, name, callee.getString("descriptor") ?: "")
        owner(ref.className)

        return ref
    }

    private fun owner(className: String): Owner = ownerCache.getOrPut(className) { index.owner(className) }

    private fun blameable(className: String): Boolean = owner(className).blameable

    private companion object {
        const val DEOPT_SITE_CAP = 16
        const val DEOPT_CALLER_CAP = 4
        const val FAILURE_MESSAGE_CAP = 4
        const val C2 = "c2"
    }
}
