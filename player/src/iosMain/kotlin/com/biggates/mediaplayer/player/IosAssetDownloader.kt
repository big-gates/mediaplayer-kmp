package com.biggates.mediaplayer.player

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAssetDownloadDelegateProtocol
import platform.AVFoundation.AVAssetDownloadTask
import platform.AVFoundation.AVAssetDownloadURLSession
import platform.AVFoundation.AVURLAsset
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeRange
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSString
import platform.Foundation.NSTimer
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.NSUserDefaults
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.stringWithString
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS 전용 HLS 다운로드 관리 싱글톤.
 *
 * - 백그라운드 세션 구성으로 AVAssetDownloadURLSession을 생성한다.
 * - 작업 수명과 콜백 처리를 위해 델리게이트(AVAssetDownloadDelegateProtocol, NSURLSessionTaskDelegateProtocol)를 구현한다.
 * - 항목 식별자별(미디어 항목의 identifier)로 작업과 콜백을 매핑한다.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosAssetDownloader {

    private const val SESSION_IDENTIFIER: String = "com.biggates.mediaplayer.assetdownload.session"
    private const val USER_DEFAULTS_OFFLINE_URL_PREFIX: String = "offline-url-"

    private val userDefaults = NSUserDefaults.standardUserDefaults

    // 다운로드 세션과 델리게이트
    private val delegate = object : NSObject(), AVAssetDownloadDelegateProtocol, NSURLSessionTaskDelegateProtocol {

        // 다운로드 완료: 로컬 위치 전달
        override fun URLSession(
            session: NSURLSession,
            assetDownloadTask: AVAssetDownloadTask,
            didFinishDownloadingToURL: NSURL
        ) {
            val itemIdentifier = taskToItemIdentifierMap.remove(assetDownloadTask)
            if (itemIdentifier != null) {
                // 경로 저장
                userDefaults.setObject(didFinishDownloadingToURL.absoluteString, forKey = USER_DEFAULTS_OFFLINE_URL_PREFIX + itemIdentifier)
                // 대기 중인 완료 콜백을 성공으로 마무리
                completionContinuationMap.remove(itemIdentifier)?.resume(Unit)
            }
        }

        // 진행 업데이트(선택): 필요하면 진행 정보를 events 로 업데이트하도록 전달 가능
        override fun URLSession(
            session: NSURLSession,
            assetDownloadTask: AVAssetDownloadTask,
            didLoadTimeRange: CValue<CMTimeRange>,
            totalTimeRangesLoaded: List<*>,
            timeRangeExpectedToLoad: CValue<CMTimeRange>
        ) {
            // 전체로 기대되는 길이(초) 계산
            val expectedSeconds: Double = CMTimeGetSeconds(timeRangeExpectedToLoad)
                .let { if (it.isFinite() && it > 0.0) it else 0.0 }

            // 지금까지 내려받은 전체 구간 길이(초) 합산
            var loadedSeconds: Double = 0.0
            for (element in totalTimeRangesLoaded) {
                val range = element as? CMTimeRange ?: continue
                val part = CMTimeGetSeconds(range.duration)
                if (part.isFinite() && part > 0.0) {
                    loadedSeconds += part
                }
            }

            // 진행률(0.0..1.0) 계산
            val progressFraction: Double =
                if (expectedSeconds > 0.0) (loadedSeconds / expectedSeconds).coerceIn(0.0, 1.0) else 0.0

            // 항목 식별자를 얻어 저장(또는 이후 UI 갱신 경로로 전달)
            val itemIdentifier: String? = taskToItemIdentifierMap[assetDownloadTask]
            if (itemIdentifier != null) {
                // 필요에 따라 percent(0..100)로 변환해도 됨
                val progressPercent: Double = progressFraction * 100.0

                // 간단히 UserDefaults에 저장해 둔다. (UI에서 주기적으로 읽거나, 다른 통신 방식으로 전달 가능)
                userDefaults.setDouble(progressPercent, forKey = USER_DEFAULTS_OFFLINE_URL_PREFIX + itemIdentifier + "-progress")

                // 원한다면 로그도 남긴다.
                NSLog(
                    NSString.stringWithString("HLS download progress for %@: %.2f%%"),
                    itemIdentifier,
                    progressPercent
                )
            }
        }

        // 오류 처리
        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            didCompleteWithError: NSError?
        ) {
            if (didCompleteWithError != null) {
                val itemIdentifier = taskToItemIdentifierMap.remove(task as? AVAssetDownloadTask)
                if (itemIdentifier != null) {
                    completionContinuationMap.remove(itemIdentifier)?.resumeWithException(
                        RuntimeException(didCompleteWithError.localizedDescription)
                    )
                }
            }
        }
    }

    private val sessionConfiguration: NSURLSessionConfiguration by lazy {
        // 백그라운드 세션 구성(식별자는 앱 전체에서 유일해야 한다)
        NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(SESSION_IDENTIFIER).apply {
            allowsCellularAccess = true           // 셀룰러 다운로드 허용 여부(정책에 맞게 조정)
            waitsForConnectivity = true           // 네트워크 복구 대기
            HTTPMaximumConnectionsPerHost = 4     // 동시 연결 개수(정책에 맞게 조정)
        }
    }

    private val downloadSession: AVAssetDownloadURLSession by lazy {
        AVAssetDownloadURLSession.sessionWithConfiguration(
            configuration = sessionConfiguration,
            assetDownloadDelegate = delegate,
            delegateQueue = NSOperationQueue.mainQueue
        )
    }

    // 작업 및 완료 콜백 매핑
    private val taskToItemIdentifierMap = mutableMapOf<AVAssetDownloadTask, String>()
    private val completionContinuationMap = mutableMapOf<String, CancellableContinuation<Unit>>()

    /**
     * HLS 전체 오프라인 다운로드를 수행한다.
     *
     * @param mediaItem 대상 미디어 항목
     */
    suspend fun downloadAll(mediaItem: MediaItem) {
        val url = NSURL.URLWithString(mediaItem.url) ?: throw IllegalArgumentException("잘못된 URL")
        val asset = AVURLAsset(uRL = url, options = null)

        // 아트워크(선택): 존재한다면 다운로드하여 전달
        val artworkData: NSData? = mediaItem.artworkUrl?.let { artworkUrlString ->
            val artworkUrl = NSURL.URLWithString(artworkUrlString)
            if (artworkUrl != null) NSData.dataWithContentsOfURL(artworkUrl) else null
        }

        // 다운로드 작업 생성
        val downloadTask: AVAssetDownloadTask = downloadSession.makeAssetDownloadTaskWithURLAsset(
            URLAsset = asset,
            assetTitle = mediaItem.title ?: mediaItem.identifier,
            assetArtworkData = artworkData,
            options = null as NSDictionary?
        ) ?: throw IllegalStateException("다운로드 작업 생성 실패")

        taskToItemIdentifierMap[downloadTask] = mediaItem.identifier

        // 작업 완료를 일시 중지 없이 끝까지 기다린다.
        suspendCancellableCoroutine<Unit> { continuation ->
            completionContinuationMap[mediaItem.identifier] = continuation
            downloadTask.resume()

            continuation.invokeOnCancellation {
                // 취소된 경우 작업을 중단
                downloadTask.cancel()
                taskToItemIdentifierMap.remove(downloadTask)
                completionContinuationMap.remove(mediaItem.identifier)
            }
        }
    }

    /**
     * HLS 앞부분을 시간 예산만큼 선다운로드한다.
     * 정확한 "밀리초 단위" 세그먼트 수와 일치하지 않으므로, 주어진 시간만큼 다운로드를 진행하다가 취소한다.
     *
     * @param mediaItem 대상 미디어 항목
     * @param headMillis 앞부분 선다운로드 시간 예산(밀리초)
     */
    suspend fun downloadHeadAndCancel(mediaItem: MediaItem, headMillis: Long) {
        val url = NSURL.URLWithString(mediaItem.url) ?: throw IllegalArgumentException("잘못된 URL")
        val asset = AVURLAsset(uRL = url, options = null)

        val downloadTask: AVAssetDownloadTask = downloadSession.makeAssetDownloadTaskWithURLAsset(
            URLAsset = asset,
            assetTitle = mediaItem.title ?: mediaItem.identifier,
            assetArtworkData = null,
            options = null as NSDictionary?
        ) ?: throw IllegalStateException("다운로드 작업 생성 실패")

        taskToItemIdentifierMap[downloadTask] = mediaItem.identifier

        // 지정 시간(headMillis) 만큼만 내려받다가 취소한다.
        // 너무 큰 값이 들어오면 과도한 다운로드를 방지하기 위해 상한을 둔다(예: 최대 20초).
        val limitedMillis = headMillis.coerceIn(1000L, 20_000L)

        suspendCancellableCoroutine<Unit> { continuation ->
            // 이 프리로드는 "완료" 콜백을 기다리지 않고, 일정 시간 후 취소되는 즉시 종료한다.
            downloadTask.resume()

            // 지연 후 취소
            NSTimer.scheduledTimerWithTimeInterval(
                interval = limitedMillis.toDouble() / 1000.0,
                repeats = false,
                block = { _ ->
                    downloadTask.cancel()
                    taskToItemIdentifierMap.remove(downloadTask)
                    // 프리로드는 "성공" 개념 없이 그냥 종료시킨다.
                    continuation.resume(Unit)
                }
            )

            continuation.invokeOnCancellation {
                downloadTask.cancel()
                taskToItemIdentifierMap.remove(downloadTask)
            }
        }
    }

    /**
     * 오프라인으로 내려받아 저장된 항목의 로컬 URL 문자열을 반환한다.
     *
     * @param itemIdentifier 항목 식별자
     * @return 저장되어 있으면 로컬 URL 문자열, 없으면 null
     */
    fun findOfflineUrlString(itemIdentifier: String): String? {
        return userDefaults.stringForKey(USER_DEFAULTS_OFFLINE_URL_PREFIX + itemIdentifier)
    }

    /**
     * 오프라인으로 내려받아 저장된 항목을 삭제한다.
     *
     * @param itemIdentifier 항목 식별자
     */
    fun removeOffline(itemIdentifier: String) {
        val stored = findOfflineUrlString(itemIdentifier) ?: return
        val fileUrl = NSURL.URLWithString(stored) ?: return
        try {
            NSFileManager.defaultManager.removeItemAtURL(fileUrl, error = null)
        } catch (_: Throwable) {
            // 삭제 실패는 무시(로그 필요하면 추가)
        } finally {
            userDefaults.removeObjectForKey(USER_DEFAULTS_OFFLINE_URL_PREFIX + itemIdentifier)
        }
    }
}