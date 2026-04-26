package com.nuvio.tv.core.player

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.TreeMap

/**
 * Disk store for seek-preview thumbnails, keyed by content hash
 * (see [SeekPreviewCacheKey]).
 *
 * Layout: one `<hash>.nvst` file per movie under [rootDir]. The binary
 * format keeps a fixed header, a sorted index of (timestamp, offset, length)
 * entries, and concatenated JPEG payloads. One file per movie keeps the
 * inode count low on older Android TV filesystems, and eviction is a
 * single file delete.
 *
 * File format (big-endian):
 *   offset 0  : magic        "NVST" (4 bytes)
 *   offset 4  : version      u8  = 1
 *   offset 5  : format       u8  = 1 (JPEG)
 *   offset 6  : width        u16
 *   offset 8  : height       u16
 *   offset 10 : intervalMs   u32
 *   offset 14 : count        u32
 *   offset 18 : durationMs   i64
 *   offset 26 : generatedThroughMs i64
 *   offset 34 : reserved     2 bytes (zero)
 *   offset 36 : index        count * 16 bytes (tsMs i64, offset u32, length u32)
 *   offset ...: JPEG payloads
 *
 * `generatedThroughMs` is the upper bound of the contiguous-from-zero range
 * the generator has reached. The generator reads it on open to know where
 * to resume, and advances it via [MovieEntry.commit]. Sparse coverage
 * beyond that mark (eg. opportunistic grabs) is still stored in the index.
 */
class SeekPreviewThumbnailStore(private val rootDir: File) {

    init {
        if (!rootDir.exists()) rootDir.mkdirs()
    }

    fun open(
        key: String,
        width: Int,
        height: Int,
        intervalMs: Int,
        durationMs: Long
    ): MovieEntry {
        val file = fileFor(key)
        val existing = if (file.isFile) runCatching { readFile(file) }.getOrNull() else null
        return if (existing != null &&
            existing.width == width &&
            existing.height == height &&
            existing.intervalMs == intervalMs &&
            existing.durationMs == durationMs
        ) {
            touch(file)
            MovieEntry(file, existing.width, existing.height, existing.intervalMs,
                existing.durationMs, existing.generatedThroughMs, existing.entries)
        } else {
            if (file.isFile) file.delete()
            MovieEntry(file, width, height, intervalMs, durationMs, 0L, TreeMap())
        }
    }

    /**
     * Returns an entry for read-only lookup if a cached file exists, or null otherwise.
     * Does not create a new file on miss.
     */
    fun peek(key: String): MovieEntry? {
        val file = fileFor(key)
        if (!file.isFile) return null
        val loaded = runCatching { readFile(file) }.getOrNull() ?: return null
        touch(file)
        return MovieEntry(file, loaded.width, loaded.height, loaded.intervalMs,
            loaded.durationMs, loaded.generatedThroughMs, loaded.entries)
    }

    fun delete(key: String): Boolean = fileFor(key).delete()

    fun clearAll() {
        rootDir.listFiles { _, name -> name.endsWith(EXT) }?.forEach { it.delete() }
    }

    fun totalBytes(): Long = rootDir.listFiles { _, name -> name.endsWith(EXT) }
        ?.sumOf { it.length() } ?: 0L

    /**
     * Enforces [maxBytes] across all cached movies, evicting whole files
     * ordered by lastModified (oldest-accessed first).
     */
    fun trimLru(maxBytes: Long) {
        val files = rootDir.listFiles { _, name -> name.endsWith(EXT) } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        val ordered = files.sortedBy { it.lastModified() }
        for (f in ordered) {
            if (total <= maxBytes) break
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    private fun fileFor(key: String): File = File(rootDir, "$key$EXT")

    private fun touch(file: File) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }

    class MovieEntry internal constructor(
        private val file: File,
        val width: Int,
        val height: Int,
        val intervalMs: Int,
        val durationMs: Long,
        generatedThroughMs: Long,
        entries: TreeMap<Long, ByteArray>
    ) {
        private val lock = Any()
        private val entries: TreeMap<Long, ByteArray> = entries
        // Disk is in sync with memory at construction time. `put` flips this
        // to true; `commit`/`close` writes and clears it.
        private var dirty: Boolean = false
        private var _generatedThroughMs: Long = generatedThroughMs

        val generatedThroughMs: Long get() = synchronized(lock) { _generatedThroughMs }

        fun size(): Int = synchronized(lock) { entries.size }

        fun hasTimestamp(tsMs: Long): Boolean = synchronized(lock) { entries.containsKey(tsMs) }

        /** Returns a sorted snapshot of all cached timestamp keys. Thread-safe. */
        fun cachedTimestampKeys(): List<Long> = synchronized(lock) { entries.keys.toList() }

        fun isCompleteThrough(rangeEndMs: Long): Boolean =
            synchronized(lock) { _generatedThroughMs >= rangeEndMs }

        fun put(tsMs: Long, jpeg: ByteArray) {
            synchronized(lock) {
                entries[tsMs] = jpeg
                dirty = true
            }
        }

        /**
         * Returns the JPEG bytes for the cached timestamp nearest [tsMs],
         * or null if none within [maxDeltaMs].
         */
        fun nearest(tsMs: Long, maxDeltaMs: Long = 30_000L): ByteArray? {
            synchronized(lock) {
                if (entries.isEmpty()) return null
                val floor = entries.floorEntry(tsMs)
                val ceil = entries.ceilingEntry(tsMs)
                val best = when {
                    floor == null -> ceil
                    ceil == null -> floor
                    kotlin.math.abs(tsMs - floor.key) <= kotlin.math.abs(ceil.key - tsMs) -> floor
                    else -> ceil
                } ?: return null
                return if (kotlin.math.abs(best.key - tsMs) <= maxDeltaMs) best.value else null
            }
        }

        /**
         * Flushes the current state to disk and advances the generator watermark.
         * Atomic: writes a .tmp file then renames over the canonical one.
         */
        @Throws(IOException::class)
        fun commit(generatedThroughMs: Long) {
            synchronized(lock) {
                val newMark = maxOf(_generatedThroughMs, generatedThroughMs)
                val markChanged = newMark != _generatedThroughMs
                _generatedThroughMs = newMark
                if (!dirty && !markChanged && file.isFile) return
                writeFile(file, width, height, intervalMs, durationMs, _generatedThroughMs, entries)
                dirty = false
            }
        }

        fun close() {
            synchronized(lock) {
                if (dirty) {
                    runCatching {
                        writeFile(file, width, height, intervalMs, durationMs,
                            _generatedThroughMs, entries)
                    }
                    dirty = false
                }
            }
        }
    }

    private data class Loaded(
        val width: Int,
        val height: Int,
        val intervalMs: Int,
        val durationMs: Long,
        val generatedThroughMs: Long,
        val entries: TreeMap<Long, ByteArray>
    )

    private fun readFile(file: File): Loaded {
        DataInputStream(FileInputStream(file).buffered()).use { input ->
            val magic = ByteArray(4).also { input.readFully(it) }
            require(magic.contentEquals(MAGIC)) { "bad magic" }
            val version = input.readUnsignedByte()
            require(version == VERSION) { "unsupported version $version" }
            val format = input.readUnsignedByte()
            require(format == FORMAT_JPEG) { "unsupported format $format" }
            val width = input.readUnsignedShort()
            val height = input.readUnsignedShort()
            val intervalMs = input.readInt()
            val count = input.readInt()
            val durationMs = input.readLong()
            val generatedThroughMs = input.readLong()
            input.skipBytes(2) // reserved
            data class Idx(val ts: Long, val offset: Int, val length: Int)
            val index = ArrayList<Idx>(count)
            repeat(count) {
                val ts = input.readLong()
                val offset = input.readInt()
                val length = input.readInt()
                index.add(Idx(ts, offset, length))
            }
            val headerEnd = HEADER_SIZE + count * INDEX_ENTRY_SIZE
            val entries = TreeMap<Long, ByteArray>()
            // The payload follows after index. We've read the whole index via
            // the buffered stream; switch to RandomAccessFile for payload reads
            // to keep behaviour simple and predictable across versions.
            java.io.RandomAccessFile(file, "r").use { raf ->
                for (e in index) {
                    require(e.offset >= headerEnd) { "offset before body" }
                    require(e.length >= 0) { "negative length" }
                    raf.seek(e.offset.toLong())
                    val bytes = ByteArray(e.length)
                    raf.readFully(bytes)
                    entries[e.ts] = bytes
                }
            }
            return Loaded(width, height, intervalMs, durationMs, generatedThroughMs, entries)
        }
    }

    companion object {
        const val EXT = ".nvst"
        internal val MAGIC = byteArrayOf('N'.code.toByte(), 'V'.code.toByte(),
            'S'.code.toByte(), 'T'.code.toByte())
        internal const val VERSION = 1
        internal const val FORMAT_JPEG = 1
        internal const val HEADER_SIZE = 36
        internal const val INDEX_ENTRY_SIZE = 16

        internal fun writeFile(
            file: File,
            width: Int,
            height: Int,
            intervalMs: Int,
            durationMs: Long,
            generatedThroughMs: Long,
            entries: TreeMap<Long, ByteArray>
        ) {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            val count = entries.size
            val headerEnd = HEADER_SIZE + count * INDEX_ENTRY_SIZE
            DataOutputStream(FileOutputStream(tmp).buffered()).use { out ->
                out.write(MAGIC)
                out.writeByte(VERSION)
                out.writeByte(FORMAT_JPEG)
                out.writeShort(width)
                out.writeShort(height)
                out.writeInt(intervalMs)
                out.writeInt(count)
                out.writeLong(durationMs)
                out.writeLong(generatedThroughMs)
                out.writeShort(0) // reserved
                var cursor = headerEnd
                for ((ts, bytes) in entries) {
                    out.writeLong(ts)
                    out.writeInt(cursor)
                    out.writeInt(bytes.size)
                    cursor += bytes.size
                }
                for ((_, bytes) in entries) {
                    out.write(bytes)
                }
                out.flush()
            }
            if (!tmp.renameTo(file)) {
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    throw IOException("could not publish ${file.name}")
                }
            }
        }
    }
}
