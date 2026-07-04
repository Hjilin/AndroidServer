package com.zm920.androidserver.ui.widget;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RedisClient {
    private final String host;
    private final int port;

    public RedisClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public List<String> keys(String pattern) throws Exception {
        Object reply = send("KEYS", pattern == null ? "*" : pattern);
        List<String> result = new ArrayList<>();
        if (reply instanceof List) {
            for (Object item : (List<?>) reply) result.add(String.valueOf(item));
        }
        return result;
    }

    public String get(String key) throws Exception {
        Object reply = send("GET", key);
        return reply == null ? null : String.valueOf(reply);
    }

    public String dbsize() throws Exception {
        Object reply = send("DBSIZE");
        return reply == null ? "0" : String.valueOf(reply);
    }

    public String flushAll() throws Exception {
        Object reply = send("FLUSHALL");
        return reply == null ? "" : String.valueOf(reply);
    }

    private Object send(String... args) throws Exception {
        Socket socket = new Socket(host, port);
        socket.setSoTimeout(5000);
        BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        out.write(encode(args));
        out.flush();
        Object reply = parseReply(in);
        in.close();
        out.close();
        socket.close();
        return reply;
    }

    private byte[] encode(String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            sb.append("$").append(bytes.length).append("\r\n");
            sb.append(arg).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Object parseReply(BufferedInputStream in) throws Exception {
        int type = in.read();
        if (type == -1) throw new RuntimeException("Redis 无响应");
        switch (type) {
            case '+':
                return readLine(in);
            case '-':
                throw new RuntimeException(readLine(in));
            case ':':
                return readLine(in);
            case '$': {
                int len = Integer.parseInt(readLine(in));
                if (len < 0) return null;
                byte[] data = in.readNBytes(len);
                in.read(); in.read();
                return new String(data, StandardCharsets.UTF_8);
            }
            case '*': {
                int count = Integer.parseInt(readLine(in));
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < count; i++) list.add(parseReply(in));
                return list;
            }
            default:
                throw new RuntimeException("未知 Redis 响应类型: " + (char) type);
        }
    }

    private String readLine(BufferedInputStream in) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int prev = -1;
        int cur;
        while ((cur = in.read()) != -1) {
            if (prev == '\r' && cur == '\n') break;
            if (prev != -1) baos.write(prev);
            prev = cur;
        }
        return baos.toString(StandardCharsets.UTF_8.name());
    }
}
