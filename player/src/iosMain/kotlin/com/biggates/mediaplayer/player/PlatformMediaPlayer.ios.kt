package com.biggates.mediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.biggates.mediaplayer.player.MediaItem
import com.biggates.mediaplayer.player.MediaPlayerController
import com.biggates.mediaplayer.player.OrchestratePlayback
import platform.UIKit.UIView

/**
 * iOS 실제 구현: AVPlayerLayer 를 담는 UIView 를 UIKitView 로 렌더링합니다.
 *
 * @param controller 공통 컨트롤러(내부적으로 AVPlayer 사용)
 * @param mediaItem 재생할 항목
 * @param autoPlay 자동 재생 여부
 * @param loop 반복 여부(공통 오케스트레이션에서 Ended 시 처음으로 이동)
 * @param mute 음소거 여부
 * @param modifier 컴포즈 수정자
 */
@Composable
actual fun PlatformMediaPlayer(
    modifier: Modifier,
    controller: MediaPlayerController,
    mediaItem: MediaItem,
    autoPlay: Boolean,
    loop: Boolean,
    mute: Boolean
) {

    UIKitView(
        modifier = modifier,
        factory = {
            val view = UIView()
            controller.attachTo(view)
            view
        },
        update = { view ->
            controller.attachTo(view)
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

actual typealias PlatformContext = Unit