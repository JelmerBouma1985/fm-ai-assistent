package com.github.fmaiassistent.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "load_metadata")
public class LoadMetadataEntity {
    @Id
    @Column(name = "meta_key", length = 100)
    private String key;

    @Column(name = "meta_value", length = 1024)
    private String value;

    protected LoadMetadataEntity() {
    }

    public LoadMetadataEntity(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }
}
