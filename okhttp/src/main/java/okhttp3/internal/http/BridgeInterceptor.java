/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.http;

import java.io.IOException;
import java.util.List;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.internal.Version;
import okio.GzipSource;
import okio.Okio;

import static okhttp3.internal.Util.hostHeader;

/**
 * Bridges from application code to network code. First it builds a network request from a user
 * request. Then it proceeds to call the network. Finally it builds a user response from the network
 * response.
 */
public final class BridgeInterceptor implements Interceptor {
  private final CookieJar cookieJar;

  public BridgeInterceptor(CookieJar cookieJar) {
    this.cookieJar = cookieJar;
  }

  @Override public Response intercept(Chain chain) throws IOException {
    Request userRequest = chain.request();
    Request.Builder requestBuilder = userRequest.newBuilder();

    RequestBody body = userRequest.body();
    if (body != null) {
      MediaType contentType = body.contentType();
      /**
       * Content-Type 实体头部用于指示资源的MIME类型 media type 。
       * 在响应中，Content-Type标头告诉客户端实际返回的内容的内容类型。
       *
       * Content-Type: text/html; charset=utf-8
       * Content-Type: multipart/form-data; boundary=something
       */
      if (contentType != null) {
        //报文主体内容类型
        requestBuilder.header("Content-Type", contentType.toString());
      }

      /**
       * Content-Length
       * 是一个实体消息首部，用来指明发送给接收方的消息主体的大小，即用十进制数字表示的八位元组的数目。
       *
       * Transfer-Encoding 消息首部指明了将 entity 安全传递给用户所采用的编码形式。
       * Transfer-Encoding 是一个逐跳传输消息首部，即仅应用于两个节点之间的消息传递，而不是所请求的资源本身。
       * 一个多节点连接中的每一段都可以应用不同的Transfer-Encoding 值。如果你想要将压缩后的数据应用于整个连接，那么请使用端到端传输消息首部  Content-Encoding 。
       * 当这个消息首部出现在 HEAD 请求的响应中，而这样的响应没有消息体，那么它其实指的是应用在相应的  GET 请求的应答的值。
       *
       * Transfer-Encoding: chunked
       * Transfer-Encoding: compress
       * Transfer-Encoding: deflate
       * Transfer-Encoding: gzip
       * Transfer-Encoding: identity
       *
       * chunked
       * 数据以一系列分块的形式进行发送。 Content-Length 首部在这种情况下不被发送。。在每一个分块的开头需要添加当前分块的长度，以十六进制的形式表示，后面紧跟着 '\r\n' ，之后是分块本身，后面也是'\r\n' 。终止块是一个常规的分块，不同之处在于其长度为0。终止块后面是一个挂载（trailer），由一系列（或者为空）的实体消息首部构成。
       * compress
       * 采用 Lempel-Ziv-Welch (LZW) 压缩算法。这个名称来自UNIX系统的 compress 程序，该程序实现了前述算法。
       * 与其同名程序已经在大部分UNIX发行版中消失一样，这种内容编码方式已经被大部分浏览器弃用，部分因为专利问题（这项专利在2003年到期）。
       * deflate
       * 采用 zlib 结构 (在 RFC 1950 中规定)，和 deflate 压缩算法(在 RFC 1951 中规定)。
       * gzip
       * 表示采用  Lempel-Ziv coding (LZ77) 压缩算法，以及32位CRC校验的编码方式。这个编码方式最初由 UNIX 平台上的 gzip 程序采用。处于兼容性的考虑， HTTP/1.1 标准提议支持这种编码方式的服务器应该识别作为别名的 x-gzip 指令。
       * identity
       * 用于指代自身（例如：未经过压缩和修改）。除非特别指明，这个标记始终可以被接受。
       */
      long contentLength = body.contentLength();
      if (contentLength != -1) {
        //报文内容大小
        requestBuilder.header("Content-Length", Long.toString(contentLength));
        //传输报文主体时采用的编码方式
        requestBuilder.removeHeader("Transfer-Encoding");
      } else {
        requestBuilder.header("Transfer-Encoding", "chunked");
        requestBuilder.removeHeader("Content-Length");
      }
    }

    /**
     * Host 请求头指明了请求将要发送到的服务器主机名和端口号。
     * 如果没有包含端口号，会自动使用被请求服务的默认端口（比如HTTPS URL使用443端口，HTTP URL使用80端口）。
     * 所有HTTP/1.1 请求报文中必须包含一个Host头字段。对于缺少Host头或者含有超过一个Host头的HTTP/1.1 请求，可能会收到400（Bad Request）状态码。
     */
    if (userRequest.header("Host") == null) {
      //请求资源所在服务器，段在 HTTP/1.1 规范内是唯一一个必须被包含在请 求内的首部字段
      requestBuilder.header("Host", hostHeader(userRequest.url(), false));
    }

    /**
     * Connection 头（header） 决定当前的事务完成后，是否会关闭网络连接。
     * 如果该值是“keep-alive”，网络连接就是持久的，不会关闭，使得对同一个服务器的请求可以继续在该连接上完成。
     *
     * Connection: keep-alive
     * Connection: close
     * close:表明客户端或服务器想要关闭该网络连接，这是HTTP/1.0请求的默认值
     */
    if (userRequest.header("Connection") == null) {
      //连接状态是否是持久连接,HTTP/1.1默认开启，不需要使用了
      requestBuilder.header("Connection", "Keep-Alive");
    }

    // If we add an "Accept-Encoding: gzip" header field we're responsible for also decompressing
    // the transfer stream.
    //okhttp 默认添加添加了Gzip 压缩
    boolean transparentGzip = false;
    /**
     * HTTP 请求头 Accept-Encoding 会将客户端能够理解的内容编码方式——通常是某种压缩算法——进行通知（给服务端）。
     * 通过内容协商的方式，服务端会选择一个客户端提议的方式，使用并在响应头 Content-Encoding 中通知客户端该选择。
     *
     * Accept-Encoding: gzip
     * Accept-Encoding: compress
     * Accept-Encoding: deflate
     * Accept-Encoding: br
     * Accept-Encoding: identity
     * Accept-Encoding: *
     * gzip  表示采用 Lempel-Ziv coding (LZ77) 压缩算法，以及32位CRC校验的编码方式。
     * compress 采用 Lempel-Ziv-Welch (LZW) 压缩算法。
     * deflate 采用 zlib 结构和 deflate 压缩算法。
     * br 表示采用 Brotli 算法的编码方式。
     * identity 用于指代自身（例如：未经过压缩和修改）。除非特别指明，这个标记始终可以被接受。
     * * 匹配其他任意未在该请求头字段中列出的编码方式。假如该请求头字段不存在的话，这个值是默认值。
     * 它并不代表任意算法都支持，而仅仅表示算法之间无优先次序。
     */
    if (userRequest.header("Accept-Encoding") == null && userRequest.header("Range") == null) {
      transparentGzip = true;
      requestBuilder.header("Accept-Encoding", "gzip");
    }

    /**
     * Cookie 是一个请求首部，其中含有先前由服务器通过 Set-Cookie  首部投放并存储到客户端的 HTTP cookies。
     */
    List<Cookie> cookies = cookieJar.loadForRequest(userRequest.url());
    if (!cookies.isEmpty()) {
      //获取本地存储的 cookie，然后设置到请求头的Cookie中去。
      requestBuilder.header("Cookie", cookieHeader(cookies));
    }

    /**
     * User-Agent 首部包含了一个特征字符串，用来让网络协议的对端来识别发起请求的用户代理软件的应用类型、操作系统、软件开发商以及版本号。
     */
    if (userRequest.header("User-Agent") == null) {
      //客户端信息
      requestBuilder.header("User-Agent", Version.userAgent());
    }

    Response networkResponse = chain.proceed(requestBuilder.build());
    //存储服务端返回的 cookie
    HttpHeaders.receiveHeaders(cookieJar, userRequest.url(), networkResponse.headers());

    /**
     *  Content-Encoding 列出了对当前实体消息（消息荷载）应用的任何编码类型，以及编码的顺序。它让接收者知道需要以何种顺序解码该实体消息才能获得原始荷载格式。
     *  Content-Encoding 主要用于在不丢失原媒体类型内容的情况下压缩消息数据。
     * 请注意原始媒体/内容的类型通过 Content-Type 首部给出，而 Content-Encoding 应用于数据的表示，或“编码形式”。如果原始媒体以某种方式编码（例如 zip 文件），
     * 则该信息不应该被包含在 Content-Encoding 首部内。
     * 一般建议服务器应对数据尽可能地进行压缩，并在适当情况下对内容进行编码。对一种压缩过的媒体类型如 zip 或 jpeg 进行额外的压缩并不合适，因为这反而有可能会使荷载增大。
     *
     * Content-Encoding: gzip
     * Content-Encoding: compress
     * Content-Encoding: deflate
     * Content-Encoding: br
     * // 多个，按应用的编码顺序列出
     * Content-Encoding: deflate, gzip
     */
    Response.Builder responseBuilder = networkResponse.newBuilder()
        .request(userRequest);
    //返回数据是否需要 gzip 解压
    if (transparentGzip
        && "gzip".equalsIgnoreCase(networkResponse.header("Content-Encoding"))
        && HttpHeaders.hasBody(networkResponse)) {
      GzipSource responseBody = new GzipSource(networkResponse.body().source());
      Headers strippedHeaders = networkResponse.headers().newBuilder()
          .removeAll("Content-Encoding")//对实体内容进行压缩编码，目的是优化传输
          .removeAll("Content-Length")
          .build();
      responseBuilder.headers(strippedHeaders);
      String contentType = networkResponse.header("Content-Type");
      responseBuilder.body(new RealResponseBody(contentType, -1L, Okio.buffer(responseBody)));
    }
    //返回数据
    return responseBuilder.build();
  }

  /** Returns a 'Cookie' HTTP request header with all cookies, like {@code a=b; c=d}. */
  /**
   * 处理 header
   * @param cookies
   * @return
   */
  private String cookieHeader(List<Cookie> cookies) {
    StringBuilder cookieHeader = new StringBuilder();
    for (int i = 0, size = cookies.size(); i < size; i++) {
      if (i > 0) {
        cookieHeader.append("; ");
      }
      Cookie cookie = cookies.get(i);
      cookieHeader.append(cookie.name()).append('=').append(cookie.value());
    }
    return cookieHeader.toString();
  }
}
