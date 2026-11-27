package com.lightstick.music.core.bus

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object MusicPlayerCommandBus {
    sealed class Command {
        object TogglePlay : Command()
        /** 헤드셋/잠금화면의 명시적 재생·일시정지 (토글이 아니라 상태를 지정) */
        object Play : Command()
        object Pause : Command()
        object Next : Command()
        object Previous : Command()
        data class SeekTo(val position: Long) : Command()
    }

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 10)
    val commands = _commands.asSharedFlow()

    fun trySend(command: Command) {
        _commands.tryEmit(command)
    }
}