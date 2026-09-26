package com.bunq.sdk.http;

import com.bunq.sdk.exception.BunqException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import okhttp3.CacheControl;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.RequestBody;

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
 * <h2>Where it lives now, and what that changed (steward/109)</h2>
 * It was in {@code discord-bot} until 2026-09-18, where JDA and the SDK genuinely shared a
 * classpath. bunq moved to {@code steward-worker}, and <b>there is no JDA here</b>: measured on
 * 2026-09-18, {@code :steward-worker:dependencies --configuration runtimeClasspath} resolves
 * {@code com.squareup.okhttp3:okhttp:3.14.9}, brought by the SDK itself and by nothing else. So the
 * conflict this class was written for does not exist in this module today.
 *
 * <p><b>It is kept anyway, deliberately.</b> It is compatible with both majors - every method it
 * overrides is non-final and identically shaped in 3.14.9 - so what it costs is a file and what it
 * buys is that the module does not depend on nothing ever putting OkHttp 5 on this classpath. A
 * shim that only matters under a condition that is currently false is still cheaper than the
 * {@code VerifyError} at class load that its absence produced once.</p>
 *
 * <p><b>The one behavioural consequence, written down rather than discovered:</b> on OkHttp 3 the
 * inherited no-argument {@code delete()} calls {@code delete(Util.EMPTY_REQUEST)}, whose body is not
 * a {@link BunqRequestBody}, so {@link #method(String, RequestBody)} below throws
 * {@code BunqException}. Nothing in this repository reaches it - {@code BunqGateway} makes exactly
 * four kinds of call (create, get, list, update: POST, GET, PUT) and never a DELETE - but a fifth
 * one that did would fail here rather than at bunq.</p>
 *
 * <h2>Rules</h2>
 * <b>Do not delete this file.</b> <b>Re-check it against the SDK's own sources on any bunq SDK or
 * OkHttp bump</b>: it is a copy, so a fix upstream does not reach us, and a change upstream that we
 * do not mirror silently reverts to old behaviour. Diffed against the 1.28.0.6 sources on
 * 2026-08-30.
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
    public BunqRequestBuilder url(HttpUrl url) {
        this.url = url;
        return (BunqRequestBuilder) super.url(url);
    }

    @Override
    public BunqRequestBuilder method(String method, RequestBody body) {
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
    public BunqRequestBuilder url(String url) {
        return (BunqRequestBuilder) super.url(url);
    }

    @Override
    public BunqRequestBuilder url(URL url) {
        return (BunqRequestBuilder) super.url(url);
    }

    private void addToAllHeader(String name, String value) {
        BunqHeader header = BunqHeader.parseHeaderOrNull(name);
        if (header != null) {
            this.allHeader.add(new BunqBasicHeader(header, value));
        }
    }

    @Override
    public BunqRequestBuilder header(String name, String value) {
        addToAllHeader(name, value);
        return (BunqRequestBuilder) super.header(name, value);
    }

    @Override
    public BunqRequestBuilder addHeader(String name, String value) {
        addToAllHeader(name, value);
        return (BunqRequestBuilder) super.addHeader(name, value);
    }

    @Override
    public BunqRequestBuilder removeHeader(String name) {
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
    public BunqRequestBuilder cacheControl(CacheControl cacheControl) {
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
    public BunqRequestBuilder post(RequestBody body) {
        return (BunqRequestBuilder) super.post(body);
    }

    @Override
    public BunqRequestBuilder delete(RequestBody body) {
        return (BunqRequestBuilder) super.delete(body);
    }

    // Note: we intentionally do NOT override delete() without parameters because
    // the method is final in OkHttp5. Callers will use the superclass implementation
    // which internally delegates to delete(RequestBody) with a null body. If an
    // empty body is required, use delete(BunqRequestBody.create(...)).

    @Override
    public BunqRequestBuilder put(RequestBody body) {
        return (BunqRequestBuilder) super.put(body);
    }

    @Override
    public BunqRequestBuilder patch(RequestBody body) {
        return (BunqRequestBuilder) super.patch(body);
    }

    @Override
    public BunqRequestBuilder tag(Object tag) {
        return (BunqRequestBuilder) super.tag(tag);
    }
}
