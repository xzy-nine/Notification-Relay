package com.xzyht.notifyrelay.core.util

/**
 * 加密管理器使用示例
 * 展示如何使用EncryptionManager进行数据加密和解密
 */
object EncryptionExample {

    /**
     * 基础使用示例
     */
    fun basicUsageExample() {
        // 设置加密类型
        EncryptionManager.setEncryptionType(EncryptionManager.EncryptionType.AES)

        // 原始数据
        val originalData = "这是要加密的通知数据"
        val key = "mySecretKey123"

        // 加密
        val encryptedData = EncryptionManager.encrypt(originalData, key)
        println("加密后的数据: $encryptedData")

        // 解密
        val decryptedData = EncryptionManager.decrypt(encryptedData, key)
        println("解密后的数据: $decryptedData")

        // 验证结果
        assert(originalData == decryptedData) { "加密解密失败！" }
    }

    /**
     * 切换加密类型的示例
     */
    fun switchEncryptionTypeExample() {
        val data = "测试数据"
        val key = "testKey"

        // 使用AES加密
        EncryptionManager.setEncryptionType(EncryptionManager.EncryptionType.AES)
        val aesEncrypted = EncryptionManager.encrypt(data, key)
        val aesDecrypted = EncryptionManager.decrypt(aesEncrypted, key)
        println("AES加密结果: $aesDecrypted")

        // 验证结果
        assert(data == aesDecrypted) { "AES加密解密失败！" }
    }

    /**
     * 生成共享密钥的示例
     */
    fun sharedSecretExample() {
        val localKey = "deviceA_public_key_12345"
        val remoteKey = "deviceB_public_key_67890"

        // 使用不同加密类型生成共享密钥
        EncryptionManager.setEncryptionType(EncryptionManager.EncryptionType.AES)
        val aesSecret = EncryptionManager.generateSharedSecret(localKey, remoteKey)

        EncryptionManager.setEncryptionType(EncryptionManager.EncryptionType.RSA)
        val rsaSecret = EncryptionManager.generateSharedSecret(localKey, remoteKey)

        println("AES共享密钥: $aesSecret")
        println("RSA共享密钥: $rsaSecret")
    }

    /**
     * RSA加密示例（需要密钥对）
     */
    fun rsaExample() {
        // 生成RSA密钥对
        val keyPair = EncryptionManager.generateRSAKeyPair()
        val publicKey = keyPair.public
        val privateKey = keyPair.private

        val data = "RSA加密测试数据"

        // 使用公钥加密
        val encryptedData = EncryptionManager.encryptWithRSAPublicKey(data, publicKey)
        println("RSA加密后的数据: $encryptedData")

        // 使用私钥解密
        val decryptedData = EncryptionManager.decryptWithRSAPrivateKey(encryptedData, privateKey)
        println("RSA解密后的数据: $decryptedData")

        // 验证结果
        assert(data == decryptedData) { "RSA加密解密失败！" }
    }

    /**
     * 密钥序列化示例
     */
    fun keySerializationExample() {
        // AES密钥序列化
        val aesKey = EncryptionManager.generateAESKey()
        val keyString = EncryptionManager.aesKeyToString(aesKey)
        val restoredKey = EncryptionManager.stringToAESKey(keyString)

        println("AES密钥字符串: $keyString")
        println("密钥恢复成功: ${aesKey == restoredKey}")

        // RSA密钥序列化
        val rsaKeyPair = EncryptionManager.generateRSAKeyPair()
        val publicKeyString = EncryptionManager.rsaPublicKeyToString(rsaKeyPair.public)
        val privateKeyString = EncryptionManager.rsaPrivateKeyToString(rsaKeyPair.private)

        val restoredPublicKey = EncryptionManager.stringToRSAPublicKey(publicKeyString)
        val restoredPrivateKey = EncryptionManager.stringToRSAPrivateKey(privateKeyString)

        println("RSA公钥字符串长度: ${publicKeyString.length}")
        println("RSA私钥字符串长度: ${privateKeyString.length}")
        println("RSA密钥恢复成功: ${rsaKeyPair.public == restoredPublicKey && rsaKeyPair.private == restoredPrivateKey}")
    }
}
