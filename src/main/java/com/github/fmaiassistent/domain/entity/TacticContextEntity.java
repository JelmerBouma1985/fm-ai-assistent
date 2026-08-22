package com.github.fmaiassistent.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "tactic_context")
public class TacticContextEntity {
    @Id
    private Integer id;

    @Column(name = "file_name", nullable = false, length = 512)
    private String fileName;

    @Lob
    @Column(name = "fmf_data", nullable = false)
    private byte[] fmfData;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "uploaded_at", nullable = false, length = 64)
    private String uploadedAt;

    @Column(nullable = false)
    private boolean enabled;

    protected TacticContextEntity() {
    }

    public TacticContextEntity(String fileName, byte[] fmfData, String fingerprint) {
        this.id = 1;
        this.fileName = fileName;
        this.fmfData = fmfData.clone();
        this.fingerprint = fingerprint;
        this.uploadedAt = OffsetDateTime.now().toString();
        this.enabled = true;
    }

    public Integer getId() { return id; }
    public String getFileName() { return fileName; }
    public byte[] getFmfData() { return fmfData == null ? null : fmfData.clone(); }
    public String getFingerprint() { return fingerprint; }
    public String getUploadedAt() { return uploadedAt; }
    public boolean isEnabled() { return enabled; }
}
