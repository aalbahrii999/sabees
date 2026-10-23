package app.space

import java.net.URI
import java.net.URLEncoder

object Urls {
    fun resolve(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("http://") || text.startsWith("https://")) return text
        val noSpace = !text.contains(' ') && !text.contains('\n')
        val hostLike = text.contains('.') || text.equals("localhost", true)
        if (noSpace && hostLike) return "https://$text"
        val query = URLEncoder.encode(text, "UTF-8")
        return "https://www.google.com/search?hl=ar&q=$query"
    }

    fun host(url: String): String {
        return try {
            URI(url).host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url
        } catch (_: Exception) {
            url
        }
    }
}
