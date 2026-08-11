package org.cef.network;

import org.cef.handler.CefLoadHandler;

import java.util.Map;

/** Lightweight pure-Java response double for the scheme-handler tests. */
public final class RecordingCefResponse extends CefResponse {
    private int status;
    private String statusText = "";
    private String mime = "";
    private Map<String, String> headers = Map.of();

    @Override public void dispose() { }
    @Override public boolean isReadOnly() { return false; }
    @Override public CefLoadHandler.ErrorCode getError() { return CefLoadHandler.ErrorCode.ERR_NONE; }
    @Override public void setError(CefLoadHandler.ErrorCode error) { }
    @Override public int getStatus() { return status; }
    @Override public void setStatus(int status) { this.status = status; }
    @Override public String getStatusText() { return statusText; }
    @Override public void setStatusText(String statusText) { this.statusText = statusText; }
    @Override public String getMimeType() { return mime; }
    @Override public void setMimeType(String mime) { this.mime = mime; }
    @Override public String getHeaderByName(String name) { return headers.get(name); }
    @Override public void setHeaderByName(String name, String value, boolean overwrite) {
        headers = Map.of(name, value);
    }
    @Override public void getHeaderMap(Map<String, String> target) { target.putAll(headers); }
    @Override public void setHeaderMap(Map<String, String> headers) { this.headers = Map.copyOf(headers); }

    public int status() { return status; }
    public String statusText() { return statusText; }
    public String mime() { return mime; }
}
