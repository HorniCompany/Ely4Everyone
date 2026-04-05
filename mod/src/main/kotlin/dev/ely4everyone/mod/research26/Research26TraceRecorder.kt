package dev.ely4everyone.mod.research26

data class Research26TraceEntry(
    val operation: String,
    val classification: AuthlibEndpointClassification,
)

class Research26TraceRecorder(
    private val enabled: Boolean,
) {
    private val entries = linkedSetOf<Pair<String, String>>()

    fun record(operation: String, url: String) {
        if (!enabled) {
            return
        }
        entries += operation to url
    }

    fun snapshot(): List<Research26TraceEntry> {
        return entries.map { (operation, url) ->
            Research26TraceEntry(
                operation = operation,
                classification = AuthlibEndpointClassifier.classify(url),
            )
        }
    }
}
