package com.github.brokenhappy.kotlinconfchallengeplugin.shared.protocol

const val HEARTBEAT_BYTE: Byte = 0x04

private const val CHUNK_COMPLETE: Byte = 0x00
private const val CHUNK_START: Byte = 0x01
private const val CHUNK_CONTINUE: Byte = 0x02
private const val CHUNK_END: Byte = 0x03
private const val MAX_CHUNK_PAYLOAD = 180

fun encodeChunked(data: ByteArray): List<ByteArray> {
    if (data.size <= MAX_CHUNK_PAYLOAD) {
        return listOf(byteArrayOf(CHUNK_COMPLETE) + data)
    }
    val chunks = mutableListOf<ByteArray>()
    var offset = 0
    while (offset < data.size) {
        val end = minOf(offset + MAX_CHUNK_PAYLOAD, data.size)
        val chunkData = data.copyOfRange(offset, end)
        val flag = when {
            offset == 0 -> CHUNK_START
            end == data.size -> CHUNK_END
            else -> CHUNK_CONTINUE
        }
        chunks.add(byteArrayOf(flag) + chunkData)
        offset = end
    }
    return chunks
}

class MessageAssembler {
    private var buffer = ByteArray(0)

    fun processChunk(chunk: ByteArray): ByteArray? {
        if (chunk.isEmpty()) return null
        if (chunk[0] == HEARTBEAT_BYTE) return null
        val flag = chunk[0]
        val data = if (chunk.size > 1) chunk.copyOfRange(1, chunk.size) else ByteArray(0)
        return when (flag) {
            CHUNK_COMPLETE -> {
                buffer = ByteArray(0)
                data
            }
            CHUNK_START -> {
                buffer = data
                null
            }
            CHUNK_CONTINUE -> {
                buffer += data
                null
            }
            CHUNK_END -> {
                val assembled = buffer + data
                buffer = ByteArray(0)
                assembled
            }
            else -> {
                // Legacy: raw data without chunk header
                chunk
            }
        }
    }
}
