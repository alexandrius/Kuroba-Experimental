package com.github.adamantcheese.chan.core.image

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.annotation.DrawableRes
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.network.HttpException
import coil.request.LoadRequest
import coil.request.RequestDisposable
import coil.size.Scale
import coil.transform.Transformation
import com.github.adamantcheese.chan.R
import com.github.adamantcheese.chan.core.model.PostImage
import com.github.adamantcheese.chan.ui.theme.ThemeHelper
import com.github.adamantcheese.chan.utils.BackgroundUtils
import com.github.adamantcheese.chan.utils.Logger
import com.github.adamantcheese.chan.utils.getLifecycleFromContext
import java.util.concurrent.atomic.AtomicReference

class ImageLoaderV2(
  private val imageLoader: ImageLoader,
  private val verboseLogsEnabled: Boolean,
  private val themeHelper: ThemeHelper
) {
  private var imageNotFoundDrawable: BitmapDrawable? = null
  private var imageErrorLoadingDrawable: BitmapDrawable? = null

  fun loadFromNetwork(
    context: Context,
    requestUrl: String,
    listener: ImageListener
  ): RequestDisposable {
    BackgroundUtils.ensureMainThread()
    return loadFromNetwork(context, requestUrl, null, null, listener)
  }

  fun loadFromNetwork(
    context: Context,
    url: String?,
    width: Int?,
    height: Int?,
    transformations: List<Transformation>,
    listener: SimpleImageListener,
    @DrawableRes errorDrawableId: Int? = null,
    @DrawableRes notFoundDrawableId: Int? = errorDrawableId
  ): RequestDisposable {
    val listenerRef = AtomicReference(listener)
    val contextRef = AtomicReference(context)
    val lifecycle = context.getLifecycleFromContext()

    if (verboseLogsEnabled) {
      Logger.d(TAG, "loadFromNetwork(url=$url, width=$width, height=$height)")
    }

    val request = with(LoadRequest.Builder(context)) {
      if (url != null) {
        data(url)
      } else {
        data(getImageNotFoundDrawable(context))
      }

      lifecycle(lifecycle)
      transformations(transformations)
      allowHardware(true)
      scale(Scale.FIT)

      if ((width != null && width > 0) && (height != null && height > 0)) {
        size(width, height)
      }

      listener(
        onError = { _, throwable ->
          val realContext = contextRef.get()

          try {
            if (realContext != null) {
              if (throwable is HttpException && throwable.response.code == 404) {
                if (notFoundDrawableId != null) {
                  loadFromResources(context, notFoundDrawableId, width, height, transformations, listener)
                  return@listener
                }

                listenerRef.get()?.onResponse(getImageNotFoundDrawable(realContext))
              } else {
                if (errorDrawableId != null) {
                  loadFromResources(context, errorDrawableId, width, height, transformations, listener)
                  return@listener
                }

                listenerRef.get()?.onResponse(getImageErrorLoadingDrawable(realContext))
              }
            }
          } finally {
            listenerRef.set(null)
            contextRef.set(null)
          }
        },
        onCancel = {
          listenerRef.set(null)
          contextRef.set(null)
        }
      )
      target(
        onSuccess = { drawable ->
          try {
            listenerRef.get()?.onResponse(drawable as BitmapDrawable)
          } finally {
            listenerRef.set(null)
            contextRef.set(null)
          }
        }
      )

      build()
    }

    return imageLoader.execute(request)
  }

  fun loadFromResources(
    context: Context,
    @DrawableRes drawableId: Int,
    width: Int?,
    height: Int?,
    transformations: List<Transformation>,
    listener: SimpleImageListener
  ): RequestDisposable {
    val listenerRef = AtomicReference(listener)
    val contextRef = AtomicReference(context)
    val lifecycle = context.getLifecycleFromContext()

    if (verboseLogsEnabled) {
      Logger.d(TAG, "loadFromResources(drawableId=$drawableId, width=$width, height=$height)")
    }

    val request = with(LoadRequest.Builder(context)) {
      data(drawableId)
      lifecycle(lifecycle)
      transformations(transformations)
      allowHardware(true)
      scale(Scale.FIT)

      if ((width != null && width > 0) && (height != null && height > 0)) {
        size(width, height)
      }

      listener(
        onError = { _, throwable ->
          listenerRef.set(null)
          contextRef.set(null)

          throw throwable
        },
        onCancel = {
          listenerRef.set(null)
          contextRef.set(null)
        }
      )
      target(
        onSuccess = { drawable ->
          try {
            listenerRef.get()?.onResponse(drawable as BitmapDrawable)
          } finally {
            listenerRef.set(null)
            contextRef.set(null)
          }
        }
      )

      build()
    }

    return imageLoader.execute(request)
  }

  fun loadFromNetwork(
    context: Context,
    url: String?,
    width: Int?,
    height: Int?,
    listener: ImageListener
  ): RequestDisposable {
    val localListener = AtomicReference(listener)
    val lifecycle = context.getLifecycleFromContext()

    if (verboseLogsEnabled) {
      Logger.d(TAG, "loadFromNetwork(url=$url, width=$width, height=$height)")
    }

    val request = with(LoadRequest.Builder(context)) {
      if (url != null) {
        data(url)
      } else {
        data(getImageNotFoundDrawable(context))
      }

      lifecycle(lifecycle)
      scale(Scale.FIT)
      allowHardware(true)

      if ((width != null && width > 0) && (height != null && height > 0)) {
        size(width, height)
      }

      listener(
        onError = { _, throwable ->
          try {
            if (throwable is HttpException && throwable.response.code == 404) {
              localListener.get()?.onNotFound()
            } else {
              localListener.get()?.onResponseError(throwable)
            }
          } finally {
            localListener.set(null)
          }
        },
        onCancel = {
          localListener.set(null)
        }
      )
      target(
        onSuccess = { drawable ->
          try {
            localListener.get()?.onResponse(drawable as BitmapDrawable, false)
          } finally {
            localListener.set(null)
          }
        }
      )

      build()
    }

    return imageLoader.execute(request)
  }

  @Suppress("UnnecessaryVariable")
  fun load(
    context: Context,
    postImage: PostImage,
    width: Int,
    height: Int,
    listener: ImageListener
  ): RequestDisposable? {
    BackgroundUtils.ensureMainThread()

    val url = postImage.getThumbnailUrl().toString()
    return loadFromNetwork(context, url, width, height, listener)
  }

  @Synchronized
  private fun getImageNotFoundDrawable(context: Context): BitmapDrawable {
    if (imageNotFoundDrawable != null) {
      return imageNotFoundDrawable!!
    }

    val drawable = themeHelper.tintDrawable(
      context,
      R.drawable.ic_image_not_found,
      themeHelper.theme.textHint
    )

    requireNotNull(drawable) { "Couldn't load R.drawable.ic_image_not_found" }

    if (drawable is BitmapDrawable) {
      imageNotFoundDrawable = drawable
    } else {
      imageNotFoundDrawable = BitmapDrawable(context.resources, drawable.toBitmap())
    }


    return imageNotFoundDrawable!!
  }

  @Synchronized
  private fun getImageErrorLoadingDrawable(context: Context): BitmapDrawable {
    if (imageErrorLoadingDrawable != null) {
      return imageErrorLoadingDrawable!!
    }

    val drawable = themeHelper.tintDrawable(
      context,
      R.drawable.ic_image_error_loading,
      themeHelper.theme.textHint
    )

    requireNotNull(drawable) { "Couldn't load R.drawable.ic_image_error_loading" }

    if (drawable is BitmapDrawable) {
      imageErrorLoadingDrawable = drawable
    } else {
      imageErrorLoadingDrawable = BitmapDrawable(context.resources, drawable.toBitmap())
    }

    return imageErrorLoadingDrawable!!
  }

  fun interface SimpleImageListener {
    fun onResponse(drawable: BitmapDrawable)
  }

  interface ImageListener {
    fun onResponse(drawable: BitmapDrawable, isImmediate: Boolean)
    fun onNotFound()
    fun onResponseError(error: Throwable)
  }

  companion object {
    private const val TAG = "ImageLoaderV2"
  }

}