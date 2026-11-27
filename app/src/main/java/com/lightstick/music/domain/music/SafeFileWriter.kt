package com.lightstick.music.domain.music

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 강제 종료에도 반쯤 쓴 파일이 남지 않게 쓰는 도우미.
 *
 * [target] 옆의 `<이름>.tmp` 에 버퍼로 다 쓰고 디스크에 내린(fsync) 뒤 이름을 바꾼다.
 * 같은 파일 시스템 안의 rename 은 한 번에 일어나므로, 최종 파일은 "없음" 아니면 "완전한 파일" 둘 중 하나다.
 * 도중에 죽으면 .tmp 만 남고, [AutoTimelineCleanup] 이 지운다.
 */
object SafeFileWriter {

    const val TMP_SUFFIX = ".tmp"

    fun write(target: File, block: (DataOutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { fos ->
                val out = DataOutputStream(BufferedOutputStream(fos, 64 * 1024))
                block(out)
                out.flush()
                fos.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("rename failed: ${tmp.name}")
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }
}
