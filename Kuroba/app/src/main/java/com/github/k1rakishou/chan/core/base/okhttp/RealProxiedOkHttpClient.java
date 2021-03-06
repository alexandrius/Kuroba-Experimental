package com.github.k1rakishou.chan.core.base.okhttp;

import com.github.k1rakishou.chan.Chan;
import com.github.k1rakishou.chan.core.helper.ProxyStorage;
import com.github.k1rakishou.chan.core.net.KurobaProxySelector;

import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

import static java.util.concurrent.TimeUnit.SECONDS;

// this is basically the same as OkHttpClient, but with a singleton for a proxy instance
public class RealProxiedOkHttpClient implements ProxiedOkHttpClient {
    private OkHttpClient proxiedClient;

    private final Dns okHttpDns;
    private final Chan.OkHttpProtocols okHttpProtocols;
    private final ProxyStorage proxyStorage;
    private final HttpLoggingInterceptorLazy httpLoggingInterceptorLazy;

    @Inject
    public RealProxiedOkHttpClient(
            Dns okHttpDns,
            Chan.OkHttpProtocols okHttpProtocols,
            ProxyStorage proxyStorage,
            HttpLoggingInterceptorLazy httpLoggingInterceptorLazy
    ) {
        this.okHttpDns = okHttpDns;
        this.okHttpProtocols = okHttpProtocols;
        this.proxyStorage = proxyStorage;
        this.httpLoggingInterceptorLazy = httpLoggingInterceptorLazy;
    }

    @NotNull
    @Override
    public OkHttpClient okHttpClient() {
        if (proxiedClient == null) {
            synchronized (this) {
                if (proxiedClient == null) {
                    KurobaProxySelector kurobaProxySelector = new KurobaProxySelector(
                            proxyStorage,
                            ProxyStorage.ProxyActionType.SiteRequests
                    );

                    // Proxies are usually slow, so they have increased timeouts
                    OkHttpClient.Builder builder = new OkHttpClient.Builder()
                            .connectTimeout(30, SECONDS)
                            .readTimeout(30, SECONDS)
                            .writeTimeout(30, SECONDS)
                            .protocols(okHttpProtocols.getProtocols())
                            .proxySelector(kurobaProxySelector)
                            .dns(okHttpDns);

                    HttpLoggingInterceptorInstaller.install(builder, httpLoggingInterceptorLazy);
                    proxiedClient = builder.build();
                }
            }
        }

        return proxiedClient;
    }
}