package com.sheen.adb.core.internal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.flyfishxu.kadb.cert.KadbPrivateKeyStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidKeystorePrivateKeyStore(context: Context) : KadbPrivateKeyStore {
    private val directory = File(context.noBackupFilesDir, "adb_identity")
    private val identityFile = File(directory, "host-key.enc")
    private val temporaryFile = File(directory, "host-key.tmp")

    override fun readPrivateKeyPem(): ByteArray? {
        if (!identityFile.exists()) return null
        val (iv, ciphertext) = DataInputStream(FileInputStream(identityFile)).use { input ->
            check(input.readInt() == FORMAT_VERSION) { "Unsupported identity format" }
            val ivLength = input.readUnsignedByte()
            check(ivLength in 12..16) { "Invalid identity IV" }
            val iv = ByteArray(ivLength).also(input::readFully)
            val cipherLength = input.readInt()
            check(cipherLength in 1..MAX_CIPHERTEXT_BYTES) { "Invalid identity payload" }
            iv to ByteArray(cipherLength).also(input::readFully)
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } finally {
            ciphertext.fill(0)
            iv.fill(0)
        }
    }

    override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) {
        directory.mkdirs()
        check(directory.isDirectory) { "Identity directory is unavailable" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(privateKeyPem)
        val iv = cipher.iv
        try {
            FileOutputStream(temporaryFile).use { stream ->
                DataOutputStream(stream).use { output ->
                    output.writeInt(FORMAT_VERSION)
                    output.writeByte(iv.size)
                    output.write(iv)
                    output.writeInt(ciphertext.size)
                    output.write(ciphertext)
                    output.flush()
                    stream.fd.sync()
                }
            }
            if (!temporaryFile.renameTo(identityFile)) {
                FileInputStream(temporaryFile).use { input ->
                    FileOutputStream(identityFile).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                check(temporaryFile.delete()) { "Unable to remove temporary identity" }
            }
        } finally {
            ciphertext.fill(0)
            iv.fill(0)
            if (temporaryFile.exists()) temporaryFile.delete()
        }
    }

    override fun clear() {
        if (identityFile.exists()) check(identityFile.delete()) { "Unable to delete identity" }
        if (temporaryFile.exists()) temporaryFile.delete()
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = loadKeyStore()
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "sheen.adb.host-identity.wrap.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val FORMAT_VERSION = 1
        const val MAX_CIPHERTEXT_BYTES = 32 * 1024
    }
}
