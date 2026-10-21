package io.openflux.desktop.platform

import io.openflux.desktop.data.AppDirs
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Small secrets (a saved server password) kept by the OS, never in the clear: on Windows each one is a DPAPI blob
 * (the current user only) in a file under the app's folder; on macOS an item of the login Keychain; on Linux an item of
 * the Secret Service through `secret-tool`. The value travels through the helper's stdin, not its command line.
 */
class SecretStore(private val os: String = System.getProperty("os.name").lowercase()) {
    private val windows = os.contains("win")
    private val mac = os.contains("mac")

    val supported: Boolean by lazy {
        when {
            windows -> true
            mac -> true
            else -> run("secret-tool", listOf("--version"), "").first >= 0
        }
    }

    fun load(key: String): String? = runCatching {
        when {
            windows -> {
                val blob = file(key).takeIf { it.isFile }?.readText()?.trim().orEmpty()
                if (blob.isEmpty()) null else {
                    val (code, out) = run(POWERSHELL, listOf("-NoProfile", "-NonInteractive", "-Command", DPAPI_UNPROTECT), blob)
                    if (code == 0) unhex(out.trim()) else null
                }
            }
            mac -> {
                val (code, out) = run("security", listOf("find-generic-password", "-s", SERVICE, "-a", key, "-w"), "")
                if (code == 0) String(Base64.getDecoder().decode(out.trim()), Charsets.UTF_8) else null
            }
            else -> {
                val (code, out) = run("secret-tool", listOf("lookup", "service", SERVICE, "account", key), "")
                if (code == 0 && out.isNotEmpty()) out else null
            }
        }
    }.getOrNull()

    fun save(key: String, value: String): Boolean = runCatching {
        when {
            windows -> {
                val (code, out) = run(POWERSHELL, listOf("-NoProfile", "-NonInteractive", "-Command", DPAPI_PROTECT), hex(value))
                if (code == 0 && out.isNotBlank()) {
                    file(key).apply { parentFile.mkdirs() }.writeText(out.trim())
                    true
                } else false
            }
            mac -> {
                val b64 = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
                run("security", listOf("-i"), "add-generic-password -U -s $SERVICE -a \"$key\" -w \"$b64\"\n").first == 0
            }
            else -> run("secret-tool", listOf("store", "--label=OpenFlux", "service", SERVICE, "account", key), value).first == 0
        }
    }.getOrDefault(false)

    fun delete(key: String) {
        runCatching {
            when {
                windows -> file(key).delete()
                mac -> run("security", listOf("delete-generic-password", "-s", SERVICE, "-a", key), "")
                else -> run("secret-tool", listOf("clear", "service", SERVICE, "account", key), "")
            }
        }
    }

    private fun file(key: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(AppDirs.config, "secrets"), "$name.dpapi")
    }

    /** Exit code (-1 when the program is missing or hung) and stdout. */
    private fun run(program: String, args: List<String>, stdin: String): Pair<Int, String> = try {
        val p = ProcessBuilder(listOf(program) + args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        p.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) }
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        if (p.waitFor(15, TimeUnit.SECONDS)) p.exitValue() to out else { p.destroyForcibly(); -1 to "" }
    } catch (_: Exception) {
        -1 to ""
    }

    private fun hex(text: String) = text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
    private fun unhex(h: String) = String(ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, Charsets.UTF_8)

    private companion object {
        const val SERVICE = "OpenFlux"
        const val POWERSHELL = "powershell.exe"
        const val HEXTOBYTES = "\$h=[Console]::In.ReadToEnd().Trim(); \$b=[byte[]]::new(\$h.Length/2); for(\$i=0;\$i -lt \$b.Length;\$i++){\$b[\$i]=[Convert]::ToByte(\$h.Substring(2*\$i,2),16)};"
        const val DPAPI_PROTECT = "Add-Type -AssemblyName System.Security; $HEXTOBYTES " +
            "[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Protect(\$b,\$null,'CurrentUser'))"
        const val DPAPI_UNPROTECT = "Add-Type -AssemblyName System.Security; \$s=[Console]::In.ReadToEnd().Trim(); " +
            "\$p=[Security.Cryptography.ProtectedData]::Unprotect([Convert]::FromBase64String(\$s),\$null,'CurrentUser'); " +
            "(\$p|ForEach-Object{'{0:x2}' -f \$_}) -join ''"
    }
}
