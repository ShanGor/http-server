package tech.comfortheart.util

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpRequestDecoder
import io.netty.handler.codec.http.HttpResponseEncoder
import io.netty.handler.stream.ChunkedWriteHandler
import kotlin.system.exitProcess

class HttpFileServer {

    @Throws(Exception::class)
    fun run(port: Int, url: String) {
        val bossGroup = NioEventLoopGroup()
        val workerGroup = NioEventLoopGroup()

        try {
            val b = ServerBootstrap()
            b.group(bossGroup, workerGroup).channel(NioServerSocketChannel::class.java)
                    .childHandler(object : ChannelInitializer<SocketChannel>() {
                        @Throws(Exception::class)
                        override fun initChannel(ch: SocketChannel) {
                            ch.pipeline().addLast("http-decoder", HttpRequestDecoder())
                            ch.pipeline().addLast("http-aggregator", HttpObjectAggregator(65536))
                            ch.pipeline().addLast("http-encoder", HttpResponseEncoder())
                            ch.pipeline().addLast("http-chunked", ChunkedWriteHandler())
                            ch.pipeline().addLast("fileServerHandler", HttpFileServerHandler(url))
                        }
                    })

            val f = b.bind(port).sync()
            println("HTTP 文件服务器启动, 地址是： http://*:$port$url")
            f.channel().closeFuture().sync()

        } finally {
            bossGroup.shutdownGracefully()
            workerGroup.shutdownGracefully()
        }
    }

}


private const val DEFAULT_URL = "/"
var HOME_PATH: String? = ""
var SERVER_PORT: Int = 8888

fun main(args: Array<String>) {
    HOME_PATH = System.getProperty("http.home")
    if (HOME_PATH == null || HOME_PATH!!.trim() == "") {
        println("Please specify http.home property!!")
        exitProcess(-1)
    }

    val portX = System.getProperty("server.port")
    var port = SERVER_PORT;
    if (portX != null) {
        try {
            port = Integer.parseInt(portX.trim())
        } catch (e: Exception) {
            println("The server.port parameter should be numeric!!")
            exitProcess(-1)
        }
    }

    HttpFileServer().run(port, DEFAULT_URL)
}