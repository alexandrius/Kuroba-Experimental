package com.github.adamantcheese.chan.features.bookmarks.watcher

import com.github.adamantcheese.chan.core.manager.BookmarksManager
import com.github.adamantcheese.chan.utils.Logger
import com.github.adamantcheese.chan.utils.errorMessageOrClassName
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkForegroundWatcher(
  private val isDevFlavor: Boolean,
  private val appScope: CoroutineScope,
  private val bookmarksManager: BookmarksManager,
  private val bookmarkWatcherDelegate: BookmarkWatcherDelegate
) {
  private val channel = Channel<Unit>(Channel.RENDEZVOUS)
  private val working = AtomicBoolean(false)

  private var workJob: Job? = null

  init {
    appScope.launch {
      channel.consumeEach {
        if (working.compareAndSet(false, true)) {
          if (isDevFlavor) {
            Logger.d(TAG, "working == true, calling doWorkAndWaitUntilNext()")
          }

          workJob = appScope.launch(Dispatchers.Default) {
            try {
              doWorkAndWaitUntilNext()
            } catch (error: Throwable) {
              logErrorIfNeeded(error)
            } finally {
              working.set(false)
            }
          }
        }
      }
    }
  }

  fun startWatching() {
    channel.offer(Unit)
  }

  fun stopWatching() {
    workJob?.cancel()
    workJob = null
  }

  private suspend fun CoroutineScope.doWorkAndWaitUntilNext() {
    while (true) {
      try {
        bookmarkWatcherDelegate.doWork(true)
      } catch (error: Throwable) {
        Logger.e(TAG, "Unhandled exception in bookmarkWatcherDelegate.doWork()")
      }

      if (!isActive) {
        return
      }

      delay(FOREGROUND_INITIAL_INTERVAL_MS + calculateAndLogAdditionalInterval())

      if (!isActive) {
        return
      }
    }
  }

  private fun calculateAndLogAdditionalInterval(): Long {
    val activeBookmarksCount = bookmarksManager.activeBookmarksCount()

    // Increment the interval for every 10 bookmarks by ADDITIONAL_INTERVAL_INCREMENT_MS. This way
    // if we have 100 active bookmarks we will be waiting 30secs + (10 * 5)secs = 80secs. This is
    // needed to not kill the battery with constant network request spam.
    val additionalInterval = (activeBookmarksCount / 10) * ADDITIONAL_INTERVAL_INCREMENT_MS

    if (isDevFlavor) {
      Logger.d(TAG, "bookmarkWatcherDelegate.doWork() completed, waiting for " +
        "${FOREGROUND_INITIAL_INTERVAL_MS}ms + ${additionalInterval}ms " +
        "(activeBookmarksCount: $activeBookmarksCount, " +
        "total wait time: ${FOREGROUND_INITIAL_INTERVAL_MS + additionalInterval}ms)")
    }

    return additionalInterval
  }

  private fun logErrorIfNeeded(error: Throwable) {
    if (error is CancellationException) {
      return
    }

    if (isDevFlavor) {
      Logger.e(TAG, "Error while doing foreground bookmark watching", error)
    } else {
      Logger.e(TAG, "Error while doing foreground bookmark watching: ${error.errorMessageOrClassName()}")
    }
  }

  companion object {
    private const val TAG = "BookmarkForegroundWatcher"
    private const val FOREGROUND_INITIAL_INTERVAL_MS = 30L * 1000L
    private const val ADDITIONAL_INTERVAL_INCREMENT_MS = 5L * 1000L
  }
}