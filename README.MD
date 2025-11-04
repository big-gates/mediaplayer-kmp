# MediaPlayer (Kotlin Multiplatform)

Kotlin Multiplatform 공통 코드로 **Android**와 **iOS**에서 동일한 API로 동영상을 재생합니다.  
스트리밍, 디스크 캐시, 앞부분 선로딩(프리로드), 전체 오프라인 다운로드를 지원합니다.  
UI는 공통 `PlatformMediaPlayer` 컴포저블로 플랫폼별 네이티브 플레이어를 렌더링합니다.

---

## 지원 기능

- 공통 재생 컨트롤: 준비, 재생, 일시 정지, 정지, 탐색, 배속, 볼륨
- 재생 이벤트 스트림: 상태, 위치, 길이, 버퍼, 오류, 캐시 진행 상황
- 디스크 캐시 정책: 스트리밍 캐시, 부분/전체 오프라인
- 앞부분 선로딩(프리로드) 정책 + 대기열 기반 자동 프리로드
- 전체 오프라인 다운로드 및 제거
- 공통 Compose 컴포저블을 통한 플랫폼별 비디오 렌더링

---

## 설치


---

## Android 초기화(필수)

캐시, 네트워크, 배터리 제공자들이 전역 `Context` 에 접근합니다.  
앱 시작 시 한 번 초기화하세요.

```kotlin
// app/src/main/java/.../App.kt
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PlatformMediaPlayer.init(this) 
    }
}
```

---

## 핵심 타입 요약

- `MediaItem`: 재생 항목(주소, 제목, 아트워크, 라이브 여부, MIME 타입 힌트 등)
- `MediaPlayerController`: 공통 재생 제어 API(expect/actual)
- `PlayerEvent`: 상태, 위치, 길이, 버퍼, 오류, 캐시 진행 상황
- `CachePolicy`: 디스크 캐시 정책(스트리밍 캐시 / 부분 오프라인 / 전체 오프라인)
- `PreloadPolicy` + `MediaPreloader`: 다음 항목 앞부분 선로딩
- `PlatformMediaPlayer(...)`: 공통 Compose 컴포저블(플랫폼별 뷰 렌더링)

---

## 가장 빠른 시작(공통 Compose)

```kotlin
@Composable
fun Sample() {
    val controller = remember { MediaPlayerController() }
    val item = remember {
        MediaItem(
            identifier = "sample_hls",
            url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", // HLS
            title = "Onboarding HLS"
        )
    }

    PlatformMediaPlayer(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
        controller = controller,
        mediaItem = item,
        autoPlay = true,
        loop = true,
        mute = true
    )
}
```

---

## 준비 및 재생 (세밀 제어)

```kotlin
val controller = MediaPlayerController()

// 대기열 구성
controller.setQueue(
    QueueConfig(
        items = listOf(
            MediaItem("mp4_sample", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"),
            MediaItem("hls_sample", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
        ),
        startIndex = 0
    )
)

// 캐시 정책과 함께 준비 + 재생
scope.launch {
    controller.prepare(
        item = MediaItem(
            identifier = "mp4_sample",
            url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            title = "Bunny"
        ),
        cachePolicy = CachePolicy(
            mode = CacheMode.StreamCache,            // 스트리밍 중 디스크 캐시
            diskMaxBytes = 1L * 1024 * 1024 * 1024   // 최대 1GB
        )
    )
    controller.play()
}
```

이벤트 수집으로 UI 갱신:

```kotlin
LaunchedEffect(controller) {
    controller.events.collect { event ->
        // event.state, event.positionMillis, event.durationMillis, event.bufferedMillis, event.error ...
    }
}
```

---

## 앞부분 선로딩(프리로드)

현재 항목 다음 트랙들의 **앞부분 N초**를 미리 받아 즉시 재생되도록 합니다.

```kotlin
val batteryProvider = PlatformBatteryInfoProvider()
val networkProvider = PlatformNetworkInfoProvider()
val preloader = MediaPreloader(
    controller = controller,
    batteryInfoProvider = batteryProvider,
    networkInfoProvider = networkProvider
)

preloader.configure(
    PreloadPolicy(
        aheadCountInQueue = 2,       // 다음 2개 항목
        preloadHeadMillis = 45_000,  // 각 45초 선로딩
        wifiOnly = true,             // Wi-Fi 에서만
        minBatteryPercent = 20       // 배터리 20% 이상일 때만
    )
)

// 곡 전환 시 혹은 주기적으로 호출
scope.launch {
    val currentIndex = 0 // 현재 인덱스 관리 방식에 맞게 설정
    preloader.tick(controller.queue.value, currentIndex)
}
```

> Android는 프로그레시브(MP3/MP4)의 “앞부분 바이트 선로딩”을 지원합니다.  
> HLS/DASH는 세그먼트 기반이므로 정확한 앞부분 시간만큼의 선로딩 대신 **전체 오프라인 다운로드**를 권장합니다.  
> iOS는 HLS에 대해 `AVAssetDownloadURLSession`을 사용하여 “일정 시간 다운로드 후 취소”로 프리로드합니다.

---

## 전체 오프라인 다운로드

네트워크 없이 재생할 수 있도록 **전체 저장**합니다.

```kotlin
scope.launch {
    controller.downloadOffline(
        MediaItem(
            identifier = "bb_offline",
            url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            title = "Bunny Offline"
        )
    )
}
```

오프라인 제거:

```kotlin
scope.launch {
    controller.removeOffline(itemId = "bb_offline")
}
```

캐시 현황 조회:

```kotlin
scope.launch {
    val info = controller.getCacheInfo(itemId = "bb_offline")
    // info.offlineReady, info.bytesCached, info.bytesTotal 로 UI 표시
}
```

---

## 리소스 파일(앱 번들)에 있는 동영상 재생

- **Android (raw 리소스)**
  ```kotlin
  val uri = "android.resource://${'$'}{PlatformMediaPlayer.context.packageName}/${'$'}{R.raw.test}"
  val item = MediaItem("test", uri)
  ```

- **iOS (앱 번들)**
  ```kotlin
  val urlString = NSBundle.mainBundle
      .URLForResource("test", "mp4")
      ?.absoluteString
  val item = MediaItem("test", urlString ?: error("리소스 없음"))
  ```
  
- **CommonMain (composeResources 사용)**
  ```kotlin
  val uri = Res.getUri("files/test.mp4")
  val mediaItem = remember {
      MediaItem("test", uri)
  }
  ``` 

---

## 검증용 테스트 주소

- **HLS**
    - `https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8`
    - `https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8`
- **MP4**
    - `https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4`
- **DASH (Android에서만)**
    - `https://storage.googleapis.com/wvmedia/clear/h264/tears/tears.mpd`

---

## 문제 해결

- **검은 화면**
    - Android에서 HLS 주소(`.m3u8`)를 재생하는데 `media3-exoplayer-hls` 의존성이 누락됨
    - Compose 레이아웃 크기가 0 × 0 → `aspectRatio` 또는 고정 높이 지정
    - iOS에서 뷰 프레임 갱신 누락 → `PlatformMediaPlayer` 의 `update` 경로에서 `attach` 재호출

- **`java.lang.SecurityException: missing INTERNET permission`**
    - `AndroidManifest.xml`에 `<uses-permission android:name="android.permission.INTERNET"/>` 추가

- **평문(http://) 주소 재생 불가**
    - 평문 허용 설정 추가 또는 `https://` 주소 사용

- **iOS에서 DASH 재생 불가**
    - 기본 `AVPlayer`는 DASH를 직접 재생하지 않습니다. HLS 또는 MP4로 테스트하세요.

---

## 권장 사용 패턴

1. **오프라인 우선 재생**:  
   `getCacheInfo(itemId)` 로 오프라인 유무 확인 → 있으면 오프라인 재생, 없으면 스트리밍으로 준비
2. **스트리밍 캐시 사용**:  
   `CachePolicy(mode = CacheMode.StreamCache)` 로 재생 중 데이터 자동 캐시
3. **다음 항목 프리로드**:  
   `MediaPreloader` 로 다음 항목 앞부분 선로딩
4. **백그라운드 오프라인화**:  
   사용 패턴에 맞춰 자주 재생하는 항목을 `downloadOffline` 로 저장

### 예시 코드 (공통 Compose + 코루틴)

#### 0) 준비: 컨트롤러/정책/항목 정의

```kotlin
val controller = remember { MediaPlayerController() }
val battery = remember { PlatformBatteryInfoProvider() }
val network = remember { PlatformNetworkInfoProvider() }
val preloader = remember { MediaPreloader(controller, battery, network) }

val mediaItems = remember {
    listOf(
        MediaItem(
            identifier = "intro_hls",
            url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            title = "Intro (HLS)",
            mimeType = "application/x-mpegURL"
        ),
        MediaItem(
            identifier = "bunny_mp4",
            url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            title = "Bunny (MP4)"
        )
    )
}
```

#### 1) 오프라인 우선 재생

```kotlin
LaunchedEffect(Unit) {
    val first = mediaItems.first()
    // 1) 캐시/오프라인 상태 조회
    val info = controller.getCacheInfo(first.identifier)

    // 2) 오프라인이 준비되어 있다면(프로젝트 정책에 맞게 판단) → 오프라인 URL 사용
    //    샘플 구현은 bytesTotal이 없어 단순히 캐시 유무만 참고한다고 가정
    val preparedItem = first

    // 3) 준비(스트리밍 캐시 모드)
    controller.prepare(
        item = preparedItem,
        cachePolicy = CachePolicy(
            mode = CacheMode.StreamCache,
            diskMaxBytes = 1L * 1024 * 1024 * 1024
        )
    )
}
```

`PlatformMediaPlayer` 로 실제 재생 뷰 렌더링:

```kotlin
PlatformMediaPlayer(
    modifier = Modifier.fillMaxWidth().aspectRatio(16f/9f),
    controller = controller,
    mediaItem = mediaItems.first(),
    autoPlay = true,
    loop = true,
    mute = false
)
```

#### 2) 스트리밍 캐시 사용

```kotlin
LaunchedEffect(Unit) {
    val mp4 = mediaItems.last()
    controller.prepare(
        item = mp4,
        cachePolicy = CachePolicy(
            mode = CacheMode.StreamCache, // ✅ 스트리밍 중 자동 디스크 캐시
            diskMaxBytes = 512L * 1024 * 1024
        )
    )
    controller.play()
}
```

#### 3) 다음 항목 프리로드(앞부분 선로딩)

```kotlin
// 대기열 세팅
LaunchedEffect(Unit) {
    controller.setQueue(QueueConfig(items = mediaItems, startIndex = 0))
}

// 프리로드 정책 구성 (다음 1개, 각 30초, Wi-Fi 전용)
LaunchedEffect(Unit) {
    preloader.configure(
        PreloadPolicy(
            aheadCountInQueue = 1,
            preloadHeadMillis = 30_000,
            wifiOnly = true,
            minBatteryPercent = 20
        )
    )
}

// 현재 인덱스 0이라고 가정하고 tick 호출 (곡 전환 시마다 다시 호출 권장)
LaunchedEffect(mediaItems) {
    preloader.tick(queue = mediaItems, currentIndex = 0)
}
```

#### 4) 백그라운드 오프라인화 (전체 다운로드/제거)

```kotlin
// 전체 오프라인 다운로드 (사용 빈도 높은 항목)
LaunchedEffect(Unit) {
    val item = mediaItems.last() // Big Buck Bunny (MP4)
    controller.downloadOffline(item)
}

// 나중에 제거할 때
LaunchedEffect(Unit) {
    controller.removeOffline(itemId = "bunny_mp4")
}
```

#### 5) 이벤트 수집으로 UI 갱신

```kotlin
val event by controller.events.collectAsState(PlayerEvent(PlaybackState.Idle,0,0,0))
Text("state=${'$'}{event.state} pos=${'$'}{event.positionMillis} / ${'$'}{event.durationMillis}")
```

---

## 라이선스

