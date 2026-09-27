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
 * A patched copy of {@code com.bunq.sdk.http.BunqRequestBuilder}, from {@code com.github.bunq:sdk_java}.
 *
 * It sits in the library's own package so it wins on the classpath.
 *
 * Why it exists: JDA pulls OkHttp 5 and the bunq SDK is written against OkHttp 3. Two things broke, and both are in
 * this one class upstream:
 *
 * - {@code Request.Builder.delete()} - the no-argument overload - is {@code final} in OkHttp 5, and the SDK's
 * original class overrides exactly that method. That is a {@code VerifyError} at class load, not a compile error you
 * would notice.
 *
 * - {@code okhttp3.internal.Util} no longer exists, and the original used it.
 *
 * This version does not override {@code delete()} - it delegates to the superclass - and does not touch OkHttp
 * internals.
 *
 * Where it lives now, and what that changed: bunq lives in {@code steward-worker}, and there is no JDA here:
 * {@code :steward-worker:dependencies --configuration runtimeClasspath} resolves
 * {@code com.squareup.okhttp3:okhttp:3.14.9}, brought by the SDK itself and by nothing else. So the conflict this
 * class was written for does not exist in this module today.
 *
 * It is kept anyway, deliberately. It is compatible with both majors - every method it overrides is non-final and
 * identically shaped in 3.14.9 - so what it costs is a file and what it buys is that the module does not depend on
 * nothing ever putting OkHttp 5 on this classpath. A shim that only matters under a condition that is currently
 * false is still cheaper than the {@code VerifyError} at class load that its absence produced once.
 *
 * The one behavioural consequence, written down rather than discovered: on OkHttp 3 the inherited no-argument
 * {@code delete()} calls {@code delete(Util.EMPTY_REQUEST)}, whose body is not a {@link BunqRequestBody}, so
 * {@link #method(String, RequestBody)} below throws {@code BunqException}. Nothing in this repository reaches it -
 * {@code BunqGateway} makes exactly four kinds of call (create, get, list, update: POST, GET, PUT) and never a
 * DELETE - but a fifth one that did would fail here rather than at bunq.
 *
 * Rules: Do not delete this file. Re-check it against the SDK's own sources on any bunq SDK or OkHttp bump: it is a
 * copy, so a fix upstream does not reach us, and a change upstream that we do not mirror silently reverts to old
 * behaviour.
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

    // delete() with no parameters is final in OkHttp 5; callers fall back to super, which passes a null body.

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
