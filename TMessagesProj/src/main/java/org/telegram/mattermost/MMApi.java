package org.telegram.mattermost;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal blocking Mattermost REST v4 client. All calls must be made off the UI thread.
 */
public class MMApi {

    public static class MMException extends IOException {
        public final int status;
        public final String serverId;

        public MMException(int status, String message, String serverId) {
            super(message);
            this.status = status;
            this.serverId = serverId;
        }
    }

    public static class LoginResult {
        public String token;
        public JSONObject user;
    }

    private final String server;
    private volatile String token;

    public MMApi(String server, String token) {
        this.server = server;
        this.token = token;
    }

    public String getServer() {
        return server;
    }

    public String getToken() {
        return token;
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private HttpURLConnection open(String method, String path) throws IOException {
        URL url = new URL(server + "/api/v4" + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        c.setRequestProperty("User-Agent", "mm-tg/1.0 (Android)");
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        return c;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) > 0) {
            bos.write(buf, 0, r);
        }
        in.close();
        return bos.toByteArray();
    }

    private Object request(String method, String path, Object body) throws IOException {
        HttpURLConnection c = open(method, path);
        try {
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(data.length);
                OutputStream os = c.getOutputStream();
                os.write(data);
                os.close();
            }
            return parse(c);
        } finally {
            c.disconnect();
        }
    }

    private Object parse(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = new String(readAll(in), StandardCharsets.UTF_8);
        if (code >= 400) {
            String msg = "HTTP " + code;
            String id = null;
            try {
                JSONObject o = new JSONObject(text);
                msg = o.optString("message", msg);
                id = o.optString("id", null);
            } catch (Exception ignore) {
            }
            throw new MMException(code, msg, id);
        }
        if (text.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONTokener(text).nextValue();
        } catch (JSONException e) {
            throw new IOException("Bad JSON from server: " + e.getMessage());
        }
    }

    public JSONObject getObject(String path) throws IOException {
        Object o = request("GET", path, null);
        return o instanceof JSONObject ? (JSONObject) o : new JSONObject();
    }

    public JSONArray getArray(String path) throws IOException {
        Object o = request("GET", path, null);
        return o instanceof JSONArray ? (JSONArray) o : new JSONArray();
    }

    public Object post(String path, Object body) throws IOException {
        return request("POST", path, body == null ? new JSONObject() : body);
    }

    public Object put(String path, Object body) throws IOException {
        return request("PUT", path, body == null ? new JSONObject() : body);
    }

    public Object delete(String path) throws IOException {
        return request("DELETE", path, null);
    }

    public LoginResult login(String loginId, String password, String mfa) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("login_id", loginId);
            body.put("password", password);
            if (mfa != null && !mfa.isEmpty()) {
                body.put("token", mfa);
            }
            body.put("device_id", "");
        } catch (JSONException ignore) {
        }
        HttpURLConnection c = open("POST", "/users/login");
        try {
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(data.length);
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.close();
            Object res = parse(c);
            LoginResult r = new LoginResult();
            r.token = c.getHeaderField("Token");
            r.user = (JSONObject) res;
            if (r.token == null) {
                throw new IOException("Server did not return a session token");
            }
            token = r.token;
            return r;
        } finally {
            c.disconnect();
        }
    }

    /**
     * Download a byte range of a resource. Returns fewer bytes than requested at end of file.
     */
    public byte[] getBytes(String path, long offset, int limit) throws IOException {
        HttpURLConnection c = open("GET", path);
        try {
            if (limit > 0) {
                c.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + limit - 1));
            }
            int code = c.getResponseCode();
            if (code == 416) {
                return new byte[0];
            }
            if (code >= 400) {
                readAll(c.getErrorStream());
                throw new MMException(code, "HTTP " + code, null);
            }
            InputStream in = c.getInputStream();
            if (code == 200 && offset > 0) {
                // server ignored Range: skip manually
                long skipped = 0;
                while (skipped < offset) {
                    long s = in.skip(offset - skipped);
                    if (s <= 0) {
                        if (in.read() < 0) {
                            return new byte[0];
                        }
                        s = 1;
                    }
                    skipped += s;
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(limit > 0 ? limit : 65536);
            byte[] buf = new byte[16384];
            int r;
            while ((limit <= 0 || bos.size() < limit) && (r = in.read(buf, 0, limit > 0 ? Math.min(buf.length, limit - bos.size()) : buf.length)) > 0) {
                bos.write(buf, 0, r);
            }
            in.close();
            return bos.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    public JSONObject uploadFile(String channelId, File file, String name, String mime) throws IOException {
        String boundary = "----mmtg" + System.currentTimeMillis();
        HttpURLConnection c = open("POST", "/files");
        try {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            byte[] head = ("--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"channel_id\"\r\n\r\n" + channelId + "\r\n" +
                    "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"files\"; filename=\"" + name.replace("\"", "") + "\"\r\n" +
                    "Content-Type: " + (mime == null ? "application/octet-stream" : mime) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(head.length + file.length() + tail.length);
            OutputStream os = c.getOutputStream();
            os.write(head);
            FileInputStream fis = new FileInputStream(file);
            byte[] buf = new byte[65536];
            int r;
            while ((r = fis.read(buf)) > 0) {
                os.write(buf, 0, r);
            }
            fis.close();
            os.write(tail);
            os.close();
            Object res = parse(c);
            JSONObject o = (JSONObject) res;
            JSONArray infos = o.optJSONArray("file_infos");
            if (infos == null || infos.length() == 0) {
                throw new IOException("Upload failed");
            }
            return infos.optJSONObject(0);
        } finally {
            c.disconnect();
        }
    }
}
