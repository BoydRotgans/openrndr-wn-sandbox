import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Duration

fun okFetcher(key: String, timeoutInSeconds: Long = 60): (String) -> String {
    val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(timeoutInSeconds))
        .readTimeout(Duration.ofSeconds(timeoutInSeconds))
        .build()


    return fun(url: String): String {
        val request = Request.Builder()
            .url("https://api.figma.com$url")
            .addHeader("X-Figma-Token", key)
            .build()

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            response.body?.string()
        }

        return body ?: error("no body")
    }
}

fun okFileDownloader(key: String, timeoutInSeconds: Long = 60): (String, File) -> Unit {
    val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(timeoutInSeconds))
        .readTimeout(Duration.ofSeconds(timeoutInSeconds))
        .build()

    return fun (url: String, targetFile: java.io.File) {
        println("fetching '${targetFile.path}' from '$url'")

        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            targetFile.writeBytes(response.body?.bytes() ?: error("no response body"))
        }
    }
}