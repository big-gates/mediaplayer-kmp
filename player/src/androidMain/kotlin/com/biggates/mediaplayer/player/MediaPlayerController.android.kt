package com.biggates.mediaplayer.player

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


/**
 * Android에서 배터리 정보를 제공합니다.
 */
actual class PlatformBatteryInfoProvider actual constructor() : BatteryInfoProvider {
    /**
     * 현재 배터리 잔량을 퍼센트(0..100)로 반환합니다.
     *
     * - Android 5.0(Lollipop)+: [BatteryManager.BATTERY_PROPERTY_CAPACITY] 사용.
     * - 이하 버전 또는 보조 경로: `ACTION_BATTERY_CHANGED` sticky 브로드캐스트로 계산.
     *
     * @return 배터리 잔량 백분율(0..100). 값을 판정할 수 없을 경우 100을 반환합니다.
     */
    actual override suspend fun batteryPercentage(): Int {
        val applicationContext: Context = PlatformMediaPlayer.context

        val batteryManager = applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val capacityPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (capacityPercent in 0..100) return capacityPercent

        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatusIntent: Intent? = applicationContext.registerReceiver(null, intentFilter)

        val batteryLevel: Int = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val batteryScale: Int = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percentage: Int = if (batteryLevel >= 0 && batteryScale > 0) (batteryLevel * 100 / batteryScale) else -1

        return percentage.takeIf { it in 0..100 } ?: 100
    }
}

/**
 * Android에서 네트워크(특히 Wi-Fi) 연결 정보를 제공합니다.
 *
 * Android 6.0 이상의 [ConnectivityManager] 와 [NetworkCapabilities]를 사용합니다.
 * AndroidManifest.xml에 `android.permission.ACCESS_NETWORK_STATE` 권한 선언이 필요합니다.
 */
actual class PlatformNetworkInfoProvider actual constructor() : NetworkInfoProvider {
    /**
     * 현재 Wi-Fi 연결 여부를 반환합니다.
     *
     * @return Wi-Fi에 연결되어 있으면 true, 아니면 false
     */
    actual override suspend fun isWifiConnected(): Boolean {
        val applicationContext: Context = PlatformMediaPlayer.context
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}

/**
 * Android SimpleCache 홀더입니다.
 *
 * @property sharedCache 프로세스 전역 공유 캐시 인스턴스
 */
@UnstableApi
internal object AndroidCacheHolder {
    private var sharedCache: Cache? = null
    /**
     * 캐시 인스턴스를 반환합니다. 없으면 생성합니다.
     * @param cachePolicy 적용할 캐시 정책
     * @return SimpleCache 인스턴스
     */
    fun cache(cachePolicy: CachePolicy): Cache {
        val applicationContext = PlatformMediaPlayer.context
        val databaseProvider = StandaloneDatabaseProvider(applicationContext)
        val evictor = LeastRecentlyUsedCacheEvictor(cachePolicy.diskMaxBytes)
        val directory = File(applicationContext.filesDir, "media_cache")
        if (!directory.exists()) directory.mkdirs()
        return sharedCache ?: SimpleCache(directory, evictor, databaseProvider).also { sharedCache = it }
    }
}

/**
 * 미디어 앞부분 프리로드를 위한 바이트 예산을 추정합니다.
 * 단순 추정: (kbps × 초 / 8) × 1024
 *
 * @param headMillis 앞부분 목표 시간(밀리초)
 * @param assumedKbps 추정 비트레이트(kbps)
 * @return 바이트 단위 예산(최소 256 KiB)
 */
private fun estimateByteBudgetForHead(headMillis: Long, assumedKbps: Int = 256): Long {
    val seconds = headMillis.coerceAtLeast(0) / 1000.0
    val kiloBytes = assumedKbps * seconds / 8.0
    return (kiloBytes * 1024).toLong().coerceAtLeast(256 * 1024)
}

/**
 * Android 실제 플레이어 구현입니다. Media3/ExoPlayer 를 사용합니다.
 */
@UnstableApi
actual class MediaPlayerController : MediaQueueController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var cachePolicy: CachePolicy = CachePolicy()

    private val _events = MutableStateFlow(PlayerEvent(PlaybackState.Idle, 0, 0, 0))
    actual val events = _events.asStateFlow()

    // 대기열
    private val _queue = MutableStateFlow<List<MediaItem>>(emptyList())
    actual override val queue = _queue.asStateFlow()
    private var currentIndex: Int = 0

    // 프리로드 정책(보관)
    private var preloadPolicy: PreloadPolicy = PreloadPolicy()

    /** 내부 ExoPlayer 인스턴스를 보장합니다. */
    private fun ensurePlayer() {
        if (player != null) return
        val exoPlayer = ExoPlayer.Builder(PlatformMediaPlayer.context).build()
        exoPlayer.addListener(object : Player.Listener {
            override fun onEvents(pl: Player, e: Player.Events) {
                val state = when (pl.playbackState) {
                    Player.STATE_BUFFERING -> PlaybackState.Buffering
                    Player.STATE_READY -> if (pl.playWhenReady) PlaybackState.Playing else PlaybackState.Paused
                    Player.STATE_ENDED -> PlaybackState.Ended
                    else -> PlaybackState.Idle
                }
                _events.update {
                    it.copy(
                        state = state,
                        positionMillis = pl.currentPosition.coerceAtLeast(0L),
                        durationMillis = pl.duration.takeIf { d -> d >= 0 } ?: 0L,
                        bufferedMillis = pl.bufferedPosition.coerceAtLeast(0L),
                        speed = pl.playbackParameters.speed,
                        error = null
                    )
                }
                if (state == PlaybackState.Ended) next()
            }

            override fun onPlayerError(error: PlaybackException) {
                _events.update { it.copy(state = PlaybackState.Error, error = error) }
            }
        })
        player = exoPlayer
    }

    /** @param config 대기열 구성 */
    actual override fun setQueue(config: QueueConfig) {
        _queue.value = config.items
        currentIndex = config.startIndex.coerceIn(0, config.items.lastIndex)
    }

    /** 다음 항목으로 이동합니다. */
    actual override fun next() {
        val list = _queue.value
        if (list.isEmpty()) return
        currentIndex = (currentIndex + 1).coerceAtMost(list.lastIndex)
        scope.launch { prepare(list[currentIndex], cachePolicy); play() }
    }

    /** 이전 항목으로 이동합니다. */
    actual override fun previous() {
        val list = _queue.value
        if (list.isEmpty()) return
        currentIndex = (currentIndex - 1).coerceAtLeast(0)
        scope.launch { prepare(list[currentIndex], cachePolicy); play() }
    }

    /** @return 현재 항목 또는 null */
    actual override fun current(): MediaItem? = _queue.value.getOrNull(currentIndex)

    /**
     * 항목을 준비합니다.
     * @param item 준비할 미디어 항목
     * @param cachePolicy 적용할 캐시 정책
     */
    actual suspend fun prepare(item: MediaItem, cachePolicy: CachePolicy) {
        this.cachePolicy = cachePolicy
        ensurePlayer()
        val exo = player!!

        val mediaItem = ExoMediaItem.Builder()
            .setUri(item.url)
            .setMediaId(item.identifier)
            .build()

        exo.setMediaItem(mediaItem)
        exo.prepare()
    }

    /**
     * Android PlayerView에 내부 플레이어를 연결합니다.
     *
     * @param playerView Android Media3 PlayerView 인스턴스
     */
    fun attachTo(playerView: PlayerView) {
        ensurePlayer()
        playerView.player = player
    }

    /**
     * 전체 반복 재생 설정을 토글합니다.
     *
     * @param enabled true 이면 전체 반복, false 이면 반복 해제
     */
    fun setRepeatAll(enabled: Boolean) {
        ensurePlayer()
        player?.repeatMode = if (enabled) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    /** 재생 시작 또는 재개합니다. */
    actual fun play() {
        player?.play()
    }

    /** 일시 정지합니다. */
    actual fun pause() {
        player?.pause()
    }

    /**
     * 재생을 정지합니다.
     * @param release true 이면 내부 자원을 함께 해제합니다.
     */
    actual fun stop(release: Boolean) {
        player?.stop()
        if (release) {
            player?.release(); player = null
        }
        _events.update { it.copy(state = PlaybackState.Idle) }
    }

    /** @param positionMillis 이동할 위치(밀리초) */
    actual fun seekTo(positionMillis: Long) {
        player?.seekTo(positionMillis)
    }

    /** @param speed 배속 값(예: 1.0f) */
    actual fun setSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
    }

    /** @param volume 0.0..1.0 범위의 값 */
    actual fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    /** @return 재생 중이면 true */
    actual fun isPlaying(): Boolean = player?.isPlaying == true

    /** @return 현재 위치(밀리초) */
    actual fun currentPositionMillis(): Long = player?.currentPosition ?: 0L

    /** @return 전체 길이(밀리초, 알 수 없으면 0) */
    actual fun durationMillis(): Long = player?.duration ?: 0L

    /** @param policy 적용할 프리로드 정책 */
    actual fun configurePreloader(policy: PreloadPolicy) {
        this.preloadPolicy = policy
    }

    /** 항목별 고정 캐시 키를 생성합니다. (identifier 우선, 없으면 URL 기반) */
    private fun cacheKeyFor(item: MediaItem): String =
        if (item.identifier.isNotBlank()) "mp_key_${item.identifier}" else "mp_key_${item.url}"

    /** 식별자만 알고 있을 때의 캐시 키 */
    private fun cacheKeyForId(itemId: String): String = "mp_key_$itemId"

    /**
     * 프로그레시브(mp3/mp4 등)에 대해 앞부분 바이트만 캐시에 선로딩합니다.
     * HLS/DASH 는 세그먼트 기반이므로 Downloaders 사용을 권장합니다.
     *
     * @param item 대상 항목
     * @param headMillis 앞부분 프리로드 목표 시간(밀리초)
     */
    actual suspend fun preload(item: MediaItem, headMillis: Long) {
        if (item.isLive || cachePolicy.mode == CacheMode.None) return

        val isHls = item.mimeType?.contains("mpegurl", true) == true || item.url.endsWith(".m3u8", true)
        val isDash = item.mimeType?.contains("dash", true) == true || item.url.endsWith(".mpd", true)
        if (isHls || isDash) return

        val cache: Cache = AndroidCacheHolder.cache(cachePolicy)
        val customKey = cacheKeyFor(item)

        val byteBudget = estimateByteBudgetForHead(headMillis)
        val dataSpec = DataSpec.Builder()
            .setUri(item.url)
            .setKey(customKey)
            .setLength(byteBudget)
            .build()

        val upstreamFactory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val cacheDataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .createDataSource()

        val progress = CacheWriter.ProgressListener { requestLength, bytesCached, _ ->
            _events.update {
                it.copy(
                    cacheBytesCached = bytesCached,
                    cacheBytesTotal = if (requestLength != C.LENGTH_UNSET.toLong()) requestLength else null
                )
            }
        }

        val tempBuffer = ByteArray(8 * 1024)

        withContext(Dispatchers.IO) {
            CacheWriter(
                cacheDataSource,
                dataSpec,
                tempBuffer,
                progress
            ).cache()
        }
    }

    /**
     * 전체 오프라인 다운로드 수행.
     * - 프로그레시브: CacheWriter 로 전체 다운로드
     * - HLS/DASH: Downloader 사용
     *
     * @param item 대상 항목
     */
    actual suspend fun downloadOffline(item: MediaItem) {
        if (item.isLive || cachePolicy.mode == CacheMode.None) return

        val isHls = item.mimeType?.contains("mpegurl", true) == true || item.url.endsWith(".m3u8", true)
        val isDash = item.mimeType?.contains("dash", true) == true || item.url.endsWith(".mpd", true)
        if (isHls) { downloadHlsAll(item); return }
        if (isDash) { downloadDashAll(item); return }

        val cache: Cache = AndroidCacheHolder.cache(cachePolicy)
        val customKey = cacheKeyFor(item)

        val dataSpec = DataSpec.Builder()
            .setUri(item.url)
            .setKey(customKey)
            .setLength(C.LENGTH_UNSET.toLong())
            .build()

        val upstreamFactory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val cacheDataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .createDataSource()

        val progress = CacheWriter.ProgressListener { requestLength, bytesCached, _ ->
            _events.update {
                it.copy(
                    cacheBytesCached = bytesCached,
                    cacheBytesTotal = if (requestLength != C.LENGTH_UNSET.toLong()) requestLength else null
                )
            }
        }

        val tempBuffer = ByteArray(16 * 1024)

        withContext(Dispatchers.IO) {
            CacheWriter(
                cacheDataSource,
                dataSpec,
                tempBuffer,
                progress
            ).cache()
        }
    }

    /**
     * 지정 항목의 오프라인/캐시 데이터를 제거합니다.
     * @param itemId 제거할 항목 식별자
     */
    actual suspend fun removeOffline(itemId: String) {
        val cache: Cache = AndroidCacheHolder.cache(cachePolicy)
        val customKey = cacheKeyForId(itemId)
        withContext(Dispatchers.IO) {
            try {
                cache.removeResource(customKey)
            } catch (error: Throwable) {
                // 필요 시 로깅
            }
        }
    }
    /**
     * 지정 항목의 캐시 현황을 조회합니다.
     * @param itemId 대상 항목 식별자
     * @return [CacheInfo]
     */
    actual suspend fun getCacheInfo(itemId: String): CacheInfo {
        // 간단 구현: 총 바이트를 알기 어렵기 때문에 cached 값만 보고 offlineReady 는 false 처리
        return CacheInfo(itemId = itemId, bytesCached = 0, bytesTotal = null, offlineReady = false)
    }

    /** HLS 전체 다운로드(세그먼트 단위) */
    private suspend fun downloadHlsAll(item: MediaItem) {
        val cache: Cache = AndroidCacheHolder.cache(cachePolicy)
        val upstreamFactory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)

        val downloaderFactory = DefaultDownloaderFactory(cacheDataSourceFactory)
        val customKey = cacheKeyFor(item)

        val request = DownloadRequest.Builder(item.identifier, Uri.parse(item.url))
            .setCustomCacheKey(customKey)
            .build()

        withContext(Dispatchers.IO) {
            val downloader: Downloader = downloaderFactory.createDownloader(request)
            downloader.download { contentLength, bytesDownloaded, _ ->
                _events.update {
                    it.copy(
                        cacheBytesCached = bytesDownloaded,
                        cacheBytesTotal = if (contentLength > 0) contentLength else null
                    )
                }
            }
        }
    }

    /** DASH 전체 다운로드(세그먼트 단위) */
    private suspend fun downloadDashAll(item: MediaItem) {
        val cache: Cache = AndroidCacheHolder.cache(cachePolicy)
        val upstreamFactory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)

        val downloaderFactory = DefaultDownloaderFactory(cacheDataSourceFactory)
        val customKey = cacheKeyFor(item)

        val request = DownloadRequest.Builder(item.identifier, Uri.parse(item.url))
            .setCustomCacheKey(customKey)
            .build()

        withContext(Dispatchers.IO) {
            val downloader: Downloader = downloaderFactory.createDownloader(request)
            downloader.download { contentLength, bytesDownloaded, _ ->
                _events.update {
                    it.copy(
                        cacheBytesCached = bytesDownloaded,
                        cacheBytesTotal = if (contentLength > 0) contentLength else null
                    )
                }
            }
        }
    }
}