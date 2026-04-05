package dev.ely4everyone.mod.research26

import org.slf4j.LoggerFactory

object Research26TraceBus {
    private val logger = LoggerFactory.getLogger("ely4everyone/research26")
    private val recorder = Research26TraceRecorder(enabled = enabled())
    private val emitted = linkedSetOf<Pair<String, String>>()

    fun enabled(): Boolean {
        return System.getProperty("ely4everyone.research26.trace", "false").equals("true", ignoreCase = true)
    }

    fun record(operation: String, url: String) {
        recorder.record(operation, url)
        if (!enabled()) {
            return
        }
        val key = operation to url
        if (emitted.add(key)) {
            val classification = AuthlibEndpointClassifier.classify(url)
            logger.info(
                "research26 trace: operation={}, kind={}, mojang={}, services={}, url={}",
                operation,
                classification.kind,
                classification.isMojang,
                classification.isMinecraftServices,
                url,
            )
        }
    }

    fun snapshot(): List<Research26TraceEntry> = recorder.snapshot()
}
