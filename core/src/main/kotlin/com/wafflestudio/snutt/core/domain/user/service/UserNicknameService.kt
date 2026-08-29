package com.wafflestudio.snutt.core.domain.user.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component

@Component
class UserNicknameService(
    private val userRepository: UserRepository,
    resourceLoader: ResourceLoader,
) {
    private val adjectives = readLines(resourceLoader.getResource("classpath:adjectives.txt"))
    private val nouns = readLines(resourceLoader.getResource("classpath:nouns.txt"))

    private val nicknames =
        adjectives
            .flatMap { adj -> nouns.map { "$adj $it" } }
            .filter { it.length <= NICKNAME_MAX_LENGTH }

    companion object {
        private const val NICKNAME_TAG_LENGTH_BOUND = 10_000
        private const val NICKNAME_MAX_LENGTH = 10
        private val nicknameRegex = "^[a-zA-Z가-힣0-9 ]+$".toRegex()
    }

    private fun readLines(resource: Resource): List<String> =
        resource.inputStream
            .readBytes()
            .decodeToString()
            .split("\n")
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }

    fun generateUniqueRandomNickname(): String = appendNewTag(nicknames.random())

    fun appendNewTag(nickname: String): String {
        if (!isValidNickname(nickname)) throw SnuttException(ErrorType.INVALID_NICKNAME)

        val existingTags =
            userRepository
                .findAllByNicknameStartingWithAndActiveTrue(nickname)
                .mapNotNull { it.nicknameTag }
                .toSet()
        val newTag =
            generateSequence { (0 until NICKNAME_TAG_LENGTH_BOUND).random() }
                .filter { it !in existingTags }
                .first()

        return "$nickname${User.NICKNAME_TAG_DELIMITER}%04d".format(newTag)
    }

    private fun isValidNickname(nickname: String): Boolean =
        nickname.isNotBlank() && nickname.length <= NICKNAME_MAX_LENGTH && nickname.matches(nicknameRegex)
}
