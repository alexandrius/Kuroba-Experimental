package com.github.adamantcheese.database.repository

import com.github.adamantcheese.base.ModularResult
import com.github.adamantcheese.database.KurobaDatabase
import com.github.adamantcheese.database.common.Logger
import com.github.adamantcheese.database.data.video_service.MediaServiceLinkExtraContent
import com.github.adamantcheese.database.data.video_service.MediaServiceType
import com.github.adamantcheese.database.source.local.MediaServiceLinkExtraContentLocalSource
import com.github.adamantcheese.database.source.remote.MediaServiceLinkExtraContentRemoteSource
import com.github.adamantcheese.database.util.errorMessageOrClassName
import java.util.concurrent.atomic.AtomicBoolean

class MediaServiceLinkExtraContentRepository(
        database: KurobaDatabase,
        loggerTag: String,
        private val logger: Logger,
        private val mediaServiceLinkExtraContentLocalSource: MediaServiceLinkExtraContentLocalSource,
        private val mediaServiceLinkExtraContentRemoteSource: MediaServiceLinkExtraContentRemoteSource
) : AbstractRepository(database) {
    private val TAG = "$loggerTag MediaServiceLinkExtraContentRepository"
    private val alreadyExecuted = AtomicBoolean(false)

    suspend fun getLinkExtraContent(
            mediaServiceType: MediaServiceType,
            requestUrl: String,
            originalUrl: String
    ): ModularResult<MediaServiceLinkExtraContent> {
        mediaServiceLinkExtraContentRepositoryCleanup().ignore()

        val localSourceResult = mediaServiceLinkExtraContentLocalSource.selectByVideoUrl(originalUrl)
        when (localSourceResult) {
            is ModularResult.Error -> {
                logger.logError(TAG, "Error while trying to get MediaServiceLinkExtraContent from " +
                        "local source: error = ${localSourceResult.error.errorMessageOrClassName()}, " +
                        "originalUrl = ${originalUrl}")
                return ModularResult.error(localSourceResult.error)
            }
            is ModularResult.Value -> {
                if (localSourceResult.value != null) {
                    return ModularResult.value(localSourceResult.value!!)
                }

                // Fallthrough
            }
        }

        val remoteSourceResult = mediaServiceLinkExtraContentRemoteSource.fetchFromNetwork(
                requestUrl,
                mediaServiceType
        )

        when (remoteSourceResult) {
            is ModularResult.Error -> {
                logger.logError(TAG, "Error while trying to fetch MediaServiceLinkExtraContent from " +
                        "remote source: error = ${remoteSourceResult.error.errorMessageOrClassName()}, " +
                        "requestUrl = ${requestUrl}, mediaServiceType = ${mediaServiceType}")
                return ModularResult.error(remoteSourceResult.error)
            }
            is ModularResult.Value -> {
                val mediaServiceLinkExtraInfo = remoteSourceResult.value

                val mediaServiceLinkExtraContent = MediaServiceLinkExtraContent(
                        originalUrl,
                        mediaServiceType,
                        mediaServiceLinkExtraInfo.videoTitle,
                        mediaServiceLinkExtraInfo.videoDuration
                )

                val storeResult = mediaServiceLinkExtraContentLocalSource.insert(
                        mediaServiceLinkExtraContent
                )

                when (storeResult) {
                    is ModularResult.Error -> {
                        logger.logError(TAG, "Error while trying to store MediaServiceLinkExtraContent in the " +
                                "local source: error = ${storeResult.error.errorMessageOrClassName()}, " +
                                "mediaServiceLinkExtraContent = ${mediaServiceLinkExtraContent}")
                        return ModularResult.error(storeResult.error)
                    }
                    is ModularResult.Value -> {
                        return ModularResult.value(mediaServiceLinkExtraContent)
                    }
                }
            }
        }
    }

    private suspend fun mediaServiceLinkExtraContentRepositoryCleanup(): ModularResult<Int> {
        if (!alreadyExecuted.compareAndSet(false, true)) {
            return ModularResult.value(0)
        }

        val result = mediaServiceLinkExtraContentLocalSource.deleteOlderThanOneWeek()
        if (result is ModularResult.Value) {
            logger.log(TAG, "cleanup() -> $result")
        } else {
            logger.logError(TAG, "cleanup() -> $result")
        }

        return result
    }
}