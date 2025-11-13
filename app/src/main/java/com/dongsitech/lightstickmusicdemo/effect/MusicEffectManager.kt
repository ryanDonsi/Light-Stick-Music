package com.dongsitech.lightstickmusicdemo.effect

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class MusicEffect(
    val startTimeMs: Long,
    val payload: ByteArray
)

object MusicEffectManager {

    private val effectFileMap = mutableMapOf<Int, File>()

    /**
     * 이펙트 디렉토리 내의 모든 .efx 파일을 musicId(hashCode) 기준으로 매핑
     */
    fun initialize(effectDir: File) {
        if (!effectDir.exists()) return
        effectFileMap.clear()

        effectDir.listFiles { file ->
            file.extension == "efx"
        }?.forEach { file ->
            val musicId = file.nameWithoutExtension.hashCode()
            effectFileMap[musicId] = file
        }
    }

    /**
     * 주어진 음악 ID에 대한 이펙트 존재 여부 확인
     */
    fun hasEffectFor(musicId: Int): Boolean {
        return effectFileMap.containsKey(musicId)
    }

    /**
     * 음악 ID에 해당하는 이펙트 파일을 읽고 파싱
     */
    fun loadEffects(musicId: Int): List<MusicEffect>? {
        val file = effectFileMap[musicId] ?: return null
        val data = file.readBytes()

        val effects = mutableListOf<MusicEffect>()
        var offset = 0
        while (offset + 30 <= data.size) {
            val time = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
            val payload = data.copyOfRange(offset + 4, offset + 30)
            effects.add(MusicEffect(time, payload))
            offset += 30
        }

        return effects
    }
}
