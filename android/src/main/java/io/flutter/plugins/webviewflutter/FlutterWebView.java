// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.webviewflutter;

import android.annotation.TargetApi;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.platform.PlatformView;

import java.util.Collections;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import android.os.Build.VERSION_CODES;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;

public class FlutterWebView implements PlatformView, MethodCallHandler {
    private static final String JS_CHANNEL_NAMES_FIELD = "javascriptChannelNames";
    private final InputAwareWebView webView;
    private final MethodChannel methodChannel;
    private final FlutterWebViewClient flutterWebViewClient;
    private final Handler platformThreadHandler;

    // Verifies that a url opened by `Window.open` has a secure url.
    private class FlutterWebChromeClient extends WebChromeClient {
        @Override
        public boolean onCreateWindow(
                final WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            final WebViewClient webViewClient =
                    new WebViewClient() {
                        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
                        @Override
                        public boolean shouldOverrideUrlLoading(
                                @NonNull WebView view, @NonNull WebResourceRequest request) {
                            final String url = request.getUrl().toString();
                            if (!flutterWebViewClient.shouldOverrideUrlLoading(
                                    FlutterWebView.this.webView, request)) {
                                webView.loadUrl(url);
                            }
                            return true;
                        }

                        @Override
                        public boolean shouldOverrideUrlLoading(WebView view, String url) {
                            if (!flutterWebViewClient.shouldOverrideUrlLoading(
                                    FlutterWebView.this.webView, url)) {
                                webView.loadUrl(url);
                            }
                            return true;
                        }
                    };

            final WebView newWebView = new WebView(view.getContext());
            newWebView.setWebViewClient(webViewClient);

            final WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(newWebView);
            resultMsg.sendToTarget();

            return true;
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            super.onReceivedTitle(view, title);
            String url = view.getUrl();
            Map<String, Object> tempTitle = new HashMap<>();
            tempTitle.put("title", title);
            methodChannel.invokeMethod("onPageChangeTitle", tempTitle);

            Map<String, Object> tempUrl = new HashMap<>();
            tempUrl.put("url", url);
            methodChannel.invokeMethod("onPageJumpURL", tempUrl);
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    @SuppressWarnings("unchecked")
    FlutterWebView(
            final Context context,
            BinaryMessenger messenger,
            int id,
            Map<String, Object> params,
            View containerView) {

        DisplayListenerProxy displayListenerProxy = new DisplayListenerProxy();
        DisplayManager displayManager =
                (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        displayListenerProxy.onPreWebViewInitialization(displayManager);
        webView = new InputAwareWebView(context, containerView);
        displayListenerProxy.onPostWebViewInitialization(displayManager);

        platformThreadHandler = new Handler(context.getMainLooper());
        // Allow local storage.
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);

        // Multi windows is set with FlutterWebChromeClient by default to handle internal bug: b/159892679.
        webView.getSettings().setSupportMultipleWindows(true);
        webView.setWebChromeClient(new FlutterWebChromeClient());

        methodChannel = new MethodChannel(messenger, "plugins.flutter.io/webview_" + id);
        methodChannel.setMethodCallHandler(this);

        flutterWebViewClient = new FlutterWebViewClient(methodChannel);
        Map<String, Object> settings = (Map<String, Object>) params.get("settings");
        if (settings != null) applySettings(settings);

        if (params.containsKey(JS_CHANNEL_NAMES_FIELD)) {
            List<String> names = (List<String>) params.get(JS_CHANNEL_NAMES_FIELD);
            if (names != null) registerJavaScriptChannelNames(names);
        }

        Integer autoMediaPlaybackPolicy = (Integer) params.get("autoMediaPlaybackPolicy");
        if (autoMediaPlaybackPolicy != null) updateAutoMediaPlaybackPolicy(autoMediaPlaybackPolicy);
        if (params.containsKey("userAgent")) {
            String userAgent = (String) params.get("userAgent");
            updateUserAgent(userAgent);
        }

        if (params.containsKey("cookies")) {
            ArrayList<Map<String, String>> cookies = (ArrayList<Map<String, String>>) params.get("cookies");
            loadCookies(params, cookies);
        }

        setLoadUrl(params, true);
    }

    @Override
    public View getView() {
        return webView;
    }

    // @Override
    // This is overriding a method that hasn't rolled into stable Flutter yet. Including the
    // annotation would cause compile time failures in versions of Flutter too old to include the new
    // method. However leaving it raw like this means that the method will be ignored in old versions
    // of Flutter but used as an override anyway wherever it's actually defined.
    // TODO(mklim): Add the @Override annotation once flutter/engine#9727 rolls to stable.
    public void onInputConnectionUnlocked() {
        webView.unlockInputConnection();
    }

    // @Override
    // This is overriding a method that hasn't rolled into stable Flutter yet. Including the
    // annotation would cause compile time failures in versions of Flutter too old to include the new
    // method. However leaving it raw like this means that the method will be ignored in old versions
    // of Flutter but used as an override anyway wherever it's actually defined.
    // TODO(mklim): Add the @Override annotation once flutter/engine#9727 rolls to stable.
    public void onInputConnectionLocked() {
        webView.lockInputConnection();
    }

    // @Override
    // This is overriding a method that hasn't rolled into stable Flutter yet. Including the
    // annotation would cause compile time failures in versions of Flutter too old to include the new
    // method. However leaving it raw like this means that the method will be ignored in old versions
    // of Flutter but used as an override anyway wherever it's actually defined.
    // TODO(mklim): Add the @Override annotation once stable passes v1.10.9.
    public void onFlutterViewAttached(View flutterView) {
        webView.setContainerView(flutterView);
    }

    // @Override
    // This is overriding a method that hasn't rolled into stable Flutter yet. Including the
    // annotation would cause compile time failures in versions of Flutter too old to include the new
    // method. However leaving it raw like this means that the method will be ignored in old versions
    // of Flutter but used as an override anyway wherever it's actually defined.
    // TODO(mklim): Add the @Override annotation once stable passes v1.10.9.
    public void onFlutterViewDetached() {
        webView.setContainerView(null);
    }

    @Override
    public void onMethodCall(MethodCall methodCall, Result result) {
        switch (methodCall.method) {
            case "loadUrl":
                loadUrl(methodCall, result);
                break;
            case "updateSettings":
                updateSettings(methodCall, result);
                break;
            case "updateCookies":
                uploadCookies(methodCall, result);
                break;
            case "canGoBack":
                canGoBack(result);
                break;
            case "canGoForward":
                canGoForward(result);
                break;
            case "goBack":
                goBack(result);
                break;
            case "goForward":
                goForward(result);
                break;
            case "reload":
                reload(result);
                break;
            case "currentUrl":
                currentUrl(result);
                break;
            case "evaluateJavascript":
                evaluateJavaScript(methodCall, result);
                break;
            case "addJavascriptChannels":
                addJavaScriptChannels(methodCall, result);
                break;
            case "removeJavascriptChannels":
                removeJavaScriptChannels(methodCall, result);
                break;
            case "clearCache":
                clearCache(result);
                break;
            case "getTitle":
                getTitle(result);
                break;
            case "scrollTo":
                scrollTo(methodCall, result);
                break;
            case "scrollBy":
                scrollBy(methodCall, result);
                break;
            case "getScrollX":
                getScrollX(result);
                break;
            case "getScrollY":
                getScrollY(result);
                break;
            default:
                result.notImplemented();
        }
    }

    @SuppressWarnings("unchecked")
    private void loadUrl(MethodCall methodCall, Result result) {
        Map<String, Object> request = (Map<String, Object>) methodCall.arguments;
        String url = (String) request.get("url");
        Map<String, String> headers = (Map<String, String>) request.get("headers");
        if (headers == null) {
            headers = Collections.emptyMap();
        }
        webView.loadUrl(url, headers);
        result.success(null);
    }

    private void canGoBack(Result result) {
        result.success(webView.canGoBack());
    }

    private void canGoForward(Result result) {
        result.success(webView.canGoForward());
    }

    private void goBack(Result result) {
        if (webView.canGoBack()) {
            webView.goBack();
        }
        result.success(null);
    }

    private void goForward(Result result) {
        if (webView.canGoForward()) {
            webView.goForward();
        }
        result.success(null);
    }

    private void reload(Result result) {
        webView.reload();
        result.success(null);
    }

    private void currentUrl(Result result) {
        result.success(webView.getUrl());
    }

    @SuppressWarnings("unchecked")
    private void updateSettings(MethodCall methodCall, Result result) {
        applySettings((Map<String, Object>) methodCall.arguments);
        result.success(null);
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void evaluateJavaScript(MethodCall methodCall, final Result result) {
        String jsString = (String) methodCall.arguments;
        if (jsString == null) {
            throw new UnsupportedOperationException("JavaScript string cannot be null");
        }
        webView.evaluateJavascript(
                jsString,
                new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        result.success(value);
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private void addJavaScriptChannels(MethodCall methodCall, Result result) {
        List<String> channelNames = (List<String>) methodCall.arguments;
        registerJavaScriptChannelNames(channelNames);
        result.success(null);
    }

    @SuppressWarnings("unchecked")
    private void removeJavaScriptChannels(MethodCall methodCall, Result result) {
        List<String> channelNames = (List<String>) methodCall.arguments;
        for (String channelName : channelNames) {
            webView.removeJavascriptInterface(channelName);
        }
        result.success(null);
    }

    private void clearCache(Result result) {
        webView.clearCache(true);
        WebStorage.getInstance().deleteAllData();
        result.success(null);
    }

    private void getTitle(Result result) {
        result.success(webView.getTitle());
    }

    private void scrollTo(MethodCall methodCall, Result result) {
        Map<String, Object> request = methodCall.arguments();
        int x = (int) request.get("x");
        int y = (int) request.get("y");

        webView.scrollTo(x, y);

        result.success(null);
    }

    private void scrollBy(MethodCall methodCall, Result result) {
        Map<String, Object> request = methodCall.arguments();
        int x = (int) request.get("x");
        int y = (int) request.get("y");

        webView.scrollBy(x, y);
        result.success(null);
    }

    private void getScrollX(Result result) {
        result.success(webView.getScrollX());
    }

    private void getScrollY(Result result) {
        result.success(webView.getScrollY());
    }

    private void applySettings(Map<String, Object> settings) {
        for (String key : settings.keySet()) {
            switch (key) {
                case "jsMode":
                    Integer mode = (Integer) settings.get(key);
                    if (mode != null) updateJsMode(mode);
                    break;
                case "hasNavigationDelegate":
                    final boolean hasNavigationDelegate = (boolean) settings.get(key);

                    final WebViewClient webViewClient =
                            flutterWebViewClient.createWebViewClient(hasNavigationDelegate);

                    webView.setWebViewClient(webViewClient);
                    break;
                case "debuggingEnabled":
                    final boolean debuggingEnabled = (boolean) settings.get(key);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        webView.setWebContentsDebuggingEnabled(debuggingEnabled);
                    }
                    break;
                case "gestureNavigationEnabled":
                    break;
                case "userAgent":
                    updateUserAgent((String) settings.get(key));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown WebView setting: " + key);
            }
        }
    }

    private void updateJsMode(int mode) {
        switch (mode) {
            case 0: // disabled
                webView.getSettings().setJavaScriptEnabled(false);
                break;
            case 1: // unrestricted
                webView.getSettings().setJavaScriptEnabled(true);
                break;
            default:
                throw new IllegalArgumentException("Trying to set unknown JavaScript mode: " + mode);
        }
    }

    private void updateAutoMediaPlaybackPolicy(int mode) {
        // This is the index of the AutoMediaPlaybackPolicy enum, index 1 is always_allow, for all
        // other values we require a user gesture.
        boolean requireUserGesture = mode != 1;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            webView.getSettings().setMediaPlaybackRequiresUserGesture(requireUserGesture);
        }
    }

    private void registerJavaScriptChannelNames(List<String> channelNames) {
        for (String channelName : channelNames) {
            webView.addJavascriptInterface(
                    new JavaScriptChannel(methodChannel, channelName, platformThreadHandler), channelName);
        }
    }

    //更新userAgent
    private void updateUserAgent(String userAgent) {
        webView.getSettings().setUserAgentString(userAgent);
    }

    //加载cookies
    private void loadCookies(final Map<String, Object> params, final ArrayList<Map<String, String>> list) {
        if (list == null) {
            setLoadUrl(params, false);
            return;
        }
        CookieManager cookieManager = CookieManager.getInstance();
        if (Build.VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
            cookieManager.removeAllCookies(new ValueCallback<Boolean>() {
                @Override
                public void onReceiveValue(Boolean value) {
                    setCookies(list);
                    setLoadUrl(params, false);
                }
            });
        } else {
            cookieManager.removeAllCookie();
            setCookies(list);
        }
    }

    //设置cookies
    private void setCookies(final ArrayList<Map<String, String>> list) {
        CookieManager cookieManager = CookieManager.getInstance();
        for (Map<String, String> map : list) {
            String domain = map.get("domain");
            String path = map.get("path");
            String name = map.get("name");
            String value = map.get("value");
            cookieManager.setCookie(domain, "path=" + path);
            cookieManager.setCookie(domain, name + "=" + value);
        }
        cookieManager.flush();
    }

    //更新cookies
    private void uploadCookies(MethodCall methodCall, Result result) {
        ArrayList<Map<String, String>> list = (ArrayList<Map<String, String>>) methodCall.arguments;
        if (list == null) return;
        setCookies(list);
        webView.reload();
        result.success(null);
    }

    //加载url
    private void setLoadUrl(Map<String, Object> params, boolean isInit) {
        //兼容5.0以上设置cookies(5.0以上cookies设置是异步的，不这样设置会造成第一次cookie 不被页面抓取到)
        if (Build.VERSION.SDK_INT < VERSION_CODES.LOLLIPOP || !isInit) {
            if (params.containsKey("initialUrl")) {
                String url = (String) params.get("initialUrl");
                webView.loadUrl(url);
            }
        }
    }

    @Override
    public void dispose() {
        methodChannel.setMethodCallHandler(null);
        webView.dispose();
        webView.destroy();
    }
}
