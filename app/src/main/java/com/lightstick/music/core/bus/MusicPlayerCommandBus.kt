package com.lightstick.music.core.bus

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object MusicPlayerCommandBus {
    sealed class Command {
        object TogglePlay : Command()
        object Next : Command()
        object Previous : Command()
        data class SeekTo(val position: Long) : Command()
    }

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 1)
    val commands = _commands.asSharedFlow()

    fun trySend(command: Command) {
        _commands.tryEmit(command)
    }
}