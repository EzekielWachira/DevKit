package io.devkit.netkit.scenario

/**
 * The HTTP verbs an [EndpointRule] can be scoped to.
 *
 * [ANY] matches every verb and is the default for new rules, because most QA
 * scenarios care about a path rather than a specific method.
 */
enum class HttpMethod {
    ANY,
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
    HEAD,
    OPTIONS,
    ;

    /** Label shown in the debug UI and in [io.devkit.netkit.history.NetworkRecord] summaries. */
    val label: String get() = if (this == ANY) "ANY" else name

    /**
     * Case-insensitive match against a raw wire method such as `"get"`.
     * [ANY] matches everything, including verbs NetKit does not model.
     */
    fun matches(rawMethod: String): Boolean =
        this == ANY || name.equals(rawMethod, ignoreCase = true)

    companion object {
        /** Verbs offered by the rule editor, in the order they are displayed. */
        val selectable: List<HttpMethod> = entries

        /** Resolves a wire method to a known verb, or `null` when NetKit does not model it. */
        fun fromRaw(rawMethod: String): HttpMethod? =
            entries.firstOrNull { it != ANY && it.name.equals(rawMethod, ignoreCase = true) }
    }
}
