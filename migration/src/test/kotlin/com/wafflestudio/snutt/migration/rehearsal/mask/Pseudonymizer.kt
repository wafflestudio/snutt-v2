package com.wafflestudio.snutt.migration.rehearsal.mask

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 결정적 가명화. 같은 비밀값·영역(domain)·원문이면 언제나 같은 결과가 나오고, 비밀값 없이는 원문을 되돌릴 수 없다(HMAC-SHA256).
 *
 * 글자 종류와 코드포인트 수를 보존한다: 숫자→숫자, 영문 소문자→소문자, 대문자→대문자, 한글 음절→한글 음절,
 * 그 밖의 문자(한자 등)→한글 음절. 공백·구두점·기호·이모지·`@`·`#`·`$`는 그대로 둔다. 그래서 길이 분포, 줄바꿈,
 * bcrypt(`$2a$10$…`)나 이메일 같은 형식이 유지된다.
 *
 * 영역이 다르면 같은 원문도 다르게 바뀐다. 서로 참조하는 값(사용자 이메일과 소셜 이메일, 테마 이름과 게시 이름)은 같은 영역을 쓴다.
 */
class Pseudonymizer(
    secret: ByteArray,
) {
    init {
        require(secret.size >= 16) { "마스킹 비밀값은 16바이트 이상이어야 한다" }
    }

    private val key = SecretKeySpec(secret, "HmacSHA256")

    fun text(
        domain: String,
        value: String,
        attempt: Int = 0,
    ): String {
        val stream = KeyStream(mac("$domain\u0000$attempt\u0000$value".toByteArray()))
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            out.appendCodePoint(substitute(codePoint, stream))
            index += Character.charCount(codePoint)
        }
        return out.toString()
    }

    private fun substitute(
        codePoint: Int,
        stream: KeyStream,
    ): Int =
        when {
            codePoint in '0'.code..'9'.code -> '0'.code + stream.next() % 10
            codePoint in 'a'.code..'z'.code -> 'a'.code + stream.next() % 26
            codePoint in 'A'.code..'Z'.code -> 'A'.code + stream.next() % 26
            codePoint in HANGUL_FIRST..HANGUL_LAST -> HANGUL_FIRST + stream.next() % HANGUL_COUNT
            Character.isLetter(codePoint) -> HANGUL_FIRST + stream.next() % HANGUL_COUNT
            else -> codePoint
        }

    private fun mac(input: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(input)

    /** HMAC 출력을 카운터로 늘려 쓰는 키 스트림. 글자 하나에 16비트를 쓴다. */
    private inner class KeyStream(
        private val seed: ByteArray,
    ) {
        private var block = seed
        private var position = 0
        private var counter = 0

        fun next(): Int {
            if (position + 2 > block.size) {
                counter++
                block = mac(seed + counter.toString().toByteArray())
                position = 0
            }
            val value = ((block[position].toInt() and 0xFF) shl 8) or (block[position + 1].toInt() and 0xFF)
            position += 2
            return value
        }
    }

    companion object {
        private const val HANGUL_FIRST = 0xAC00
        private const val HANGUL_LAST = 0xD7A3
        private const val HANGUL_COUNT = HANGUL_LAST - HANGUL_FIRST + 1
    }
}

/**
 * 원래 서로 다른 값이 가명화 뒤에 같아지지 않게 하는 표. 유일 제약이나 중복 판정에 쓰이는 값(닉네임, 아이디, 이메일, 소셜 sub, 토큰)에 쓴다.
 * 충돌하면 attempt를 올려 다시 만든다. 결과가 처리 순서에 따라 달라지지 않도록 [prepare]로 모든 키를 정렬된 순서로 먼저 배정한다.
 */
class InjectiveMap(
    private val generate: (key: String, attempt: Int) -> String,
) {
    private val forward = HashMap<String, String>()
    private val taken = HashSet<String>()

    val size get() = forward.size

    fun prepare(keys: Collection<String>) {
        keys.toSortedSet().forEach { get(it) }
    }

    fun get(key: String): String {
        forward[key]?.let { return it }
        var attempt = 0
        while (true) {
            val candidate = generate(key, attempt)
            if (taken.add(candidate)) {
                forward[key] = candidate
                return candidate
            }
            attempt++
        }
    }
}
