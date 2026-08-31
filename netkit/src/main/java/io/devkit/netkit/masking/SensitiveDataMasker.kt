package io.devkit.netkit.masking

/** A single header as it is stored in history, already masked when sensitive. */
data class MaskedHeader(
    val name: String,
    val value: String,
    val masked: Boolean,
)

/**
 * Redacts credentials before anything reaches the history store.
 *
 * Masking happens on the way **in**, not on the way out: NetKit never keeps a
 * raw token in memory, so copying a request from the detail screen, exporting a
 * QA session or dumping history in a future release cannot leak one.
 *
 * Implementations must be thread-safe — masking runs on OkHttp's request threads.
 */
interface SensitiveDataMasker {

    /** Masks a header list, preserving order and duplicates. */
    fun maskHeaders(headers: List<Pair<String, String>>): List<MaskedHeader>

    /**
     * Masks credential-bearing query parameters in an absolute URL.
     * Returns the URL unchanged when nothing sensitive is present.
     */
    fun maskUrl(url: String): String
}
