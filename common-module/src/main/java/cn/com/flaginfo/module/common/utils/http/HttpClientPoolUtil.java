package cn.com.flaginfo.module.common.utils.http;

import cn.com.flaginfo.module.common.domain.config.HttpClientPoolConfig;
import com.alibaba.fastjson.JSONObject;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.*;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.*;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.LayeredConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author meng.liu
 */
@Slf4j
public class HttpClientPoolUtil {

    private String componentId;

    private HttpClientPoolConfig config;

    /**
     * 使用部分参数初始化连接池
     *
     * @param maxPoolSize
     * @param maxRoute
     * @param maxPreRoute
     */
    public HttpClientPoolUtil(int maxPoolSize, int maxRoute, int maxPreRoute) {
        this(HttpClientPoolConfig.builder().maxPoolSize(maxPoolSize)
                .maxRoute(maxRoute)
                .maxPreRout(maxPreRoute).build());
    }

    /**
     * 使用置顶配置初始化连接池
     *
     * @param config
     */
    public HttpClientPoolUtil(HttpClientPoolConfig config) {
        this();
        if (null == config) {
            log.warn("the parameter which named HttpClientPoolConfig is null, will be init HttpClientPoolUtil with default config.");
        } else {
            this.config = config;
        }
    }

    /**
     * 使用默认参数初始化连接池
     */
    public HttpClientPoolUtil() {
        this.config = HttpClientPoolConfig.builder().build();
        this.componentId = UUID.randomUUID().toString();
    }

    /**
     * 发送请求的客户端单例
     */
    private CloseableHttpClient httpClient;
    /**
     * 连接池管理类
     */
    private PoolingHttpClientConnectionManager manager;
    private ScheduledExecutorService monitorExecutor;
    /**
     * 相当于线程锁,用于线程安全
     */
    private final Object syncLock = new Object();

    /**
     * 对http请求进行基本设置
     *
     * @param httpRequestBase http请求
     */
    private void setupRequestConfig(HttpRequestBase httpRequestBase) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(config.getConnectTimeout())
                .setConnectTimeout(config.getConnectTimeout())
                .setSocketTimeout(config.getSocketTimeout()).build();
        httpRequestBase.setConfig(requestConfig);
    }

    private CloseableHttpClient getHttpClient(String url) {
        if (httpClient == null) {
            synchronized (syncLock) {
                if (httpClient == null) {
                    httpClient = this.createHttpClient(url);
                    monitorExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "Http-Client-Pool-Monitor-" + this.componentId);
                        thread.setDaemon(true);
                        return thread;
                    });
                    monitorExecutor.scheduleAtFixedRate(() -> {
                        manager.closeExpiredConnections();
                        manager.closeIdleConnections(config.getIdleTimeout(), TimeUnit.SECONDS);
                        if (log.isDebugEnabled()) {
                            log.debug("close expired and idle for over 5s connection");
                        }
                    }, config.getMonitorInterval(), config.getMonitorInterval(), TimeUnit.SECONDS);
                }
            }
        }
        return httpClient;
    }

    /**
     * 根据host和port构建httpclient实例
     *
     * @param url 请求url
     * @return
     */
    public CloseableHttpClient createHttpClient(String url) {
        UrlUtils.Url urlInfo = UrlUtils.parse(url);
        if (!urlInfo.isUrl()) {
            url = HttpConstants.HTTP_PROTOCOL + url;
            urlInfo = UrlUtils.parse(url);
        }
        if (!urlInfo.isUrl()) {
            throw new IllegalArgumentException("url formatter error : " + url);
        }
        ConnectionSocketFactory plainSocketFactory = PlainConnectionSocketFactory.getSocketFactory();
        LayeredConnectionSocketFactory sslSocketFactory = SSLConnectionSocketFactory.getSocketFactory();
        Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create().register(HttpConstants.PROTOCOL_HTTP, plainSocketFactory)
                .register(HttpConstants.PROTOCOL_HTTPS, sslSocketFactory)
                .build();

        manager = new PoolingHttpClientConnectionManager(registry);
        manager.setMaxTotal(config.getMaxPoolSize());
        manager.setDefaultMaxPerRoute(config.getMaxPreRout());
        HttpHost httpHost = new HttpHost(urlInfo.getHost(), urlInfo.getPort());
        manager.setMaxPerRoute(new HttpRoute(httpHost), config.getMaxPreRout());
        HttpRequestRetryHandler retryHandler = new DefaultHttpRequestRetryHandler(config.getRetryTimes(), false);
        return HttpClients.custom()
                .setConnectionManager(manager)
                .setRetryHandler(retryHandler).build();
    }

    /**
     * 设置请求头
     *
     * @param requestBase
     * @param header
     */
    private void setupRequestHeader(HttpRequestBase requestBase, Map<String, String> header) {
        if (null == requestBase) {
            return;
        }
        if (null == header) {
            requestBase.setHeader(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.APPLICATION_JSON);
        } else {
            header.forEach(requestBase::addHeader);
        }
    }


    /**
     * 设置entity请求的参数
     *
     * @param request
     * @param params
     */
    private void setupRequestParams(HttpRequestBase request, Map<String, String> header, Map<String, String> params) {
        if (!(request instanceof HttpEntityEnclosingRequest) || CollectionUtils.isEmpty(params)) {
            return;
        }
        AbstractHttpEntity entity;
        if (null == header ||
                header.getOrDefault(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.APPLICATION_JSON).equals(HttpConstants.APPLICATION_JSON)) {
            entity = new StringEntity(JSONObject.toJSONString(params), Consts.UTF_8);
            entity.setContentType(HttpConstants.APPLICATION_JSON);
        } else if (header.get(HttpConstants.HEADER_CONTENT_TYPE).equals(HttpConstants.APPLICATION_X_WWW_FORM_URLENCODED)) {
            List<NameValuePair> nvps = new ArrayList<>();
            Set<String> keys = params.keySet();
            for (String key : keys) {
                nvps.add(new BasicNameValuePair(key, params.get(key)));
            }
            entity = new UrlEncodedFormEntity(nvps, Consts.UTF_8);
            entity.setContentType(HttpConstants.APPLICATION_X_WWW_FORM_URLENCODED);
        } else {
            throw new IllegalArgumentException("Unexpected Content-Type : " + header.get(HttpConstants.HEADER_CONTENT_TYPE));
        }
        entity.setContentEncoding(HttpConstants.UTF_8);
        ((HttpEntityEnclosingRequest) request).setEntity(entity);
    }

    /**
     * 设置entity请求的参数
     *
     * @param request
     * @param params
     */
    private void setupRequestParams(HttpRequestBase request, String params) {
        if (!(request instanceof HttpEntityEnclosingRequest) || StringUtils.isBlank(params)) {
            return;
        }
        StringEntity entity = new StringEntity(params, Consts.UTF_8);
        entity.setContentType(HttpConstants.APPLICATION_JSON);
        entity.setContentEncoding(HttpConstants.UTF_8);
        ((HttpEntityEnclosingRequest) request).setEntity(entity);
    }

    /**
     * request请求
     *
     * @param url
     * @return
     */
    public Response get(String url) {
        return this.get(url, (Map<String, String>) null);
    }

    /**
     * request请求
     *
     * @param url
     * @param params
     * @return
     */
    public Response get(String url, Map<String, String> params) {
        return this.get(url, null, params);
    }

    /**
     * request请求
     *
     * @param url
     * @param params
     * @return
     */
    public Response get(String url, Map<String, String> header, Map<String, String> params) {
        url = HttpParamsUtils.appendUrlParams(url, params);
        return this.sampleRequest(RequestMethod.GET, url, header);
    }


    /**
     * request请求
     * 默认使用json格式
     *
     * @param url
     * @param params
     * @return
     */
    public Response post(String url, Map<String, String> params) {
        return this.post(url, null, params);
    }

    /**
     * request请求
     * 如果header不设置content-type, 默认使用json格式
     *
     * @param url
     * @param header
     * @param params
     * @return
     */
    public Response post(String url, Map<String, String> header, Map<String, String> params) {
        return this.sampleRequest(RequestMethod.POST, url, header, params);
    }


    /**
     * request请求
     *
     * @param url
     * @param params
     * @return
     */
    public Response post(String url, String params) {
        return this.post(url, null, params);
    }

    /**
     * request请求
     *
     * @param url
     * @param header
     * @param params
     * @return
     */
    public Response post(String url, Map<String, String> header, String params) {
        return this.sampleRequest(RequestMethod.POST, url, header, params);
    }

    /**
     * 通用请求
     *
     * @param method
     * @param url
     * @return
     */
    public Response sampleRequest(RequestMethod method, String url) {
        return this.sampleRequest(method, url, null);
    }

    /**
     * 通用请求
     *
     * @param method
     * @param url
     * @param header
     * @return
     */
    public Response sampleRequest(RequestMethod method, String url, Map<String, String> header) {
        return this.sampleRequest(method, url, null, Collections.emptyMap());
    }

    /**
     * 通用请求
     *
     * @param method
     * @param url
     * @param header
     * @param params
     * @return
     */
    public Response sampleRequest(RequestMethod method, String url, Map<String, String> header, Map<String, String> params) {
        HttpRequestBase request = this.createRequest(method, url);
        if (CollectionUtils.isEmpty(params)) {
            return this.doRequest(url, request, header);
        }
        this.setupRequestParams(request, header, params);
        return this.doRequest(url, request, header);
    }

    /**
     * 通用请求
     *
     * @param method
     * @param url
     * @param header
     * @param params
     * @return
     */
    public Response sampleRequest(RequestMethod method, String url, Map<String, String> header, String params) {
        HttpRequestBase request = this.createRequest(method, url);
        if (null == header) {
            header = new HashMap<>(2);
        }
        header.put(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.APPLICATION_JSON);
        this.setupRequestParams(request, params);
        return this.doRequest(url, request, header);
    }

    public Response post(String url, HttpPost post) {
        return this.doRequest(url, post);
    }

    public Response get(String url, HttpGet get) {
        return this.doRequest(url, get);
    }

    private Response doRequest(String url, HttpRequestBase request, Map<String, String> header) {
        this.setupRequestConfig(request);
        this.setupRequestHeader(request, header);
        return this.doRequest(url, request);
    }

    public Response doRequest(String url, HttpRequestBase request) {
        CloseableHttpResponse response = null;
        try {
            if (null == request.getURI()) {
                request.setURI(URI.create(url));
            }
            response = this.getHttpClient(url)
                    .execute(request, HttpClientContext.create());
            StatusLine responseState = response.getStatusLine();
            if (null == responseState) {
                log.error("http client request no response : {}", url);
                return null;
            }
            HttpEntity entity = response.getEntity();
            String result = EntityUtils.toString(entity, Consts.UTF_8);
            EntityUtils.consume(entity);
            return Response.builder().statusCode(responseState.getStatusCode()).body(result).build();
        } catch (IOException e) {
            log.error("http request error:{}", url, e);
        } finally {
            this.closeQuietly(response);
        }
        return null;
    }

    private void closeQuietly(Closeable closeable) {
        if (null != closeable) {
            try {
                closeable.close();
            } catch (IOException e) {
            }
        }
    }

    /**
     * 关闭连接池
     */
    public void closeConnectionPool() {
        log.warn("the HttpClientPoolUtil that id is {} will be close now.", this.componentId);
        try {
            httpClient.close();
            manager.close();
            monitorExecutor.shutdown();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private HttpRequestBase createRequest(RequestMethod requestMethod, String url) {
        HttpRequestBase request;
        switch (requestMethod) {
            case POST:
                request = new HttpPost(url);
                break;
            case GET:
                request = new HttpGet(url);
                break;
            case PUT:
                request = new HttpPut(url);
                break;
            case HEAD:
                request = new HttpHead(url);
                break;
            case PATCH:
                request = new HttpPatch(url);
                break;
            case DELETE:
                request = new HttpDelete(url);
                break;
            case TRACE:
                request = new HttpTrace(url);
                break;
            case OPTIONS:
                request = new HttpOptions(url);
                break;
            default:
                throw new IllegalArgumentException("Unexpected method : " + requestMethod);
        }
        return request;
    }

    @Getter
    @ToString
    @Builder
    public static class Response {
        /**
         * 响应状态码，该参数为Http协议状态
         */
        private int statusCode;

        /**
         * 响应内容
         */
        private String body;

        public <T> T getBody(Class<T> tClass) {
            if (StringUtils.isBlank(body)) {
                return null;
            } else {
                return JSONObject.parseObject(body, tClass);
            }
        }
    }
}