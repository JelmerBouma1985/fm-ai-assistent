package com.github.fmaiassistent.repository;

public final class PlayerColumnNames {
    private PlayerColumnNames() {
    }

    public static String toColumnName(String fieldName) {
        if (fieldName.equals("index")) {
            return "player_index";
        }
        if (fieldName.equals("record")) {
            return "record_address";
        }
        StringBuilder out = new StringBuilder();
        char previous = 0;
        for (char ch : fieldName.toCharArray()) {
            if (Character.isUpperCase(ch) && previous != 0 && previous != '_') {
                out.append('_');
            }
            out.append(Character.toLowerCase(ch));
            previous = ch;
        }
        return out.toString();
    }

    public static String toEntityFieldName(String exportFieldName) {
        if (exportFieldName.equals("index")) {
            return "playerIndex";
        }
        if (exportFieldName.equals("record")) {
            return "recordAddress";
        }
        StringBuilder out = new StringBuilder();
        boolean upperNext = false;
        for (int i = 0; i < exportFieldName.length(); i++) {
            char ch = exportFieldName.charAt(i);
            if (ch == '_') {
                upperNext = true;
            } else if (upperNext) {
                out.append(Character.toUpperCase(ch));
                upperNext = false;
            } else if (i == 0) {
                out.append(Character.toLowerCase(ch));
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
