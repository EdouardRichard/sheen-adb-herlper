package com.sheen.adb.feature.processes

import com.sheen.adb.core.ProcessFieldState
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import java.nio.file.Files
import java.nio.file.Path

class ProcessesPresentationTest {
    @Test
    fun `screen presents process management fields refresh and risk confirmation copy`() {
        val source = String(
            Files.readAllBytes(
                Path.of("src/main/kotlin/com/sheen/adb/feature/processes/ProcessesScreen.kt"),
            ),
        )
        listOf("进程管理", "刷新", "应用名", "包名", "进程名", "CPU", "内存", "父 PID", "进程 PID", "我了解").forEach {
            assertTrue(source.contains(it), "missing $it")
        }
        listOf("数据丢失", "服务中断", "设备不稳定", "force-stop", "进程、服务和任务", "再次显式启动前").forEach {
            assertTrue(source.contains(it), "missing risk phrase $it")
        }
        assertTrue(ProcessFieldState.entries.contains(ProcessFieldState.UNKNOWN))
    }
}
