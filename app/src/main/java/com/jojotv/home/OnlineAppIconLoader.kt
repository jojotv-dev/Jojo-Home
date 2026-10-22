package com.jojotv.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.view.View
import coil3.Image
import coil3.asDrawable
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.error
import coil3.target.ImageViewTarget
import coil3.target.Target
import kotlin.math.roundToInt

/** Loads online icons and rasterizes SVG artwork through the app's Coil image loader. */
object OnlineAppIconLoader {
    private val iconIdPattern = Regex("^[a-z0-9-]+:[a-z0-9-]+$")
    private val titleLogoBitmaps = object : android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val titleLogoRequests = mutableMapOf<String, MutableList<SvgBitmapCallback>>()
    private val titleLogoWork = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    @JvmStatic
    fun cachedTitleLogoBitmap(url: String?): Bitmap? =
        url?.takeIf { it.isNotBlank() }?.let { titleLogoBitmaps.get(HomeMediaRules.canonicalTitleLogoUrl(it)) }

    @JvmStatic
    fun rememberTitleLogoBitmap(url: String, bitmap: Bitmap) {
        if (url.isNotBlank() && !bitmap.isRecycled)
            titleLogoBitmaps.put(HomeMediaRules.canonicalTitleLogoUrl(url), bitmap)
    }

    /** Share the prepared graphic across Home, IPTV and the player, with synchronous cache hits. */
    @JvmStatic
    fun loadTitleLogoBitmap(context: Context, url: String, callback: SvgBitmapCallback) {
        val key = HomeMediaRules.canonicalTitleLogoUrl(url)
        cachedTitleLogoBitmap(key)?.let { callback.onLoaded(it); return }
        if (key.isBlank()) { callback.onLoaded(null); return }
        synchronized(titleLogoRequests) {
            titleLogoRequests[key]?.let { it.add(callback); return }
            titleLogoRequests[key] = mutableListOf(callback)
        }
        fun complete(bitmap: Bitmap?) {
            if (bitmap != null) rememberTitleLogoBitmap(key, bitmap)
            val callbacks = synchronized(titleLogoRequests) { titleLogoRequests.remove(key).orEmpty().toList() }
            main.post { callbacks.forEach { it.onLoaded(bitmap) } }
        }
        loadSvgBitmap(context.applicationContext, key, 1024) { bitmap ->
            if (bitmap == null) complete(null)
            else titleLogoWork.execute {
                complete(runCatching { MainActivity.prepareFolderTitleLogoBitmap(bitmap) }.getOrNull())
            }
        }
    }

    @JvmStatic
    fun cachedTitleLogo(context: Context, mediaType: String?, tmdbId: Int): String {
        val key = HomeMediaRules.titleLogoCacheKey(mediaType, tmdbId)
        if (key.isBlank()) return ""
        val prefs = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
        // A policy update must not discard a graphic already chosen for this identity.
        for (version in 1..HomeMediaRules.TITLE_LOGO_POLICY_VERSION) {
            val savedKey = key.replace("_v${HomeMediaRules.TITLE_LOGO_POLICY_VERSION}_", "_v${version}_")
            val logo = prefs.getString(savedKey, "").orEmpty()
            if (logo.isNotBlank()) return HomeMediaRules.canonicalTitleLogoUrl(logo)
        }
        return ""
    }

    @JvmStatic
    @Synchronized
    fun rememberTitleLogo(context: Context, mediaType: String?, tmdbId: Int, url: String?) {
        val key = HomeMediaRules.titleLogoCacheKey(mediaType, tmdbId)
        if (key.isBlank() || url.isNullOrBlank()) return
        val logo = HomeMediaRules.canonicalTitleLogoUrl(url)
        val prefs = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
        if (cachedTitleLogo(context, mediaType, tmdbId).isBlank()) prefs.edit().putString(key, logo).apply()
    }

    fun interface SvgBitmapCallback {
        fun onLoaded(bitmap: Bitmap?)
    }

    @JvmStatic
    fun loadSvgBitmap(context: Context, url: String, maxDimension: Int, callback: SvgBitmapCallback) {
        val request = ImageRequest.Builder(context)
            .data(HomeMediaRules.canonicalTitleLogoUrl(url))
            // Raster images also pass through the software Canvas in rasterize.
            .allowHardware(false)
            .target(object : Target {
                override fun onSuccess(result: Image) {
                    callback.onLoaded(rasterize(result, maxDimension))
                }

                override fun onError(error: Image?) {
                    callback.onLoaded(null)
                }
            })
            .build()
        SingletonImageLoader.get(context).enqueue(request)
    }

    private fun rasterize(image: Image, maxDimension: Int): Bitmap? {
        val limit = maxDimension.coerceAtLeast(1)
        val sourceWidth = image.width.takeIf { it > 0 } ?: limit
        val sourceHeight = image.height.takeIf { it > 0 } ?: limit
        val scale = minOf(1f, limit / maxOf(sourceWidth, sourceHeight).toFloat())
        val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(width / sourceWidth.toFloat(), height / sourceHeight.toFloat())
            image.draw(canvas)
        } catch (_: Exception) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    @JvmStatic
    fun load(imageView: ImageView, iconId: String) {
        if (!iconIdPattern.matches(iconId)) return
        val separator = iconId.indexOf(':')
        val prefix = iconId.substring(0, separator)
        val name = iconId.substring(separator + 1)
        val enqueueRequest = {
            val context = imageView.context
            val request = ImageRequest.Builder(context)
                .data("https://api.iconify.design/$prefix/$name.svg")
                .error(imageView.drawable)
                .target(object : ImageViewTarget(imageView) {
                    override fun onSuccess(result: Image) {
                        super.onSuccess(result)
                        if (iconId == "logos:youtube") {
                            imageView.setImageDrawable(youtubeBanner(result, imageView))
                        }
                    }
                })
                .build()
            SingletonImageLoader.get(context).enqueue(request)
        }
        if (imageView.isAttachedToWindow) {
            enqueueRequest()
        } else {
            imageView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    view.removeOnAttachStateChangeListener(this)
                    enqueueRequest()
                }

                override fun onViewDetachedFromWindow(view: View) = Unit
            })
        }
    }

    /** Keep the YouTube wordmark inside a white 16:9 TV banner with 15% side margins. */
    private fun youtubeBanner(image: Image, imageView: ImageView): Drawable {
        val logo = image.asDrawable(imageView.resources)
        val background = Paint().apply { color = Color.WHITE }
        val width = image.width.coerceAtLeast(1)
        val height = (width * 9f / 16f).roundToInt().coerceAtLeast(1)
        logo.setBounds(0, 0, width, image.height)
        return object : Drawable() {
            override fun getIntrinsicWidth() = width
            override fun getIntrinsicHeight() = height

            override fun draw(canvas: Canvas) {
                canvas.drawRect(bounds, background)
                val logoWidth = bounds.width() * 0.70f
                val scale = logoWidth / width
                val logoHeight = image.height * scale
                val saveCount = canvas.save()
                canvas.translate(bounds.exactCenterX() - logoWidth / 2,
                    bounds.exactCenterY() - logoHeight / 2)
                canvas.scale(scale, scale)
                logo.draw(canvas)
                canvas.restoreToCount(saveCount)
            }

            override fun setAlpha(alpha: Int) {
                background.alpha = alpha
                logo.alpha = alpha
            }

            override fun setColorFilter(colorFilter: ColorFilter?) {
                background.colorFilter = colorFilter
                logo.colorFilter = colorFilter
            }

            @Deprecated("Deprecated in Java")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
    }

}
