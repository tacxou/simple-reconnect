package com.simpleplugins.reconnect.util.updater;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class UpdateChecker {

    private String content;
    private String latest;
    private String link;
    private boolean isLatest;

    public UpdateChecker get(String url) {
        this.latest = null;
        this.link = null;

        try {
            URL u = new URI(url).toURL();
            HttpURLConnection connection = (HttpURLConnection) u.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "SimpleReconnect-UpdateChecker");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return this;
            }

            StringBuilder builder = new StringBuilder();
            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }
            }

            content = builder.toString();
            JsonElement root = JsonParser.parseString(content);
            if (!root.isJsonObject()) return this;

            JsonObject obj = root.getAsJsonObject();
            if (obj.has("tag_name") && obj.has("html_url")) {
                this.latest = obj.get("tag_name").getAsString();
                this.link = obj.get("html_url").getAsString();
            }
        } catch (IOException | URISyntaxException ex) {
            return this;
        }

        return this;
    }

    public boolean isLatest(String version) {
        isLatest = version.equalsIgnoreCase(latest);
        return isLatest();
    }

    public boolean isLatest() {
        return isLatest;
    }

    public String getLatest() {
        return latest;
    }

    public String getLink() {
        return link;
    }
}
