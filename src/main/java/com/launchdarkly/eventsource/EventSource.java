package com.launchdarkly.eventsource;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LDSLF4J;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import static com.launchdarkly.eventsource.Helpers.pow2;
import static com.launchdarkly.eventsource.ReadyState.CLOSED;
import static com.launchdarkly.eventsource.ReadyState.CONNECTING;
import static com.launchdarkly.eventsource.ReadyState.OPEN;
import static com.launchdarkly.eventsource.ReadyState.RAW;
import static com.launchdarkly.eventsource.ReadyState.SHUTDOWN;
import static java.lang.String.format;

import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * A client for the <a href="https://html.spec.whatwg.org/multipage/server-sent-events.html#server-sent-events">Server-Sent
 * Events</a> (SSE) protocol.
 * <p>
 * Instances are always configured and constructed with {@link Builder}. The client is created in
 * an inactive state and will not connect until you call {@link #start()}.
 * <p>
 * Note that although {@code EventSource} is named after the JavaScript API that is described in
 * the SSE specification, its behavior is not identical to standard web browser implementations of
 * EventSource, specifically in terms of failure/reconnection behavior: it will automatically
 * retry (with a backoff delay) for some error conditions where a browser will not retry. It also
 * supports request configuration options (such as request headers and method) that the browser
 * EventSource does not support. However, its interpretation of the stream data is fully conformant
 * with the SSE specification, unless you use the opt-in mode {@link Builder#streamEventData(boolean)}
 * which allows for greater efficiency in some use cases but has some behavioral constraints.
 */
public class EventSource implements Closeable {
  final LDLogger logger; // visible for tests

  /**
   * The default value for {@link Builder#reconnectTime(Duration)}: 1 second.
   */
  public static final Duration DEFAULT_RECONNECT_TIME = Duration.ofSeconds(1);
  /**
   * The default value for {@link Builder#maxReconnectTime(Duration)}: 30 seconds.
   */
  public static final Duration DEFAULT_MAX_RECONNECT_TIME = Duration.ofSeconds(30);
  /**
   * The default value for {@link Builder#connectTimeout(Duration)}: 10 seconds.
   */
  public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
  /**
   * The default value for {@link Builder#writeTimeout(Duration)}: 5 seconds.
   */
  public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ofSeconds(5);
  /**
   * The default value for {@link Builder#readTimeout(Duration)}: 5 minutes.
   */
  public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofMinutes(5);
  /**
   * The default value for {@link Builder#backoffResetThreshold(Duration)}: 60 seconds.
   */
  public static final Duration DEFAULT_BACKOFF_RESET_THRESHOLD = Duration.ofSeconds(60);
  /**
   * The default value for {@link Builder#readBufferSize(int)}.
   */
  public static final int DEFAULT_READ_BUFFER_SIZE = 1000;
  
  private static final Headers defaultHeaders =
      new Headers.Builder().add("Accept", "text/event-stream").add("Cache-Control", "no-cache").build();
  
  private final String name;
  private volatile HttpUrl url;
  private final Headers headers;
  private final String method;
  private final RequestBody body;
  private final RequestTransformer requestTransformer;
  private final ExecutorService eventExecutor;
  private final ExecutorService streamExecutor;
  final int readBufferSize; // visible for tests
  volatile Duration reconnectTime; // visible for tests
  final Duration maxReconnectTime; // visible for tests
  final Duration backoffResetThreshold; // visible for tests
  private volatile String lastEventId;
  final AsyncEventHandler handler; // visible for tests
  private final ConnectionErrorHandler connectionErrorHandler;
  final boolean streamEventData;   // visible for tests
  final Set<String> expectFields;  // visible for tests
  private final AtomicReference<ReadyState> readyState;
  private final OkHttpClient client;
  private volatile Call call;
  private final SecureRandom jitter = new SecureRandom();

  EventSource(Builder builder) {
    this.name = builder.name == null ? "" : builder.name;
    if (builder.logger == null) {
      String loggerName = (builder.loggerBaseName == null ? EventSource.class.getCanonicalName() : builder.loggerBaseName) +
          (name.isEmpty() ? "" : ("." + name));
      this.logger = LDLogger.withAdapter(LDSLF4J.adapter(), loggerName);
    } else {
      this.logger = builder.logger;
    }
    this.url = builder.url;
    this.headers = addDefaultHeaders(builder.headers);
    this.method = builder.method;
    this.body = builder.body;
    this.requestTransformer = builder.requestTransformer;
    this.lastEventId = builder.lastEventId;
    this.reconnectTime = builder.reconnectTime;
    this.maxReconnectTime = builder.maxReconnectTime;
    this.backoffResetThreshold = builder.backoffResetThreshold;
    this.streamEventData = builder.streamEventData;
    this.expectFields = builder.expectFields;
    
    ThreadFactory eventsThreadFactory = createThreadFactory("okhttp-eventsource-events", builder.threadPriority);
    this.eventExecutor = Executors.newSingleThreadExecutor(eventsThreadFactory);
    ThreadFactory streamThreadFactory = createThreadFactory("okhttp-eventsource-stream", builder.threadPriority);
    this.streamExecutor = Executors.newSingleThreadExecutor(streamThreadFactory);
    Semaphore eventThreadSemaphore;
    if (builder.maxEventTasksInFlight > 0) {
      eventThreadSemaphore = new Semaphore(builder.maxEventTasksInFlight);
    } else {
      eventThreadSemaphore = null;
    }
    this.handler = new AsyncEventHandler(this.eventExecutor, builder.handler, logger, eventThreadSemaphore);
    this.connectionErrorHandler = builder.connectionErrorHandler == null ?
        ConnectionErrorHandler.DEFAULT : builder.connectionErrorHandler;
    this.readBufferSize = builder.readBufferSize;
    this.readyState = new AtomicReference<>(RAW);
    this.client = builder.clientBuilder.build();
  }

  private ThreadFactory createThreadFactory(final String type, final Integer threadPriority) {
    final ThreadFactory backingThreadFactory = Executors.defaultThreadFactory();
    final AtomicLong count = new AtomicLong(0);
    return runnable -> {
      Thread thread = backingThreadFactory.newThread(runnable);
      thread.setName(format(Locale.ROOT, "%s-[%s]-%d", type, name, count.getAndIncrement()));
      thread.setDaemon(true);
      if (threadPriority != null) {
        thread.setPriority(threadPriority);
      }
      return thread;
    };
  }

  /**
   * Attempts to connect to the remote event source if not already connected. This method returns
   * immediately; the connection happens on a worker thread.
   */
  public void start() {
    if (!readyState.compareAndSet(RAW, CONNECTING)) {
      logger.info("Start method called on this already-started EventSource object. Doing nothing");
      return;
    }
    logger.debug("readyState change: {} -> {}", RAW, CONNECTING);
    logger.info("Starting EventSource client using URI: {}", url);
    streamExecutor.execute(this::run);
  }
  
  /**
   * Drops the current stream connection (if any) and attempts to reconnect.
   * <p>
   * This method returns immediately after dropping the current connection; the reconnection happens on
   * a worker thread.
   * <p>
   * If a connection attempt is already in progress but has not yet connected, or if {@link #close()} has
   * previously been called, this method has no effect. If {@link #start()} has never been called, it is
   * the same as calling {@link #start()}.
   */
  public void restart() {
    ReadyState previousState = readyState.getAndUpdate(t -> t == ReadyState.OPEN ? ReadyState.CLOSED : t);
    if (previousState == OPEN) {
      closeCurrentStream(previousState);
    } else if (previousState == RAW) {
      start();
    }
    // if already connecting or already shutdown or in the process of closing, do nothing
  }
  
  /**
   * Returns an enum indicating the current status of the connection.
   * @return a {@link ReadyState} value
   */
  public ReadyState getState() {
    return readyState.get();
  }

  /**
   * Drops the current stream connection (if any) and permanently shuts down the EventSource.
   */
  @Override
  public void close() {
    ReadyState currentState = readyState.getAndSet(SHUTDOWN);
    logger.debug("readyState change: {} -> {}", currentState, SHUTDOWN);
    if (currentState == SHUTDOWN) {
      return;
    }
    
    closeCurrentStream(currentState);

    eventExecutor.shutdown();
    streamExecutor.shutdown();

    // COVERAGE: these null guards are here for safety but in practice the values are never null and there
    // is no way to cause them to be null in unit tests
    if (client.connectionPool() != null) {
      client.connectionPool().evictAll();
    }
    if (client.dispatcher() != null) {
      client.dispatcher().cancelAll();
      if (client.dispatcher().executorService() != null) {
        client.dispatcher().executorService().shutdownNow();
      }
    }
  }

  /**
   * Block until all underlying threads have terminated and resources have been released.
   *
   * @param timeout maximum time to wait for everything to shut down
   * @return {@code true} if all thread pools terminated within the specified timeout, {@code false} otherwise.
   * @throws InterruptedException if this thread is interrupted while blocking
   */
  public boolean awaitClosed(final Duration timeout) throws InterruptedException {
    final long deadline = System.currentTimeMillis() + timeout.toMillis();

    if (!eventExecutor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
      return false;
    }

    long shutdownTimeoutMills = Math.max(0, deadline - System.currentTimeMillis());
    if (!streamExecutor.awaitTermination(shutdownTimeoutMills, TimeUnit.MILLISECONDS)) {
      return false; // COVERAGE: this condition can't be reproduced in unit tests
    }

    if (client.dispatcher().executorService() != null) {
      shutdownTimeoutMills = Math.max(0, deadline - System.currentTimeMillis());
      if (!client.dispatcher().executorService().awaitTermination(shutdownTimeoutMills, TimeUnit.MILLISECONDS)) {
        return false; // COVERAGE: this condition can't be reproduced in unit tests
      }
    }

    return true;
  }
  
  private void closeCurrentStream(ReadyState previousState) {
    if (previousState == ReadyState.OPEN) {
      handler.onClosed();
    }

    if (call != null) {
      // The call.cancel() must precede the bufferedSource.close().
      // Otherwise, an IllegalArgumentException "Unbalanced enter/exit" error is thrown by okhttp.
      // https://github.com/google/ExoPlayer/issues/1348
      call.cancel();
      logger.debug("call cancelled");
    }
  }
  
  Request buildRequest() {
    Request.Builder builder = new Request.Builder()
        .headers(headers)
        .url(url)
        .method(method, body);

    if (lastEventId != null && !lastEventId.isEmpty()) {
      builder.addHeader("Last-Event-ID", lastEventId);
    }
    
    Request request = builder.build();
    return requestTransformer == null ? request : requestTransformer.transformRequest(request);
  }

  private void run() {
    AtomicLong connectedTime = new AtomicLong();
    int reconnectAttempts = 0;
    
    try {
      while (!Thread.currentThread().isInterrupted() && readyState.get() != SHUTDOWN) {
        if (reconnectAttempts == 0) {
          reconnectAttempts++;
        } else {
          reconnectAttempts = maybeReconnectDelay(reconnectAttempts, connectedTime.get());
        }
        newConnectionAttempt(connectedTime);
      }
    } catch (RejectedExecutionException ignored) {
      // COVERAGE: there is no way to simulate this condition in unit tests
      call = null;
      logger.debug("Rejected execution exception ignored: {}", ignored);
      // During shutdown, we tried to send a message to the event handler
      // Do not reconnect; the executor has been shut down
    }
  }

  private int maybeReconnectDelay(int reconnectAttempts, long connectedTime) {
    if (reconnectTime.isZero() || reconnectTime.isNegative()) {
      return reconnectAttempts;
    }
    
    int counter = reconnectAttempts;
    
    // Reset the backoff if we had a successful connection that stayed good for at least
    // backoffResetThresholdMs milliseconds.
    if (connectedTime > 0 && (System.currentTimeMillis() - connectedTime) >= backoffResetThreshold.toMillis()) {
      counter = 1;
    }
    
    try {
      Duration sleepTime = backoffWithJitter(counter);
      logger.info("Waiting {} milliseconds before reconnecting...", sleepTime.toMillis());
      Thread.sleep(sleepTime.toMillis());
    } catch (InterruptedException ignored) { // COVERAGE: no way to cause this in unit tests
    }
    
    return ++counter;
  }
  
  private void newConnectionAttempt(AtomicLong connectedTime) {
    ConnectionErrorHandler.Action errorHandlerAction = ConnectionErrorHandler.Action.PROCEED;

    ReadyState stateBeforeConnecting = readyState.getAndSet(CONNECTING);
    logger.debug("readyState change: {} -> {}", stateBeforeConnecting, CONNECTING);
    
    connectedTime.set(0);
    call = client.newCall(buildRequest());
    
    try {
      try (Response response = call.execute()) {
        if (response.isSuccessful()) {
          connectedTime.set(System.currentTimeMillis());
          handleSuccessfulResponse(response);

          // If handleSuccessfulResponse returned without throwing an exception, it means the server
          // ended the stream. We don't call the handler's onError() method in this case; but we will
          // call the ConnectionErrorHandler with an EOFException, in case it wants to do something
          // special in this scenario (like choose not to retry the connection). However, first we
          // should check the state in case we've been deliberately closed from elsewhere.
          ReadyState state = readyState.get();
          if (state != SHUTDOWN && state != CLOSED) {
            logger.warn("Connection unexpectedly closed");
            errorHandlerAction = connectionErrorHandler.onConnectionError(new EOFException());
          }
        } else {
          logger.debug("Unsuccessful response: {}", response);
          errorHandlerAction = dispatchError(new UnsuccessfulResponseException(response.code()));
        }
      }
    } catch (IOException e) {
      ReadyState state = readyState.get();
      if (state != SHUTDOWN && state != CLOSED) {
        logger.debug("Connection problem: {}", e);
        errorHandlerAction = dispatchError(e);
      }
    } finally {
      if (errorHandlerAction == ConnectionErrorHandler.Action.SHUTDOWN) {
        logger.info("Connection has been explicitly shut down by error handler");
        close();
      } else {
        boolean wasOpen = readyState.compareAndSet(OPEN, CLOSED);
        boolean wasConnecting = readyState.compareAndSet(CONNECTING, CLOSED);
        if (wasOpen) {
          logger.debug("readyState change: {} -> {}", OPEN, CLOSED);  
          handler.onClosed(); 
        } else if (wasConnecting) {
          logger.debug("readyState change: {} -> {}", CONNECTING, CLOSED);  
        }
      }
    }
  }
  
  // Read the response body as an SSE stream and dispatch each received event to the EventHandler.
  // This function exits in one of two ways:
  // 1. A normal return - this means the response simply ended.
  // 2. Throwing an IOException - there was an unexpected connection failure.
  private void handleSuccessfulResponse(Response response) throws IOException {
    ConnectionHandler connectionHandler = new ConnectionHandler() {
      @Override
      public void setReconnectionTime(Duration reconnectionTime) {
        EventSource.this.setReconnectionTime(reconnectionTime);
      }
      
      @Override
      public void setLastEventId(String lastEventId) {
        EventSource.this.setLastEventId(lastEventId);
      }
    };

    ReadyState previousState = readyState.getAndSet(OPEN);
    if (previousState != CONNECTING) {
      // COVERAGE: there is no way to simulate this condition in unit tests
      logger.warn("Unexpected readyState change: " + previousState + " -> " + OPEN);
    } else {
      logger.debug("readyState change: {} -> {}", previousState, OPEN);
    }
    logger.info("Connected to EventSource stream.");
    handler.onOpen();
    
    EventParser parser = new EventParser(
        response.body().byteStream(),
        url.uri(),
        handler,
        connectionHandler,
        readBufferSize,
        streamEventData,
        expectFields,
        logger
        );
    
    // COVERAGE: the isInterrupted() condition is not encountered in unit tests and it's unclear if it can ever happen
    while (!Thread.currentThread().isInterrupted() && !parser.isEof()) {
      parser.processStream();
    }
  }
  
  private ConnectionErrorHandler.Action dispatchError(Throwable t) {
    ConnectionErrorHandler.Action action = connectionErrorHandler.onConnectionError(t);
    if (action != ConnectionErrorHandler.Action.SHUTDOWN) {
      handler.onError(t);
    }
    return action;
  }

  Duration backoffWithJitter(int reconnectAttempts) {
    long maxTimeLong = Math.min(maxReconnectTime.toMillis(), reconnectTime.toMillis() * pow2(reconnectAttempts));
    // 2^31 milliseconds is much longer than any reconnect time we would reasonably want to use, so we can pin this to int
    int maxTimeInt = maxTimeLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)maxTimeLong;
    return Duration.ofMillis(maxTimeInt / 2 + jitter.nextInt(maxTimeInt) / 2);
  }

  private static Headers addDefaultHeaders(Headers custom) {
    Headers.Builder builder = new Headers.Builder();

    for (String name : defaultHeaders.names()) {
      if (!custom.names().contains(name)) { // skip the default if they set any custom values for this key
        for (String value: defaultHeaders.values(name)) {
          builder.add(name, value);         
        }
      }
    }

    for (String name : custom.names()) {
      for (String value : custom.values(name)) {
        builder.add(name, value);
      }
    }

    return builder.build();
  }

  // setReconnectionTime and setLastEventId are used only by our internal ConnectionHandler, in response
  // to stream events. From an application's point of view, these properties can only be set at
  // configuration time via the builder.
  private void setReconnectionTime(Duration reconnectionTime) {
    this.reconnectTime = reconnectionTime;
  }

  private void setLastEventId(String lastEventId) {
    this.lastEventId = lastEventId;
  }

  /**
   * Returns the ID value, if any, of the last known event.
   * <p>
   * This can be set initially with {@link Builder#lastEventId(String)}, and is updated whenever an event
   * is received that has an ID. Whether event IDs are supported depends on the server; it may ignore this
   * value.
   * 
   * @return the last known event ID, or null
   * @see Builder#lastEventId(String)
   * @since 2.0.0
   */
  public String getLastEventId() {
    return lastEventId;
  }
  
  /**
   * Returns the current stream endpoint as an OkHttp HttpUrl.
   * 
   * @return the endpoint URL
   * @since 1.9.0
   * @see #getUri()
   */
  public HttpUrl getHttpUrl() {
    return this.url;
  }
  
  /**
   * Returns the current stream endpoint as a java.net.URI.
   * 
   * @return the endpoint URI
   * @see #getHttpUrl()
   */
  public URI getUri() {
    return this.url.uri();
  }

  /**
   * Interface for an object that can modify the network request that the EventSource will make.
   * Use this in conjunction with {@link EventSource.Builder#requestTransformer(EventSource.RequestTransformer)}
   * if you need to set request properties other than the ones that are already supported by the builder (or if,
   * for whatever reason, you need to determine the request properties dynamically rather than setting them
   * to fixed values initially). For example:
   * <pre><code>
   * public class RequestTagger implements EventSource.RequestTransformer {
   *   public Request transformRequest(Request input) {
   *     return input.newBuilder().tag("hello").build();
   *   }
   * }
   * 
   * EventSource es = new EventSource.Builder(handler, uri).requestTransformer(new RequestTagger()).build();
   * </code></pre>
   * 
   * @since 1.9.0
   */
  public static interface RequestTransformer {
    /**
     * Returns a request that is either the same as the input request or based on it. When
     * this method is called, EventSource has already set all of its standard properties on
     * the request.
     * 
     * @param input the original request
     * @return the request that will be used
     */
    public Request transformRequest(Request input);
  }
  
  /**
   * Builder for {@link EventSource}.
   */
  public static final class Builder {
    private String name;
    private Duration reconnectTime = DEFAULT_RECONNECT_TIME;
    private Duration maxReconnectTime = DEFAULT_MAX_RECONNECT_TIME;
    private Duration backoffResetThreshold = DEFAULT_BACKOFF_RESET_THRESHOLD;
    private String lastEventId;
    private final HttpUrl url;
    private final EventHandler handler;
    private ConnectionErrorHandler connectionErrorHandler = ConnectionErrorHandler.DEFAULT;
    private Integer threadPriority = null;
    private Headers headers = Headers.of();
    private Proxy proxy;
    private Authenticator proxyAuthenticator = null;
    private String method = "GET";
    private RequestTransformer requestTransformer = null;
    private RequestBody body = null;
    private OkHttpClient.Builder clientBuilder;
    private int readBufferSize = DEFAULT_READ_BUFFER_SIZE;
    private LDLogger logger = null;
    private String loggerBaseName = null;
    private int maxEventTasksInFlight = 0;
    private boolean streamEventData;
    private Set<String> expectFields = null;
    
    /**
     * Creates a new builder.
     * 
     * @param handler the event handler
     * @param uri the endpoint as a java.net.URI
     * @throws IllegalArgumentException if either argument is null, or if the endpoint is not HTTP or HTTPS
     */
    public Builder(EventHandler handler, URI uri) {
      this(handler, uri == null ? null : HttpUrl.get(uri));
    }

    /**
     * Creates a new builder.
     * 
     * @param handler the event handler
     * @param url the endpoint as an OkHttp HttpUrl
     * @throws IllegalArgumentException if either argument is null, or if the endpoint is not HTTP or HTTPS
     * 
     * @since 1.9.0
     */
    public Builder(EventHandler handler, HttpUrl url) {
      if (handler == null) {
        throw new IllegalArgumentException("handler must not be null");
      }
      if (url == null) {
        throw new IllegalArgumentException("URI/URL must not be null");
      }
      this.url = url;
      this.handler = handler;
      this.clientBuilder = createInitialClientBuilder();
    }
    
    private static OkHttpClient.Builder createInitialClientBuilder() {
      OkHttpClient.Builder b = new OkHttpClient.Builder()
          .connectionPool(new ConnectionPool(1, 1, TimeUnit.SECONDS))
          .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
          .readTimeout(DEFAULT_READ_TIMEOUT)
          .writeTimeout(DEFAULT_WRITE_TIMEOUT)
          .retryOnConnectionFailure(true);
      try {
        b.sslSocketFactory(new ModernTLSSocketFactory(), defaultTrustManager());
      } catch (GeneralSecurityException e) {
        // TLS is not available, so don't set up the socket factory, swallow the exception
        // COVERAGE: There is no way to cause this to happen in unit tests
      }
      return b;
    }
    
    /**
     * Set the HTTP method used for this EventSource client to use for requests to establish the EventSource.
     * <p>
     * Defaults to "GET".
     *
     * @param method the HTTP method name; if null or empty, "GET" is used as the default
     * @return the builder
     */
    public Builder method(String method) {
      this.method = (method != null && method.length() > 0) ? method.toUpperCase() : "GET";
      return this;
    }

    /**
     * Sets the request body to be used for this EventSource client to use for requests to establish the EventSource.
     * 
     * @param body the body to use in HTTP requests
     * @return the builder
     */
    public Builder body(RequestBody body) {
      this.body = body;
      return this;
    }

    /**
     * Specifies an object that will be used to customize outgoing requests. See {@link RequestTransformer} for details.
     * 
     * @param requestTransformer the transformer object
     * @return the builder
     * 
     * @since 1.9.0
     */
    public Builder requestTransformer(RequestTransformer requestTransformer) {
      this.requestTransformer = requestTransformer;
      return this;
    }
    
    /**
     * Set the name for this EventSource client to be used when naming thread pools (and, possibly, the logger).
     * This is mainly useful when multiple EventSource clients exist within the same process.
     * <p>
     * The name only affects logging when using the default SLF4J integration; if you have specified a custom
     * {@link #logger(LDLogger)}, the logging facade has its own way to specify a logger name. 
     *
     * @param name the name (without any whitespaces)
     * @return the builder
     */
    public Builder name(String name) {
      this.name = name;
      return this;
    }

    /**
     * Sets the ID value of the last event received.
     * <p>
     * This will be sent to the remote server on the initial connection request, allowing the server to
     * skip past previously sent events if it supports this behavior. Once the connection is established,
     * this value will be updated whenever an event is received that has an ID. Whether event IDs are
     * supported depends on the server; it may ignore this value.
     * 
     * @param lastEventId the last event identifier
     * @return the builder
     * @since 2.0.0
     */
    public Builder lastEventId(String lastEventId) {
      this.lastEventId = lastEventId;
      return this;
    }
    
    /**
     * Sets the minimum delay between connection attempts. The actual delay may be slightly less or
     * greater, since there is a random jitter. When there is a connection failure, the delay will
     * start at this value and will increase exponentially up to the {@link #maxReconnectTime(Duration)}
     * value with each subsequent failure, unless it is reset as described in
     * {@link Builder#backoffResetThreshold(Duration)}.
     * 
     * @param reconnectTime the minimum delay; null to use the default
     * @return the builder
     * @see EventSource#DEFAULT_RECONNECT_TIME
     */
    public Builder reconnectTime(Duration reconnectTime) {
      this.reconnectTime = reconnectTime == null ? DEFAULT_RECONNECT_TIME : reconnectTime;
      return this;
    }

    /**
     * Sets the maximum delay between connection attempts. See {@link #reconnectTime(Duration)}.
     * The default value is 30 seconds.
     * 
     * @param maxReconnectTime the maximum delay; null to use the default
     * @return the builder
     * @see EventSource#DEFAULT_MAX_RECONNECT_TIME
     */
    public Builder maxReconnectTime(Duration maxReconnectTime) {
      this.maxReconnectTime = maxReconnectTime == null ? DEFAULT_MAX_RECONNECT_TIME : maxReconnectTime;
      return this;
    }

    /**
     * Sets the minimum amount of time that a connection must stay open before the EventSource resets its
     * backoff delay. If a connection fails before the threshold has elapsed, the delay before reconnecting
     * will be greater than the last delay; if it fails after the threshold, the delay will start over at
     * the initial minimum value. This prevents long delays from occurring on connections that are only
     * rarely restarted.
     *   
     * @param backoffResetThreshold the minimum time that a connection must stay open to avoid resetting
     *   the delay; null to use the default 
     * @return the builder
     * @see EventSource#DEFAULT_BACKOFF_RESET_THRESHOLD
     */
    public Builder backoffResetThreshold(Duration backoffResetThreshold) {
      this.backoffResetThreshold = backoffResetThreshold == null ? DEFAULT_BACKOFF_RESET_THRESHOLD : backoffResetThreshold;
      return this;
    }

    /**
     * Set the headers to be sent when establishing the EventSource connection.
     *
     * @param headers headers to be sent with the EventSource request
     * @return the builder
     */
    public Builder headers(Headers headers) {
      this.headers = headers;
      return this;
    }

    /**
     * Set a custom HTTP client that will be used to make the EventSource connection.
     * If you're setting this along with other connection-related items (ie timeouts, proxy),
     * you should do this first to avoid overwriting values.
     *
     * @param client the HTTP client
     * @return the builder
     */
    public Builder client(OkHttpClient client) {
      this.clientBuilder = client.newBuilder();
      return this;
    }

    /**
     * Set the HTTP proxy address to be used to make the EventSource connection
     *
     * @param proxyHost the proxy hostname
     * @param proxyPort the proxy port
     * @return the builder
     */
    public Builder proxy(String proxyHost, int proxyPort) {
      proxy = new Proxy(Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
      return this;
    }

    /**
     * Set the {@link Proxy} to be used to make the EventSource connection.
     *
     * @param proxy the proxy
     * @return the builder
     */
    public Builder proxy(Proxy proxy) {
      this.proxy = proxy;
      return this;
    }

    /**
     * Sets the Proxy Authentication mechanism if needed. Defaults to no auth.
     *
     * @param proxyAuthenticator the authentication mechanism
     * @return the builder
     */
    public Builder proxyAuthenticator(Authenticator proxyAuthenticator) {
      this.proxyAuthenticator = proxyAuthenticator;
      return this;
    }

    /**
     * Sets the connection timeout.
     *
     * @param connectTimeout the connection timeout; null to use the default
     * @return the builder
     * @see EventSource#DEFAULT_CONNECT_TIMEOUT
     */
    public Builder connectTimeout(Duration connectTimeout) {
      this.clientBuilder.connectTimeout(connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout);
      return this;
    }

    /**
     * Sets the write timeout.
     *
     * @param writeTimeout the write timeout; null to use the default
     * @return the builder
     * @see EventSource#DEFAULT_WRITE_TIMEOUT
     */
    public Builder writeTimeout(Duration writeTimeout) {
      this.clientBuilder.writeTimeout(writeTimeout == null ? DEFAULT_WRITE_TIMEOUT : writeTimeout);
      return this;
    }

    /**
     * Sets the read timeout. If a read timeout happens, the {@code EventSource}
     * will restart the connection.
     *
     * @param readTimeout the read timeout; null to use the default
     * @return the builder
     * @see EventSource#DEFAULT_READ_TIMEOUT
     */
    public Builder readTimeout(Duration readTimeout) {
      this.clientBuilder.readTimeout(readTimeout == null ? DEFAULT_READ_TIMEOUT : readTimeout); 
      return this;
    }

    /**
     * Sets the {@link ConnectionErrorHandler} that should process connection errors.
     *
     * @param handler the error handler
     * @return the builder
     */
    public Builder connectionErrorHandler(ConnectionErrorHandler handler) {
      this.connectionErrorHandler = handler;
      return this;
    }

    /**
     * Specifies the priority for threads created by {@code EventSource}.
     * <p>
     * If this is left unset, or set to {@code null}, threads will inherit the default priority
     * provided by {@code Executors.defaultThreadFactory()}.
     * 
     * @param threadPriority the thread priority, or null to ue the default
     * @return the builder
     * @since 2.2.0
     */
    public Builder threadPriority(Integer threadPriority) {
      this.threadPriority = threadPriority;
      return this;
    }
    
    /**
     * Specifies any type of configuration actions you want to perform on the OkHttpClient builder.
     * <p>
     * {@link ClientConfigurer} is an interface with a single method, {@link ClientConfigurer#configure(okhttp3.OkHttpClient.Builder)},
     * that will be called with the {@link okhttp3.OkHttpClient.Builder} instance being used by EventSource.
     * In Java 8, this can be a lambda.
     * <p>
     * It is not guaranteed to be called in any particular order relative to other configuration
     * actions specified by this Builder, so if you are using more than one method, do not attempt
     * to overwrite the same setting in two ways.
     * <pre><code>
     *     // Java 8 example (lambda)
     *     eventSourceBuilder.clientBuilderActions(b -&gt; {
     *         b.sslSocketFactory(mySocketFactory, myTrustManager);
     *     });
     * 
     *     // Java 7 example (anonymous class)
     *     eventSourceBuilder.clientBuilderActions(new EventSource.Builder.ClientConfigurer() {
     *         public void configure(OkHttpClient.Builder v) {
     *             b.sslSocketFactory(mySocketFactory, myTrustManager);
     *         }
     *     });
     * </code></pre>
     * @param configurer a ClientConfigurer (or lambda) that will act on the HTTP client builder
     * @return the builder
     * @since 1.10.0
     */
    public Builder clientBuilderActions(ClientConfigurer configurer) {
      configurer.configure(clientBuilder);
      return this;
    }
    
    /**
     * Specifies the fixed size of the buffer that EventSource uses to parse incoming data.
     * <p>
     * EventSource allocates a single buffer to hold data from the stream as it scans for
     * line breaks. If no lines of data from the stream exceed this size, it will keep reusing
     * the same space; if a line is longer than this size, it creates a temporary
     * {@code ByteArrayOutputStream} to accumulate data for that line, which is less efficient.
     * Therefore, if an application expects to see many lines in the stream that are longer
     * than {@link EventSource#DEFAULT_READ_BUFFER_SIZE}, it can specify a larger buffer size
     * to avoid unnecessary heap allocations.
     * 
     * @param readBufferSize the buffer size
     * @return the builder
     * @throws IllegalArgumentException if the size is less than or equal to zero
     * @see EventSource#DEFAULT_READ_BUFFER_SIZE
     * @since 2.4.0
     */
    public Builder readBufferSize(int readBufferSize) {
      if (readBufferSize <= 0) {
        throw new IllegalArgumentException("readBufferSize must be greater than zero");
      }
      this.readBufferSize = readBufferSize;
      return this;
    }
    
    /**
     * Specifies a custom logger to receive EventSource logging.
     * <p>
     * This has been superseded by {@link #logger(LDLogger)}. The
     * <a href="https://github.com/launchdarkly/java-logging">com.launchdarkly.logging</a>
     * facade used by that method provides many options for customizing logging behavior.
     * The {@link Logger} interface defined by {@code okhttp-eventsource} will be removed
     * in a future major version release.
     * <p>
     * If you do not provide a logger, the default is to send log output to SLF4J.
     * 
     * @param logger a {@link Logger} implementation, or null to use the default (SLF4J)
     * @return the builder
     * @since 2.3.0
     * @deprecated use {@link #logger(LDLogger)}
     */
    @Deprecated
    public Builder logger(Logger logger) {
      this.logger = logger == null ? null : LoggerBridge.wrapLogger(logger);
      return this;
    }

    /**
     * Specifies a custom logger to receive EventSource logging.
     * <p>
     * This method uses the {@link LDLogger} type from
     * <a href="https://github.com/launchdarkly/java-logging">com.launchdarkly.logging</a>, a
     * facade that provides several logging implementations as well as the option to forward
     * log output to SLF4J or another framework. Here is an example of configuring it to use
     * the basic console logging implementation, and to tag the output with the name "logname":
     * <pre><code>
     *   // import com.launchdarkly.logging.*;
     *   
     *   builder.logger(
     *      LDLogger.withAdapter(Logs.basic(), "logname") 
     *   );
     * </code></pre>
     * <p>
     * If you do not provide a logger, the default is to send log output to SLF4J, and to use
     * a logger name based on the {@link #loggerBaseName(String)} and {@link #name(String)}
     * settings. In a future major version, the default behavior may be changed so that this
     * library no longer has a mandatory dependency on SLF4J.
     * 
     * @param logger an {@link LDLogger} implementation, or null to use the default (SLF4J)
     * @return the builder
     * @since 2.7.0
     */
    public Builder logger(LDLogger logger) {
      this.logger = logger;
      return this;
    }

    /**
     * Specifies the base logger name to use for SLF4J logging.
     * <p>
     * The default is {@code com.launchdarkly.eventsource.EventSource}, plus any name suffix specified
     * by {@link #name(String)}. If you instead use {@link #logger(Logger)} to specify some other log
     * destination rather than SLF4J, this name is unused.
     * <p>
     * This method is now deprecated, because the logging facade used by {@link #logger(LDLogger)}
     * makes it easy to set a logger name for SLF4J, as in this example:
     * <pre><code>
     *   // import com.launchdarkly.logging.*;
     *   
     *   builder.logger(
     *      LDLogger.withAdapter(LDSLF4J.adapter(), "my.preferred.log.name") 
     *   );
     * </code></pre>
     * 
     * @param loggerBaseName the SLF4J logger name, or null to use the default
     * @return the builder
     * @since 2.3.0
     * @deprecated use {@link #logger(LDLogger)}
     */
    @Deprecated
    public Builder loggerBaseName(String loggerBaseName) {
      this.loggerBaseName = loggerBaseName;
      return this;
    }

    /**
     * Specifies the maximum number of tasks that can be "in-flight" for the thread executing {@link EventHandler}.
     * A semaphore will be used to artificially constrain the number of tasks sitting in the queue fronting the
     * event handler thread. When this limit is reached the stream thread will block until the backpressure passes.
     * <p>
     * For backward compatibility reasons the default is "unbounded".
     *
     * @param maxEventTasksInFlight the maximum number of tasks/messages that can be in-flight for the {@code EventHandler}
     * @return the builder
     * @since 2.5.0
     */
    public Builder maxEventTasksInFlight(int maxEventTasksInFlight) {
      this.maxEventTasksInFlight = maxEventTasksInFlight;
      return this;
    }
    
    /**
     * Specifies whether EventSource should send a {@link MessageEvent} to the handler as soon as it receives the
     * beginning of the event data, allowing the handler to read the data incrementally with
     * {@link MessageEvent#getDataReader()}.
     * <p>
     * The default for this property is {@code false}, meaning that EventSource will always read the entire event into
     * memory before dispatching it to the handler.
     * <p>
     * If you set it to {@code true}, it will instead call the handler as soon as it sees a {@code data} field--
     * setting {@link MessageEvent#getDataReader()} to a {@link java.io.Reader} that reads directly from the data as
     * it arrives. The EventSource will perform any necessary parsing under the covers, so that for instance if there
     * are multiple {@code data:} lines in the event, the {@link java.io.Reader} will emit a newline character between
     * each and will not see the "data:" field names. The {@link java.io.Reader} will report "end of stream" as soon
     * as the event is terminated normally by a blank line.
     * <p>
     * This mode is designed for applications that expect very large data items to be delivered over SSE. Use it
     * with caution, since there are several limitations:
     * <ul>
     * <li> EventSource cannot continue processing further events on the stream until the handler's
     * {@link EventHandler#onMessage(String, MessageEvent)} method has returned. </li>
     * <li> The {@link MessageEvent} is constructed as soon as a {@code data:} field appears, so it will only include
     * fields that appeared <i>before</i> {@code data:}. In other words, if the SSE server happens to send {@code data:}
     * first and {@code event:} second, {@link MessageEvent#getEventName()} will <i>not</i> contain the value of
     * {@code event:} but will be {@link MessageEvent#DEFAULT_EVENT_NAME} instead; similarly, an {@code id:} field will
     * be ignored if it appears after {@code data:} in this mode. Therefore, you should only use this mode if the
     * server's behavior is predictable in this regard.</li>  
     * <li> The SSE protocol specifies that an event should be processed only if it is terminated by a blank line, but
     * in this mode the handler will receive the event as soon as a {@code data:} field appears-- so, if the stream
     * happens to cut off abnormally without a trailing blank line, technically you will be receiving an incomplete
     * event that should have been ignored. </li>
     * </ul>  
     * 
     * @param streamEventData true if events should be dispatched immediately with asynchronous data rather than
     *   read fully before dispatch 
     * @return the builder
     * @see #expectFields(String...)
     * @since 2.6.0
     */
    public Builder streamEventData(boolean streamEventData) {
      this.streamEventData = streamEventData;
      return this;
    }

    /**
     * Specifies that the application expects the server to send certain fields in every event.
     * <p>
     * This setting makes no difference unless you have enabled {@link #streamEventData(boolean)} mode. In that case,
     * it causes EventSource to only use the streaming data mode for an event <i>if</i> the specified fields have
     * already been received; otherwise, it will buffer the whole event (as if {@link #streamEventData(boolean)} had
     * not been enabled), to ensure that those fields are not lost if they appear after the {@code data:} field.
     * <p>
     * For instance, if you had called {@code expectFields("event")}, then EventSource would be able to use streaming
     * data mode for the following SSE response--
     * <pre><code>
     *     event: hello
     *     data: here is some very long streaming data
     * </code></pre>
     * <p>
     * --but it would buffer the full event if the server used the opposite order:
     * <pre><code>
     *     data: here is some very long streaming data
     *     event: hello
     * </code></pre>
     * <p>
     * Such behavior is not automatic because in some applications, there might never be an {@code event:} field,
     * and EventSource has no way to anticipate this.
     * 
     * @param fieldNames a list of SSE field names (case-sensitive; any names other than "event" and "id" are ignored)
     * @return the builder
     * @see #streamEventData(boolean)
     * @since 2.6.0
     */
    public Builder expectFields(String... fieldNames) {
      if (fieldNames == null || fieldNames.length == 0) {
        expectFields = null;
      } else {
        expectFields = new HashSet<>();
        for (String f: fieldNames) {
          if (f != null) {
            expectFields.add(f);
          }
        }
      }
      return this;
    }
    
    /**
     * Constructs an {@link EventSource} using the builder's current properties.
     * @return the new EventSource instance
     */
    public EventSource build() {
      if (proxy != null) {
        clientBuilder.proxy(proxy);
      }

      if (proxyAuthenticator != null) {
        clientBuilder.proxyAuthenticator(proxyAuthenticator);
      }

      return new EventSource(this);
    }

    protected OkHttpClient.Builder getClientBuilder() {
      return clientBuilder;
    }

    private static X509TrustManager defaultTrustManager() throws GeneralSecurityException {
      TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
              TrustManagerFactory.getDefaultAlgorithm());
      trustManagerFactory.init((KeyStore) null);
      TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
      if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
        // COVERAGE: There is no way to cause this to happen in unit tests
        throw new IllegalStateException("Unexpected default trust managers:"
                + Arrays.toString(trustManagers));
      }
      return (X509TrustManager) trustManagers[0];
    }
    
    /**
     * An interface for use with {@link EventSource.Builder#clientBuilderActions(ClientConfigurer)}.
     * @since 1.10.0
     */
    public static interface ClientConfigurer {
      /**
       * This method is called with the OkHttp {@link okhttp3.OkHttpClient.Builder} that will be used for
       * the EventSource, allowing you to call any configuration methods you want.
       * @param builder the client builder
       */
      public void configure(OkHttpClient.Builder builder);
    }
  }
}
