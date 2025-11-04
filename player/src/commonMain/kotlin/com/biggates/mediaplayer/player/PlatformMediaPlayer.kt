package com.biggates.mediaplayer.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

expect class PlatformContext

object PlatformMediaPlayer {
    lateinit var context: PlatformContext

    fun init(context: PlatformContext){
        this.context = context
    }
}

/**
 * 공통 동영상 플레이어 컴포저블입니다.
 *
 * 이 함수는 각 플랫폼(Android, iOS)에서 실제 렌더링을 담당하는 `actual` 구현으로 연결됩니다.
 * 공통 계층에서는 재생 준비, 자동 재생, 반복 재생(단일 항목일 때), 음소거와 같은 동작을 오케스트레이션합니다.
 *
 * @param modifier 컴포즈 수정자
 * @param controller 공통 미디어 플레이어 컨트롤러
 * @param mediaItem 재생할 미디어 항목
 * @param autoPlay 자동 재생 여부
 * @param loop 단일 항목에서 반복 재생 여부(큐가 비어 있거나 단일 항목일 때만 효과적)
 * @param mute 음소거 여부
 */
@Composable
expect fun PlatformMediaPlayer(
    modifier: Modifier = Modifier,
    controller: MediaPlayerController,
    mediaItem: MediaItem,
    autoPlay: Boolean = true,
    loop: Boolean = true,
    mute: Boolean = false,
)

/**
 * 공통 재생 오케스트레이션을 수행합니다.
 * 플랫폼 렌더링과 분리되어 있어 재사용이 가능합니다.
 *
 * @param controller 공통 컨트롤러
 * @param mediaItem 미디어 항목
 * @param autoPlay 자동 재생 여부
 * @param loop 반복 재생 여부(단일 항목일 때)
 * @param mute 음소거 여부
 */
@Composable
internal fun OrchestratePlayback(
    controller: MediaPlayerController,
    mediaItem: MediaItem,
    autoPlay: Boolean,
    loop: Boolean,
    mute: Boolean,
) {
    // 준비, 음소거, 자동 재생
    LaunchedEffect(mediaItem.identifier, mute) {
        controller.prepare(
            item = mediaItem,
            cachePolicy = CachePolicy(mode = CacheMode.StreamCache)
        )
        controller.setVolume(if (mute) 0f else 1f)
        if (autoPlay) controller.play()
    }

    // 반복 재생(단일 항목일 때) – 종료 이벤트에 맞추어 처음으로 이동 후 재생
    LaunchedEffect(mediaItem.identifier, loop) {
        if (!loop) return@LaunchedEffect
        controller.events.collect { event ->
            if (event.state == PlaybackState.Ended) {
                // 큐가 비어 있거나 단일 항목일 때만 효과적
                controller.seekTo(0L)
                controller.play()
            }
        }
    }
}