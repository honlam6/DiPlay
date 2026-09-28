package com.shilapi.xcertplay.airplay

/**
 * 全局 trace 开关。调试日志默认关闭时，热路径（触控、事件/控制通道每包）上的
 * hex 转储字符串完全跳过构建，避免每秒上千个临时对象；开启调试日志后按会话生效。
 */
object TraceGate {
    @Volatile var enabled = false
}
