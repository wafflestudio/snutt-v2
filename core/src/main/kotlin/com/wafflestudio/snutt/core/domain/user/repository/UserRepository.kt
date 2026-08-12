package com.wafflestudio.snutt.core.domain.user.repository

import com.wafflestudio.snutt.core.domain.user.model.User
import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<User, Long> {
    fun findByExternalIdAndActiveTrue(externalId: String): User?

    fun findByCredentialHashAndActiveTrue(credentialHash: String): User?

    fun findByLocalIdAndActiveTrue(localId: String): User?

    fun findByFacebookSubAndActiveTrue(facebookSub: String): User?

    fun findByAppleSubAndActiveTrue(appleSub: String): User?

    fun findByAppleTransferSubAndActiveTrue(appleTransferSub: String): User?

    fun findByGoogleSubAndActiveTrue(googleSub: String): User?

    fun findByKakaoSubAndActiveTrue(kakaoSub: String): User?

    fun findByEmailAndIsEmailVerifiedTrueAndActiveTrue(email: String): User?

    fun findByNicknameAndActiveTrue(nickname: String): User?

    fun findByIdAndActiveTrue(id: Long): User?

    fun findByEmailContainingIgnoreCaseAndActiveTrue(email: String): List<User>

    fun findAllByNicknameStartingWithAndActiveTrue(nickname: String): List<User>

    fun existsByLocalIdAndActiveTrue(localId: String): Boolean
}
