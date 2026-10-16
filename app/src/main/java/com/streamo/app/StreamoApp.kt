package com.streamo.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.streamo.app.data.LibraryStore
import com.streamo.app.data.OkHttpDownloader
import com.streamo.app.data.SponsorBlock
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale
import java.util.concurrent.TimeUnit

class StreamoApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        LibraryStore.init(this)
        com.streamo.app.download.Downloads.init(this)
        val locale = Locale.getDefault()
        NewPipe.init(
            OkHttpDownloader(http),
            Localization.fromLocale(locale),
            ContentCountry(locale.country.ifEmpty { "US" }),
        )
    }

    // Thumbnails: kept in memory and on disk so scrolling back is instant.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("thumbnails"))
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .build()

    companion object {
        /** One shared HTTP client: shared connections make every request faster. */
        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                // OkHttp allows only 5 parallel requests per site by default, which made
                // thumbnails and channel pictures trickle in while scrolling.
                .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
                .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
                .build()
        }
        val sponsorBlock by lazy { SponsorBlock(http) }
    }
}
