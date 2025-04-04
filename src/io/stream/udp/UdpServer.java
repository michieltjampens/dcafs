package io.stream.udp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.Future;
import io.stream.BaseStream;
import org.tinylog.Logger;
import util.xml.XMLdigger;

public class UdpServer extends BaseStream {

  EventLoopGroup group;
  int port;
  UDPhandler handler;
  Future<?> serverFuture;

    public UdpServer(XMLdigger stream) {
    super(stream);
  }
  public UdpServer( String id, int port ){
      super(id);
      this.port=port;
  }
  public void setEventLoopGroup( EventLoopGroup group ){
    this.group=group;
  }

  class RunServer implements Runnable{ 
    @Override
    public void run() {
      try {
        Bootstrap b = new Bootstrap();

        handler = new UDPhandler();
        handler.setTargets(targets);
        handler.setID(id);
        
        b.group(group).channel(NioDatagramChannel.class).option(ChannelOption.SO_BROADCAST, true).handler(handler);
        b.bind(port).sync().channel().closeFuture().await();
      } catch (InterruptedException e) {
        Logger.error(e);
        Logger.error("InterruptedException for UDP server on port " + port);
        // Restore interrupted state...
        Thread.currentThread().interrupt();
      }
    }
  }
  @Override
  protected boolean readExtraFromXML(XMLdigger stream) {
      port = stream.peekAt("port").value(-1);
    return port != -1;
  }

  @Override
  public boolean connect() {
    serverFuture = group.submit(new RunServer());
    return true;
  }

  @Override
  public boolean disconnect() {
    return serverFuture.cancel(true);
  }

  @Override
  public boolean isConnectionValid() {
    return serverFuture != null;
  }

  @Override
  public long getLastTimestamp() {
    return handler.getTimestamp();
  }

  @Override
  public String getInfo() {
    return "UDP server [" + id + (label.isEmpty() ? "" : "|" + label) + "] listens on " + port;
  }

  @Override
  protected String getType() {
    return "udpserver";
  }

  @Override
  protected void flagIdle() {

  }
}