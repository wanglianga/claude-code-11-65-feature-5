package com.agri.pit.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;

@Component
public class Db {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public Db(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("insert did not return a generated id: " + sql);
        }
        return key.longValue();
    }

    public Object jsonArg(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return toJson(node);
    }

    public String toJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node == null ? NullNode.getInstance() : node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize json", e);
        }
    }

    public JsonNode readTree(String value) {
        if (value == null) {
            return NullNode.getInstance();
        }
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot parse stored json", e);
        }
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
