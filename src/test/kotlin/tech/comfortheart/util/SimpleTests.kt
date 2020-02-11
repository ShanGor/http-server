package tech.comfortheart.util

import junit.framework.Assert.assertEquals
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

class SimpleTests {
    @Test
    fun testCoroutine() = runBlocking {
        for (i in 0..9) {
            GlobalScope.launch {
                delay(100)
                println("$i")
            }
        }
    }
    @Test
    fun testCoroutine1() = runBlocking<Unit> {
        GlobalScope.launch {
            repeat(1000) { i ->
                println("I'm sleeping $i ...")
                delay(500L)
            }
        }

        repeat(10) {
            GlobalScope.launch {
                println("Hello $it")
                delay(50L)
            }
        }
    }

    @Test
    fun testExtension() {
        val ext = File("c:\\hello").extension
        assertEquals("", ext)
    }

    @Test
    fun testCharsetName() {
        println(StandardCharsets.UTF_8.name())
    }

}