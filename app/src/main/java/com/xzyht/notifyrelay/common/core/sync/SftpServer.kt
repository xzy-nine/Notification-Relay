package com.xzyht.notifyrelay.common.core.sync

import android.util.Base64
import com.xzyht.notifyrelay.common.core.util.Logger
import org.apache.sshd.common.file.nativefs.NativeFileSystemFactory
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.session.SessionContext
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.server.ServerBuilder
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.nio.file.Paths
import java.security.KeyPair
import java.security.MessageDigest

data class SftpServerInfo(
    val username: String,
    val password: String,
    val ipAddress: String,
    val port: Int
)

object SftpServer {
    private const val TAG = "SftpServer"
    private const val DERIVED_USERNAME_PREFIX = "sftp_"
    private const val DERIVED_PASSWORD_LENGTH = 32

    private val PORT_RANGE = 5151..5169

    private var sshd: org.apache.sshd.server.SshServer? = null
    private var isRunning = false
    private var serverInfo: SftpServerInfo? = null

    init {
        System.setProperty(SecurityUtils.SECURITY_PROVIDER_REGISTRARS, "")
        System.setProperty(
            "org.apache.sshd.common.io.IoServiceFactoryFactory",
            "org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory"
        )
        PathUtils.setUserHomeFolderResolver {
            Paths.get("/")
        }
    }

    private fun createSelfSignedKeyPair(): KeyPair {
        val keyPairGenerator = java.security.KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        return keyPairGenerator.generateKeyPair()
    }

    private class PfxKeyPairProvider : KeyPairProvider {
        private val keyPair: KeyPair = createSelfSignedKeyPair()

        override fun loadKeys(session: SessionContext?): Iterable<KeyPair> = listOf(keyPair)
    }

    private class SimpleFileSystemFactory : VirtualFileSystemFactory() {
        init {
            defaultHomeDir = Paths.get("/storage/emulated/0/")
        }
    }

    private class DerivedPasswordAuthenticator(
        private val validUsername: String,
        private val validPasswordHash: String
    ) : PasswordAuthenticator {
        override fun authenticate(
            username: String?,
            password: String?,
            session: org.apache.sshd.server.session.ServerSession?
        ): Boolean {
            if (username != validUsername) return false
            val inputHash = derivePasswordHash(password ?: "")
            return inputHash == validPasswordHash
        }
    }

    fun deriveCredentialsFromSharedSecret(sharedSecret: String): Pair<String, String> {
        val secretBytes = Base64.decode(sharedSecret, Base64.NO_WRAP)
        val sha256 = MessageDigest.getInstance("SHA-256")
        val derived = sha256.digest(secretBytes)

        val username = DERIVED_USERNAME_PREFIX + Base64.encodeToString(derived.copyOf(8), Base64.NO_WRAP or Base64.URL_SAFE)
            .replace("[^a-zA-Z0-9]".toRegex(), "")
            .take(16)
            .lowercase()

        val password = Base64.encodeToString(derived.copyOf(DERIVED_PASSWORD_LENGTH), Base64.NO_WRAP or Base64.URL_SAFE)
            .replace("[^a-zA-Z0-9]".toRegex(), "")

        return Pair(username, password)
    }

    fun derivePasswordHash(password: String): String {
        val md5 = MessageDigest.getInstance("MD5")
        val hash = md5.digest(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    fun initialize() {
        if (sshd != null) return
        val sshd = ServerBuilder.builder().apply {
            fileSystemFactory(NativeFileSystemFactory())
        }.build()
        this.sshd = sshd
    }

    fun start(sharedSecret: String, deviceName: String): SftpServerInfo? {
        Logger.i(TAG, "SFTP 服务器启动请求，设备名称: $deviceName")
        if (isRunning) {
            Logger.i(TAG, "SFTP 服务器已在运行，返回当前服务器信息")
            return serverInfo
        }

        Logger.d(TAG, "从共享密钥派生 SFTP 凭据")
        val (username, password) = deriveCredentialsFromSharedSecret(sharedSecret)
        val passwordHash = derivePasswordHash(password)
        Logger.d(TAG, "派生的用户名: $username")

        Logger.d(TAG, "开始在端口范围 $PORT_RANGE 中尝试启动 SFTP 服务器")
        PORT_RANGE.forEach { port ->
            try {
                Logger.d(TAG, "尝试在端口 $port 启动 SFTP 服务器")
                sshd = ServerBuilder.builder().apply {
                    fileSystemFactory(SimpleFileSystemFactory())
                }.build().apply {
                    this.port = port
                    keyPairProvider = PfxKeyPairProvider()
                    publickeyAuthenticator = PublickeyAuthenticator { _, _, _ -> true }
                    passwordAuthenticator = DerivedPasswordAuthenticator(username, passwordHash)
                    subsystemFactories = listOf(SftpSubsystemFactory())
                    start()
                }

                isRunning = true
                val ipAddress = getDeviceIpAddress()
                Logger.i(TAG, "SFTP 服务器在端口 $port 启动成功，IP 地址: $ipAddress")

                serverInfo = SftpServerInfo(
                    username = username,
                    password = password,
                    ipAddress = ipAddress ?: "127.0.0.1",
                    port = port
                )

                Logger.i(TAG, "SFTP server started: $ipAddress on port $port (derived from sharedSecret)")
                return serverInfo
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to start SFTP server on port $port", e)
            }
        }
        Logger.e(TAG, "所有端口尝试失败，无法启动 SFTP 服务器")
        return null
    }

    fun stop() {
        try {
            if (isRunning) {
                sshd?.stop(true)
                isRunning = false
                serverInfo = null
                com.xzyht.notifyrelay.common.core.util.Logger.i(TAG, "SFTP server stopped")
            }
        } catch (e: Exception) {
            com.xzyht.notifyrelay.common.core.util.Logger.e(TAG, "Failed to stop SFTP server", e)
        }
    }

    private fun getDeviceIpAddress(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            com.xzyht.notifyrelay.common.core.util.Logger.e(TAG, "Failed to get device IP address", e)
            null
        }
    }
}
