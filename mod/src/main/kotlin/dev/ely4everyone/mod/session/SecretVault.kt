package dev.ely4everyone.mod.session

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** Keeps one small secret (the serialized Ely tokens) for the current OS user. */
interface SecretVault {
    /** Shown in the log, never the secret itself. */
    val description: String

    fun read(): String?

    fun write(secret: String)

    fun delete()
}

object SecretVaults {
    private val logger = LoggerFactory.getLogger("Ely4Everyone/SecretVault")

    /**
     * Windows: Credential Manager. macOS and Linux: an owner-only file in [directory].
     * The macOS Keychain asks for the login password whenever a different Java binary reads the item
     * (every launcher and Java update), and Secret Service is missing on many Linux setups, so both
     * use the file. The point of the vault is to keep tokens out of the game instance; the file does that too.
     */
    fun forCurrentOs(directory: Path): SecretVault {
        val fileVault = FileSecretVault(directory.resolve("secrets.properties"))
        if (!System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")) {
            return fileVault
        }
        return try {
            WindowsCredentialVault(WindowsCredentialVault.DEFAULT_TARGET).also { it.read() }
        } catch (error: Throwable) {
            // JNA missing or the API refused: a working login beats no login.
            logger.warn("Windows Credential Manager is unavailable, keeping the Ely login in {}: {}", fileVault.description, error.toString())
            fileVault
        }
    }
}

/** Plain file readable only by its owner where the file system supports POSIX permissions. */
class FileSecretVault(private val file: Path) : SecretVault {
    override val description: String = file.toString()

    override fun read(): String? {
        if (!Files.exists(file)) return null
        return Files.readString(file, StandardCharsets.UTF_8)
    }

    override fun write(secret: String) {
        Files.createDirectories(file.parent)
        val temp = Files.createTempFile(file.parent, "secrets", ".tmp")
        try {
            restrictToOwner(temp)
            Files.writeString(temp, secret, StandardCharsets.UTF_8)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun delete() {
        Files.deleteIfExists(file)
    }

    private fun restrictToOwner(path: Path) {
        // Windows has no POSIX view; %APPDATA% is per-user there anyway.
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
    }
}

/** A generic credential in Windows Credential Manager, encrypted by Windows for the current user. */
class WindowsCredentialVault(private val target: String) : SecretVault {
    override val description: String = "Windows Credential Manager ($target)"

    override fun read(): String? {
        val out = PointerByReference()
        if (!CredApi.INSTANCE.CredReadW(WString(target), CRED_TYPE_GENERIC, 0, out)) {
            val error = Native.getLastError()
            if (error == ERROR_NOT_FOUND) return null
            throw IllegalStateException("CredReadW failed with error $error")
        }
        try {
            val credential = Credential(out.value)
            if (credential.CredentialBlobSize == 0 || credential.CredentialBlob == null) return ""
            val bytes = credential.CredentialBlob!!.getByteArray(0, credential.CredentialBlobSize)
            return String(bytes, StandardCharsets.UTF_8)
        } finally {
            CredApi.INSTANCE.CredFree(out.value)
        }
    }

    override fun write(secret: String) {
        val bytes = secret.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_BLOB_SIZE) { "secret is ${bytes.size} bytes, Credential Manager takes at most $MAX_BLOB_SIZE" }
        val blob = Memory(maxOf(bytes.size, 1).toLong()).apply { write(0, bytes, 0, bytes.size) }
        val credential = Credential().apply {
            Type = CRED_TYPE_GENERIC
            TargetName = WString(target)
            Comment = WString("Ely4Everyone: Ely.by login shared by all Minecraft instances")
            UserName = WString("Ely4Everyone")
            CredentialBlobSize = bytes.size
            CredentialBlob = blob
            Persist = CRED_PERSIST_LOCAL_MACHINE
        }
        try {
            if (!CredApi.INSTANCE.CredWriteW(credential, 0)) {
                throw IllegalStateException("CredWriteW failed with error ${Native.getLastError()}")
            }
        } finally {
            blob.clear()
        }
    }

    override fun delete() {
        if (!CredApi.INSTANCE.CredDeleteW(WString(target), CRED_TYPE_GENERIC, 0)) {
            val error = Native.getLastError()
            if (error != ERROR_NOT_FOUND) throw IllegalStateException("CredDeleteW failed with error $error")
        }
    }

    /** CREDENTIALW from wincred.h. LastWritten (FILETIME) is spelled out as its two DWORDs. */
    @Suppress("PropertyName")
    @Structure.FieldOrder(
        "Flags", "Type", "TargetName", "Comment", "LastWrittenLow", "LastWrittenHigh", "CredentialBlobSize",
        "CredentialBlob", "Persist", "AttributeCount", "Attributes", "TargetAlias", "UserName",
    )
    class Credential() : Structure() {
        @JvmField var Flags: Int = 0
        @JvmField var Type: Int = 0
        @JvmField var TargetName: WString? = null
        @JvmField var Comment: WString? = null
        @JvmField var LastWrittenLow: Int = 0
        @JvmField var LastWrittenHigh: Int = 0
        @JvmField var CredentialBlobSize: Int = 0
        @JvmField var CredentialBlob: Pointer? = null
        @JvmField var Persist: Int = 0
        @JvmField var AttributeCount: Int = 0
        @JvmField var Attributes: Pointer? = null
        @JvmField var TargetAlias: WString? = null
        @JvmField var UserName: WString? = null

        constructor(pointer: Pointer) : this() {
            useMemory(pointer)
            read()
        }
    }

    @Suppress("FunctionName")
    private interface CredApi : StdCallLibrary {
        fun CredReadW(target: WString, type: Int, flags: Int, credential: PointerByReference): Boolean

        fun CredWriteW(credential: Credential, flags: Int): Boolean

        fun CredDeleteW(target: WString, type: Int, flags: Int): Boolean

        fun CredFree(buffer: Pointer)

        companion object {
            val INSTANCE: CredApi by lazy { Native.load("Advapi32", CredApi::class.java) }
        }
    }

    companion object {
        const val DEFAULT_TARGET: String = "Ely4Everyone/ely.by"

        private const val CRED_TYPE_GENERIC = 1
        private const val CRED_PERSIST_LOCAL_MACHINE = 2
        private const val ERROR_NOT_FOUND = 1168

        /** CRED_MAX_CREDENTIAL_BLOB_SIZE: 5 * 512 bytes. */
        private const val MAX_BLOB_SIZE = 5 * 512
    }
}
