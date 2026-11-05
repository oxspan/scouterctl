package dev.scouterctl.core

data class SavedConnection(val host: String, val port: Int, val user: String) {
    fun endpoint(timeoutMs: Int) = Endpoint(host, port, user, timeoutMs)
}

data class ContextConfig(
    val schemaVersion: String = "scouterctl.contexts/v1",
    val current: String? = null,
    val contexts: Map<String, SavedConnection> = emptyMap(),
) {
    fun validate() {
        if (schemaVersion != "scouterctl.contexts/v1" || (current != null && current !in contexts)) {
            throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Unsupported or inconsistent context config")
        }
        contexts.forEach { (name, connection) -> validateContextName(name); connection.endpoint(5000) }
    }
}

internal fun validateContextName(name: String) {
    if (name.codePointCount(0, name.length) !in 1..64 ||
        !name.matches(Regex("[\\p{L}\\p{Nd}][\\p{L}\\p{Nd}\\p{M}._-]*"))) {
        throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Context name must be 1..64 Unicode code points: letters, digits, combining marks, dots, underscores or hyphens; start with a letter or digit")
    }
}
