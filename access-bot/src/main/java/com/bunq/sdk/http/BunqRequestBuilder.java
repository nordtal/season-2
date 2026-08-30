package com.bunq.sdk.http;

import com.bunq.sdk.exception.BunqException;
import lombok.Getter;
import lombok.Setter;
import okhttp3.CacheControl;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.jetbrains.annotations.NotNull;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * A patched copy of {@code com.bunq.sdk.http.BunqRequestBuilder} from
 * {@code com.github.bunq:sdk_java}, sitting in the library's own package so it wins on the
 * classpath.
 *
 * <h2>Why it exists</h2>
 * <b>JDA pulls OkHttp 5 and the bunq SDK is written against OkHttp 3.</b> Two things broke, and
 * both are in this one class upstream:
 * <ul>
 *   <li>{@code Request.Builder.delete()} - the no-argument overload - is {@code final} in OkHttp
 *       5, and the SDK's original class overrides exactly that method. That is a
 *       {@code VerifyError} at class load, not a compile error you would notice.</li>
 *   <li>{@code okhttp3.internal.Util} no longer exists, and the original used it.</li>
 * </ul>
 * This version does not override {@code delete()} - it delegates to the superclass - and does not
 * touch OkHttp internals.
 *
 * <h2>Rules</h2>
 * <b>Do not delete this file.</b> It is required as long as JDA and the bunq SDK share a
 * classpath, which they do in this module and only in this module. <b>Re-check it against the
 * SDK's own sources on any bunq SDK or JDA bump</b>: it is a copy, so a fix upstream does not
 * reach us, and a change upstream that we do not mirror silently reverts to old behaviour.
 * Diffed against the 1.28.0.6 sources on 2026-08-30.
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
    public @NotNull BunqRequestBuilder url(@NotNull HttpUrl url) {
        this.url = url;
        return (BunqRequestBuilder) super.url(url);
    }

    @Override
    public @NotNull BunqRequestBuilder method(@NotNull String method, RequestBody body) {
        RequestBody bodyToPassToSuper;
        if (body instanceof BunqRequestBody) {
            bodyToPassToSuper = ((BunqRequestBody) body).getRequestBody();
        } else if (body == null) {
            bodyToPassToSuper = null;
        } else {
            throw new BunqException(ERROR_BODY_IS_OF_UNEXPECTED_INSTANCE);
        }
        this.method = HttpMethod.createFromMethodString(method.toUpperCase());
        this.body = (BunqRequestBody) body;
        return (BunqRequestBuilder) super.method(method, bodyToPassToSuper);
    }

    @Override
    public @NotNull BunqRequestBuilder url(@NotNull String url) {
        return (BunqRequestBuilder) super.url(url);
    }

    @Override
    public @NotNull BunqRequestBuilder url(@NotNull URL url) {
        return (BunqRequestBuilder) super.url(url);
    }

    private void addToAllHeader(String name, String value) {
        BunqHeader header = BunqHeader.parseHeaderOrNull(name);
        if (header != null) {
            this.allHeader.add(new BunqBasicHeader(header, value));
        }
    }

    @Override
    public @NotNull BunqRequestBuilder header(@NotNull String name, @NotNull String value) {
        addToAllHeader(name, value);
        return (BunqRequestBuilder) super.header(name, value);
    }

    @Override
    public @NotNull BunqRequestBuilder addHeader(@NotNull String name, @NotNull String value) {
        addToAllHeader(name, value);
        return (BunqRequestBuilder) super.addHeader(name, value);
    }

    @Override
    public @NotNull BunqRequestBuilder removeHeader(@NotNull String name) {
        List<BunqBasicHeader> allHeaderToRemove = new ArrayList<>();
        for (BunqBasicHeader basicHeader : this.allHeader) {
            if (basicHeader.getName().equals(name)) {
                allHeaderToRemove.add(basicHeader);
            }
        }
        this.allHeader.removeAll(allHeaderToRemove);
        return (BunqRequestBuilder) super.removeHeader(name);
    }

    @Override
    public @NotNull BunqRequestBuilder cacheControl(@NotNull CacheControl cacheControl) {
        return (BunqRequestBuilder) super.cacheControl(cacheControl);
    }

    @Override
    public @NotNull BunqRequestBuilder get() {
        return (BunqRequestBuilder) super.get();
    }

    @Override
    public @NotNull BunqRequestBuilder head() {
        return (BunqRequestBuilder) super.head();
    }

    @Override
    public @NotNull BunqRequestBuilder post(@NotNull RequestBody body) {
        return (BunqRequestBuilder) super.post(body);
    }

    @Override
    public @NotNull BunqRequestBuilder delete(RequestBody body) {
        return (BunqRequestBuilder) super.delete(body);
    }

    // Note: we intentionally do NOT override delete() without parameters because
    // the method is final in OkHttp5. Callers will use the superclass implementation
    // which internally delegates to delete(RequestBody) with a null body. If an
    // empty body is required, use delete(BunqRequestBody.create(...)).

    @Override
    public @NotNull BunqRequestBuilder put(RequestBody body) {
        return (BunqRequestBuilder) super.put(body);
    }

    @Override
    public @NotNull BunqRequestBuilder patch(@NotNull RequestBody body) {
        return (BunqRequestBuilder) super.patch(body);
    }

    @Override
    public @NotNull BunqRequestBuilder tag(Object tag) {
        return (BunqRequestBuilder) super.tag(tag);
    }
}
