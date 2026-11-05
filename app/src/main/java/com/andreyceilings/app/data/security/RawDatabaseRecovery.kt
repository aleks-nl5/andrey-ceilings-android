package com.andreyceilings.app.data.security

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** Closed, unopenable encrypted files. Never decrypts, checkpoints, upgrades or discards the source. */
internal object RawDatabaseRecovery {
    private val suffixes = linkedMapOf("database" to "", "wal" to "-wal", "shm" to "-shm", "journal" to "-journal")
    private val bundlePattern = Regex("unavailable-[0-9a-f-]{36}")
    fun root(context: android.content.Context) = File(context.noBackupFilesDir,"database_recovery")

    fun capture(root: File, live: File, wrappedKey: String, afterChunk: () -> Unit = {}): File {
        check((root.isDirectory || root.mkdirs()) && !java.nio.file.Files.isSymbolicLink(root.toPath())) { "Не вдалося створити сховище відкату." }
        syncDirectory(checkNotNull(root.parentFile))
        val id=UUID.randomUUID().toString()
        val draft=File(root,"prepared-$id");val bundle=File(root,"unavailable-$id")
        check(draft.mkdir())
        try {
            val manifest=Properties().apply { setProperty("format","1") }
            for((name,suffix) in suffixes) {
                val source=File(live.path+suffix)
                manifest.setProperty("$name.present",source.exists().toString())
                if(source.exists()) {
                    check(source.isFile && !java.nio.file.Files.isSymbolicLink(source.toPath())) { "Файли початкової БД недоступні для безпечного збереження." }
                    val target=File(draft,name);copy(source,target,afterChunk)
                    manifest.setProperty("$name.sha256",digest(target))
                    check(digest(source)==digest(target)) { "Початкові файли змінилися. Заміни не виконано." }
                }
            }
            write(File(draft,"wrapped.key"),wrappedKey.toByteArray(Charsets.UTF_8))
            manifest.setProperty("key.sha256",digest(File(draft,"wrapped.key")))
            FileOutputStream(File(draft,"manifest")).use { manifest.store(it,null);it.fd.sync() }
            syncDirectory(draft)
            check(draft.renameTo(bundle)) { "Не вдалося опублікувати резерв для відкату." }
            syncDirectory(root)
            validate(root,bundle.name,wrappedKey)
            return bundle
        } catch(failure:Throwable) { draft.deleteRecursively();throw failure }
    }

    fun manifestHash(bundle:File):String = digest(File(bundle,"manifest"))

    private fun validate(root:File,name:String,wrappedKey:String,expectedManifestHash:String?=null): Pair<File,Properties> {
        check(bundlePattern.matches(name)) { "Неприпустимий ідентифікатор відкату." }
        val bundle=File(root,name)
        check(bundle.isDirectory && !java.nio.file.Files.isSymbolicLink(bundle.toPath())) { "Збережений початковий стан недоступний." }
        val metadata=File(bundle,"manifest")
        check(metadata.isFile && !java.nio.file.Files.isSymbolicLink(metadata.toPath()) && metadata.length() in 1..16_384 &&
            (expectedManifestHash==null || digest(metadata)==expectedManifestHash)) { "Маркер початкового стану пошкоджений." }
        val manifest=Properties().apply { metadata.inputStream().use(::load) }
        check(manifest.getProperty("format")=="1")
        val key=File(bundle,"wrapped.key")
        check(key.isFile && !java.nio.file.Files.isSymbolicLink(key.toPath()) && key.length() in 1..8192 && digest(key)==manifest.getProperty("key.sha256") && key.readText()==wrappedKey) {
            "Захищений ключ початкового стану не підтверджено."
        }
        suffixes.keys.forEach { tag ->
            val present=manifest.getProperty("$tag.present")
            check(present=="true" || present=="false")
            val file=File(bundle,tag)
            check(if(present=="true") file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) && digest(file)==manifest.getProperty("$tag.sha256") else !file.exists()) {
                "Цілісність початкових зашифрованих файлів не підтверджено."
            }
        }
        return bundle to manifest
    }

    /** Marker and source bundle remain durable until every original file AND key is restored. */
    fun restore(root:File,name:String,live:File,wrappedKey:String,expectedManifestHash:String) {
        val(bundle,manifest)=validate(root,name,wrappedKey,expectedManifestHash)
        val staged=File(root,"recovery-${UUID.randomUUID()}");check(staged.mkdir())
        try {
            for(tag in suffixes.keys) if(manifest.getProperty("$tag.present")=="true") {
                copy(File(bundle,tag),File(staged,tag))
                check(digest(File(staged,tag))==manifest.getProperty("$tag.sha256"))
            }
            syncDirectory(staged)
            // All originals are verified/staged before deleting any replacement file.
            suffixes.values.forEach { suffix -> File(live.path+suffix).let { check(!it.exists() || it.delete()) } }
            for((tag,suffix) in suffixes) if(manifest.getProperty("$tag.present")=="true") {
                copy(File(staged,tag),File(live.path+suffix))
                check(digest(File(live.path+suffix))==manifest.getProperty("$tag.sha256"))
            }
            syncDirectory(checkNotNull(live.parentFile))
        } finally { staged.deleteRecursively() }
    }

    private fun copy(source:File,target:File,afterChunk:()->Unit={}) {
        source.inputStream().use { input -> FileOutputStream(target).use { output ->
            val chunk=ByteArray(64*1024);var first=true
            while(true) { val n=input.read(chunk);if(n<0)break;output.write(chunk,0,n);if(first){first=false;afterChunk()} }
            output.fd.sync()
        } }
    }
    private fun write(target:File,bytes:ByteArray) { FileOutputStream(target).use { it.write(bytes);it.fd.sync() } }
    private fun digest(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes=ByteArray(64*1024);while(true){val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n)} }
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    private fun syncDirectory(directory:File) {
        val fd=Os.open(directory.path,OsConstants.O_RDONLY,0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
}
