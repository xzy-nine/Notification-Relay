package com.xzyht.notifyrelay.core.util

import android.util.Base64
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
 * 整合AES和RSA两种现代加密算法的实现到一个文件中
 */
object EncryptionManager {

    // 加密类型枚举
    enum class EncryptionType {
        AES,    // AES对称加密（推荐）
        RSA     // RSA非对称加密（最高安全）
    }

    // 当前使用的加密类型（默认AES）
    private var currentEncryptionType: EncryptionType = EncryptionType.AES

    // =================== AES加密实现 ===================
    private object AESEncryption {
        private const val ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/ECB/PKCS5Padding"

        fun generateKey(): SecretKey {
            val keyGenerator = KeyGenerator.getInstance(ALGORITHM)
            keyGenerator.init(256)
            return keyGenerator.generateKey()
        }

        fun keyToString(key: SecretKey): String {
            return Base64.encodeToString(key.encoded, Base64.NO_WRAP)
        }

        fun stringToKey(keyString: String): SecretKey {
            val keyBytes = Base64.decode(keyString, Base64.NO_WRAP)
            return SecretKeySpec(keyBytes, ALGORITHM)
        }

        fun encrypt(data: String, key: String): String {
            val secretKey = stringToKey(key)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val encryptedBytes = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        }

        fun decrypt(encryptedData: String, key: String): String {
            val secretKey = stringToKey(key)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val encryptedBytes = Base64.decode(encryptedData, Base64.NO_WRAP)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            return String(decryptedBytes, Charsets.UTF_8)
        }

        fun generateSharedSecret(localKey: String, remoteKey: String): String {
            val combined = if (localKey < remoteKey) {
                (localKey + remoteKey).take(32)
            } else {
                (remoteKey + localKey).take(32)
            }
            val keyBytes = combined.toByteArray(Charsets.UTF_8)
            val paddedKey = ByteArray(32)
            for (i in paddedKey.indices) {
                paddedKey[i] = keyBytes[i % keyBytes.size]
            }
            return Base64.encodeToString(paddedKey, Base64.NO_WRAP)
        }
    }

    // =================== RSA加密实现 ===================
    private object RSAEncryption {
        private const val ALGORITHM = "RSA"
        private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"
        private const val KEY_SIZE = 2048

        fun generateKeyPair(): KeyPair {
            val keyPairGenerator = KeyPairGenerator.getInstance(ALGORITHM)
            keyPairGenerator.initialize(KEY_SIZE)
            return keyPairGenerator.generateKeyPair()
        }

        fun publicKeyToString(publicKey: PublicKey): String {
            return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
        }

        fun privateKeyToString(privateKey: PrivateKey): String {
            return Base64.encodeToString(privateKey.encoded, Base64.NO_WRAP)
        }

        fun stringToPublicKey(publicKeyString: String): PublicKey {
            val keyBytes = Base64.decode(publicKeyString, Base64.NO_WRAP)
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance(ALGORITHM)
            return keyFactory.generatePublic(keySpec)
        }

        fun stringToPrivateKey(privateKeyString: String): PrivateKey {
            val keyBytes = Base64.decode(privateKeyString, Base64.NO_WRAP)
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance(ALGORITHM)
            return keyFactory.generatePrivate(keySpec)
        }

        fun encryptWithPublicKey(data: String, publicKey: PublicKey): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val encryptedBytes = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        }

        fun decryptWithPrivateKey(encryptedData: String, privateKey: PrivateKey): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, privateKey)
            val encryptedBytes = Base64.decode(encryptedData, Base64.NO_WRAP)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            return String(decryptedBytes, Charsets.UTF_8)
        }

        fun generateSharedSecret(localKey: String, remoteKey: String): String {
            return (localKey + remoteKey).hashCode().toString()
        }
    }

    // =================== 公共接口 ===================

    /**
     * 设置加密类型
     */
    fun setEncryptionType(type: EncryptionType) {
        currentEncryptionType = type
    }

    /**
     * 获取当前加密类型
     */
    fun getCurrentEncryptionType(): EncryptionType {
        return currentEncryptionType
    }

    /**
     * 加密数据
     */
    fun encrypt(data: String, key: String): String {
        return when (currentEncryptionType) {
            EncryptionType.AES -> AESEncryption.encrypt(data, key)
            EncryptionType.RSA -> throw UnsupportedOperationException("RSA encryption requires PublicKey object")
        }
    }

    /**
     * 解密数据
     */
    fun decrypt(encryptedData: String, key: String): String {
        return when (currentEncryptionType) {
            EncryptionType.AES -> AESEncryption.decrypt(encryptedData, key)
            EncryptionType.RSA -> throw UnsupportedOperationException("RSA decryption requires PrivateKey object")
        }
    }

    /**
     * 生成共享密钥
     */
    fun generateSharedSecret(localKey: String, remoteKey: String): String {
        return when (currentEncryptionType) {
            EncryptionType.AES -> AESEncryption.generateSharedSecret(localKey, remoteKey)
            EncryptionType.RSA -> RSAEncryption.generateSharedSecret(localKey, remoteKey)
        }
    }

    // =================== RSA专用方法 ===================

    /**
     * 生成RSA密钥对
     */
    fun generateRSAKeyPair(): KeyPair {
        return RSAEncryption.generateKeyPair()
    }

    /**
     * RSA公钥加密
     */
    fun encryptWithRSAPublicKey(data: String, publicKey: PublicKey): String {
        return RSAEncryption.encryptWithPublicKey(data, publicKey)
    }

    /**
     * RSA私钥解密
     */
    fun decryptWithRSAPrivateKey(encryptedData: String, privateKey: PrivateKey): String {
        return RSAEncryption.decryptWithPrivateKey(encryptedData, privateKey)
    }

    /**
     * 公钥转字符串
     */
    fun rsaPublicKeyToString(publicKey: PublicKey): String {
        return RSAEncryption.publicKeyToString(publicKey)
    }

    /**
     * 私钥转字符串
     */
    fun rsaPrivateKeyToString(privateKey: PrivateKey): String {
        return RSAEncryption.privateKeyToString(privateKey)
    }

    /**
     * 字符串转公钥
     */
    fun stringToRSAPublicKey(publicKeyString: String): PublicKey {
        return RSAEncryption.stringToPublicKey(publicKeyString)
    }

    /**
     * 字符串转私钥
     */
    fun stringToRSAPrivateKey(privateKeyString: String): PrivateKey {
        return RSAEncryption.stringToPrivateKey(privateKeyString)
    }

    // =================== AES专用方法 ===================

    /**
     * 生成AES密钥
     */
    fun generateAESKey(): SecretKey {
        return AESEncryption.generateKey()
    }

    /**
     * AES密钥转字符串
     */
    fun aesKeyToString(key: SecretKey): String {
        return AESEncryption.keyToString(key)
    }

    /**
     * 字符串转AES密钥
     */
    fun stringToAESKey(keyString: String): SecretKey {
        return AESEncryption.stringToKey(keyString)
    }

    // =================== 配置和工具方法 ===================

    /**
     * 获取加密类型的显示名称
     */
    fun getEncryptionTypeDisplayName(type: EncryptionType): String {
        return when (type) {
            EncryptionType.AES -> "AES加密（推荐）"
            EncryptionType.RSA -> "RSA加密（最高安全）"
        }
    }

    /**
     * 获取加密类型的描述
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
     * 验证加密类型是否支持对称加密
     */
    fun supportsSymmetricEncryption(type: EncryptionType): Boolean {
        return type == EncryptionType.AES
    }

    /**
     * 验证加密类型是否支持非对称加密
     */
    fun supportsAsymmetricEncryption(type: EncryptionType): Boolean {
        return type == EncryptionType.RSA
    }
}
