package tech.comfortheart.util

import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.util.regex.Pattern

import javax.activation.MimetypesFileTypeMap
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelProgressiveFuture
import io.netty.channel.ChannelProgressiveFutureListener
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.*
import io.netty.handler.stream.ChunkedFile
import io.netty.util.CharsetUtil
import java.nio.charset.StandardCharsets

class HttpFileServerHandler(private val url: String) : SimpleChannelInboundHandler<FullHttpRequest>() {
    override fun channelRead0(p0: ChannelHandlerContext?, p1: FullHttpRequest?) {
        messageReceived(p0!!, p1!!)
    }

    @Throws(Exception::class)
    fun messageReceived(ctx: ChannelHandlerContext,
                                  request: FullHttpRequest) {
        if (!request.decoderResult().isSuccess) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST)
            return
        }
        if (request.method() !== HttpMethod.GET) {
            sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED)
            return
        }

        val uri = request.uri()
        val path = sanitizeUri(uri)
        if (path == null) {
            sendError(ctx, HttpResponseStatus.FORBIDDEN)
            return
        }

        val file = File(path)
        if (file.isHidden || !file.exists()) {
            sendError(ctx, HttpResponseStatus.NOT_FOUND)
            return
        }
        if (file.isDirectory) {
            if (uri.endsWith("/")) {
                sendListing(ctx, file)
            } else {
                sendRedirect(ctx, "$uri/")
            }
            return
        }
        if (!file.isFile) {
            sendError(ctx, HttpResponseStatus.FORBIDDEN)
            return
        }

        var randomAccessFile = try {
            RandomAccessFile(file, "r")
        } catch (e: FileNotFoundException) {
            sendError(ctx, HttpResponseStatus.NOT_FOUND)
            return
        }

        val fileLength = randomAccessFile.length()
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        HttpUtil.setContentLength(response, fileLength)
        setContentTypeHeader(response, file)

        if (HttpUtil.isKeepAlive(request)) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        }

        ctx.write(response)
        var sendFileFuture = ctx.write(ChunkedFile(randomAccessFile, 0, fileLength, 8192), ctx.newProgressivePromise())
        sendFileFuture!!.addListener(object : ChannelProgressiveFutureListener {

            @Throws(Exception::class)
            override fun operationComplete(future: ChannelProgressiveFuture) {
                println("Transfer complete.")

            }

            @Throws(Exception::class)
            override fun operationProgressed(future: ChannelProgressiveFuture,
                                             progress: Long, total: Long) {
                if (total < 0)
                    System.err.println("Transfer progress: $progress")
                else
                    System.err.println("Transfer progress: $progress/$total")
            }
        })

        val lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
        if (!HttpUtil.isKeepAlive(request))
            lastContentFuture.addListener(ChannelFutureListener.CLOSE)

    }

    @Throws(Exception::class)
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        cause.printStackTrace()
        if (ctx.channel().isActive)
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR)
    }

    private fun sanitizeUri(uri: String): String? {
        var uri =  try {
            URLDecoder.decode(uri, StandardCharsets.UTF_8.name())
        } catch (e: UnsupportedEncodingException) {
            try {
                URLDecoder.decode(uri, StandardCharsets.ISO_8859_1.name())
            } catch (e1: UnsupportedEncodingException) {
                throw Error()
            }

        }

        if (!uri.startsWith(url))
            return null
        if (!uri.startsWith("/"))
            return null

        uri = uri.replace('/', File.separatorChar)
        return if (uri.contains("${File.separator}.")
                || uri.contains(".${File.separator}")
                || uri.startsWith(".")
                || uri.endsWith(".")
                || INSECURE_URI.matcher(uri).matches()) {
            null
        } else {
            val queryStart = uri.indexOf('?')
            val uriTmp = if (queryStart > 0) uri.substring(0, queryStart) else uri

            File(HOME_PATH, uriTmp).absolutePath
        }
    }

    companion object {

        /**
         * Invalid URI types
         *  - with these 5 characters <>":'
         *  - with & before ?
         */
        private val INSECURE_URI = Pattern.compile(".*(([<>\":'])|(&.*\\?)).*")

        private val ALLOWED_FILE_NAME = Pattern.compile("[A-Za-z0-9][-_A-Za-z0-9\\.]*")

        private fun sendListing(ctx: ChannelHandlerContext, dir: File) {
            val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html;charset=UTF-8")

            val dirPath = dir.path
            val buf = StringBuilder()

            buf.append("<!DOCTYPE html>")
            buf.append("<html><head>")
            buf.append("<title>目录: $dirPath</title>")
            buf.append("</head><body>")

            buf.append("<h3>$dirPath</h3>")
            buf.append("<ul>")
            buf.append("<li>链接：<a href=\"..\">..</a></li>")
            for (f in dir.listFiles()!!) {
                if (f.isHidden || !f.canRead()) {
                    continue
                }
                val name = f.name
                if (!ALLOWED_FILE_NAME.matcher(name).matches()) {
                    continue
                }

                buf.append("<li>链接：<a href=\"$name\">$name</a></li>")
            }

            buf.append("</ul></body></html>")

            val buffer = Unpooled.copiedBuffer(buf, CharsetUtil.UTF_8)
            response.content().writeBytes(buffer)
            buffer.release()
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
        }

        private fun sendRedirect(ctx: ChannelHandlerContext, newUri: String) {
            val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND)
            response.headers().set(HttpHeaderNames.LOCATION, newUri)
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
        }

        private fun sendError(ctx: ChannelHandlerContext, status: HttpResponseStatus) {
            val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                    Unpooled.copiedBuffer("Failure: " + status.toString() + "\r\n", CharsetUtil.UTF_8))
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html;charset=UTF-8")
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
        }

        private fun setContentTypeHeader(response: HttpResponse, file: File) {
            val mimeTypesFileTypeMap = MimetypesFileTypeMap()
            val ext = file.extension
            if (ext == "") {
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream")
            } else {
                when(ext){
                    "cer" ->
                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream")
                    "css"->
                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/css;charset=UTF-8")
                    "html", "htm", "xhtml", "xml" ->
                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html;charset=UTF-8")
                    "map", "txt"->
                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain;charset=UTF-8")
                    "js" ->
                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/javascript;charset=UTF-8")
                    "json" ->
                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8")
                    else ->
                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, mimeTypesFileTypeMap.getContentType(file.path))
                }
            }
        }
    }
}