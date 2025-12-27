package com.xzyht.notifyrelay.common.core.util

import android.util.Base64
import com.xzyht.notifyrelay.common.core.util.EncryptionManager.AESEncryption.generateSharedSecret
import com.xzyht.notifyrelay.common.core.util.EncryptionManager.AESEncryption.keyToString
import com.xzyht.notifyrelay.common.core.util.EncryptionManager.setEncryptionType
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * 统一加密管理器
 *
 * 整合 AES 与 RSA 两种加密算法的工具类。提供密钥生成、加解密、密钥与字符串互转以及
 * 生成共享密钥等常用方法。
 *
 * 注意：
 * - AES 方法使用对称加密，适用于大数据量和性能敏感场景；RSA 为非对称加密，适用于密钥交换或
 *   对安全性要求极高的场景。
 * - 本类将 AES 与 RSA 的实现封装在私有对象中，公共方法根据当前的加密类型（可通过
 *   [setEncryptionType] 修改）来选择行为；也提供 RSA/AES 的专用方法供需要时直接调用。
 */
object EncryptionManager {

    /**
     * 支持的加密类型枚举
     *
     * AES - 对称加密（推荐）
     * RSA - 非对称加密（最高安全）
     */
    enum class EncryptionType {
        AES,    // AES对称加密（推荐）
        RSA     // RSA非对称加密（最高安全）
    }

    // 当前使用的加密类型（默认AES）
    private var currentEncryptionType: EncryptionType = EncryptionType.AES

    // =================== AES加密实现 ===================
    private object AESEncryption {
        private const val ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128 // bits
        private const val GCM_IV_LENGTH = 12 // bytes

        /**
         * 生成一个新的 AES 对称密钥
         *
         * @return 生成的 [SecretKey] 对象，密钥长度为 256 位（若平台不支持 256 则可能抛出异常）
         */
        fun generateKey(): SecretKey {
            val keyGenerator = KeyGenerator.getInstance(ALGORITHM)
            keyGenerator.init(256)
            return keyGenerator.generateKey()
        }

        /**
         * 将 AES 密钥转换为 Base64 编码的字符串
         *
         * @param key 要编码的 [SecretKey]
         * @return Base64 编码的密钥字符串（无换行）
         */
        fun keyToString(key: SecretKey): String {
            return Base64.encodeToString(key.encoded, Base64.NO_WRAP)
        }

        /**
         * 将 Base64 编码的密钥字符串还原为 [SecretKey]
         *
         * @param keyString Base64 编码的密钥字符串（无换行）
         * @return 还原后的 [SecretKey]
         */
        fun stringToKey(keyString: String): SecretKey {
            val keyBytes = Base64.decode(keyString, Base64.NO_WRAP)
            return SecretKeySpec(keyBytes, ALGORITHM)
        }

        /**
         * 使用 AES 加密数据
         *
         * @param data 要加密的明文字符串，使用 UTF-8 编码
         * @param key Base64 编码的 AES 密钥字符串（通过 [keyToString] 或 [generateSharedSecret] 获取）
         * @return Base64 编码的密文字符串（无换行）
         */
        fun encrypt(data: String, key: String): String {
            val secretKey = stringToKey(key)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = ByteArray(GCM_IV_LENGTH)
            java.security.SecureRandom().nextBytes(iv)
            val spec = javax.crypto.spec.GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
            val encryptedBytes = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            // 输出格式：IV || ciphertext (ciphertext 包含 tag)
            val out = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, out, iv.size, encryptedBytes.size)
            return Base64.encodeToString(out, Base64.NO_WRAP)
        }

        /**
         * 使用 AES 解密数据
         *
         * @param encryptedData Base64 编码的密文字符串（无换行）
         * @param key Base64 编码的 AES 密钥字符串
         * @return 解密后的明文字符串（UTF-8）
         */
        fun decrypt(encryptedData: String, key: String): String {
            val secretKey = stringToKey(key)
            val data = Base64.decode(encryptedData, Base64.NO_WRAP)
            if (data.size < GCM_IV_LENGTH) throw IllegalArgumentException("Invalid encrypted data")
            val iv = data.copyOfRange(0, GCM_IV_LENGTH)
            val cipherBytes = data.copyOfRange(GCM_IV_LENGTH, data.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = javax.crypto.spec.GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val decryptedBytes = cipher.doFinal(cipherBytes)
            return String(decryptedBytes, Charsets.UTF_8)
        }

        /**
         * 根据本地与远端的密钥字符串生成一个共享的 AES 密钥（Base64 编码）
         *
         * 此方法通过拼接并截取/填充得到固定长度（32 字节）的字节序列，然后返回其 Base64 编码。
         * 注意：该方法不是标准的密钥协商算法，仅用于简单场景的共享密钥生成；如需更高安全性，
         * 请使用基于 Diffie-Hellman 或密钥交换协议的实现。
         *
         * @param localKey 本地标识或密钥字符串（非 Base64 必需）
         * @param remoteKey 远端标识或密钥字符串（非 Base64 必需）
         * @return Base64 编码的 32 字节共享密钥字符串（无换行）
         */
        fun generateSharedSecret(localKey: String, remoteKey: String): String {
            // 使用 HKDF-SHA256 从 localKey||remoteKey 派生 32 字节密钥，确保双方一致
            val a = localKey
            val b = remoteKey
            val combined = if (a < b) a + b else b + a
            val ikm = combined.toByteArray(Charsets.UTF_8)
            val prk = hkdfExtract(null, ikm)
            val okm = hkdfExpand(prk, "shared-secret".toByteArray(Charsets.UTF_8), 32)
            return Base64.encodeToString(okm, Base64.NO_WRAP)
        }

        private fun hkdfExtract(salt: ByteArray?, ikm: ByteArray): ByteArray {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            val realSalt = salt ?: ByteArray(32) { 0.toByte() }
            val keySpec = SecretKeySpec(realSalt, "HmacSHA256")
            mac.init(keySpec)
            return mac.doFinal(ikm)
        }

        private fun hkdfExpand(prk: ByteArray, info: ByteArray, len: Int): ByteArray {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            val keySpec = SecretKeySpec(prk, "HmacSHA256")
            mac.init(keySpec)
            val hashLen = 32
            val n = (len + hashLen - 1) / hashLen
            var t = ByteArray(0)
            val okm = ByteArray(len)
            var copied = 0
            for (i in 1..n) {
                mac.reset()
                mac.update(t)
                mac.update(info)
                mac.update(i.toByte())
                t = mac.doFinal()
                val toCopy = Math.min(hashLen, len - copied)
                System.arraycopy(t, 0, okm, copied, toCopy)
                copied += toCopy
            }
            return okm
        }
    }

    // =================== RSA加密实现 ===================
    private object RSAEncryption {
        private const val ALGORITHM = "RSA"
        private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"
        private const val KEY_SIZE = 2048

        /**
         * 生成 RSA 密钥对
         *
         * @return 生成的 [KeyPair]，包含公钥与私钥（2048 位）
         */
        fun generateKeyPair(): KeyPair {
            val keyPairGenerator = KeyPairGenerator.getInstance(ALGORITHM)
            keyPairGenerator.initialize(KEY_SIZE)
            return keyPairGenerator.generateKeyPair()
        }

        /**
         * 将 RSA 公钥编码为 Base64 字符串
         *
         * @param publicKey 要编码的 [PublicKey]
         * @return Base64 编码的公钥字符串（无换行）
         */
        fun publicKeyToString(publicKey: PublicKey): String {
            return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
        }

        /**
         * 将 RSA 私钥编码为 Base64 字符串
         *
         * @param privateKey 要编码的 [PrivateKey]
         * @return Base64 编码的私钥字符串（无换行）
         */
        fun privateKeyToString(privateKey: PrivateKey): String {
            return Base64.encodeToString(privateKey.encoded, Base64.NO_WRAP)
        }

        /**
         * 将 Base64 编码的公钥字符串还原为 [PublicKey]
         *
         * @param publicKeyString Base64 编码的公钥字符串（无换行）
         * @return 还原后的 [PublicKey]
         */
        fun stringToPublicKey(publicKeyString: String): PublicKey {
            val keyBytes = Base64.decode(publicKeyString, Base64.NO_WRAP)
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance(ALGORITHM)
            return keyFactory.generatePublic(keySpec)
        }

        /**
         * 将 Base64 编码的私钥字符串还原为 [PrivateKey]
         *
         * @param privateKeyString Base64 编码的私钥字符串（无换行）
         * @return 还原后的 [PrivateKey]
         */
        fun stringToPrivateKey(privateKeyString: String): PrivateKey {
            val keyBytes = Base64.decode(privateKeyString, Base64.NO_WRAP)
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance(ALGORITHM)
            return keyFactory.generatePrivate(keySpec)
        }

        /**
         * 使用 RSA 公钥对数据加密
         *
         * @param data 要加密的明文字符串（UTF-8）
         * @param publicKey 用于加密的 [PublicKey]
         * @return Base64 编码的密文字符串（无换行）
         */
        fun encryptWithPublicKey(data: String, publicKey: PublicKey): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val encryptedBytes = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        }

        /**
         * 使用 RSA 私钥对密文解密
         *
         * @param encryptedData Base64 编码的密文字符串（无换行）
         * @param privateKey 用于解密的 [PrivateKey]
         * @return 解密后的明文字符串（UTF-8）
         */
        fun decryptWithPrivateKey(encryptedData: String, privateKey: PrivateKey): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, privateKey)
            val encryptedBytes = Base64.decode(encryptedData, Base64.NO_WRAP)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            return String(decryptedBytes, Charsets.UTF_8)
        }

        /**
         * 基于字符串生成一个简易的共享密钥表示（仅用于低安全性场景）
         *
         * @param localKey 本地标识或密钥字符串
         * @param remoteKey 远端标识或密钥字符串
         * @return 基于字符串计算的共享密钥（通过 hashCode 转为字符串）
         */
        fun generateSharedSecret(localKey: String, remoteKey: String): String {
            return (localKey + remoteKey).hashCode().toString()
        }
    }

    // =================== 公共接口 ===================

    /**
     * 设置当前使用的加密类型
     *
     * @param type 要设置的 [EncryptionType]
     */
    fun setEncryptionType(type: EncryptionType) {
        currentEncryptionType = type
    }

    /**
     * 获取当前使用的加密类型
     *
     * @return 当前的 [EncryptionType]
     */
    fun getCurrentEncryptionType(): EncryptionType {
        return currentEncryptionType
    }


    /**
     * 对数据进行加密
     *
     * 注意：当当前加密类型为 AES 时，使用 Base64 编码的 AES 密钥字符串进行加解密；
     * 当当前加密类型为 RSA 时，此通用方法不支持直接传入字符串形式的密钥（RSA 需要 [PublicKey]/[PrivateKey] 对象），
     * 会抛出 [UnsupportedOperationException]。
     *
     * @param data 要加密的明文字符串（UTF-8）
     * @param key AES 模式下为 Base64 编码的密钥字符串；RSA 模式下不适用（会抛出异常）
     * @return 加密后的 Base64 编码密文字符串
     * @throws UnsupportedOperationException 当当前类型为 RSA 时抛出
     */
    fun encrypt(data: String, key: String): String {
        return when (currentEncryptionType) {
            EncryptionType.AES -> AESEncryption.encrypt(data, key)
            EncryptionType.RSA -> throw UnsupportedOperationException("RSA encryption requires PublicKey object")
        }
    }


    /**
     * 对密文进行解密
     *
     * @param encryptedData Base64 编码的密文字符串（无换行）
     * @param key AES 模式下为 Base64 编码的密钥字符串；RSA 模式下不适用（会抛出异常）
     * @return 解密后的明文字符串（UTF-8）
     * @throws UnsupportedOperationException 当当前类型为 RSA 时抛出
     */
    fun decrypt(encryptedData: String, key: String): String {
        return when (currentEncryptionType) {
            EncryptionType.AES -> AESEncryption.decrypt(encryptedData, key)
            EncryptionType.RSA -> throw UnsupportedOperationException("RSA decryption requires PrivateKey object")
        }
    }


    /**
     * 根据当前加密类型生成共享密钥字符串
     *
     * @param localKey 本地标识或密钥字符串
     * @param remoteKey 远端标识或密钥字符串
     * @return 生成的共享密钥字符串（AES 返回 Base64 编码的 32 字节密钥，RSA 返回基于 hashCode 的字符串）
     */
    fun generateSharedSecret(localKey: String, remoteKey: String): String {
        return when (currentEncryptionType) {
            EncryptionType.AES -> AESEncryption.generateSharedSecret(localKey, remoteKey)
            EncryptionType.RSA -> RSAEncryption.generateSharedSecret(localKey, remoteKey)
        }
    }

    // =================== RSA专用方法 ===================


    /**
     * 生成 RSA 密钥对（公私钥）
     *
     * @return 生成的 [KeyPair]
     */
    fun generateRSAKeyPair(): KeyPair {
        return RSAEncryption.generateKeyPair()
    }


    /**
     * 使用 RSA 公钥加密数据（专用方法）
     *
     * @param data 要加密的明文字符串（UTF-8）
     * @param publicKey 用于加密的 [PublicKey]
     * @return Base64 编码的密文字符串（无换行）
     */
    fun encryptWithRSAPublicKey(data: String, publicKey: PublicKey): String {
        return RSAEncryption.encryptWithPublicKey(data, publicKey)
    }


    /**
     * 使用 RSA 私钥解密数据（专用方法）
     *
     * @param encryptedData Base64 编码的密文字符串（无换行）
     * @param privateKey 用于解密的 [PrivateKey]
     * @return 解密后的明文字符串（UTF-8）
     */
    fun decryptWithRSAPrivateKey(encryptedData: String, privateKey: PrivateKey): String {
        return RSAEncryption.decryptWithPrivateKey(encryptedData, privateKey)
    }


    /**
     * 将 RSA 公钥编码为字符串（Base64）
     *
     * @param publicKey 要编码的 [PublicKey]
     * @return Base64 编码的公钥字符串（无换行）
     */
    fun rsaPublicKeyToString(publicKey: PublicKey): String {
        return RSAEncryption.publicKeyToString(publicKey)
    }


    /**
     * 将 RSA 私钥编码为字符串（Base64）
     *
     * @param privateKey 要编码的 [PrivateKey]
     * @return Base64 编码的私钥字符串（无换行）
     */
    fun rsaPrivateKeyToString(privateKey: PrivateKey): String {
        return RSAEncryption.privateKeyToString(privateKey)
    }


    /**
     * 将 Base64 编码的公钥字符串还原为 [PublicKey]
     *
     * @param publicKeyString Base64 编码的公钥字符串（无换行）
     * @return 还原后的 [PublicKey]
     */
    fun stringToRSAPublicKey(publicKeyString: String): PublicKey {
        return RSAEncryption.stringToPublicKey(publicKeyString)
    }


    /**
     * 将 Base64 编码的私钥字符串还原为 [PrivateKey]
     *
     * @param privateKeyString Base64 编码的私钥字符串（无换行）
     * @return 还原后的 [PrivateKey]
     */
    fun stringToRSAPrivateKey(privateKeyString: String): PrivateKey {
        return RSAEncryption.stringToPrivateKey(privateKeyString)
    }

    // =================== AES专用方法 ===================


    /**
     * 生成 AES 对称密钥（快捷方法）
     *
     * @return 新生成的 [SecretKey]
     */
    fun generateAESKey(): SecretKey {
        return AESEncryption.generateKey()
    }


    /**
     * 将 AES 密钥转为 Base64 字符串（快捷方法）
     *
     * @param key 要编码的 [SecretKey]
     * @return Base64 编码的密钥字符串（无换行）
     */
    fun aesKeyToString(key: SecretKey): String {
        return keyToString(key)
    }


    /**
     * 将 Base64 编码的 AES 密钥字符串还原为 [SecretKey]（快捷方法）
     *
     * @param keyString Base64 编码的密钥字符串（无换行）
     * @return 还原后的 [SecretKey]
     */
    fun stringToAESKey(keyString: String): SecretKey {
        return AESEncryption.stringToKey(keyString)
    }

    // =================== 配置和工具方法 ===================


    /**
     * 获取加密类型的显示名称（中文）
     *
     * @param type 要获取名称的 [EncryptionType]
     * @return 中文显示名称
     */
    fun getEncryptionTypeDisplayName(type: EncryptionType): String {
        return when (type) {
            EncryptionType.AES -> "AES加密（推荐）"
            EncryptionType.RSA -> "RSA加密（最高安全）"
        }
    }


    /**
     * 获取加密类型的详细描述（中文）
     *
     * @param type 要获取描述的 [EncryptionType]
     * @return 对该类型的中文描述
     */
    fun getEncryptionTypeDescription(type: EncryptionType): String {
        return when (type) {
            EncryptionType.AES ->
                "AES对称加密，安全性高，性能良好。推荐用于大多数场景。"
            EncryptionType.RSA ->
                "RSA非对称加密，最安全的加密方式，但性能相对较低。适用于对安全性要求极高的场景。"
        }
    }


    /**
     * 判断指定的加密类型是否支持对称加密
     *
     * @param type 要判断的 [EncryptionType]
     * @return 如果为 AES 则返回 true，否则返回 false
     */
    fun supportsSymmetricEncryption(type: EncryptionType): Boolean {
        return type == EncryptionType.AES
    }

    /**
     * 判断指定的加密类型是否支持非对称加密
     *
     * @param type 要判断的 [EncryptionType]
     * @return 如果为 RSA 则返回 true，否则返回 false
     */
    fun supportsAsymmetricEncryption(type: EncryptionType): Boolean {
        return type == EncryptionType.RSA
    }
}
