/*
 * Copyright (c) 2018-2021 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http.server;

import java.net.SocketAddress;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureContextListener;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.util.annotation.Nullable;

import static reactor.netty.ReactorNetty.format;

/**
 * This handler is intended to work together with {@link Http2StreamFrameToHttpObjectCodec}
 * it converts the outgoing messages into objects expected by
 * {@link Http2StreamFrameToHttpObjectCodec}.
 *
 * @author Violeta Georgieva
 */
final class Http2StreamBridgeServerHandler extends ChannelHandlerAdapter implements FutureContextListener<Channel, Void> {

	final BiPredicate<HttpServerRequest, HttpServerResponse>      compress;
	final ServerCookieDecoder                                     cookieDecoder;
	final ServerCookieEncoder                                     cookieEncoder;
	final HttpServerFormDecoderProvider                           formDecoderProvider;
	final BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
	final ConnectionObserver                                      listener;
	final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>
	                                                              mapHandle;

	SocketAddress remoteAddress;

	Boolean secured;

	Http2StreamBridgeServerHandler(
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compress,
			ServerCookieDecoder decoder,
			ServerCookieEncoder encoder,
			HttpServerFormDecoderProvider formDecoderProvider,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			ConnectionObserver listener,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle) {
		this.compress = compress;
		this.cookieDecoder = decoder;
		this.cookieEncoder = encoder;
		this.formDecoderProvider = formDecoderProvider;
		this.forwardedHeaderHandler = forwardedHeaderHandler;
		this.listener = listener;
		this.mapHandle = mapHandle;
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) {
		if (HttpServerOperations.log.isDebugEnabled()) {
			HttpServerOperations.log.debug(format(ctx.channel(), "New HTTP/2 stream"));
		}
		ctx.read();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (secured == null) {
			secured = ctx.channel().parent().pipeline().get(SslHandler.class) != null;
		}
		if (remoteAddress == null) {
			remoteAddress =
					Optional.ofNullable(HAProxyMessageReader.resolveRemoteAddressFromProxyProtocol(ctx.channel().parent()))
					        .orElse(ctx.channel().parent().remoteAddress());
		}
		if (msg instanceof HttpRequest) {
			HttpRequest request = (HttpRequest) msg;
			HttpServerOperations ops;
			try {
				ops = new HttpServerOperations(Connection.from(ctx.channel()),
						listener,
						request,
						compress,
						ConnectionInfo.from(ctx.channel().parent(),
						                    request,
						                    secured,
						                    remoteAddress,
						                    forwardedHeaderHandler),
						cookieDecoder,
						cookieEncoder,
						formDecoderProvider,
						mapHandle,
						secured);
			}
			catch (RuntimeException e) {
				HttpServerOperations.sendDecodingFailures(ctx, listener, secured, e, msg);
				return;
			}
			ops.bind();
			listener.onStateChange(ops, ConnectionObserver.State.CONFIGURED);
		}
		ctx.fireChannelRead(msg);
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public Future<Void> write(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof ByteBuf) {
			//"FutureReturnValueIgnored" this is deliberate
			return ctx.write(new DefaultHttpContent((ByteBuf) msg));
		}
		else {
			//"FutureReturnValueIgnored" this is deliberate
			Future<Void> f = ctx.write(msg);
			if (msg instanceof LastHttpContent) {
				f.addListener(ctx.channel(), this);
			}
			return f;
		}
	}

	@Override
	public void operationComplete(Channel context, Future<? extends Void> future) {
		if (!future.isSuccess()) {
			if (HttpServerOperations.log.isDebugEnabled()) {
				HttpServerOperations.log.debug(format(context,
						"Sending last HTTP packet was not successful, terminating the channel"),
						future.cause());
			}
		}
		else {
			if (HttpServerOperations.log.isDebugEnabled()) {
				HttpServerOperations.log.debug(format(context,
						"Last HTTP packet was sent, terminating the channel"));
			}
		}

		HttpServerOperations.cleanHandlerTerminate(context);
	}
}
