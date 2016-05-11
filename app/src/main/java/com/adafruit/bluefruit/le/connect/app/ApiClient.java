package com.adafruit.bluefruit.le.connect.app;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Created by uriel on 11/05/16.
 */



public class ApiClient {
    private static final String BaseUrl = "http://192.168.0.22:4000/api/v1/";
    private static String getAbsoluteUrl(String relativeUrl) {
        return BaseUrl + relativeUrl;
    }


    public static final MediaType JSON
            = MediaType.parse("application/json; charset=utf-8");

    OkHttpClient client = new OkHttpClient();

    String post(String url, String json) throws IOException {
        RequestBody body = RequestBody.create(JSON, json);
        Request request = new Request.Builder()
                .url(getAbsoluteUrl(url))
                .post(body)
                .build();
        Response response = client.newCall(request).execute();
        return response.body().string();
    }
}
