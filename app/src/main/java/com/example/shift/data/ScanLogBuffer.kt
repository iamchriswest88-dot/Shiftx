package com.example.shift.data

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue

object ScanLogBuffer {
    private const val MAX_CAPACITY = 500
    private val buffer = ConcurrentLinkedQueue<String>()
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun log(msg: String) {
        val timestamp = try {
            LocalDateTime.now().format(timeFormatter)
        } catch (e: Exception) {
            System.currentTimeMillis().toString()
        }
        val entry = "[$timestamp] $msg"
        buffer.add(entry)
        while (buffer.size > MAX_CAPACITY) {
            buffer.poll()
        }
    }

    fun getLogs(): List<String> {
        return buffer.toList()
    }

    fun clear() {
        buffer.clear()
    }
}
