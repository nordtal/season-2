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
 * Replacement for the Bunq SDK builder that is compatible with OkHttp5.
 * The original class overrides Request.Builder#delete(), which became final
 * in newer OkHttp versions. This implementation avoids overriding that method
 * and delegates to the superclass instead.
 * Needed because JDA uses OkHttp5 alongside the Bunq SDK.
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
