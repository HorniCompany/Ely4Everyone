package dev.ely4everyone.mod.auth

import java.net.URI

/**
 * Opens a web page in the default browser, the way Minecraft's Util.OS did before 26.3 removed it.
 * Only https links are opened: the page is always Ely.by's login.
 */
object BrowserOpener {
    fun open(url: String) {
        val uri = URI(url)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Refusing to open a non-https link: $url" }
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val command = when {
            os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", uri.toString())
            os.contains("mac") -> listOf("open", uri.toString())
            else -> listOf("xdg-open", uri.toString())
        }
        ProcessBuilder(command).redirectErrorStream(true).start().apply {
            // Nobody reads the output; close it so the helper never blocks on a full pipe.
            inputStream.close()
            outputStream.close()
        }
    }
}
