package com.wafflestudio.snutt.core.test.stress

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 서버가 ERROR로 남긴 예외를 모은다. 500 응답 본문은 원인을 숨기므로(`SnuttExceptionHandler`가 "unhandled exception"으로 로그만 남긴다)
 * 데드락·lock wait timeout·풀 타임아웃은 이 로그로 구분한다. 루트 로거에 붙였다가 [close]에서 뗀다.
 */
class ServerErrorLog : AutoCloseable {
    class Entry(
        val logger: String,
        val message: String,
        val error: Throwable,
    ) {
        val kind: FailureKind = FailureKind.classify(error)
    }

    private val entries = ConcurrentLinkedQueue<Entry>()
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    private val appender =
        object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                if (!event.level.isGreaterOrEqual(Level.ERROR)) return
                val error = (event.throwableProxy as? ThrowableProxy)?.throwable ?: return
                entries += Entry(event.loggerName, event.formattedMessage, error)
            }
        }

    init {
        appender.context = root.loggerContext
        appender.name = "stress-server-errors"
        appender.start()
        root.addAppender(appender)
    }

    /** 지금까지 모은 항목을 꺼내고 비운다. 반복마다 불러서 반복 번호를 붙인다. */
    fun drain(): List<Entry> = generateSequence { entries.poll() }.toList()

    override fun close() {
        root.detachAppender(appender)
        appender.stop()
    }
}
