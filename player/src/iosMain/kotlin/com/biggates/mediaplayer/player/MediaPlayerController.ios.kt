package com.biggates.mediaplayer.player

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.removeTimeObserver
import platform.AVFoundation.seekToTime
import platform.AVFoundation.volume
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.QuartzCore.CALayer
import platform.SystemConfiguration.SCNetworkReachabilityCreateWithName
import platform.SystemConfiguration.SCNetworkReachabilityGetFlags
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsConnectionRequired
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsIsWWAN
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsReachable
import platform.UIKit.UIDevice
import platform.UIKit.UIView
import platform.darwin.UInt32Var
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS에서 배터리 정보를 제공합니다.
 */
actual class PlatformBatteryInfoProvider actual constructor() : BatteryInfoProvider {
    /**
     * 현재 배터리 잔량을 퍼센트(0..100)로 반환합니다.
     *
     * 내부적으로 [UIDevice]의 `batteryMonitoringEnabled`를 일시적으로 활성화하여
     * `batteryLevel`을 읽어옵니다. `batteryLevel`이 -1.0f 이면 알 수 없음으로 간주하고 100을 반환합니다.
     *
     * @return 배터리 잔량 백분율(0..100)
     */
    actual override suspend fun batteryPercentage(): Int {
        val device = UIDevice.currentDevice
        val previousMonitoringEnabled = device.batteryMonitoringEnabled

        device.batteryMonitoringEnabled = true
        val levelFraction: Float = device.batteryLevel // 0.0..1.0, 알 수 없으면 -1.0

        val percentage: Int = if (levelFraction >= 0f) {
            (levelFraction * 100f).toInt()
        } else {
            100
        }.coerceIn(0, 100)

        if (!previousMonitoringEnabled) {
            device.batteryMonitoringEnabled = false
        }
        return percentage
    }
}

/**
 * iOS에서 네트워크(특히 Wi-Fi) 연결 정보를 제공합니다.
 *
 * 간단한 구현으로 SystemConfiguration의 Reachability 플래그를 사용합니다.
 * - 도달 가능(Reachable)이고
 * - 추가 연결이 필요하지 않으며(ConnectionRequired == false)
 * - WWAN(셀룰러)이 아니면
 * Wi-Fi(또는 유선)으로 간주합니다.
 *
 * 더 정밀한 판정이 필요하면 NWPathMonitor 기반 구현으로 교체할 수 있습니다.
 */
@OptIn(ExperimentalForeignApi::class)
actual class PlatformNetworkInfoProvider actual constructor() : NetworkInfoProvider {
    /**
     * 현재 Wi-Fi(또는 비-셀룰러)로 인터넷에 도달 가능한지 여부를 반환합니다.
     *
     * @return Wi-Fi/유선 등 비-WWAN으로 도달 가능하면 true, 아니면 false
     */
    actual override suspend fun isWifiConnected(): Boolean {
        val reachabilityRef = SCNetworkReachabilityCreateWithName(null, "apple.com") ?: return false

        return memScoped {
            val flagsVar = alloc<UInt32Var>()
            val ok = SCNetworkReachabilityGetFlags(reachabilityRef, flagsVar.ptr)
            if (!ok) return@memScoped false

            val flags = flagsVar.value                              // UInt32
            val isReachable = (flags and kSCNetworkReachabilityFlagsReachable) != 0u
            val isCellular = (flags and kSCNetworkReachabilityFlagsIsWWAN) != 0u
            val isConnectionRequired = (flags and kSCNetworkReachabilityFlagsConnectionRequired) != 0u

            isReachable && !isConnectionRequired && !isCellular
        }
    }
}

/**
 * iOS 실제 플레이어 구현입니다. AVPlayer 를 사용합니다.
 */
@OptIn(ExperimentalForeignApi::class)
actual class MediaPlayerController actual constructor() : MediaQueueController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var avPlayer: AVPlayer? = null
    private var periodicObserver: Any? = null
    private var currentIndex: Int = 0
    // 프리로드 정책(보관)
    private var preloadPolicy: PreloadPolicy = PreloadPolicy()
    private var targetView: UIView? = null
    private var playerLayer: AVPlayerLayer? = null

    private val _events = MutableStateFlow(PlayerEvent(PlaybackState.Idle, 0, 0, 0))
    actual val events = _events.asStateFlow()

    private val _queue = MutableStateFlow<List<MediaItem>>(emptyList())
    actual override val queue = _queue.asStateFlow()

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
        scope.launch { prepare(list[currentIndex], CachePolicy()); play() }
    }

    /** 이전 항목으로 이동합니다. */
    actual override fun previous() {
        val list = _queue.value
        if (list.isEmpty()) return
        currentIndex = (currentIndex - 1).coerceAtLeast(0)
        scope.launch { prepare(list[currentIndex], CachePolicy()); play() }
    }

    /** @return 현재 항목 또는 null */
    actual override fun current(): MediaItem? = _queue.value.getOrNull(currentIndex)

    /**
     * 항목을 준비합니다.
     * @param item 준비할 미디어 항목
     * @param cachePolicy 적용할 캐시 정책(미사용; iOS는 스트리밍 우선)
     */
    actual suspend fun prepare(item: MediaItem, cachePolicy: CachePolicy) {
        releaseObservers()
        val nsUrl = NSURL.URLWithString(item.url) ?: error("잘못된 URL")
        val asset = AVURLAsset(uRL = nsUrl, options = null)
        val playerItem = AVPlayerItem(asset = asset)
        avPlayer = AVPlayer.playerWithPlayerItem(playerItem)

        dispatch_async(dispatch_get_main_queue()) {
            attachLayerIfPossible()
        }

        // 0.5초 주기로 위치 업데이트
        periodicObserver = avPlayer?.addPeriodicTimeObserverForInterval(
            CMTimeMake(value = 1, timescale = 2),
            queue = dispatch_get_main_queue(),
            usingBlock = { time ->
                val posSec = CMTimeGetSeconds(time).coerceAtLeast(0.0)
                val durSec = CMTimeGetSeconds(playerItem.duration).takeIf { it.isFinite() && it > 0 } ?: 0.0
                _events.update {
                    it.copy(
                        positionMillis = (posSec * 1000).toLong(),
                        durationMillis = (durSec * 1000).toLong(),
                        state = if ((avPlayer?.rate ?: 0f) > 0f) PlaybackState.Playing else PlaybackState.Paused
                    )
                }
            }
        )

        // 항목 종료 알림
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = playerItem,
            queue = NSOperationQueue.mainQueue,
            usingBlock = { _ ->
                _events.update { it.copy(state = PlaybackState.Ended) }
                next()
            }
        )
    }

    /** 재생 시작 또는 재개합니다. */
    actual fun play() { avPlayer?.play(); _events.update { it.copy(state = PlaybackState.Playing) } }
    /** 일시 정지합니다. */
    actual fun pause() { avPlayer?.pause(); _events.update { it.copy(state = PlaybackState.Paused) } }

    /**
     * 재생을 정지합니다.
     * @param release true 이면 내부 자원을 함께 해제합니다.
     */
    actual fun stop(release: Boolean) {
        avPlayer?.pause()
        if (release) { releaseObservers(); avPlayer = null }
        _events.update { it.copy(state = PlaybackState.Idle) }
    }

    /**
     * iOS UIView 에 AVPlayerLayer 를 연결합니다.
     *
     * @param containerView 비디오를 표시할 컨테이너 뷰
     */
    fun attachTo(view: UIView) {
        targetView = view
        // 이미 레이어가 있으면 프레임만 갱신
        playerLayer?.let { layer ->
            layer.frame = view.bounds
            if (layer.superlayer !== view.layer) {
                (view.layer.sublayers as? List<CALayer>)?.forEach { it.removeFromSuperlayer() }
                view.layer.addSublayer(layer)
            }
        }
        // 아직 레이어가 없고 avPlayer가 있으면 생성해서 붙이기
        if (playerLayer == null && avPlayer != null) {
            attachLayerIfPossible()
        }
    }

    /** avPlayer 생성/교체 직후 호출해서 레이어를 붙여준다. */
    private fun attachLayerIfPossible() {
        val view = targetView ?: return
        val player = avPlayer ?: return

        // 기존 서브레이어 정리
        (view.layer.sublayers as? List<CALayer>)?.forEach { it.removeFromSuperlayer() }

        val layer = AVPlayerLayer.playerLayerWithPlayer(player)
        layer.videoGravity = AVLayerVideoGravityResizeAspect
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        playerLayer = layer
    }

    fun onLayoutChanged() {
        targetView?.let { v ->
            playerLayer?.frame = v.bounds
        }
    }

    /** @param positionMillis 이동할 위치(밀리초) */
    actual fun seekTo(positionMillis: Long) {
        val sec = positionMillis.toDouble() / 1000.0
        avPlayer?.seekToTime(CMTimeMakeWithSeconds(sec, 600))
    }

    /** @param speed 배속 값 */
    actual fun setSpeed(speed: Float) { avPlayer?.rate = speed; _events.update { it.copy(speed = speed) } }

    /** @param volume 0.0..1.0 범위의 값 */
    actual fun setVolume(volume: Float) { avPlayer?.volume = volume.coerceIn(0f, 1f) }

    /** @return 재생 중이면 true */
    actual fun isPlaying(): Boolean = (avPlayer?.rate ?: 0f) > 0f
    /** @return 현재 위치(밀리초) */
    actual fun currentPositionMillis(): Long = avPlayer?.currentTime()?.let { (CMTimeGetSeconds(it) * 1000).toLong() } ?: 0L
    /** @return 전체 길이(밀리초, 알 수 없으면 0) */
    actual fun durationMillis(): Long = avPlayer?.currentItem?.duration?.let { (CMTimeGetSeconds(it) * 1000).toLong() } ?: 0L

    /** @param policy 적용할 프리로드 정책 */
    actual fun configurePreloader(policy: PreloadPolicy) {
        // 현재 구현에서는 정책 값을 단순 저장만 한다.
        // 필요 시 AVURLAssetResourceLoaderDelegate 등을 이용해 더 정교한 제어를 추가할 수 있다.
        // (여기서는 컨트롤러 내부 상태로만 보관)
        this.preloadPolicy = policy
    }

    /**
     * iOS 프리로드: HLS 스트림의 앞부분을 시간 예산만큼 선다운로드 후 즉시 취소한다.
     *
     * @param item 대상 항목
     * @param headMillis 앞부분 선다운로드 시간 예산(밀리초)
     */
    actual suspend fun preload(item: MediaItem, headMillis: Long) {
        // 라이브 스트림은 건너뛴다.
        if (item.isLive) return

        // HLS(m3u8)만 AVAssetDownloadURLSession 기반 프리로드를 지원한다.
        val isHls = item.mimeType?.contains("mpegurl", true) == true || item.url.endsWith(".m3u8", true)
        if (!isHls) return

        IosAssetDownloader.downloadHeadAndCancel(item, headMillis)
    }

    /**
     * iOS 전체 오프라인 다운로드: HLS 스트림 전체를 내려받는다.
     *
     * @param item 대상 항목
     */
    actual suspend fun downloadOffline(item: MediaItem) {
        if (item.isLive) return

        val isHls = item.mimeType?.contains("mpegurl", true) == true || item.url.endsWith(".m3u8", true)
        if (!isHls) return

        IosAssetDownloader.downloadAll(item)
    }

    /**
     * @param itemId 제거할 항목 식별자
     */
    actual suspend fun removeOffline(itemId: String) {
        IosAssetDownloader.removeOffline(itemId)
    }

    /** @return 캐시 정보(샘플: 미구현) */
    actual suspend fun getCacheInfo(itemId: String): CacheInfo =
        CacheInfo(itemId = itemId, bytesCached = 0, bytesTotal = null, offlineReady = false)

    /** 내부 옵저버 해제 */
    private fun releaseObservers() {
        periodicObserver?.let { obs -> avPlayer?.removeTimeObserver(obs); periodicObserver = null }
    }
}