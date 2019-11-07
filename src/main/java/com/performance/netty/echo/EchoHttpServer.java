package com.performance.netty.echo;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.*;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.NettyRuntime;
import io.netty.util.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;
import java.io.*;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

/**
 * Echoes back any received data from an HTTP client.
 */
public final class EchoHttpServer {

    private static final Logger logger = LoggerFactory.getLogger(EchoHttpServer.class);

    private static final PrintStream consoleErr = System.err;

    @Parameter(names = "--port", description = "Server Port")
    private int port = 8688;

    @Parameter(names = "--boss-threads", description = "Boss Threads")
    private int bossThreads = NettyRuntime.availableProcessors();

    @Parameter(names = "--worker-threads", description = "Worker Threads")
    private int workerThreads = NettyRuntime.availableProcessors() * 600;

    @Parameter(names = "--http2", description = "Use HTTP/2 protocol instead of HTTP/1.1")
    private boolean http2 = false;

    @Parameter(names = "--ssl", description = "Enable SSL")
    private boolean ssl = false;

    @Parameter(names = "--key-store-file", validateValueWith = KeyStoreFileValidator.class,
            description = "Keystore file")
    private File keyStoreFile = null;

    @Parameter(names = "--key-store-password", description = "Keystore password")
    private String keyStorePassword = "";

    @Parameter(names = "--delay", description = "Response delay in milliseconds")
    private int sleepTime = 0;

    @Parameter(names = {"-h", "--help"}, description = "Display Help", help = true)
    private boolean help = false;

    @Parameter(names = "--h2-aggregate-content", description = "Enable HTTP/2 content aggregation")
    private boolean h2AggregateContent = true;

    public static void main(String[] args) throws Exception {
        EchoHttpServer echoHttpServer = new EchoHttpServer();
        final JCommander jcmdr = new JCommander(echoHttpServer);
        jcmdr.setProgramName(EchoHttpServer.class.getSimpleName());
        try {
            jcmdr.parse(args);
        } catch (ParameterException ex) {
            consoleErr.println(ex.getMessage());
            return;
        }

        if (echoHttpServer.help) {
            jcmdr.usage();
            return;
        }

        echoHttpServer.startServer();
    }

    private void startServer() throws SSLException, CertificateException, InterruptedException {
        logger.info("Echo HTTP/{} Server. Port: {}, Boss Threads: {}, Worker Threads: {}, SSL Enabled: {}" +
                ", Sleep Time: {}ms", http2 ? "2.0" : "1.1", port, bossThreads, workerThreads, ssl, sleepTime);
        // Print Max Heap Size
        logger.info("Max Heap Size: {}MB", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        // Print Netty Version
        Version version = Version.identify(this.getClass().getClassLoader()).values().iterator().next();
        logger.info("Netty Version: {}", version.artifactVersion());
        // Configure the server.
        EventLoopGroup bossGroup = new NioEventLoopGroup(bossThreads);
        EventLoopGroup workerGroup = new NioEventLoopGroup(workerThreads);
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024);
            b = http2 ? configureHttp2(b) : configureHttp1_1(b);

            // Start the server.
            // Bind and start to accept incoming connections.
            ChannelFuture f = b.bind(port).sync();

            // Wait until the server socket is closed.
            f.channel().closeFuture().sync();
        } finally {
            // Shut down all event loops to terminate all threads.
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    private ServerBootstrap configureHttp1_1(ServerBootstrap b) throws SSLException, CertificateException {
        // Configure SSL.
        final SslContext sslCtx;
        if (ssl) {
            SslContextBuilder sslContextBuilder = createSslContextBuilder();
            sslCtx = sslContextBuilder.build();
        } else {
            sslCtx = null;
        }
        return b.childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        if (sslCtx != null) {
                            p.addLast(sslCtx.newHandler(ch.alloc()));
                        }
                        p.addLast(new HttpServerCodec());
                        p.addLast("aggregator", new HttpObjectAggregator(1048576));
                        p.addLast(new EchoHttpServerHandler(sleepTime, false));
                    }
                });
    }

    private ServerBootstrap configureHttp2(ServerBootstrap b) throws SSLException, CertificateException {
        // Configure SSL.
        final SslContext sslCtx;
        if (ssl) {
            ApplicationProtocolConfig protocolConfig =
                    new ApplicationProtocolConfig(ApplicationProtocolConfig.Protocol.ALPN,
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1);
            SslContextBuilder sslContextBuilder = createSslContextBuilder();
            sslCtx = sslContextBuilder.applicationProtocolConfig(protocolConfig)
                    .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                    .build();
        } else {
            sslCtx = null;
        }
        return b.childHandler(new Http2ServerInitializer(sslCtx, sleepTime, h2AggregateContent));
    }

    private SslContextBuilder createSslContextBuilder() throws CertificateException {
        final SslContextBuilder sslContextBuilder;
        if (keyStoreFile != null) {
            logger.info("Creating SSL context using the key store {}", keyStoreFile.getAbsolutePath());
            KeyManagerFactory keyManagerFactory = getKeyManagerFactory(keyStoreFile);
            sslContextBuilder = SslContextBuilder.forServer(keyManagerFactory);
        } else {
            logger.info("Creating SSL context using self signed certificate");
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslContextBuilder = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey());
        }
        return sslContextBuilder.sslProvider(SslProvider.OPENSSL);
    }

    private KeyManagerFactory getKeyManagerFactory(File keyStoreFile) {
        KeyManagerFactory kmf;
        try {
            KeyStore ks = getKeyStore(keyStoreFile, keyStorePassword);
            kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            if (ks != null) {
                kmf.init(ks, keyStorePassword.toCharArray());
            }
            return kmf;
        } catch (UnrecoverableKeyException | NoSuchAlgorithmException | KeyStoreException | IOException e) {
            throw new IllegalArgumentException("Failed to initialize the Key Manager factory", e);
        }
    }

    private KeyStore getKeyStore(File keyStoreFile, String keyStorePassword) throws IOException {
        KeyStore keyStore = null;
        String tlsStoreType = "PKCS12";
        if (keyStoreFile != null && keyStorePassword != null) {
            try (InputStream is = new FileInputStream(keyStoreFile)) {
                keyStore = KeyStore.getInstance(tlsStoreType);
                keyStore.load(is, keyStorePassword.toCharArray());
            } catch (CertificateException | NoSuchAlgorithmException | KeyStoreException e) {
                throw new IOException(e);
            }
        }
        return keyStore;
    }
}
