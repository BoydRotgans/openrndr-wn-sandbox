import java.io.File

/**
 * Reads configuration from a .env file in the project root.
 *
 * Real environment variables take precedence, so you can override any single
 * value for one run without editing the file:
 *
 *     FIGMA_FRAME="Other Frame" ./gradlew run -Popenrndr.application=FigmaFrameProgramKt
 *
 * .env is gitignored because it holds your personal access token. .env.example
 * is the committed template.
 */
object Env {

    private val file = File(".env")

    private val fromFile: Map<String, String> by lazy {
        if (!file.isFile) emptyMap() else file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .associate { line ->
                val withoutExport = line.removePrefix("export ").trim()
                val key = withoutExport.substringBefore('=').trim()
                val value = withoutExport.substringAfter('=').trim().unquoted()
                key to value
            }
    }

    /** The value for [key], or null when it is unset or blank in both sources. */
    operator fun get(key: String): String? =
        System.getenv(key)?.takeIf { it.isNotBlank() }
            ?: fromFile[key]?.takeIf { it.isNotBlank() }

    /** The value for [key], or a message explaining exactly what to fill in. */
    fun require(key: String): String = get(key) ?: error(
        "$key is not set. Copy .env.example to .env and fill in $key" +
                if (file.isFile) " (.env exists but $key is empty)." else " (.env does not exist yet)."
    )

    fun boolean(key: String, default: Boolean = false): Boolean =
        get(key)?.lowercase()?.let { it == "true" || it == "1" || it == "yes" } ?: default

    private fun String.unquoted() =
        if (length >= 2 && (startsWith('"') && endsWith('"') || startsWith('\'') && endsWith('\'')))
            substring(1, length - 1) else this
}
