package com.biggates.mediaplayer.player

import android.app.Application
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/**
 * Android 실제 구현: Media3 PlayerView 를 AndroidView 로 렌더링합니다.
 *
 * @param controller 공통 컨트롤러(내부적으로 ExoPlayer 사용)
 * @param mediaItem 재생할 항목
 * @param autoPlay 자동 재생 여부
 * @param loop 반복 여부
 * @param mute 음소거 여부
 * @param modifier 컴포즈 수정자
 */
@OptIn(UnstableApi::class)
@Composable
actual fun PlatformMediaPlayer(
    modifier: Modifier,
    controller: MediaPlayerController,
    mediaItem: MediaItem,
    autoPlay: Boolean,
    loop: Boolean,
    mute: Boolean
) {
    // 실제 비디오 뷰 렌더링
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false // 화면 위 컨트롤 숨김(필요하면 true)
                controller.attachTo(this)
            }
        },
        update = { playerView ->
            controller.attachTo(playerView)
            controller.setRepeatAll(loop)
        }
    )

    // 공통 재생 오케스트레이션
    OrchestratePlayback(
        controller = controller,
        mediaItem = mediaItem,
        autoPlay = autoPlay,
        loop = loop,
        mute = mute
    )

    // 뷰가 제거될 때 일시 정지(필요 시 정지/해제)
    DisposableEffect(Unit) {
        onDispose {
            controller.pause()
        }
    }
}

actual typealias PlatformContext = Application