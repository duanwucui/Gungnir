package io.github.chinalhr.gungnir.netchannel.client.netty;

import io.github.chinalhr.gungnir.protocol.GResponse;
import io.github.chinalhr.gungnir.utils.RpcCallbackFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @Author : ChinaLHR
 * @Date : Create in 15:12 2018/4/26
 * @Email : 13435500980@163.com
 */
public class GungnirClientHandler extends SimpleChannelInboundHandler<GResponse>{

    private static final Logger LOGGER = LoggerFactory.getLogger(GungnirClientHandler.class);

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, GResponse response) throws Exception {
        RpcCallbackFuture future = RpcCallbackFuture.futurePool.get(response.getRequestId());
        future.setResponse(response);
        RpcCallbackFuture.futurePool.put(response.getRequestId(), future);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOGGER.error("GungnirClient Netty caught exception",cause);
        ctx.close();
    }
}