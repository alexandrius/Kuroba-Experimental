package com.github.k1rakishou.chan.core.site.sites.dvach

import android.webkit.WebView
import com.github.k1rakishou.chan.core.net.JsonReaderRequest
import com.github.k1rakishou.chan.core.site.ChunkDownloaderSiteProperties
import com.github.k1rakishou.chan.core.site.Site
import com.github.k1rakishou.chan.core.site.Site.BoardsType
import com.github.k1rakishou.chan.core.site.Site.SiteFeature
import com.github.k1rakishou.chan.core.site.SiteActions
import com.github.k1rakishou.chan.core.site.SiteAuthentication
import com.github.k1rakishou.chan.core.site.SiteIcon
import com.github.k1rakishou.chan.core.site.SiteRequestModifier
import com.github.k1rakishou.chan.core.site.SiteSetting
import com.github.k1rakishou.chan.core.site.SiteSetting.SiteOptionsSetting
import com.github.k1rakishou.chan.core.site.common.CommonSite
import com.github.k1rakishou.chan.core.site.common.MultipartHttpCall
import com.github.k1rakishou.chan.core.site.common.vichan.VichanActions
import com.github.k1rakishou.chan.core.site.common.vichan.VichanEndpoints
import com.github.k1rakishou.chan.core.site.http.DeleteRequest
import com.github.k1rakishou.chan.core.site.http.HttpCall
import com.github.k1rakishou.chan.core.site.http.login.AbstractLoginRequest
import com.github.k1rakishou.chan.core.site.http.login.DvachLoginRequest
import com.github.k1rakishou.chan.core.site.http.login.DvachLoginResponse
import com.github.k1rakishou.chan.core.site.limitations.PasscodeDependantMaxAttachablesTotalSize
import com.github.k1rakishou.chan.core.site.limitations.PasscodePostingLimitationsInfo
import com.github.k1rakishou.chan.core.site.limitations.SiteDependantAttachablesCount
import com.github.k1rakishou.chan.core.site.limitations.SitePostingLimitationInfo
import com.github.k1rakishou.chan.core.site.parser.CommentParser
import com.github.k1rakishou.chan.core.site.parser.CommentParserType
import com.github.k1rakishou.chan.core.site.sites.chan4.Chan4
import com.github.k1rakishou.common.DoNotStrip
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.common.errorMessageOrClassName
import com.github.k1rakishou.model.data.board.ChanBoard
import com.github.k1rakishou.model.data.board.pages.BoardPages
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.post.ChanPostBuilder
import com.github.k1rakishou.model.data.site.SiteBoards
import com.github.k1rakishou.prefs.JsonSetting
import com.github.k1rakishou.prefs.OptionsSetting
import com.github.k1rakishou.prefs.StringSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.*

@DoNotStrip
class Dvach : CommonSite() {
  private val chunkDownloaderSiteProperties = ChunkDownloaderSiteProperties(
    // 2ch.hk sends file size in KB
    siteSendsCorrectFileSizeInBytes = false,
    // 2ch.hk sometimes sends an incorrect file hash
    canFileHashBeTrusted = false
  )

  private lateinit var passCode: StringSetting
  private lateinit var passCookie: StringSetting
  private lateinit var captchaType: OptionsSetting<Chan4.CaptchaType>
  private lateinit var passCodeInfo: JsonSetting<DvachPasscodeInfo>

  override fun initialize() {
    super.initialize()

    passCode = StringSetting(prefs, "preference_pass_code", "")
    passCookie = StringSetting(prefs, "preference_pass_cookie", "")

    captchaType = OptionsSetting(
      prefs,
      "preference_captcha_type_dvach",
      Chan4.CaptchaType::class.java,
      Chan4.CaptchaType.V2NOJS
    )

    passCodeInfo = JsonSetting(
      gson,
      DvachPasscodeInfo::class.java,
      prefs,
      "preference_pass_code_info",
      DvachPasscodeInfo()
    )
  }

  override fun settings(): List<SiteSetting> {
    val settings = ArrayList<SiteSetting>()

    settings.add(
      SiteOptionsSetting(
        "Captcha type",
        null,
        captchaType,
        mutableListOf("Javascript", "Noscript")
      )
    )

    settings.addAll(
      super.settings()
    )

    return settings
  }

  override fun setParser(commentParser: CommentParser) {
    postParser = DvachPostParser(commentParser, postFilterManager, archivesManager)
  }

  override fun setup() {
    setEnabled(true)
    setName(SITE_NAME)
    setIcon(SiteIcon.fromFavicon(imageLoaderV2, "https://2ch.hk/favicon.ico".toHttpUrl()))
    setBoardsType(BoardsType.DYNAMIC)
    setResolvable(URL_HANDLER)
    setConfig(object : CommonConfig() {
      override fun siteFeature(siteFeature: SiteFeature): Boolean {
        return super.siteFeature(siteFeature)
          || siteFeature == SiteFeature.POSTING
          || siteFeature == SiteFeature.LOGIN
      }
    })
    setEndpoints(DvachEndpoints(this))
    setActions(object : VichanActions(this@Dvach, proxiedOkHttpClient, siteManager, replyManager) {
      override fun setupPost(
        replyChanDescriptor: ChanDescriptor,
        call: MultipartHttpCall
      ): ModularResult<Unit> {
        return super.setupPost(replyChanDescriptor, call)
          .mapValue {
            if (replyChanDescriptor.isThreadDescriptor()) {
              // "thread" is already added in VichanActions.
              call.parameter("post", "New Reply")
            } else {
              call.parameter("post", "New Thread")
              call.parameter("page", "1")
            }

            return@mapValue
          }
      }

      override fun requirePrepare(): Boolean {
        return false
      }

      override suspend fun post(replyChanDescriptor: ChanDescriptor): Flow<SiteActions.PostResult> {
        val replyCall = DvachReplyCall(
          this@Dvach,
          replyChanDescriptor,
          replyManager
        )

        return httpCallManager.makePostHttpCallWithProgress(replyCall)
          .map { replyCallResult ->
            when (replyCallResult) {
              is HttpCall.HttpCallWithProgressResult.Success -> {
                return@map SiteActions.PostResult.PostComplete(
                  replyCallResult.httpCall.replyResponse
                )
              }
              is HttpCall.HttpCallWithProgressResult.Progress -> {
                return@map SiteActions.PostResult.UploadingProgress(
                  replyCallResult.fileIndex,
                  replyCallResult.totalFiles,
                  replyCallResult.percent
                )
              }
              is HttpCall.HttpCallWithProgressResult.Fail -> {
                return@map SiteActions.PostResult.PostError(replyCallResult.error)
              }
            }
          }
      }

      override suspend fun delete(deleteRequest: DeleteRequest): SiteActions.DeleteResult {
        return super.delete(deleteRequest)
      }

      override suspend fun boards(): JsonReaderRequest.JsonReaderResponse<SiteBoards> {
        val dvachEndpoints = endpoints() as DvachEndpoints

        return DvachBoardsRequest(
          siteDescriptor(),
          boardManager,
          proxiedOkHttpClient,
          dvachEndpoints.boards(),
          dvachEndpoints.dvachGetBoards()
        ).execute()
      }

      @Suppress("MoveVariableDeclarationIntoWhen")
      override suspend fun <T : AbstractLoginRequest> login(loginRequest: T): SiteActions.LoginResult {
        val dvachLoginRequest = loginRequest as DvachLoginRequest
        passCode.set(dvachLoginRequest.passcode)

        val loginResult = httpCallManager.makeHttpCall(
          DvachGetPassCookieHttpCall(this@Dvach, loginRequest)
        )

        when (loginResult) {
          is HttpCall.HttpCallResult.Success -> {
            val loginResponse =
              requireNotNull(loginResult.httpCall.loginResponse) { "loginResponse is null" }

            return when (loginResponse) {
              is DvachLoginResponse.Success -> {
                passCookie.set(loginResponse.authCookie)
                SiteActions.LoginResult.LoginComplete(loginResponse)
              }
              is DvachLoginResponse.Failure -> {
                SiteActions.LoginResult.LoginError(loginResponse.errorMessage)
              }
            }
          }
          is HttpCall.HttpCallResult.Fail -> {
            return SiteActions.LoginResult.LoginError(loginResult.error.errorMessageOrClassName())
          }
        }
      }

      override suspend fun getOrRefreshPasscodeInfo(resetCached: Boolean): SiteActions.GetPasscodeInfoResult {
        if (!isLoggedIn()) {
          return SiteActions.GetPasscodeInfoResult.NotLoggedIn
        }

        if (!resetCached && passCodeInfo.isNotDefault()) {
          val dvachPasscodeInfo = passCodeInfo.get()

          val maxAttachedFilesPerPost = dvachPasscodeInfo.files
          val maxTotalAttachablesSize = dvachPasscodeInfo.filesSize

          if (maxAttachedFilesPerPost != null && maxTotalAttachablesSize != null) {
            val passcodePostingLimitationsInfo = PasscodePostingLimitationsInfo(
              maxAttachedFilesPerPost,
              maxTotalAttachablesSize
            )

            return SiteActions.GetPasscodeInfoResult.Success(passcodePostingLimitationsInfo)
          }

          // fallthrough
        }

        val passcodeInfoCall = DvachGetPasscodeInfoHttpCall(this@Dvach, gson)

        val passcodeInfoCallResult = httpCallManager.makeHttpCall(passcodeInfoCall)
        if (passcodeInfoCallResult is HttpCall.HttpCallResult.Fail) {
          return SiteActions.GetPasscodeInfoResult.Failure(passcodeInfoCallResult.error)
        }

        val passcodePostingLimitationsInfoResult = (passcodeInfoCallResult as HttpCall.HttpCallResult.Success)
          .httpCall.passcodePostingLimitationsInfoResult

        if (passcodePostingLimitationsInfoResult is ModularResult.Error) {
          return SiteActions.GetPasscodeInfoResult.Failure(passcodePostingLimitationsInfoResult.error)
        }

        val passcodePostingLimitationsInfo =
          (passcodePostingLimitationsInfoResult as ModularResult.Value).value

        val dvachPasscodeInfo = DvachPasscodeInfo(
          files = passcodePostingLimitationsInfo.maxAttachedFilesPerPost,
          filesSize = passcodePostingLimitationsInfo.maxTotalAttachablesSize
        )

        passCodeInfo.set(dvachPasscodeInfo)

        return SiteActions.GetPasscodeInfoResult.Success(passcodePostingLimitationsInfo)
      }

      override fun postRequiresAuthentication(): Boolean {
        return !isLoggedIn()
      }

      override fun postAuthenticate(): SiteAuthentication {
        return if (isLoggedIn()) {
          SiteAuthentication.fromNone()
        } else {
          when (captchaType.get()) {
            Chan4.CaptchaType.V2JS -> SiteAuthentication.fromCaptcha2(
              CAPTCHA_KEY,
              "https://2ch.hk/api/captcha/recaptcha/mobile"
            )
            Chan4.CaptchaType.V2NOJS -> SiteAuthentication.fromCaptcha2nojs(
              CAPTCHA_KEY,
              "https://2ch.hk/api/captcha/recaptcha/mobile"
            )
            else -> throw IllegalArgumentException()
          }
        }
      }

      override fun logout() {
        passCode.remove()
        passCookie.remove()
        passCodeInfo.reset()
      }

      override fun isLoggedIn(): Boolean {
        return passCookie.get().isNotEmpty()
      }

      override fun loginDetails(): DvachLoginRequest {
        return DvachLoginRequest(passCode.get())
      }

      override suspend fun pages(
        board: ChanBoard
      ): JsonReaderRequest.JsonReaderResponse<BoardPages> {
        val request = Request.Builder()
          .url(endpoints().pages(board))
          .get()
          .build()

        return DvachPagesRequest(
          board,
          request,
          proxiedOkHttpClient
        ).execute()
      }

    })
    setRequestModifier(siteRequestModifier)
    setApi(DvachApi(siteManager, boardManager, this))
    setParser(DvachCommentParser(mockReplyManager))

    setPostingLimitationInfo(
      SitePostingLimitationInfo(
        postMaxAttachables = SiteDependantAttachablesCount(siteManager, 4),
        postMaxAttachablesTotalSize = PasscodeDependantMaxAttachablesTotalSize(
          siteManager = siteManager
        )
      )
    )
  }

  override fun commentParserType(): CommentParserType {
    return CommentParserType.DvachParser
  }

  override fun getChunkDownloaderSiteProperties(): ChunkDownloaderSiteProperties {
    return chunkDownloaderSiteProperties
  }

  inner class DvachEndpoints(commonSite: CommonSite) : VichanEndpoints(commonSite, "https://2ch.hk", "https://2ch.hk") {
    override fun imageUrl(post: ChanPostBuilder, arg: Map<String, String>): HttpUrl {
      val path = requireNotNull(arg["path"]) { "\"path\" parameter not found" }

      return root.builder().s(path).url()
    }

    override fun thumbnailUrl(
      post: ChanPostBuilder,
      spoiler: Boolean,
      customSpoilers: Int,
      arg: Map<String, String>
    ): HttpUrl {
      val thumbnail = requireNotNull(arg["thumbnail"]) { "\"thumbnail\" parameter not found" }

      return root.builder().s(thumbnail).url()
    }

    fun dvachGetBoards(): HttpUrl {
      return HttpUrl.Builder()
        .scheme("https")
        .host("2ch.hk")
        .addPathSegment("makaba")
        .addPathSegment("mobile.fcgi")
        .addQueryParameter("task", "get_boards")
        .build()
    }

    override fun boards(): HttpUrl {
      return HttpUrl.Builder()
        .scheme("https")
        .host("2ch.hk")
        .addPathSegment("boards.json")
        .build()
    }

    override fun pages(board: ChanBoard): HttpUrl {
      return HttpUrl.Builder()
        .scheme("https")
        .host("2ch.hk")
        .addPathSegment(board.boardCode())
        .addPathSegment("catalog.json")
        .build()
    }

    override fun reply(chanDescriptor: ChanDescriptor): HttpUrl {
      return HttpUrl.Builder()
        .scheme("https")
        .host("2ch.hk")
        .addPathSegment("makaba")
        .addPathSegment("posting.fcgi")
        .addQueryParameter("json", "1")
        .build()
    }

    override fun login(): HttpUrl {
      return HttpUrl.Builder()
        .scheme("https")
        .host("2ch.hk")
        .addPathSegment("makaba")
        .addPathSegment("makaba.fcgi")
        .build()
    }

    override fun passCodeInfo(): HttpUrl? {
      if (!actions().isLoggedIn()) {
        return null
      }

      val passcode = passCode.get()
      if (passcode.isEmpty()) {
        return null
      }

      return HttpUrl.Builder()
        .scheme("https")
        .host("2ch.hk")
        .addPathSegment("makaba")
        .addPathSegment("makaba.fcgi")
        .addQueryParameter("task", "auth")
        .addQueryParameter("usercode", passcode)
        .addQueryParameter("json", "1")
        .build()
    }
  }

  private val siteRequestModifier = object : SiteRequestModifier {

    override fun modifyHttpCall(httpCall: HttpCall, requestBuilder: Request.Builder) {
      if (actions().isLoggedIn()) {
        requestBuilder.addHeader("Cookie", "passcode_auth=" + passCookie.get())
      }
    }

    override fun modifyWebView(webView: WebView?) {
    }

  }

  companion object {
    private const val TAG = "Dvach"
    const val SITE_NAME = "2ch.hk"
    const val CAPTCHA_KEY = "6LeQYz4UAAAAAL8JCk35wHSv6cuEV5PyLhI6IxsM"
    const val DEFAULT_MAX_FILE_SIZE = 20480 * 1024 // 20MB

    @JvmField
    val URL_HANDLER: CommonSiteUrlHandler = object : CommonSiteUrlHandler() {
      val ROOT = "https://2ch.hk/"

      override fun getSiteClass(): Class<out Site?> {
        return Dvach::class.java
      }

      override val url: HttpUrl
        get() = ROOT.toHttpUrl()

      override val mediaHosts: Array<HttpUrl>
        get() = arrayOf(url)

      override val names: Array<String>
        get() = arrayOf("dvach", "2ch")

      override fun desktopUrl(chanDescriptor: ChanDescriptor, postNo: Long?): String {
        when (chanDescriptor) {
          is ChanDescriptor.CatalogDescriptor -> {
            val builtUrl = url.newBuilder()
              .addPathSegment(chanDescriptor.boardCode())
              .toString()

            if (postNo == null) {
              return builtUrl
            }

            return "${builtUrl}/res/${postNo}.html"
          }
          is ChanDescriptor.ThreadDescriptor -> {
            val builtUrl = url.newBuilder()
              .addPathSegment(chanDescriptor.boardCode())
              .addPathSegment("res")
              .addPathSegment(chanDescriptor.threadNo.toString() + ".html")
              .toString()

            if (postNo == null) {
              return builtUrl
            }

            return "${builtUrl}#${postNo}"
          }
          else -> return url.toString()
        }
      }
    }
  }

}