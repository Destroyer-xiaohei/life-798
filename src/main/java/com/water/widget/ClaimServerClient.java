package com.water.widget;

import android.content.Context;
import android.content.SharedPreferences;

import com.water.widget.ui.ClaimServerUi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * 自建积分托管服务端（server/app.py）客户端。
 *
 * 服务端地址与 ApiKey 保存在 SharedPreferences；所有方法都在后台线程执行，
 * 失败只通过 err 回调，不抛异常，方便界面直接提示。
 */
public class ClaimServerClient {
    private static final String PREFS = "claim_server_cfg";
    private static final String KEY_URL = "base_url";
    private static final String KEY_API = "api_key";
    private static final int CONNECT_TIMEOUT = 12_000;
    private static final int READ_TIMEOUT = 30_000;

    public interface JsonCallback {
        void onResult(JSONObject json, String err);
    }

    private ClaimServerClient() {}

    // ====== 配置 ======

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String baseUrl(Context ctx) {
        return prefs(ctx).getString(KEY_URL, "");
    }

    public static String apiKey(Context ctx) {
        return prefs(ctx).getString(KEY_API, "");
    }

    public static boolean isConfigured(Context ctx) {
        return !baseUrl(ctx).isEmpty() && !apiKey(ctx).isEmpty();
    }

    /** 保存服务端配置，地址会先做规范化。 */
    public static void save(Context ctx, String url, String key) {
        String normalized = ClaimServerUi.INSTANCE.normalizeUrl(url);
        prefs(ctx).edit()
                .putString(KEY_URL, normalized)
                .putString(KEY_API, key == null ? "" : key.trim())
                .apply();
    }

    // ====== 业务接口 ======

    public static void health(Context ctx, JsonCallback cb) {
        get(ctx, "/api/health", null, cb);
    }

    public static void accounts(Context ctx, JsonCallback cb) {
        get(ctx, "/api/accounts", null, cb);
    }

    public static void logs(Context ctx, Integer accountId, int limit, JsonCallback cb) {
        StringBuilder query = new StringBuilder("?limit=").append(Math.max(1, limit));
        if (accountId != null && accountId > 0) {
            query.append("&account_id=").append(accountId);
        }
        get(ctx, "/api/logs", query.toString(), cb);
    }

    public static void runs(Context ctx, Integer accountId, int limit, JsonCallback cb) {
        StringBuilder query = new StringBuilder("?limit=").append(Math.max(1, limit));
        if (accountId != null && accountId > 0) {
            query.append("&account_id=").append(accountId);
        }
        get(ctx, "/api/runs", query.toString(), cb);
    }

    public static void deleteAccount(Context ctx, int accountId, JsonCallback cb) {
        delete(ctx, "/api/accounts/" + accountId, cb);
    }

    /** accountId 为空表示所有账号。 */
    public static void triggerRun(Context ctx, Integer accountId, JsonCallback cb) {
        JSONObject body = new JSONObject();
        if (accountId != null && accountId > 0) {
            try {
                body.put("account_id", accountId);
            } catch (Exception ignored) {
            }
        }
        post(ctx, "/api/run", body, cb);
    }

    /**
     * 把本机账号（token / uid / eid / 手机号）同步到服务端。
     * 串行推送避免并发风暴；结果汇总为 {pushed, failed, total, errors[]}。
     */
    public static void pushAccounts(Context ctx, final List<Account> accounts,
                                    final JsonCallback cb) {
        final String base = baseUrl(ctx);
        final String key = apiKey(ctx);
        new Thread(() -> {
            if (base.isEmpty() || key.isEmpty()) {
                cb.onResult(null, "未配置服务端地址或 ApiKey");
                return;
            }
            JSONArray errors = new JSONArray();
            int pushed = 0;
            int total = 0;
            if (accounts != null) {
                for (Account account : accounts) {
                    if (account == null) continue;
                    String token = account.token == null ? "" : account.token.trim();
                    String appToken = account.appToken == null ? "" : account.appToken.trim();
                    String uid = account.uid == null ? "" : account.uid.trim();
                    // 设备登录只有 appToken 的账号也要同步，服务端会把两套 token 的任务合并。
                    if ((token.isEmpty() && appToken.isEmpty()) || uid.isEmpty()) continue;
                    total++;
                    JSONObject body = new JSONObject();
                    try {
                        String phone = account.phone == null ? "" : account.phone;
                        String name = account.name == null || account.name.isEmpty()
                                ? phone : account.name;
                        body.put("phone", phone);
                        body.put("token", token);
                        body.put("app_token", appToken);
                        body.put("uid", uid);
                        body.put("eid", account.eid == null ? "" : account.eid);
                        body.put("name", name);
                    } catch (Exception ignored) {
                    }
                    try {
                        JSONObject resp = request("POST", base, key, "/api/accounts",
                                body, CONNECT_TIMEOUT, READ_TIMEOUT);
                        if (resp != null && resp.optInt("code", -999) == 0) {
                            pushed++;
                        } else {
                            errors.put(ClaimServerUi.INSTANCE.messageOf(resp, "同步失败"));
                        }
                    } catch (Exception error) {
                        errors.put(String.valueOf(error.getMessage()));
                    }
                }
            }
            JSONObject summary = new JSONObject();
            try {
                JSONObject data = new JSONObject();
                data.put("pushed", pushed);
                data.put("failed", total - pushed);
                data.put("total", total);
                data.put("errors", errors);
                summary.put("code", 0);
                summary.put("msg", "已同步 " + pushed + "/" + total + " 个账号");
                summary.put("data", data);
            } catch (Exception ignored) {
            }
            cb.onResult(summary, null);
        }).start();
    }

    // ====== 通用请求 ======

    private static void get(final Context ctx, final String path, final String query,
                            final JsonCallback cb) {
        final String base = baseUrl(ctx);
        final String key = apiKey(ctx);
        new Thread(() -> {
            if (base.isEmpty() || key.isEmpty()) {
                cb.onResult(null, "未配置服务端地址或 ApiKey");
                return;
            }
            try {
                cb.onResult(request("GET", base, key, path + (query == null ? "" : query),
                        null, CONNECT_TIMEOUT, READ_TIMEOUT), null);
            } catch (Exception error) {
                cb.onResult(null, String.valueOf(error.getMessage()));
            }
        }).start();
    }

    private static void post(final Context ctx, final String path, final JSONObject body,
                             final JsonCallback cb) {
        final String base = baseUrl(ctx);
        final String key = apiKey(ctx);
        new Thread(() -> {
            if (base.isEmpty() || key.isEmpty()) {
                cb.onResult(null, "未配置服务端地址或 ApiKey");
                return;
            }
            try {
                cb.onResult(request("POST", base, key, path, body,
                        CONNECT_TIMEOUT, READ_TIMEOUT), null);
            } catch (Exception error) {
                cb.onResult(null, String.valueOf(error.getMessage()));
            }
        }).start();
    }

    private static void delete(final Context ctx, final String path, final JsonCallback cb) {
        final String base = baseUrl(ctx);
        final String key = apiKey(ctx);
        new Thread(() -> {
            if (base.isEmpty() || key.isEmpty()) {
                cb.onResult(null, "未配置服务端地址或 ApiKey");
                return;
            }
            try {
                cb.onResult(request("DELETE", base, key, path, null,
                        CONNECT_TIMEOUT, READ_TIMEOUT), null);
            } catch (Exception error) {
                cb.onResult(null, String.valueOf(error.getMessage()));
            }
        }).start();
    }

    private static JSONObject request(String method, String base, String key, String path,
                                      JSONObject body, int connectTimeout, int readTimeout)
            throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(base + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);
            connection.setRequestProperty("X-Api-Key", key);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                OutputStream output = connection.getOutputStream();
                output.write(body.toString().getBytes("UTF-8"));
                output.flush();
                output.close();
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream()
                    : connection.getInputStream();
            StringBuilder text = new StringBuilder();
            if (stream != null) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream, "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) text.append(line);
                reader.close();
            }
            if (text.length() == 0) {
                throw new IllegalStateException("服务端无响应 (HTTP " + code + ")");
            }
            return new JSONObject(text.toString());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

}
