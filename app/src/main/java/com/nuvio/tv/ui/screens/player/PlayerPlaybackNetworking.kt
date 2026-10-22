package com.nuvio.tv.ui.screens.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.core.network.IPv4FirstDns
import com.jojotv.home.iptv.IptvDns
import com.jojotv.home.iptv.IptvDnsConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal object PlayerPlaybackNetworking {
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val playbackHostnameVerifier = HostnameVerifier { _, _ -> true }

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
    }

    private val playbackHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(IPv4FirstDns(IptvDns))
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier(playbackHostnameVerifier)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build().let(IptvDns::register)
    }

    @OptIn(UnstableApi::class)
    fun createHttpDataSourceFactory(defaultHeaders: Map<String, String> = emptyMap(), dns: IptvDnsConfig? = null): DataSource.Factory {
        val base = IptvDns.clientFor(playbackHttpClient, dns)
        val stickyHeaders = defaultHeaders.filterKeys { key ->
            key.equals("Authorization", ignoreCase = true) ||
                key.equals("X-Fbx-App-Auth", ignoreCase = true) ||
                key.equals("Cookie", ignoreCase = true)
        }
        val client = if (stickyHeaders.isNotEmpty()) {
            // Some servers/redirects drop auth/session headers. Reapply the
            // sensitive playback headers to every network request, which is
            // closer to Kodi/mpv behavior for direct NAS/Freebox playback.
            base.newBuilder()
                .addNetworkInterceptor { chain ->
                    val request = chain.request()
                    val builder = request.newBuilder()
                    stickyHeaders.forEach { (name, value) ->
                        if (request.header(name) == null) {
                            builder.header(name, value)
                        }
                    }
                    chain.proceed(builder.build())
                }
                .build()
        } else {
            base
        }
        return OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(defaultHeaders + defaultPlaybackHeaders(defaultHeaders))
            if (defaultHeaders.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                setUserAgent(PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            }
        }
    }

    private fun defaultPlaybackHeaders(headers: Map<String, String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (headers.none { it.key.equals("Accept", ignoreCase = true) }) result["Accept"] = "*/*"
        if (headers.none { it.key.equals("Connection", ignoreCase = true) }) result["Connection"] = "keep-alive"
        return result
    }
    @OptIn(UnstableApi::class)
    fun createDataSourceFactory(
        context: android.content.Context,
        defaultHeaders: Map<String, String> = emptyMap(),
        dns: IptvDnsConfig? = null
    ): DataSource.Factory {
        return DefaultDataSource.Factory(context, createHttpDataSourceFactory(defaultHeaders, dns))
    }

    // Format probes use the same DNS and HTTP policy as the actual media requests.
    fun openResponse(
        url: String,
        headers: Map<String, String>,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        range: String? = null,
        dns: IptvDnsConfig? = null
    ): Response {
        val request = Request.Builder().url(url).method(method, null).apply {
            header("User-Agent", headers["User-Agent"] ?: PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            headers.forEach { (key, value) ->
                if (key.equals("Range", ignoreCase = true)) return@forEach
                if (key.equals("User-Agent", ignoreCase = true)) return@forEach
                header(key, value)
            }
            range?.let { header("Range", it) }
        }.build()
        return IptvDns.clientFor(playbackHttpClient, dns).newBuilder()
            .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(connectTimeoutMs.toLong() + readTimeoutMs, TimeUnit.MILLISECONDS)
            .build().newCall(request).execute()
    }
}
