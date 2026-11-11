package com.amrstream

/** Decodes Movy's enc=2 source payloads using the seed returned for the TMDB media ID. */
class MovySourceDecoder : SourceDecoder {
    override fun decode(payload: String, seed: String, mediaId: Int): String {
        val encoded = payload.trim().removeSurrounding("\"").trim()
        val data = decodeBase64Url(encoded)
        require(data.size >= MAGIC.size) { "Movy payload is too short" }

        val state = initState(seed, mediaId)
        var counter = 0
        var offset = 0
        while (offset < data.size) {
            val key = step(state, counter++)
            repeat(4) { byteIndex ->
                if (offset < data.size) {
                    data[offset] = (data[offset].toInt() xor (key ushr (byteIndex * 8) and 0xff)).toByte()
                    offset++
                }
            }
        }

        require(MAGIC.indices.all { data[it] == MAGIC[it] }) { "Movy payload could not be decoded (seed mismatch)" }
        return String(data, MAGIC.size, data.size - MAGIC.size, Charsets.UTF_8)
    }

    private fun decodeBase64Url(value: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val normalized = value.replace('-', '+').replace('_', '/')
        val output = ArrayList<Byte>(normalized.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (char in normalized) {
            if (char == '=') break
            val digit = alphabet.indexOf(char)
            if (digit < 0) continue
            buffer = (buffer shl 6) or digit
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output.add(((buffer ushr bits) and 0xff).toByte())
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return output.toByteArray()
    }

    private data class State(val words: IntArray, val used: BooleanArray, var accumulator: Int)

    private fun initState(seed: String, mediaId: Int): State {
        var fnv = 0x811c9dc5.toInt()
        for (char in seed) fnv = (fnv xor char.code) * 0x01000193

        var n = mix(mix(fnv) xor mix(mediaId xor 0x9e3779b9.toInt()))
        val words = IntArray(61)
        val used = BooleanArray(61)
        repeat(8) { index ->
            val slot = Integer.remainderUnsigned(n, 61)
            n = Integer.rotateLeft(n + 0x9e3779b9.toInt(), 7 + (7 and index))
            words[slot] = n xor mix(n)
            used[slot] = true
            n = mix(n + slot)
        }
        return State(words, used, mix(0xa5a5a5a5.toInt() xor n))
    }

    private fun step(state: State, counter: Int): Int {
        val previous = state.accumulator
        val slot = Integer.remainderUnsigned(previous, 61)
        val oldWord = state.words[slot]
        val counterWord = 0x9e3779b9.toInt() * (counter + 1)
        val mixed = oldWord xor counterWord
        val combined = if (state.used[slot]) {
            (previous xor mixed) or (previous and mixed)
        } else {
            previous xor mixed
        }
        val rotated = Integer.rotateLeft(combined + previous, slot and 31) xor
            Integer.rotateLeft(previous, (slot * 7) and 31)
        val next = mix(rotated + 0x9e3779b9.toInt())
        state.words[slot] = next
        state.used[slot] = true
        state.accumulator = next
        return next
    }

    private fun mix(input: Int): Int {
        var value = input
        value = value xor (value ushr 16)
        value *= 0x85ebca6b.toInt()
        value = value xor (value ushr 13)
        value *= 0xc2b2ae35.toInt()
        return value xor (value ushr 16)
    }

    private companion object {
        val MAGIC = byteArrayOf(109, 118, 109, 49) // mvm1
    }
}
