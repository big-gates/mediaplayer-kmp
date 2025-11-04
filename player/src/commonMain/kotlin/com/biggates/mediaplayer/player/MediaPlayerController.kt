package com.biggates.mediaplayer.player

import kotlinx.coroutines.flow.StateFlow

/**
 * 재생할 개별 미디어 정보입니다.
 * URL 은 HLS(m3u8), MP3/MP4 등 스트리밍 또는 파일 경로(file://)를 모두 지원합니다.
 *
 * @property identifier 외부에서 항목을 식별하기 위한 고유 식별자
 * @property url 재생할 미디어의 절대 경로 또는 URL
 * @property title UI 표현용 제목(선택)
 * @property artist UI 표현용 아티스트(선택)
 * @property artworkUrl UI 표현용 아트워크 URL(선택)
 * @property mimeType MIME 타입 힌트(예: "application/x-mpegURL", 선택)
 * @property isLive 라이브 스트림 여부(HLS 라이브 등). 라이브는 오프라인/선로딩을 비활성화합니다.
 */
data class MediaItem(
    val identifier: String,
    val url: String,
    val title: String? = null,
    val artist: String? = null,
    val artworkUrl: String? = null,
    val mimeType: String? = null,
    val isLive: Boolean = false,
)

/** 재생 상태를 나타냅니다. */
enum class PlaybackState { Idle, Buffering, Playing, Paused, Ended, Error }

/**
 * 플레이어에서 발생하는 단일 이벤트 스냅샷입니다.
 * UI 는 이 흐름을 수집하여 상태를 갱신합니다.
 *
 * @property state 현재 재생 상태
 * @property positionMillis 현재 재생 위치(밀리초)
 * @property durationMillis 전체 길이(밀리초). 알 수 없으면 0
 * @property bufferedMillis 버퍼링된 위치(밀리초)
 * @property speed 재생 속도(배속)
 * @property error 오류가 있으면 포함
 * @property cacheBytesCached (선택) 캐시에 적재된 바이트 수
 * @property cacheBytesTotal (선택) 캐시에 목표로 하는 총 바이트 수
 */
data class PlayerEvent(
    val state: PlaybackState,
    val positionMillis: Long,
    val durationMillis: Long,
    val bufferedMillis: Long,
    val speed: Float = 1f,
    val error: Throwable? = null,
    val cacheBytesCached: Long? = null,
    val cacheBytesTotal: Long? = null,
)


/** 캐시 동작 모드입니다. */
enum class CacheMode {
    /** 캐시를 사용하지 않습니다. */
    None,
    /** 스트리밍 중 세그먼트를 LRU 캐시에 저장합니다. */
    StreamCache,
    /** 앞부분만 부분적으로 다운로드하여 오프라인 재생 가능하도록 합니다. */
    PartialOffline,
    /** 전체를 다운로드하여 완전한 오프라인 재생이 가능하도록 합니다. */
    FullOffline
}

/**
 * 디스크 캐시 정책입니다.
 * Android 는 Media3(SimpleCache), iOS 는 플랫폼별 파일/보안 컨테이너를 사용합니다.
 *
 * @property mode 캐시 동작 모드
 * @property diskMaxBytes 캐시 디렉터리의 최대 바이트 수
 * @property reservedBytesForOffline 오프라인 전용으로 남겨둘 최소 바이트 수
 * @property evictOldestFirst 용량 초과 시 가장 오래된 항목부터 제거할지 여부
 */
data class CachePolicy(
    val mode: CacheMode = CacheMode.StreamCache,
    val diskMaxBytes: Long = 512L * 1024 * 1024,
    val reservedBytesForOffline: Long = 256L * 1024 * 1024,
    val evictOldestFirst: Boolean = true,
)


/**
 * 프리로드(선로딩) 정책입니다.
 * 현재 곡 기준 다음 몇 곡, 각 곡의 앞부분 몇 밀리초를 미리 캐시에 적재할지를 정의합니다.
 *
 * @property aheadCountInQueue 현재 재생 항목 다음으로 몇 개를 미리 준비할지
 * @property preloadHeadMillis 각 항목에서 앞부분을 몇 밀리초 프리로드할지
 * @property wifiOnly Wi‑Fi 연결에서만 프리로드할지
 * @property minBatteryPercent 배터리 잔량이 이 비율(%) 이상일 때만 프리로드할지
 */
data class PreloadPolicy(
    val aheadCountInQueue: Int = 1,
    val preloadHeadMillis: Long = 30_000,
    val wifiOnly: Boolean = false,
    val minBatteryPercent: Int = 10,
)


/**
 * 재생 대기열 구성입니다.
 *
 * @property items 재생할 항목 리스트
 * @property startIndex 시작 인덱스
 */
data class QueueConfig(
    val items: List<MediaItem>,
    val startIndex: Int = 0,
)

/** 대기열 제어용 공통 인터페이스입니다. */
interface MediaQueueController {
    /** 현재 대기열 스트림 */
    val queue: StateFlow<List<MediaItem>>


    /**
     * 대기열을 설정합니다.
     * @param config 대기열 구성
     */
    fun setQueue(config: QueueConfig)


    /** 다음 항목으로 이동합니다. */
    fun next()


    /** 이전 항목으로 이동합니다. */
    fun previous()


    /**
     * 현재 항목을 반환합니다.
     * @return 현재 항목 또는 null
     */
    fun current(): MediaItem?
}


/**
 * 특정 항목의 캐시 현황입니다.
 *
 * @property itemId 대상 항목 식별자
 * @property bytesCached 캐시에 누적된 바이트 수
 * @property bytesTotal 전체 바이트 수(알 수 없으면 null)
 * @property offlineReady 완전 오프라인 재생 가능 여부
 */
data class CacheInfo(
    val itemId: String,
    val bytesCached: Long,
    val bytesTotal: Long?,
    val offlineReady: Boolean,
)


/**
 * 공통에서 사용하는 플레이어 컨트롤러입니다.
 * 플랫폼별로 실제 구현이 제공되며, 공통 코드는 이 API 만 의존합니다.
 */
expect class MediaPlayerController() : MediaQueueController {
    override val queue: StateFlow<List<MediaItem>>
    override fun setQueue(config: QueueConfig)
    override fun next()
    override fun previous()
    override fun current(): MediaItem?
    /** 재생 이벤트 스트림 */
    val events: StateFlow<PlayerEvent>

    /**
     * 항목을 준비합니다. 정책에 따라 캐시/데이터 소스가 구성됩니다.
     * @param item 준비할 미디어 항목
     * @param cachePolicy 적용할 캐시 정책(기본값: [CachePolicy])
     */
    suspend fun prepare(item: MediaItem, cachePolicy: CachePolicy = CachePolicy())

    /** 재생 시작 또는 재개합니다. */
    fun play()

    /** 일시 정지합니다. */
    fun pause()

    /**
     * 재생을 정지합니다.
     * @param release true 이면 내부 자원을 함께 해제합니다.
     */
    fun stop(release: Boolean = false)

    /**
     * 지정 위치로 탐색합니다.
     * @param positionMillis 이동할 위치(밀리초)
     */
    fun seekTo(positionMillis: Long)

    /**
     * 재생 속도를 설정합니다.
     * @param speed 배속 값(예: 1.0f)
     */
    fun setSpeed(speed: Float)

    /**
     * 볼륨을 설정합니다.
     * @param volume 0.0..1.0 범위의 값
     */
    fun setVolume(volume: Float)

    /**
     * 현재 재생 중인지 여부를 반환합니다.
     * @return 재생 중이면 true
     */
    fun isPlaying(): Boolean

    /**
     * 현재 재생 위치를 반환합니다.
     * @return 밀리초 단위의 위치
     */
    fun currentPositionMillis(): Long

    /**
     * 전체 길이를 반환합니다.
     * @return 밀리초 단위의 길이(알 수 없으면 0)
     */
    fun durationMillis(): Long

    // ---- 프리로드 & 오프라인 ----

    /**
     * 프리로드 정책을 설정합니다.
     * @param policy 적용할 프리로드 정책
     */
    fun configurePreloader(policy: PreloadPolicy)

    /**
     * 지정 항목의 앞부분을 선로딩합니다.
     * @param item 대상 항목
     * @param headMillis 앞부분 프리로드 목표 시간(밀리초)
     */
    suspend fun preload(item: MediaItem, headMillis: Long)

    /**
     * 지정 항목 전체를 오프라인으로 다운로드합니다.
     * @param item 대상 항목
     */
    suspend fun downloadOffline(item: MediaItem)

    /**
     * 지정 항목의 오프라인/캐시 데이터를 제거합니다.
     * @param itemId 제거할 항목 식별자
     */
    suspend fun removeOffline(itemId: String)

    /**
     * 지정 항목의 캐시 현황을 조회합니다.
     * @param itemId 대상 항목 식별자
     * @return 캐시 정보
     */
    suspend fun getCacheInfo(itemId: String): CacheInfo
}

/** 배터리 정보를 제공하는 기대 선언입니다. */
interface BatteryInfoProvider {
    /**
     * 현재 배터리 잔량을 퍼센트(0..100)로 반환합니다.
     * @return 배터리 잔량 백분율
     */
    suspend fun batteryPercentage(): Int
}


/** 네트워크 정보를 제공하는 기대 선언입니다. */
interface NetworkInfoProvider {
    /**
     * 현재 Wi‑Fi 연결 여부를 반환합니다.
     * @return Wi‑Fi 연결되어 있으면 true
     */
    suspend fun isWifiConnected(): Boolean
}

/** Android/iOS 에서 실제 구현을 제공합니다. */
expect class PlatformBatteryInfoProvider() : BatteryInfoProvider {
    override suspend fun batteryPercentage(): Int
}

expect class PlatformNetworkInfoProvider() : NetworkInfoProvider {
    override suspend fun isWifiConnected(): Boolean
}

/**
 * 대기열 기반 프리로더입니다.
 * 네트워크/배터리 정책을 만족할 때, 현재 항목 다음 항목들을 앞부분 프리로드합니다.
 *
 * @property controller 실제 재생을 수행하는 컨트롤러
 * @property batteryInfoProvider 배터리 정보 제공자
 * @property networkInfoProvider 네트워크 정보 제공자
 */
class MediaPreloader(
    private val controller: MediaPlayerController,
    private val batteryInfoProvider: BatteryInfoProvider,
    private val networkInfoProvider: NetworkInfoProvider,
) {
    private var policy: PreloadPolicy = PreloadPolicy()

    /**
     * 프리로드 정책을 갱신합니다.
     * @param policy 적용할 프리로드 정책
     */
    fun configure(policy: PreloadPolicy) { this.policy = policy }

    /**
     * 현재 인덱스를 기준으로 다음 항목들을 정책에 맞춰 프리로드합니다.
     * UI 의 곡 전환 시점 혹은 주기적인 타이머에서 호출하세요.
     *
     * @param queue 현재 대기열
     * @param currentIndex 현재 재생 인덱스
     */
    suspend fun tick(queue: List<MediaItem>, currentIndex: Int) {
        if (policy.wifiOnly && !networkInfoProvider.isWifiConnected()) return
        if (batteryInfoProvider.batteryPercentage() < policy.minBatteryPercent) return
        val endIndex = (currentIndex + policy.aheadCountInQueue).coerceAtMost(queue.lastIndex)
        for (index in (currentIndex + 1)..endIndex) {
            val item = queue[index]
            if (!item.isLive) controller.preload(item, policy.preloadHeadMillis)
        }
    }
}