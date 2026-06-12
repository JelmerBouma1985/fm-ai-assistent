package com.github.fmaiassistent.domain.enums;

import java.math.BigDecimal;

public enum MoneyCurrency {
    POUND("pound", "Pound", "£", BigDecimal.ONE),
    DOLLAR("dollar", "Dollar", "$", BigDecimal.valueOf(1.33)),
    EURO("euro", "Euro", "€", BigDecimal.valueOf(1.16));

    private final String propertyValue;
    private final String label;
    private final String symbol;
    private final BigDecimal rateFromPounds;

    MoneyCurrency(String propertyValue, String label, String symbol, BigDecimal rateFromPounds) {
        this.propertyValue = propertyValue;
        this.label = label;
        this.symbol = symbol;
        this.rateFromPounds = rateFromPounds;
    }

    public String propertyValue() {
        return propertyValue;
    }

    public String label() {
        return label;
    }

    public String symbol() {
        return symbol;
    }

    public BigDecimal rateFromPounds() {
        return rateFromPounds;
    }

    public static MoneyCurrency fromPropertyValue(String value) {
        if (value == null || value.isBlank()) {
            return POUND;
        }
        for (MoneyCurrency currency : values()) {
            if (currency.propertyValue.equalsIgnoreCase(value.trim()) || currency.name().equalsIgnoreCase(value.trim())) {
                return currency;
            }
        }
        return POUND;
    }
}
