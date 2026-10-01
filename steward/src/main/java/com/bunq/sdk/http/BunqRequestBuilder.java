package com.bunq.sdk.http;

import com.bunq.sdk.exception.BunqException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.Getter;
import lombok.Setter;
import okhttp3.CacheControl;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * A patched copy of the bunq SDK's request builder, in the library's own package so it wins on the classpath.
 *
 * It leaves the final {@code delete()} of OkHttp 5 alone; re-check it on every SDK or OkHttp bump.
 */
@Getter
@Setter
public class BunqRequestBuilder extends Request.Builder {

    private static final String ERROR_BODY_IS_OF_UNEXPECTED_INSTANCE = "Body is of unexpected instance.";

    private HttpUrl url;
    private HttpMethod method;
    private BunqRequestBody body;
    private final List<BunqBasicHeader> allHeader;

    public BunqRequestBuilder() {
        this.allHeader = new ArrayList<>();
    }

    @Override
    public BunqRequestBuilder url(final HttpUrl url) {
        this.url = url;
        return (BunqRequestBuilder) super.url(url);
    }

    @Override
    public BunqRequestBuilder method(final String method, final RequestBody body) {
        final RequestBody bodyToPassToSuper;
        if (body instanceof BunqRequestBody bunqRequestBody) {
            bodyToPassToSuper = bunqRequestBody.getRequestBody();
        } else if (body == null) {
            bodyToPassToSuper = null;
        } else {
            throw new BunqException(ERROR_BODY_IS_OF_UNEXPECTED_INSTANCE);
        }
        this.method = HttpMethod.createFromMethodString(method.toUpperCase(Locale.ROOT));
        this.body = (BunqRequestBody) body;
        return (BunqRequestBuilder) super.method(method, bodyToPassToSuper);
    }

    @Override
    public BunqRequestBuilder url(final String url) {
        return (BunqRequestBuilder) super.url(url);
    }

    @Override
    public BunqRequestBuilder url(final URL url) {
        return (BunqRequestBuilder) super.url(url);
    }

    private void addToAllHeader(final String name, final String value) {
        final BunqHeader header = BunqHeader.parseHeaderOrNull(name);
        if (header != null) {
            this.allHeader.add(new BunqBasicHeader(header, value));
        }
    }

    @Override
    public BunqRequestBuilder header(final String name, final String value) {
        addToAllHeader(name, value);
        return (BunqRequestBuilder) super.header(name, value);
    }

    @Override
    public BunqRequestBuilder addHeader(final String name, final String value) {
        addToAllHeader(name, value);
        return (BunqRequestBuilder) super.addHeader(name, value);
    }

    @Override
    public BunqRequestBuilder removeHeader(final String name) {
        final List<BunqBasicHeader> allHeaderToRemove = new ArrayList<>();
        for (final BunqBasicHeader basicHeader : this.allHeader) {
            if (basicHeader.getName().equals(name)) {
                allHeaderToRemove.add(basicHeader);
            }
        }
        this.allHeader.removeAll(allHeaderToRemove);
        return (BunqRequestBuilder) super.removeHeader(name);
    }

    @Override
    public BunqRequestBuilder cacheControl(final CacheControl cacheControl) {
        return (BunqRequestBuilder) super.cacheControl(cacheControl);
    }

    @Override
    public BunqRequestBuilder get() {
        return (BunqRequestBuilder) super.get();
    }

    @Override
    public BunqRequestBuilder head() {
        return (BunqRequestBuilder) super.head();
    }

    @Override
    public BunqRequestBuilder post(final RequestBody body) {
        return (BunqRequestBuilder) super.post(body);
    }

    @Override
    public BunqRequestBuilder delete(final RequestBody body) {
        return (BunqRequestBuilder) super.delete(body);
    }

    // delete() is final in OkHttp 5; callers fall back to super, which passes a null body.

    @Override
    public BunqRequestBuilder put(final RequestBody body) {
        return (BunqRequestBuilder) super.put(body);
    }

    @Override
    public BunqRequestBuilder patch(final RequestBody body) {
        return (BunqRequestBuilder) super.patch(body);
    }

    @Override
    public BunqRequestBuilder tag(final Object tag) {
        return (BunqRequestBuilder) super.tag(tag);
    }
}
