package com.github.k1rakishou.chan.core.cache

import com.google.android.exoplayer2.source.MediaSource

interface MediaSourceCallback {
  fun onMediaSourceReady(source: MediaSource?)
  fun onError(error: Throwable)
}